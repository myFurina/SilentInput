use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::pin::Pin;
use std::process::Stdio;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use evdev::uinput::{VirtualDevice, VirtualDeviceBuilder};
use evdev::{
    AbsInfo, AbsoluteAxisType, AttributeSet, BusType, EventType, InputEvent, InputId, Key,
    PropType, RelativeAxisType, UinputAbsSetup,
};
use silentinput_core::keymap::{
    all_registered_keys, build_wtype_args, build_xdotool_args,
    clipboard_candidates_for_session, clipboard_read_candidates_for_session,
    detect_session_capabilities, detect_wayland_env, detect_x11_env, has_graphical_session,
    lookup_char, normalize_line_endings, resolve_key_combination,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::process::Command;

pub const MAX_BACKUP_CLIPBOARD_BYTES: usize = 256 * 1024;
const CLIPBOARD_READ_TIMEOUT_MS: u64 = 500;

pub const TOUCHPAD_MAX_X: i32 = 16000;
pub const TOUCHPAD_MAX_Y: i32 = 16000;
pub const TOUCHPAD_RES: i32 = 40;
pub const TOUCHPAD_MAX_SLOTS: usize = 5;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TouchContact {
    pub pointer_id: u16,
    pub x: u16,
    pub y: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct ActiveSlot {
    pointer_id: u16,
    tracking_id: i32,
    x: i32,
    y: i32,
}

pub struct TouchpadMtState {
    owner_conn_id: Option<u64>,
    slots: [Option<ActiveSlot>; TOUCHPAD_MAX_SLOTS],
    next_tracking_id: i32,
    prev_count: usize,
    last_slot: Option<i32>,
    last_abs_x: Option<i32>,
    last_abs_y: Option<i32>,
}

impl Default for TouchpadMtState {
    fn default() -> Self {
        Self {
            owner_conn_id: None,
            slots: [None; TOUCHPAD_MAX_SLOTS],
            next_tracking_id: 1,
            prev_count: 0,
            last_slot: None,
            last_abs_x: None,
            last_abs_y: None,
        }
    }
}

impl TouchpadMtState {
    pub fn reset(&mut self) {
        *self = Self::default();
    }

    fn alloc_tracking_id(&mut self) -> i32 {
        let id = self.next_tracking_id;
        self.next_tracking_id = if self.next_tracking_id >= 65535 {
            1
        } else {
            self.next_tracking_id + 1
        };
        id
    }

    fn tool_key_for_count(count: usize) -> Option<Key> {
        match count {
            1 => Some(Key::BTN_TOOL_FINGER),
            2 => Some(Key::BTN_TOOL_DOUBLETAP),
            3 => Some(Key::BTN_TOOL_TRIPLETAP),
            4 => Some(Key::BTN_TOOL_QUADTAP),
            5 => Some(Key::BTN_TOOL_QUINTTAP),
            _ => None,
        }
    }

    fn push_slot_select(
        last_slot: &mut Option<i32>,
        slot: usize,
        events: &mut Vec<InputEvent>,
    ) {
        let slot_i32 = slot as i32;
        if *last_slot != Some(slot_i32) {
            events.push(InputEvent::new(
                EventType::ABSOLUTE,
                AbsoluteAxisType::ABS_MT_SLOT.0,
                slot_i32,
            ));
            *last_slot = Some(slot_i32);
        }
    }

    /// Diff an active multi-touch snapshot for `conn_id` into Linux MT Protocol B evdev events.
    pub fn apply_frame(&mut self, conn_id: u64, contacts: &[TouchContact]) -> Vec<InputEvent> {
        if contacts.is_empty() && (self.owner_conn_id != Some(conn_id) || self.prev_count == 0) {
            return Vec::new();
        }

        let mut sanitized: Vec<(u16, i32, i32)> = Vec::with_capacity(TOUCHPAD_MAX_SLOTS);
        for c in contacts {
            let cx = (c.x as i32).clamp(0, TOUCHPAD_MAX_X);
            let cy = (c.y as i32).clamp(0, TOUCHPAD_MAX_Y);
            if let Some(existing) = sanitized.iter_mut().find(|(pid, _, _)| *pid == c.pointer_id) {
                existing.1 = cx;
                existing.2 = cy;
            } else if sanitized.len() < TOUCHPAD_MAX_SLOTS {
                sanitized.push((c.pointer_id, cx, cy));
            }
        }

        let owner_changed = self.owner_conn_id.is_some() && self.owner_conn_id != Some(conn_id);
        let mut events = Vec::with_capacity(24);

        // Step A: lift contacts missing from this frame (or all slots on connection handoff)
        for slot_idx in 0..TOUCHPAD_MAX_SLOTS {
            if let Some(active) = self.slots[slot_idx] {
                let keep = !owner_changed
                    && sanitized
                        .iter()
                        .any(|(pid, _, _)| *pid == active.pointer_id);
                if !keep {
                    Self::push_slot_select(&mut self.last_slot, slot_idx, &mut events);
                    events.push(InputEvent::new(
                        EventType::ABSOLUTE,
                        AbsoluteAxisType::ABS_MT_TRACKING_ID.0,
                        -1,
                    ));
                    self.slots[slot_idx] = None;
                }
            }
        }

        // Step B: update moved contacts and allocate slots for newly touching contacts
        for &(pid, cx, cy) in &sanitized {
            if let Some((slot_idx, slot)) = self
                .slots
                .iter_mut()
                .enumerate()
                .find_map(|(i, opt)| opt.as_mut().filter(|s| s.pointer_id == pid).map(|s| (i, s)))
            {
                if slot.x != cx || slot.y != cy {
                    Self::push_slot_select(&mut self.last_slot, slot_idx, &mut events);
                    if slot.x != cx {
                        events.push(InputEvent::new(
                            EventType::ABSOLUTE,
                            AbsoluteAxisType::ABS_MT_POSITION_X.0,
                            cx,
                        ));
                        slot.x = cx;
                    }
                    if slot.y != cy {
                        events.push(InputEvent::new(
                            EventType::ABSOLUTE,
                            AbsoluteAxisType::ABS_MT_POSITION_Y.0,
                            cy,
                        ));
                        slot.y = cy;
                    }
                }
            } else if let Some(free_idx) = self.slots.iter().position(|opt| opt.is_none()) {
                let tid = self.alloc_tracking_id();
                Self::push_slot_select(&mut self.last_slot, free_idx, &mut events);
                events.push(InputEvent::new(
                    EventType::ABSOLUTE,
                    AbsoluteAxisType::ABS_MT_TRACKING_ID.0,
                    tid,
                ));
                events.push(InputEvent::new(
                    EventType::ABSOLUTE,
                    AbsoluteAxisType::ABS_MT_POSITION_X.0,
                    cx,
                ));
                events.push(InputEvent::new(
                    EventType::ABSOLUTE,
                    AbsoluteAxisType::ABS_MT_POSITION_Y.0,
                    cy,
                ));
                self.slots[free_idx] = Some(ActiveSlot {
                    pointer_id: pid,
                    tracking_id: tid,
                    x: cx,
                    y: cy,
                });
            }
        }

        let new_count = self.slots.iter().filter(|opt| opt.is_some()).count();

        // Step C: BTN_TOUCH & BTN_TOOL_* transitions
        if self.prev_count == 0 && new_count > 0 {
            events.push(InputEvent::new(EventType::KEY, Key::BTN_TOUCH.code(), 1));
        }
        if new_count != self.prev_count {
            if let Some(old_tool) = Self::tool_key_for_count(self.prev_count) {
                events.push(InputEvent::new(EventType::KEY, old_tool.code(), 0));
            }
            if let Some(new_tool) = Self::tool_key_for_count(new_count) {
                events.push(InputEvent::new(EventType::KEY, new_tool.code(), 1));
            }
        }
        if self.prev_count > 0 && new_count == 0 {
            events.push(InputEvent::new(EventType::KEY, Key::BTN_TOUCH.code(), 0));
        }

        // Step D: single-touch ABS_X / ABS_Y tracking lowest active slot
        if let Some(primary) = self.slots.iter().flatten().next() {
            if self.last_abs_x != Some(primary.x) {
                events.push(InputEvent::new(
                    EventType::ABSOLUTE,
                    AbsoluteAxisType::ABS_X.0,
                    primary.x,
                ));
                self.last_abs_x = Some(primary.x);
            }
            if self.last_abs_y != Some(primary.y) {
                events.push(InputEvent::new(
                    EventType::ABSOLUTE,
                    AbsoluteAxisType::ABS_Y.0,
                    primary.y,
                ));
                self.last_abs_y = Some(primary.y);
            }
            self.owner_conn_id = Some(conn_id);
        } else {
            self.owner_conn_id = None;
            self.last_abs_x = None;
            self.last_abs_y = None;
        }

        self.prev_count = new_count;
        events
    }

    pub fn release_connection(&mut self, conn_id: u64) -> Vec<InputEvent> {
        if self.owner_conn_id == Some(conn_id) && self.prev_count > 0 {
            self.apply_frame(conn_id, &[])
        } else {
            Vec::new()
        }
    }
}

pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

pub trait InputController: Send + Sync {
    fn ensure_ready(&self, log_failure: bool) -> bool;
    fn has_uinput_device(&self) -> bool;
    fn has_touchpad_device(&self) -> bool {
        false
    }
    fn ensure_touchpad_ready(&self, _log_failure: bool) -> bool {
        false
    }
    fn apply_touchpad_frame(&self, _conn_id: u64, _contacts: &[TouchContact]) -> bool {
        false
    }
    fn release_connection_touchpad(&self, _conn_id: u64) {}
    fn is_graphical_session(&self) -> bool;
    fn emit_key_combination<'a>(&'a self, combo: &'a str) -> BoxFuture<'a, ()>;
    fn type_text<'a>(&'a self, text: &'a str) -> BoxFuture<'a, bool>;
    fn copy_clipboard<'a>(&'a self, text: &'a str) -> BoxFuture<'a, bool>;
    fn read_clipboard<'a>(&'a self) -> BoxFuture<'a, Option<String>> {
        Box::pin(async { None })
    }
    fn emit_uinput_events(&self, events: &[InputEvent]) -> bool;
    fn track_mouse_button(&self, btn_code: u16, pressed: bool, conn_id: u64);
    fn release_connection_buttons(&self, conn_id: u64);
    fn take_wheel_detents(&self, _conn_id: u64, delta_hi: i32) -> i32 {
        delta_hi / 120
    }
    fn take_hwheel_detents(&self, _conn_id: u64, delta_hi: i32) -> i32 {
        delta_hi / 120
    }
}

pub const WHEEL_ACC_RESET_IDLE: Duration = Duration::from_millis(250);

fn push_axis_wheel_events(
    acc: &mut i32,
    raw_hi: i32,
    raw_detent: i32,
    hi_axis: u16,
    detent_axis: u16,
    events: &mut Vec<InputEvent>,
) {
    if raw_hi != 0 {
        events.push(InputEvent::new(EventType::RELATIVE, hi_axis, raw_hi));
        *acc = acc.saturating_add(raw_hi);
        let detents = *acc / 120;
        if detents != 0 {
            *acc -= detents * 120;
            events.push(InputEvent::new(EventType::RELATIVE, detent_axis, detents));
        }
    } else if raw_detent != 0 {
        events.push(InputEvent::new(EventType::RELATIVE, detent_axis, raw_detent));
        events.push(InputEvent::new(
            EventType::RELATIVE,
            hi_axis,
            raw_detent.saturating_mul(120),
        ));
    }
}

#[derive(Default)]
pub struct TimedWheelAcc {
    acc: i32,
    last_at: Option<std::time::Instant>,
}

impl TimedWheelAcc {
    fn reset_if_stale(&mut self, now: std::time::Instant) {
        if self
            .last_at
            .is_some_and(|prev| now.saturating_duration_since(prev) > WHEEL_ACC_RESET_IDLE)
        {
            self.acc = 0;
        }
        self.last_at = Some(now);
    }

    pub fn add_and_take_detents(&mut self, delta_hi: i32, now: std::time::Instant) -> i32 {
        self.reset_if_stale(now);
        self.acc = self.acc.saturating_add(delta_hi);
        let detents = self.acc / 120;
        if detents != 0 {
            self.acc -= detents * 120;
        }
        detents
    }

    pub fn push_wheel_events_at(
        &mut self,
        raw_hi: i32,
        raw_detent: i32,
        is_hwheel: bool,
        now: std::time::Instant,
        events: &mut Vec<InputEvent>,
    ) {
        let (hi_axis, detent_axis) = if is_hwheel {
            (
                RelativeAxisType::REL_HWHEEL_HI_RES.0,
                RelativeAxisType::REL_HWHEEL.0,
            )
        } else {
            (
                RelativeAxisType::REL_WHEEL_HI_RES.0,
                RelativeAxisType::REL_WHEEL.0,
            )
        };
        if raw_hi != 0 {
            self.reset_if_stale(now);
        }
        push_axis_wheel_events(&mut self.acc, raw_hi, raw_detent, hi_axis, detent_axis, events);
    }
}

#[derive(Default)]
struct UinputHolder {
    device: Option<VirtualDevice>,
    warned: bool,
}

#[derive(Default)]
struct TouchpadHolder {
    device: Option<VirtualDevice>,
    warned: bool,
    state: TouchpadMtState,
}

#[derive(Default)]
struct MouseHoldTracker {
    held: HashMap<u16, HashSet<u64>>,
    wheel_acc: HashMap<u64, TimedWheelAcc>,
    hwheel_acc: HashMap<u64, TimedWheelAcc>,
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
        self.wheel_acc
            .entry(conn_id)
            .or_default()
            .add_and_take_detents(delta_hi, std::time::Instant::now())
    }

    fn accumulate_hwheel(&mut self, conn_id: u64, delta_hi: i32) -> i32 {
        self.hwheel_acc
            .entry(conn_id)
            .or_default()
            .add_and_take_detents(delta_hi, std::time::Instant::now())
    }

    fn release_connection(&mut self, conn_id: u64) -> Vec<u16> {
        self.wheel_acc.remove(&conn_id);
        self.hwheel_acc.remove(&conn_id);
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
    touchpad: Mutex<TouchpadHolder>,
    mouse_tracker: Mutex<MouseHoldTracker>,
    paste_lock: Arc<tokio::sync::Mutex<()>>,
}

impl SystemInputController {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            uinput: Mutex::new(UinputHolder::default()),
            touchpad: Mutex::new(TouchpadHolder::default()),
            mouse_tracker: Mutex::new(MouseHoldTracker::default()),
            paste_lock: Arc::new(tokio::sync::Mutex::new(())),
        })
    }

    pub fn paste_lock(&self) -> Arc<tokio::sync::Mutex<()>> {
        Arc::clone(&self.paste_lock)
    }

    fn build_virtual_touchpad() -> Result<VirtualDevice, String> {
        let mut props = AttributeSet::<PropType>::new();
        props.insert(PropType::POINTER);

        let mut keys = AttributeSet::<Key>::new();
        keys.insert(Key::BTN_LEFT);
        keys.insert(Key::BTN_RIGHT);
        keys.insert(Key::BTN_TOUCH);
        keys.insert(Key::BTN_TOOL_FINGER);
        keys.insert(Key::BTN_TOOL_DOUBLETAP);
        keys.insert(Key::BTN_TOOL_TRIPLETAP);
        keys.insert(Key::BTN_TOOL_QUADTAP);
        keys.insert(Key::BTN_TOOL_QUINTTAP);

        let dev = VirtualDeviceBuilder::new()
            .map_err(|e| format!("Failed to open /dev/uinput: {e}"))?
            .name("SilentInput-Virtual-Touchpad")
            .input_id(InputId::new(BusType::BUS_VIRTUAL, 0x1D6B, 0x5170, 1))
            .with_properties(&props)
            .map_err(|e| format!("with_properties failed: {e}"))?
            .with_keys(&keys)
            .map_err(|e| format!("with_keys failed: {e}"))?
            .with_absolute_axis(&UinputAbsSetup::new(
                AbsoluteAxisType::ABS_X,
                AbsInfo::new(0, 0, TOUCHPAD_MAX_X, 0, 0, TOUCHPAD_RES),
            ))
            .map_err(|e| format!("with_absolute_axis ABS_X failed: {e}"))?
            .with_absolute_axis(&UinputAbsSetup::new(
                AbsoluteAxisType::ABS_Y,
                AbsInfo::new(0, 0, TOUCHPAD_MAX_Y, 0, 0, TOUCHPAD_RES),
            ))
            .map_err(|e| format!("with_absolute_axis ABS_Y failed: {e}"))?
            .with_absolute_axis(&UinputAbsSetup::new(
                AbsoluteAxisType::ABS_MT_SLOT,
                AbsInfo::new(0, 0, (TOUCHPAD_MAX_SLOTS - 1) as i32, 0, 0, 0),
            ))
            .map_err(|e| format!("with_absolute_axis ABS_MT_SLOT failed: {e}"))?
            .with_absolute_axis(&UinputAbsSetup::new(
                AbsoluteAxisType::ABS_MT_TRACKING_ID,
                AbsInfo::new(0, 0, 65535, 0, 0, 0),
            ))
            .map_err(|e| format!("with_absolute_axis ABS_MT_TRACKING_ID failed: {e}"))?
            .with_absolute_axis(&UinputAbsSetup::new(
                AbsoluteAxisType::ABS_MT_POSITION_X,
                AbsInfo::new(0, 0, TOUCHPAD_MAX_X, 0, 0, TOUCHPAD_RES),
            ))
            .map_err(|e| format!("with_absolute_axis ABS_MT_POSITION_X failed: {e}"))?
            .with_absolute_axis(&UinputAbsSetup::new(
                AbsoluteAxisType::ABS_MT_POSITION_Y,
                AbsInfo::new(0, 0, TOUCHPAD_MAX_Y, 0, 0, TOUCHPAD_RES),
            ))
            .map_err(|e| format!("with_absolute_axis ABS_MT_POSITION_Y failed: {e}"))?
            .build()
            .map_err(|e| format!("VirtualDeviceBuilder build failed: {e}"))?;

        println!("[SilentInput] Kernel uinput native multi-touch touchpad created successfully.");
        Ok(dev)
    }

    fn emit_touchpad_events(&self, conn_id: u64, contacts: &[TouchContact], release_only: bool) -> bool {
        for attempt in 0..2 {
            let mut guard = self.touchpad.lock().unwrap_or_else(|e| e.into_inner());
            if guard.device.is_none() {
                match Self::build_virtual_touchpad() {
                    Ok(dev) => {
                        guard.device = Some(dev);
                        guard.warned = false;
                        guard.state.reset();
                    }
                    Err(_) => return false,
                }
            }
            let events = if release_only {
                guard.state.release_connection(conn_id)
            } else {
                guard.state.apply_frame(conn_id, contacts)
            };
            if events.is_empty() {
                return true;
            }
            if let Some(dev) = guard.device.as_mut() {
                match dev.emit(&events) {
                    Ok(()) => return true,
                    Err(err) => {
                        eprintln!(
                            "[SilentInput] Warning: touchpad uinput write failed ({err}), resetting virtual touchpad."
                        );
                        guard.device = None;
                        guard.state.reset();
                        if release_only || attempt == 1 {
                            return false;
                        }
                    }
                }
            }
        }
        false
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
        rels.insert(RelativeAxisType::REL_HWHEEL);
        rels.insert(RelativeAxisType::REL_HWHEEL_HI_RES);

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
        let primary_ready = if guard.device.is_some() {
            true
        } else {
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
        };
        drop(guard);
        let _ = self.ensure_touchpad_ready(log_failure);
        primary_ready
    }

    fn has_uinput_device(&self) -> bool {
        self.uinput
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .device
            .is_some()
    }

    fn has_touchpad_device(&self) -> bool {
        self.touchpad
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .device
            .is_some()
    }

    fn ensure_touchpad_ready(&self, log_failure: bool) -> bool {
        let mut guard = self.touchpad.lock().unwrap_or_else(|e| e.into_inner());
        if guard.device.is_some() {
            return true;
        }
        match Self::build_virtual_touchpad() {
            Ok(dev) => {
                guard.device = Some(dev);
                guard.warned = false;
                guard.state.reset();
                true
            }
            Err(err) => {
                if log_failure && !guard.warned {
                    guard.warned = true;
                    eprintln!(
                        "[SilentInput] Warning: could not initialize virtual touchpad ({err}). Native touchpad mode disabled."
                    );
                }
                false
            }
        }
    }

    fn apply_touchpad_frame(&self, conn_id: u64, contacts: &[TouchContact]) -> bool {
        self.emit_touchpad_events(conn_id, contacts, false)
    }

    fn release_connection_touchpad(&self, conn_id: u64) {
        let _ = self.emit_touchpad_events(conn_id, &[], true);
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
                        drop(guard);
                        self.mouse_tracker
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .held
                            .clear();
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
                    tokio::time::sleep(Duration::from_millis(8)).await;
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

    fn read_clipboard<'a>(&'a self) -> BoxFuture<'a, Option<String>> {
        Box::pin(async move {
            let (has_wayland, has_x11) = detect_session_capabilities();
            let mut env_vars = Vec::new();
            if has_wayland {
                env_vars.extend(detect_wayland_env());
            }
            if has_x11 {
                env_vars.extend(detect_x11_env());
            }

            let candidates = clipboard_read_candidates_for_session(has_wayland, has_x11);
            for (bin, args) in &candidates {
                if let Some(text) = read_clipboard_with_tool(bin, args, &env_vars).await {
                    return Some(text);
                }
            }
            None
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

    fn take_hwheel_detents(&self, conn_id: u64, delta_hi: i32) -> i32 {
        self.mouse_tracker
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .accumulate_hwheel(conn_id, delta_hi)
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
        self.input.release_connection_touchpad(self.conn_id);
    }
}

async fn read_clipboard_with_tool(
    bin: &str,
    args: &[&str],
    env_vars: &[(String, String)],
) -> Option<String> {
    let mut cmd = Command::new(bin);
    cmd.args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true);
    for (k, v) in env_vars {
        cmd.env(k, v);
    }

    let mut child = cmd.spawn().ok()?;
    let stdout = child.stdout.take()?;

    let read_fut = async {
        let mut buf = Vec::new();
        let mut limited = stdout.take((MAX_BACKUP_CLIPBOARD_BYTES + 1) as u64);
        limited.read_to_end(&mut buf).await.ok()?;
        let status = child.wait().await.ok()?;
        if !status.success() || buf.is_empty() || buf.len() > MAX_BACKUP_CLIPBOARD_BYTES {
            return None;
        }
        String::from_utf8(buf).ok()
    };

    match tokio::time::timeout(Duration::from_millis(CLIPBOARD_READ_TIMEOUT_MS), read_fut).await {
        Ok(res) => res,
        Err(_) => {
            let _ = child.kill().await;
            let _ = child.wait().await;
            None
        }
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
        if !input.has_uinput_device() || !input.has_touchpad_device() {
            input.ensure_ready(attempts == 0);
            attempts = attempts.saturating_add(1);
        } else {
            attempts = 0;
        }
        let delay_secs = if (!input.has_uinput_device() || !input.has_touchpad_device()) && attempts < 30 {
            2
        } else {
            15
        };
        tokio::time::sleep(Duration::from_secs(delay_secs)).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn touchpad_mt_state_tracks_multi_finger_lifecycle_and_release() {
        let mut state = TouchpadMtState::default();

        // 1. Primary finger down -> slot 0, tid 1, BTN_TOUCH=1, BTN_TOOL_FINGER=1, ABS_X/Y
        let ev1 = state.apply_frame(
            10,
            &[TouchContact {
                pointer_id: 7,
                x: 4000,
                y: 3500,
            }],
        );
        assert!(ev1.iter().any(|e| e.event_type() == EventType::ABSOLUTE
            && e.code() == AbsoluteAxisType::ABS_MT_SLOT.0
            && e.value() == 0));
        assert!(ev1.iter().any(|e| e.event_type() == EventType::ABSOLUTE
            && e.code() == AbsoluteAxisType::ABS_MT_TRACKING_ID.0
            && e.value() == 1));
        assert!(ev1.iter().any(|e| e.event_type() == EventType::KEY
            && e.code() == Key::BTN_TOUCH.code()
            && e.value() == 1));
        assert!(ev1.iter().any(|e| e.event_type() == EventType::KEY
            && e.code() == Key::BTN_TOOL_FINGER.code()
            && e.value() == 1));
        assert!(ev1.iter().any(|e| e.event_type() == EventType::ABSOLUTE
            && e.code() == AbsoluteAxisType::ABS_X.0
            && e.value() == 4000));

        // 2. Second finger added -> slot 1, tid 2, FINGER=0, DOUBLETAP=1
        let ev2 = state.apply_frame(
            10,
            &[
                TouchContact {
                    pointer_id: 7,
                    x: 4050,
                    y: 3500,
                },
                TouchContact {
                    pointer_id: 9,
                    x: 4500,
                    y: 3600,
                },
            ],
        );
        assert!(ev2.iter().any(|e| e.event_type() == EventType::ABSOLUTE
            && e.code() == AbsoluteAxisType::ABS_MT_SLOT.0
            && e.value() == 1));
        assert!(ev2.iter().any(|e| e.event_type() == EventType::ABSOLUTE
            && e.code() == AbsoluteAxisType::ABS_MT_TRACKING_ID.0
            && e.value() == 2));
        assert!(ev2.iter().any(|e| e.event_type() == EventType::KEY
            && e.code() == Key::BTN_TOOL_FINGER.code()
            && e.value() == 0));
        assert!(ev2.iter().any(|e| e.event_type() == EventType::KEY
            && e.code() == Key::BTN_TOOL_DOUBLETAP.code()
            && e.value() == 1));

        // 3. First finger lifts -> slot 0 tracking_id = -1, DOUBLETAP=0, FINGER=1
        let ev3 = state.apply_frame(
            10,
            &[TouchContact {
                pointer_id: 9,
                x: 4500,
                y: 3600,
            }],
        );
        assert!(ev3.iter().any(|e| e.event_type() == EventType::ABSOLUTE
            && e.code() == AbsoluteAxisType::ABS_MT_SLOT.0
            && e.value() == 0));
        assert!(ev3.iter().any(|e| e.event_type() == EventType::ABSOLUTE
            && e.code() == AbsoluteAxisType::ABS_MT_TRACKING_ID.0
            && e.value() == -1));
        assert!(ev3.iter().any(|e| e.event_type() == EventType::KEY
            && e.code() == Key::BTN_TOOL_DOUBLETAP.code()
            && e.value() == 0));
        assert!(ev3.iter().any(|e| e.event_type() == EventType::KEY
            && e.code() == Key::BTN_TOOL_FINGER.code()
            && e.value() == 1));

        // 4. Different connection release does NOT lift owner conn=10
        assert!(state.release_connection(99).is_empty());

        // 5. Owner connection release lifts remaining slot 1, FINGER=0, BTN_TOUCH=0
        let ev_rel = state.release_connection(10);
        assert!(ev_rel.iter().any(|e| e.event_type() == EventType::ABSOLUTE
            && e.code() == AbsoluteAxisType::ABS_MT_TRACKING_ID.0
            && e.value() == -1));
        assert!(ev_rel.iter().any(|e| e.event_type() == EventType::KEY
            && e.code() == Key::BTN_TOOL_FINGER.code()
            && e.value() == 0));
        assert!(ev_rel.iter().any(|e| e.event_type() == EventType::KEY
            && e.code() == Key::BTN_TOUCH.code()
            && e.value() == 0));
    }
}
