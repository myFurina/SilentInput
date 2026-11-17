use std::collections::{BTreeSet, HashMap, HashSet};
use std::future::Future;
use std::io;
use std::net::SocketAddr;
use std::path::Path;
use std::process::Stdio;
use std::time::Duration;

use silentinput_core::process::{
    adb_reverse_list_has_spec, encode_adb_request, parse_adb_online_serials, resolve_adb_endpoint,
    AdbEndpoint,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::process::Command;
use tokio::time::Instant;

const SYSFS_USB_DEVICES: &str = "/sys/bus/usb/devices";
const MAX_ADB_FRAME_LEN: usize = 1024 * 1024;
const ADB_IO_TIMEOUT: Duration = Duration::from_secs(2);
const BACKOFF_NO_ADB: Duration = Duration::from_secs(60);
const BACKOFF_UNSUPPORTED_SOCKET: Duration = Duration::from_secs(60);
const BACKOFF_START_FAILED: Duration = Duration::from_secs(20);
const BACKOFF_IDLE_SYSFS: Duration = Duration::from_secs(5);
const BACKOFF_DISCONNECTED: Duration = Duration::from_secs(2);
const BACKOFF_AFTER_START: Duration = Duration::from_millis(500);
const REVERSE_BACKOFF_SECS: [u64; 5] = [5, 5, 10, 30, 60];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AdbStartOutcome {
    Started,
    NotInstalled,
    Failed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SessionOutcome {
    ConnectFailed,
    Disconnected,
}

#[derive(Debug, Default)]
struct AdbAutoStartTracker {
    known_ifaces: BTreeSet<String>,
}

impl AdbAutoStartTracker {
    fn sync_observed(&mut self, current: &BTreeSet<String>) {
        self.known_ifaces = current.clone();
    }

    fn prune_missing(&mut self, current: &BTreeSet<String>) {
        self.known_ifaces.retain(|id| current.contains(id));
    }

    /// Check whether any newly attached ADB interface exists (does not lock interfaces yet).
    fn has_new_adb_interface(&mut self, current: &BTreeSet<String>) -> bool {
        self.prune_missing(current);
        current.iter().any(|id| !self.known_ifaces.contains(id))
    }

    /// Record the outcome of attempting `adb start-server`:
    /// only lock the current interfaces when `Started` succeeded, so `Failed`/`NotInstalled`
    /// will retry after backoff without needing physical unplug.
    fn record_start_attempt(&mut self, current: &BTreeSet<String>, outcome: AdbStartOutcome) {
        if outcome == AdbStartOutcome::Started {
            self.sync_observed(current);
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct SerialRetryState {
    failures: u32,
    next_retry_at: Instant,
    last_error: Option<String>,
}

impl SerialRetryState {
    fn backoff_for_failures(failures: u32) -> Duration {
        let idx = usize::try_from(failures.saturating_sub(1))
            .unwrap_or(usize::MAX)
            .min(REVERSE_BACKOFF_SECS.len() - 1);
        Duration::from_secs(REVERSE_BACKOFF_SECS[idx])
    }

    /// Record a reverse failure; return true only if the warning should be logged.
    fn record_failure(&mut self, err: &str, now: Instant) -> bool {
        self.failures = self.failures.saturating_add(1);
        self.next_retry_at = now + Self::backoff_for_failures(self.failures);
        let should_log = self.last_error.as_deref() != Some(err);
        if should_log {
            self.last_error = Some(err.to_string());
        }
        should_log
    }
}

fn read_trimmed_sysfs(path: &Path) -> Option<String> {
    std::fs::read_to_string(path)
        .ok()
        .map(|s| s.trim().to_string())
}

/// Scan Linux sysfs (`/sys/bus/usb/devices`) for interfaces matching ADB (`ff`/`42`/`01`).
fn scan_sysfs_adb_interfaces_in(dir: &Path) -> BTreeSet<String> {
    let mut found = BTreeSet::new();
    let Ok(entries) = std::fs::read_dir(dir) else {
        return found;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Some(cls) = read_trimmed_sysfs(&path.join("bInterfaceClass")) else {
            continue;
        };
        let Some(sub) = read_trimmed_sysfs(&path.join("bInterfaceSubClass")) else {
            continue;
        };
        let Some(proto) = read_trimmed_sysfs(&path.join("bInterfaceProtocol")) else {
            continue;
        };
        if cls.eq_ignore_ascii_case("ff")
            && sub.eq_ignore_ascii_case("42")
            && proto.eq_ignore_ascii_case("01")
        {
            if let Some(name) = entry.file_name().to_str() {
                found.insert(name.to_string());
            }
        }
    }
    found
}

pub async fn maintain_adb_reverse(port: u16) {
    let mut tracker = AdbAutoStartTracker::default();
    loop {
        let addr = match resolve_adb_endpoint() {
            AdbEndpoint::LocalTcp(addr) => addr,
            AdbEndpoint::UnsupportedSocket => {
                tokio::time::sleep(BACKOFF_UNSUPPORTED_SOCKET).await;
                continue;
            }
        };

        let outcome = run_track_devices_session(
            addr,
            || {
                let current_ifaces = scan_sysfs_adb_interfaces_in(Path::new(SYSFS_USB_DEVICES));
                tracker.sync_observed(&current_ifaces);
            },
            |serial| async move { ensure_adb_reverse_for_serial(addr, &serial, port).await },
        )
        .await;

        match outcome {
            SessionOutcome::Disconnected => {
                tokio::time::sleep(BACKOFF_DISCONNECTED).await;
            }
            SessionOutcome::ConnectFailed => {
                let current_ifaces = scan_sysfs_adb_interfaces_in(Path::new(SYSFS_USB_DEVICES));
                if tracker.has_new_adb_interface(&current_ifaces) {
                    let start_outcome = try_start_adb_server().await;
                    tracker.record_start_attempt(&current_ifaces, start_outcome);
                    match start_outcome {
                        AdbStartOutcome::Started => {
                            tokio::time::sleep(BACKOFF_AFTER_START).await;
                        }
                        AdbStartOutcome::NotInstalled => {
                            tokio::time::sleep(BACKOFF_NO_ADB).await;
                        }
                        AdbStartOutcome::Failed => {
                            tokio::time::sleep(BACKOFF_START_FAILED).await;
                        }
                    }
                } else {
                    tokio::time::sleep(BACKOFF_IDLE_SYSFS).await;
                }
            }
        }
    }
}

async fn try_start_adb_server() -> AdbStartOutcome {
    let mut cmd = Command::new("adb");
    cmd.arg("start-server")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true);

    let res = tokio::time::timeout(Duration::from_secs(5), cmd.status()).await;
    match res {
        Ok(Ok(status)) if status.success() => AdbStartOutcome::Started,
        Ok(Err(err)) if err.kind() == io::ErrorKind::NotFound => AdbStartOutcome::NotInstalled,
        _ => AdbStartOutcome::Failed,
    }
}

async fn read_adb_status(stream: &mut TcpStream) -> Result<(), String> {
    let mut status = [0u8; 4];
    tokio::time::timeout(ADB_IO_TIMEOUT, stream.read_exact(&mut status))
        .await
        .map_err(|_| "timed out reading ADB status".to_string())?
        .map_err(|e| format!("failed to read ADB status: {e}"))?;

    if &status == b"OKAY" {
        return Ok(());
    }
    if &status == b"FAIL" {
        let mut len_hex = [0u8; 4];
        if let Ok(Ok(_)) =
            tokio::time::timeout(Duration::from_millis(200), stream.read_exact(&mut len_hex)).await
        {
            if let Some(len) = std::str::from_utf8(&len_hex)
                .ok()
                .and_then(|s| usize::from_str_radix(s, 16).ok())
                .filter(|&n| n > 0 && n <= 4096)
            {
                let mut reason = vec![0u8; len];
                if let Ok(Ok(_)) = tokio::time::timeout(
                    Duration::from_millis(200),
                    stream.read_exact(&mut reason),
                )
                .await
                {
                    return Err(format!(
                        "ADB FAIL: {}",
                        String::from_utf8_lossy(&reason).trim()
                    ));
                }
            }
        }
        return Err("ADB FAIL".to_string());
    }
    Err(format!("unexpected ADB status: {status:?}"))
}

async fn open_transport(addr: SocketAddr, serial: &str) -> Result<TcpStream, String> {
    let mut stream = tokio::time::timeout(ADB_IO_TIMEOUT, TcpStream::connect(addr))
        .await
        .map_err(|_| "timed out connecting to ADB server".to_string())?
        .map_err(|e| format!("connect failed: {e}"))?;

    let cmd = encode_adb_request(&format!("host:transport:{serial}"));
    tokio::time::timeout(ADB_IO_TIMEOUT, stream.write_all(cmd.as_bytes()))
        .await
        .map_err(|_| "timed out writing host:transport".to_string())?
        .map_err(|e| format!("write host:transport failed: {e}"))?;

    read_adb_status(&mut stream).await?;
    Ok(stream)
}

async fn list_reverse_forwards_tcp(addr: SocketAddr, serial: &str) -> Result<String, String> {
    let mut stream = open_transport(addr, serial).await?;
    let cmd = encode_adb_request("reverse:list-forward");
    tokio::time::timeout(ADB_IO_TIMEOUT, stream.write_all(cmd.as_bytes()))
        .await
        .map_err(|_| "timed out writing reverse:list-forward".to_string())?
        .map_err(|e| format!("write reverse:list-forward failed: {e}"))?;

    read_adb_status(&mut stream).await?;

    let mut len_hex = [0u8; 4];
    tokio::time::timeout(ADB_IO_TIMEOUT, stream.read_exact(&mut len_hex))
        .await
        .map_err(|_| "timed out reading reverse:list-forward length".to_string())?
        .map_err(|e| format!("read reverse:list-forward length failed: {e}"))?;

    let len_str = std::str::from_utf8(&len_hex)
        .map_err(|_| "non-UTF8 reverse:list-forward length".to_string())?;
    let payload_len = usize::from_str_radix(len_str, 16)
        .map_err(|_| format!("invalid hex length {len_str:?}"))?;
    if payload_len > MAX_ADB_FRAME_LEN {
        return Err(format!("reverse:list-forward payload too large: {payload_len}"));
    }
    if payload_len == 0 {
        return Ok(String::new());
    }

    let mut body = vec![0u8; payload_len];
    tokio::time::timeout(ADB_IO_TIMEOUT, stream.read_exact(&mut body))
        .await
        .map_err(|_| "timed out reading reverse:list-forward body".to_string())?
        .map_err(|e| format!("read reverse:list-forward body failed: {e}"))?;

    Ok(String::from_utf8_lossy(&body).into_owned())
}

async fn add_reverse_forward_tcp(addr: SocketAddr, serial: &str, port: u16) -> Result<(), String> {
    let mut stream = open_transport(addr, serial).await?;
    let cmd = encode_adb_request(&format!("reverse:forward:tcp:{port};tcp:{port}"));
    tokio::time::timeout(ADB_IO_TIMEOUT, stream.write_all(cmd.as_bytes()))
        .await
        .map_err(|_| "timed out writing reverse:forward".to_string())?
        .map_err(|e| format!("write reverse:forward failed: {e}"))?;

    // ADB responds with service setup OKAY, followed by device execution OKAY.
    read_adb_status(&mut stream).await?;
    read_adb_status(&mut stream).await?;
    Ok(())
}

async fn ensure_adb_reverse_for_serial(
    addr: SocketAddr,
    serial: &str,
    port: u16,
) -> Result<(), String> {
    let target_spec = format!("tcp:{port}");
    let list_body = list_reverse_forwards_tcp(addr, serial).await?;
    if adb_reverse_list_has_spec(&list_body, &target_spec) {
        return Ok(());
    }

    add_reverse_forward_tcp(addr, serial, port).await?;
    println!("[SilentInput] Auto-configured adb reverse {target_spec} for ADB device {serial}.");
    Ok(())
}

fn try_take_frame(buf: &mut Vec<u8>) -> Result<Option<Vec<String>>, ()> {
    if buf.len() < 4 {
        return Ok(None);
    }
    let len_str = std::str::from_utf8(&buf[..4]).map_err(|_| ())?;
    let payload_len = usize::from_str_radix(len_str, 16).map_err(|_| ())?;
    if payload_len > MAX_ADB_FRAME_LEN {
        return Err(());
    }
    if buf.len() < 4 + payload_len {
        return Ok(None);
    }
    let online = if payload_len == 0 {
        Vec::new()
    } else {
        let body = String::from_utf8_lossy(&buf[4..4 + payload_len]);
        parse_adb_online_serials(&body)
    };
    buf.drain(..4 + payload_len);
    Ok(Some(online))
}

async fn configure_pending_serials<F, Fut>(
    current_online: &[String],
    configured_serials: &mut HashSet<String>,
    retry_states: &mut HashMap<String, SerialRetryState>,
    now: Instant,
    ensure_reverse: &mut F,
) where
    F: FnMut(String) -> Fut,
    Fut: Future<Output = Result<(), String>>,
{
    configured_serials.retain(|s| current_online.contains(s));
    retry_states.retain(|s, _| current_online.contains(s) && !configured_serials.contains(s));

    let pending: Vec<String> = current_online
        .iter()
        .filter(|s| {
            !configured_serials.contains(*s)
                && retry_states.get(*s).is_none_or(|st| now >= st.next_retry_at)
        })
        .cloned()
        .collect();

    for serial in pending {
        match ensure_reverse(serial.clone()).await {
            Ok(()) => {
                configured_serials.insert(serial.clone());
                retry_states.remove(&serial);
            }
            Err(err) => {
                let state = retry_states
                    .entry(serial.clone())
                    .or_insert(SerialRetryState {
                        failures: 0,
                        next_retry_at: now,
                        last_error: None,
                    });
                if state.record_failure(&err, now) {
                    eprintln!(
                        "[SilentInput] Warning: failed to configure adb reverse for ADB device {serial}: {err}"
                    );
                }
            }
        }
    }
}

fn next_pending_retry_at(
    current_online: &[String],
    configured_serials: &HashSet<String>,
    retry_states: &HashMap<String, SerialRetryState>,
) -> Option<Instant> {
    current_online
        .iter()
        .filter(|s| !configured_serials.contains(*s))
        .map(|s| {
            retry_states
                .get(s)
                .map(|st| st.next_retry_at)
                .unwrap_or_else(Instant::now)
        })
        .min()
}

async fn run_track_devices_session<O, F, Fut>(
    addr: SocketAddr,
    mut on_connected: O,
    mut ensure_reverse: F,
) -> SessionOutcome
where
    O: FnMut(),
    F: FnMut(String) -> Fut,
    Fut: Future<Output = Result<(), String>>,
{
    let Ok(Ok(mut stream)) = tokio::time::timeout(ADB_IO_TIMEOUT, TcpStream::connect(addr)).await
    else {
        return SessionOutcome::ConnectFailed;
    };

    on_connected();

    let req = encode_adb_request("host:track-devices");
    if tokio::time::timeout(ADB_IO_TIMEOUT, stream.write_all(req.as_bytes()))
        .await
        .ok()
        .and_then(Result::ok)
        .is_none()
    {
        return SessionOutcome::Disconnected;
    }

    if read_adb_status(&mut stream).await.is_err() {
        return SessionOutcome::Disconnected;
    }

    let mut read_buf: Vec<u8> = Vec::with_capacity(512);
    let mut current_online: Vec<String> = Vec::new();
    let mut configured_serials: HashSet<String> = HashSet::new();
    let mut retry_states: HashMap<String, SerialRetryState> = HashMap::new();

    loop {
        while let Some(online) = match try_take_frame(&mut read_buf) {
            Ok(frame) => frame,
            Err(()) => return SessionOutcome::Disconnected,
        } {
            current_online = online;
            configure_pending_serials(
                &current_online,
                &mut configured_serials,
                &mut retry_states,
                Instant::now(),
                &mut ensure_reverse,
            )
            .await;
        }

        if let Some(deadline) =
            next_pending_retry_at(&current_online, &configured_serials, &retry_states)
        {
            tokio::select! {
                res = stream.read_buf(&mut read_buf) => {
                    match res {
                        Ok(0) | Err(_) => return SessionOutcome::Disconnected,
                        Ok(_) => {}
                    }
                }
                () = tokio::time::sleep_until(deadline) => {
                    configure_pending_serials(
                        &current_online,
                        &mut configured_serials,
                        &mut retry_states,
                        Instant::now(),
                        &mut ensure_reverse,
                    )
                    .await;
                }
            }
        } else {
            // Blocking read with zero timer wakeup while idle or all connected devices are synced.
            match stream.read_buf(&mut read_buf).await {
                Ok(0) | Err(_) => return SessionOutcome::Disconnected,
                Ok(_) => {}
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::{Arc, Mutex};
    use tokio::net::TcpListener;

    async fn send_frame(sock: &mut TcpStream, body: &[u8]) {
        let hdr = format!("{:04x}", body.len());
        sock.write_all(hdr.as_bytes()).await.unwrap();
        sock.write_all(body).await.unwrap();
    }

    async fn read_adb_framed_cmd(sock: &mut TcpStream) -> String {
        let mut len_hex = [0u8; 4];
        sock.read_exact(&mut len_hex).await.unwrap();
        let len = usize::from_str_radix(std::str::from_utf8(&len_hex).unwrap(), 16).unwrap();
        let mut body = vec![0u8; len];
        sock.read_exact(&mut body).await.unwrap();
        String::from_utf8(body).unwrap()
    }

    #[tokio::test]
    async fn track_devices_tcp_reverse_multi_device_retry_and_reconnect() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        let attempts = Arc::new(AtomicUsize::new(0));
        let configured_log = Arc::new(Mutex::new(Vec::<String>::new()));

        let attempts_srv = Arc::clone(&attempts);
        let configured_srv = Arc::clone(&configured_log);

        let server = tokio::spawn(async move {
            // 1) Session 1 track-devices connects
            let (mut track1, _) = listener.accept().await.unwrap();
            assert_eq!(read_adb_framed_cmd(&mut track1).await, "host:track-devices");
            track1.write_all(b"OKAY").await.unwrap();

            // Device phone1 (fails first reverse attempt, retries cleanly!) + offline emulator
            send_frame(&mut track1, b"phone1\tdevice\nemulator-5554\toffline\n").await;

            // Mock ADB reverse RPCs:
            // Attempt 1 for phone1 -> host:transport + reverse:list-forward (empty) + reverse:forward -> FAIL
            let (mut c1, _) = listener.accept().await.unwrap();
            assert_eq!(read_adb_framed_cmd(&mut c1).await, "host:transport:phone1");
            c1.write_all(b"OKAY").await.unwrap();
            assert_eq!(read_adb_framed_cmd(&mut c1).await, "reverse:list-forward");
            c1.write_all(b"OKAY0000").await.unwrap();
            drop(c1);

            let (mut c2, _) = listener.accept().await.unwrap();
            assert_eq!(read_adb_framed_cmd(&mut c2).await, "host:transport:phone1");
            c2.write_all(b"OKAY").await.unwrap();
            assert_eq!(
                read_adb_framed_cmd(&mut c2).await,
                "reverse:forward:tcp:53318;tcp:53318"
            );
            c2.write_all(b"OKAYFAIL0008dev busy").await.unwrap();
            attempts_srv.fetch_add(1, Ordering::SeqCst);
            drop(c2);

            // Second device arrives while phone1 is in backoff -> only phone2 is configured now
            send_frame(
                &mut track1,
                b"phone1\tdevice\nphone2\tdevice\nemulator-5554\toffline\n",
            )
            .await;

            {
                let (mut l_conn, _) = listener.accept().await.unwrap();
                assert_eq!(
                    read_adb_framed_cmd(&mut l_conn).await,
                    "host:transport:phone2"
                );
                l_conn.write_all(b"OKAY").await.unwrap();
                assert_eq!(
                    read_adb_framed_cmd(&mut l_conn).await,
                    "reverse:list-forward"
                );
                // Notice tcp:533180 must NOT satisfy tcp:53318!
                let list = b"UsbFfs tcp:533180 tcp:533180\n";
                l_conn
                    .write_all(format!("OKAY{:04x}", list.len()).as_bytes())
                    .await
                    .unwrap();
                l_conn.write_all(list).await.unwrap();
                drop(l_conn);

                let (mut f_conn, _) = listener.accept().await.unwrap();
                assert_eq!(
                    read_adb_framed_cmd(&mut f_conn).await,
                    "host:transport:phone2"
                );
                f_conn.write_all(b"OKAY").await.unwrap();
                assert_eq!(
                    read_adb_framed_cmd(&mut f_conn).await,
                    "reverse:forward:tcp:53318;tcp:53318"
                );
                f_conn.write_all(b"OKAYOKAY").await.unwrap();
                attempts_srv.fetch_add(1, Ordering::SeqCst);
                configured_srv.lock().unwrap().push("phone2".to_string());
                drop(f_conn);
            }

            // Unplug phone1 then replug phone1 -> backoff cleared on unplug, succeeds immediately on replug
            send_frame(&mut track1, b"phone2\tdevice\n").await;
            send_frame(&mut track1, b"phone1\tdevice\nphone2\tdevice\n").await;

            {
                let (mut l_conn, _) = listener.accept().await.unwrap();
                assert_eq!(
                    read_adb_framed_cmd(&mut l_conn).await,
                    "host:transport:phone1"
                );
                l_conn.write_all(b"OKAY").await.unwrap();
                assert_eq!(
                    read_adb_framed_cmd(&mut l_conn).await,
                    "reverse:list-forward"
                );
                l_conn.write_all(b"OKAY0000").await.unwrap();
                drop(l_conn);

                let (mut f_conn, _) = listener.accept().await.unwrap();
                assert_eq!(
                    read_adb_framed_cmd(&mut f_conn).await,
                    "host:transport:phone1"
                );
                f_conn.write_all(b"OKAY").await.unwrap();
                assert_eq!(
                    read_adb_framed_cmd(&mut f_conn).await,
                    "reverse:forward:tcp:53318;tcp:53318"
                );
                f_conn.write_all(b"OKAYOKAY").await.unwrap();
                attempts_srv.fetch_add(1, Ordering::SeqCst);
                configured_srv.lock().unwrap().push("phone1".to_string());
                drop(f_conn);
            }

            // Unplug all devices -> clears configured_serials; replug phone1 -> already listed on list-forward!
            send_frame(&mut track1, b"").await;
            send_frame(&mut track1, b"phone1\tdevice\n").await;

            let (mut l_replug, _) = listener.accept().await.unwrap();
            assert_eq!(
                read_adb_framed_cmd(&mut l_replug).await,
                "host:transport:phone1"
            );
            l_replug.write_all(b"OKAY").await.unwrap();
            assert_eq!(
                read_adb_framed_cmd(&mut l_replug).await,
                "reverse:list-forward"
            );
            let existing = b"UsbFfs tcp:53318 tcp:53318\n";
            l_replug
                .write_all(format!("OKAY{:04x}", existing.len()).as_bytes())
                .await
                .unwrap();
            l_replug.write_all(existing).await.unwrap();
            drop(l_replug);

            // Disconnect session 1 -> session 2 reconnects cleanly
            drop(track1);

            let (mut track2, _) = listener.accept().await.unwrap();
            assert_eq!(read_adb_framed_cmd(&mut track2).await, "host:track-devices");
            track2.write_all(b"OKAY").await.unwrap();
            drop(track2);
        });

        let outcome1 = run_track_devices_session(
            addr,
            || {},
            |serial| async move { ensure_adb_reverse_for_serial(addr, &serial, 53318).await },
        )
        .await;
        assert_eq!(outcome1, SessionOutcome::Disconnected);

        let outcome2 = run_track_devices_session(
            addr,
            || {},
            |serial| async move { ensure_adb_reverse_for_serial(addr, &serial, 53318).await },
        )
        .await;
        assert_eq!(outcome2, SessionOutcome::Disconnected);

        server.await.unwrap();
        assert_eq!(attempts.load(Ordering::SeqCst), 3);
        assert_eq!(
            *configured_log.lock().unwrap(),
            vec!["phone2".to_string(), "phone1".to_string()]
        );
    }

    #[test]
    fn sysfs_scan_and_auto_start_tracker_retries_on_failure_and_locks_on_started() {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let sysfs_root = std::env::temp_dir().join(format!(
            "silentinput_sysfs_test_{}_{nanos}",
            std::process::id()
        ));
        let iface_adb = sysfs_root.join("1-2:1.0");
        let iface_hid = sysfs_root.join("1-3:1.0");
        std::fs::create_dir_all(&iface_adb).unwrap();
        std::fs::create_dir_all(&iface_hid).unwrap();

        std::fs::write(iface_adb.join("bInterfaceClass"), "ff\n").unwrap();
        std::fs::write(iface_adb.join("bInterfaceSubClass"), "42\n").unwrap();
        std::fs::write(iface_adb.join("bInterfaceProtocol"), "01\n").unwrap();

        std::fs::write(iface_hid.join("bInterfaceClass"), "03\n").unwrap();
        std::fs::write(iface_hid.join("bInterfaceSubClass"), "01\n").unwrap();
        std::fs::write(iface_hid.join("bInterfaceProtocol"), "01\n").unwrap();

        let scanned = scan_sysfs_adb_interfaces_in(&sysfs_root);
        assert_eq!(scanned, BTreeSet::from(["1-2:1.0".to_string()]));

        let mut tracker = AdbAutoStartTracker::default();
        assert!(!tracker.has_new_adb_interface(&BTreeSet::new()));

        // Failed / NotInstalled MUST NOT lock current interfaces so backoff retries still work
        assert!(tracker.has_new_adb_interface(&scanned));
        tracker.record_start_attempt(&scanned, AdbStartOutcome::Failed);
        assert!(tracker.has_new_adb_interface(&scanned));
        tracker.record_start_attempt(&scanned, AdbStartOutcome::NotInstalled);
        assert!(tracker.has_new_adb_interface(&scanned));

        // Started locks current interfaces -> after `adb kill-server`, does NOT respawn
        tracker.record_start_attempt(&scanned, AdbStartOutcome::Started);
        assert!(!tracker.has_new_adb_interface(&scanned));

        // Unplug clears state; re-plug triggers start again
        assert!(!tracker.has_new_adb_interface(&BTreeSet::new()));
        assert!(tracker.has_new_adb_interface(&scanned));

        let _ = std::fs::remove_dir_all(&sysfs_root);
    }

    #[tokio::test]
    async fn serial_retry_backoff_steps_log_dedup_and_cleanup() {
        let base = Instant::now();
        let mut retry = SerialRetryState {
            failures: 0,
            next_retry_at: base,
            last_error: None,
        };

        // 5s -> 5s -> 10s -> 30s -> 60s -> 60s, and log dedup only prints on new error text
        assert!(retry.record_failure("err A", base));
        assert_eq!(retry.next_retry_at, base + Duration::from_secs(5));
        assert!(!retry.record_failure("err A", base + Duration::from_secs(5)));
        assert_eq!(retry.next_retry_at, base + Duration::from_secs(10));
        assert!(retry.record_failure("err B", base + Duration::from_secs(10)));
        assert_eq!(retry.next_retry_at, base + Duration::from_secs(20));
        assert!(!retry.record_failure("err B", base + Duration::from_secs(20)));
        assert_eq!(retry.next_retry_at, base + Duration::from_secs(50));
        assert!(!retry.record_failure("err B", base + Duration::from_secs(50)));
        assert_eq!(retry.next_retry_at, base + Duration::from_secs(110));
        assert!(!retry.record_failure("err B", base + Duration::from_secs(110)));
        assert_eq!(retry.next_retry_at, base + Duration::from_secs(170));

        // Verify configure_pending_serials respects next_retry_at & cleans up on success/unplug
        let online = vec!["s1".to_string()];
        let mut configured = HashSet::new();
        let mut retry_states = HashMap::new();
        let mut calls = 0usize;

        configure_pending_serials(
            &online,
            &mut configured,
            &mut retry_states,
            base,
            &mut |_| {
                calls += 1;
                async { Err("busy".to_string()) }
            },
        )
        .await;
        assert_eq!(calls, 1);
        assert!(retry_states.contains_key("s1"));

        // Before +5s deadline: skipped
        configure_pending_serials(
            &online,
            &mut configured,
            &mut retry_states,
            base + Duration::from_secs(4),
            &mut |_| {
                calls += 1;
                async { Ok(()) }
            },
        )
        .await;
        assert_eq!(calls, 1);

        // At +5s deadline: retried and succeeds, cleaning retry state
        configure_pending_serials(
            &online,
            &mut configured,
            &mut retry_states,
            base + Duration::from_secs(5),
            &mut |_| {
                calls += 1;
                async { Ok(()) }
            },
        )
        .await;
        assert_eq!(calls, 2);
        assert!(configured.contains("s1"));
        assert!(!retry_states.contains_key("s1"));

        // Fail once then unplug -> cleans retry_states entry
        let online2 = vec!["s2".to_string()];
        configure_pending_serials(
            &online2,
            &mut configured,
            &mut retry_states,
            base + Duration::from_secs(6),
            &mut |_| async { Err("offline".to_string()) },
        )
        .await;
        assert!(retry_states.contains_key("s2"));
        configure_pending_serials(
            &[],
            &mut configured,
            &mut retry_states,
            base + Duration::from_secs(7),
            &mut |_| async { Ok(()) },
        )
        .await;
        assert!(configured.is_empty());
        assert!(retry_states.is_empty());
    }
}
