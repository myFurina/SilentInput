use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;

use rcgen::{date_time_ymd, CertificateParams, DnType, KeyPair};

use crate::config::{atomic_write_private_file, ensure_config_dir_at};
use crate::paths::SilentInputPaths;

fn civil_from_days(days_since_unix: i64) -> (i32, u8, u8) {
    // Howard Hinnant's civil_from_days algorithm (UTC calendar date)
    let z = days_since_unix + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u8;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u8;
    let year = (y + if m <= 2 { 1 } else { 0 }) as i32;
    (year, m, d)
}

/// Ensure self-signed TLS certificate (`cert.pem`) and private key (`key.pem`) exist.
/// Existing certificates are never overwritten so Android TOFU fingerprints stay intact.
pub fn ensure_certificates_at(paths: &SilentInputPaths) -> Result<(PathBuf, PathBuf), String> {
    ensure_config_dir_at(paths).map_err(|e| format!("Failed to prepare config dir: {e}"))?;
    let cert_path = paths.cert_file();
    let key_path = paths.key_file();

    let cert_exists = cert_path
        .metadata()
        .map(|m| m.is_file() && m.len() > 0)
        .unwrap_or(false);
    let key_exists = key_path
        .metadata()
        .map(|m| m.is_file() && m.len() > 0)
        .unwrap_or(false);

    if cert_exists && key_exists {
        let _ = fs::set_permissions(&key_path, fs::Permissions::from_mode(0o600));
        return Ok((cert_path, key_path));
    }

    let now_days = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| (d.as_secs() / 86_400) as i64)
        .unwrap_or(19_723);
    let (start_y, start_m, start_d) = civil_from_days(now_days.saturating_sub(1));
    let (end_y, end_m, end_d) = civil_from_days(now_days + 3650);

    let mut params = CertificateParams::new(vec!["SilentInput".to_string()])
        .map_err(|e| format!("Failed to initialize cert params: {e}"))?;
    params
        .distinguished_name
        .push(DnType::CommonName, "SilentInput");
    params.not_before = date_time_ymd(start_y, start_m, start_d);
    params.not_after = date_time_ymd(end_y, end_m, end_d);

    let key_pair = KeyPair::generate().map_err(|e| format!("Failed to generate keypair: {e}"))?;
    let cert = params
        .self_signed(&key_pair)
        .map_err(|e| format!("Failed to sign certificate: {e}"))?;

    let cert_pem = cert.pem();
    let key_pem = key_pair.serialize_pem();

    atomic_write_private_file(&paths.config_dir, &key_path, key_pem.as_bytes())
        .map_err(|e| format!("Failed to write private key: {e}"))?;
    atomic_write_private_file(&paths.config_dir, &cert_path, cert_pem.as_bytes())
        .map_err(|e| format!("Failed to write certificate: {e}"))?;

    println!("[SilentInput] Generated new TLS certificate: {}", cert_path.display());
    Ok((cert_path, key_path))
}

pub fn ensure_certificates() -> Result<(PathBuf, PathBuf), String> {
    ensure_certificates_at(&SilentInputPaths::system())
}

fn decode_base64_std(input: &str) -> Option<Vec<u8>> {
    let clean: Vec<u8> = input
        .bytes()
        .filter(|b| !b.is_ascii_whitespace())
        .collect();
    if clean.is_empty() || !clean.len().is_multiple_of(4) {
        return None;
    }
    let val = |c: u8| -> Option<u32> {
        match c {
            b'A'..=b'Z' => Some((c - b'A') as u32),
            b'a'..=b'z' => Some((c - b'a' + 26) as u32),
            b'0'..=b'9' => Some((c - b'0' + 52) as u32),
            b'+' => Some(62),
            b'/' => Some(63),
            _ => None,
        }
    };
    let mut out = Vec::with_capacity((clean.len() / 4) * 3);
    for (idx, chunk) in clean.chunks(4).enumerate() {
        let is_last = (idx + 1) * 4 == clean.len();
        let a = val(chunk[0])?;
        let b = val(chunk[1])?;
        let pad2 = chunk[2] == b'=';
        let pad3 = chunk[3] == b'=';
        if (pad2 || pad3) && !is_last {
            return None;
        }
        if pad2 && !pad3 {
            return None;
        }
        let c = if pad2 { 0 } else { val(chunk[2])? };
        let d = if pad3 { 0 } else { val(chunk[3])? };
        let t = (a << 18) | (b << 12) | (c << 6) | d;
        out.push(((t >> 16) & 0xff) as u8);
        if !pad2 {
            out.push(((t >> 8) & 0xff) as u8);
        }
        if !pad3 {
            out.push((t & 0xff) as u8);
        }
    }
    Some(out)
}

pub fn cert_pem_sha256_hex(pem_text: &str) -> Option<String> {
    let mut in_cert = false;
    let mut b64 = String::new();
    for line in pem_text.lines() {
        let trimmed = line.trim();
        if trimmed == "-----BEGIN CERTIFICATE-----" {
            in_cert = true;
            continue;
        }
        if trimmed == "-----END CERTIFICATE-----" {
            break;
        }
        if in_cert {
            b64.push_str(trimmed);
        }
    }
    if b64.is_empty() {
        return None;
    }
    let der = decode_base64_std(&b64)?;
    let digest = ring::digest::digest(&ring::digest::SHA256, &der);
    let mut hex = String::with_capacity(64);
    for byte in digest.as_ref() {
        hex.push_str(&format!("{byte:02x}"));
    }
    Some(hex)
}

pub fn get_cert_sha256_hex_at(paths: &SilentInputPaths) -> Option<String> {
    let pem = fs::read_to_string(paths.cert_file()).ok()?;
    cert_pem_sha256_hex(&pem)
}

pub fn format_short_fingerprint(hex: &str) -> String {
    let clean: String = hex
        .trim()
        .chars()
        .filter(|c| c.is_ascii_hexdigit())
        .take(16)
        .collect::<String>()
        .to_ascii_uppercase();
    if clean.is_empty() {
        return String::new();
    }
    clean
        .as_bytes()
        .chunks(4)
        .map(|c| std::str::from_utf8(c).unwrap_or(""))
        .collect::<Vec<_>>()
        .join(":")
}
