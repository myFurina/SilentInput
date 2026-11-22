mod adb;
mod auth_limiter;
mod bluetooth;
mod input;
mod protocol;
mod ws_server;

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::sync::atomic::AtomicU64;
use std::sync::Arc;

use silentinput_core::acl::get_bind_addresses;
use silentinput_core::certs::ensure_certificates;
use silentinput_core::config::{ensure_config_dir_at, load_config};
use silentinput_core::paths::SilentInputPaths;
use silentinput_core::process::get_all_host_ips;
use tokio::net::TcpListener;
use tokio::signal::unix::{signal, SignalKind};

use crate::adb::maintain_adb_reverse;
use crate::auth_limiter::AuthRateLimiter;
use crate::bluetooth::{run_bluetooth_server, BtServerDeps};
use crate::input::{maintain_uinput_device, InputController, SystemInputController};
use crate::protocol::DAEMON_VERSION;
use crate::ws_server::{load_tls_acceptor, run_ws_listener, WsServerDeps};

fn cleanup_pid(paths: &SilentInputPaths) {
    let _ = fs::remove_file(paths.pid_file());
}

#[tokio::main]
async fn main() {
    if let Some(arg) = std::env::args().nth(1) {
        match arg.as_str() {
            "--version" | "-V" => {
                println!("silentinput-daemon {DAEMON_VERSION}");
                return;
            }
            "--help" | "-h" => {
                println!("silentinput-daemon {DAEMON_VERSION}\nHeadless SilentInput PC companion daemon.");
                return;
            }
            _ => {}
        }
    }

    let paths = SilentInputPaths::system();
    let _ = ensure_config_dir_at(&paths);
    let pid_path = paths.pid_file();
    let _ = fs::write(&pid_path, std::process::id().to_string());
    let _ = fs::set_permissions(&pid_path, fs::Permissions::from_mode(0o600));

    let input_impl = SystemInputController::new();
    let paste_lock = input_impl.paste_lock();
    let input: Arc<dyn InputController> = input_impl;

    input.ensure_ready(true);
    let config = load_config();
    let port = config.port;
    let bind_addrs = get_bind_addresses(&config);

    let auth_limiter = Arc::new(AuthRateLimiter::new());
    let next_conn_id = Arc::new(AtomicU64::new(1));
    let paste_semaphore = Arc::new(tokio::sync::Semaphore::new(
        protocol::MAX_PENDING_PASTE_TASKS,
    ));

    let uinput_handle = tokio::spawn(maintain_uinput_device(Arc::clone(&input)));
    let adb_handle = tokio::spawn(maintain_adb_reverse(port));
    let mut bt_deps = BtServerDeps::new(
        Arc::clone(&auth_limiter),
        Arc::clone(&input),
        Arc::clone(&paste_lock),
        Arc::clone(&next_conn_id),
    );
    bt_deps.paste_semaphore = Arc::clone(&paste_semaphore);
    let bt_handle = tokio::spawn(run_bluetooth_server(bt_deps));

    let host_ips = get_all_host_ips();
    let mut tls_acceptor = None;
    if config.enable_tls {
        match ensure_certificates() {
            Ok((cert_path, key_path)) => match load_tls_acceptor(&cert_path, &key_path) {
                Ok(acceptor) => {
                    tls_acceptor = Some(acceptor);
                    println!("[SilentInput] TLS Traffic Encryption (WSS) activated");
                }
                Err(err) => {
                    eprintln!("[SilentInput] Warning: Failed to load TLS certs: {err}");
                }
            },
            Err(err) => {
                eprintln!("[SilentInput] Warning: Failed to ensure TLS certs: {err}");
            }
        }
    }

    println!(
        "[SilentInput] Starting daemon version {DAEMON_VERSION} (PID: {})",
        std::process::id()
    );
    println!("[SilentInput] Port: {port} | Listening on: {bind_addrs:?}");
    println!(
        "[SilentInput] Available Host IPs: {}",
        if host_ips.is_empty() {
            "None".to_string()
        } else {
            host_ips.join(", ")
        }
    );
    println!(
        "[SilentInput] Access Control Mode: {}",
        config.access_control_mode
    );
    println!("[SilentInput] Wireless Authentication: Always Enabled (Token required)");
    println!(
        "[SilentInput] Traffic Encryption: {}",
        if tls_acceptor.is_some() {
            "TLS Enabled (WSS)"
        } else {
            "Plaintext (WS)"
        }
    );

    let mut ws_deps = WsServerDeps::new(
        Arc::clone(&auth_limiter),
        Arc::clone(&input),
        Arc::clone(&paste_lock),
        Arc::clone(&next_conn_id),
    );
    ws_deps.paste_semaphore = paste_semaphore;

    let mut accept_handles = Vec::new();
    for addr in &bind_addrs {
        let bind_spec = format!("{addr}:{port}");
        match TcpListener::bind(&bind_spec).await {
            Ok(listener) => {
                let proto = if tls_acceptor.is_some() {
                    "WSS (TLS)"
                } else {
                    "WS"
                };
                println!("[SilentInput] Server listening on {bind_spec} ({proto})");
                accept_handles.push(tokio::spawn(run_ws_listener(
                    listener,
                    tls_acceptor.clone(),
                    ws_deps.clone(),
                )));
            }
            Err(err) => {
                eprintln!("[SilentInput] Warning: Failed to bind to {bind_spec}: {err}");
            }
        }
    }

    if accept_handles.is_empty() {
        eprintln!("[SilentInput] Error: Could not bind to any address!");
        uinput_handle.abort();
        adb_handle.abort();
        bt_handle.abort();
        cleanup_pid(&paths);
        return;
    }

    let mut sigterm = signal(SignalKind::terminate()).ok();
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = async {
            if let Some(ref mut s) = sigterm {
                s.recv().await;
            } else {
                std::future::pending::<()>().await;
            }
        } => {}
    }

    println!("\n[SilentInput] Shutting down daemon...");
    for handle in accept_handles {
        handle.abort();
    }
    uinput_handle.abort();
    adb_handle.abort();
    bt_handle.abort();
    cleanup_pid(&paths);
}

#[cfg(test)]
mod tests {
    use std::net::SocketAddr;
    use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
    use std::sync::{Arc, Mutex};
    use std::time::{Duration, Instant};

    use evdev::{EventType, InputEvent, Key};
    use futures_util::{SinkExt, StreamExt};
    use serde_json::{json, Value};
    use silentinput_core::config::SilentInputConfig;
    use silentinput_core::keymap::normalize_line_endings;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::{TcpListener, TcpStream};
    use tokio_tungstenite::connect_async;
    use tokio_tungstenite::tungstenite::client::IntoClientRequest;
    use tokio_tungstenite::tungstenite::Message;

    use crate::auth_limiter::{
        AuthRateLimiter, AUTH_FAILURE_MAX_ENTRIES, AUTH_FAILURE_TTL_SECS, AUTH_LOCKOUT_SECS,
        AUTH_MAX_FAILURES,
    };
    use crate::input::{BoxFuture, InputController, MouseHoldGuard};
    use crate::protocol::{process_message, safe_round_int, SessionContext};
    use crate::ws_server::{handle_tcp_client, WsServerDeps};

    #[derive(Default)]
    struct MockInput {
        graphical: AtomicBool,
        clipboard_ok: AtomicBool,
        slow_type_ms: AtomicU64,
        typed_texts: Mutex<Vec<String>>,
        combos: Mutex<Vec<String>>,
        uinput_events: Mutex<Vec<Vec<(u16, u16, i32)>>>,
        held: Mutex<std::collections::HashMap<u16, std::collections::HashSet<u64>>>,
    }

    impl MockInput {
        fn new() -> Arc<Self> {
            Arc::new(Self {
                graphical: AtomicBool::new(true),
                clipboard_ok: AtomicBool::new(true),
                slow_type_ms: AtomicU64::new(0),
                ..Default::default()
            })
        }
    }

    impl InputController for MockInput {
        fn ensure_ready(&self, _log_failure: bool) -> bool {
            true
        }

        fn has_uinput_device(&self) -> bool {
            true
        }

        fn is_graphical_session(&self) -> bool {
            self.graphical.load(Ordering::Acquire)
        }

        fn emit_key_combination<'a>(&'a self, combo: &'a str) -> BoxFuture<'a, ()> {
            let combo_owned = combo.to_string();
            Box::pin(async move {
                self.combos
                    .lock()
                    .unwrap()
                    .push(combo_owned);
            })
        }

        fn type_text<'a>(&'a self, text: &'a str) -> BoxFuture<'a, bool> {
            let owned = normalize_line_endings(text);
            Box::pin(async move {
                let delay = self.slow_type_ms.load(Ordering::Acquire);
                if delay > 0 {
                    tokio::time::sleep(Duration::from_millis(delay)).await;
                }
                self.typed_texts.lock().unwrap().push(owned);
                true
            })
        }

        fn copy_clipboard<'a>(&'a self, _text: &'a str) -> BoxFuture<'a, bool> {
            Box::pin(async move { self.clipboard_ok.load(Ordering::Acquire) })
        }

        fn emit_uinput_events(&self, events: &[InputEvent]) -> bool {
            let tuples: Vec<(u16, u16, i32)> = events
                .iter()
                .map(|ev| (ev.event_type().0, ev.code(), ev.value()))
                .collect();
            self.uinput_events.lock().unwrap().push(tuples);
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

    #[test]
    fn auth_limiter_ttl_and_capacity_eviction() {
        let limiter = AuthRateLimiter::new();
        let t0 = Instant::now();

        for i in 0..50 {
            assert!(!limiter.record_failure_at(&format!("10.0.0.{i}"), t0));
        }
        assert_eq!(limiter.len(), 50);

        // After TTL expires, stale entries are pruned automatically
        let after_ttl = t0 + Duration::from_secs(AUTH_FAILURE_TTL_SECS + 5);
        assert!(!limiter.is_locked_at("10.0.0.0", after_ttl));
        assert_eq!(limiter.len(), 0);

        // Exceeding MAX_ENTRIES evicts oldest
        for i in 0..(AUTH_FAILURE_MAX_ENTRIES + 25) {
            limiter.record_failure_at(&format!("172.16.{}.{}", i / 256, i % 256), after_ttl);
        }
        assert!(limiter.len() <= AUTH_FAILURE_MAX_ENTRIES);

        // 5 failures trigger lockout
        let mut locked = false;
        for attempt in 1..=AUTH_MAX_FAILURES {
            locked = limiter.record_failure_at("192.168.1.99", after_ttl);
            assert_eq!(locked, attempt == AUTH_MAX_FAILURES);
        }
        assert!(locked);
        assert!(limiter.is_locked_at("192.168.1.99", after_ttl));
        assert!(!limiter.is_locked_at(
            "192.168.1.99",
            after_ttl + Duration::from_secs(AUTH_LOCKOUT_SECS + 1)
        ));
    }

    #[tokio::test]
    async fn protocol_auth_status_rate_limit_and_clipboard_failure() {
        let input = MockInput::new();
        let paste_lock = Arc::new(tokio::sync::Mutex::new(()));
        let paste_sem = Arc::new(tokio::sync::Semaphore::new(
            crate::protocol::MAX_PENDING_PASTE_TASKS,
        ));
        let limiter = AuthRateLimiter::new();
        let cfg = SilentInputConfig {
            auth_token: "secret12".to_string(),
            access_control_mode: "whitelist".to_string(),
            ..SilentInputConfig::default()
        };

        // get_status reports unicode_direct_typing dynamically
        input.graphical.store(false, Ordering::Release);
        let out_status = process_message(
            r#"{"type":"get_status"}"#,
            SessionContext {
                authenticated: false,
                config: &cfg,
                peer_ip: "127.0.0.1",
                conn_id: 1,
                auth_limiter: &limiter,
                input: Arc::clone(&input) as Arc<dyn InputController>,
                paste_lock: Arc::clone(&paste_lock),
                paste_semaphore: Arc::clone(&paste_sem),
            },
        )
        .await;
        let status_json: Value = serde_json::from_str(out_status.reply.as_deref().unwrap()).unwrap();
        assert_eq!(status_json["type"], "status");
        assert_eq!(status_json["unicode_direct_typing"], false);

        // Unauthenticated key command rejected with auth_required
        let out_unauth = process_message(
            r#"{"type":"key","key":"a"}"#,
            SessionContext {
                authenticated: false,
                config: &cfg,
                peer_ip: "192.168.1.50",
                conn_id: 1,
                auth_limiter: &limiter,
                input: Arc::clone(&input) as Arc<dyn InputController>,
                paste_lock: Arc::clone(&paste_lock),
                paste_semaphore: Arc::clone(&paste_sem),
            },
        )
        .await;
        assert!(!out_unauth.authenticated);
        let unauth_json: Value = serde_json::from_str(out_unauth.reply.as_deref().unwrap()).unwrap();
        assert_eq!(unauth_json["type"], "auth_required");

        // 5 failed auth attempts -> lockout with "Too many" message expected by Android NetworkClient
        for idx in 0..5 {
            let res = process_message(
                r#"{"type":"auth","token":"wrong"}"#,
                SessionContext {
                    authenticated: false,
                    config: &cfg,
                    peer_ip: "192.168.1.88",
                    conn_id: 2,
                    auth_limiter: &limiter,
                    input: Arc::clone(&input) as Arc<dyn InputController>,
                    paste_lock: Arc::clone(&paste_lock),
                    paste_semaphore: Arc::clone(&paste_sem),
                },
            )
            .await;
            assert!(!res.authenticated);
            assert_eq!(res.should_close, idx == 4);
        }
        let locked_res = process_message(
            r#"{"type":"auth","token":"secret12"}"#,
            SessionContext {
                authenticated: false,
                config: &cfg,
                peer_ip: "192.168.1.88",
                conn_id: 3,
                auth_limiter: &limiter,
                input: Arc::clone(&input) as Arc<dyn InputController>,
                paste_lock: Arc::clone(&paste_lock),
                paste_semaphore: Arc::clone(&paste_sem),
            },
        )
        .await;
        assert!(!locked_res.authenticated);
        assert!(locked_res.should_close);
        let locked_json: Value = serde_json::from_str(locked_res.reply.as_deref().unwrap()).unwrap();
        assert!(locked_json["message"]
            .as_str()
            .unwrap()
            .contains("Too many"));

        // Clipboard failure skips paste shortcut
        input.clipboard_ok.store(false, Ordering::Release);
        process_message(
            r#"{"type":"paste","content":"hello","mode":"terminal","enter":true}"#,
            SessionContext {
                authenticated: true,
                config: &cfg,
                peer_ip: "127.0.0.1",
                conn_id: 4,
                auth_limiter: &limiter,
                input: Arc::clone(&input) as Arc<dyn InputController>,
                paste_lock: Arc::clone(&paste_lock),
                paste_semaphore: Arc::clone(&paste_sem),
            },
        )
        .await;
        tokio::time::sleep(Duration::from_millis(80)).await;
        assert!(input.combos.lock().unwrap().is_empty());

        // Saturated paste queue rejects excess queued background typing tasks safely and notifies client
        let tiny_sem = Arc::new(tokio::sync::Semaphore::new(1));
        let _held_permit = Arc::clone(&tiny_sem).try_acquire_owned().unwrap();
        let saturated_res = process_message(
            r#"{"type":"paste","content":"dropped","mode":"keys"}"#,
            SessionContext {
                authenticated: true,
                config: &cfg,
                peer_ip: "127.0.0.1",
                conn_id: 5,
                auth_limiter: &limiter,
                input: Arc::clone(&input) as Arc<dyn InputController>,
                paste_lock,
                paste_semaphore: tiny_sem,
            },
        )
        .await;
        let busy_json: Value = serde_json::from_str(saturated_res.reply.as_deref().unwrap()).unwrap();
        assert_eq!(busy_json["type"], "paste_result");
        assert_eq!(busy_json["status"], "busy");
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert!(input.typed_texts.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn ws_server_rejects_origin_http_post_ignores_url_token_and_nonblocking_ping() {
        let input = MockInput::new();
        input.slow_type_ms.store(250, Ordering::Release);
        let paste_lock = Arc::new(tokio::sync::Mutex::new(()));
        let auth_limiter = Arc::new(AuthRateLimiter::new());
        let next_conn_id = Arc::new(AtomicU64::new(1));

        let mut deps = WsServerDeps::new(
            auth_limiter,
            Arc::clone(&input) as Arc<dyn InputController>,
            paste_lock,
            next_conn_id,
        );
        deps.max_ws_connections = 4;

        let cfg = SilentInputConfig {
            allow_usb_unauthenticated: false,
            auth_token: "goodtok1".to_string(),
            access_control_mode: "all".to_string(),
            ..SilentInputConfig::default()
        };

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr: SocketAddr = listener.local_addr().unwrap();

        let srv_deps = deps.clone();
        let srv_cfg = cfg.clone();
        let server_task = tokio::spawn(async move {
            loop {
                let (sock, peer) = listener.accept().await.unwrap();
                let d = srv_deps.clone();
                let c = srv_cfg.clone();
                tokio::spawn(async move {
                    handle_tcp_client(sock, peer, None, d, Some(c)).await;
                });
            }
        });

        // 1. Reject raw HTTP POST (closed immediately / 400 without upgrade)
        let mut raw_stream = TcpStream::connect(addr).await.unwrap();
        raw_stream
            .write_all(b"POST / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            .await
            .unwrap();
        let mut buf = [0u8; 256];
        let n = raw_stream.read(&mut buf).await.unwrap();
        let resp_str = String::from_utf8_lossy(&buf[..n]);
        assert!(
            n == 0 || resp_str.contains("400") || resp_str.contains("405"),
            "Expected immediate close or HTTP 4xx rejection, got: {resp_str}"
        );

        // 2. Reject WebSocket handshake with Origin header (403)
        let mut origin_req = format!("ws://{addr}/").into_client_request().unwrap();
        origin_req
            .headers_mut()
            .insert("Origin", "https://evil.example".parse().unwrap());
        let err = connect_async(origin_req).await.unwrap_err();
        let err_text = err.to_string();
        assert!(err_text.contains("403"), "Expected 403 rejection, got: {err_text}");

        // 3. URL query token (?token=goodtok1) must NOT authenticate connection
        let (mut ws, _) = connect_async(format!("ws://{addr}/?token=goodtok1"))
            .await
            .unwrap();
        ws.send(Message::Text(r#"{"type":"key","key":"a"}"#.to_string()))
            .await
            .unwrap();
        let msg = ws.next().await.unwrap().unwrap().into_text().unwrap();
        let v: Value = serde_json::from_str(&msg).unwrap();
        assert_eq!(v["type"], "auth_required");

        // 4. Proper JSON auth succeeds
        ws.send(Message::Text(
            r#"{"type":"auth","token":"goodtok1"}"#.to_string(),
        ))
        .await
        .unwrap();
        let auth_msg = ws.next().await.unwrap().unwrap().into_text().unwrap();
        let av: Value = serde_json::from_str(&auth_msg).unwrap();
        assert_eq!(av["type"], "auth_result");
        assert_eq!(av["status"], "ok");
        assert_eq!(av["unicode_direct_typing"], true);

        // 5. Long slow direct-typing task does NOT block concurrent ping/pong replies!
        ws.send(Message::Text(
            r#"{"type":"paste","content":"hello\r\nworld","mode":"keys"}"#.to_string(),
        ))
        .await
        .unwrap();
        let t_ping = Instant::now();
        ws.send(Message::Text(r#"{"type":"ping","t":4242}"#.to_string()))
            .await
            .unwrap();
        let pong_txt = ws.next().await.unwrap().unwrap().into_text().unwrap();
        let pong_elapsed = t_ping.elapsed();
        let pv: Value = serde_json::from_str(&pong_txt).unwrap();
        assert_eq!(pv["type"], "pong");
        assert_eq!(pv["t"], 4242);
        assert!(
            pong_elapsed < Duration::from_millis(120),
            "ping/pong blocked by typing: {pong_elapsed:?}"
        );

        // Wait for background type_text task to complete and confirm CRLF normalized to LF
        tokio::time::sleep(Duration::from_millis(320)).await;
        assert_eq!(
            input.typed_texts.lock().unwrap().as_slice(),
            &["hello\nworld".to_string()]
        );

        let _ = ws.close(None).await;
        server_task.abort();
    }

    #[tokio::test]
    async fn mouse_nan_safe_and_guard_releases_held_buttons_on_drop() {
        assert_eq!(safe_round_int(Some(&json!(3.6))), 4);
        assert_eq!(safe_round_int(Some(&json!("NaN"))), 0);
        assert_eq!(safe_round_int(Some(&json!("Infinity"))), 0);
        assert_eq!(safe_round_int(Some(&json!(true))), 0);

        let input = MockInput::new();
        let paste_lock = Arc::new(tokio::sync::Mutex::new(()));
        let paste_sem = Arc::new(tokio::sync::Semaphore::new(
            crate::protocol::MAX_PENDING_PASTE_TASKS,
        ));
        let limiter = AuthRateLimiter::new();
        let cfg = SilentInputConfig::default();

        {
            let _guard = MouseHoldGuard::new(Arc::clone(&input) as Arc<dyn InputController>, 77);
            process_message(
                r#"{"type":"mouse","action":"down_left"}"#,
                SessionContext {
                    authenticated: true,
                    config: &cfg,
                    peer_ip: "127.0.0.1",
                    conn_id: 77,
                    auth_limiter: &limiter,
                    input: Arc::clone(&input) as Arc<dyn InputController>,
                    paste_lock,
                    paste_semaphore: paste_sem,
                },
            )
            .await;
            assert!(!input.held.lock().unwrap().is_empty());
        } // MouseHoldGuard dropped -> BTN_LEFT released!

        assert!(input.held.lock().unwrap().is_empty());
        let events = input.uinput_events.lock().unwrap();
        assert_eq!(events.len(), 2);
        assert_eq!(events[0], vec![(EventType::KEY.0, Key::BTN_LEFT.code(), 1)]);
        assert_eq!(events[1], vec![(EventType::KEY.0, Key::BTN_LEFT.code(), 0)]);
    }
}
