use std::collections::HashMap;
use std::io;
use std::os::fd::{AsFd, AsRawFd, FromRawFd, OwnedFd};
use std::pin::Pin;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::Duration;

use futures_util::StreamExt;
use silentinput_core::config::{load_config, SilentInputConfig};
use tokio::io::unix::AsyncFd;
use tokio::io::{
    AsyncBufRead, AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader,
    ReadBuf,
};
use zbus::zvariant::{ObjectPath, OwnedObjectPath, OwnedValue, Value};
use zbus::{Connection, MatchRule, MessageStream};

use crate::auth_limiter::AuthRateLimiter;
use crate::input::{InputController, MouseHoldGuard};
use crate::protocol::{process_message, SessionContext, MAX_PENDING_PASTE_TASKS};
use crate::ws_server::MAX_WS_FRAME_SIZE;

pub const SPP_UUID: &str = "00001101-0000-1000-8000-00805f9b34fb";
pub const PROFILE_PATH: &str = "/org/silentinput/btprofile";
pub const BT_IDLE_TIMEOUT_SECS: u64 = 300;

/// Async stream wrapper around a non-blocking Unix/RFCOMM socket file descriptor.
pub struct AsyncRfcommStream {
    inner: AsyncFd<OwnedFd>,
}

impl AsyncRfcommStream {
    pub fn from_owned_fd(fd: OwnedFd) -> io::Result<Self> {
        let raw = fd.as_raw_fd();
        unsafe {
            let flags = libc::fcntl(raw, libc::F_GETFL);
            if flags < 0 {
                return Err(io::Error::last_os_error());
            }
            if libc::fcntl(raw, libc::F_SETFL, flags | libc::O_NONBLOCK) < 0 {
                return Err(io::Error::last_os_error());
            }
        }
        Ok(Self {
            // SAFETY: `fd` is an `OwnedFd` exclusively owned here and kept open for the lifetime of `AsyncFd`.
            inner: unsafe { AsyncFd::register(fd)? },
        })
    }
}

impl AsyncRead for AsyncRfcommStream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        loop {
            let mut guard = match self.inner.poll_read_ready(cx) {
                Poll::Ready(Ok(g)) => g,
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            };

            let unfilled = buf.initialize_unfilled();
            let fd = self.inner.as_raw_fd();
            let res = unsafe {
                libc::read(
                    fd,
                    unfilled.as_mut_ptr() as *mut libc::c_void,
                    unfilled.len(),
                )
            };
            if res >= 0 {
                buf.advance(res as usize);
                return Poll::Ready(Ok(()));
            }
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::WouldBlock {
                guard.clear_ready();
                continue;
            }
            return Poll::Ready(Err(err));
        }
    }
}

impl AsyncWrite for AsyncRfcommStream {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        loop {
            let mut guard = match self.inner.poll_write_ready(cx) {
                Poll::Ready(Ok(g)) => g,
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            };

            let fd = self.inner.as_raw_fd();
            let res = unsafe {
                libc::write(fd, buf.as_ptr() as *const libc::c_void, buf.len())
            };
            if res >= 0 {
                return Poll::Ready(Ok(res as usize));
            }
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::WouldBlock {
                guard.clear_ready();
                continue;
            }
            return Poll::Ready(Err(err));
        }
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        unsafe {
            libc::shutdown(self.inner.as_raw_fd(), libc::SHUT_WR);
        }
        Poll::Ready(Ok(()))
    }
}

#[derive(Clone)]
pub struct BtServerDeps {
    pub auth_limiter: Arc<AuthRateLimiter>,
    pub input: Arc<dyn InputController>,
    pub paste_lock: Arc<tokio::sync::Mutex<()>>,
    pub paste_semaphore: Arc<tokio::sync::Semaphore>,
    pub next_conn_id: Arc<AtomicU64>,
    pub idle_timeout: Duration,
}

impl BtServerDeps {
    pub fn new(
        auth_limiter: Arc<AuthRateLimiter>,
        input: Arc<dyn InputController>,
        paste_lock: Arc<tokio::sync::Mutex<()>>,
        next_conn_id: Arc<AtomicU64>,
    ) -> Self {
        Self {
            auth_limiter,
            input,
            paste_lock,
            paste_semaphore: Arc::new(tokio::sync::Semaphore::new(MAX_PENDING_PASTE_TASKS)),
            next_conn_id,
            idle_timeout: Duration::from_secs(BT_IDLE_TIMEOUT_SECS),
        }
    }
}

/// Read up to `max_bytes` until `\n` or EOF, bounding memory before newline arrives
/// and tolerating invalid UTF-8 sequences via lossy conversion.
async fn read_bounded_line<R: AsyncBufRead + Unpin>(
    reader: &mut R,
    buf: &mut Vec<u8>,
    max_bytes: usize,
) -> io::Result<Option<String>> {
    buf.clear();
    let limit = (max_bytes.saturating_add(1)) as u64;
    let n = reader.take(limit).read_until(b'\n', buf).await?;
    if n == 0 {
        return Ok(None);
    }
    if buf.len() > max_bytes {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Bluetooth line exceeds maximum allowed length",
        ));
    }
    Ok(Some(String::from_utf8_lossy(buf).into_owned()))
}

pub async fn handle_bt_stream<S>(
    stream: S,
    peer_name: String,
    deps: BtServerDeps,
    config_override: Option<SilentInputConfig>,
) where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let config = match config_override {
        Some(cfg) => cfg,
        None => match tokio::task::spawn_blocking(load_config).await {
            Ok(cfg) => cfg,
            Err(_) => return,
        },
    };

    let conn_id = deps.next_conn_id.fetch_add(1, Ordering::Relaxed);
    let _mouse_guard = MouseHoldGuard::new(Arc::clone(&deps.input), conn_id);

    println!("[SilentInput] Bluetooth client connected: {peer_name}");
    // Bluetooth connections are authenticated via BlueZ OS-level device pairing check
    let mut authenticated = true;

    let (read_half, mut write_half) = tokio::io::split(stream);
    let mut reader = BufReader::with_capacity(64 * 1024, read_half);
    let mut raw_buf = Vec::with_capacity(4096);

    loop {
        let read_res = tokio::time::timeout(
            deps.idle_timeout,
            read_bounded_line(&mut reader, &mut raw_buf, MAX_WS_FRAME_SIZE),
        )
        .await;
        match read_res {
            Ok(Ok(None)) => break,
            Ok(Ok(Some(line))) => {
                let msg_str = line.trim();
                if msg_str.is_empty() {
                    continue;
                }
                let outcome = process_message(
                    msg_str,
                    SessionContext {
                        authenticated,
                        config: &config,
                        peer_ip: &peer_name,
                        conn_id,
                        auth_limiter: &deps.auth_limiter,
                        input: Arc::clone(&deps.input),
                        paste_lock: Arc::clone(&deps.paste_lock),
                        paste_semaphore: Arc::clone(&deps.paste_semaphore),
                        udp_registry: None,
                    },
                )
                .await;

                authenticated = outcome.authenticated;
                if let Some(reply) = outcome.reply {
                    let payload = format!("{reply}\n");
                    if write_half.write_all(payload.as_bytes()).await.is_err() {
                        break;
                    }
                    let _ = write_half.flush().await;
                }
                if outcome.should_close {
                    break;
                }
            }
            Ok(Err(err)) => {
                eprintln!("[SilentInput] Bluetooth stream error: {err}");
                break;
            }
            Err(_) => {
                println!("[SilentInput] Bluetooth idle timeout for {peer_name}");
                break;
            }
        }
    }

    let _ = write_half.shutdown().await;
    println!("[SilentInput] Bluetooth connection closed for {peer_name}");
}

struct SilentInputProfile {
    conn: Connection,
    deps: BtServerDeps,
}

#[zbus::interface(name = "org.bluez.Profile1")]
impl SilentInputProfile {
    async fn new_connection(
        &self,
        device: OwnedObjectPath,
        fd: zbus::zvariant::Fd<'_>,
        _properties: HashMap<String, OwnedValue>,
    ) -> zbus::fdo::Result<()> {
        let dev_path = device.to_string();
        println!("[SilentInput] BlueZ Profile NewConnection from {dev_path}");

        let owned_fd = match fd.as_fd().try_clone_to_owned() {
            Ok(f) => f,
            Err(err) => {
                eprintln!("[SilentInput] Error cloning Bluetooth FD: {err}");
                return Err(zbus::fdo::Error::Failed(err.to_string()));
            }
        };

        let is_paired = check_bluez_device_paired(&self.conn, &dev_path).await;
        if !is_paired {
            println!("[SilentInput] Rejected unpaired Bluetooth device: {dev_path}");
            drop(owned_fd);
            return Ok(());
        }

        let stream = match AsyncRfcommStream::from_owned_fd(owned_fd) {
            Ok(s) => s,
            Err(err) => {
                eprintln!("[SilentInput] Failed to wrap Bluetooth RFCOMM FD: {err}");
                return Err(zbus::fdo::Error::Failed(err.to_string()));
            }
        };

        let deps = self.deps.clone();
        let peer_label = format!("Bluetooth({dev_path})");
        tokio::spawn(async move {
            handle_bt_stream(stream, peer_label, deps, None).await;
        });

        Ok(())
    }

    async fn release(&self) -> zbus::fdo::Result<()> {
        println!("[SilentInput] BlueZ Profile released");
        Ok(())
    }
}

async fn check_bluez_device_paired(conn: &Connection, device_path: &str) -> bool {
    let reply = conn
        .call_method(
            Some("org.bluez"),
            device_path,
            Some("org.freedesktop.DBus.Properties"),
            "Get",
            &("org.bluez.Device1", "Paired"),
        )
        .await;

    match reply {
        Ok(msg) => match msg.body().deserialize::<OwnedValue>() {
            Ok(val) => bool::try_from(&val).unwrap_or(false),
            Err(err) => {
                eprintln!(
                    "[SilentInput] Warning: failed to decode Paired property for {device_path}: {err}"
                );
                false
            }
        },
        Err(err) => {
            eprintln!(
                "[SilentInput] Warning: could not verify Paired state for {device_path}: {err}"
            );
            false
        }
    }
}

async fn register_bluez_profile(conn: &Connection, reason: &str) -> bool {
    let mut opts: HashMap<&str, Value<'_>> = HashMap::new();
    opts.insert("Name", Value::from("SilentInput Bluetooth"));
    opts.insert("Role", Value::from("server"));
    opts.insert("Channel", Value::from(1u16));
    opts.insert("Service", Value::from(SPP_UUID));
    opts.insert("AutoConnect", Value::from(true));

    let profile_path = ObjectPath::from_str_unchecked(PROFILE_PATH);
    match conn
        .call_method(
            Some("org.bluez"),
            "/org/bluez",
            Some("org.bluez.ProfileManager1"),
            "RegisterProfile",
            &(profile_path, SPP_UUID, opts),
        )
        .await
    {
        Ok(_) => {
            println!("[SilentInput] Registered Bluetooth SPP profile with BlueZ ({reason})");
            true
        }
        Err(zbus::Error::MethodError(name, _, _))
            if name.as_str().contains("AlreadyExists") =>
        {
            true
        }
        Err(err) => {
            eprintln!("[SilentInput] BlueZ RegisterProfile ({reason}) failed: {err}");
            false
        }
    }
}

pub async fn run_bluetooth_server(deps: BtServerDeps) {
    match setup_bluez_dbus_profile(deps.clone()).await {
        Ok(conn) => {
            // Keep D-Bus Connection & exported object alive and listen for BlueZ/adapter/sleep recovery
            run_dbus_lifecycle_watchers(conn).await;
        }
        Err(err) => {
            println!("[SilentInput] Info: BlueZ D-Bus profile registration skipped: {err}");
            run_direct_rfcomm_fallback(deps);
        }
    }
}

async fn setup_bluez_dbus_profile(deps: BtServerDeps) -> Result<Connection, String> {
    let conn = Connection::system()
        .await
        .map_err(|e| format!("System D-Bus unavailable: {e}"))?;

    let iface = SilentInputProfile {
        conn: conn.clone(),
        deps,
    };
    conn.object_server()
        .at(PROFILE_PATH, iface)
        .await
        .map_err(|e| format!("Failed to export BlueZ Profile1: {e}"))?;

    Ok(conn)
}

async fn run_dbus_lifecycle_watchers(conn: Connection) {
    let registered = Arc::new(AtomicBool::new(register_bluez_profile(&conn, "startup").await));

    // Retry boot registration up to 20 times every 3s if bluetoothd comes up late
    if !registered.load(Ordering::Acquire) {
        let conn_retry = conn.clone();
        let reg_retry = Arc::clone(&registered);
        tokio::spawn(async move {
            for attempt in 1..=20 {
                tokio::time::sleep(Duration::from_secs(3)).await;
                if reg_retry.load(Ordering::Acquire) {
                    break;
                }
                let reason = format!("boot-retry-{attempt}");
                if register_bluez_profile(&conn_retry, &reason).await {
                    reg_retry.store(true, Ordering::Release);
                    break;
                }
            }
        });
    }

    let owner_rule = MatchRule::builder()
        .msg_type(zbus::message::Type::Signal)
        .interface("org.freedesktop.DBus")
        .and_then(|b| b.member("NameOwnerChanged"))
        .and_then(|b| b.arg(0, "org.bluez"))
        .ok()
        .map(|b| b.build());

    let adapter_rule = MatchRule::builder()
        .msg_type(zbus::message::Type::Signal)
        .interface("org.freedesktop.DBus.Properties")
        .and_then(|b| b.member("PropertiesChanged"))
        .and_then(|b| b.arg(0, "org.bluez.Adapter1"))
        .ok()
        .map(|b| b.build());

    let sleep_rule = MatchRule::builder()
        .msg_type(zbus::message::Type::Signal)
        .interface("org.freedesktop.login1.Manager")
        .and_then(|b| b.member("PrepareForSleep"))
        .ok()
        .map(|b| b.build());

    let mut owner_stream = match owner_rule {
        Some(rule) => MessageStream::for_match_rule(rule, &conn, None).await.ok(),
        None => None,
    };
    let mut adapter_stream = match adapter_rule {
        Some(rule) => MessageStream::for_match_rule(rule, &conn, None).await.ok(),
        None => None,
    };
    let mut sleep_stream = match sleep_rule {
        Some(rule) => MessageStream::for_match_rule(rule, &conn, None).await.ok(),
        None => None,
    };

    loop {
        tokio::select! {
            Some(Ok(msg)) = async {
                match owner_stream.as_mut() {
                    Some(s) => s.next().await,
                    None => std::future::pending().await,
                }
            } => {
                if let Ok((_, _, new_owner)) = msg.body().deserialize::<(String, String, String)>() {
                    if !new_owner.is_empty() {
                        registered.store(false, Ordering::Release);
                        tokio::time::sleep(Duration::from_millis(500)).await;
                        if register_bluez_profile(&conn, "bluez-restarted").await {
                            registered.store(true, Ordering::Release);
                        }
                    }
                }
            }
            Some(Ok(msg)) = async {
                match adapter_stream.as_mut() {
                    Some(s) => s.next().await,
                    None => std::future::pending().await,
                }
            } => {
                if let Ok((_, changed, _)) =
                    msg.body().deserialize::<(String, HashMap<String, OwnedValue>, Vec<String>)>()
                {
                    let powered = changed
                        .get("Powered")
                        .and_then(|v| bool::try_from(v).ok())
                        .unwrap_or(false);
                    if powered {
                        tokio::time::sleep(Duration::from_millis(300)).await;
                        if register_bluez_profile(&conn, "adapter-powered-on").await {
                            registered.store(true, Ordering::Release);
                        }
                    }
                }
            }
            Some(Ok(msg)) = async {
                match sleep_stream.as_mut() {
                    Some(s) => s.next().await,
                    None => std::future::pending().await,
                }
            } => {
                if let Ok(sleeping) = msg.body().deserialize::<bool>() {
                    if !sleeping {
                        tokio::time::sleep(Duration::from_millis(1500)).await;
                        if register_bluez_profile(&conn, "system-resume").await {
                            registered.store(true, Ordering::Release);
                        }
                    }
                }
            }
            else => {
                tokio::time::sleep(Duration::from_secs(60)).await;
            }
        }
    }
}

fn is_bt_mac_paired(mac_addr: &str) -> bool {
    if mac_addr.is_empty() {
        return false;
    }
    let Ok(out) = Command::new("bluetoothctl")
        .args(["info", mac_addr])
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
    else {
        return false;
    };
    String::from_utf8_lossy(&out.stdout).lines().any(|line| {
        let trimmed = line.trim().to_ascii_lowercase();
        trimmed.starts_with("paired:") && trimmed.contains("yes")
    })
}

fn run_direct_rfcomm_fallback(deps: BtServerDeps) {
    const BTPROTO_RFCOMM: libc::c_int = 3;

    #[repr(C)]
    struct SockAddrRc {
        rc_family: libc::sa_family_t,
        rc_bdaddr: [u8; 6],
        rc_channel: u8,
    }

    let handle = tokio::runtime::Handle::current();
    std::thread::spawn(move || loop {
        unsafe {
            let server_fd =
                libc::socket(libc::AF_BLUETOOTH, libc::SOCK_STREAM, BTPROTO_RFCOMM);
            if server_fd < 0 {
                std::thread::sleep(Duration::from_secs(3));
                continue;
            }

            let addr = SockAddrRc {
                rc_family: libc::AF_BLUETOOTH as libc::sa_family_t,
                rc_bdaddr: [0u8; 6],
                rc_channel: 1,
            };

            if libc::bind(
                server_fd,
                &addr as *const SockAddrRc as *const libc::sockaddr,
                std::mem::size_of::<SockAddrRc>() as libc::socklen_t,
            ) < 0
                || libc::listen(server_fd, 4) < 0
            {
                libc::close(server_fd);
                std::thread::sleep(Duration::from_secs(3));
                continue;
            }

            println!("[SilentInput] Direct RFCOMM fallback socket listening on Channel 1");
            loop {
                let mut client_addr = SockAddrRc {
                    rc_family: 0,
                    rc_bdaddr: [0u8; 6],
                    rc_channel: 0,
                };
                let mut len = std::mem::size_of::<SockAddrRc>() as libc::socklen_t;
                let client_fd = libc::accept(
                    server_fd,
                    &mut client_addr as *mut SockAddrRc as *mut libc::sockaddr,
                    &mut len,
                );
                if client_fd < 0 {
                    break;
                }

                let owned = OwnedFd::from_raw_fd(client_fd);
                let mac = format!(
                    "{:02X}:{:02X}:{:02X}:{:02X}:{:02X}:{:02X}",
                    client_addr.rc_bdaddr[5],
                    client_addr.rc_bdaddr[4],
                    client_addr.rc_bdaddr[3],
                    client_addr.rc_bdaddr[2],
                    client_addr.rc_bdaddr[1],
                    client_addr.rc_bdaddr[0]
                );
                if !is_bt_mac_paired(&mac) {
                    println!("[SilentInput] Rejected unpaired direct RFCOMM device: {mac}");
                    drop(owned);
                    continue;
                }
                let deps_c = deps.clone();
                handle.spawn(async move {
                    if let Ok(stream) = AsyncRfcommStream::from_owned_fd(owned) {
                        handle_bt_stream(stream, format!("RFCOMM({mac})"), deps_c, None).await;
                    }
                });
            }
            libc::close(server_fd);
        }
        std::thread::sleep(Duration::from_secs(3));
    });
}

#[cfg(test)]
mod tests {
    use tokio::io::{AsyncWriteExt, BufReader};

    use super::read_bounded_line;

    #[tokio::test]
    async fn read_bounded_line_rejects_oversized_and_tolerates_invalid_utf8() {
        let (mut tx, rx) = tokio::io::duplex(256);
        let mut reader = BufReader::new(rx);
        let mut buf = Vec::new();

        tx.write_all(b"{\"type\":\"ping\"}\n").await.unwrap();
        let line = read_bounded_line(&mut reader, &mut buf, 64).await.unwrap();
        assert_eq!(line.as_deref(), Some("{\"type\":\"ping\"}\n"));

        // Invalid UTF-8 bytes are decoded via replacement without failing the stream
        tx.write_all(&[b'{', b'"', 0xff, 0xfe, b'"', b'}', b'\n'])
            .await
            .unwrap();
        let lossy = read_bounded_line(&mut reader, &mut buf, 64).await.unwrap();
        assert!(lossy.is_some());

        // Line exceeding max_bytes without newline errors out instead of buffering unboundedly
        tx.write_all(&[b'a'; 80]).await.unwrap();
        drop(tx);
        let err = read_bounded_line(&mut reader, &mut buf, 64)
            .await
            .unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::InvalidData);
    }
}
