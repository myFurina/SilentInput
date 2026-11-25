use std::sync::Arc;
use std::time::Duration;

use evdev::{EventType, InputEvent, Key, RelativeAxisType};
use serde_json::{json, Map, Value};
use silentinput_core::acl::is_localhost_ip;
use silentinput_core::config::{constant_time_token_eq, unix_timestamp_secs, SilentInputConfig};
use silentinput_core::keymap::{lookup_char, normalize_line_endings};

use crate::auth_limiter::{AuthRateLimiter, AUTH_LOCKOUT_SECS};
use crate::input::InputController;
use crate::udp_mouse::UdpSessionRegistry;

pub const DAEMON_VERSION: &str = env!("CARGO_PKG_VERSION");
pub const MAX_PENDING_PASTE_TASKS: usize = 16;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcessOutcome {
    pub authenticated: bool,
    pub should_close: bool,
    pub reply: Option<String>,
    pub udp_session_id: Option<u64>,
}

impl ProcessOutcome {
    fn plain(authenticated: bool, should_close: bool, reply: Option<String>) -> Self {
        Self {
            authenticated,
            should_close,
            reply,
            udp_session_id: None,
        }
    }
}

pub fn safe_round_int(value: Option<&Value>) -> i32 {
    let Some(v) = value else {
        return 0;
    };
    if v.is_null() || v.is_boolean() {
        return 0;
    }
    let num = if let Some(n) = v.as_f64() {
        n
    } else if let Some(s) = v.as_str() {
        match s.trim().parse::<f64>() {
            Ok(parsed) => parsed,
            Err(_) => return 0,
        }
    } else {
        return 0;
    };

    if !num.is_finite() {
        return 0;
    }
    let rounded = num.round();
    if rounded >= i32::MAX as f64 {
        i32::MAX
    } else if rounded <= i32::MIN as f64 {
        i32::MIN
    } else {
        rounded as i32
    }
}

pub fn json_truthy(value: Option<&Value>) -> bool {
    let Some(v) = value else {
        return false;
    };
    match v {
        Value::Null => false,
        Value::Bool(b) => *b,
        Value::Number(n) => n.as_f64().is_some_and(|x| x != 0.0 && !x.is_nan()),
        Value::String(s) => !s.is_empty(),
        Value::Array(a) => !a.is_empty(),
        Value::Object(m) => !m.is_empty(),
    }
}

pub struct SessionContext<'a> {
    pub authenticated: bool,
    pub config: &'a SilentInputConfig,
    pub peer_ip: &'a str,
    pub conn_id: u64,
    pub auth_limiter: &'a AuthRateLimiter,
    pub input: Arc<dyn InputController>,
    pub paste_lock: Arc<tokio::sync::Mutex<()>>,
    pub paste_semaphore: Arc<tokio::sync::Semaphore>,
    pub udp_registry: Option<&'a UdpSessionRegistry>,
}

pub async fn process_message(msg_str: &str, ctx: SessionContext<'_>) -> ProcessOutcome {
    let trimmed = msg_str.trim();
    if trimmed.is_empty() || !trimmed.starts_with('{') {
        return ProcessOutcome::plain(ctx.authenticated, false, None);
    }

    let Ok(Value::Object(map)) = serde_json::from_str::<Value>(trimmed) else {
        return ProcessOutcome::plain(ctx.authenticated, false, None);
    };

    let Some(msg_type) = map.get("type").and_then(|v| v.as_str()) else {
        return ProcessOutcome::plain(ctx.authenticated, false, None);
    };

    if msg_type == "ping" {
        let mut resp = Map::new();
        resp.insert("type".to_string(), json!("pong"));
        resp.insert("time".to_string(), json!(unix_timestamp_secs()));
        if let Some(t_val) = map.get("t") {
            resp.insert("t".to_string(), t_val.clone());
        }
        return ProcessOutcome::plain(ctx.authenticated, false, Some(Value::Object(resp).to_string()));
    }

    if msg_type == "get_status" {
        let reply = json!({
            "type": "status",
            "version": DAEMON_VERSION,
            "authenticated": ctx.authenticated,
            "access_control_mode": ctx.config.access_control_mode,
            "unicode_direct_typing": ctx.input.is_graphical_session(),
        });
        return ProcessOutcome::plain(ctx.authenticated, false, Some(reply.to_string()));
    }

    if msg_type == "auth" {
        if ctx.authenticated {
            let reply = json!({
                "type": "auth_result",
                "status": "ok",
                "message": "Authenticated",
                "unicode_direct_typing": ctx.input.is_graphical_session(),
            });
            return ProcessOutcome::plain(true, false, Some(reply.to_string()));
        }

        if ctx.auth_limiter.is_locked(ctx.peer_ip) {
            let reply = json!({
                "type": "auth_result",
                "status": "fail",
                "message": "Too many failed attempts. Try again later.",
            });
            return ProcessOutcome::plain(false, true, Some(reply.to_string()));
        }

        let token = map.get("token").and_then(|v| v.as_str()).unwrap_or("");
        if constant_time_token_eq(token, &ctx.config.auth_token) {
            ctx.auth_limiter.clear(ctx.peer_ip);
            println!("[SilentInput] Authentication SUCCESS via auth token");
            let mut reply = json!({
                "type": "auth_result",
                "status": "ok",
                "message": "Authenticated",
                "unicode_direct_typing": ctx.input.is_graphical_session(),
            });
            let mut created_udp_id = None;
            if !is_localhost_ip(ctx.peer_ip) {
                if let Some(creds) = ctx.udp_registry.and_then(|r| r.create_session(ctx.peer_ip)) {
                    if let Some(obj) = reply.as_object_mut() {
                        obj.insert("udp_port".to_string(), json!(creds.port));
                        obj.insert("udp_session_id".to_string(), json!(creds.session_id_hex));
                        obj.insert("udp_key".to_string(), json!(creds.key_b64));
                    }
                    created_udp_id = Some(creds.session_id);
                }
            }
            return ProcessOutcome {
                authenticated: true,
                should_close: false,
                reply: Some(reply.to_string()),
                udp_session_id: created_udp_id,
            };
        }

        let locked_now = ctx.auth_limiter.record_failure(ctx.peer_ip);
        let peer_display = if ctx.peer_ip.is_empty() {
            "unknown"
        } else {
            ctx.peer_ip
        };
        if locked_now {
            println!(
                "[SilentInput] Authentication FAILED from {peer_display} (rate-limited for {AUTH_LOCKOUT_SECS}s)"
            );
        } else {
            println!("[SilentInput] Authentication FAILED from {peer_display}");
        }
        let reply = json!({
            "type": "auth_result",
            "status": "fail",
            "message": "Invalid token",
        });
        return ProcessOutcome::plain(false, locked_now, Some(reply.to_string()));
    }

    if !ctx.authenticated {
        println!("[SilentInput] Blocked unauthenticated command '{msg_type}'");
        let reply = json!({
            "type": "auth_required",
            "status": "unauthorized",
            "message": "Authentication required. Please send token.",
        });
        return ProcessOutcome::plain(false, false, Some(reply.to_string()));
    }

    match msg_type {
        "paste" | "text" | "type" => {
            let Some(content) = map.get("content").and_then(|v| v.as_str()) else {
                return ProcessOutcome::plain(true, false, None);
            };
            let content = content.to_string();
            let enter_after = json_truthy(map.get("enter"));
            let mode = map
                .get("mode")
                .and_then(|v| v.as_str())
                .unwrap_or("terminal");
            let paste_mode = map
                .get("paste_mode")
                .and_then(|v| v.as_str())
                .unwrap_or(mode)
                .to_string();

            let Ok(permit) = Arc::clone(&ctx.paste_semaphore).try_acquire_owned() else {
                eprintln!(
                    "[SilentInput] Dropping paste/type command: background input queue is full."
                );
                let reply = json!({
                    "type": "paste_result",
                    "status": "busy",
                    "message": "Input queue full, please slow down.",
                });
                return ProcessOutcome::plain(true, false, Some(reply.to_string()));
            };

            let input = Arc::clone(&ctx.input);
            let paste_lock = Arc::clone(&ctx.paste_lock);

            // Spawn off the read loop so long typing does not starve 5s ping/pong heartbeats.
            tokio::spawn(async move {
                let _permit = permit;
                let _guard = paste_lock.lock().await;
                if paste_mode == "keys" {
                    let normalized = normalize_line_endings(&content);
                    println!(
                        "[SilentInput] Direct typing {} chars via virtual hardware keyboard",
                        normalized.chars().count()
                    );
                    let ends_with_unicode = normalized
                        .chars()
                        .last()
                        .is_some_and(|ch| lookup_char(ch).is_none() && input.is_graphical_session());
                    input.type_text(&normalized).await;
                    if enter_after {
                        tokio::time::sleep(Duration::from_millis(if ends_with_unicode {
                            40
                        } else {
                            15
                        }))
                        .await;
                        input.emit_key_combination("enter").await;
                    }
                } else {
                    println!(
                        "[SilentInput] Injecting {} chars via clipboard (mode: {paste_mode}, enter: {enter_after})",
                        content.chars().count()
                    );
                    if !input.copy_clipboard(&content).await {
                        eprintln!(
                            "[SilentInput] Aborting paste shortcut because clipboard write failed."
                        );
                        return;
                    }
                    tokio::time::sleep(Duration::from_millis(60)).await;
                    if paste_mode == "gui" {
                        input.emit_key_combination("ctrl+v").await;
                    } else {
                        input.emit_key_combination("ctrl+shift+v").await;
                    }
                    if enter_after {
                        tokio::time::sleep(Duration::from_millis(40)).await;
                        input.emit_key_combination("enter").await;
                    }
                }
            });
        }
        "key" => {
            if let Some(key_str) = map.get("key").and_then(|v| v.as_str()) {
                if !key_str.is_empty() {
                    println!("[SilentInput] Key event: {key_str}");
                    ctx.input.emit_key_combination(key_str).await;
                }
            }
        }
        "mouse" if ctx.input.ensure_ready(false) => {
            let action = map.get("action").and_then(|v| v.as_str()).unwrap_or("");
            match action {
                "click_left" => {
                    let btn = Key::BTN_LEFT.code();
                    if ctx
                        .input
                        .emit_uinput_events(&[InputEvent::new(EventType::KEY, btn, 1)])
                    {
                        tokio::time::sleep(Duration::from_millis(20)).await;
                        ctx.input
                            .emit_uinput_events(&[InputEvent::new(EventType::KEY, btn, 0)]);
                    }
                    ctx.input.track_mouse_button(btn, false, ctx.conn_id);
                }
                "click_right" => {
                    let btn = Key::BTN_RIGHT.code();
                    if ctx
                        .input
                        .emit_uinput_events(&[InputEvent::new(EventType::KEY, btn, 1)])
                    {
                        tokio::time::sleep(Duration::from_millis(20)).await;
                        ctx.input
                            .emit_uinput_events(&[InputEvent::new(EventType::KEY, btn, 0)]);
                    }
                    ctx.input.track_mouse_button(btn, false, ctx.conn_id);
                }
                "down_left" => {
                    let btn = Key::BTN_LEFT.code();
                    if ctx
                        .input
                        .emit_uinput_events(&[InputEvent::new(EventType::KEY, btn, 1)])
                    {
                        ctx.input.track_mouse_button(btn, true, ctx.conn_id);
                    }
                }
                "up_left" => {
                    let btn = Key::BTN_LEFT.code();
                    ctx.input
                        .emit_uinput_events(&[InputEvent::new(EventType::KEY, btn, 0)]);
                    ctx.input.track_mouse_button(btn, false, ctx.conn_id);
                }
                "down_right" => {
                    let btn = Key::BTN_RIGHT.code();
                    if ctx
                        .input
                        .emit_uinput_events(&[InputEvent::new(EventType::KEY, btn, 1)])
                    {
                        ctx.input.track_mouse_button(btn, true, ctx.conn_id);
                    }
                }
                "up_right" => {
                    let btn = Key::BTN_RIGHT.code();
                    ctx.input
                        .emit_uinput_events(&[InputEvent::new(EventType::KEY, btn, 0)]);
                    ctx.input.track_mouse_button(btn, false, ctx.conn_id);
                }
                _ => {
                    let mut events = Vec::new();
                    let mut btn_updates: Vec<(u16, bool)> = Vec::new();
                    let vx = safe_round_int(map.get("dx"));
                    let vy = safe_round_int(map.get("dy"));
                    let vw = safe_round_int(map.get("wheel"));

                    if vx != 0 {
                        events.push(InputEvent::new(
                            EventType::RELATIVE,
                            RelativeAxisType::REL_X.0,
                            vx,
                        ));
                    }
                    if vy != 0 {
                        events.push(InputEvent::new(
                            EventType::RELATIVE,
                            RelativeAxisType::REL_Y.0,
                            vy,
                        ));
                    }
                    if vw != 0 {
                        events.push(InputEvent::new(
                            EventType::RELATIVE,
                            RelativeAxisType::REL_WHEEL.0,
                            vw,
                        ));
                    }
                    if let Some(val) = map.get("btn_left") {
                        let pressed = json_truthy(Some(val));
                        let btn = Key::BTN_LEFT.code();
                        events.push(InputEvent::new(
                            EventType::KEY,
                            btn,
                            if pressed { 1 } else { 0 },
                        ));
                        btn_updates.push((btn, pressed));
                    }
                    if let Some(val) = map.get("btn_right") {
                        let pressed = json_truthy(Some(val));
                        let btn = Key::BTN_RIGHT.code();
                        events.push(InputEvent::new(
                            EventType::KEY,
                            btn,
                            if pressed { 1 } else { 0 },
                        ));
                        btn_updates.push((btn, pressed));
                    }
                    if !events.is_empty() {
                        let emitted = ctx.input.emit_uinput_events(&events);
                        for (btn, is_pressed) in btn_updates {
                            if is_pressed {
                                if emitted {
                                    ctx.input.track_mouse_button(btn, true, ctx.conn_id);
                                }
                            } else {
                                ctx.input.track_mouse_button(btn, false, ctx.conn_id);
                            }
                        }
                    }
                }
            }
        }
        _ => {}
    }

    ProcessOutcome::plain(ctx.authenticated, false, None)
}
