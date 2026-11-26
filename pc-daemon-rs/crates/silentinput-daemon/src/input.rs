use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::pin::Pin;
use std::process::Stdio;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use evdev::uinput::{VirtualDevice, VirtualDeviceBuilder};
use evdev::{AttributeSet, EventType, InputEvent, Key, RelativeAxisType};
use silentinput_core::keymap::{
    all_registered_keys, build_key_map, build_wtype_args, build_xdotool_args,
    clipboard_candidates_for_session, detect_session_capabilities, detect_wayland_env,
    detect_x11_env, has_graphical_session, lookup_char, normalize_line_endings,
    resolve_key_combination,
};
use tokio::io::AsyncWriteExt;
use tokio::process::Command;

pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

pub trait InputController: Send + Sync {
    fn ensure_ready(&self, log_failure: bool) -> bool;
    fn has_uinput_device(&self) -> bool;
    fn is_graphical_session(&self) -> bool;
    fn emit_key_combination<'a>(&'a self, combo: &'a str) -> BoxFuture<'a, ()>;
    fn type_text<'a>(&'a self, text: &'a str) -> BoxFuture<'a, bool>;
    fn copy_clipboard<'a>(&'a self, text: &'a str) -> BoxFuture<'a, bool>;
    fn emit_uinput_events(&self, events: &[InputEvent]) -> bool;
    fn track_mouse_button(&self, btn_code: u16, pressed: bool, conn_id: u64);
    fn release_connection_buttons(&self, conn_id: u64);
    fn take_wheel_detents(&self, _conn_id: u64, delta_hi: i32) -> i32 {
        delta_hi / 120
    }
}

/// Emit paired Linux `REL_WHEEL_HI_RES` and legacy `REL_WHEEL` events cleanly without drift.
pub fn push_wheel_events(
    acc: &mut i32,
    raw_hi: i32,
    raw_detent: i32,
    events: &mut Vec<InputEvent>,
) {
    if raw_hi != 0 {
        events.push(InputEvent::new(
            EventType::RELATIVE,
            RelativeAxisType::REL_WHEEL_HI_RES.0,
            raw_hi,
        ));
        *acc = acc.saturating_add(raw_hi);
        let detents = *acc / 120;
        if detents != 0 {
            *acc -= detents * 120;
            events.push(InputEvent::new(
                EventType::RELATIVE,
                RelativeAxisType::REL_WHEEL.0,
                detents,
            ));
        }
    } else if raw_detent != 0 {
        events.push(InputEvent::new(
            EventType::RELATIVE,
            RelativeAxisType::REL_WHEEL.0,
            raw_detent,
        ));
        events.push(InputEvent::new(
            EventType::RELATIVE,
            RelativeAxisType::REL_WHEEL_HI_RES.0,
            raw_detent.saturating_mul(120),
        ));
    }
}

#[derive(Default)]
struct UinputHolder {
    device: Option<VirtualDevice>,
    warned: bool,
}

#[derive(Default)]
struct MouseHoldTracker {
    held: HashMap<u16, HashSet<u64>>,
    wheel_acc: HashMap<u64, i32>,
}

impl MouseHoldTracker {
    fn track(&mut self, btn_code: u16, pressed: bool, conn_id: u64) {
        if pressed {
            self.held.entry(btn_code).or_default().insert(conn_id);
        } else if let Some(holders) = self.held.get_mut(&btn_code) {
            holders.remove(&conn_id);
            if holders.is_empty() {
                self.held.remove(&btn_code);
            }
        }
    }

    fn accumulate_wheel(&mut self, conn_id: u64, delta_hi: i32) -> i32 {
        let entry = self.wheel_acc.entry(conn_id).or_insert(0);
        *entry = entry.saturating_add(delta_hi);
        let detents = *entry / 120;
        if detents != 0 {
            *entry -= detents * 120;
        }
        detents
    }

    fn release_connection(&mut self, conn_id: u64) -> Vec<u16> {
        self.wheel_acc.remove(&conn_id);
        let mut to_release = Vec::new();
        let keys: Vec<u16> = self.held.keys().copied().collect();
        for code in keys {
            if let Some(holders) = self.held.get_mut(&code) {
                if holders.remove(&conn_id) && holders.is_empty() {
                    self.held.remove(&code);
                    to_release.push(code);
                }
            }
        }
        to_release
    }
}

pub struct SystemInputController {
    uinput: Mutex<UinputHolder>,
    mouse_tracker: Mutex<MouseHoldTracker>,
    paste_lock: Arc<tokio::sync::Mutex<()>>,
}

impl SystemInputController {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            uinput: Mutex::new(UinputHolder::default()),
            mouse_tracker: Mutex::new(MouseHoldTracker::default()),
            paste_lock: Arc::new(tokio::sync::Mutex::new(())),
        })
    }

    pub fn paste_lock(&self) -> Arc<tokio::sync::Mutex<()>> {
        Arc::clone(&self.paste_lock)
    }

    fn build_virtual_device() -> Result<VirtualDevice, String> {
        let mut keys = AttributeSet::<Key>::new();
        let all_keys = all_registered_keys();
        let key_count = all_keys.len();
        for key in all_keys {
            keys.insert(key);
        }

        let mut rels = AttributeSet::<RelativeAxisType>::new();
        rels.insert(RelativeAxisType::REL_X);
        rels.insert(RelativeAxisType::REL_Y);
        rels.insert(RelativeAxisType::REL_WHEEL);
        rels.insert(RelativeAxisType::REL_WHEEL_HI_RES);

        let dev = VirtualDeviceBuilder::new()
            .map_err(|e| format!("Failed to open /dev/uinput: {e}"))?
            .name("SilentInput-Virtual-Device")
            .with_keys(&keys)
            .map_err(|e| format!("with_keys failed: {e}"))?
            .with_relative_axes(&rels)
            .map_err(|e| format!("with_relative_axes failed: {e}"))?
            .build()
            .map_err(|e| format!("VirtualDeviceBuilder build failed: {e}"))?;

        println!(
            "[SilentInput] Kernel uinput virtual hardware device created successfully ({key_count} keys supported)."
        );
        Ok(dev)
    }

    fn emit_char_sync(&self, key: Key, requires_shift: bool) -> Result<(), ()> {
        let mut press = Vec::with_capacity(2);
        if requires_shift {
            press.push(InputEvent::new(EventType::KEY, Key::KEY_LEFTSHIFT.code(), 1));
        }
        press.push(InputEvent::new(EventType::KEY, key.code(), 1));
        if !self.emit_uinput_events(&press) {
            return Err(());
        }
        Ok(())
    }

    fn release_char_sync(&self, key: Key, requires_shift: bool) -> bool {
        let mut release = Vec::with_capacity(2);
        release.push(InputEvent::new(EventType::KEY, key.code(), 0));
        if requires_shift {
            release.push(InputEvent::new(EventType::KEY, Key::KEY_LEFTSHIFT.code(), 0));
        }
        self.emit_uinput_events(&release)
    }
}

impl InputController for SystemInputController {
    fn ensure_ready(&self, log_failure: bool) -> bool {
        let mut guard = self.uinput.lock().unwrap_or_else(|e| e.into_inner());
        if guard.device.is_some() {
            return true;
        }
        match Self::build_virtual_device() {
            Ok(dev) => {
                guard.device = Some(dev);
                guard.warned = false;
                true
            }
            Err(err) => {
                if log_failure && !guard.warned {
                    guard.warned = true;
                    eprintln!(
                        "[SilentInput] Warning: could not initialize kernel uinput ({err}). Will retry automatically."
                    );
                }
                false
            }
        }
    }

    fn has_uinput_device(&self) -> bool {
        self.uinput
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .device
            .is_some()
    }

    fn is_graphical_session(&self) -> bool {
        has_graphical_session()
    }

    fn emit_uinput_events(&self, events: &[InputEvent]) -> bool {
        for attempt in 0..2 {
            let mut guard = self.uinput.lock().unwrap_or_else(|e| e.into_inner());
            if guard.device.is_none() {
                match Self::build_virtual_device() {
                    Ok(dev) => {
                        guard.device = Some(dev);
                        guard.warned = false;
                    }
                    Err(_) => return false,
                }
            }
            if let Some(dev) = guard.device.as_mut() {
                match dev.emit(events) {
                    Ok(()) => return true,
                    Err(err) => {
                        eprintln!(
                            "[SilentInput] Warning: uinput write failed ({err}), resetting virtual device."
                        );
                        guard.device = None;
                        if attempt == 0 {
                            continue;
                        }
                        return false;
                    }
                }
            }
        }
        false
    }

    fn emit_key_combination<'a>(&'a self, combo: &'a str) -> BoxFuture<'a, ()> {
        Box::pin(async move {
            if self.ensure_ready(false) {
                let Some(codes) = resolve_key_combination(combo) else {
                    eprintln!("[SilentInput] Warning: ignoring unknown key combination '{combo}'.");
                    return;
                };
                let press: Vec<InputEvent> = codes
                    .iter()
                    .map(|k| InputEvent::new(EventType::KEY, k.code(), 1))
                    .collect();
                if self.emit_uinput_events(&press) {
                    tokio::time::sleep(Duration::from_millis(15)).await;
                    let release: Vec<InputEvent> = codes
                        .iter()
                        .rev()
                        .map(|k| InputEvent::new(EventType::KEY, k.code(), 0))
                        .collect();
                    self.emit_uinput_events(&release);
                    return;
                }
            }

            let (has_wayland, has_x11) = detect_session_capabilities();
            let mut env_vars = Vec::new();
            if has_wayland {
                env_vars.extend(detect_wayland_env());
            }
            if has_x11 {
                env_vars.extend(detect_x11_env());
            }

            let mut candidates: Vec<(String, Vec<String>)> = Vec::new();
            if has_wayland || !has_x11 {
                let Some(wtype_args) = build_wtype_args(combo) else {
                    eprintln!("[SilentInput] Warning: ignoring invalid fallback key '{combo}'.");
                    return;
                };
                candidates.push(("wtype".to_string(), wtype_args));
            }
            if has_x11 {
                if let Some(xdotool_args) = build_xdotool_args(combo) {
                    candidates.push(("xdotool".to_string(), xdotool_args));
                }
            }

            if let Some(codes) = resolve_key_combination(combo) {
                let mut ydo_args = vec!["key".to_string()];
                for k in &codes {
                    ydo_args.push(format!("{}:1", k.code()));
                }
                for k in codes.iter().rev() {
                    ydo_args.push(format!("{}:0", k.code()));
                }
                candidates.push(("ydotool".to_string(), ydo_args));
            }

            let _ = build_key_map();
            for (bin, args) in candidates {
                let mut cmd = Command::new(&bin);
                cmd.args(&args)
                    .stdin(Stdio::null())
                    .stdout(Stdio::null())
                    .stderr(Stdio::null())
                    .kill_on_drop(true);
                for (k, v) in &env_vars {
                    cmd.env(k, v);
                }
                if let Ok(mut child) = cmd.spawn() {
                    if let Ok(Ok(status)) =
                        tokio::time::timeout(Duration::from_secs(2), child.wait()).await
                    {
                        if status.success() {
                            return;
                        }
                    } else {
                        let _ = child.kill().await;
                    }
                }
            }
            eprintln!(
                "[SilentInput] Warning: could not emit key '{combo}' (uinput unavailable and fallback tools failed)."
            );
        })
    }

    fn type_text<'a>(&'a self, text: &'a str) -> BoxFuture<'a, bool> {
        Box::pin(async move {
            if !self.ensure_ready(false) {
                return false;
            }
            let normalized = normalize_line_endings(text);
            let mut graphical: Option<bool> = None;
            let mut skipped_unicode: usize = 0;

            for ch in normalized.chars() {
                if let Some((key, requires_shift)) = lookup_char(ch) {
                    if self.emit_char_sync(key, requires_shift).is_err() {
                        return false;
                    }
                    tokio::time::sleep(Duration::from_millis(2)).await;
                    if !self.release_char_sync(key, requires_shift) {
                        return false;
                    }
                    tokio::time::sleep(Duration::from_millis(2)).await;
                    continue;
                }

                let is_gfx = *graphical.get_or_insert_with(has_graphical_session);
                if !is_gfx {
                    skipped_unicode += 1;
                    continue;
                }

                self.emit_key_combination("ctrl+shift+u").await;
                tokio::time::sleep(Duration::from_millis(25)).await;
                let hex = format!("{:x}", ch as u32);
                for hex_ch in hex.chars() {
                    if let Some((key, shift)) = lookup_char(hex_ch) {
                        if self.emit_char_sync(key, shift).is_err() {
                            return false;
                        }
                        tokio::time::sleep(Duration::from_millis(2)).await;
                        if !self.release_char_sync(key, shift) {
                            return false;
                        }
                        tokio::time::sleep(Duration::from_millis(2)).await;
                    }
                }
                self.emit_key_combination("enter").await;
                tokio::time::sleep(Duration::from_millis(20)).await;
            }

            if skipped_unicode > 0 {
                eprintln!(
                    "[SilentInput] Warning: skipped {skipped_unicode} non-ASCII character(s) in headless mode."
                );
            }
            true
        })
    }

    fn copy_clipboard<'a>(&'a self, text: &'a str) -> BoxFuture<'a, bool> {
        Box::pin(async move {
            let (has_wayland, has_x11) = detect_session_capabilities();
            let mut env_vars = Vec::new();
            if has_wayland {
                env_vars.extend(detect_wayland_env());
            }
            if has_x11 {
                env_vars.extend(detect_x11_env());
            }

            let candidates = clipboard_candidates_for_session(has_wayland, has_x11);
            for (bin, args) in &candidates {
                if write_clipboard_with_tool(bin, args, &env_vars, text).await {
                    println!(
                        "[SilentInput] Copied {} chars to clipboard via {bin}.",
                        text.chars().count()
                    );
                    return true;
                }
            }
            eprintln!(
                "[SilentInput] Error writing to clipboard: all clipboard tools failed (tried: {}).",
                candidates
                    .iter()
                    .map(|(bin, _)| *bin)
                    .collect::<Vec<_>>()
                    .join(", ")
            );
            false
        })
    }

    fn track_mouse_button(&self, btn_code: u16, pressed: bool, conn_id: u64) {
        self.mouse_tracker
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .track(btn_code, pressed, conn_id);
    }

    fn take_wheel_detents(&self, conn_id: u64, delta_hi: i32) -> i32 {
        self.mouse_tracker
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .accumulate_wheel(conn_id, delta_hi)
    }

    fn release_connection_buttons(&self, conn_id: u64) {
        let to_release = self
            .mouse_tracker
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .release_connection(conn_id);
        if to_release.is_empty() {
            return;
        }
        let events: Vec<InputEvent> = to_release
            .into_iter()
            .map(|code| InputEvent::new(EventType::KEY, code, 0))
            .collect();
        self.emit_uinput_events(&events);
    }
}

/// RAII guard ensuring any mouse button held by a client connection is released on drop.
pub struct MouseHoldGuard {
    input: Arc<dyn InputController>,
    conn_id: u64,
}

impl MouseHoldGuard {
    pub fn new(input: Arc<dyn InputController>, conn_id: u64) -> Self {
        Self { input, conn_id }
    }
}

impl Drop for MouseHoldGuard {
    fn drop(&mut self) {
        self.input.release_connection_buttons(self.conn_id);
    }
}

async fn write_clipboard_with_tool(
    bin: &str,
    args: &[&str],
    env_vars: &[(String, String)],
    text: &str,
) -> bool {
    let mut cmd = Command::new(bin);
    cmd.args(args)
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true);
    for (k, v) in env_vars {
        cmd.env(k, v);
    }

    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(err) => {
            if err.kind() != std::io::ErrorKind::NotFound {
                eprintln!("[SilentInput] Error spawning clipboard tool '{bin}': {err}");
            }
            return false;
        }
    };

    if let Some(mut stdin) = child.stdin.take() {
        if let Err(err) = stdin.write_all(text.as_bytes()).await {
            eprintln!("[SilentInput] Error writing to '{bin}' stdin: {err}");
            let _ = child.kill().await;
            return false;
        }
        let _ = stdin.shutdown().await;
        drop(stdin);
    }

    match tokio::time::timeout(Duration::from_secs(2), child.wait()).await {
        Ok(Ok(status)) if status.success() => true,
        Ok(Ok(status)) => {
            eprintln!("[SilentInput] Clipboard tool '{bin}' exited with {status}");
            false
        }
        Ok(Err(err)) => {
            eprintln!("[SilentInput] Error waiting for '{bin}': {err}");
            false
        }
        Err(_) => {
            let _ = child.kill().await;
            let _ = child.wait().await;
            eprintln!("[SilentInput] Clipboard tool '{bin}' timed out");
            false
        }
    }
}

pub async fn maintain_uinput_device(input: Arc<dyn InputController>) {
    let mut attempts: u32 = 0;
    loop {
        if !input.has_uinput_device() {
            input.ensure_ready(attempts == 0);
            attempts = attempts.saturating_add(1);
        } else {
            attempts = 0;
        }
        let delay_secs = if !input.has_uinput_device() && attempts < 30 {
            2
        } else {
            15
        };
        tokio::time::sleep(Duration::from_secs(delay_secs)).await;
    }
}
