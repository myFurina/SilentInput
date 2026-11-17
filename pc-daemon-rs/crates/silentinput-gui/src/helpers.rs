use std::fs;
use std::process::{Command, Stdio};
use std::sync::OnceLock;

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

/// Query fontconfig for the preferred Chinese sans-serif font family name on Linux (cached).
pub fn detect_cjk_font_family() -> Option<&'static str> {
    static CJK_SANS: OnceLock<Option<&'static str>> = OnceLock::new();
    *CJK_SANS.get_or_init(|| query_fontconfig_family("sans:lang=zh-cn"))
}

/// Query fontconfig for the preferred Chinese monospace font family name on Linux (cached).
pub fn detect_cjk_mono_font_family() -> Option<&'static str> {
    static CJK_MONO: OnceLock<Option<&'static str>> = OnceLock::new();
    *CJK_MONO.get_or_init(|| query_fontconfig_family("monospace:lang=zh-cn"))
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
