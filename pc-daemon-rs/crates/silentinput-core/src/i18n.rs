use std::env;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LanguagePreference {
    System,
    Zh,
    En,
}

impl LanguagePreference {
    pub fn as_str(&self) -> &'static str {
        match self {
            LanguagePreference::System => "system",
            LanguagePreference::Zh => "zh",
            LanguagePreference::En => "en",
        }
    }

    pub fn from_config_str(value: &str) -> Self {
        match value.trim().to_ascii_lowercase().as_str() {
            "zh" => LanguagePreference::Zh,
            "en" => LanguagePreference::En,
            _ => LanguagePreference::System,
        }
    }
}

pub fn is_zh_system_locale() -> bool {
    for key in ["LC_ALL", "LC_MESSAGES", "LANG", "LANGUAGE"] {
        if let Ok(val) = env::var(key) {
            let trimmed = val.trim().to_ascii_lowercase();
            if !trimmed.is_empty() {
                if trimmed.starts_with("zh") {
                    return true;
                }
                if !matches!(trimmed.as_str(), "c" | "c.utf-8" | "c.utf8" | "posix") {
                    return false;
                }
            }
        }
    }
    true
}

pub fn resolve_gui_is_zh(lang_pref: &str) -> bool {
    match lang_pref.trim().to_ascii_lowercase().as_str() {
        "zh" => true,
        "en" => false,
        _ => is_zh_system_locale(),
    }
}

#[inline]
pub fn t<'a>(is_zh: bool, zh: &'a str, en: &'a str) -> &'a str {
    if is_zh {
        zh
    } else {
        en
    }
}
