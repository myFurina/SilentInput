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
            label: if is_zh { "永不过期" } else { "Never" },
        },
        PickOption {
            key: "24h",
            label: if is_zh { "24 小时" } else { "24 hours" },
        },
        PickOption {
            key: "7d",
            label: if is_zh { "7 天" } else { "7 days" },
        },
        PickOption {
            key: "1h",
            label: if is_zh { "1 小时" } else { "1 hour" },
        },
    ]
}

pub fn acl_mode_options(is_zh: bool) -> Vec<PickOption> {
    vec![
        PickOption {
            key: "whitelist",
            label: if is_zh { "白名单" } else { "Whitelist" },
        },
        PickOption {
            key: "blacklist",
            label: if is_zh { "黑名单" } else { "Blacklist" },
        },
        PickOption {
            key: "all",
            label: if is_zh {
                "允许所有网络"
            } else {
                "Allow all"
            },
        },
        PickOption {
            key: "localhost_only",
            label: if is_zh {
                "仅限本机 USB"
            } else {
                "Localhost USB"
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
