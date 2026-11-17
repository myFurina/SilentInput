pub mod acl;
pub mod certs;
pub mod config;
pub mod i18n;
pub mod keymap;
pub mod paths;
pub mod process;

#[cfg(test)]
mod tests {
    use std::fs;
    use std::os::unix::fs::PermissionsExt;
    use std::os::unix::net::UnixListener;
    use std::sync::Arc;
    use std::thread;

    use crate::acl::{get_bind_addresses, is_ip_allowed, match_ip_pattern};
    use crate::certs::ensure_certificates_at;
    use crate::config::{
        constant_time_token_eq, generate_token_hex, get_token_status_text_at, load_config_at,
        save_config_at, SilentInputConfig,
    };
    use crate::keymap::{
        all_registered_keys, build_key_map, build_wtype_args, has_graphical_session_in,
        lookup_char, normalize_line_endings, resolve_key_combination,
    };
    use crate::paths::SilentInputPaths;
    use crate::process::find_daemon_pids_in;

    #[test]
    fn config_dir_and_file_permissions_and_persistence() {
        let tmp = tempfile_dir("cfg_perms");
        let paths = SilentInputPaths::from_dir(&tmp);

        let cfg = load_config_at(&paths);
        assert_eq!(cfg.port, 53318);
        assert_eq!(cfg.auth_token.len(), 8);
        assert!(cfg.enable_tls);
        assert!(cfg.allow_usb_unauthenticated);
        assert_eq!(cfg.access_control_mode, "whitelist");
        assert_eq!(cfg.gui_language, "system");
        assert_eq!(cfg.gui_theme, "system");

        let dir_mode = fs::metadata(&paths.config_dir)
            .unwrap()
            .permissions()
            .mode()
            & 0o777;
        assert_eq!(dir_mode, 0o700);

        let file_mode = fs::metadata(paths.config_file())
            .unwrap()
            .permissions()
            .mode()
            & 0o777;
        assert_eq!(file_mode, 0o600);

        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn multithreaded_save_config_safe() {
        let tmp = tempfile_dir("cfg_threads");
        let paths = Arc::new(SilentInputPaths::from_dir(&tmp));
        let mut handles = Vec::new();
        for i in 0..8 {
            let p = Arc::clone(&paths);
            handles.push(thread::spawn(move || {
                for j in 0..10 {
                    let cfg = SilentInputConfig {
                        port: 53318,
                        auth_token: format!("t{:02x}{:02x}0000", i, j),
                        ..SilentInputConfig::default()
                    };
                    save_config_at(&p, &cfg).unwrap();
                }
            }));
        }
        for h in handles {
            h.join().unwrap();
        }
        let loaded = load_config_at(&paths);
        assert_eq!(loaded.port, 53318);
        let leftovers: Vec<_> = fs::read_dir(&paths.config_dir)
            .unwrap()
            .flatten()
            .filter(|e| e.file_name().to_string_lossy().starts_with(".tmp."))
            .collect();
        assert!(leftovers.is_empty());
        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn certs_preserve_existing_and_generate_new() {
        let tmp = tempfile_dir("certs");
        let paths = SilentInputPaths::from_dir(&tmp);
        let (cert, key) = ensure_certificates_at(&paths).unwrap();
        assert!(cert.exists());
        assert!(key.exists());

        let cert_content = fs::read_to_string(&cert).unwrap();
        assert!(cert_content.contains("BEGIN CERTIFICATE"));
        let key_mode = fs::metadata(&key).unwrap().permissions().mode() & 0o777;
        assert_eq!(key_mode, 0o600);

        // Must never overwrite existing certs (TOFU guarantee)
        let (cert2, _) = ensure_certificates_at(&paths).unwrap();
        assert_eq!(fs::read_to_string(cert2).unwrap(), cert_content);
        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn acl_empty_whitelist_denies_remote_allows_localhost() {
        let cfg = SilentInputConfig {
            access_control_mode: "whitelist".to_string(),
            whitelist: Vec::new(),
            ..SilentInputConfig::default()
        };
        assert!(!is_ip_allowed("192.168.1.100", &cfg));
        assert!(!is_ip_allowed("::ffff:192.168.1.100", &cfg));
        assert!(is_ip_allowed("127.0.0.1", &cfg));
        assert!(is_ip_allowed("::1", &cfg));
        assert!(is_ip_allowed("::ffff:127.0.0.1", &cfg));

        let cidr_cfg = SilentInputConfig {
            access_control_mode: "whitelist".to_string(),
            whitelist: vec!["100.64.0.0/10".to_string(), "192.168.*".to_string()],
            ..SilentInputConfig::default()
        };
        assert!(is_ip_allowed("100.86.221.32", &cidr_cfg));
        assert!(is_ip_allowed("192.168.10.22", &cidr_cfg));
        assert!(!is_ip_allowed("172.20.1.5", &cidr_cfg));
        assert!(match_ip_pattern("192.168.1.5", "*"));

        let usb_only = SilentInputConfig {
            access_control_mode: "localhost_only".to_string(),
            ..SilentInputConfig::default()
        };
        assert_eq!(get_bind_addresses(&usb_only), vec!["127.0.0.1"]);
        assert!(!is_ip_allowed("192.168.1.2", &usb_only));
        assert!(is_ip_allowed("127.0.0.1", &usb_only));
    }

    #[test]
    fn token_constant_time_and_status_strings() {
        let tok = generate_token_hex(4);
        assert_eq!(tok.len(), 8);
        assert!(constant_time_token_eq("ab12cd34", "ab12cd34"));
        assert!(constant_time_token_eq("  ab12cd34\n", "ab12cd34"));
        assert!(!constant_time_token_eq("", "ab12cd34"));
        assert!(!constant_time_token_eq("ab12cd35", "ab12cd34"));
        assert!(!constant_time_token_eq("short", "longertoken"));

        let never_cfg = SilentInputConfig::default();
        assert_eq!(get_token_status_text_at(&never_cfg, false, 1000.0), "Never expires");
        assert_eq!(get_token_status_text_at(&never_cfg, true, 1000.0), "永不过期");

        let mut h1 = SilentInputConfig {
            token_expire_mode: "1h".to_string(),
            token_created_at: 4900.0,
            ..SilentInputConfig::default()
        };
        assert_eq!(get_token_status_text_at(&h1, false, 5000.0), "1h (58m left)");
        h1.token_created_at = 100.0;
        assert_eq!(
            get_token_status_text_at(&h1, false, 5000.0),
            "1h (expired, rotates on next connect)"
        );
    }

    #[test]
    fn keymap_strict_combos_and_wtype_validation_and_crlf() {
        let km = build_key_map();
        assert_eq!(km.get("prtsc"), km.get("sysrq"));
        assert_eq!(km.get("["), km.get("lbracket"));
        assert_eq!(km.get("`"), km.get("grave"));
        assert!(!all_registered_keys().is_empty());
        assert!(lookup_char('A').unwrap().1);
        assert!(!lookup_char('\n').unwrap().1);

        // Partial invalid segment must reject the whole combination (no dangling Ctrl press)
        assert!(resolve_key_combination("ctrl+c").is_some());
        assert!(resolve_key_combination("ctrl+not_a_key").is_none());
        assert!(resolve_key_combination("bad;cmd").is_none());

        // wtype whitelist validation
        assert_eq!(
            build_wtype_args("ctrl+prtsc"),
            Some(vec!["-M".into(), "ctrl".into(), "-k".into(), "Print".into()])
        );
        assert_eq!(
            build_wtype_args("ctrl+["),
            Some(vec![
                "-M".into(),
                "ctrl".into(),
                "-k".into(),
                "bracketleft".into()
            ])
        );
        assert!(build_wtype_args("--help").is_none());
        assert!(build_wtype_args("ctrl+-M").is_none());
        assert!(build_wtype_args("bad;cmd").is_none());
        assert!(build_wtype_args("unknownlongkey").is_none());

        // CRLF normalization
        assert_eq!(normalize_line_endings("a\r\nb\rc\n"), "a\nb\nc\n");
    }

    #[test]
    fn graphical_session_detection_wayland_and_headless() {
        let tmp = tempfile_dir("gfx");
        let rt_dir = tmp.join("runtime");
        let x11_dir = tmp.join("x11");
        fs::create_dir_all(&rt_dir).unwrap();
        fs::create_dir_all(&x11_dir).unwrap();

        // Lock file alone does not indicate graphical session
        fs::write(rt_dir.join("wayland-0.lock"), b"").unwrap();
        assert!(!has_graphical_session_in(&rt_dir, None, Some(":99"), &x11_dir));

        // Live Unix socket triggers graphical detection
        let _sock = UnixListener::bind(rt_dir.join("wayland-0")).unwrap();
        assert!(has_graphical_session_in(&rt_dir, None, None, &x11_dir));
        assert!(has_graphical_session_in(&rt_dir, Some("wayland-0"), None, &x11_dir));

        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn find_daemon_pids_matches_exact_binary_only() {
        let tmp = tempfile_dir("proc");
        let p101 = tmp.join("101");
        let p102 = tmp.join("102");
        let p103 = tmp.join("103");
        fs::create_dir_all(&p101).unwrap();
        fs::create_dir_all(&p102).unwrap();
        fs::create_dir_all(&p103).unwrap();

        fs::write(p101.join("cmdline"), b"/usr/bin/silentinput-daemon\x00").unwrap();
        fs::write(
            p102.join("cmdline"),
            b"/usr/bin/tail\x00-f\x00/home/user/.config/silentinput/silentinput.log\x00",
        )
        .unwrap();
        fs::write(
            p103.join("cmdline"),
            b"/usr/bin/python3\x00-u\x00/usr/lib/silentinput/silentinput-daemon.py\x00",
        )
        .unwrap();

        let pids = find_daemon_pids_in(&tmp, 99999);
        assert_eq!(pids, vec![101, 103]);
        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn config_backward_compat_defaults_gui_theme_to_system() {
        let legacy_json = r#"{
            "port": 53318,
            "auth_token": "abcd1234",
            "gui_language": "zh"
        }"#;
        let cfg: SilentInputConfig = serde_json::from_str(legacy_json).unwrap();
        assert_eq!(cfg.gui_language, "zh");
        assert_eq!(cfg.gui_theme, "system");
    }

    fn tempfile_dir(tag: &str) -> std::path::PathBuf {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("silentinput_test_{tag}_{}_{nanos}", std::process::id()))
    }
}
