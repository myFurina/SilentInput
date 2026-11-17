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
