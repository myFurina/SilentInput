use std::collections::{BTreeSet, HashMap};
use std::env;
use std::os::unix::fs::FileTypeExt;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use evdev::Key;

static KEY_MAP_CACHE: OnceLock<HashMap<&'static str, Key>> = OnceLock::new();

fn cached_key_map() -> &'static HashMap<&'static str, Key> {
    KEY_MAP_CACHE.get_or_init(build_key_map)
}

pub fn build_key_map() -> HashMap<&'static str, Key> {
    HashMap::from([
        ("a", Key::KEY_A),
        ("b", Key::KEY_B),
        ("c", Key::KEY_C),
        ("d", Key::KEY_D),
        ("e", Key::KEY_E),
        ("f", Key::KEY_F),
        ("g", Key::KEY_G),
        ("h", Key::KEY_H),
        ("i", Key::KEY_I),
        ("j", Key::KEY_J),
        ("k", Key::KEY_K),
        ("l", Key::KEY_L),
        ("m", Key::KEY_M),
        ("n", Key::KEY_N),
        ("o", Key::KEY_O),
        ("p", Key::KEY_P),
        ("q", Key::KEY_Q),
        ("r", Key::KEY_R),
        ("s", Key::KEY_S),
        ("t", Key::KEY_T),
        ("u", Key::KEY_U),
        ("v", Key::KEY_V),
        ("w", Key::KEY_W),
        ("x", Key::KEY_X),
        ("y", Key::KEY_Y),
        ("z", Key::KEY_Z),
        ("0", Key::KEY_0),
        ("1", Key::KEY_1),
        ("2", Key::KEY_2),
        ("3", Key::KEY_3),
        ("4", Key::KEY_4),
        ("5", Key::KEY_5),
        ("6", Key::KEY_6),
        ("7", Key::KEY_7),
        ("8", Key::KEY_8),
        ("9", Key::KEY_9),
        ("enter", Key::KEY_ENTER),
        ("esc", Key::KEY_ESC),
        ("backspace", Key::KEY_BACKSPACE),
        ("tab", Key::KEY_TAB),
        ("space", Key::KEY_SPACE),
        ("delete", Key::KEY_DELETE),
        ("del", Key::KEY_DELETE),
        ("insert", Key::KEY_INSERT),
        ("ins", Key::KEY_INSERT),
        ("home", Key::KEY_HOME),
        ("end", Key::KEY_END),
        ("pageup", Key::KEY_PAGEUP),
        ("pagedown", Key::KEY_PAGEDOWN),
        ("print", Key::KEY_SYSRQ),
        ("printscreen", Key::KEY_SYSRQ),
        ("prtsc", Key::KEY_SYSRQ),
        ("sysrq", Key::KEY_SYSRQ),
        ("shift", Key::KEY_LEFTSHIFT),
        ("ctrl", Key::KEY_LEFTCTRL),
        ("alt", Key::KEY_LEFTALT),
        ("super", Key::KEY_LEFTMETA),
        ("win", Key::KEY_LEFTMETA),
        ("up", Key::KEY_UP),
        ("down", Key::KEY_DOWN),
        ("left", Key::KEY_LEFT),
        ("right", Key::KEY_RIGHT),
        ("minus", Key::KEY_MINUS),
        ("-", Key::KEY_MINUS),
        ("equal", Key::KEY_EQUAL),
        ("=", Key::KEY_EQUAL),
        ("slash", Key::KEY_SLASH),
        ("/", Key::KEY_SLASH),
        ("backslash", Key::KEY_BACKSLASH),
        ("\\", Key::KEY_BACKSLASH),
        ("[", Key::KEY_LEFTBRACE),
        ("leftbrace", Key::KEY_LEFTBRACE),
        ("lbracket", Key::KEY_LEFTBRACE),
        ("]", Key::KEY_RIGHTBRACE),
        ("rightbrace", Key::KEY_RIGHTBRACE),
        ("rbracket", Key::KEY_RIGHTBRACE),
        (";", Key::KEY_SEMICOLON),
        ("semicolon", Key::KEY_SEMICOLON),
        ("'", Key::KEY_APOSTROPHE),
        ("apostrophe", Key::KEY_APOSTROPHE),
        ("quote", Key::KEY_APOSTROPHE),
        ("`", Key::KEY_GRAVE),
        ("grave", Key::KEY_GRAVE),
        ("backtick", Key::KEY_GRAVE),
        (",", Key::KEY_COMMA),
        ("comma", Key::KEY_COMMA),
        (".", Key::KEY_DOT),
        ("dot", Key::KEY_DOT),
        ("period", Key::KEY_DOT),
        ("f1", Key::KEY_F1),
        ("f2", Key::KEY_F2),
        ("f3", Key::KEY_F3),
        ("f4", Key::KEY_F4),
        ("f5", Key::KEY_F5),
        ("f6", Key::KEY_F6),
        ("f7", Key::KEY_F7),
        ("f8", Key::KEY_F8),
        ("f9", Key::KEY_F9),
        ("f10", Key::KEY_F10),
        ("f11", Key::KEY_F11),
        ("f12", Key::KEY_F12),
    ])
}

pub fn lookup_char(ch: char) -> Option<(Key, bool)> {
    let mapping = match ch {
        ' ' => (Key::KEY_SPACE, false),
        '\t' => (Key::KEY_TAB, false),
        '\n' => (Key::KEY_ENTER, false),
        '-' => (Key::KEY_MINUS, false),
        '_' => (Key::KEY_MINUS, true),
        '=' => (Key::KEY_EQUAL, false),
        '+' => (Key::KEY_EQUAL, true),
        '[' => (Key::KEY_LEFTBRACE, false),
        '{' => (Key::KEY_LEFTBRACE, true),
        ']' => (Key::KEY_RIGHTBRACE, false),
        '}' => (Key::KEY_RIGHTBRACE, true),
        ';' => (Key::KEY_SEMICOLON, false),
        ':' => (Key::KEY_SEMICOLON, true),
        '\'' => (Key::KEY_APOSTROPHE, false),
        '"' => (Key::KEY_APOSTROPHE, true),
        '`' => (Key::KEY_GRAVE, false),
        '~' => (Key::KEY_GRAVE, true),
        '\\' => (Key::KEY_BACKSLASH, false),
        '|' => (Key::KEY_BACKSLASH, true),
        ',' => (Key::KEY_COMMA, false),
        '<' => (Key::KEY_COMMA, true),
        '.' => (Key::KEY_DOT, false),
        '>' => (Key::KEY_DOT, true),
        '/' => (Key::KEY_SLASH, false),
        '?' => (Key::KEY_SLASH, true),
        '!' => (Key::KEY_1, true),
        '@' => (Key::KEY_2, true),
        '#' => (Key::KEY_3, true),
        '$' => (Key::KEY_4, true),
        '%' => (Key::KEY_5, true),
        '^' => (Key::KEY_6, true),
        '&' => (Key::KEY_7, true),
        '*' => (Key::KEY_8, true),
        '(' => (Key::KEY_9, true),
        ')' => (Key::KEY_0, true),
        '0' => (Key::KEY_0, false),
        '1' => (Key::KEY_1, false),
        '2' => (Key::KEY_2, false),
        '3' => (Key::KEY_3, false),
        '4' => (Key::KEY_4, false),
        '5' => (Key::KEY_5, false),
        '6' => (Key::KEY_6, false),
        '7' => (Key::KEY_7, false),
        '8' => (Key::KEY_8, false),
        '9' => (Key::KEY_9, false),
        'a' => (Key::KEY_A, false),
        'A' => (Key::KEY_A, true),
        'b' => (Key::KEY_B, false),
        'B' => (Key::KEY_B, true),
        'c' => (Key::KEY_C, false),
        'C' => (Key::KEY_C, true),
        'd' => (Key::KEY_D, false),
        'D' => (Key::KEY_D, true),
        'e' => (Key::KEY_E, false),
        'E' => (Key::KEY_E, true),
        'f' => (Key::KEY_F, false),
        'F' => (Key::KEY_F, true),
        'g' => (Key::KEY_G, false),
        'G' => (Key::KEY_G, true),
        'h' => (Key::KEY_H, false),
        'H' => (Key::KEY_H, true),
        'i' => (Key::KEY_I, false),
        'I' => (Key::KEY_I, true),
        'j' => (Key::KEY_J, false),
        'J' => (Key::KEY_J, true),
        'k' => (Key::KEY_K, false),
        'K' => (Key::KEY_K, true),
        'l' => (Key::KEY_L, false),
        'L' => (Key::KEY_L, true),
        'm' => (Key::KEY_M, false),
        'M' => (Key::KEY_M, true),
        'n' => (Key::KEY_N, false),
        'N' => (Key::KEY_N, true),
        'o' => (Key::KEY_O, false),
        'O' => (Key::KEY_O, true),
        'p' => (Key::KEY_P, false),
        'P' => (Key::KEY_P, true),
        'q' => (Key::KEY_Q, false),
        'Q' => (Key::KEY_Q, true),
        'r' => (Key::KEY_R, false),
        'R' => (Key::KEY_R, true),
        's' => (Key::KEY_S, false),
        'S' => (Key::KEY_S, true),
        't' => (Key::KEY_T, false),
        'T' => (Key::KEY_T, true),
        'u' => (Key::KEY_U, false),
        'U' => (Key::KEY_U, true),
        'v' => (Key::KEY_V, false),
        'V' => (Key::KEY_V, true),
        'w' => (Key::KEY_W, false),
        'W' => (Key::KEY_W, true),
        'x' => (Key::KEY_X, false),
        'X' => (Key::KEY_X, true),
        'y' => (Key::KEY_Y, false),
        'Y' => (Key::KEY_Y, true),
        'z' => (Key::KEY_Z, false),
        'Z' => (Key::KEY_Z, true),
        _ => return None,
    };
    Some(mapping)
}

pub fn all_registered_keys() -> Vec<Key> {
    let mut keys = BTreeSet::new();
    for key in cached_key_map().values() {
        keys.insert(key.code());
    }
    for code_u8 in 0x09u8..=0x7eu8 {
        if let Some((k, _)) = lookup_char(code_u8 as char) {
            keys.insert(k.code());
        }
    }
    keys.insert(Key::BTN_LEFT.code());
    keys.insert(Key::BTN_RIGHT.code());
    keys.insert(Key::BTN_MIDDLE.code());
    keys.into_iter().map(Key::new).collect()
}

/// Resolve a key combination strictly: EVERY segment must exist in KEY_MAP,
/// otherwise returns `None` without partially triggering modifiers.
pub fn resolve_key_combination(combo_str: &str) -> Option<Vec<Key>> {
    let key_map = cached_key_map();
    let parts: Vec<&str> = combo_str
        .split('+')
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .collect();
    if parts.is_empty() {
        return None;
    }

    let mut codes = Vec::with_capacity(parts.len());
    for part in parts {
        let lower = part.to_ascii_lowercase();
        let key = key_map.get(lower.as_str())?;
        codes.push(*key);
    }
    Some(codes)
}

fn wtype_modifier_name(part: &str) -> Option<&'static str> {
    match part {
        "ctrl" => Some("ctrl"),
        "alt" => Some("alt"),
        "shift" => Some("shift"),
        "super" | "win" => Some("logo"),
        _ => None,
    }
}

fn wtype_key_name(key: &str) -> Option<String> {
    let mapped = match key {
        "enter" => "Return",
        "esc" => "Escape",
        "backspace" => "BackSpace",
        "tab" => "Tab",
        "space" => "space",
        "delete" | "del" => "Delete",
        "insert" | "ins" => "Insert",
        "home" => "Home",
        "end" => "End",
        "pageup" => "Prior",
        "pagedown" => "Next",
        "print" | "printscreen" | "prtsc" | "sysrq" => "Print",
        "up" => "Up",
        "down" => "Down",
        "left" => "Left",
        "right" => "Right",
        "minus" | "-" => "minus",
        "equal" | "=" => "equal",
        "slash" | "/" => "slash",
        "backslash" | "\\" => "backslash",
        "[" | "leftbrace" | "lbracket" => "bracketleft",
        "]" | "rightbrace" | "rbracket" => "bracketright",
        ";" | "semicolon" => "semicolon",
        "'" | "apostrophe" | "quote" => "apostrophe",
        "`" | "grave" | "backtick" => "grave",
        "," | "comma" => "comma",
        "." | "dot" | "period" => "period",
        "f1" => "F1",
        "f2" => "F2",
        "f3" => "F3",
        "f4" => "F4",
        "f5" => "F5",
        "f6" => "F6",
        "f7" => "F7",
        "f8" => "F8",
        "f9" => "F9",
        "f10" => "F10",
        "f11" => "F11",
        "f12" => "F12",
        _ => return None,
    };
    Some(mapped.to_string())
}

fn resolve_keysym_name(target_key: &str) -> Option<String> {
    match target_key {
        "ctrl" => return Some("Control_L".to_string()),
        "alt" => return Some("Alt_L".to_string()),
        "shift" => return Some("Shift_L".to_string()),
        "super" | "win" => return Some("Super_L".to_string()),
        _ => {}
    }
    let key_map = cached_key_map();
    let is_single_alnum = target_key.len() == 1
        && target_key
            .chars()
            .all(|c| c.is_ascii_alphanumeric());

    if let Some(mapped) = wtype_key_name(target_key) {
        Some(mapped)
    } else if !target_key.starts_with('-')
        && (key_map.contains_key(target_key) || is_single_alnum)
    {
        Some(target_key.to_string())
    } else {
        None
    }
}

/// Build validated `wtype` arguments, rejecting invalid/flag-like tokens (`--help`, `bad;cmd`, `ctrl+-M`).
pub fn build_wtype_args(combo_str: &str) -> Option<Vec<String>> {
    let lower = combo_str.to_ascii_lowercase();
    let parts: Vec<&str> = lower
        .split('+')
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .collect();
    if parts.is_empty() {
        return None;
    }

    let mut args = Vec::new();
    for mod_part in &parts[..parts.len() - 1] {
        let mod_name = wtype_modifier_name(mod_part)?;
        args.push("-M".to_string());
        args.push(mod_name.to_string());
    }

    let resolved_key = resolve_keysym_name(parts[parts.len() - 1])?;
    args.push("-k".to_string());
    args.push(resolved_key);
    Some(args)
}

fn xdotool_modifier_name(part: &str) -> Option<&'static str> {
    match part {
        "ctrl" => Some("ctrl"),
        "alt" => Some("alt"),
        "shift" => Some("shift"),
        "super" | "win" => Some("Super_L"),
        _ => None,
    }
}

/// Build validated `xdotool` arguments for X11 fallback key injection.
pub fn build_xdotool_args(combo_str: &str) -> Option<Vec<String>> {
    let lower = combo_str.to_ascii_lowercase();
    let parts: Vec<&str> = lower
        .split('+')
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .collect();
    if parts.is_empty() {
        return None;
    }

    let mut seq = Vec::with_capacity(parts.len());
    for mod_part in &parts[..parts.len() - 1] {
        seq.push(xdotool_modifier_name(mod_part)?.to_string());
    }
    seq.push(resolve_keysym_name(parts[parts.len() - 1])?);
    Some(vec!["key".to_string(), seq.join("+")])
}

pub fn normalize_line_endings(text: &str) -> String {
    text.replace("\r\n", "\n").replace('\r', "\n")
}

pub fn detect_wayland_env() -> Vec<(String, String)> {
    let uid = unsafe { libc::geteuid() };
    let runtime_dir = env::var("XDG_RUNTIME_DIR")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| format!("/run/user/{uid}"));

    let wayland_display = env::var("WAYLAND_DISPLAY")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| {
            find_newest_wayland_socket(Path::new(&runtime_dir))
                .unwrap_or_else(|| "wayland-0".to_string())
        });

    vec![
        ("XDG_RUNTIME_DIR".to_string(), runtime_dir),
        ("WAYLAND_DISPLAY".to_string(), wayland_display),
    ]
}

fn find_newest_wayland_socket(runtime_dir: &Path) -> Option<String> {
    let entries = std::fs::read_dir(runtime_dir).ok()?;
    let mut best: Option<(std::time::SystemTime, String)> = None;
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if !name.starts_with("wayland-") || name.ends_with(".lock") {
            continue;
        }
        if let Ok(meta) = entry.metadata() {
            if meta.file_type().is_socket() {
                let mtime = meta.modified().unwrap_or(std::time::UNIX_EPOCH);
                if best.as_ref().is_none_or(|(b_time, _)| mtime > *b_time) {
                    best = Some((mtime, name));
                }
            }
        }
    }
    best.map(|(_, name)| name)
}

pub fn detect_has_wayland_in(runtime_dir: &Path, wayland_display: Option<&str>) -> bool {
    if let Some(wd) = wayland_display.map(str::trim).filter(|s| !s.is_empty()) {
        let sock_path: PathBuf = if wd.contains('/') {
            PathBuf::from(wd)
        } else {
            runtime_dir.join(wd)
        };
        if let Ok(meta) = std::fs::metadata(&sock_path) {
            if meta.file_type().is_socket() {
                return true;
            }
        }
    } else if let Ok(entries) = std::fs::read_dir(runtime_dir) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if name.starts_with("wayland-") && !name.ends_with(".lock") {
                if let Ok(meta) = entry.metadata() {
                    if meta.file_type().is_socket() {
                        return true;
                    }
                }
            }
        }
    }
    false
}

pub fn detect_x11_display_in(x_display: Option<&str>, x11_unix_dir: &Path) -> Option<String> {
    if let Some(disp) = x_display.map(str::trim).filter(|s| !s.is_empty()) {
        if let Some(rest) = disp.strip_prefix(':') {
            let disp_num = rest.split('.').next().unwrap_or("");
            if !disp_num.is_empty() && disp_num.chars().all(|c| c.is_ascii_digit()) {
                let x_sock = x11_unix_dir.join(format!("X{disp_num}"));
                if let Ok(meta) = std::fs::metadata(&x_sock) {
                    if meta.file_type().is_socket() {
                        return Some(disp.to_string());
                    }
                }
            }
            return None;
        }
        return Some(disp.to_string());
    }

    let entries = std::fs::read_dir(x11_unix_dir).ok()?;
    let mut best: Option<(std::time::SystemTime, String)> = None;
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let Some(num) = name.strip_prefix('X') else {
            continue;
        };
        if num.is_empty() || !num.chars().all(|c| c.is_ascii_digit()) {
            continue;
        }
        if let Ok(meta) = entry.metadata() {
            if meta.file_type().is_socket() {
                let mtime = meta.modified().unwrap_or(std::time::UNIX_EPOCH);
                if best.as_ref().is_none_or(|(b_time, _)| mtime > *b_time) {
                    best = Some((mtime, format!(":{num}")));
                }
            }
        }
    }
    best.map(|(_, d)| d)
}

pub fn find_xauthority_in(runtime_dir: &Path, home_dir: Option<&Path>) -> Option<PathBuf> {
    if let Some(home) = home_dir {
        let candidate = home.join(".Xauthority");
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    let gdm_xauth = runtime_dir.join("gdm").join("Xauthority");
    if gdm_xauth.is_file() {
        return Some(gdm_xauth);
    }
    let entries = std::fs::read_dir(runtime_dir).ok()?;
    let mut best: Option<(std::time::SystemTime, PathBuf)> = None;
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if !(name.starts_with("xauth_") || name.starts_with(".mutter-Xwaylandauth.")) {
            continue;
        }
        let path = entry.path();
        if let Ok(meta) = entry.metadata() {
            if meta.is_file() {
                let mtime = meta.modified().unwrap_or(std::time::UNIX_EPOCH);
                if best.as_ref().is_none_or(|(b_time, _)| mtime > *b_time) {
                    best = Some((mtime, path));
                }
            }
        }
    }
    best.map(|(_, p)| p)
}

pub fn detect_x11_env() -> Vec<(String, String)> {
    let x_display = env::var("DISPLAY").ok();
    let Some(display) = detect_x11_display_in(x_display.as_deref(), Path::new("/tmp/.X11-unix"))
    else {
        return Vec::new();
    };
    let mut vars = vec![("DISPLAY".to_string(), display)];
    if let Some(auth) = env::var("XAUTHORITY")
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty() && Path::new(s).exists())
    {
        vars.push(("XAUTHORITY".to_string(), auth));
    } else {
        let uid = unsafe { libc::geteuid() };
        let runtime_dir = env::var("XDG_RUNTIME_DIR")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| format!("/run/user/{uid}"));
        let home_path = env::var_os("HOME").map(PathBuf::from);
        if let Some(found) = find_xauthority_in(Path::new(&runtime_dir), home_path.as_deref()) {
            vars.push((
                "XAUTHORITY".to_string(),
                found.to_string_lossy().to_string(),
            ));
        }
    }
    vars
}

pub fn clipboard_candidates_for_session(
    has_wayland: bool,
    has_x11: bool,
) -> Vec<(&'static str, Vec<&'static str>)> {
    let mut out: Vec<(&'static str, Vec<&'static str>)> = Vec::new();
    if has_wayland || !has_x11 {
        out.push(("wl-copy", Vec::new()));
    }
    if has_x11 || !has_wayland {
        out.push(("xclip", vec!["-in", "-selection", "clipboard"]));
        out.push(("xsel", vec!["--clipboard", "--input"]));
    }
    out
}

pub fn clipboard_read_candidates_for_session(
    has_wayland: bool,
    has_x11: bool,
) -> Vec<(&'static str, Vec<&'static str>)> {
    let mut out: Vec<(&'static str, Vec<&'static str>)> = Vec::new();
    if has_wayland || !has_x11 {
        out.push(("wl-paste", vec!["--no-newline", "--type", "text"]));
    }
    if has_x11 || !has_wayland {
        out.push(("xclip", vec!["-out", "-selection", "clipboard"]));
        out.push(("xsel", vec!["--clipboard", "--output"]));
    }
    out
}

pub fn detect_session_capabilities() -> (bool, bool) {
    let uid = unsafe { libc::geteuid() };
    let runtime_dir = env::var("XDG_RUNTIME_DIR")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| format!("/run/user/{uid}"));
    let wayland_display = env::var("WAYLAND_DISPLAY").ok();
    let x_display = env::var("DISPLAY").ok();

    let has_wayland = detect_has_wayland_in(Path::new(&runtime_dir), wayland_display.as_deref());
    let has_x11 = detect_x11_display_in(x_display.as_deref(), Path::new("/tmp/.X11-unix")).is_some();
    (has_wayland, has_x11)
}

pub fn has_graphical_session_in(
    runtime_dir: &Path,
    wayland_display: Option<&str>,
    x_display: Option<&str>,
    x11_unix_dir: &Path,
) -> bool {
    detect_has_wayland_in(runtime_dir, wayland_display)
        || detect_x11_display_in(x_display, x11_unix_dir).is_some()
}

pub fn has_graphical_session() -> bool {
    let uid = unsafe { libc::geteuid() };
    let runtime_dir = env::var("XDG_RUNTIME_DIR")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| format!("/run/user/{uid}"));
    let wayland_display = env::var("WAYLAND_DISPLAY").ok();
    let x_display = env::var("DISPLAY").ok();

    has_graphical_session_in(
        Path::new(&runtime_dir),
        wayland_display.as_deref(),
        x_display.as_deref(),
        Path::new("/tmp/.X11-unix"),
    )
}
