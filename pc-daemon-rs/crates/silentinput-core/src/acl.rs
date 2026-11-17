use std::net::IpAddr;

use ipnet::IpNet;

use crate::config::SilentInputConfig;

/// Canonicalize IPv4-mapped IPv6 addresses (`::ffff:192.168.1.50` -> `192.168.1.50`).
pub fn normalize_ip_str(raw: &str) -> String {
    let trimmed = raw.trim();
    if let Ok(ip) = trimmed.parse::<IpAddr>() {
        return ip.to_canonical().to_string();
    }
    trimmed.to_string()
}

fn fnmatch_simple(text: &str, pattern: &str) -> bool {
    let t: Vec<char> = text.chars().collect();
    let p: Vec<char> = pattern.chars().collect();
    let (mut ti, mut pi) = (0usize, 0usize);
    let (mut star_pi, mut match_ti) = (None, 0usize);

    while ti < t.len() {
        if pi < p.len() && (p[pi] == '?' || p[pi] == t[ti]) {
            ti += 1;
            pi += 1;
        } else if pi < p.len() && p[pi] == '*' {
            star_pi = Some(pi);
            match_ti = ti;
            pi += 1;
        } else if let Some(sp) = star_pi {
            pi = sp + 1;
            match_ti += 1;
            ti = match_ti;
        } else {
            return false;
        }
    }
    while pi < p.len() && p[pi] == '*' {
        pi += 1;
    }
    pi == p.len()
}

pub fn match_ip_pattern(ip_str: &str, pattern: &str) -> bool {
    let pattern = pattern.trim();
    if pattern.is_empty() {
        return false;
    }
    if matches!(pattern, "*" | "all" | "0.0.0.0/0") {
        return true;
    }

    let normalized_ip = normalize_ip_str(ip_str);
    if pattern.contains('/') {
        if let (Ok(ip), Ok(net)) = (normalized_ip.parse::<IpAddr>(), pattern.parse::<IpNet>()) {
            return net.contains(&ip);
        }
    }
    fnmatch_simple(&normalized_ip, pattern)
}

pub fn is_localhost_ip(client_ip: &str) -> bool {
    let normalized = normalize_ip_str(client_ip);
    matches!(normalized.as_str(), "127.0.0.1" | "::1" | "localhost")
}

pub fn is_ip_allowed(client_ip: &str, config: &SilentInputConfig) -> bool {
    let trimmed = client_ip.trim();
    if trimmed.is_empty() {
        return false;
    }
    if is_localhost_ip(trimmed) {
        return true;
    }

    match config.access_control_mode.as_str() {
        "localhost_only" => false,
        "all" => true,
        "whitelist" => {
            if config.whitelist.is_empty() {
                return false;
            }
            config
                .whitelist
                .iter()
                .any(|pat| match_ip_pattern(trimmed, pat))
        }
        "blacklist" => !config
            .blacklist
            .iter()
            .any(|pat| match_ip_pattern(trimmed, pat)),
        _ => true,
    }
}

pub fn get_bind_addresses(config: &SilentInputConfig) -> Vec<String> {
    if config.access_control_mode == "localhost_only" {
        vec!["127.0.0.1".to_string()]
    } else {
        vec!["0.0.0.0".to_string()]
    }
}
