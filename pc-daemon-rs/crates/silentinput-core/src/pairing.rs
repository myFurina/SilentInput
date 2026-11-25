use std::ffi::CStr;

use qrcode::render::unicode;
use qrcode::{Color, EcLevel, QrCode};

use crate::certs::get_cert_sha256_hex_at;
use crate::config::SilentInputConfig;
use crate::paths::SilentInputPaths;
use crate::process::get_all_host_ips;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingQrPayload {
    pub name: String,
    pub ips: Vec<String>,
    pub port: u16,
    pub token: String,
    pub tls: bool,
    pub fingerprint: Option<String>,
}

pub fn get_host_display_name() -> String {
    let mut buf = [0u8; 256];
    let rc = unsafe { libc::gethostname(buf.as_mut_ptr() as *mut libc::c_char, buf.len()) };
    if rc == 0 {
        buf[buf.len() - 1] = 0;
        if let Ok(cstr) = CStr::from_bytes_until_nul(&buf) {
            if let Ok(s) = cstr.to_str() {
                let trimmed = s.trim();
                if !trimmed.is_empty() {
                    return trimmed.to_string();
                }
            }
        }
    }
    "SilentInput-PC".to_string()
}

pub fn build_pairing_payload_at(paths: &SilentInputPaths, config: &SilentInputConfig) -> PairingQrPayload {
    let fingerprint = if config.enable_tls {
        get_cert_sha256_hex_at(paths)
    } else {
        None
    };
    PairingQrPayload {
        name: get_host_display_name(),
        ips: get_all_host_ips(),
        port: config.port,
        token: config.auth_token.clone(),
        tls: config.enable_tls,
        fingerprint,
    }
}

fn percent_encode(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    for b in raw.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char);
            }
            _ => {
                out.push_str(&format!("%{b:02X}"));
            }
        }
    }
    out
}

fn percent_decode(raw: &str) -> Option<String> {
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            if i + 2 >= bytes.len() {
                return None;
            }
            let hi = (bytes[i + 1] as char).to_digit(16)?;
            let lo = (bytes[i + 2] as char).to_digit(16)?;
            out.push(((hi << 4) | lo) as u8);
            i += 3;
        } else if bytes[i] == b'+' {
            out.push(b' ');
            i += 1;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

pub fn build_pairing_uri(payload: &PairingQrPayload) -> String {
    let ips_joined = payload.ips.join(",");
    let mut uri = format!(
        "silentinput://pair?v=1&name={}&ips={}&port={}&token={}&tls={}",
        percent_encode(payload.name.trim()),
        percent_encode(&ips_joined),
        payload.port,
        percent_encode(payload.token.trim()),
        if payload.tls { "1" } else { "0" },
    );
    if let Some(fp) = &payload.fingerprint {
        let fp_clean = fp.trim().to_ascii_lowercase();
        if !fp_clean.is_empty() {
            uri.push_str("&fp=");
            uri.push_str(&percent_encode(&fp_clean));
        }
    }
    uri
}

pub fn parse_pairing_uri(uri: &str) -> Option<PairingQrPayload> {
    let trimmed = uri.trim();
    let query = trimmed.strip_prefix("silentinput://pair?")?;
    let mut version: Option<String> = None;
    let mut name = String::new();
    let mut ips = Vec::new();
    let mut port: Option<u16> = None;
    let mut token = String::new();
    let mut tls = true;
    let mut fingerprint: Option<String> = None;

    for pair in query.split('&') {
        let (k, v) = pair.split_once('=')?;
        let decoded = percent_decode(v)?;
        match k {
            "v" => version = Some(decoded),
            "name" => name = decoded,
            "ips" => {
                ips = decoded
                    .split(',')
                    .map(|s| s.trim().to_string())
                    .filter(|s| !s.is_empty())
                    .collect();
            }
            "port" => port = decoded.parse::<u16>().ok().filter(|&p| p > 0),
            "token" => token = decoded,
            "tls" => tls = decoded == "1" || decoded.eq_ignore_ascii_case("true"),
            "fp" if !decoded.is_empty() => fingerprint = Some(decoded),
            _ => {}
        }
    }

    if version.as_deref() != Some("1") || !crate::config::is_valid_strong_token(&token) {
        return None;
    }

    Some(PairingQrPayload {
        name,
        ips,
        port: port?,
        token,
        tls,
        fingerprint,
    })
}

/// Returns `(width_in_modules, row_major_dark_flags)` for a QR code.
pub fn get_qr_matrix(data: &str) -> Option<(usize, Vec<bool>)> {
    let code = QrCode::with_error_correction_level(data.as_bytes(), EcLevel::M).ok()?;
    let width = code.width();
    let dark = code
        .to_colors()
        .into_iter()
        .map(|c| c == Color::Dark)
        .collect();
    Some((width, dark))
}

pub fn render_qr_terminal(data: &str) -> Option<String> {
    let code = QrCode::with_error_correction_level(data.as_bytes(), EcLevel::M).ok()?;
    let rendered = code
        .render::<unicode::Dense1x2>()
        .dark_color(unicode::Dense1x2::Light)
        .light_color(unicode::Dense1x2::Dark)
        .quiet_zone(true)
        .build();
    Some(rendered)
}
