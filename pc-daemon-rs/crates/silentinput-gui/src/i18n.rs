use std::fmt;

#[derive(Debug, Clone)]
pub struct PickOption {
    pub key: &'static str,
    pub label: &'static str,
}

impl PartialEq for PickOption {
    fn eq(&self, other: &Self) -> bool {
        self.key == other.key
    }
}

impl Eq for PickOption {}

impl fmt::Display for PickOption {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label)
    }
}

pub fn expire_options(is_zh: bool) -> Vec<PickOption> {
    vec![
        PickOption {
            key: "never",
            label: if is_zh { "永不过期" } else { "Never expires" },
        },
        PickOption {
            key: "24h",
            label: if is_zh {
                "24 小时自动轮换"
            } else {
                "Rotate every 24h"
            },
        },
        PickOption {
            key: "7d",
            label: if is_zh {
                "7 天自动轮换"
            } else {
                "Rotate every 7d"
            },
        },
        PickOption {
            key: "1h",
            label: if is_zh { "1 小时临时" } else { "1h temporary" },
        },
    ]
}

pub fn acl_mode_options(is_zh: bool) -> Vec<PickOption> {
    vec![
        PickOption {
            key: "whitelist",
            label: if is_zh {
                "白名单模式 (仅允许白名单 IP)"
            } else {
                "Whitelist (allow listed IPs only)"
            },
        },
        PickOption {
            key: "blacklist",
            label: if is_zh {
                "黑名单模式 (仅拒绝黑名单 IP)"
            } else {
                "Blacklist (block listed IPs only)"
            },
        },
        PickOption {
            key: "all",
            label: if is_zh {
                "允许所有网络 (0.0.0.0)"
            } else {
                "Allow all networks (0.0.0.0)"
            },
        },
        PickOption {
            key: "localhost_only",
            label: if is_zh {
                "仅本机 USB (127.0.0.1)"
            } else {
                "Localhost USB only (127.0.0.1)"
            },
        },
    ]
}

pub fn language_options(is_zh: bool) -> Vec<PickOption> {
    vec![
        PickOption {
            key: "system",
            label: if is_zh {
                "跟随系统"
            } else {
                "Follow System"
            },
        },
        PickOption {
            key: "zh",
            label: "简体中文",
        },
        PickOption {
            key: "en",
            label: "English",
        },
    ]
}

pub fn theme_options(is_zh: bool) -> Vec<PickOption> {
    vec![
        PickOption {
            key: "system",
            label: if is_zh {
                "跟随系统"
            } else {
                "Follow System"
            },
        },
        PickOption {
            key: "light",
            label: if is_zh { "浅色" } else { "Light" },
        },
        PickOption {
            key: "dark",
            label: if is_zh { "深色" } else { "Dark" },
        },
    ]
}

pub fn find_option(options: &[PickOption], key: &str) -> Option<PickOption> {
    options
        .iter()
        .find(|o| o.key == key)
        .cloned()
        .or_else(|| options.first().cloned())
}
