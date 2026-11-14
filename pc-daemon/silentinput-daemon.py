#!/usr/bin/env python3
"""
SilentInput PC Companion Daemon for Linux (GNOME / Wayland / Bluefin)
High-performance asyncio RFC 6455 WebSocket and TCP server that handles
input injection (keyboard shortcuts, mouse gestures, clipboard paste)
with custom IP whitelist/blacklist access control and LocalSend-style optional encryption.
"""

import asyncio
import base64
import hashlib
import hmac
import json
import math
import os
import signal
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time
import weakref
from pathlib import Path

# Add script directory to sys.path to import silentinput_config
sys.path.insert(0, str(Path(__file__).resolve().parent))
import silentinput_config

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_WS_FRAME_SIZE = 1024 * 1024  # 1 MiB
MAX_WS_HEADERS = 64
MAX_WS_CONNECTIONS = 32
WS_IDLE_TIMEOUT_SECONDS = 30.0
BT_IDLE_TIMEOUT_SECONDS = 30.0
AUTH_MAX_FAILURES = 5
AUTH_LOCKOUT_SECONDS = 60.0
AUTH_FAILURE_TTL_SECONDS = 600.0
AUTH_FAILURE_MAX_ENTRIES = 1024

# Rate-limiting state: peer_ip -> (consecutive_failures, locked_until_monotonic, last_updated_monotonic)
_auth_failures: dict[str, tuple[int, float, float]] = {}
_active_ws_connections = 0
_paste_locks: weakref.WeakKeyDictionary[asyncio.AbstractEventLoop, asyncio.Lock] = weakref.WeakKeyDictionary()

def _get_paste_lock() -> asyncio.Lock:
    loop = asyncio.get_running_loop()
    lock = _paste_locks.get(loop)
    if lock is None:
        lock = asyncio.Lock()
        _paste_locks[loop] = lock
    return lock

def _prune_auth_failures(now: float | None = None):
    if not _auth_failures:
        return
    if now is None:
        now = time.monotonic()
    expired = [
        ip for ip, (_count, locked_until, last_updated) in _auth_failures.items()
        if (locked_until > 0 and now >= locked_until)
        or (locked_until <= 0 and (now - last_updated) >= AUTH_FAILURE_TTL_SECONDS)
    ]
    for ip in expired:
        _auth_failures.pop(ip, None)

def _is_auth_locked(peer_ip: str) -> bool:
    if not peer_ip:
        return False
    now = time.monotonic()
    _prune_auth_failures(now)
    entry = _auth_failures.get(peer_ip)
    if not entry:
        return False
    _, locked_until, _ = entry
    return locked_until > 0 and now < locked_until

def _record_auth_failure(peer_ip: str) -> bool:
    if not peer_ip:
        return False
    now = time.monotonic()
    _prune_auth_failures(now)
    entry = _auth_failures.get(peer_ip)
    if entry is None and len(_auth_failures) >= AUTH_FAILURE_MAX_ENTRIES:
        while len(_auth_failures) >= AUTH_FAILURE_MAX_ENTRIES:
            oldest_ip = min(_auth_failures, key=lambda ip: _auth_failures[ip][2])
            _auth_failures.pop(oldest_ip, None)
    count = (entry[0] if entry else 0) + 1
    if count >= AUTH_MAX_FAILURES:
        _auth_failures[peer_ip] = (count, now + AUTH_LOCKOUT_SECONDS, now)
        return True
    _auth_failures[peer_ip] = (count, 0.0, now)
    return False

def _clear_auth_failures(peer_ip: str):
    if peer_ip:
        _auth_failures.pop(peer_ip, None)

def _enable_tcp_keepalive(writer):
    """Enable OS-level TCP keepalive on accepted sockets when supported."""
    try:
        sock = writer.get_extra_info("socket")
        if sock is None:
            return
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        if hasattr(socket, "TCP_KEEPIDLE"):
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPIDLE, 15)
        if hasattr(socket, "TCP_KEEPINTVL"):
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPINTVL, 5)
        if hasattr(socket, "TCP_KEEPCNT"):
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPCNT, 3)
    except Exception:
        pass

def _safe_round_int(value) -> int:
    if value is None or isinstance(value, bool):
        return 0
    try:
        num = float(value)
    except (TypeError, ValueError, OverflowError):
        return 0
    if not math.isfinite(num):
        return 0
    return int(round(num))

# Initialize Linux kernel uinput virtual keyboard/mouse
HAVE_UINPUT = False
KEY_MAP = {}
CHAR_MAP = {}
evdev = None
uinput_device = None
_uinput_warned = False
_held_mouse_buttons: dict[int, set[object]] = {}

try:
    import evdev
    from evdev import ecodes
    HAVE_UINPUT = True
    KEY_MAP = {
        "a": ecodes.KEY_A, "b": ecodes.KEY_B, "c": ecodes.KEY_C, "d": ecodes.KEY_D,
        "e": ecodes.KEY_E, "f": ecodes.KEY_F, "g": ecodes.KEY_G, "h": ecodes.KEY_H,
        "i": ecodes.KEY_I, "j": ecodes.KEY_J, "k": ecodes.KEY_K, "l": ecodes.KEY_L,
        "m": ecodes.KEY_M, "n": ecodes.KEY_N, "o": ecodes.KEY_O, "p": ecodes.KEY_P,
        "q": ecodes.KEY_Q, "r": ecodes.KEY_R, "s": ecodes.KEY_S, "t": ecodes.KEY_T,
        "u": ecodes.KEY_U, "v": ecodes.KEY_V, "w": ecodes.KEY_W, "x": ecodes.KEY_X,
        "y": ecodes.KEY_Y, "z": ecodes.KEY_Z,
        "0": ecodes.KEY_0, "1": ecodes.KEY_1, "2": ecodes.KEY_2, "3": ecodes.KEY_3,
        "4": ecodes.KEY_4, "5": ecodes.KEY_5, "6": ecodes.KEY_6, "7": ecodes.KEY_7,
        "8": ecodes.KEY_8, "9": ecodes.KEY_9,
        "enter": ecodes.KEY_ENTER, "esc": ecodes.KEY_ESC,
        "backspace": ecodes.KEY_BACKSPACE, "tab": ecodes.KEY_TAB,
        "space": ecodes.KEY_SPACE, "delete": ecodes.KEY_DELETE, "del": ecodes.KEY_DELETE,
        "insert": ecodes.KEY_INSERT, "ins": ecodes.KEY_INSERT,
        "home": ecodes.KEY_HOME, "end": ecodes.KEY_END,
        "pageup": ecodes.KEY_PAGEUP, "pagedown": ecodes.KEY_PAGEDOWN,
        "print": ecodes.KEY_SYSRQ, "printscreen": ecodes.KEY_SYSRQ,
        "prtsc": ecodes.KEY_SYSRQ, "sysrq": ecodes.KEY_SYSRQ,
        "shift": ecodes.KEY_LEFTSHIFT, "ctrl": ecodes.KEY_LEFTCTRL,
        "alt": ecodes.KEY_LEFTALT, "super": ecodes.KEY_LEFTMETA, "win": ecodes.KEY_LEFTMETA,
        "up": ecodes.KEY_UP, "down": ecodes.KEY_DOWN,
        "left": ecodes.KEY_LEFT, "right": ecodes.KEY_RIGHT,
        "minus": ecodes.KEY_MINUS, "equal": ecodes.KEY_EQUAL,
        "slash": ecodes.KEY_SLASH, "backslash": ecodes.KEY_BACKSLASH,
        "f1": ecodes.KEY_F1, "f2": ecodes.KEY_F2, "f3": ecodes.KEY_F3, "f4": ecodes.KEY_F4,
        "f5": ecodes.KEY_F5, "f6": ecodes.KEY_F6, "f7": ecodes.KEY_F7, "f8": ecodes.KEY_F8,
        "f9": ecodes.KEY_F9, "f10": ecodes.KEY_F10, "f11": ecodes.KEY_F11, "f12": ecodes.KEY_F12,
    }

    # Complete printable ASCII character mapping for direct hardware typing: char -> (keycode, requires_shift)
    CHAR_MAP = {
        ' ': (ecodes.KEY_SPACE, False),
        '\t': (ecodes.KEY_TAB, False),
        '\n': (ecodes.KEY_ENTER, False),
        '-': (ecodes.KEY_MINUS, False),
        '_': (ecodes.KEY_MINUS, True),
        '=': (ecodes.KEY_EQUAL, False),
        '+': (ecodes.KEY_EQUAL, True),
        '[': (ecodes.KEY_LEFTBRACE, False),
        '{': (ecodes.KEY_LEFTBRACE, True),
        ']': (ecodes.KEY_RIGHTBRACE, False),
        '}': (ecodes.KEY_RIGHTBRACE, True),
        ';': (ecodes.KEY_SEMICOLON, False),
        ':': (ecodes.KEY_SEMICOLON, True),
        '\'': (ecodes.KEY_APOSTROPHE, False),
        '\"': (ecodes.KEY_APOSTROPHE, True),
        '`': (ecodes.KEY_GRAVE, False),
        '~': (ecodes.KEY_GRAVE, True),
        '\\': (ecodes.KEY_BACKSLASH, False),
        '|': (ecodes.KEY_BACKSLASH, True),
        ',': (ecodes.KEY_COMMA, False),
        '<': (ecodes.KEY_COMMA, True),
        '.': (ecodes.KEY_DOT, False),
        '>': (ecodes.KEY_DOT, True),
        '/': (ecodes.KEY_SLASH, False),
        '?': (ecodes.KEY_SLASH, True),
        '!': (ecodes.KEY_1, True),
        '@': (ecodes.KEY_2, True),
        '#': (ecodes.KEY_3, True),
        '$': (ecodes.KEY_4, True),
        '%': (ecodes.KEY_5, True),
        '^': (ecodes.KEY_6, True),
        '&': (ecodes.KEY_7, True),
        '*': (ecodes.KEY_8, True),
        '(': (ecodes.KEY_9, True),
        ')': (ecodes.KEY_0, True),
    }
    for d in '0123456789':
        CHAR_MAP[d] = (getattr(ecodes, f'KEY_{d}'), False)
    for c in 'abcdefghijklmnopqrstuvwxyz':
        CHAR_MAP[c] = (getattr(ecodes, f'KEY_{c.upper()}'), False)
        CHAR_MAP[c.upper()] = (getattr(ecodes, f'KEY_{c.upper()}'), True)

    all_registered_keys = list(set(list(KEY_MAP.values()) + [v[0] for v in CHAR_MAP.values()] + [ecodes.BTN_LEFT, ecodes.BTN_RIGHT, ecodes.BTN_MIDDLE]))
    UINPUT_CAPABILITIES = {
        ecodes.EV_KEY: all_registered_keys,
        ecodes.EV_REL: [ecodes.REL_X, ecodes.REL_Y, ecodes.REL_WHEEL],
    }
except Exception as e:
    UINPUT_CAPABILITIES = {}
    print(f"[SilentInput] Warning: could not load evdev mappings ({e}). Fallback to tools.", file=sys.stderr)

def init_uinput_device(log_failure: bool = True) -> bool:
    global uinput_device, _uinput_warned
    if uinput_device is not None:
        return True
    if not HAVE_UINPUT or evdev is None or not UINPUT_CAPABILITIES:
        return False
    try:
        uinput_device = evdev.UInput(UINPUT_CAPABILITIES, name="SilentInput-Virtual-Device")
        _uinput_warned = False
        print(f"[SilentInput] Kernel uinput virtual hardware device created successfully ({len(UINPUT_CAPABILITIES[evdev.ecodes.EV_KEY])} keys supported).")
        return True
    except Exception as e:
        uinput_device = None
        if log_failure and not _uinput_warned:
            _uinput_warned = True
            print(f"[SilentInput] Warning: could not initialize kernel uinput ({e}). Will retry automatically.", file=sys.stderr)
        return False

def ensure_uinput_device() -> bool:
    if uinput_device is not None:
        return True
    return init_uinput_device(log_failure=False)

def close_uinput_device():
    global uinput_device
    if uinput_device is not None:
        try:
            uinput_device.close()
        except Exception:
            pass
        uinput_device = None

def _emit_uinput_events(events: list[tuple[int, int, int]]) -> bool:
    """Write (ev_type, code, value) tuples and syn() cleanly; reset device on OSError."""
    if not ensure_uinput_device() or uinput_device is None:
        return False
    for attempt in range(2):
        dev = uinput_device
        if dev is None:
            if not ensure_uinput_device():
                return False
            dev = uinput_device
        try:
            for ev_type, code, value in events:
                dev.write(ev_type, code, value)
            dev.syn()
            return True
        except OSError as e:
            print(f"[SilentInput] Warning: uinput write failed ({e}), resetting virtual device.", file=sys.stderr)
            close_uinput_device()
            if attempt == 0 and ensure_uinput_device():
                continue
            return False
    return False

def _detect_wayland_env() -> dict:
    """Build environment dict with valid Wayland socket (ignoring .lock files)."""
    env = os.environ.copy()
    uid = os.getuid()
    if not env.get("XDG_RUNTIME_DIR"):
        env["XDG_RUNTIME_DIR"] = f"/run/user/{uid}"

    if not env.get("WAYLAND_DISPLAY"):
        try:
            runtime_dir = Path(env["XDG_RUNTIME_DIR"])
            wayland_socks = sorted(
                [
                    p for p in runtime_dir.glob("wayland-*")
                    if not p.name.endswith(".lock") and p.is_socket()
                ],
                key=lambda p: p.stat().st_mtime,
                reverse=True,
            )
            env["WAYLAND_DISPLAY"] = wayland_socks[0].name if wayland_socks else "wayland-0"
        except Exception:
            env["WAYLAND_DISPLAY"] = "wayland-0"
    return env

async def _run_wl_copy(args: list[str], text: str, env: dict):
    # wl-copy forks a background child to serve the Wayland clipboard selection.
    # The forked child inherits open FDs, so stderr=PIPE would prevent EOF and
    # stall proc.communicate() for the full timeout. Use DEVNULL instead.
    proc = await asyncio.create_subprocess_exec(
        *args,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.DEVNULL,
        env=env,
    )
    try:
        await asyncio.wait_for(proc.communicate(input=text.encode("utf-8")), timeout=2.0)
        if proc.returncode != 0:
            raise RuntimeError(f"{' '.join(args)} failed with exit {proc.returncode}")
    except asyncio.TimeoutError:
        try:
            proc.kill()
            await proc.wait()
        except Exception:
            pass
        raise RuntimeError(f"{' '.join(args)} timed out")

async def copy_to_wayland_clipboard(text: str) -> bool:
    """Write text to Wayland regular clipboard using wl-copy without blocking the event loop."""
    env = _detect_wayland_env()
    try:
        await _run_wl_copy(["wl-copy"], text, env)
        print(f"[SilentInput] Copied {len(text)} chars to clipboard.")
        return True
    except Exception as e:
        print(f"[SilentInput] Error writing to clipboard: {e}", file=sys.stderr)
        return False

async def type_text(text: str) -> bool:
    """
    Directly emit keyboard events for each character using virtual uinput hardware keyboard.
    Guaranteed to work natively in any Linux terminal (bash, zsh, vim, nano, Ptyxis),
    GUI app, and password prompt without needing clipboard or paste shortcuts.
    """
    if not ensure_uinput_device() or evdev is None:
        return False

    for ch in text:
        if ch in CHAR_MAP:
            keycode, requires_shift = CHAR_MAP[ch]
            press = []
            if requires_shift:
                press.append((evdev.ecodes.EV_KEY, evdev.ecodes.KEY_LEFTSHIFT, 1))
            press.append((evdev.ecodes.EV_KEY, keycode, 1))
            if not _emit_uinput_events(press):
                return False
            await asyncio.sleep(0.002)
            release = [(evdev.ecodes.EV_KEY, keycode, 0)]
            if requires_shift:
                release.append((evdev.ecodes.EV_KEY, evdev.ecodes.KEY_LEFTSHIFT, 0))
            if not _emit_uinput_events(release):
                return False
            await asyncio.sleep(0.002)
    return True

async def emit_key_combination(combo_str: str):
    """Emit key combination or single key via virtual hardware keyboard"""
    if ensure_uinput_device() and evdev is not None:
        parts = combo_str.lower().split("+")
        codes = [KEY_MAP[p.strip()] for p in parts if p.strip() in KEY_MAP]
        if codes:
            if _emit_uinput_events([(evdev.ecodes.EV_KEY, code, 1) for code in codes]):
                await asyncio.sleep(0.015)
                _emit_uinput_events([(evdev.ecodes.EV_KEY, code, 0) for code in reversed(codes)])
                return

    # Fallback to wtype/ydotool (non-blocking)
    parts = [p.strip() for p in combo_str.lower().split("+") if p.strip()]
    if not parts:
        return

    wtype_mods = {"ctrl": "ctrl", "alt": "alt", "shift": "shift", "super": "logo", "win": "logo"}
    wtype_key_map = {
        "enter": "Return", "esc": "Escape", "backspace": "BackSpace", "tab": "Tab",
        "space": "space", "delete": "Delete", "del": "Delete",
        "insert": "Insert", "ins": "Insert", "home": "Home", "end": "End",
        "pageup": "Prior", "pagedown": "Next",
        "print": "Print", "printscreen": "Print", "prtsc": "Print", "sysrq": "Print",
        "up": "Up", "down": "Down", "left": "Left", "right": "Right",
        "minus": "minus", "equal": "equal", "slash": "slash", "backslash": "backslash",
    }
    for i in range(1, 13):
        wtype_key_map[f"f{i}"] = f"F{i}"

    wtype_cmd = ["wtype"]
    for mod in parts[:-1]:
        if mod in wtype_mods:
            wtype_cmd.extend(["-M", wtype_mods[mod]])
    target_key = parts[-1]
    is_single_alnum = len(target_key) == 1 and target_key.isascii() and target_key.isalnum()
    if target_key.startswith("-") or (
        target_key not in wtype_key_map and target_key not in KEY_MAP and not is_single_alnum
    ):
        print(f"[SilentInput] Warning: ignoring invalid fallback key '{combo_str}'.", file=sys.stderr)
        return
    wtype_cmd.extend(["-k", wtype_key_map.get(target_key, target_key)])

    fallback_cmds = [wtype_cmd]
    if HAVE_UINPUT and all(p in KEY_MAP for p in parts):
        codes = [KEY_MAP[p] for p in parts]
        ydotool_args = ["ydotool", "key"] + [f"{c}:1" for c in codes] + [f"{c}:0" for c in reversed(codes)]
        fallback_cmds.append(ydotool_args)

    env = _detect_wayland_env()
    for cmd in fallback_cmds:
        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
                env=env,
            )
            rc = await asyncio.wait_for(proc.wait(), timeout=2.0)
            if rc == 0:
                return
        except Exception:
            continue
    print(f"[SilentInput] Warning: could not emit key '{combo_str}' (uinput unavailable and fallback tools failed).", file=sys.stderr)

async def ws_handshake(reader, writer, first_line: str) -> bool:
    """RFC 6455 WebSocket handshake. Rejects browser Origin and non-Upgrade HTTP requests."""
    parts = first_line.split()
    if len(parts) < 3 or parts[0] != "GET":
        writer.write(b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
        await writer.drain()
        return False

    headers = {}
    for _ in range(MAX_WS_HEADERS):
        try:
            line = await asyncio.wait_for(reader.readline(), timeout=5.0)
        except asyncio.TimeoutError:
            return False
        if not line or line in (b"\r\n", b"\n"):
            break
        hparts = line.decode("utf-8", errors="ignore").split(":", 1)
        if len(hparts) == 2:
            headers[hparts[0].strip().lower()] = hparts[1].strip()
    else:
        writer.write(b"HTTP/1.1 431 Request Header Fields Too Large\r\nConnection: close\r\n\r\n")
        await writer.drain()
        return False

    # Reject browser-initiated WebSocket requests carrying an Origin header
    if "origin" in headers:
        writer.write(b"HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n")
        await writer.drain()
        return False

    upgrade_hdr = headers.get("upgrade", "").lower()
    conn_hdr = headers.get("connection", "").lower()
    key = headers.get("sec-websocket-key", "")
    if upgrade_hdr != "websocket" or "upgrade" not in conn_hdr or not key:
        writer.write(b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
        await writer.drain()
        return False

    accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode("utf-8")).digest()).decode("utf-8")
    resp = (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
    )
    writer.write(resp.encode("utf-8"))
    await writer.drain()
    return True

async def ws_send_frame(writer, text_data: str):
    """Send a text frame to the WebSocket client"""
    payload = text_data.encode("utf-8")
    length = len(payload)
    frame = bytearray([0x81])  # FIN + Text frame
    if length <= 125:
        frame.append(length)
    elif length <= 65535:
        frame.append(126)
        frame.extend(struct.pack("!H", length))
    else:
        frame.append(127)
        frame.extend(struct.pack("!Q", length))
    frame.extend(payload)
    writer.write(frame)
    await writer.drain()

async def ws_recv_frame(reader, writer):
    """
    Read an RFC 6455 WebSocket message from reader (handles TCP segmentation & fragmented frames).
    Automatically responds to Ping (0x9) with unmasked Pong (0xA) on writer.
    Returns:
      - None if connection closed, frame too large, or protocol error
      - "" (empty string) for handled standalone control/binary frames
      - string payload for complete text messages
    """
    fragments: list[bytes] = []
    total_len = 0
    expecting_continuation = False
    skipping_binary = False

    try:
        while True:
            header = await reader.readexactly(2)
            b1, b2 = header[0], header[1]
            fin = (b1 & 0x80) != 0
            opcode = b1 & 0x0F
            masked = (b2 & 0x80) != 0
            length = b2 & 0x7F

            if length == 126:
                length = struct.unpack("!H", await reader.readexactly(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", await reader.readexactly(8))[0]

            if length > MAX_WS_FRAME_SIZE:
                return None

            # Control frames (0x8..0xF) must not be fragmented and payload <= 125
            if opcode >= 0x8 and (not fin or length > 125):
                return None

            mask = await reader.readexactly(4) if masked else None
            data = await reader.readexactly(length) if length > 0 else b""

            if masked and mask:
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))

            # Close frame (0x8)
            if opcode == 0x8:
                return None

            # Ping frame (0x9) -> Send unmasked Pong (0xA) immediately
            if opcode == 0x9:
                pong_frame = bytearray([0x8A, len(data)]) + data
                writer.write(pong_frame)
                await writer.drain()
                if not expecting_continuation and not skipping_binary:
                    return ""
                continue

            # Pong frame (0xA)
            if opcode == 0xA:
                if not expecting_continuation and not skipping_binary:
                    return ""
                continue

            if opcode == 0x1:  # Text frame
                if expecting_continuation or skipping_binary:
                    return None
                if fin:
                    return data.decode("utf-8", errors="ignore")
                fragments.append(data)
                total_len += len(data)
                expecting_continuation = True
                continue

            if opcode == 0x2:  # Binary frame (ignored safely, including fragmented frames)
                if expecting_continuation or skipping_binary:
                    return None
                if fin:
                    return ""
                total_len = len(data)
                skipping_binary = True
                continue

            if opcode == 0x0:  # Continuation frame
                if not expecting_continuation and not skipping_binary:
                    return None
                total_len += len(data)
                if total_len > MAX_WS_FRAME_SIZE:
                    return None
                if skipping_binary:
                    if fin:
                        return ""
                    continue
                fragments.append(data)
                if fin:
                    return b"".join(fragments).decode("utf-8", errors="ignore")
                continue

            # Unknown opcode
            return None
    except (asyncio.IncompleteReadError, ConnectionError, OSError, struct.error, ValueError):
        return None

async def send_response(writer, is_websocket: bool, payload: dict):
    msg = json.dumps(payload, ensure_ascii=False)
    if is_websocket:
        await ws_send_frame(writer, msg)
    else:
        writer.write((msg + "\n").encode("utf-8"))
        await writer.drain()

async def process_message(
    msg_str: str,
    writer,
    is_websocket: bool,
    is_authenticated: bool,
    config: dict,
    peer_ip: str = "",
    conn_token: object | None = None,
) -> tuple[bool, bool]:
    """
    Process incoming JSON command.
    Returns (new_authentication_status, should_close_connection).
    """
    if not msg_str or not msg_str.startswith("{"):
        return is_authenticated, False

    try:
        data = json.loads(msg_str)
    except Exception:
        return is_authenticated, False

    if not isinstance(data, dict):
        return is_authenticated, False

    msg_type = data.get("type")

    # Handle Ping / Pong with round-trip latency support
    if msg_type == "ping":
        resp = {"type": "pong", "time": time.time()}
        if "t" in data:
            resp["t"] = data["t"]
        await send_response(writer, is_websocket, resp)
        return is_authenticated, False
    elif msg_type == "get_status":
        await send_response(writer, is_websocket, {
            "type": "status",
            "version": "1.0.20",
            "authenticated": is_authenticated,
            "access_control_mode": config.get("access_control_mode", "whitelist")
        })
        return is_authenticated, False

    # Authentication Handshake (Required for wireless; pre-trusted USB/Bluetooth never downgraded)
    if msg_type == "auth":
        if is_authenticated:
            await send_response(writer, is_websocket, {"type": "auth_result", "status": "ok", "message": "Authenticated"})
            return True, False

        if _is_auth_locked(peer_ip):
            await send_response(writer, is_websocket, {
                "type": "auth_result",
                "status": "fail",
                "message": "Too many failed attempts. Try again later.",
            })
            return False, True

        raw_token = data.get("token", "")
        token = raw_token.strip() if isinstance(raw_token, str) else ""
        expected = str(config.get("auth_token", "")).strip()
        if expected and token and hmac.compare_digest(token.encode("utf-8"), expected.encode("utf-8")):
            _clear_auth_failures(peer_ip)
            print("[SilentInput] Authentication SUCCESS via auth token")
            await send_response(writer, is_websocket, {"type": "auth_result", "status": "ok", "message": "Authenticated"})
            return True, False
        else:
            locked_now = _record_auth_failure(peer_ip)
            if locked_now:
                print(f"[SilentInput] Authentication FAILED from {peer_ip or 'unknown'} (rate-limited for {int(AUTH_LOCKOUT_SECONDS)}s)")
            else:
                print(f"[SilentInput] Authentication FAILED from {peer_ip or 'unknown'}")
            await send_response(writer, is_websocket, {"type": "auth_result", "status": "fail", "message": "Invalid token"})
            return False, locked_now

    # Guard: Require auth if not yet authenticated
    if not is_authenticated:
        print(f"[SilentInput] Blocked unauthenticated command '{msg_type}'")
        await send_response(writer, is_websocket, {
            "type": "auth_required",
            "status": "unauthorized",
            "message": "Authentication required. Please send token."
        })
        return False, False

    # Execute Commands
    if msg_type in ("paste", "text", "type"):
        content = data.get("content", "")
        if not isinstance(content, str):
            return is_authenticated, False
        enter_after = bool(data.get("enter", False))
        mode = data.get("mode", "terminal")
        paste_mode = data.get("paste_mode", mode)

        async with _get_paste_lock():
            if mode == "keys" or paste_mode == "keys":
                print(f"[SilentInput] Direct typing {len(content)} chars via virtual hardware keyboard")
                await type_text(content)
                if enter_after:
                    await asyncio.sleep(0.015)
                    await emit_key_combination("enter")
            else:
                # Clipboard injection: Completely bypasses Chinese/Pinyin IME!
                print(f"[SilentInput] Injecting {len(content)} chars via clipboard (mode: {paste_mode}, enter: {enter_after})")
                if not await copy_to_wayland_clipboard(content):
                    print("[SilentInput] Aborting paste shortcut because clipboard write failed.", file=sys.stderr)
                    return is_authenticated, False
                await asyncio.sleep(0.06)
                if paste_mode == "gui":
                    await emit_key_combination("ctrl+v")
                else:
                    # Default / terminal: Ctrl+Shift+V (Universal Linux terminal paste)
                    await emit_key_combination("ctrl+shift+v")
                if enter_after:
                    await asyncio.sleep(0.04)
                    await emit_key_combination("enter")
    elif msg_type == "key":
        key = data.get("key", "")
        if isinstance(key, str) and key:
            print(f"[SilentInput] Key event: {key}")
            await emit_key_combination(key)
    elif msg_type == "mouse":
        if ensure_uinput_device() and evdev is not None:
            action = data.get("action", "")
            holder_token = conn_token if conn_token is not None else id(writer)
            if action == "click_left":
                if _emit_uinput_events([(evdev.ecodes.EV_KEY, evdev.ecodes.BTN_LEFT, 1)]):
                    await asyncio.sleep(0.02)
                    _emit_uinput_events([(evdev.ecodes.EV_KEY, evdev.ecodes.BTN_LEFT, 0)])
                _track_held_button(evdev.ecodes.BTN_LEFT, False, holder_token)
            elif action == "click_right":
                if _emit_uinput_events([(evdev.ecodes.EV_KEY, evdev.ecodes.BTN_RIGHT, 1)]):
                    await asyncio.sleep(0.02)
                    _emit_uinput_events([(evdev.ecodes.EV_KEY, evdev.ecodes.BTN_RIGHT, 0)])
                _track_held_button(evdev.ecodes.BTN_RIGHT, False, holder_token)
            elif action == "down_left":
                if _emit_uinput_events([(evdev.ecodes.EV_KEY, evdev.ecodes.BTN_LEFT, 1)]):
                    _track_held_button(evdev.ecodes.BTN_LEFT, True, holder_token)
            elif action == "up_left":
                _emit_uinput_events([(evdev.ecodes.EV_KEY, evdev.ecodes.BTN_LEFT, 0)])
                _track_held_button(evdev.ecodes.BTN_LEFT, False, holder_token)
            elif action == "down_right":
                if _emit_uinput_events([(evdev.ecodes.EV_KEY, evdev.ecodes.BTN_RIGHT, 1)]):
                    _track_held_button(evdev.ecodes.BTN_RIGHT, True, holder_token)
            elif action == "up_right":
                _emit_uinput_events([(evdev.ecodes.EV_KEY, evdev.ecodes.BTN_RIGHT, 0)])
                _track_held_button(evdev.ecodes.BTN_RIGHT, False, holder_token)
            else:
                events = []
                button_updates: list[tuple[int, bool]] = []
                vx = _safe_round_int(data.get("dx", 0))
                vy = _safe_round_int(data.get("dy", 0))
                vw = _safe_round_int(data.get("wheel", 0))
                if vx != 0:
                    events.append((evdev.ecodes.EV_REL, evdev.ecodes.REL_X, vx))
                if vy != 0:
                    events.append((evdev.ecodes.EV_REL, evdev.ecodes.REL_Y, vy))
                if vw != 0:
                    events.append((evdev.ecodes.EV_REL, evdev.ecodes.REL_WHEEL, vw))
                if "btn_left" in data:
                    pressed = 1 if data["btn_left"] else 0
                    events.append((evdev.ecodes.EV_KEY, evdev.ecodes.BTN_LEFT, pressed))
                    button_updates.append((evdev.ecodes.BTN_LEFT, bool(pressed)))
                if "btn_right" in data:
                    pressed = 1 if data["btn_right"] else 0
                    events.append((evdev.ecodes.EV_KEY, evdev.ecodes.BTN_RIGHT, pressed))
                    button_updates.append((evdev.ecodes.BTN_RIGHT, bool(pressed)))
                if events:
                    emitted = _emit_uinput_events(events)
                    for btn_code, is_pressed in button_updates:
                        if is_pressed:
                            if emitted:
                                _track_held_button(btn_code, True, holder_token)
                        else:
                            _track_held_button(btn_code, False, holder_token)

    return is_authenticated, False

def _track_held_button(button_code: int, pressed: bool, holder_token: object):
    if pressed:
        _held_mouse_buttons.setdefault(button_code, set()).add(holder_token)
        return
    holders = _held_mouse_buttons.get(button_code)
    if holders is not None:
        holders.discard(holder_token)
        if not holders:
            _held_mouse_buttons.pop(button_code, None)

def _release_held_mouse_buttons(conn_token: object | None = None):
    """Ensure mouse buttons held by this connection (or all if conn_token is None) are released."""
    if not _held_mouse_buttons:
        return

    if conn_token is None:
        buttons_to_release = list(_held_mouse_buttons.keys())
        _held_mouse_buttons.clear()
    else:
        buttons_to_release = []
        for btn_code, holders in list(_held_mouse_buttons.items()):
            if conn_token in holders:
                holders.discard(conn_token)
                if not holders:
                    _held_mouse_buttons.pop(btn_code, None)
                    buttons_to_release.append(btn_code)

    if buttons_to_release and evdev is not None and uinput_device is not None:
        events = [(evdev.ecodes.EV_KEY, btn, 0) for btn in buttons_to_release]
        _emit_uinput_events(events)

async def handle_client(reader, writer):
    global _active_ws_connections
    peer = writer.get_extra_info("peername")
    peer_ip = peer[0] if peer else ""

    if _active_ws_connections >= MAX_WS_CONNECTIONS:
        print(f"[SilentInput] Access REJECTED for {peer}: too many active connections.")
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass
        return

    _active_ws_connections += 1
    conn_token = object()
    handshake_started = False
    try:
        _enable_tcp_keepalive(writer)
        config = await asyncio.to_thread(silentinput_config.load_config)

        # 1. Custom Access Control Check (Whitelist / Blacklist)
        if not silentinput_config.is_ip_allowed(peer_ip, config):
            print(f"[SilentInput] Access REJECTED for {peer}: blocked by custom IP policy.")
            return

        is_localhost = peer_ip in ("127.0.0.1", "::1", "localhost")

        if not is_localhost and _is_auth_locked(peer_ip):
            print(f"[SilentInput] Access REJECTED for {peer}: temporarily rate-limited.")
            return

        # 2. Determine initial auth state
        if is_localhost and config.get("allow_usb_unauthenticated", True):
            authenticated = True
            print(f"[SilentInput] New connection from {peer} (Local/USB: Auto-authenticated)")
        else:
            authenticated = False
            print(f"[SilentInput] New connection from {peer} (Wireless: Requires auth token)")

        try:
            first_line = await asyncio.wait_for(reader.readline(), timeout=10.0)
        except Exception:
            first_line = b""

        if not first_line:
            return

        first_str = first_line.decode("utf-8", errors="ignore")
        if not first_str.startswith("GET "):
            # Reject non-WebSocket / HTTP POST / Raw TCP requests
            try:
                writer.write(b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
                await writer.drain()
            except Exception:
                pass
            return

        ok = await ws_handshake(reader, writer, first_str)
        if not ok:
            return

        handshake_started = True
        auth_deadline = time.monotonic() + 15.0
        while True:
            try:
                if not authenticated:
                    remaining = auth_deadline - time.monotonic()
                    if remaining <= 0:
                        print(f"[SilentInput] Unauthenticated handshake/auth timeout for {peer}")
                        break
                    try:
                        msg_str = await asyncio.wait_for(ws_recv_frame(reader, writer), timeout=remaining)
                    except asyncio.TimeoutError:
                        print(f"[SilentInput] Unauthenticated handshake/auth timeout for {peer}")
                        break
                else:
                    try:
                        msg_str = await asyncio.wait_for(
                            ws_recv_frame(reader, writer), timeout=WS_IDLE_TIMEOUT_SECONDS
                        )
                    except asyncio.TimeoutError:
                        print(f"[SilentInput] Authenticated WS idle timeout for {peer}")
                        break
                if msg_str is None:
                    break
                if not msg_str:
                    continue
                authenticated, should_close = await process_message(
                    msg_str, writer, True, authenticated, config, peer_ip=peer_ip, conn_token=conn_token
                )
                if should_close:
                    break
            except Exception as e:
                print(f"[SilentInput] WS stream error: {e}")
                break
    finally:
        _active_ws_connections = max(0, _active_ws_connections - 1)
        _release_held_mouse_buttons(conn_token)
        if handshake_started:
            print(f"[SilentInput] Connection closed for {peer}")
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass

async def maintain_adb_reverse(port: int):
    """Periodically ensure adb reverse tcp:PORT tcp:PORT is active when phone is connected."""
    while True:
        try:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(None, _check_and_reverse, port)
        except Exception:
            pass
        await asyncio.sleep(3)

def _check_and_reverse(port: int):
    try:
        res = subprocess.run(["adb", "devices"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=2)
        lines = [l.strip() for l in res.stdout.splitlines() if l.strip() and not l.startswith("List of")]
        connected_devices = [l for l in lines if "\tdevice" in l]
        if connected_devices:
            rev = subprocess.run(["adb", "reverse", "--list"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=2)
            if f"tcp:{port}" not in rev.stdout:
                subprocess.run(["adb", "reverse", f"tcp:{port}", f"tcp:{port}"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=2)
                print(f"[SilentInput] Auto-configured adb reverse tcp:{port} for USB device.")
    except Exception:
        pass

# Bluetooth RFCOMM Companion Support
SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"
PROFILE_PATH = "/org/silentinput/btprofile"

def _is_bt_address_paired(mac_addr: str) -> bool:
    """Verify via bluetoothctl that a Bluetooth MAC address is OS-paired."""
    if not mac_addr:
        return False
    try:
        res = subprocess.run(
            ["bluetoothctl", "info", mac_addr],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=2,
        )
        for line in res.stdout.splitlines():
            if line.strip().lower().startswith("paired:"):
                return "yes" in line.lower()
    except Exception:
        pass
    return False

async def handle_bt_client(reader, writer, peer_name: str):
    conn_token = object()
    connected_logged = False
    try:
        config = await asyncio.to_thread(silentinput_config.load_config)
        print(f"[SilentInput] Bluetooth client connected: {peer_name}")
        connected_logged = True
        # Bluetooth connections are authenticated via BlueZ OS-level device pairing check
        authenticated = True

        while True:
            try:
                try:
                    line = await asyncio.wait_for(reader.readline(), timeout=BT_IDLE_TIMEOUT_SECONDS)
                except asyncio.TimeoutError:
                    print(f"[SilentInput] Bluetooth idle timeout for {peer_name}")
                    break
                if not line:
                    break
                msg_str = line.decode("utf-8", errors="ignore").strip()
                if msg_str:
                    authenticated, should_close = await process_message(
                        msg_str,
                        writer,
                        is_websocket=False,
                        is_authenticated=authenticated,
                        config=config,
                        peer_ip=peer_name,
                        conn_token=conn_token,
                    )
                    if should_close:
                        break
            except Exception as e:
                print(f"[SilentInput] Bluetooth stream error: {e}")
                break
    finally:
        _release_held_mouse_buttons(conn_token)
        if connected_logged:
            print(f"[SilentInput] Bluetooth connection closed for {peer_name}")
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass

_bt_dbus_refs = []

def setup_bluetooth_server(loop):
    """
    Sets up Bluetooth RFCOMM companion server.
    Uses BlueZ ProfileManager1 via D-Bus (with Paired check and wake/restart recovery),
    falling back to direct AF_BLUETOOTH socket only if D-Bus registration fails.
    """
    dbus_registered = False

    # 1. Try BlueZ D-Bus Profile Registration
    try:
        import dbus
        import dbus.service
        import dbus.mainloop.glib
        from gi.repository import GLib

        dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
        bus = dbus.SystemBus()

        class SilentInputProfile(dbus.service.Object):
            @dbus.service.method('org.bluez.Profile1', in_signature='oha{sv}', out_signature='')
            def NewConnection(self, path, fd, properties):
                print(f"[SilentInput] BlueZ Profile NewConnection from {path}")
                sock_fd = -1
                sock = None
                try:
                    sock_fd = fd.take()
                    # Verify peer device is OS-level paired in BlueZ
                    try:
                        props_iface = dbus.Interface(
                            bus.get_object('org.bluez', path),
                            'org.freedesktop.DBus.Properties',
                        )
                        is_paired = bool(props_iface.Get('org.bluez.Device1', 'Paired'))
                    except Exception as prop_err:
                        print(f"[SilentInput] Warning: could not verify Paired state for {path}: {prop_err}")
                        is_paired = False

                    if not is_paired:
                        print(f"[SilentInput] Rejected unpaired Bluetooth device: {path}")
                        os.close(sock_fd)
                        return

                    # Directly adopt sock_fd without dup() to avoid FD leaks
                    sock = socket.socket(fileno=sock_fd)
                    sock_fd = -1  # Ownership transferred to socket object
                    sock.setblocking(False)
                    asyncio.run_coroutine_threadsafe(self._accept(sock, str(path)), loop)
                except Exception as ex:
                    print(f"[SilentInput] Error accepting Bluetooth FD: {ex}")
                    if sock is not None:
                        try:
                            sock.close()
                        except Exception:
                            pass
                    elif sock_fd >= 0:
                        try:
                            os.close(sock_fd)
                        except Exception:
                            pass

            async def _accept(self, sock, peer):
                try:
                    reader, writer = await asyncio.open_connection(sock=sock, limit=MAX_WS_FRAME_SIZE)
                    await handle_bt_client(reader, writer, f"Bluetooth({peer})")
                except Exception as e:
                    print(f"[SilentInput] Bluetooth connection setup error: {e}")
                    try:
                        sock.close()
                    except Exception:
                        pass

            @dbus.service.method('org.bluez.Profile1', in_signature='', out_signature='')
            def Release(self):
                print("[SilentInput] BlueZ Profile released")

        profile = SilentInputProfile(bus, PROFILE_PATH)
        opts = {
            'Name': 'SilentInput Bluetooth',
            'Role': 'server',
            'Channel': dbus.UInt16(1),
            'Service': SPP_UUID,
            'AutoConnect': True,
        }

        profile_state = {"registered": False, "retries": 0}

        def register_profile(reason: str = "startup") -> bool:
            try:
                mgr = dbus.Interface(bus.get_object('org.bluez', '/org/bluez'), 'org.bluez.ProfileManager1')
                mgr.RegisterProfile(PROFILE_PATH, SPP_UUID, opts)
                profile_state["registered"] = True
                print(f"[SilentInput] Registered Bluetooth SPP profile with BlueZ ({reason})")
                return True
            except dbus.exceptions.DBusException as de:
                err_name = de.get_dbus_name() or ""
                if "AlreadyExists" in err_name:
                    profile_state["registered"] = True
                    return True
                print(f"[SilentInput] BlueZ RegisterProfile ({reason}) failed: {de}")
            except Exception as re_err:
                print(f"[SilentInput] BlueZ RegisterProfile ({reason}) error: {re_err}")
            return False

        def _retry_startup_register():
            if profile_state["registered"]:
                return False
            profile_state["retries"] += 1
            if register_profile(f"boot-retry-{profile_state['retries']}"):
                return False
            return profile_state["retries"] < 20

        def _on_bluez_owner_changed(name, old_owner, new_owner):
            if new_owner:
                profile_state["registered"] = False
                GLib.timeout_add(500, lambda: (register_profile("bluez-restarted"), False)[1])

        def _on_adapter_props_changed(interface, changed, invalidated):
            if interface == "org.bluez.Adapter1" and bool(changed.get("Powered")):
                GLib.timeout_add(300, lambda: (register_profile("adapter-powered-on"), False)[1])

        def _on_prepare_for_sleep(sleeping):
            if not sleeping:
                GLib.timeout_add(1500, lambda: (register_profile("system-resume"), False)[1])

        bus.add_signal_receiver(
            _on_bluez_owner_changed,
            signal_name='NameOwnerChanged',
            dbus_interface='org.freedesktop.DBus',
            arg0='org.bluez',
        )
        bus.add_signal_receiver(
            _on_adapter_props_changed,
            signal_name='PropertiesChanged',
            dbus_interface='org.freedesktop.DBus.Properties',
            arg0='org.bluez.Adapter1',
        )
        try:
            bus.add_signal_receiver(
                _on_prepare_for_sleep,
                signal_name='PrepareForSleep',
                dbus_interface='org.freedesktop.login1.Manager',
            )
        except Exception:
            pass

        if not register_profile("startup"):
            GLib.timeout_add_seconds(3, _retry_startup_register)
        dbus_registered = True
        _bt_dbus_refs.extend([bus, profile, register_profile])

        # Run GLib main loop in background thread for D-Bus profile & wake events
        glib_loop = GLib.MainLoop()
        t = threading.Thread(target=glib_loop.run, daemon=True)
        t.start()
    except Exception as e:
        print(f"[SilentInput] Info: BlueZ D-Bus profile registration skipped: {e}")

    # 2. Fallback direct AF_BLUETOOTH RFCOMM socket (only if BlueZ D-Bus profile is unavailable)
    if not dbus_registered and hasattr(socket, "AF_BLUETOOTH") and hasattr(socket, "BTPROTO_RFCOMM"):
        def rfcomm_listener():
            while True:
                server_sock = None
                try:
                    server_sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
                    server_sock.bind(("00:00:00:00:00:00", 1))
                    server_sock.listen(1)
                    print("[SilentInput] Direct RFCOMM fallback socket listening on Channel 1")
                    while True:
                        client_sock, addr = server_sock.accept()
                        peer_mac = addr[0] if isinstance(addr, tuple) and addr else ""
                        if not _is_bt_address_paired(peer_mac):
                            print(f"[SilentInput] Rejected unpaired direct RFCOMM device: {addr}")
                            try:
                                client_sock.close()
                            except Exception:
                                pass
                            continue
                        print(f"[SilentInput] Direct RFCOMM connection from {addr}")
                        client_sock.setblocking(False)
                        asyncio.run_coroutine_threadsafe(_accept_direct(client_sock, addr), loop)
                except Exception as e:
                    print(f"[SilentInput] Direct RFCOMM listener error: {e}")
                    if server_sock is not None:
                        try:
                            server_sock.close()
                        except Exception:
                            pass
                    time.sleep(3)

        async def _accept_direct(sock, addr):
            try:
                reader, writer = await asyncio.open_connection(sock=sock, limit=MAX_WS_FRAME_SIZE)
                await handle_bt_client(reader, writer, f"RFCOMM({addr})")
            except Exception as e:
                print(f"[SilentInput] RFCOMM direct connection setup error: {e}")
                try:
                    sock.close()
                except Exception:
                    pass

        t = threading.Thread(target=rfcomm_listener, daemon=True)
        t.start()

def cleanup_pid():
    silentinput_config.PID_FILE.unlink(missing_ok=True)

async def maintain_uinput_device():
    """Retry kernel uinput initialization until logind uaccess ACL settles after boot."""
    attempts = 0
    while True:
        if uinput_device is None and HAVE_UINPUT:
            init_uinput_device(log_failure=(attempts == 0))
            attempts += 1
        else:
            attempts = 0
        await asyncio.sleep(2 if (uinput_device is None and attempts < 30) else 15)

async def main():
    init_uinput_device()
    config = silentinput_config.load_config()
    port = config.get("port", 53318)
    bind_addrs = silentinput_config.get_bind_addresses(config)

    # Save PID
    silentinput_config.ensure_config_dir()
    silentinput_config.PID_FILE.write_text(str(os.getpid()))

    # Start kernel uinput recovery & ADB reverse loops
    uinput_task = asyncio.create_task(maintain_uinput_device())
    adb_task = asyncio.create_task(maintain_adb_reverse(port))

    # Start Bluetooth companion server
    setup_bluetooth_server(asyncio.get_running_loop())

    host_ips = silentinput_config.get_all_host_ips()
    ip_strs = host_ips

    enable_tls = config.get("enable_tls", True)
    ssl_context = None
    if enable_tls:
        cert_path, key_path = silentinput_config.ensure_certificates()
        if cert_path.exists() and key_path.exists():
            try:
                ssl_context = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
                ssl_context.load_cert_chain(certfile=str(cert_path), keyfile=str(key_path))
                print(f"[SilentInput] TLS Traffic Encryption (WSS) activated")
            except Exception as e:
                print(f"[SilentInput] Warning: Failed to load TLS certs: {e}")

    print(f"[SilentInput] Starting daemon version 1.0.20 (PID: {os.getpid()})")
    print(f"[SilentInput] Port: {port} | Listening on: {bind_addrs}")
    print(f"[SilentInput] Available Host IPs: {', '.join(ip_strs) if ip_strs else 'None'}")
    print(f"[SilentInput] Access Control Mode: {config.get('access_control_mode', 'whitelist')}")
    print(f"[SilentInput] Wireless Authentication: Always Enabled (Token required)")
    print(f"[SilentInput] Traffic Encryption: {'TLS Enabled (WSS)' if ssl_context else 'Plaintext (WS)'}")

    servers = []
    for addr in bind_addrs:
        try:
            srv = await asyncio.start_server(handle_client, addr, port, ssl=ssl_context)
            servers.append(srv)
            proto = "WSS (TLS)" if ssl_context else "WS"
            print(f"[SilentInput] Server listening on {addr}:{port} ({proto})")
        except Exception as e:
            print(f"[SilentInput] Warning: Failed to bind to {addr}:{port}: {e}", file=sys.stderr)

    if not servers:
        print("[SilentInput] Error: Could not bind to any address!", file=sys.stderr)
        uinput_task.cancel()
        adb_task.cancel()
        await asyncio.gather(uinput_task, adb_task, return_exceptions=True)
        cleanup_pid()
        return

    # Handle termination signals cleanly without raising SystemExit inside a task
    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop_event.set)
        except NotImplementedError:
            pass

    serve_tasks = [asyncio.create_task(s.serve_forever()) for s in servers]
    stop_task = asyncio.create_task(stop_event.wait())

    try:
        await asyncio.wait([stop_task, *serve_tasks], return_when=asyncio.FIRST_COMPLETED)
        print("\n[SilentInput] Shutting down daemon...")
    finally:
        stop_task.cancel()
        uinput_task.cancel()
        adb_task.cancel()
        for t in serve_tasks:
            t.cancel()
        for s in servers:
            s.close()
        await asyncio.gather(stop_task, uinput_task, adb_task, *serve_tasks, return_exceptions=True)
        await asyncio.gather(*(s.wait_closed() for s in servers), return_exceptions=True)
        close_uinput_device()
        cleanup_pid()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        cleanup_pid()
        print("\n[SilentInput] Daemon stopped.")
