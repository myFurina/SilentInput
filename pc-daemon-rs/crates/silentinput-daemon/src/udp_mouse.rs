use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};

use evdev::{EventType, InputEvent, RelativeAxisType};
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, AES_256_GCM};
use ring::rand::{SecureRandom, SystemRandom};
use silentinput_core::acl::normalize_ip_str;
use tokio::net::UdpSocket;

use crate::input::{push_wheel_events, InputController};

pub const UDP_MAGIC: [u8; 2] = *b"SI";
pub const UDP_VERSION: u8 = 0x01;
pub const PKT_PROBE: u8 = 0x01;
pub const PKT_PROBE_ACK: u8 = 0x02;
pub const PKT_MOUSE_MOVE: u8 = 0x03;

pub const FLAG_HI_RES_WHEEL: u16 = 0x0001;
pub const FLAG_FEC_PREV1: u16 = 0x0002;
pub const FLAG_FEC_PREV2: u16 = 0x0004;

pub const HEADER_LEN: usize = 20;
pub const GCM_TAG_LEN: usize = 16;
pub const MAX_UDP_PACKET_LEN: usize = 128;
const UDP_PACER_INTERVAL: std::time::Duration = std::time::Duration::from_millis(5);
const UDP_SMOOTHER_DRAIN_RATIO: f64 = 0.78;
const UDP_SMOOTHER_SNAP_THRESHOLD: i32 = 4;
const UDP_BURST_SMOOTH_THRESHOLD: i32 = 40;

#[derive(Default)]
struct UdpMoveSmoother {
    pending_dx: i32,
    pending_dy: i32,
    last_emit: Option<tokio::time::Instant>,
}

impl UdpMoveSmoother {
    fn push(&mut self, dx: i32, dy: i32) {
        self.pending_dx = self.pending_dx.saturating_add(dx);
        self.pending_dy = self.pending_dy.saturating_add(dy);
    }

    fn has_pending(&self) -> bool {
        self.pending_dx != 0 || self.pending_dy != 0
    }

    fn next_deadline(&self) -> Option<tokio::time::Instant> {
        if self.has_pending() {
            self.last_emit.map(|t| t + UDP_PACER_INTERVAL)
        } else {
            None
        }
    }

    fn take_step(&mut self, now: tokio::time::Instant) -> Option<(i32, i32)> {
        if !self.has_pending() {
            return None;
        }
        if let Some(last) = self.last_emit {
            if now.duration_since(last) < UDP_PACER_INTERVAL {
                return None;
            }
        }
        let max_abs = self.pending_dx.abs().max(self.pending_dy.abs());
        let (step_x, step_y) = if max_abs <= UDP_SMOOTHER_SNAP_THRESHOLD {
            (self.pending_dx, self.pending_dy)
        } else {
            let sx = (self.pending_dx as f64 * UDP_SMOOTHER_DRAIN_RATIO).round() as i32;
            let sy = (self.pending_dy as f64 * UDP_SMOOTHER_DRAIN_RATIO).round() as i32;
            (sx, sy)
        };
        self.pending_dx -= step_x;
        self.pending_dy -= step_y;
        self.last_emit = Some(now);
        Some((step_x, step_y))
    }
}

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
    wheel_hi_acc: Mutex<i32>,
    server_seq: AtomicU64,
    probe_logged: AtomicBool,
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
            wheel_hi_acc: Mutex::new(0),
            server_seq: AtomicU64::new(0),
            probe_logged: AtomicBool::new(false),
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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct DecodedUdpMove {
    dx: i32,
    dy: i32,
    wheel: i32,
    is_hi_res: bool,
}

enum UdpPacketAction {
    ProbeAck(Vec<u8>),
    MoveFrames {
        session: Arc<UdpSession>,
        frames: Vec<DecodedUdpMove>,
    },
}

fn parse_move_frame(bytes: &[u8]) -> (DecodedUdpMove, u16) {
    let dx = i16::from_be_bytes([bytes[0], bytes[1]]) as i32;
    let dy = i16::from_be_bytes([bytes[2], bytes[3]]) as i32;
    let wheel = i16::from_be_bytes([bytes[4], bytes[5]]) as i32;
    let flags = u16::from_be_bytes([bytes[6], bytes[7]]);
    (
        DecodedUdpMove {
            dx,
            dy,
            wheel,
            is_hi_res: (flags & FLAG_HI_RES_WHEEL) != 0,
        },
        flags,
    )
}

fn emit_rel_move(input: &dyn InputController, dx: i32, dy: i32) {
    if (dx == 0 && dy == 0) || !input.ensure_ready(false) {
        return;
    }
    let mut events = Vec::with_capacity(2);
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
    input.emit_uinput_events(&events);
}

fn emit_wheel_frames(input: &dyn InputController, session: &UdpSession, frames: &[DecodedUdpMove]) {
    if !frames.iter().any(|f| f.wheel != 0) || !input.ensure_ready(false) {
        return;
    }
    let mut events = Vec::with_capacity(frames.len() * 2);
    {
        let mut acc = session.wheel_hi_acc.lock().unwrap_or_else(|e| e.into_inner());
        for f in frames {
            if f.wheel != 0 {
                if f.is_hi_res {
                    push_wheel_events(&mut acc, f.wheel, 0, &mut events);
                } else {
                    push_wheel_events(&mut acc, 0, f.wheel, &mut events);
                }
            }
        }
    }
    if !events.is_empty() {
        input.emit_uinput_events(&events);
    }
}

fn decode_udp_packet_action(
    packet: &[u8],
    peer_addr: SocketAddr,
    registry: &UdpSessionRegistry,
) -> Option<UdpPacketAction> {
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
    let mut replay = session.replay.lock().unwrap_or_else(|e| e.into_inner());
    if !replay.check_and_update(seq) {
        return None;
    }

    match pkt_type {
        PKT_PROBE => {
            if pt.len() < 4 {
                return None;
            }
            drop(replay);
            if !session.probe_logged.swap(true, Ordering::AcqRel) {
                println!(
                    "[SilentInput] UDP low-latency mouse verified for {peer_addr} (session {session_id:016x})"
                );
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
            .map(UdpPacketAction::ProbeAck)
        }
        PKT_MOUSE_MOVE => {
            if pt.len() < 8 {
                return None;
            }
            let (cur_frame, cur_flags) = parse_move_frame(&pt[0..8]);
            let mut frames = Vec::with_capacity(3);
            if (cur_flags & FLAG_FEC_PREV2) != 0
                && pt.len() >= 24
                && seq > 2
                && replay.check_and_update(seq - 2)
            {
                frames.push(parse_move_frame(&pt[16..24]).0);
            }
            if (cur_flags & FLAG_FEC_PREV1) != 0
                && pt.len() >= 16
                && seq > 1
                && replay.check_and_update(seq - 1)
            {
                frames.push(parse_move_frame(&pt[8..16]).0);
            }
            frames.push(cur_frame);
            drop(replay);
            Some(UdpPacketAction::MoveFrames { session, frames })
        }
        _ => None,
    }
}

#[cfg(test)]
pub fn handle_udp_packet(
    packet: &[u8],
    peer_addr: SocketAddr,
    registry: &UdpSessionRegistry,
    input: &dyn InputController,
) -> Option<Vec<u8>> {
    match decode_udp_packet_action(packet, peer_addr, registry)? {
        UdpPacketAction::ProbeAck(reply) => Some(reply),
        UdpPacketAction::MoveFrames { session, frames } => {
            if input.ensure_ready(false) {
                let mut sum_dx = 0i32;
                let mut sum_dy = 0i32;
                let mut has_wheel = false;
                for f in &frames {
                    sum_dx = sum_dx.saturating_add(f.dx);
                    sum_dy = sum_dy.saturating_add(f.dy);
                    if f.wheel != 0 {
                        has_wheel = true;
                    }
                }
                if sum_dx != 0 || sum_dy != 0 || has_wheel {
                    let mut events = Vec::with_capacity(4);
                    if sum_dx != 0 {
                        events.push(InputEvent::new(
                            EventType::RELATIVE,
                            RelativeAxisType::REL_X.0,
                            sum_dx,
                        ));
                    }
                    if sum_dy != 0 {
                        events.push(InputEvent::new(
                            EventType::RELATIVE,
                            RelativeAxisType::REL_Y.0,
                            sum_dy,
                        ));
                    }
                    if has_wheel {
                        let mut acc = session.wheel_hi_acc.lock().unwrap_or_else(|e| e.into_inner());
                        for f in &frames {
                            if f.wheel != 0 {
                                if f.is_hi_res {
                                    push_wheel_events(&mut acc, f.wheel, 0, &mut events);
                                } else {
                                    push_wheel_events(&mut acc, 0, f.wheel, &mut events);
                                }
                            }
                        }
                    }
                    if !events.is_empty() {
                        input.emit_uinput_events(&events);
                    }
                }
            }
            None
        }
    }
}

pub async fn run_udp_mouse_server(
    socket: UdpSocket,
    registry: Arc<UdpSessionRegistry>,
    input: Arc<dyn InputController>,
) {
    let mut buf = [0u8; 256];
    let mut smoother = UdpMoveSmoother::default();

    loop {
        let flush_deadline = smoother.next_deadline();
        let pacer_sleep = async {
            if let Some(deadline) = flush_deadline {
                tokio::time::sleep_until(deadline).await;
            } else {
                std::future::pending::<()>().await;
            }
        };

        tokio::select! {
            recv_res = socket.recv_from(&mut buf) => {
                match recv_res {
                    Ok((len, peer_addr)) => {
                        match decode_udp_packet_action(&buf[..len], peer_addr, &registry) {
                            Some(UdpPacketAction::ProbeAck(reply)) => {
                                let _ = socket.send_to(&reply, peer_addr).await;
                            }
                            Some(UdpPacketAction::MoveFrames { session, frames }) => {
                                emit_wheel_frames(&*input, &session, &frames);
                                let mut frame_dx = 0i32;
                                let mut frame_dy = 0i32;
                                for f in &frames {
                                    frame_dx = frame_dx.saturating_add(f.dx);
                                    frame_dy = frame_dy.saturating_add(f.dy);
                                }
                                if frame_dx != 0 || frame_dy != 0 {
                                    let is_burst = frames.len() > 1
                                        || smoother.has_pending()
                                        || frame_dx.abs().max(frame_dy.abs())
                                            > UDP_BURST_SMOOTH_THRESHOLD;
                                    if !is_burst {
                                        // Pass normal single frames directly to kernel uinput with zero pacing jitter.
                                        emit_rel_move(&*input, frame_dx, frame_dy);
                                    } else {
                                        smoother.push(frame_dx, frame_dy);
                                        if let Some((sx, sy)) =
                                            smoother.take_step(tokio::time::Instant::now())
                                        {
                                            emit_rel_move(&*input, sx, sy);
                                        }
                                    }
                                }
                            }
                            None => {}
                        }
                    }
                    Err(err) => {
                        eprintln!("[SilentInput] UDP mouse recv error: {err}");
                        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
                    }
                }
            }
            _ = pacer_sleep, if flush_deadline.is_some() => {
                let now = tokio::time::Instant::now();
                let step_time = flush_deadline.map_or(now, |d| now.max(d));
                if let Some((sx, sy)) = smoother.take_step(step_time) {
                    emit_rel_move(&*input, sx, sy);
                }
            }
        }
    }
}

pub fn encode_base64(bytes: &[u8]) -> String {
    silentinput_core::config::encode_base64_std(bytes)
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

    #[test]
    fn udp_hi_res_wheel_accumulates_to_detents_and_preserves_legacy() {
        let registry = UdpSessionRegistry::new(53318, true);
        let input = Arc::new(TestUdpInput {
            ready: AtomicBool::new(true),
            ..Default::default()
        });
        let creds = registry.create_session("192.168.1.50").unwrap();
        let key_bytes = decode_base64_for_test(&creds.key_b64);
        let client_key = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, &key_bytes).unwrap());
        let peer = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 168, 1, 50)), 44445);

        let make_pkt = |seq: u64, wheel: i16, flags: u16| {
            let mut p = Vec::with_capacity(8);
            p.extend_from_slice(&0i16.to_be_bytes());
            p.extend_from_slice(&0i16.to_be_bytes());
            p.extend_from_slice(&wheel.to_be_bytes());
            p.extend_from_slice(&flags.to_be_bytes());
            seal_udp_packet(
                &client_key,
                PKT_MOUSE_MOVE,
                creds.session_id,
                DIR_CLIENT_TO_SERVER,
                seq,
                &p,
            )
            .unwrap()
        };

        // 1. Hi-res 70 units -> only REL_WHEEL_HI_RES=70 (< 120 threshold)
        assert!(handle_udp_packet(&make_pkt(1, 70, FLAG_HI_RES_WHEEL), peer, &registry, &*input).is_none());
        // 2. Another hi-res 70 units -> sum 140 >= 120 -> emits REL_WHEEL_HI_RES=70 AND REL_WHEEL=1 (remainder 20)
        assert!(handle_udp_packet(&make_pkt(2, 70, FLAG_HI_RES_WHEEL), peer, &registry, &*input).is_none());
        // 3. Legacy detent (flags=0, wheel=-1) -> emits REL_WHEEL=-1 AND REL_WHEEL_HI_RES=-120
        assert!(handle_udp_packet(&make_pkt(3, -1, 0), peer, &registry, &*input).is_none());

        let evs = input.events.lock().unwrap();
        assert_eq!(evs.len(), 3);
        assert_eq!(
            evs[0],
            vec![(EventType::RELATIVE.0, RelativeAxisType::REL_WHEEL_HI_RES.0, 70)]
        );
        assert_eq!(
            evs[1],
            vec![
                (EventType::RELATIVE.0, RelativeAxisType::REL_WHEEL_HI_RES.0, 70),
                (EventType::RELATIVE.0, RelativeAxisType::REL_WHEEL.0, 1),
            ]
        );
        assert_eq!(
            evs[2],
            vec![
                (EventType::RELATIVE.0, RelativeAxisType::REL_WHEEL.0, -1),
                (EventType::RELATIVE.0, RelativeAxisType::REL_WHEEL_HI_RES.0, -120),
            ]
        );
    }

    #[test]
    fn udp_fec_recovers_dropped_packets_and_rejects_late_duplicates() {
        let registry = UdpSessionRegistry::new(53318, true);
        let input = Arc::new(TestUdpInput {
            ready: AtomicBool::new(true),
            ..Default::default()
        });
        let creds = registry.create_session("192.168.1.50").unwrap();
        let key_bytes = decode_base64_for_test(&creds.key_b64);
        let client_key = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, &key_bytes).unwrap());
        let peer = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 168, 1, 50)), 44446);

        let encode_frame = |dx: i16, dy: i16, wheel: i16, flags: u16| -> [u8; 8] {
            let mut b = [0u8; 8];
            b[0..2].copy_from_slice(&dx.to_be_bytes());
            b[2..4].copy_from_slice(&dy.to_be_bytes());
            b[4..6].copy_from_slice(&wheel.to_be_bytes());
            b[6..8].copy_from_slice(&flags.to_be_bytes());
            b
        };

        // 1. seq=1 arrives normally
        let pkt1 = seal_udp_packet(
            &client_key,
            PKT_MOUSE_MOVE,
            creds.session_id,
            DIR_CLIENT_TO_SERVER,
            1,
            &encode_frame(5, -2, 0, 0),
        )
        .unwrap();
        assert!(handle_udp_packet(&pkt1, peer, &registry, &*input).is_none());

        // 2. seq=2 (dx=10, dy=1) and seq=3 (dx=12, dy=2) are dropped; seq=4 arrives with FEC history!
        let pkt2_lost = seal_udp_packet(
            &client_key,
            PKT_MOUSE_MOVE,
            creds.session_id,
            DIR_CLIENT_TO_SERVER,
            2,
            &encode_frame(10, 1, 0, 0),
        )
        .unwrap();
        let mut pt4 = Vec::with_capacity(24);
        pt4.extend_from_slice(&encode_frame(8, -4, 0, FLAG_FEC_PREV1 | FLAG_FEC_PREV2));
        pt4.extend_from_slice(&encode_frame(12, 2, 0, 0)); // prev1 = seq 3
        pt4.extend_from_slice(&encode_frame(10, 1, 0, 0)); // prev2 = seq 2
        let pkt4 = seal_udp_packet(
            &client_key,
            PKT_MOUSE_MOVE,
            creds.session_id,
            DIR_CLIENT_TO_SERVER,
            4,
            &pt4,
        )
        .unwrap();
        assert!(handle_udp_packet(&pkt4, peer, &registry, &*input).is_none());

        // 3. Delayed seq=2 arrives later -> must be ignored because FEC already applied seq=2
        assert!(handle_udp_packet(&pkt2_lost, peer, &registry, &*input).is_none());

        let evs = input.events.lock().unwrap();
        assert_eq!(evs.len(), 2);
        assert_eq!(
            evs[0],
            vec![
                (EventType::RELATIVE.0, RelativeAxisType::REL_X.0, 5),
                (EventType::RELATIVE.0, RelativeAxisType::REL_Y.0, -2),
            ]
        );
        // Combined recovered seq=2 (10,1) + seq=3 (12,2) + seq=4 (8,-4) = (30, -1)
        assert_eq!(
            evs[1],
            vec![
                (EventType::RELATIVE.0, RelativeAxisType::REL_X.0, 30),
                (EventType::RELATIVE.0, RelativeAxisType::REL_Y.0, -1),
            ]
        );
    }

    #[test]
    fn udp_move_smoother_paces_bursts_losslessly_and_cancels_reversals() {
        let mut smoother = UdpMoveSmoother::default();
        let t0 = tokio::time::Instant::now();

        // 1. Small delta <= 4 emits 100% immediately on cold arrival
        smoother.push(3, -2);
        assert_eq!(smoother.take_step(t0), Some((3, -2)));
        assert!(!smoother.has_pending());

        // 2. Arrival < 6ms waits until next tick
        smoother.push(20, -10);
        assert_eq!(smoother.take_step(t0 + std::time::Duration::from_millis(2)), None);
        assert!(smoother.next_deadline().is_some());

        // 3. Proportional drain across 6ms ticks with zero total drift
        let mut sum_x = 0;
        let mut sum_y = 0;
        let mut steps = Vec::new();
        let mut cur = t0 + UDP_PACER_INTERVAL;
        while smoother.has_pending() {
            if let Some((sx, sy)) = smoother.take_step(cur) {
                sum_x += sx;
                sum_y += sy;
                steps.push((sx, sy));
            }
            cur += UDP_PACER_INTERVAL;
        }
        assert!(steps.len() >= 2, "expected multi-tick smoothing, got {steps:?}");
        assert_eq!((sum_x, sum_y), (20, -10));

        // 4. Rapid reversal cancels stale backlog cleanly
        smoother.push(20, 0);
        assert_eq!(smoother.take_step(cur), Some((16, 0))); // 4 remaining
        smoother.push(-14, 0); // net backlog = -10
        assert!(smoother.take_step(cur + std::time::Duration::from_millis(1)).is_none());
        assert_eq!(smoother.take_step(cur + UDP_PACER_INTERVAL), Some((-8, 0)));
        assert_eq!(
            smoother.take_step(cur + UDP_PACER_INTERVAL * 2),
            Some((-2, 0))
        );
        assert!(!smoother.has_pending());
    }

    #[tokio::test]
    async fn run_udp_mouse_server_end_to_end_probe_move_fec_and_wheel() {
        let registry = UdpSessionRegistry::new(53318, true);
        let input = Arc::new(TestUdpInput {
            ready: AtomicBool::new(true),
            ..Default::default()
        });
        let creds = registry.create_session("127.0.0.1").unwrap();
        let key_bytes = decode_base64_for_test(&creds.key_b64);
        let client_key = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, &key_bytes).unwrap());

        let srv_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let srv_addr = srv_sock.local_addr().unwrap();
        let srv_handle = tokio::spawn(run_udp_mouse_server(
            srv_sock,
            Arc::clone(&registry),
            Arc::clone(&input) as Arc<dyn InputController>,
        ));

        let client_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        client_sock.connect(srv_addr).await.unwrap();

        // 1. Probe -> PROBE_ACK
        let probe = seal_udp_packet(
            &client_key,
            PKT_PROBE,
            creds.session_id,
            DIR_CLIENT_TO_SERVER,
            1,
            &0xCAFEBABEu32.to_be_bytes(),
        )
        .unwrap();
        client_sock.send(&probe).await.unwrap();
        let mut ack_buf = [0u8; 128];
        let ack_len = tokio::time::timeout(
            std::time::Duration::from_secs(2),
            client_sock.recv(&mut ack_buf),
        )
        .await
        .unwrap()
        .unwrap();
        assert_eq!(ack_buf[3], PKT_PROBE_ACK);
        let ack_seq = u64::from_be_bytes(ack_buf[12..20].try_into().unwrap());
        let mut ack_ct = ack_buf[HEADER_LEN..ack_len].to_vec();
        let ack_pt = client_key
            .open_in_place(
                make_nonce(DIR_SERVER_TO_CLIENT, ack_seq),
                Aad::from(&ack_buf[..HEADER_LEN]),
                &mut ack_ct,
            )
            .unwrap();
        assert_eq!(ack_pt, &0xCAFEBABEu32.to_be_bytes());

        let encode_frame = |dx: i16, dy: i16, wheel: i16, flags: u16| -> [u8; 8] {
            let mut b = [0u8; 8];
            b[0..2].copy_from_slice(&dx.to_be_bytes());
            b[2..4].copy_from_slice(&dy.to_be_bytes());
            b[4..6].copy_from_slice(&wheel.to_be_bytes());
            b[6..8].copy_from_slice(&flags.to_be_bytes());
            b
        };

        // 2. Normal single move frame emits immediately without pacer splitting
        let pkt2 = seal_udp_packet(
            &client_key,
            PKT_MOUSE_MOVE,
            creds.session_id,
            DIR_CLIENT_TO_SERVER,
            2,
            &encode_frame(14, -9, 0, 0),
        )
        .unwrap();
        client_sock.send(&pkt2).await.unwrap();

        // 3. Drop seq=3, send seq=4 carrying FEC prev1 (seq=3) + hi-res wheel
        let mut pt4 = Vec::with_capacity(16);
        pt4.extend_from_slice(&encode_frame(6, 2, 120, FLAG_FEC_PREV1 | FLAG_HI_RES_WHEEL));
        pt4.extend_from_slice(&encode_frame(4, 3, 0, 0));
        let pkt4 = seal_udp_packet(
            &client_key,
            PKT_MOUSE_MOVE,
            creds.session_id,
            DIR_CLIENT_TO_SERVER,
            4,
            &pt4,
        )
        .unwrap();
        client_sock.send(&pkt4).await.unwrap();

        // 4. Replay pkt2 -> must be ignored
        client_sock.send(&pkt2).await.unwrap();

        // Wait for async server to emit events (with smoother drain completing if burst)
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(1);
        loop {
            let total_dx: i32 = input
                .events
                .lock()
                .unwrap()
                .iter()
                .flatten()
                .filter(|(t, c, _)| *t == EventType::RELATIVE.0 && *c == RelativeAxisType::REL_X.0)
                .map(|(_, _, v)| *v)
                .sum();
            let has_wheel = input.events.lock().unwrap().iter().flatten().any(|(t, c, v)| {
                *t == EventType::RELATIVE.0 && *c == RelativeAxisType::REL_WHEEL.0 && *v == 1
            });
            if total_dx == 24 && has_wheel {
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "timed out waiting for UDP events: {:?}",
                *input.events.lock().unwrap()
            );
            tokio::time::sleep(std::time::Duration::from_millis(5)).await;
        }

        let all_events = input.events.lock().unwrap().clone();
        // First emission is unsplit immediate move (14, -9)
        assert_eq!(
            all_events[0],
            vec![
                (EventType::RELATIVE.0, RelativeAxisType::REL_X.0, 14),
                (EventType::RELATIVE.0, RelativeAxisType::REL_Y.0, -9),
            ]
        );
        let total_dy: i32 = all_events
            .iter()
            .flatten()
            .filter(|(t, c, _)| *t == EventType::RELATIVE.0 && *c == RelativeAxisType::REL_Y.0)
            .map(|(_, _, v)| *v)
            .sum();
        assert_eq!(total_dy, -4); // -9 + 3 + 2

        srv_handle.abort();
    }
}
