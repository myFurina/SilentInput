#!/usr/bin/env python3
"""
SilentInput Configuration and Security Module
Manages configuration file, authentication token, access control (whitelist/blacklist), and process lifecycle.
"""

import os
import json
import secrets
import subprocess
import signal
import sys
import ipaddress
import fnmatch
import tempfile
import threading
from pathlib import Path

_CONFIG_LOCK = threading.RLock()

CONFIG_DIR = Path.home() / ".config" / "silentinput"
CONFIG_FILE = CONFIG_DIR / "config.json"
PID_FILE = CONFIG_DIR / "silentinput.pid"
LOG_FILE = CONFIG_DIR / "silentinput.log"
CERT_FILE = CONFIG_DIR / "cert.pem"
KEY_FILE = CONFIG_DIR / "key.pem"
SCRIPT_DIR = Path(__file__).resolve().parent
DAEMON_SCRIPT = SCRIPT_DIR / "silentinput-daemon.py"
SYSTEMD_SERVICE_NAME = "silentinput.service"
SYSTEMD_SERVICE_FILE = Path.home() / ".config" / "systemd" / "user" / SYSTEMD_SERVICE_NAME

import time

DEFAULT_CONFIG = {
    "port": 53318,
    "auth_token": "",
    "token_created_at": 0.0,
    "token_expire_mode": "never",  # "never" (不过期), "24h" (24小时轮换), "7d" (7天轮换), "1h" (1小时临时)
    "enable_tls": True,  # 流量传输层加密 (TLS / WSS)，防止局域网抓包截获按键
    "allow_usb_unauthenticated": True,
    "access_control_mode": "whitelist", # "whitelist", "blacklist", "all", "localhost_only"
    "whitelist": ["127.0.0.1", "10.*", "192.168.*", "100.*", "172.16.*"],
    "blacklist": [],
    "gui_language": "system",  # "system", "zh", "en"
}

def ensure_config_dir():
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(CONFIG_DIR, 0o700)
    except OSError:
        pass

def get_token_expire_seconds(mode: str) -> int:
    mapping = {
        "1h": 3600,
        "24h": 86400,
        "7d": 604800,
        "never": 0
    }
    return mapping.get(mode, 0)

def get_token_status_text(config: dict, is_zh: bool = True) -> str:
    mode = config.get("token_expire_mode", "never")
    if mode == "never":
        return "永不过期" if is_zh else "Never expires"

    expire_sec = get_token_expire_seconds(mode)
    created_at = float(config.get("token_created_at", 0.0))
    if created_at <= 0:
        return mode

    elapsed = time.time() - created_at
    remaining = expire_sec - elapsed
    if remaining <= 0:
        return (
            f"{mode} (已到期，下次连接时轮换)"
            if is_zh
            else f"{mode} (expired, rotates on next connect)"
        )

    hours = int(remaining // 3600)
    minutes = int((remaining % 3600) // 60)
    if hours > 0:
        return (
            f"{mode} (剩余 {hours}小时{minutes}分)"
            if is_zh
            else f"{mode} ({hours}h {minutes}m left)"
        )
    return (
        f"{mode} (剩余 {minutes}分钟)"
        if is_zh
        else f"{mode} ({minutes}m left)"
    )

def check_and_rotate_token(config: dict) -> tuple[bool, dict]:
    """Check if token is expired based on token_expire_mode, rotate if needed"""
    mode = config.get("token_expire_mode", "never")
    expire_sec = get_token_expire_seconds(mode)
    now = time.time()

    need_new = False
    if not config.get("auth_token"):
        need_new = True
    elif expire_sec > 0:
        created_at = float(config.get("token_created_at", 0.0))
        if created_at <= 0 or (now - created_at) >= expire_sec:
            need_new = True

    if need_new:
        config["auth_token"] = secrets.token_hex(4)
        config["token_created_at"] = now
        save_config(config)
        return True, config

    return False, config

def _restrict_umask():
    os.umask(0o077)

def ensure_certificates() -> tuple[Path, Path]:
    """Ensure self-signed TLS certificate and private key exist for WSS traffic encryption"""
    ensure_config_dir()
    if not CERT_FILE.exists() or not KEY_FILE.exists():
        try:
            subprocess.run([
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-keyout", str(KEY_FILE),
                "-out", str(CERT_FILE),
                "-days", "3650",
                "-subj", "/CN=SilentInput"
            ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, preexec_fn=_restrict_umask)
            KEY_FILE.chmod(0o600)
            print(f"[SilentInput] Generated new TLS certificate: {CERT_FILE}")
        except Exception as e:
            print(f"[SilentInput] Warning: Failed to generate TLS certificate: {e}")
    elif KEY_FILE.exists():
        try:
            KEY_FILE.chmod(0o600)
        except OSError:
            pass
    return CERT_FILE, KEY_FILE

def load_config() -> dict:
    with _CONFIG_LOCK:
        ensure_config_dir()
        config = DEFAULT_CONFIG.copy()
        if CONFIG_FILE.exists():
            try:
                os.chmod(CONFIG_FILE, 0o600)
            except OSError:
                pass
            try:
                with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    config.update(data)
            except Exception as e:
                print(f"[SilentInput Config] Warning: Failed to read {CONFIG_FILE}: {e}")

        # Ensure token validity and expiration rotation
        _, config = check_and_rotate_token(config)
        return config

def save_config(config: dict):
    with _CONFIG_LOCK:
        ensure_config_dir()
        tmp_path = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                dir=CONFIG_DIR,
                prefix=".config.",
                suffix=".tmp",
                delete=False,
            ) as f:
                tmp_path = Path(f.name)
                json.dump(config, f, indent=2, ensure_ascii=False)
                f.flush()
            os.chmod(tmp_path, 0o600)
            os.replace(tmp_path, CONFIG_FILE)
        except Exception as e:
            print(f"[SilentInput Config] Error saving {CONFIG_FILE}: {e}")
            if tmp_path is not None:
                try:
                    tmp_path.unlink(missing_ok=True)
                except OSError:
                    pass

def regenerate_token() -> str:
    config = load_config()
    new_token = secrets.token_hex(4)
    config["auth_token"] = new_token
    config["token_created_at"] = time.time()
    save_config(config)
    return new_token

def set_tls_enabled(enable: bool) -> bool:
    config = load_config()
    config["enable_tls"] = bool(enable)
    save_config(config)
    return config["enable_tls"]


def get_all_host_ips() -> list[str]:
    """
    Returns list of host IPv4 addresses:
    ["10.17.178.197", "100.86.221.32"]
    """
    results = []
    try:
        output = subprocess.check_output(
            ["ip", "-4", "-o", "addr", "show"],
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=1.0
        )
        for line in output.splitlines():
            parts = line.split()
            if len(parts) >= 4:
                ip_cidr = parts[3]
                ip = ip_cidr.split("/")[0]
                if ip == "127.0.0.1":
                    continue
                if ip not in results:
                    results.append(ip)
    except Exception:
        pass
    return results

def get_connected_usb_devices() -> list[str]:
    """Check ADB connected devices for USB tethering status"""
    try:
        res = subprocess.run(["adb", "devices"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=2)
        lines = [l.strip() for l in res.stdout.splitlines() if l.strip() and not l.startswith("List of")]
        return [l.split("\t")[0] for l in lines if "\tdevice" in l]
    except Exception:
        return []

def match_ip_pattern(ip_str: str, pattern: str) -> bool:
    pattern = pattern.strip()
    if not pattern:
        return False
    if pattern in ("*", "all", "0.0.0.0/0"):
        return True
    if "/" in pattern:
        try:
            return ipaddress.ip_address(ip_str) in ipaddress.ip_network(pattern, strict=False)
        except Exception:
            pass
    return fnmatch.fnmatch(ip_str, pattern)

def is_ip_allowed(client_ip: str, config: dict) -> bool:
    """Check if incoming client IP is permitted by mutually exclusive access policy"""
    if not client_ip:
        return False
    # Localhost / USB ADB reverse is always permitted
    if client_ip in ("127.0.0.1", "::1", "localhost"):
        return True

    mode = config.get("access_control_mode", "whitelist")
    if mode == "localhost_only":
        return False
    elif mode == "all":
        return True
    elif mode == "whitelist":
        whitelist = config.get("whitelist", [])
        if not whitelist:
            return False
        for w_pat in whitelist:
            if match_ip_pattern(client_ip, w_pat):
                return True
        return False
    elif mode == "blacklist":
        blacklist = config.get("blacklist", [])
        for b_pat in blacklist:
            if match_ip_pattern(client_ip, b_pat):
                return False
        return True
    return True

def get_bind_addresses(config: dict) -> list[str]:
    """
    Returns list of IP addresses to bind to:
    - 'localhost_only': 127.0.0.1 only
    - other modes: 0.0.0.0 (filtering handled dynamically by is_ip_allowed)
    """
    mode = config.get("access_control_mode", "whitelist")
    if mode == "localhost_only":
        return ["127.0.0.1"]
    return ["0.0.0.0"]

def is_daemon_running() -> tuple[bool, int]:
    """Check if daemon is currently running, returns (running, pid)"""
    config = load_config()
    port = config.get("port", 53318)

    # 1. Check PID file if present
    if PID_FILE.exists():
        try:
            pid = int(PID_FILE.read_text().strip())
            if pid > 0 and pid != os.getpid():
                try:
                    os.kill(pid, 0)
                    cmdline_path = Path(f"/proc/{pid}/cmdline")
                    if cmdline_path.exists():
                        cmdline = cmdline_path.read_text(errors="ignore")
                        if "silentinput-daemon" in cmdline:
                            return True, pid
                    else:
                        return True, pid
                except OSError:
                    pass
        except Exception:
            pass

    # 2. Probe local port to detect running daemon across namespaces/containers
    try:
        import socket
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(0.5)
            s.connect(("127.0.0.1", port))
            return True, -1
    except Exception:
        pass

    PID_FILE.unlink(missing_ok=True)
    return False, 0

def _find_daemon_pids(proc_root: Path = Path("/proc")) -> list[int]:
    """Find running silentinput-daemon.py PIDs via /proc cmdline (without broad pkill -f)."""
    pids = []
    my_pid = os.getpid()
    if not proc_root.exists():
        return pids
    for entry in proc_root.iterdir():
        if not entry.name.isdigit():
            continue
        pid = int(entry.name)
        if pid <= 0 or pid == my_pid:
            continue
        try:
            raw = (entry / "cmdline").read_bytes()
            args = [a.decode("utf-8", errors="ignore") for a in raw.split(b"\x00") if a]
            if not args:
                continue
            first_bin = Path(args[0]).name.lower()
            if first_bin == "silentinput-daemon.py":
                pids.append(pid)
            elif "python" in first_bin:
                for a in args[1:]:
                    if a.startswith("-"):
                        continue
                    if Path(a).name == "silentinput-daemon.py":
                        pids.append(pid)
                    break
        except Exception:
            continue
    return pids

def is_system_package_install() -> bool:
    """Return True when pc-daemon files are installed under system prefixes (/usr or /opt)."""
    return str(SCRIPT_DIR).startswith(("/usr/", "/opt/"))


def get_python_executable() -> str:
    """Prefer user-local venv Python when installed via install.sh on immutable systems."""
    if not is_system_package_install():
        venv_py = Path.home() / ".local" / "share" / "silentinput" / "venv" / "bin" / "python3"
        if venv_py.exists():
            return str(venv_py)
    return sys.executable


def get_package_uninstall_command() -> str | None:
    """Detect package manager command if silentinput was installed via apt/dnf/pacman."""
    try:
        dpkg = subprocess.run(
            ["dpkg-query", "-W", "-f=${Status}", "silentinput"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=2,
        )
        if dpkg.returncode == 0 and "installed" in dpkg.stdout:
            return "sudo apt remove silentinput"
    except Exception:
        pass
    try:
        rpm = subprocess.run(
            ["rpm", "-q", "silentinput"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=2,
        )
        if rpm.returncode == 0:
            return "sudo dnf remove silentinput"
    except Exception:
        pass
    try:
        pacman = subprocess.run(
            ["pacman", "-Q", "silentinput"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=2,
        )
        if pacman.returncode == 0:
            return "sudo pacman -R silentinput"
    except Exception:
        pass
    return None


def is_systemd_unit_installed() -> bool:
    candidates = [
        SYSTEMD_SERVICE_FILE,
        Path("/etc/systemd/user") / SYSTEMD_SERVICE_NAME,
        Path("/usr/lib/systemd/user") / SYSTEMD_SERVICE_NAME,
        Path("/lib/systemd/user") / SYSTEMD_SERVICE_NAME,
    ]
    return any(p.exists() for p in candidates)


def has_active_systemd() -> bool:
    """Return True if user systemd is active and silentinput.service unit is installed."""
    if not is_systemd_unit_installed():
        return False
    try:
        res = subprocess.run(
            ["systemctl", "--user", "status"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=2,
        )
        return res.returncode in (0, 3)
    except Exception:
        return False


def _user_wants_override_link() -> Path:
    return Path.home() / ".config" / "systemd" / "user" / "default.target.wants" / SYSTEMD_SERVICE_NAME


def _global_wants_link() -> Path:
    return Path("/etc/systemd/user/default.target.wants") / SYSTEMD_SERVICE_NAME


def _xdg_autostart_file() -> Path:
    return Path.home() / ".config" / "autostart" / "com.silentinput.daemon.desktop"


def is_autostart_enabled() -> bool:
    """Return True when SilentInput daemon is configured to start automatically on login."""
    if has_active_systemd():
        override_link = _user_wants_override_link()
        if override_link.is_symlink():
            try:
                if os.readlink(override_link) == "/dev/null":
                    return False
            except OSError:
                pass
        try:
            res = subprocess.run(
                ["systemctl", "--user", "is-enabled", SYSTEMD_SERVICE_NAME],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                timeout=2,
            )
            return res.returncode == 0 and res.stdout.strip() in (
                "enabled", "enabled-runtime", "static", "indirect"
            )
        except Exception:
            return False
    return _xdg_autostart_file().exists()


def set_autostart_enabled(enabled: bool, is_zh: bool = True) -> tuple[bool, str]:
    """Enable or disable automatic startup at login (via systemd --user or XDG autostart fallback)."""
    if has_active_systemd():
        override_link = _user_wants_override_link()
        if enabled:
            if override_link.is_symlink():
                try:
                    if os.readlink(override_link) == "/dev/null":
                        override_link.unlink(missing_ok=True)
                except OSError:
                    pass
            subprocess.run(
                ["systemctl", "--user", "unmask", SYSTEMD_SERVICE_NAME],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
            res = subprocess.run(
                ["systemctl", "--user", "enable", SYSTEMD_SERVICE_NAME],
                capture_output=True,
                text=True,
            )
            if res.returncode != 0 and not is_autostart_enabled():
                subprocess.run(["systemctl", "--user", "daemon-reload"], check=False)
                res = subprocess.run(
                    ["systemctl", "--user", "enable", SYSTEMD_SERVICE_NAME],
                    capture_output=True,
                    text=True,
                )
                if res.returncode != 0 and not is_autostart_enabled():
                    fallback_err = "启用开机启动失败" if is_zh else "Failed to enable autostart"
                    return False, (res.stderr or res.stdout or fallback_err).strip()
            subprocess.run(["systemctl", "--user", "daemon-reload"], check=False)
            return True, "已启用开机自动启动" if is_zh else "Autostart enabled"

        subprocess.run(
            ["systemctl", "--user", "disable", SYSTEMD_SERVICE_NAME],
            capture_output=True,
            text=True,
            check=False,
        )
        # If globally enabled under /etc/systemd/user/default.target.wants/,
        # shadow the .wants symlink with /dev/null so manual start still works.
        if _global_wants_link().exists() or is_autostart_enabled():
            try:
                override_link.parent.mkdir(parents=True, exist_ok=True)
                if override_link.exists() or override_link.is_symlink():
                    override_link.unlink(missing_ok=True)
                override_link.symlink_to("/dev/null")
            except OSError as e:
                return False, (f"关闭开机启动失败: {e}" if is_zh else f"Failed to disable autostart: {e}")
        subprocess.run(["systemctl", "--user", "daemon-reload"], check=False)
        return True, "已关闭开机自动启动" if is_zh else "Autostart disabled"

    autostart_file = _xdg_autostart_file()
    try:
        if enabled:
            autostart_file.parent.mkdir(parents=True, exist_ok=True)
            py_exec = get_python_executable()
            autostart_file.write_text(
                "[Desktop Entry]\n"
                "Type=Application\n"
                "Name=SilentInput Daemon\n"
                f'Exec="{py_exec}" -u "{DAEMON_SCRIPT}"\n'
                "X-GNOME-Autostart-enabled=true\n",
                encoding="utf-8",
            )
            return True, "已启用开机自动启动 (XDG)" if is_zh else "Autostart enabled (XDG)"
        autostart_file.unlink(missing_ok=True)
        return True, "已关闭开机自动启动" if is_zh else "Autostart disabled"
    except OSError as e:
        return False, (f"设置开机启动失败: {e}" if is_zh else f"Failed to configure autostart: {e}")

def start_daemon_process() -> tuple[bool, str]:
    """Start daemon in background if not already running"""
    running, pid = is_daemon_running()
    if running:
        return True, "Daemon is already running"

    ensure_config_dir()
    try:
        # Rotate log if over 2 MiB
        if LOG_FILE.exists() and LOG_FILE.stat().st_size > 2 * 1024 * 1024:
            backup_log = CONFIG_DIR / "silentinput.log.1"
            os.replace(LOG_FILE, backup_log)
    except OSError:
        pass

    try:
        log_fd = os.open(LOG_FILE, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(log_fd, "a", encoding="utf-8") as log_f:
            proc = subprocess.Popen(
                [get_python_executable(), "-u", str(DAEMON_SCRIPT)],
                stdout=log_f,
                stderr=subprocess.STDOUT,
                start_new_session=True
            )
        PID_FILE.write_text(str(proc.pid))
        return True, f"Daemon started successfully (PID: {proc.pid})"
    except Exception as e:
        return False, f"Failed to start daemon: {e}"

def stop_daemon_process() -> tuple[bool, str]:
    """Stop daemon process if running"""
    _, pid = is_daemon_running()
    target_pids = set(_find_daemon_pids())
    if pid > 0:
        target_pids.add(pid)

    try:
        for tpid in target_pids:
            try:
                os.kill(tpid, signal.SIGTERM)
            except OSError:
                pass
        for tpid in target_pids:
            for _ in range(15):
                try:
                    os.kill(tpid, 0)
                    time.sleep(0.1)
                except OSError:
                    break
            else:
                try:
                    os.kill(tpid, signal.SIGKILL)
                except OSError:
                    pass
        PID_FILE.unlink(missing_ok=True)
        return True, "Daemon stopped"
    except Exception as e:
        PID_FILE.unlink(missing_ok=True)
        return False, f"Failed to stop daemon: {e}"

def service_start() -> tuple[bool, str]:
    """Start daemon via systemd --user when installed, else direct background process."""
    if has_active_systemd():
        res = subprocess.run(["systemctl", "--user", "start", SYSTEMD_SERVICE_NAME], capture_output=True, text=True)
        if res.returncode == 0:
            return True, "SilentInput service started via systemd."
        return False, (res.stderr or res.stdout or "systemctl start failed").strip()
    return start_daemon_process()

def service_stop() -> tuple[bool, str]:
    """Stop daemon via systemd --user when installed, and clean up any orphaned process."""
    if has_active_systemd():
        subprocess.run(["systemctl", "--user", "stop", SYSTEMD_SERVICE_NAME], check=False)
    return stop_daemon_process()

def service_restart() -> tuple[bool, str]:
    """Restart daemon cleanly via systemd --user or direct process."""
    if has_active_systemd():
        res = subprocess.run(["systemctl", "--user", "restart", SYSTEMD_SERVICE_NAME], capture_output=True, text=True)
        if res.returncode == 0:
            return True, "SilentInput service restarted via systemd."
    stop_daemon_process()
    time.sleep(0.3)
    return start_daemon_process()

if __name__ == "__main__":
    cfg = load_config()
    print("Config:", cfg)
    print("Host IPs:", get_all_host_ips())
    print("Bind Addresses:", get_bind_addresses(cfg))
    running, pid = is_daemon_running()
    print(f"Daemon Running: {running} (PID: {pid})")
