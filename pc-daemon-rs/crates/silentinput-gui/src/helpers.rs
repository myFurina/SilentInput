use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;
use std::process::{Command, Stdio};

pub fn parse_ip_list(raw: &str) -> Vec<String> {
    raw.split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(ToString::to_string)
        .collect()
}

pub fn format_ip_list(items: &[String]) -> String {
    items.join(", ")
}

/// Read up to last 32 KiB of the daemon log and return the trailing 80 lines.
pub fn read_log_tail(log_path: &Path, is_zh: bool) -> String {
    if !log_path.exists() {
        return if is_zh {
            "暂无日志".to_string()
        } else {
            "No logs yet".to_string()
        };
    }

    let Ok(mut file) = File::open(log_path) else {
        return if is_zh {
            format!("读取日志失败: {}", log_path.display())
        } else {
            format!("Failed to open log file: {}", log_path.display())
        };
    };

    const TAIL_BYTES: u64 = 32 * 1024;
    if let Ok(meta) = file.metadata() {
        let size = meta.len();
        if size > TAIL_BYTES {
            let _ = file.seek(SeekFrom::Start(size - TAIL_BYTES));
        }
    }

    let mut raw = Vec::new();
    if let Err(err) = file.read_to_end(&mut raw) {
        return if is_zh {
            format!("读取日志失败: {err}")
        } else {
            format!("Failed to read logs: {err}")
        };
    }

    let text = String::from_utf8_lossy(&raw);
    let lines: Vec<&str> = text.lines().collect();
    if lines.is_empty() {
        return if is_zh {
            "日志为空".to_string()
        } else {
            "Log is empty".to_string()
        };
    }
    let start = lines.len().saturating_sub(80);
    lines[start..].join("\n")
}

/// Query fontconfig for the preferred Chinese sans-serif font family name on Linux.
pub fn detect_cjk_font_family() -> Option<&'static str> {
    query_fontconfig_family("sans:lang=zh-cn")
}

/// Query fontconfig for the preferred Chinese monospace font family name on Linux.
pub fn detect_cjk_mono_font_family() -> Option<&'static str> {
    query_fontconfig_family("monospace:lang=zh-cn")
}

fn query_fontconfig_family(pattern: &str) -> Option<&'static str> {
    if let Ok(out) = Command::new("fc-match")
        .args(["--format=%{family}", pattern])
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
    {
        if out.status.success() {
            let raw = String::from_utf8_lossy(&out.stdout);
            if let Some(first) = raw.split(',').next().map(str::trim).filter(|s| !s.is_empty()) {
                return Some(Box::leak(first.to_string().into_boxed_str()));
            }
        }
    }
    None
}

/// Remove user-local installation artifacts when uninstalling a non-package build.
pub fn perform_user_uninstall() {
    let paths = silentinput_core::paths::SilentInputPaths::system();
    let _ = silentinput_core::process::service_stop_at(&paths);

    let _ = Command::new("systemctl")
        .args(["--user", "disable", "silentinput.service"])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();

    let _ = fs::remove_file(paths.systemd_service_file());
    let _ = fs::remove_file(paths.user_wants_override_link());
    let _ = fs::remove_file(paths.xdg_autostart_file());

    let _ = Command::new("systemctl")
        .args(["--user", "daemon-reload"])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();

    let local_bin = paths.home_dir.join(".local").join("bin");
    for bin_name in ["silentinput", "silentinput-daemon", "silentinput-gui"] {
        let _ = fs::remove_file(local_bin.join(bin_name));
    }

    let app_dir = paths.home_dir.join(".local").join("share").join("applications");
    for desktop in [
        "com.silentinput.controlcenter.desktop",
        "silentinput.desktop",
        "silentinput-toggle.desktop",
    ] {
        let _ = fs::remove_file(app_dir.join(desktop));
    }

    let icons_base = paths
        .home_dir
        .join(".local")
        .join("share")
        .join("icons")
        .join("hicolor");
    let _ = fs::remove_file(
        icons_base
            .join("scalable")
            .join("apps")
            .join("com.silentinput.controlcenter.svg"),
    );
    let _ = fs::remove_file(
        icons_base
            .join("128x128")
            .join("apps")
            .join("com.silentinput.controlcenter.png"),
    );
    let _ = fs::remove_dir_all(paths.home_dir.join(".local").join("share").join("silentinput"));
}
