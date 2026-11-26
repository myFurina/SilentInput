pub mod acl;
pub mod certs;
pub mod config;
pub mod i18n;
pub mod keymap;
pub mod logs;
pub mod pairing;
pub mod paths;
pub mod process;

#[cfg(test)]
mod tests {
    use std::fs;
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener};
    use std::os::unix::fs::PermissionsExt;
    use std::os::unix::net::UnixListener;
    use std::sync::Arc;
    use std::thread;
    use std::time::Duration;

    use crate::acl::{
        find_invalid_ip_patterns, get_bind_addresses, is_ip_allowed, match_ip_pattern,
        validate_ip_pattern,
    };
    use crate::certs::{self, ensure_certificates_at};
    use crate::pairing;
    use crate::config::{
        constant_time_token_eq, generate_pairing_token, generate_token_hex,
        get_token_status_text_at, is_valid_strong_token, load_config_at, STRONG_TOKEN_LEN,
        save_config_at, SilentInputConfig,
    };
    use crate::keymap::{
        all_registered_keys, build_key_map, build_wtype_args, build_xdotool_args,
        clipboard_candidates_for_session, detect_x11_display_in, has_graphical_session_in,
        lookup_char, normalize_line_endings, resolve_key_combination,
    };
    use crate::logs::{
        parse_short_unix_timestamp_secs, read_log_tail_from_file, should_prefer_log_file,
    };
    use crate::paths::SilentInputPaths;
    use crate::process::{
        adb_reverse_list_has_spec, encode_adb_request, find_daemon_pids_in, get_all_host_ips,
        parse_adb_online_serials, query_adb_devices_at, resolve_adb_endpoint_from, AdbEndpoint,
    };

    #[test]
    fn config_dir_and_file_permissions_and_persistence() {
        let tmp = tempfile_dir("cfg_perms");
        let paths = SilentInputPaths::from_dir(&tmp);

        let cfg = load_config_at(&paths);
        assert_eq!(cfg.port, 53318);
        assert_eq!(cfg.auth_token.len(), STRONG_TOKEN_LEN);
        assert!(is_valid_strong_token(&cfg.auth_token));
        assert!(cfg.enable_tls);
        assert!(cfg.allow_usb_unauthenticated);
        assert_eq!(cfg.access_control_mode, "localhost_only");
        assert_eq!(cfg.token_expire_mode, "1h");
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
        assert!(is_valid_strong_token(&loaded.auth_token));
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

        // Default config uses least privilege (localhost_only)
        let default_cfg = SilentInputConfig::default();
        assert_eq!(default_cfg.access_control_mode, "localhost_only");
        assert!(!is_ip_allowed("172.16.0.1", &default_cfg));
        assert!(is_ip_allowed("127.0.0.1", &default_cfg));

        // Whitelist mode CIDR coverage for 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
        let whitelist_cfg = SilentInputConfig {
            access_control_mode: "whitelist".to_string(),
            ..SilentInputConfig::default()
        };
        assert!(is_ip_allowed("172.16.0.1", &whitelist_cfg));
        assert!(is_ip_allowed("172.20.1.5", &whitelist_cfg));
        assert!(is_ip_allowed("172.31.255.254", &whitelist_cfg));
        assert!(!is_ip_allowed("172.15.255.255", &whitelist_cfg));
        assert!(!is_ip_allowed("172.32.0.1", &whitelist_cfg));
    }

    #[test]
    fn token_constant_time_and_status_strings() {
        let legacy = generate_token_hex(4);
        assert_eq!(legacy.len(), 8);
        assert!(!is_valid_strong_token(&legacy));
        assert!(!is_valid_strong_token("550e8400-e29b-41d4-a716-446655440000"));

        let strong = generate_pairing_token();
        assert_eq!(strong.len(), STRONG_TOKEN_LEN);
        assert!(strong.starts_with("si_"));
        assert!(is_valid_strong_token(&strong));
        assert_ne!(strong, generate_pairing_token());

        assert!(constant_time_token_eq("ab12cd34", "ab12cd34"));
        assert!(constant_time_token_eq("  ab12cd34\n", "ab12cd34"));
        assert!(!constant_time_token_eq("", "ab12cd34"));
        assert!(!constant_time_token_eq("ab12cd35", "ab12cd34"));
        assert!(!constant_time_token_eq("short", "longertoken"));

        let tmp = tempfile_dir("pairing_and_certs");
        let paths = SilentInputPaths::from_dir(&tmp);
        ensure_certificates_at(&paths).unwrap();
        let fp = certs::get_cert_sha256_hex_at(&paths).unwrap();
        assert_eq!(fp.len(), 64);
        assert!(fp.chars().all(|c| c.is_ascii_hexdigit()));
        let short_fp = certs::format_short_fingerprint(&fp);
        assert_eq!(short_fp.len(), 19);
        assert!(short_fp.contains(':'));

        let payload = pairing::PairingQrPayload {
            name: "workstation 01".to_string(),
            ips: vec!["192.168.1.10".to_string(), "100.64.0.1".to_string()],
            port: 53318,
            token: strong.clone(),
            tls: true,
            fingerprint: Some(fp.clone()),
        };
        let uri = pairing::build_pairing_uri(&payload);
        let parsed = pairing::parse_pairing_uri(&uri).expect("valid pairing URI");
        assert_eq!(parsed, payload);
        assert!(pairing::parse_pairing_uri(
            "silentinput://pair?v=1&name=pc&ips=192.168.1.2&port=53318&token=ab12cd34&tls=1"
        )
        .is_none());

        let (width, matrix) = pairing::get_qr_matrix(&uri).expect("qr matrix");
        assert!(width >= 21);
        assert_eq!(matrix.len(), width * width);
        assert!(pairing::render_qr_terminal(&uri).is_some());
        let _ = fs::remove_dir_all(&tmp);

        let def_cfg = SilentInputConfig::default();
        assert_eq!(def_cfg.token_expire_mode, "1h");

        let never_cfg = SilentInputConfig {
            token_expire_mode: "never".to_string(),
            ..SilentInputConfig::default()
        };
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

        assert_eq!(
            build_xdotool_args("ctrl+prtsc"),
            Some(vec!["key".into(), "ctrl+Print".into()])
        );
        assert_eq!(
            build_xdotool_args("super+c"),
            Some(vec!["key".into(), "Super_L+c".into()])
        );
        assert!(build_xdotool_args("--help").is_none());
        assert!(build_xdotool_args("ctrl+-M").is_none());

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

        // Live X11 socket triggers graphical detection & resolves DISPLAY even when unset
        let _x_sock = UnixListener::bind(x11_dir.join("X0")).unwrap();
        assert!(has_graphical_session_in(&rt_dir, None, Some(":0"), &x11_dir));
        assert!(has_graphical_session_in(&rt_dir, None, None, &x11_dir));
        assert_eq!(detect_x11_display_in(None, &x11_dir), Some(":0".into()));
        assert_eq!(detect_x11_display_in(Some(":0.0"), &x11_dir), Some(":0.0".into()));

        // Clipboard candidate prioritization
        let x11_only = clipboard_candidates_for_session(false, true);
        assert_eq!(x11_only.iter().map(|(b, _)| *b).collect::<Vec<_>>(), vec!["xclip", "xsel"]);
        let wayland_and_x = clipboard_candidates_for_session(true, true);
        assert_eq!(
            wayland_and_x.iter().map(|(b, _)| *b).collect::<Vec<_>>(),
            vec!["wl-copy", "xclip", "xsel"]
        );

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

    #[test]
    fn logs_tail_limits_to_80_and_source_preference_rules() {
        let tmp = tempfile_dir("logs");
        fs::create_dir_all(&tmp).unwrap();
        let log_path = tmp.join("silentinput.log");

        assert_eq!(read_log_tail_from_file(&log_path, false, 80), "No logs yet");
        let lines: String = (1..=120).map(|i| format!("line-{i}\n")).collect();
        fs::write(&log_path, lines).unwrap();

        let tail = read_log_tail_from_file(&log_path, true, 80);
        assert_eq!(tail.lines().count(), 80);
        assert!(tail.starts_with("line-41"));
        assert!(tail.ends_with("line-120"));

        assert_eq!(
            parse_short_unix_timestamp_secs(
                "1794923266.312627 bluefin silentinput-daemon[4940]: [SilentInput] Ready\n"
            ),
            Some(1794923266.312627)
        );
        assert_eq!(parse_short_unix_timestamp_secs("-- No entries --\n"), None);

        assert!(should_prefer_log_file(Some(200.0), Some(150.0)));
        assert!(!should_prefer_log_file(Some(100.0), Some(150.0)));
        assert!(should_prefer_log_file(Some(100.0), None));
        assert!(!should_prefer_log_file(None, Some(150.0)));
        assert!(!should_prefer_log_file(None, None));

        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn host_ips_are_valid_non_loopback_unique_ipv4() {
        let ips = get_all_host_ips();
        let mut seen = std::collections::HashSet::new();
        for ip in ips {
            let parsed: Ipv4Addr = ip.parse().expect("valid IPv4 string");
            assert!(!parsed.is_loopback());
            assert!(!parsed.is_unspecified());
            assert!(seen.insert(ip));
        }
    }

    #[test]
    fn adb_wire_protocol_and_reverse_exact_match() {
        assert_eq!(encode_adb_request("host:devices"), "000chost:devices");
        assert_eq!(
            encode_adb_request("host:track-devices"),
            "0012host:track-devices"
        );

        let parsed = parse_adb_online_serials(
            "List of devices attached\n1eda8461\tdevice\nemulator-5554\toffline\nunauth1\tunauthorized\n2nddev\tdevice\n1eda8461\tdevice\n",
        );
        assert_eq!(parsed, vec!["1eda8461", "2nddev"]);

        assert!(adb_reverse_list_has_spec(
            "UsbFfs tcp:53318 tcp:53318\n",
            "tcp:53318"
        ));
        assert!(!adb_reverse_list_has_spec(
            "UsbFfs tcp:533180 tcp:533180\n",
            "tcp:53318"
        ));
        assert!(!adb_reverse_list_has_spec("", "tcp:53318"));

        // Mock ADB TCP server
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let srv = thread::spawn(move || {
            let (mut conn, _) = listener.accept().unwrap();
            let mut req = [0u8; 16];
            conn.read_exact(&mut req).unwrap();
            assert_eq!(&req, b"000chost:devices");
            let payload = b"1eda8461\tdevice\nemulator-5554\toffline\n";
            let hdr = format!("OKAY{:04x}", payload.len());
            conn.write_all(hdr.as_bytes()).unwrap();
            conn.write_all(payload).unwrap();
        });
        let found = query_adb_devices_at(addr, Duration::from_millis(500));
        assert_eq!(found, vec!["1eda8461"]);
        srv.join().unwrap();

        // Connection refused returns empty cleanly without spawning adb
        let closed_listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let closed_addr = closed_listener.local_addr().unwrap();
        drop(closed_listener);
        assert!(query_adb_devices_at(closed_addr, Duration::from_millis(200)).is_empty());

        // Endpoint resolution from ANDROID_ADB_SERVER_PORT and ADB_SERVER_SOCKET
        assert_eq!(
            resolve_adb_endpoint_from(None, None),
            AdbEndpoint::LocalTcp(SocketAddr::from((Ipv4Addr::LOCALHOST, 5037)))
        );
        assert_eq!(
            resolve_adb_endpoint_from(Some("5038"), None),
            AdbEndpoint::LocalTcp(SocketAddr::from((Ipv4Addr::LOCALHOST, 5038)))
        );
        assert_eq!(
            resolve_adb_endpoint_from(Some("5038"), Some("tcp:localhost:5040")),
            AdbEndpoint::LocalTcp(SocketAddr::from((Ipv4Addr::LOCALHOST, 5040)))
        );
        assert_eq!(
            resolve_adb_endpoint_from(None, Some("tcp:5041")),
            AdbEndpoint::LocalTcp(SocketAddr::from((Ipv4Addr::LOCALHOST, 5041)))
        );
        assert_eq!(
            resolve_adb_endpoint_from(None, Some("tcp:[::1]:5042")),
            AdbEndpoint::LocalTcp(SocketAddr::from((Ipv6Addr::LOCALHOST, 5042)))
        );
        assert_eq!(
            resolve_adb_endpoint_from(None, Some("localfilesystem:/tmp/adb.sock")),
            AdbEndpoint::UnsupportedSocket
        );
        assert_eq!(
            resolve_adb_endpoint_from(None, Some("tcp:192.168.1.9:5037")),
            AdbEndpoint::UnsupportedSocket
        );
        assert_eq!(
            resolve_adb_endpoint_from(Some("0"), None),
            AdbEndpoint::UnsupportedSocket
        );
    }

    #[test]
    fn validate_ip_pattern_rules() {
        // Valid forms
        assert!(validate_ip_pattern("*").is_ok());
        assert!(validate_ip_pattern("all").is_ok());
        assert!(validate_ip_pattern("0.0.0.0/0").is_ok());
        assert!(validate_ip_pattern("127.0.0.1").is_ok());
        assert!(validate_ip_pattern("::1").is_ok());
        assert!(validate_ip_pattern("192.168.1.10").is_ok());
        assert!(validate_ip_pattern("172.16.0.0/12").is_ok());
        assert!(validate_ip_pattern("10.0.0.0/8").is_ok());
        assert!(validate_ip_pattern("192.168.*").is_ok());
        assert!(validate_ip_pattern("10.*").is_ok());
        assert!(validate_ip_pattern("10.17.*").is_ok());
        assert!(validate_ip_pattern("192.168.1.*").is_ok());

        // Invalid forms
        assert!(validate_ip_pattern("").is_err());
        assert!(validate_ip_pattern("   ").is_err());
        assert!(validate_ip_pattern("abc").is_err());
        assert!(validate_ip_pattern("192.168.1.1/99").is_err());
        assert!(validate_ip_pattern("999.999.999.999").is_err());
        assert!(validate_ip_pattern("192.*.1").is_err());
        assert!(validate_ip_pattern("*.168.1").is_err());

        // find_invalid_ip_patterns
        let list = vec![
            "127.0.0.1".to_string(),
            "abc".to_string(),
            "192.168.*".to_string(),
            "172.16.0.0/99".to_string(),
        ];
        let invalid = find_invalid_ip_patterns(&list);
        assert_eq!(invalid.len(), 2);
        assert_eq!(invalid[0].0, "abc");
        assert_eq!(invalid[1].0, "172.16.0.0/99");
    }

    fn tempfile_dir(tag: &str) -> std::path::PathBuf {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("silentinput_test_{tag}_{}_{nanos}", std::process::id()))
    }
}
