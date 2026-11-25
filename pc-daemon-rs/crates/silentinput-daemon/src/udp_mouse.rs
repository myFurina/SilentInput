use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};

use evdev::{EventType, InputEvent, RelativeAxisType};
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, AES_256_GCM};
use ring::rand::{SecureRandom, SystemRandom};
use silentinput_core::acl::normalize_ip_str;
use tokio::net::UdpSocket;

use crate::input::InputController;

pub const UDP_MAGIC: [u8; 2] = *b"SI";
pub const UDP_VERSION: u8 = 0x01;
pub const PKT_PROBE: u8 = 0x01;
pub const PKT_PROBE_ACK: u8 = 0x02;
pub const PKT_MOUSE_MOVE: u8 = 0x03;

pub const HEADER_LEN: usize = 20;
pub const GCM_TAG_LEN: usize = 16;
pub const MAX_UDP_PACKET_LEN: usize = 128;

pub const DIR_CLIENT_TO_SERVER: u32 = 1;
pub const DIR_SERVER_TO_CLIENT: u32 = 2;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UdpSessionCredentials {
    pub port: u16,
    pub session_id: u64,
    pub session_id_hex: String,
    pub key_b64: String,
}

#[derive(Default)]
pub struct ReplayWindow {
    highest_seq: u64,
    bitmap: u64,
}

impl ReplayWindow {
    pub fn check_and_update(&mut self, seq: u64) -> bool {
        if seq == 0 {
            return false;
        }
        if seq > self.highest_seq {
            let shift = seq - self.highest_seq;
            if shift >= 64 {
                self.bitmap = 1;
            } else {
                self.bitmap = (self.bitmap << shift) | 1;
            }
            self.highest_seq = seq;
            true
        } else {
            let diff = self.highest_seq - seq;
            if diff >= 64 {
                return false;
            }
            let mask = 1u64 << diff;
            if (self.bitmap & mask) != 0 {
                return false;
            }
            self.bitmap |= mask;
            true
        }
    }
}

pub struct UdpSession {
    pub peer_ip: String,
    key: LessSafeKey,
    replay: Mutex<ReplayWindow>,
    server_seq: AtomicU64,
}

pub struct UdpSessionRegistry {
    udp_port: u16,
    enabled: bool,
    rng: SystemRandom,
    sessions: RwLock<HashMap<u64, Arc<UdpSession>>>,
}

impl UdpSessionRegistry {
    pub fn new(udp_port: u16, enabled: bool) -> Arc<Self> {
        Arc::new(Self {
            udp_port,
            enabled,
            rng: SystemRandom::new(),
            sessions: RwLock::new(HashMap::new()),
        })
    }

    pub fn disabled() -> Arc<Self> {
        Self::new(0, false)
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled && self.udp_port > 0
    }

    pub fn create_session(&self, peer_ip: &str) -> Option<UdpSessionCredentials> {
        if !self.is_enabled() {
            return None;
        }
        let norm_ip = normalize_ip_str(peer_ip);
        if norm_ip.is_empty() {
            return None;
        }

        let mut raw_key = [0u8; 32];
        if self.rng.fill(&mut raw_key).is_err() {
            return None;
        }

        let mut session_id = 0u64;
        for _ in 0..8 {
            let mut id_bytes = [0u8; 8];
            if self.rng.fill(&mut id_bytes).is_err() {
                return None;
            }
            let candidate = u64::from_be_bytes(id_bytes);
            if candidate != 0 {
                let guard = self.sessions.read().unwrap_or_else(|e| e.into_inner());
                if !guard.contains_key(&candidate) {
                    session_id = candidate;
                    break;
                }
            }
        }
        if session_id == 0 {
            return None;
        }

        let unbound = UnboundKey::new(&AES_256_GCM, &raw_key).ok()?;
        let session = Arc::new(UdpSession {
            peer_ip: norm_ip,
            key: LessSafeKey::new(unbound),
            replay: Mutex::new(ReplayWindow::default()),
            server_seq: AtomicU64::new(0),
        });

        {
            let mut guard = self.sessions.write().unwrap_or_else(|e| e.into_inner());
            guard.insert(session_id, session);
        }

        Some(UdpSessionCredentials {
            port: self.udp_port,
            session_id,
            session_id_hex: format!("{session_id:016x}"),
            key_b64: encode_base64(&raw_key),
        })
    }

    pub fn remove_session(&self, session_id: u64) {
        let mut guard = self.sessions.write().unwrap_or_else(|e| e.into_inner());
        guard.remove(&session_id);
    }

    #[cfg(test)]
    pub fn session_count(&self) -> usize {
        self.sessions
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .len()
    }

    fn get_session(&self, session_id: u64) -> Option<Arc<UdpSession>> {
        self.sessions
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .get(&session_id)
            .cloned()
    }
}

fn make_nonce(dir: u32, seq: u64) -> Nonce {
    let mut bytes = [0u8; 12];
    bytes[0..4].copy_from_slice(&dir.to_be_bytes());
    bytes[4..12].copy_from_slice(&seq.to_be_bytes());
    Nonce::assume_unique_for_key(bytes)
}

pub fn seal_udp_packet(
    key: &LessSafeKey,
    pkt_type: u8,
    session_id: u64,
    dir: u32,
    seq: u64,
    plaintext: &[u8],
) -> Option<Vec<u8>> {
    if plaintext.len() + HEADER_LEN + GCM_TAG_LEN > MAX_UDP_PACKET_LEN {
        return None;
    }
    let mut out = Vec::with_capacity(HEADER_LEN + plaintext.len() + GCM_TAG_LEN);
    out.extend_from_slice(&UDP_MAGIC);
    out.push(UDP_VERSION);
    out.push(pkt_type);
    out.extend_from_slice(&session_id.to_be_bytes());
    out.extend_from_slice(&seq.to_be_bytes());

    let mut aad_bytes = [0u8; HEADER_LEN];
    aad_bytes.copy_from_slice(&out[..HEADER_LEN]);

    out.extend_from_slice(plaintext);
    let mut payload_and_tag = out.split_off(HEADER_LEN);
    key.seal_in_place_append_tag(
        make_nonce(dir, seq),
        Aad::from(&aad_bytes),
        &mut payload_and_tag,
    )
    .ok()?;
    out.extend_from_slice(&payload_and_tag);
    Some(out)
}

pub fn handle_udp_packet(
    packet: &[u8],
    peer_addr: SocketAddr,
    registry: &UdpSessionRegistry,
    input: &dyn InputController,
) -> Option<Vec<u8>> {
    if packet.len() < HEADER_LEN + GCM_TAG_LEN || packet.len() > MAX_UDP_PACKET_LEN {
        return None;
    }
    if packet[0..2] != UDP_MAGIC || packet[2] != UDP_VERSION {
        return None;
    }

    let pkt_type = packet[3];
    if pkt_type != PKT_PROBE && pkt_type != PKT_MOUSE_MOVE {
        return None;
    }

    let session_id = u64::from_be_bytes(packet[4..12].try_into().ok()?);
    let seq = u64::from_be_bytes(packet[12..20].try_into().ok()?);
    if seq == 0 {
        return None;
    }

    let session = registry.get_session(session_id)?;
    let norm_ip = normalize_ip_str(&peer_addr.ip().to_string());
    if norm_ip != session.peer_ip {
        return None;
    }

    let mut ct_buf = [0u8; MAX_UDP_PACKET_LEN];
    let ct_len = packet.len() - HEADER_LEN;
    ct_buf[..ct_len].copy_from_slice(&packet[HEADER_LEN..]);

    let pt = session
        .key
        .open_in_place(
            make_nonce(DIR_CLIENT_TO_SERVER, seq),
            Aad::from(&packet[..HEADER_LEN]),
            &mut ct_buf[..ct_len],
        )
        .ok()?;

    // Enforce anti-replay ONLY after cryptographic authentication succeeds
    {
        let mut replay = session.replay.lock().unwrap_or_else(|e| e.into_inner());
        if !replay.check_and_update(seq) {
            return None;
        }
    }

    match pkt_type {
        PKT_PROBE => {
            if pt.len() < 4 {
                return None;
            }
            let ack_seq = session.server_seq.fetch_add(1, Ordering::Relaxed) + 1;
            seal_udp_packet(
                &session.key,
                PKT_PROBE_ACK,
                session_id,
                DIR_SERVER_TO_CLIENT,
                ack_seq,
                &pt[..4],
            )
        }
        PKT_MOUSE_MOVE => {
            if pt.len() < 8 {
                return None;
            }
            let dx = i16::from_be_bytes([pt[0], pt[1]]) as i32;
            let dy = i16::from_be_bytes([pt[2], pt[3]]) as i32;
            let wheel = i16::from_be_bytes([pt[4], pt[5]]) as i32;
            if (dx != 0 || dy != 0 || wheel != 0) && input.ensure_ready(false) {
                let mut events = Vec::with_capacity(3);
                if dx != 0 {
                    events.push(InputEvent::new(
                        EventType::RELATIVE,
                        RelativeAxisType::REL_X.0,
                        dx,
                    ));
                }
                if dy != 0 {
                    events.push(InputEvent::new(
                        EventType::RELATIVE,
                        RelativeAxisType::REL_Y.0,
                        dy,
                    ));
                }
                if wheel != 0 {
                    events.push(InputEvent::new(
                        EventType::RELATIVE,
                        RelativeAxisType::REL_WHEEL.0,
                        wheel,
                    ));
                }
                input.emit_uinput_events(&events);
            }
            None
        }
        _ => None,
    }
}

pub async fn run_udp_mouse_server(
    socket: UdpSocket,
    registry: Arc<UdpSessionRegistry>,
    input: Arc<dyn InputController>,
) {
    let mut buf = [0u8; 256];
    loop {
        match socket.recv_from(&mut buf).await {
            Ok((len, peer_addr)) => {
                if let Some(reply) = handle_udp_packet(&buf[..len], peer_addr, &registry, &*input) {
                    let _ = socket.send_to(&reply, peer_addr).await;
                }
            }
            Err(err) => {
                eprintln!("[SilentInput] UDP mouse recv error: {err}");
                tokio::time::sleep(std::time::Duration::from_millis(20)).await;
            }
        }
    }
}

pub fn encode_base64(bytes: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let b0 = chunk[0] as usize;
        let b1 = chunk.get(1).copied().unwrap_or(0) as usize;
        let b2 = chunk.get(2).copied().unwrap_or(0) as usize;
        let triple = (b0 << 16) | (b1 << 8) | b2;
        out.push(TABLE[(triple >> 18) & 0x3f] as char);
        out.push(TABLE[(triple >> 12) & 0x3f] as char);
        out.push(if chunk.len() > 1 {
            TABLE[(triple >> 6) & 0x3f] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            TABLE[triple & 0x3f] as char
        } else {
            '='
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr};
    use std::sync::atomic::AtomicBool;

    use crate::input::BoxFuture;

    #[derive(Default)]
    struct TestUdpInput {
        ready: AtomicBool,
        events: Mutex<Vec<Vec<(u16, u16, i32)>>>,
    }

    impl InputController for TestUdpInput {
        fn ensure_ready(&self, _log_failure: bool) -> bool {
            self.ready.load(Ordering::Acquire)
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
        fn copy_clipboard<'a>(&'a self, _content: &'a str) -> BoxFuture<'a, bool> {
            Box::pin(async { true })
        }
        fn emit_uinput_events(&self, events: &[InputEvent]) -> bool {
            let mapped = events
                .iter()
                .map(|ev| (ev.event_type().0, ev.code(), ev.value()))
                .collect();
            self.events.lock().unwrap().push(mapped);
            true
        }
        fn track_mouse_button(&self, _btn_code: u16, _pressed: bool, _conn_id: u64) {}
        fn release_connection_buttons(&self, _conn_id: u64) {}
    }

    fn decode_base64_for_test(s: &str) -> Vec<u8> {
        let mut out = Vec::new();
        let val = |c: u8| -> Option<u32> {
            match c {
                b'A'..=b'Z' => Some((c - b'A') as u32),
                b'a'..=b'z' => Some((c - b'a' + 26) as u32),
                b'0'..=b'9' => Some((c - b'0' + 52) as u32),
                b'+' => Some(62),
                b'/' => Some(63),
                _ => None,
            }
        };
        for chunk in s.as_bytes().chunks(4) {
            let a = val(chunk[0]).unwrap();
            let b = val(chunk[1]).unwrap();
            let c = if chunk[2] == b'=' { 0 } else { val(chunk[2]).unwrap() };
            let d = if chunk[3] == b'=' { 0 } else { val(chunk[3]).unwrap() };
            let t = (a << 18) | (b << 12) | (c << 6) | d;
            out.push(((t >> 16) & 0xff) as u8);
            if chunk[2] != b'=' {
                out.push(((t >> 8) & 0xff) as u8);
            }
            if chunk[3] != b'=' {
                out.push((t & 0xff) as u8);
            }
        }
        out
    }

    #[test]
    fn base64_matches_standard_rfc4648_vectors() {
        assert_eq!(encode_base64(b""), "");
        assert_eq!(encode_base64(b"f"), "Zg==");
        assert_eq!(encode_base64(b"fo"), "Zm8=");
        assert_eq!(encode_base64(b"foo"), "Zm9v");
        assert_eq!(encode_base64(b"foobar"), "Zm9vYmFy");
    }

    #[test]
    fn replay_window_handles_ordering_duplicates_and_stale_packets() {
        let mut rw = ReplayWindow::default();
        assert!(!rw.check_and_update(0));
        assert!(rw.check_and_update(1));
        assert!(rw.check_and_update(2));
        assert!(!rw.check_and_update(2)); // duplicate rejected
        assert!(rw.check_and_update(5));
        assert!(rw.check_and_update(4)); // out-of-order within window accepted once
        assert!(!rw.check_and_update(4)); // replay rejected
        assert!(rw.check_and_update(100));
        assert!(!rw.check_and_update(36)); // > 64 behind highest_seq rejected
        assert!(rw.check_and_update(37)); // 100 - 37 = 63 < 64 still valid
    }

    #[test]
    fn udp_probe_mouse_move_ip_binding_and_anti_replay() {
        let registry = UdpSessionRegistry::new(53318, true);
        let input = Arc::new(TestUdpInput {
            ready: AtomicBool::new(true),
            ..Default::default()
        });
        let creds = registry.create_session("::ffff:192.168.1.50").unwrap();
        assert_eq!(creds.port, 53318);
        assert_eq!(registry.session_count(), 1);

        let key_bytes = decode_base64_for_test(&creds.key_b64);
        assert_eq!(key_bytes.len(), 32);
        let client_key = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, &key_bytes).unwrap());

        let valid_peer = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 168, 1, 50)), 44444);
        let spoofed_peer = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 168, 1, 99)), 44444);

        // 1. Valid probe returns decryptable PROBE_ACK echoing 4-byte payload
        let probe_pkt = seal_udp_packet(
            &client_key,
            PKT_PROBE,
            creds.session_id,
            DIR_CLIENT_TO_SERVER,
            1,
            &0x11223344u32.to_be_bytes(),
        )
        .unwrap();
        let ack = handle_udp_packet(&probe_pkt, valid_peer, &registry, &*input).unwrap();
        assert_eq!(&ack[0..2], &UDP_MAGIC);
        assert_eq!(ack[3], PKT_PROBE_ACK);
        let ack_seq = u64::from_be_bytes(ack[12..20].try_into().unwrap());
        let mut ack_ct = ack[HEADER_LEN..].to_vec();
        let ack_pt = client_key
            .open_in_place(
                make_nonce(DIR_SERVER_TO_CLIENT, ack_seq),
                Aad::from(&ack[..HEADER_LEN]),
                &mut ack_ct,
            )
            .unwrap();
        assert_eq!(ack_pt, &0x11223344u32.to_be_bytes());

        // 2. Spoofed source IP is silently rejected even with valid ciphertext
        let move_payload = {
            let mut p = Vec::with_capacity(8);
            p.extend_from_slice(&12i16.to_be_bytes());
            p.extend_from_slice(&(-7i16).to_be_bytes());
            p.extend_from_slice(&0i16.to_be_bytes());
            p.extend_from_slice(&0u16.to_be_bytes());
            p
        };
        let move_pkt = seal_udp_packet(
            &client_key,
            PKT_MOUSE_MOVE,
            creds.session_id,
            DIR_CLIENT_TO_SERVER,
            2,
            &move_payload,
        )
        .unwrap();
        assert!(handle_udp_packet(&move_pkt, spoofed_peer, &registry, &*input).is_none());
        assert!(input.events.lock().unwrap().is_empty());

        // 3. Valid peer accepts seq=2 and emits REL_X + REL_Y
        assert!(handle_udp_packet(&move_pkt, valid_peer, &registry, &*input).is_none());
        {
            let evs = input.events.lock().unwrap();
            assert_eq!(evs.len(), 1);
            assert_eq!(
                evs[0],
                vec![
                    (EventType::RELATIVE.0, RelativeAxisType::REL_X.0, 12),
                    (EventType::RELATIVE.0, RelativeAxisType::REL_Y.0, -7),
                ]
            );
        }

        // 4. Replaying seq=2 is rejected
        assert!(handle_udp_packet(&move_pkt, valid_peer, &registry, &*input).is_none());
        assert_eq!(input.events.lock().unwrap().len(), 1);

        // 5. Removing session invalidates further packets
        registry.remove_session(creds.session_id);
        assert_eq!(registry.session_count(), 0);
        let move_pkt3 = seal_udp_packet(
            &client_key,
            PKT_MOUSE_MOVE,
            creds.session_id,
            DIR_CLIENT_TO_SERVER,
            3,
            &move_payload,
        )
        .unwrap();
        assert!(handle_udp_packet(&move_pkt3, valid_peer, &registry, &*input).is_none());
        assert_eq!(input.events.lock().unwrap().len(), 1);
    }
}
