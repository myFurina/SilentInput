use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use rand::RngCore;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use subtle::ConstantTimeEq;

use crate::paths::SilentInputPaths;

static CONFIG_LOCK: OnceLock<Mutex<()>> = OnceLock::new();
static TMP_COUNTER: AtomicU64 = AtomicU64::new(0);

fn config_mutex() -> &'static Mutex<()> {
    CONFIG_LOCK.get_or_init(|| Mutex::new(()))
}

pub fn default_port() -> u16 {
    53318
}

pub fn default_token_expire_mode() -> String {
    "never".to_string()
}

pub fn default_true() -> bool {
    true
}

pub fn default_access_control_mode() -> String {
    "whitelist".to_string()
}

pub fn default_whitelist() -> Vec<String> {
    vec![
        "127.0.0.1".to_string(),
        "10.*".to_string(),
        "192.168.*".to_string(),
        "100.*".to_string(),
        "172.16.*".to_string(),
    ]
}

pub fn default_gui_language() -> String {
    "system".to_string()
}

pub fn default_gui_theme() -> String {
    "system".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SilentInputConfig {
    #[serde(default = "default_port")]
    pub port: u16,
    #[serde(default)]
    pub auth_token: String,
    #[serde(default)]
    pub token_created_at: f64,
    #[serde(default = "default_token_expire_mode")]
    pub token_expire_mode: String,
    #[serde(default = "default_true")]
    pub enable_tls: bool,
    #[serde(default = "default_true")]
    pub allow_usb_unauthenticated: bool,
    #[serde(default = "default_access_control_mode")]
    pub access_control_mode: String,
    #[serde(default = "default_whitelist")]
    pub whitelist: Vec<String>,
    #[serde(default)]
    pub blacklist: Vec<String>,
    #[serde(default = "default_gui_language")]
    pub gui_language: String,
    #[serde(default = "default_gui_theme")]
    pub gui_theme: String,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl Default for SilentInputConfig {
    fn default() -> Self {
        Self {
            port: default_port(),
            auth_token: String::new(),
            token_created_at: 0.0,
            token_expire_mode: default_token_expire_mode(),
            enable_tls: true,
            allow_usb_unauthenticated: true,
            access_control_mode: default_access_control_mode(),
            whitelist: default_whitelist(),
            blacklist: Vec::new(),
            gui_language: default_gui_language(),
            gui_theme: default_gui_theme(),
            extra: Map::new(),
        }
    }
}

pub fn unix_timestamp_secs() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0)
}

pub fn generate_token_hex(byte_len: usize) -> String {
    let mut bytes = vec![0u8; byte_len];
    rand::thread_rng().fill_bytes(&mut bytes);
    let mut out = String::with_capacity(byte_len * 2);
    for b in bytes {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

pub fn constant_time_token_eq(provided: &str, expected: &str) -> bool {
    let p = provided.trim();
    let e = expected.trim();
    if p.is_empty() || e.is_empty() {
        return false;
    }
    bool::from(p.as_bytes().ct_eq(e.as_bytes()))
}

pub fn get_token_expire_seconds(mode: &str) -> u64 {
    match mode {
        "1h" => 3600,
        "24h" => 86_400,
        "7d" => 604_800,
        _ => 0,
    }
}

pub fn get_token_status_text_at(config: &SilentInputConfig, is_zh: bool, now_secs: f64) -> String {
    let mode = config.token_expire_mode.as_str();
    if mode == "never" {
        return if is_zh {
            "永不过期".to_string()
        } else {
            "Never expires".to_string()
        };
    }

    let expire_sec = get_token_expire_seconds(mode);
    if expire_sec == 0 {
        return mode.to_string();
    }
    let created_at = config.token_created_at;
    if created_at <= 0.0 {
        return mode.to_string();
    }

    let elapsed = now_secs - created_at;
    let remaining = (expire_sec as f64) - elapsed;
    if remaining <= 0.0 {
        return if is_zh {
            format!("{mode} (已到期，下次连接时轮换)")
        } else {
            format!("{mode} (expired, rotates on next connect)")
        };
    }

    let total_secs = remaining as u64;
    let hours = total_secs / 3600;
    let minutes = (total_secs % 3600) / 60;
    if hours > 0 {
        if is_zh {
            format!("{mode} (剩余 {hours}小时{minutes}分)")
        } else {
            format!("{mode} ({hours}h {minutes}m left)")
        }
    } else if is_zh {
        format!("{mode} (剩余 {minutes}分钟)")
    } else {
        format!("{mode} ({minutes}m left)")
    }
}

pub fn get_token_status_text(config: &SilentInputConfig, is_zh: bool) -> String {
    get_token_status_text_at(config, is_zh, unix_timestamp_secs())
}

pub fn ensure_config_dir_at(paths: &SilentInputPaths) -> std::io::Result<()> {
    fs::create_dir_all(&paths.config_dir)?;
    let _ = fs::set_permissions(&paths.config_dir, fs::Permissions::from_mode(0o700));
    Ok(())
}

pub fn atomic_write_private_file(dir: &Path, target: &Path, contents: &[u8]) -> std::io::Result<()> {
    let seq = TMP_COUNTER.fetch_add(1, Ordering::Relaxed);
    let pid = std::process::id();
    let tmp_name = format!(".tmp.{pid}.{seq}.tmp");
    let tmp_path = dir.join(tmp_name);

    let write_res = (|| -> std::io::Result<()> {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&tmp_path)?;
        file.write_all(contents)?;
        file.sync_all()?;
        drop(file);
        let _ = fs::set_permissions(&tmp_path, fs::Permissions::from_mode(0o600));
        fs::rename(&tmp_path, target)?;
        Ok(())
    })();

    if write_res.is_err() {
        let _ = fs::remove_file(&tmp_path);
    }
    write_res
}

fn check_and_rotate_token_locked(paths: &SilentInputPaths, config: &mut SilentInputConfig) -> bool {
    let expire_sec = get_token_expire_seconds(&config.token_expire_mode);
    let now = unix_timestamp_secs();

    let mut need_new = false;
    if config.auth_token.is_empty() {
        need_new = true;
    } else if expire_sec > 0 {
        let created_at = config.token_created_at;
        if created_at <= 0.0 || (now - created_at) >= expire_sec as f64 {
            need_new = true;
        }
    }

    if need_new {
        config.auth_token = generate_token_hex(4);
        config.token_created_at = now;
        let _ = save_config_unlocked(paths, config);
        return true;
    }
    false
}

fn save_config_unlocked(paths: &SilentInputPaths, config: &SilentInputConfig) -> std::io::Result<()> {
    ensure_config_dir_at(paths)?;
    let serialized = serde_json::to_string_pretty(config)
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))?;
    atomic_write_private_file(&paths.config_dir, &paths.config_file(), serialized.as_bytes())
}

pub fn load_config_at(paths: &SilentInputPaths) -> SilentInputConfig {
    let _guard = config_mutex().lock().unwrap_or_else(|e| e.into_inner());
    let _ = ensure_config_dir_at(paths);

    let cfg_path = paths.config_file();
    let mut config = SilentInputConfig::default();

    if cfg_path.exists() {
        let _ = fs::set_permissions(&cfg_path, fs::Permissions::from_mode(0o600));
        match fs::read_to_string(&cfg_path) {
            Ok(raw) => match serde_json::from_str::<SilentInputConfig>(&raw) {
                Ok(parsed) => {
                    config = parsed;
                }
                Err(err) => {
                    eprintln!(
                        "[SilentInput Config] Warning: Failed to parse {}: {err}",
                        cfg_path.display()
                    );
                }
            },
            Err(err) => {
                eprintln!(
                    "[SilentInput Config] Warning: Failed to read {}: {err}",
                    cfg_path.display()
                );
            }
        }
    }

    check_and_rotate_token_locked(paths, &mut config);
    config
}

pub fn save_config_at(paths: &SilentInputPaths, config: &SilentInputConfig) -> std::io::Result<()> {
    let _guard = config_mutex().lock().unwrap_or_else(|e| e.into_inner());
    save_config_unlocked(paths, config)
}

pub fn regenerate_token_at(paths: &SilentInputPaths) -> String {
    let mut config = load_config_at(paths);
    let new_token = generate_token_hex(4);
    config.auth_token = new_token.clone();
    config.token_created_at = unix_timestamp_secs();
    let _ = save_config_at(paths, &config);
    new_token
}

pub fn set_tls_enabled_at(paths: &SilentInputPaths, enable: bool) -> bool {
    let mut config = load_config_at(paths);
    config.enable_tls = enable;
    let _ = save_config_at(paths, &config);
    config.enable_tls
}

pub fn load_config() -> SilentInputConfig {
    load_config_at(&SilentInputPaths::system())
}

pub fn save_config(config: &SilentInputConfig) -> std::io::Result<()> {
    save_config_at(&SilentInputPaths::system(), config)
}

pub fn regenerate_token() -> String {
    regenerate_token_at(&SilentInputPaths::system())
}

pub fn set_tls_enabled(enable: bool) -> bool {
    set_tls_enabled_at(&SilentInputPaths::system(), enable)
}

pub fn open_private_append_file(path: &Path) -> std::io::Result<File> {
    let f = OpenOptions::new()
        .create(true)
        .append(true)
        .mode(0o600)
        .open(path)?;
    let _ = fs::set_permissions(path, fs::Permissions::from_mode(0o600));
    Ok(f)
}
