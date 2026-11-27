use std::collections::BTreeSet;
use std::env;
use std::ffi::CStr;
use std::fs;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream};
use std::os::unix::fs::PermissionsExt;
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;

use crate::config::{
    ensure_config_dir_at, load_config_at, open_private_append_file, SilentInputConfig,
};
use crate::paths::{SilentInputPaths, SYSTEMD_SERVICE_NAME};

const MAX_ADB_FRAME_LEN: usize = 1024 * 1024;

struct IfAddrsGuard(*mut libc::ifaddrs);

impl Drop for IfAddrsGuard {
    fn drop(&mut self) {
        if !self.0.is_null() {
            // SAFETY: pointer was allocated by `libc::getifaddrs` and is freed once on drop.
            unsafe {
                libc::freeifaddrs(self.0);
            }
        }
    }
}

pub fn resolve_binary(bin_name: &str) -> PathBuf {
    if let Ok(exe) = env::current_exe() {
        if let Some(dir) = exe.parent() {
            let candidate = dir.join(bin_name);
            if candidate.exists() {
                return candidate;
            }
        }
    }
    for dir in ["/usr/local/bin", "/usr/bin"] {
        let candidate = Path::new(dir).join(bin_name);
        if candidate.exists() {
            return candidate;
        }
    }
    if let Some(home) = env::var_os("HOME") {
        let local_bin = PathBuf::from(home).join(".local").join("bin").join(bin_name);
        if local_bin.exists() {
            return local_bin;
        }
    }
    PathBuf::from(bin_name)
}

fn is_virtual_interface_name(name: &str) -> bool {
    const VIRTUAL_PREFIXES: &[&str] = &[
        "docker", "br-", "virbr", "veth", "cni", "flannel", "podman", "lxcbr", "vboxnet", "vmnet",
    ];
    let lower = name.to_ascii_lowercase();
    VIRTUAL_PREFIXES
        .iter()
        .any(|prefix| lower.starts_with(prefix))
}

/// Enumerate active non-loopback IPv4 interface addresses via `libc::getifaddrs` (zero subprocesses),
/// prioritizing non-virtual interfaces ahead of container/VM bridges.
pub fn get_all_host_ips() -> Vec<String> {
    let mut head: *mut libc::ifaddrs = std::ptr::null_mut();
    // SAFETY: `getifaddrs` initializes `head` on success (return value 0).
    if unsafe { libc::getifaddrs(&mut head) } != 0 || head.is_null() {
        return Vec::new();
    }
    let _guard = IfAddrsGuard(head);

    let mut preferred = Vec::new();
    let mut fallback = Vec::new();
    let mut cur = head;
    while !cur.is_null() {
        // SAFETY: `cur` walks a valid null-terminated linked list owned by `_guard`.
        let ifa = unsafe { &*cur };
        cur = ifa.ifa_next;

        if ifa.ifa_addr.is_null() || (ifa.ifa_flags & (libc::IFF_UP as u32)) == 0 {
            continue;
        }
        // SAFETY: `ifa_addr` is non-null; reading `sa_family` checks address family before casting.
        let family = unsafe { (*ifa.ifa_addr).sa_family };
        if i32::from(family) != libc::AF_INET {
            continue;
        }
        // SAFETY: `sa_family == AF_INET` guarantees `ifa_addr` points to a `sockaddr_in`.
        let sin = unsafe { &*ifa.ifa_addr.cast::<libc::sockaddr_in>() };
        let ipv4 = Ipv4Addr::from(u32::from_be(sin.sin_addr.s_addr));
        if ipv4.is_loopback() || ipv4.is_unspecified() || ipv4.is_link_local() {
            continue;
        }
        let ip_s = ipv4.to_string();
        let is_virtual = if !ifa.ifa_name.is_null() {
            // SAFETY: `ifa_name` is a valid C string for the lifetime of `_guard`.
            let name = unsafe { CStr::from_ptr(ifa.ifa_name) }.to_string_lossy();
            is_virtual_interface_name(&name)
        } else {
            false
        };
        let target = if is_virtual {
            &mut fallback
        } else {
            &mut preferred
        };
        if !target.contains(&ip_s) {
            target.push(ip_s);
        }
    }
    if !preferred.is_empty() {
        preferred
    } else {
        fallback
    }
}

/// Encode an ADB host/service command with its 4-hex-digit length prefix.
pub fn encode_adb_request(cmd: &str) -> String {
    format!("{:04x}{cmd}", cmd.len())
}

/// Parse an ADB `host:devices` / `adb devices` payload and return only online `device` serials.
pub fn parse_adb_online_serials(body: &str) -> Vec<String> {
    let mut devices = Vec::new();
    for line in body.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with("List of") {
            continue;
        }
        let mut parts = trimmed.splitn(2, char::is_whitespace);
        let Some(serial) = parts.next().map(str::trim).filter(|s| !s.is_empty()) else {
            continue;
        };
        let Some(state) = parts.next().map(str::trim) else {
            continue;
        };
        if state.split_whitespace().next() == Some("device") {
            let serial_s = serial.to_string();
            if !devices.contains(&serial_s) {
                devices.push(serial_s);
            }
        }
    }
    devices
}

/// Return true if `adb reverse --list` output contains an exact whitespace-separated `target_spec` token.
pub fn adb_reverse_list_has_spec(output: &str, target_spec: &str) -> bool {
    output
        .lines()
        .any(|line| line.split_whitespace().any(|tok| tok == target_spec))
}

/// Query an ADB server over TCP (`host:devices`) without ever spawning an `adb` child process.
pub fn query_adb_devices_at(addr: SocketAddr, timeout: Duration) -> Vec<String> {
    let Ok(mut stream) = TcpStream::connect_timeout(&addr, timeout) else {
        return Vec::new();
    };
    let _ = stream.set_read_timeout(Some(timeout));
    let _ = stream.set_write_timeout(Some(timeout));

    let req = encode_adb_request("host:devices");
    if stream.write_all(req.as_bytes()).is_err() || stream.flush().is_err() {
        return Vec::new();
    }

    let mut status = [0u8; 4];
    if stream.read_exact(&mut status).is_err() || &status != b"OKAY" {
        return Vec::new();
    }

    let mut len_hex = [0u8; 4];
    if stream.read_exact(&mut len_hex).is_err() {
        return Vec::new();
    }
    let Ok(len_str) = std::str::from_utf8(&len_hex) else {
        return Vec::new();
    };
    let Ok(payload_len) = usize::from_str_radix(len_str, 16) else {
        return Vec::new();
    };
    if payload_len == 0 || payload_len > MAX_ADB_FRAME_LEN {
        return Vec::new();
    }

    let mut body = vec![0u8; payload_len];
    if stream.read_exact(&mut body).is_err() {
        return Vec::new();
    }
    parse_adb_online_serials(&String::from_utf8_lossy(&body))
}

/// Resolved ADB server connection target from `ADB_SERVER_SOCKET` / `ANDROID_ADB_SERVER_PORT`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AdbEndpoint {
    LocalTcp(SocketAddr),
    UnsupportedSocket,
}

fn parse_nonzero_port(raw: &str) -> Option<u16> {
    let port: u16 = raw.trim().parse().ok()?;
    (port > 0).then_some(port)
}

/// Resolve ADB server endpoint from explicit `ANDROID_ADB_SERVER_PORT` and `ADB_SERVER_SOCKET` values.
pub fn resolve_adb_endpoint_from(
    android_port: Option<&str>,
    server_socket: Option<&str>,
) -> AdbEndpoint {
    if let Some(raw_socket) = server_socket.map(str::trim).filter(|s| !s.is_empty()) {
        let Some(rest) = raw_socket.strip_prefix("tcp:") else {
            return AdbEndpoint::UnsupportedSocket;
        };
        if let Some(port) = parse_nonzero_port(rest) {
            return AdbEndpoint::LocalTcp(SocketAddr::from((Ipv4Addr::LOCALHOST, port)));
        }
        if let Some(port_str) = rest
            .strip_prefix("localhost:")
            .or_else(|| rest.strip_prefix("127.0.0.1:"))
        {
            if let Some(port) = parse_nonzero_port(port_str) {
                return AdbEndpoint::LocalTcp(SocketAddr::from((Ipv4Addr::LOCALHOST, port)));
            }
            return AdbEndpoint::UnsupportedSocket;
        }
        if let Some(port_str) = rest.strip_prefix("[::1]:") {
            if let Some(port) = parse_nonzero_port(port_str) {
                return AdbEndpoint::LocalTcp(SocketAddr::from((Ipv6Addr::LOCALHOST, port)));
            }
            return AdbEndpoint::UnsupportedSocket;
        }
        return AdbEndpoint::UnsupportedSocket;
    }

    if let Some(raw_port) = android_port.map(str::trim).filter(|s| !s.is_empty()) {
        return match parse_nonzero_port(raw_port) {
            Some(port) => AdbEndpoint::LocalTcp(SocketAddr::from((Ipv4Addr::LOCALHOST, port))),
            None => AdbEndpoint::UnsupportedSocket,
        };
    }

    AdbEndpoint::LocalTcp(SocketAddr::from((Ipv4Addr::LOCALHOST, 5037)))
}

/// Resolve the active ADB server endpoint from environment variables.
pub fn resolve_adb_endpoint() -> AdbEndpoint {
    let android_port = env::var("ANDROID_ADB_SERVER_PORT").ok();
    let server_socket = env::var("ADB_SERVER_SOCKET").ok();
    resolve_adb_endpoint_from(android_port.as_deref(), server_socket.as_deref())
}

/// Query connected USB devices from the configured local ADB TCP endpoint without spawning `adb`.
pub fn get_connected_usb_devices_if_server_running() -> Vec<String> {
    match resolve_adb_endpoint() {
        AdbEndpoint::LocalTcp(addr) => query_adb_devices_at(addr, Duration::from_millis(200)),
        AdbEndpoint::UnsupportedSocket => Vec::new(),
    }
}

pub fn get_connected_usb_devices() -> Vec<String> {
    let Ok(output) = Command::new("adb")
        .arg("devices")
        .stderr(Stdio::null())
        .output()
    else {
        return Vec::new();
    };
    if !output.status.success() {
        return Vec::new();
    }
    parse_adb_online_serials(&String::from_utf8_lossy(&output.stdout))
}

/// Find running `silentinput-daemon` (or legacy `silentinput-daemon.py`) PIDs via `/proc` cmdline.
pub fn find_daemon_pids_in(proc_root: &Path, self_pid: u32) -> Vec<u32> {
    let mut pids = Vec::new();
    let Ok(entries) = fs::read_dir(proc_root) else {
        return pids;
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        let name_str = name.to_string_lossy();
        if !name_str.chars().all(|c| c.is_ascii_digit()) {
            continue;
        }
        let Ok(pid) = name_str.parse::<u32>() else {
            continue;
        };
        if pid == 0 || pid == self_pid {
            continue;
        }
        let Ok(raw) = fs::read(entry.path().join("cmdline")) else {
            continue;
        };
        let args: Vec<String> = raw
            .split(|&b| b == 0)
            .filter(|slice| !slice.is_empty())
            .map(|slice| String::from_utf8_lossy(slice).into_owned())
            .collect();
        if args.is_empty() {
            continue;
        }
        let first_bin = Path::new(&args[0])
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("")
            .to_ascii_lowercase();
        if first_bin == "silentinput-daemon" || first_bin == "silentinput-daemon.py" {
            pids.push(pid);
        } else if first_bin.contains("python") {
            for arg in &args[1..] {
                if arg.starts_with('-') {
                    continue;
                }
                let script_name = Path::new(arg)
                    .file_name()
                    .and_then(|n| n.to_str())
                    .unwrap_or("");
                if script_name == "silentinput-daemon.py" {
                    pids.push(pid);
                }
                break;
            }
        }
    }
    pids.sort_unstable();
    pids
}

pub fn find_daemon_pids() -> Vec<u32> {
    find_daemon_pids_in(Path::new("/proc"), std::process::id())
}

fn pid_is_alive_daemon(pid: u32) -> bool {
    if pid == 0 || pid == std::process::id() {
        return false;
    }
    let res = unsafe { libc::kill(pid as libc::pid_t, 0) };
    if res != 0 {
        return false;
    }
    let cmdline_path = PathBuf::from(format!("/proc/{pid}/cmdline"));
    if let Ok(raw) = fs::read(cmdline_path) {
        let cmdline = String::from_utf8_lossy(&raw);
        cmdline.contains("silentinput-daemon")
    } else {
        true
    }
}

pub fn is_daemon_running_with_config(paths: &SilentInputPaths, config: &SilentInputConfig) -> (bool, i32) {
    let pid_path = paths.pid_file();
    if let Ok(content) = fs::read_to_string(&pid_path) {
        if let Ok(pid) = content.trim().parse::<u32>() {
            if pid_is_alive_daemon(pid) {
                return (true, pid as i32);
            }
        }
    }

    let addr = SocketAddr::from(([127, 0, 0, 1], config.port));
    if TcpStream::connect_timeout(&addr, Duration::from_millis(500)).is_ok() {
        return (true, -1);
    }

    let _ = fs::remove_file(&pid_path);
    (false, 0)
}

pub fn is_daemon_running() -> (bool, i32) {
    let paths = SilentInputPaths::system();
    let cfg = load_config_at(&paths);
    is_daemon_running_with_config(&paths, &cfg)
}

pub fn is_system_package_install() -> bool {
    env::current_exe()
        .map(|p| {
            let s = p.to_string_lossy();
            s.starts_with("/usr/") || s.starts_with("/opt/")
        })
        .unwrap_or(false)
}

pub fn get_package_uninstall_command() -> Option<&'static str> {
    if let Ok(out) = Command::new("dpkg-query")
        .args(["-W", "-f=${Status}", "silentinput"])
        .stderr(Stdio::null())
        .output()
    {
        if out.status.success()
            && String::from_utf8_lossy(&out.stdout).contains("installed")
        {
            return Some("sudo apt remove silentinput");
        }
    }
    if let Ok(status) = Command::new("rpm")
        .args(["-q", "silentinput"])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
    {
        if status.success() {
            return Some("sudo dnf remove silentinput");
        }
    }
    if let Ok(status) = Command::new("pacman")
        .args(["-Q", "silentinput"])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
    {
        if status.success() {
            return Some("sudo pacman -R silentinput");
        }
    }
    None
}

pub fn is_systemd_unit_installed_at(paths: &SilentInputPaths) -> bool {
    let candidates = [
        paths.systemd_service_file(),
        PathBuf::from("/etc/systemd/user").join(SYSTEMD_SERVICE_NAME),
        PathBuf::from("/usr/lib/systemd/user").join(SYSTEMD_SERVICE_NAME),
        PathBuf::from("/lib/systemd/user").join(SYSTEMD_SERVICE_NAME),
    ];
    candidates.iter().any(|p| p.exists())
}

pub fn has_active_systemd_at(paths: &SilentInputPaths) -> bool {
    if !is_systemd_unit_installed_at(paths) {
        return false;
    }
    Command::new("systemctl")
        .args(["--user", "status"])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| matches!(s.code(), Some(0 | 3)))
        .unwrap_or(false)
}

pub fn has_active_systemd() -> bool {
    has_active_systemd_at(&SilentInputPaths::system())
}

pub fn is_autostart_enabled_at(paths: &SilentInputPaths) -> bool {
    if has_active_systemd_at(paths) {
        let override_link = paths.user_wants_override_link();
        if let Ok(target) = fs::read_link(&override_link) {
            if target == Path::new("/dev/null") {
                return false;
            }
        }
        if let Ok(out) = Command::new("systemctl")
            .args(["--user", "is-enabled", SYSTEMD_SERVICE_NAME])
            .stderr(Stdio::null())
            .output()
        {
            if out.status.success() {
                let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
                return matches!(
                    s.as_str(),
                    "enabled" | "enabled-runtime" | "static" | "indirect"
                );
            }
        }
        return false;
    }
    paths.xdg_autostart_file().exists()
}

pub fn is_autostart_enabled() -> bool {
    is_autostart_enabled_at(&SilentInputPaths::system())
}

pub fn set_autostart_enabled_at(
    paths: &SilentInputPaths,
    enabled: bool,
    is_zh: bool,
) -> (bool, String) {
    if has_active_systemd_at(paths) {
        let override_link = paths.user_wants_override_link();
        if enabled {
            if let Ok(target) = fs::read_link(&override_link) {
                if target == Path::new("/dev/null") {
                    let _ = fs::remove_file(&override_link);
                }
            }
            let _ = Command::new("systemctl")
                .args(["--user", "unmask", SYSTEMD_SERVICE_NAME])
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status();

            let mut out = Command::new("systemctl")
                .args(["--user", "enable", SYSTEMD_SERVICE_NAME])
                .output();
            let succeeded = out
                .as_ref()
                .map(|o| o.status.success())
                .unwrap_or(false)
                || is_autostart_enabled_at(paths);

            if !succeeded {
                let _ = Command::new("systemctl")
                    .args(["--user", "daemon-reload"])
                    .stdout(Stdio::null())
                    .stderr(Stdio::null())
                    .status();
                out = Command::new("systemctl")
                    .args(["--user", "enable", SYSTEMD_SERVICE_NAME])
                    .output();
                let retry_ok = out
                    .as_ref()
                    .map(|o| o.status.success())
                    .unwrap_or(false)
                    || is_autostart_enabled_at(paths);
                if !retry_ok {
                    let detail = out
                        .ok()
                        .map(|o| {
                            let err = String::from_utf8_lossy(&o.stderr).trim().to_string();
                            if !err.is_empty() {
                                err
                            } else {
                                String::from_utf8_lossy(&o.stdout).trim().to_string()
                            }
                        })
                        .filter(|s| !s.is_empty())
                        .unwrap_or_else(|| {
                            if is_zh {
                                "启用开机启动失败".to_string()
                            } else {
                                "Failed to enable autostart".to_string()
                            }
                        });
                    return (false, detail);
                }
            }
            let _ = Command::new("systemctl")
                .args(["--user", "daemon-reload"])
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status();
            return (
                true,
                if is_zh {
                    "已启用开机自动启动".to_string()
                } else {
                    "Autostart enabled".to_string()
                },
            );
        }

        let _ = Command::new("systemctl")
            .args(["--user", "disable", SYSTEMD_SERVICE_NAME])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();

        if paths.global_wants_link().exists() || is_autostart_enabled_at(paths) {
            if let Some(parent) = override_link.parent() {
                if let Err(e) = fs::create_dir_all(parent) {
                    return (
                        false,
                        if is_zh {
                            format!("关闭开机启动失败: {e}")
                        } else {
                            format!("Failed to disable autostart: {e}")
                        },
                    );
                }
            }
            if override_link.exists() || override_link.is_symlink() {
                let _ = fs::remove_file(&override_link);
            }
            if let Err(e) = std::os::unix::fs::symlink("/dev/null", &override_link) {
                return (
                    false,
                    if is_zh {
                        format!("关闭开机启动失败: {e}")
                    } else {
                        format!("Failed to disable autostart: {e}")
                    },
                );
            }
        }
        let _ = Command::new("systemctl")
            .args(["--user", "daemon-reload"])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
        return (
            true,
            if is_zh {
                "已关闭开机自动启动".to_string()
            } else {
                "Autostart disabled".to_string()
            },
        );
    }

    let autostart_file = paths.xdg_autostart_file();
    if enabled {
        if let Some(parent) = autostart_file.parent() {
            if let Err(e) = fs::create_dir_all(parent) {
                return (
                    false,
                    if is_zh {
                        format!("设置开机启动失败: {e}")
                    } else {
                        format!("Failed to configure autostart: {e}")
                    },
                );
            }
        }
        let daemon_bin = resolve_binary("silentinput-daemon");
        let desktop_content = format!(
            "[Desktop Entry]\nType=Application\nName=SilentInput Daemon\nExec=\"{}\"\nX-GNOME-Autostart-enabled=true\n",
            daemon_bin.display()
        );
        if let Err(e) = fs::write(&autostart_file, desktop_content) {
            return (
                false,
                if is_zh {
                    format!("设置开机启动失败: {e}")
                } else {
                    format!("Failed to configure autostart: {e}")
                },
            );
        }
        return (
            true,
            if is_zh {
                "已启用开机自动启动 (XDG)".to_string()
            } else {
                "Autostart enabled (XDG)".to_string()
            },
        );
    }

    let _ = fs::remove_file(&autostart_file);
    (
        true,
        if is_zh {
            "已关闭开机自动启动".to_string()
        } else {
            "Autostart disabled".to_string()
        },
    )
}

pub fn set_autostart_enabled(enabled: bool, is_zh: bool) -> (bool, String) {
    set_autostart_enabled_at(&SilentInputPaths::system(), enabled, is_zh)
}

pub fn start_daemon_process_at(paths: &SilentInputPaths) -> (bool, String) {
    let cfg = load_config_at(paths);
    let (running, _) = is_daemon_running_with_config(paths, &cfg);
    if running {
        return (true, "Daemon is already running".to_string());
    }

    let _ = ensure_config_dir_at(paths);
    let log_file_path = paths.log_file();
    if let Ok(meta) = fs::metadata(&log_file_path) {
        if meta.len() > 2 * 1024 * 1024 {
            let _ = fs::rename(&log_file_path, paths.log_backup_file());
        }
    }

    let stdout_file = match open_private_append_file(&log_file_path) {
        Ok(f) => f,
        Err(e) => return (false, format!("Failed to open log file: {e}")),
    };
    let stderr_file = match stdout_file.try_clone() {
        Ok(f) => f,
        Err(e) => return (false, format!("Failed to clone log file fd: {e}")),
    };

    let daemon_bin = resolve_binary("silentinput-daemon");
    let mut cmd = Command::new(&daemon_bin);
    cmd.stdout(Stdio::from(stdout_file))
        .stderr(Stdio::from(stderr_file))
        .stdin(Stdio::null());
    unsafe {
        cmd.pre_exec(|| {
            libc::setsid();
            Ok(())
        });
    }

    match cmd.spawn() {
        Ok(child) => {
            let pid = child.id();
            let pid_path = paths.pid_file();
            let _ = fs::write(&pid_path, pid.to_string());
            let _ = fs::set_permissions(&pid_path, fs::Permissions::from_mode(0o600));
            (true, format!("Daemon started successfully (PID: {pid})"))
        }
        Err(e) => (false, format!("Failed to start daemon ({}): {e}", daemon_bin.display())),
    }
}

pub fn stop_daemon_process_at(paths: &SilentInputPaths) -> (bool, String) {
    let cfg = load_config_at(paths);
    let (_, pid) = is_daemon_running_with_config(paths, &cfg);
    let mut targets: BTreeSet<u32> = find_daemon_pids().into_iter().collect();
    if pid > 0 {
        targets.insert(pid as u32);
    }

    for &tpid in &targets {
        unsafe {
            libc::kill(tpid as libc::pid_t, libc::SIGTERM);
        }
    }
    for &tpid in &targets {
        let mut exited = false;
        for _ in 0..15 {
            let alive = unsafe { libc::kill(tpid as libc::pid_t, 0) } == 0;
            if !alive {
                exited = true;
                break;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        if !exited {
            unsafe {
                libc::kill(tpid as libc::pid_t, libc::SIGKILL);
            }
        }
    }

    let _ = fs::remove_file(paths.pid_file());
    (true, "Daemon stopped".to_string())
}

fn wait_for_daemon_ready_at(paths: &SilentInputPaths) {
    let cfg = load_config_at(paths);
    for _ in 0..50 {
        if is_daemon_running_with_config(paths, &cfg).0 {
            return;
        }
        std::thread::sleep(Duration::from_millis(40));
    }
}

pub fn service_start_at(paths: &SilentInputPaths) -> (bool, String) {
    if has_active_systemd_at(paths) {
        match Command::new("systemctl")
            .args(["--user", "start", SYSTEMD_SERVICE_NAME])
            .output()
        {
            Ok(out) if out.status.success() => {
                wait_for_daemon_ready_at(paths);
                return (true, "SilentInput service started via systemd.".to_string());
            }
            Ok(out) => {
                let err = String::from_utf8_lossy(&out.stderr).trim().to_string();
                let msg = if err.is_empty() {
                    String::from_utf8_lossy(&out.stdout).trim().to_string()
                } else {
                    err
                };
                return (
                    false,
                    if msg.is_empty() {
                        "systemctl start failed".to_string()
                    } else {
                        msg
                    },
                );
            }
            Err(e) => return (false, format!("systemctl start failed: {e}")),
        }
    }
    let res = start_daemon_process_at(paths);
    if res.0 {
        wait_for_daemon_ready_at(paths);
    }
    res
}

pub fn service_stop_at(paths: &SilentInputPaths) -> (bool, String) {
    if has_active_systemd_at(paths) {
        let _ = Command::new("systemctl")
            .args(["--user", "stop", SYSTEMD_SERVICE_NAME])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
    stop_daemon_process_at(paths)
}

pub fn service_restart_at(paths: &SilentInputPaths) -> (bool, String) {
    if has_active_systemd_at(paths) {
        if let Ok(out) = Command::new("systemctl")
            .args(["--user", "restart", SYSTEMD_SERVICE_NAME])
            .output()
        {
            if out.status.success() {
                wait_for_daemon_ready_at(paths);
                return (true, "SilentInput service restarted via systemd.".to_string());
            }
        }
    }
    let _ = stop_daemon_process_at(paths);
    std::thread::sleep(Duration::from_millis(300));
    let res = start_daemon_process_at(paths);
    if res.0 {
        wait_for_daemon_ready_at(paths);
    }
    res
}

pub fn service_start() -> (bool, String) {
    service_start_at(&SilentInputPaths::system())
}

pub fn service_stop() -> (bool, String) {
    service_stop_at(&SilentInputPaths::system())
}

pub fn service_restart() -> (bool, String) {
    service_restart_at(&SilentInputPaths::system())
}

pub fn spawn_detached_binary(bin_name: &str) -> std::io::Result<()> {
    let bin = resolve_binary(bin_name);
    let mut cmd = Command::new(&bin);
    cmd.stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    unsafe {
        cmd.pre_exec(|| {
            libc::setsid();
            Ok(())
        });
    }
    cmd.spawn().map(|_| ())
}
