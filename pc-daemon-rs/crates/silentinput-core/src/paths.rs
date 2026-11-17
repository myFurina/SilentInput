use std::env;
use std::path::{Path, PathBuf};

pub const SYSTEMD_SERVICE_NAME: &str = "silentinput.service";

#[derive(Debug, Clone)]
pub struct SilentInputPaths {
    pub config_dir: PathBuf,
    pub home_dir: PathBuf,
}

impl SilentInputPaths {
    pub fn system() -> Self {
        let home_dir = env::var_os("HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("/tmp"));
        let config_dir = home_dir.join(".config").join("silentinput");
        Self {
            config_dir,
            home_dir,
        }
    }

    pub fn from_dir(config_dir: impl AsRef<Path>) -> Self {
        let dir = config_dir.as_ref().to_path_buf();
        let home_dir = dir
            .parent()
            .and_then(|p| p.parent())
            .map(|p| p.to_path_buf())
            .unwrap_or_else(|| dir.clone());
        Self {
            config_dir: dir,
            home_dir,
        }
    }

    pub fn with_home(home_dir: impl AsRef<Path>) -> Self {
        let home = home_dir.as_ref().to_path_buf();
        let config_dir = home.join(".config").join("silentinput");
        Self {
            config_dir,
            home_dir: home,
        }
    }

    pub fn config_file(&self) -> PathBuf {
        self.config_dir.join("config.json")
    }

    pub fn pid_file(&self) -> PathBuf {
        self.config_dir.join("silentinput.pid")
    }

    pub fn log_file(&self) -> PathBuf {
        self.config_dir.join("silentinput.log")
    }

    pub fn log_backup_file(&self) -> PathBuf {
        self.config_dir.join("silentinput.log.1")
    }

    pub fn cert_file(&self) -> PathBuf {
        self.config_dir.join("cert.pem")
    }

    pub fn key_file(&self) -> PathBuf {
        self.config_dir.join("key.pem")
    }

    pub fn systemd_service_file(&self) -> PathBuf {
        self.home_dir
            .join(".config")
            .join("systemd")
            .join("user")
            .join(SYSTEMD_SERVICE_NAME)
    }

    pub fn user_wants_override_link(&self) -> PathBuf {
        self.home_dir
            .join(".config")
            .join("systemd")
            .join("user")
            .join("default.target.wants")
            .join(SYSTEMD_SERVICE_NAME)
    }

    pub fn global_wants_link(&self) -> PathBuf {
        PathBuf::from("/etc/systemd/user/default.target.wants").join(SYSTEMD_SERVICE_NAME)
    }

    pub fn xdg_autostart_file(&self) -> PathBuf {
        self.home_dir
            .join(".config")
            .join("autostart")
            .join("com.silentinput.daemon.desktop")
    }
}
