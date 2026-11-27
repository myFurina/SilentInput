use std::collections::HashMap;
use std::fs::File;
use std::io::BufReader;
use std::net::SocketAddr;
use std::path::Path;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use futures_util::{SinkExt, StreamExt};
use silentinput_core::acl::{is_ip_allowed, is_localhost_ip, normalize_ip_str};
use silentinput_core::config::{
    load_config, load_config_without_rotation, SilentInputConfig,
};
use socket2::{SockRef, TcpKeepalive};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpListener;
use tokio_rustls::rustls::pki_types::{CertificateDer, PrivateKeyDer};
use tokio_rustls::rustls::ServerConfig;
use tokio_rustls::TlsAcceptor;
use tokio_tungstenite::accept_hdr_async_with_config;
use tokio_tungstenite::tungstenite::handshake::server::{ErrorResponse, Request, Response};
use tokio_tungstenite::tungstenite::protocol::WebSocketConfig;
use tokio_tungstenite::tungstenite::Message;

use crate::auth_limiter::AuthRateLimiter;
use crate::input::{InputController, MouseHoldGuard};
use crate::protocol::{process_message, SessionContext, MAX_PENDING_PASTE_TASKS};
use crate::udp_mouse::UdpSessionRegistry;

pub const MAX_WS_FRAME_SIZE: usize = 1024 * 1024; // 1 MiB
pub const MAX_WS_CONNECTIONS: usize = 32;
pub const MAX_WS_CONNECTIONS_PER_IP: usize = 8;
pub const WS_AUTH_DEADLINE_SECS: u64 = 15;
pub const WS_IDLE_TIMEOUT_SECS: u64 = 45;

struct ActiveWsGuard {
    counter: Arc<AtomicUsize>,
}

impl ActiveWsGuard {
    fn try_acquire(counter: Arc<AtomicUsize>, max: usize) -> Option<Self> {
        let mut cur = counter.load(Ordering::Acquire);
        loop {
            if cur >= max {
                return None;
            }
            match counter.compare_exchange_weak(cur, cur + 1, Ordering::AcqRel, Ordering::Acquire)
            {
                Ok(_) => return Some(Self { counter }),
                Err(observed) => cur = observed,
            }
        }
    }
}

impl Drop for ActiveWsGuard {
    fn drop(&mut self) {
        self.counter.fetch_sub(1, Ordering::AcqRel);
    }
}

struct PerIpConnGuard {
    map: Arc<Mutex<HashMap<String, usize>>>,
    ip: String,
}

impl PerIpConnGuard {
    fn try_acquire(map: Arc<Mutex<HashMap<String, usize>>>, ip: &str, max: usize) -> Option<Self> {
        let mut guard = map.lock().unwrap_or_else(|e| e.into_inner());
        let count = guard.get(ip).copied().unwrap_or(0);
        if count >= max {
            return None;
        }
        guard.insert(ip.to_string(), count + 1);
        drop(guard);
        Some(Self {
            map,
            ip: ip.to_string(),
        })
    }
}

impl Drop for PerIpConnGuard {
    fn drop(&mut self) {
        let mut guard = self.map.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(count) = guard.get_mut(&self.ip) {
            if *count <= 1 {
                guard.remove(&self.ip);
            } else {
                *count -= 1;
            }
        }
    }
}

pub fn load_tls_acceptor(cert_path: &Path, key_path: &Path) -> Result<TlsAcceptor, String> {
    let cert_file =
        File::open(cert_path).map_err(|e| format!("Failed to open {}: {e}", cert_path.display()))?;
    let mut cert_reader = BufReader::new(cert_file);
    let certs: Vec<CertificateDer<'static>> = rustls_pemfile::certs(&mut cert_reader)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| format!("Failed to parse cert PEM: {e}"))?;
    if certs.is_empty() {
        return Err("No certificates found in cert.pem".to_string());
    }

    let key_file =
        File::open(key_path).map_err(|e| format!("Failed to open {}: {e}", key_path.display()))?;
    let mut key_reader = BufReader::new(key_file);
    let key: PrivateKeyDer<'static> = rustls_pemfile::private_key(&mut key_reader)
        .map_err(|e| format!("Failed to parse key PEM: {e}"))?
        .ok_or_else(|| "No private key found in key.pem".to_string())?;

    let server_cfg = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certs, key)
        .map_err(|e| format!("Invalid TLS cert/key pair: {e}"))?;

    Ok(TlsAcceptor::from(Arc::new(server_cfg)))
}

fn enable_tcp_keepalive(stream: &tokio::net::TcpStream) {
    use std::os::fd::AsRawFd;
    let sock = SockRef::from(stream);
    let ka = TcpKeepalive::new()
        .with_time(Duration::from_secs(15))
        .with_interval(Duration::from_secs(5));
    let _ = sock.set_tcp_keepalive(&ka);
    let cnt: libc::c_int = 3;
    unsafe {
        libc::setsockopt(
            stream.as_raw_fd(),
            libc::IPPROTO_TCP,
            libc::TCP_KEEPCNT,
            &cnt as *const libc::c_int as *const libc::c_void,
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        );
    }
}

#[allow(clippy::result_large_err)]
pub fn reject_origin_handshake(
    req: &Request,
    resp: Response,
) -> Result<Response, ErrorResponse> {
    if req.headers().contains_key("origin") {
        let forbidden = Response::builder()
            .status(403)
            .header("Connection", "close")
            .body(Some("Forbidden: Origin header rejected".to_string()))
            .unwrap();
        return Err(forbidden);
    }
    Ok(resp)
}

#[derive(Clone)]
pub struct WsServerDeps {
    pub auth_limiter: Arc<AuthRateLimiter>,
    pub input: Arc<dyn InputController>,
    pub paste_lock: Arc<tokio::sync::Mutex<()>>,
    pub paste_semaphore: Arc<tokio::sync::Semaphore>,
    pub next_conn_id: Arc<AtomicU64>,
    pub active_ws: Arc<AtomicUsize>,
    pub active_per_ip: Arc<Mutex<HashMap<String, usize>>>,
    pub max_ws_connections: usize,
    pub max_connections_per_ip: usize,
    pub auth_deadline: Duration,
    pub idle_timeout: Duration,
    pub udp_registry: Arc<UdpSessionRegistry>,
}

impl WsServerDeps {
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
            active_ws: Arc::new(AtomicUsize::new(0)),
            active_per_ip: Arc::new(Mutex::new(HashMap::new())),
            max_ws_connections: MAX_WS_CONNECTIONS,
            max_connections_per_ip: MAX_WS_CONNECTIONS_PER_IP,
            auth_deadline: Duration::from_secs(WS_AUTH_DEADLINE_SECS),
            idle_timeout: Duration::from_secs(WS_IDLE_TIMEOUT_SECS),
            udp_registry: UdpSessionRegistry::disabled(),
        }
    }
}

struct UdpSessionGuard {
    registry: Arc<UdpSessionRegistry>,
    session_id: Option<u64>,
}

impl UdpSessionGuard {
    fn set(&mut self, id: u64) {
        if let Some(old) = self.session_id.replace(id) {
            if old != id {
                self.registry.remove_session(old);
            }
        }
    }
}

impl Drop for UdpSessionGuard {
    fn drop(&mut self) {
        if let Some(id) = self.session_id.take() {
            self.registry.remove_session(id);
        }
    }
}

pub async fn run_ws_listener(
    listener: TcpListener,
    tls_acceptor: Option<TlsAcceptor>,
    deps: WsServerDeps,
) {
    loop {
        match listener.accept().await {
            Ok((stream, peer_addr)) => {
                let tls = tls_acceptor.clone();
                let deps_clone = deps.clone();
                tokio::spawn(async move {
                    handle_tcp_client(stream, peer_addr, tls, deps_clone, None).await;
                });
            }
            Err(err) => {
                eprintln!("[SilentInput] Accept error: {err}");
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
        }
    }
}

pub async fn handle_tcp_client(
    stream: tokio::net::TcpStream,
    peer_addr: SocketAddr,
    tls_acceptor: Option<TlsAcceptor>,
    deps: WsServerDeps,
    config_override: Option<SilentInputConfig>,
) {
    let Some(_conn_guard) =
        ActiveWsGuard::try_acquire(Arc::clone(&deps.active_ws), deps.max_ws_connections)
    else {
        println!(
            "[SilentInput] Access REJECTED for {peer_addr}: too many active connections."
        );
        return;
    };

    let peer_ip = normalize_ip_str(&peer_addr.ip().to_string());
    let Some(_per_ip_guard) = PerIpConnGuard::try_acquire(
        Arc::clone(&deps.active_per_ip),
        &peer_ip,
        deps.max_connections_per_ip,
    ) else {
        println!(
            "[SilentInput] Access REJECTED for {peer_addr}: too many connections from peer IP."
        );
        return;
    };

    let _ = stream.set_nodelay(true);
    enable_tcp_keepalive(&stream);

    let initial_cfg = match config_override.as_ref() {
        Some(cfg) => cfg.clone(),
        None => match tokio::task::spawn_blocking(load_config_without_rotation).await {
            Ok(cfg) => cfg,
            Err(_) => return,
        },
    };

    if !is_ip_allowed(&peer_ip, &initial_cfg) {
        println!(
            "[SilentInput] Access REJECTED for {peer_addr}: blocked by custom IP policy."
        );
        return;
    }

    let is_localhost = is_localhost_ip(&peer_ip);
    if !is_localhost && deps.auth_limiter.is_locked(&peer_ip) {
        println!(
            "[SilentInput] Access REJECTED for {peer_addr}: temporarily rate-limited."
        );
        return;
    }

    let config = match config_override {
        Some(cfg) => cfg,
        None => match tokio::task::spawn_blocking(load_config).await {
            Ok(cfg) => cfg,
            Err(_) => return,
        },
    };

    let initial_authenticated = if is_localhost && config.allow_usb_unauthenticated {
        println!("[SilentInput] New connection from {peer_addr} (Local/USB: Auto-authenticated)");
        true
    } else {
        println!("[SilentInput] New connection from {peer_addr} (Wireless: Requires auth token)");
        false
    };

    if let Some(acceptor) = tls_acceptor {
        match tokio::time::timeout(Duration::from_secs(10), acceptor.accept(stream)).await {
            Ok(Ok(tls_stream)) => {
                handle_ws_stream(tls_stream, peer_addr, peer_ip, initial_authenticated, config, deps)
                    .await;
            }
            Ok(Err(err)) => {
                eprintln!("[SilentInput] TLS handshake failed for {peer_addr}: {err}");
            }
            Err(_) => {
                println!("[SilentInput] TLS handshake timeout for {peer_addr}");
            }
        }
    } else {
        handle_ws_stream(stream, peer_addr, peer_ip, initial_authenticated, config, deps).await;
    }
}

pub async fn handle_ws_stream<S>(
    stream: S,
    peer_addr: SocketAddr,
    peer_ip: String,
    initial_authenticated: bool,
    config: SilentInputConfig,
    deps: WsServerDeps,
) where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let ws_cfg = WebSocketConfig {
        max_message_size: Some(MAX_WS_FRAME_SIZE),
        max_frame_size: Some(MAX_WS_FRAME_SIZE),
        ..Default::default()
    };

    let ws_stream = match tokio::time::timeout(
        Duration::from_secs(10),
        accept_hdr_async_with_config(stream, reject_origin_handshake, Some(ws_cfg)),
    )
    .await
    {
        Ok(Ok(ws)) => ws,
        Ok(Err(_)) => return,
        Err(_) => return,
    };

    let conn_id = deps.next_conn_id.fetch_add(1, Ordering::Relaxed);
    let _mouse_guard = MouseHoldGuard::new(Arc::clone(&deps.input), conn_id);
    let mut udp_guard = UdpSessionGuard {
        registry: Arc::clone(&deps.udp_registry),
        session_id: None,
    };
    let (mut sink, mut stream_rx) = ws_stream.split();

    let mut authenticated = initial_authenticated;
    let auth_deadline = Instant::now() + deps.auth_deadline;

    loop {
        let next_res = if !authenticated {
            let now = Instant::now();
            if now >= auth_deadline {
                println!("[SilentInput] Unauthenticated handshake/auth timeout for {peer_addr}");
                break;
            }
            let remaining = auth_deadline.saturating_duration_since(now);
            match tokio::time::timeout(remaining, stream_rx.next()).await {
                Ok(item) => item,
                Err(_) => {
                    println!("[SilentInput] Unauthenticated handshake/auth timeout for {peer_addr}");
                    break;
                }
            }
        } else {
            match tokio::time::timeout(deps.idle_timeout, stream_rx.next()).await {
                Ok(item) => item,
                Err(_) => {
                    println!("[SilentInput] Authenticated WS idle timeout for {peer_addr}");
                    break;
                }
            }
        };

        let Some(msg_result) = next_res else {
            break;
        };

        match msg_result {
            Ok(Message::Text(txt)) => {
                let outcome = process_message(
                    &txt,
                    SessionContext {
                        authenticated,
                        config: &config,
                        peer_ip: &peer_ip,
                        conn_id,
                        auth_limiter: &deps.auth_limiter,
                        input: Arc::clone(&deps.input),
                        paste_lock: Arc::clone(&deps.paste_lock),
                        paste_semaphore: Arc::clone(&deps.paste_semaphore),
                        udp_registry: Some(&deps.udp_registry),
                    },
                )
                .await;

                authenticated = outcome.authenticated;
                if let Some(udp_sid) = outcome.udp_session_id {
                    udp_guard.set(udp_sid);
                }
                if let Some(reply) = outcome.reply {
                    if sink.send(Message::Text(reply)).await.is_err() {
                        break;
                    }
                }
                if outcome.should_close {
                    break;
                }
            }
            Ok(Message::Binary(_)) | Ok(Message::Ping(_)) | Ok(Message::Pong(_)) => continue,
            Ok(Message::Close(_)) | Ok(Message::Frame(_)) => break,
            Err(err) => {
                eprintln!("[SilentInput] WS stream error: {err}");
                break;
            }
        }
    }

    let _ = sink.close().await;
    println!("[SilentInput] Connection closed for {peer_addr}");
}
