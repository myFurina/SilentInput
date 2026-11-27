use std::collections::HashMap;
use std::io;
use std::os::fd::{AsFd, AsRawFd, OwnedFd};
use std::pin::Pin;
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
                        udp_session_id: None,
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

async fn register_bluez_profile(
    conn: &Connection,
    reason: &str,
    allow_already_exists: bool,
) -> bool {
    let mut opts: HashMap<&str, Value<'_>> = HashMap::new();
    opts.insert("Name", Value::from("SilentInput Bluetooth"));
    opts.insert("Role", Value::from("server"));
    opts.insert("Channel", Value::from(1u16));
    opts.insert("Service", Value::from(SPP_UUID));
    opts.insert("RequireAuthentication", Value::from(true));
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
            if allow_already_exists {
                true
            } else {
                eprintln!(
                    "[SilentInput] Warning: BlueZ RegisterProfile ({reason}) returned AlreadyExists (another daemon/service may still own SPP profile)"
                );
                false
            }
        }
        Err(err) => {
            eprintln!("[SilentInput] BlueZ RegisterProfile ({reason}) failed: {err}");
            false
        }
    }
}

async fn unregister_bluez_profile(conn: &Connection) {
    let profile_path = ObjectPath::from_str_unchecked(PROFILE_PATH);
    let _ = conn
        .call_method(
            Some("org.bluez"),
            "/org/bluez",
            Some("org.bluez.ProfileManager1"),
            "UnregisterProfile",
            &(profile_path,),
        )
        .await;
}

pub async fn run_bluetooth_server(
    deps: BtServerDeps,
    shutdown_rx: tokio::sync::watch::Receiver<bool>,
) {
    match setup_bluez_dbus_profile(deps).await {
        Ok(conn) => {
            // Keep D-Bus Connection & exported object alive and listen for BlueZ/adapter/sleep recovery
            run_dbus_lifecycle_watchers(conn, shutdown_rx).await;
        }
        Err(err) => {
            println!(
                "[SilentInput] Info: BlueZ D-Bus profile unavailable ({err}); Bluetooth SPP disabled."
            );
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

async fn run_dbus_lifecycle_watchers(
    conn: Connection,
    mut shutdown_rx: tokio::sync::watch::Receiver<bool>,
) {
    let registered = Arc::new(AtomicBool::new(
        register_bluez_profile(&conn, "startup", false).await,
    ));

    // Retry boot registration up to 20 times every 3s if bluetoothd comes up late or an old instance is releasing
    if !registered.load(Ordering::Acquire) {
        let conn_retry = conn.clone();
        let reg_retry = Arc::clone(&registered);
        let mut retry_shutdown = shutdown_rx.clone();
        tokio::spawn(async move {
            for attempt in 1..=20 {
                tokio::select! {
                    _ = tokio::time::sleep(Duration::from_secs(3)) => {}
                    _ = retry_shutdown.changed() => {
                        if *retry_shutdown.borrow() {
                            break;
                        }
                    }
                }
                if *retry_shutdown.borrow() || reg_retry.load(Ordering::Acquire) {
                    break;
                }
                let reason = format!("boot-retry-{attempt}");
                if register_bluez_profile(&conn_retry, &reason, false).await {
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
        if *shutdown_rx.borrow() {
            break;
        }
        tokio::select! {
            _ = shutdown_rx.changed() => {
                if *shutdown_rx.borrow() {
                    break;
                }
            }
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
                        if register_bluez_profile(&conn, "bluez-restarted", false).await {
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
                        let already = registered.load(Ordering::Acquire);
                        if register_bluez_profile(&conn, "adapter-powered-on", already).await {
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
                        let already = registered.load(Ordering::Acquire);
                        if register_bluez_profile(&conn, "system-resume", already).await {
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

    if registered.load(Ordering::Acquire) {
        unregister_bluez_profile(&conn).await;
    }
}

#[cfg(test)]
mod tests {
    use std::collections::{HashMap, HashSet};
    use std::sync::atomic::AtomicU64;
    use std::sync::{Arc, Mutex};

    use evdev::{EventType, InputEvent, Key};
    use serde_json::Value;
    use silentinput_core::config::SilentInputConfig;
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

    use super::{handle_bt_stream, read_bounded_line, BtServerDeps};
    use crate::auth_limiter::AuthRateLimiter;
    use crate::input::{BoxFuture, InputController};

    #[derive(Default)]
    struct MockBtInput {
        events: Mutex<Vec<Vec<(u16, u16, i32)>>>,
        held: Mutex<HashMap<u16, HashSet<u64>>>,
    }

    impl InputController for MockBtInput {
        fn ensure_ready(&self, _log_failure: bool) -> bool {
            true
        }
        fn has_uinput_device(&self) -> bool {
            true
        }
        fn is_graphical_session(&self) -> bool {
            true
        }
        fn emit_key_combination<'a>(&'a self, _combo: &'a str) -> BoxFuture<'a, ()> {
            Box::pin(async {})
        }
        fn type_text<'a>(&'a self, _text: &'a str) -> BoxFuture<'a, bool> {
            Box::pin(async { true })
        }
        fn copy_clipboard<'a>(&'a self, _text: &'a str) -> BoxFuture<'a, bool> {
            Box::pin(async { true })
        }
        fn emit_uinput_events(&self, events: &[InputEvent]) -> bool {
            let tuples = events
                .iter()
                .map(|ev| (ev.event_type().0, ev.code(), ev.value()))
                .collect();
            self.events.lock().unwrap().push(tuples);
            true
        }
        fn track_mouse_button(&self, btn_code: u16, pressed: bool, conn_id: u64) {
            let mut held = self.held.lock().unwrap();
            if pressed {
                held.entry(btn_code).or_default().insert(conn_id);
            } else if let Some(set) = held.get_mut(&btn_code) {
                set.remove(&conn_id);
                if set.is_empty() {
                    held.remove(&btn_code);
                }
            }
        }
        fn release_connection_buttons(&self, conn_id: u64) {
            let mut to_release = Vec::new();
            {
                let mut held = self.held.lock().unwrap();
                let keys: Vec<u16> = held.keys().copied().collect();
                for code in keys {
                    if let Some(set) = held.get_mut(&code) {
                        if set.remove(&conn_id) && set.is_empty() {
                            held.remove(&code);
                            to_release.push(code);
                        }
                    }
                }
            }
            if !to_release.is_empty() {
                let evs: Vec<InputEvent> = to_release
                    .into_iter()
                    .map(|c| InputEvent::new(EventType::KEY, c, 0))
                    .collect();
                self.emit_uinput_events(&evs);
            }
        }
    }

    #[tokio::test]
    async fn handle_bt_stream_processes_frames_and_releases_held_button_on_eof() {
        let input = Arc::new(MockBtInput::default());
        let deps = BtServerDeps::new(
            Arc::new(AuthRateLimiter::new()),
            Arc::clone(&input) as Arc<dyn InputController>,
            Arc::new(tokio::sync::Mutex::new(())),
            Arc::new(AtomicU64::new(1)),
        );
        let cfg = SilentInputConfig::default();

        let (server_stream, client_stream) = tokio::io::duplex(4096);
        let srv_task = tokio::spawn(handle_bt_stream(
            server_stream,
            "Bluetooth(test)".to_string(),
            deps,
            Some(cfg),
        ));

        let (client_read, mut client_write) = tokio::io::split(client_stream);
        let mut lines = BufReader::new(client_read).lines();

        client_write
            .write_all(b"{\"type\":\"get_status\"}\n")
            .await
            .unwrap();
        let status_line = lines.next_line().await.unwrap().unwrap();
        let status_json: Value = serde_json::from_str(&status_line).unwrap();
        assert_eq!(status_json["type"], "status");
        assert_eq!(status_json["hi_res_wheel"], true);

        client_write
            .write_all(b"{\"type\":\"ping\",\"t\":777}\n")
            .await
            .unwrap();
        let pong_line = lines.next_line().await.unwrap().unwrap();
        let pong_json: Value = serde_json::from_str(&pong_line).unwrap();
        assert_eq!(pong_json["type"], "pong");
        assert_eq!(pong_json["t"], 777);

        client_write
            .write_all(b"{\"type\":\"mouse\",\"action\":\"down_left\"}\n")
            .await
            .unwrap();
        client_write.shutdown().await.unwrap();
        drop(client_write);
        drop(lines);

        srv_task.await.unwrap();

        assert!(input.held.lock().unwrap().is_empty());
        let evs = input.events.lock().unwrap();
        assert_eq!(evs.len(), 2);
        assert_eq!(evs[0], vec![(EventType::KEY.0, Key::BTN_LEFT.code(), 1)]);
        assert_eq!(evs[1], vec![(EventType::KEY.0, Key::BTN_LEFT.code(), 0)]);
    }

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
