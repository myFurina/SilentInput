mod helpers;
mod i18n;
mod icons;
mod style;

use std::process::Command;
use std::time::Duration;

use iced::keyboard;
use iced::theme::Mode;
use iced::widget::{
    button, column, container, opaque, operation, pick_list, row, scrollable, space, text,
    text_input, toggler, Id,
};
use iced::{window, Alignment, Element, Font, Length, Size, Subscription, Task, Theme};
use silentinput_core::acl::find_invalid_ip_patterns;
use silentinput_core::certs::format_short_fingerprint;
use silentinput_core::config::{
    generate_pairing_token, get_token_status_text, load_config_at, regenerate_token_at,
    save_config_at, unix_timestamp_secs, SilentInputConfig,
};
use silentinput_core::i18n::{resolve_gui_is_zh, t};
use silentinput_core::logs::read_log_tail_at;
use silentinput_core::pairing::{build_pairing_payload_at, build_pairing_uri, get_qr_matrix};
use silentinput_core::paths::SilentInputPaths;
use silentinput_core::process::{
    get_all_host_ips, get_connected_usb_devices_if_server_running, get_package_uninstall_command,
    is_autostart_enabled_at, is_daemon_running_with_config, is_system_package_install,
    service_restart_at, service_start_at, service_stop_at, set_autostart_enabled_at,
};

use crate::helpers::{
    detect_cjk_font_family, detect_cjk_mono_font_family, format_ip_list, parse_ip_list,
    perform_user_uninstall,
};
use crate::i18n::{
    acl_mode_options, expire_options, find_option, language_options, theme_options, PickOption,
};
use crate::icons::{icon, status_dot, IconKind};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
struct ProbeOptions {
    include_autostart: bool,
}

#[derive(Debug, Clone)]
struct ProbeSnapshot {
    gen_before: u64,
    service_gen_before: u64,
    autostart_gen_before: u64,
    config: SilentInputConfig,
    running: bool,
    pid: i32,
    autostart: Option<bool>,
    usb_devices: Vec<String>,
    host_ips: Vec<String>,
}

#[derive(Debug, Clone)]
struct QrPairingData {
    name: String,
    ips: Vec<String>,
    port: u16,
    tls: bool,
    fingerprint_short: Option<String>,
    uri: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
enum ActivePage {
    #[default]
    Home,
    Settings,
}

#[derive(Debug, Clone)]
enum ModalView {
    QrPairing(QrPairingData),
    Help,
    ResetConfigConfirm,
    Logs(String),
    UninstallPackage(String),
    UninstallUserConfirm,
}

#[derive(Debug, Clone)]
enum Message {
    BootProbe,
    TickProbe,
    ProbeCompleted(Box<ProbeSnapshot>),
    ProbeFailed,
    WindowFocused,
    WindowUnfocused,
    OpenHome,
    OpenSettings,
    ToggleAdvancedSettings,
    ToggleService(bool),
    ServiceActionFinished(bool, String),
    ToggleAutostart(bool),
    AutostartActionFinished(bool, String),
    ToggleShowKey,
    CopyToken,
    RegenerateToken,
    #[cfg_attr(not(test), allow(dead_code))]
    OpenQrPairing,
    ExpireModeSelected(PickOption),
    ToggleUsbNoAuth(bool),
    ToggleTls(bool),
    CopyIp(String),
    AclModeSelected(PickOption),
    WhitelistChanged(String),
    BlacklistChanged(String),
    FlushAclSave(u64),
    LanguageSelected(PickOption),
    ThemeSelected(PickOption),
    SystemThemeChanged(Mode),
    RestartService,
    RestartActionFinished,
    OpenHelp,
    OpenResetConfig,
    ConfirmResetConfig,
    OpenLogs,
    RefreshLogs,
    LogsLoaded(u64, String),
    CloseModal,
    OpenUninstall,
    StopServiceAndQuit,
    ConfirmUserUninstall,
    FinishedAndQuit,
    DismissToast(u64),
    WindowCloseRequested(window::Id),
}

struct ControlCenterState {
    paths: SilentInputPaths,
    config: SilentInputConfig,
    is_zh: bool,
    system_dark: bool,
    window_focused: bool,
    mono_font: Font,
    page: ActivePage,
    advanced_expanded: bool,
    show_key: bool,
    running: bool,
    pid: i32,
    autostart: bool,
    usb_devices: Vec<String>,
    host_ips: Vec<String>,
    whitelist_input: String,
    blacklist_input: String,
    config_generation: u64,
    pending_acl_save: bool,
    acl_save_seq: u64,
    service_action_count: u32,
    autostart_action_count: u32,
    service_gen: u64,
    autostart_gen: u64,
    probe_in_flight: bool,
    queued_probe: Option<ProbeOptions>,
    toast: Option<String>,
    toast_seq: u64,
    modal: Option<ModalView>,
    log_scroll_id: Id,
    logs_seq: u64,
    logs_loading: bool,
    uninstall_in_flight: bool,
}

impl ControlCenterState {
    fn is_dark(&self) -> bool {
        match self.config.gui_theme.as_str() {
            "dark" => true,
            "light" => false,
            _ => self.system_dark,
        }
    }

    fn build_qr_data(&self) -> QrPairingData {
        let payload = build_pairing_payload_at(&self.paths, &self.config);
        let uri = build_pairing_uri(&payload);
        let fingerprint_short = payload
            .fingerprint
            .as_deref()
            .map(format_short_fingerprint);
        QrPairingData {
            name: payload.name,
            ips: payload.ips,
            port: payload.port,
            tls: payload.tls,
            fingerprint_short,
            uri,
        }
    }

    fn new_with_paths(paths: SilentInputPaths) -> (Self, Task<Message>) {
        let config = load_config_at(&paths);
        let is_zh = resolve_gui_is_zh(&config.gui_language);
        let whitelist_input = format_ip_list(&config.whitelist);
        let blacklist_input = format_ip_list(&config.blacklist);
        let mono_font = detect_cjk_mono_font_family()
            .map(Font::with_name)
            .unwrap_or(Font::MONOSPACE);
        let state = Self {
            paths,
            config,
            is_zh,
            system_dark: false,
            window_focused: true,
            mono_font,
            page: ActivePage::Home,
            advanced_expanded: false,
            show_key: false,
            running: false,
            pid: 0,
            autostart: false,
            usb_devices: Vec::new(),
            host_ips: Vec::new(),
            whitelist_input,
            blacklist_input,
            config_generation: 0,
            pending_acl_save: false,
            acl_save_seq: 0,
            service_action_count: 0,
            autostart_action_count: 0,
            service_gen: 0,
            autostart_gen: 0,
            probe_in_flight: false,
            queued_probe: None,
            toast: None,
            toast_seq: 0,
            modal: None,
            log_scroll_id: Id::unique(),
            logs_seq: 0,
            logs_loading: false,
            uninstall_in_flight: false,
        };
        (
            state,
            Task::batch([
                Task::done(Message::BootProbe),
                iced::system::theme().map(Message::SystemThemeChanged),
            ]),
        )
    }

    fn load_logs_async(&mut self) -> Task<Message> {
        self.logs_seq = self.logs_seq.saturating_add(1);
        self.logs_loading = true;
        let seq = self.logs_seq;
        let paths = self.paths.clone();
        let is_zh = self.is_zh;
        Task::perform(
            async move { tokio::task::spawn_blocking(move || read_log_tail_at(&paths, is_zh, 80)).await },
            move |res| match res {
                Ok(text) => Message::LogsLoaded(seq, text),
                Err(_) => Message::LogsLoaded(
                    seq,
                    t(is_zh, "读取日志失败", "Failed to read logs").to_string(),
                ),
            },
        )
    }

    fn show_toast(&mut self, msg: impl Into<String>) -> Task<Message> {
        self.toast = Some(msg.into());
        self.toast_seq = self.toast_seq.saturating_add(1);
        let seq = self.toast_seq;
        Task::perform(
            async move {
                tokio::time::sleep(Duration::from_secs(3)).await;
                seq
            },
            Message::DismissToast,
        )
    }

    fn request_probe(&mut self, opts: ProbeOptions) -> Task<Message> {
        if self.probe_in_flight {
            let queued = self.queued_probe.get_or_insert(ProbeOptions::default());
            queued.include_autostart |= opts.include_autostart;
            return Task::none();
        }
        self.run_probe_with(opts)
    }

    fn run_probe_with(&mut self, opts: ProbeOptions) -> Task<Message> {
        self.probe_in_flight = true;
        let paths = self.paths.clone();
        let gen_before = self.config_generation;
        let service_gen_before = self.service_gen;
        let autostart_gen_before = self.autostart_gen;
        Task::perform(
            async move {
                tokio::task::spawn_blocking(move || {
                    let cfg = load_config_at(&paths);
                    let (running, pid) = is_daemon_running_with_config(&paths, &cfg);
                    let autostart = opts
                        .include_autostart
                        .then(|| is_autostart_enabled_at(&paths));
                    let usb_devices = get_connected_usb_devices_if_server_running();
                    let host_ips = get_all_host_ips();
                    ProbeSnapshot {
                        gen_before,
                        service_gen_before,
                        autostart_gen_before,
                        config: cfg,
                        running,
                        pid,
                        autostart,
                        usb_devices,
                        host_ips,
                    }
                })
                .await
            },
            |res| match res {
                Ok(snap) => Message::ProbeCompleted(Box::new(snap)),
                Err(_) => Message::ProbeFailed,
            },
        )
    }

    fn flush_acl_if_pending(&mut self) {
        if self.pending_acl_save {
            self.pending_acl_save = false;
            self.config.whitelist = parse_ip_list(&self.whitelist_input);
            self.config.blacklist = parse_ip_list(&self.blacklist_input);
            let _ = save_config_at(&self.paths, &self.config);
        }
    }

    fn update(&mut self, message: Message) -> Task<Message> {
        match message {
            Message::BootProbe => self.request_probe(ProbeOptions {
                include_autostart: true,
            }),
            Message::TickProbe => self.request_probe(ProbeOptions {
                include_autostart: false,
            }),
            Message::ProbeFailed => {
                self.probe_in_flight = false;
                if let Some(queued) = self.queued_probe.take() {
                    return self.run_probe_with(queued);
                }
                Task::none()
            }
            Message::WindowFocused => {
                let was_unfocused = !self.window_focused;
                self.window_focused = true;
                if was_unfocused {
                    self.request_probe(ProbeOptions {
                        include_autostart: true,
                    })
                } else {
                    Task::none()
                }
            }
            Message::WindowUnfocused => {
                self.window_focused = false;
                Task::none()
            }
            Message::OpenHome => {
                self.flush_acl_if_pending();
                self.page = ActivePage::Home;
                Task::none()
            }
            Message::OpenSettings => {
                self.page = ActivePage::Settings;
                Task::none()
            }
            Message::ToggleAdvancedSettings => {
                self.advanced_expanded = !self.advanced_expanded;
                Task::none()
            }
            Message::ProbeCompleted(snap) => {
                self.probe_in_flight = false;
                if snap.gen_before == self.config_generation && !self.pending_acl_save {
                    self.config = snap.config;
                    self.is_zh = resolve_gui_is_zh(&self.config.gui_language);
                    self.whitelist_input = format_ip_list(&self.config.whitelist);
                    self.blacklist_input = format_ip_list(&self.config.blacklist);
                }
                if self.service_action_count == 0
                    && snap.service_gen_before == self.service_gen
                {
                    self.running = snap.running;
                    self.pid = snap.pid;
                }
                if let Some(autostart) = snap.autostart {
                    if self.autostart_action_count == 0
                        && snap.autostart_gen_before == self.autostart_gen
                    {
                        self.autostart = autostart;
                    }
                }
                self.usb_devices = snap.usb_devices;
                self.host_ips = snap.host_ips;

                if let Some(queued) = self.queued_probe.take() {
                    return self.run_probe_with(queued);
                }
                Task::none()
            }
            Message::ToggleService(enable) => {
                if self.service_action_count > 0 {
                    return Task::none();
                }
                self.service_action_count += 1;
                self.service_gen = self.service_gen.saturating_add(1);
                self.running = enable;
                let paths = self.paths.clone();
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            if enable {
                                service_start_at(&paths)
                            } else {
                                service_stop_at(&paths)
                            }
                        })
                        .await
                    },
                    |res| match res {
                        Ok((ok, msg)) => Message::ServiceActionFinished(ok, msg),
                        Err(_) => Message::ServiceActionFinished(false, "Worker failed".to_string()),
                    },
                )
            }
            Message::ServiceActionFinished(ok, msg) => {
                self.service_action_count = self.service_action_count.saturating_sub(1);
                let toast_task = if !ok && !msg.is_empty() {
                    self.show_toast(msg)
                } else {
                    Task::none()
                };
                Task::batch([
                    toast_task,
                    self.request_probe(ProbeOptions {
                        include_autostart: false,
                    }),
                ])
            }
            Message::ToggleAutostart(enable) => {
                if self.autostart_action_count > 0 {
                    return Task::none();
                }
                self.autostart_action_count += 1;
                self.autostart_gen = self.autostart_gen.saturating_add(1);
                self.autostart = enable;
                let paths = self.paths.clone();
                let is_zh = self.is_zh;
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            set_autostart_enabled_at(&paths, enable, is_zh)
                        })
                        .await
                    },
                    |res| match res {
                        Ok((ok, msg)) => Message::AutostartActionFinished(ok, msg),
                        Err(_) => Message::AutostartActionFinished(false, String::new()),
                    },
                )
            }
            Message::AutostartActionFinished(_ok, msg) => {
                self.autostart_action_count = self.autostart_action_count.saturating_sub(1);
                let mut tasks = Vec::new();
                if !msg.is_empty() {
                    tasks.push(self.show_toast(msg));
                }
                tasks.push(self.request_probe(ProbeOptions {
                    include_autostart: true,
                }));
                Task::batch(tasks)
            }
            Message::CopyToken => {
                let tok = self.config.auth_token.clone();
                if tok.is_empty() {
                    return Task::none();
                }
                let toast = self.show_toast(t(self.is_zh, "配对密钥已复制", "Pairing key copied"));
                Task::batch([iced::clipboard::write(tok), toast])
            }
            Message::ToggleShowKey => {
                self.show_key = !self.show_key;
                Task::none()
            }
            Message::RegenerateToken => {
                self.config_generation += 1;
                let new_tok = regenerate_token_at(&self.paths);
                self.config = load_config_at(&self.paths);
                if matches!(self.modal, Some(ModalView::QrPairing(_))) {
                    self.modal = Some(ModalView::QrPairing(self.build_qr_data()));
                }
                let msg = if self.is_zh {
                    format!("已生成新密钥: {}...", new_tok.chars().take(12).collect::<String>())
                } else {
                    format!("New key generated: {}...", new_tok.chars().take(12).collect::<String>())
                };
                self.show_toast(msg)
            }
            Message::OpenQrPairing => {
                self.modal = Some(ModalView::QrPairing(self.build_qr_data()));
                Task::none()
            }
            Message::ExpireModeSelected(opt) => {
                self.config_generation += 1;
                self.config.token_expire_mode = opt.key.to_string();
                self.config.token_created_at = unix_timestamp_secs();
                let _ = save_config_at(&self.paths, &self.config);
                let msg = if self.is_zh {
                    format!("有效期已设为: {}", opt.label)
                } else {
                    format!("Expiration set to: {}", opt.label)
                };
                self.show_toast(msg)
            }
            Message::ToggleUsbNoAuth(enable) => {
                self.config_generation += 1;
                self.config.allow_usb_unauthenticated = enable;
                let _ = save_config_at(&self.paths, &self.config);
                Task::none()
            }
            Message::ToggleTls(enable) => {
                if self.service_action_count > 0 {
                    return Task::none();
                }
                self.config_generation += 1;
                self.config.enable_tls = enable;
                let _ = save_config_at(&self.paths, &self.config);
                let toast = self.show_toast(t(
                    self.is_zh,
                    "已更新加密设置，正在重启服务...",
                    "TLS setting updated, restarting service...",
                ));
                Task::batch([toast, Task::done(Message::RestartService)])
            }
            Message::CopyIp(ip) => {
                let msg = if self.is_zh {
                    format!("已复制 IP: {ip}")
                } else {
                    format!("Copied IP: {ip}")
                };
                Task::batch([iced::clipboard::write(ip), self.show_toast(msg)])
            }
            Message::AclModeSelected(opt) => {
                let was_localhost = self.config.access_control_mode == "localhost_only";
                let now_localhost = opt.key == "localhost_only";
                self.config_generation += 1;
                self.config.access_control_mode = opt.key.to_string();
                let _ = save_config_at(&self.paths, &self.config);
                if was_localhost != now_localhost && self.service_action_count == 0 {
                    let toast = self.show_toast(t(
                        self.is_zh,
                        "控制模式已更新，正在重启服务...",
                        "Access control mode updated, restarting service...",
                    ));
                    Task::batch([toast, Task::done(Message::RestartService)])
                } else {
                    self.show_toast(t(self.is_zh, "控制模式已更新", "Access control mode updated"))
                }
            }
            Message::WhitelistChanged(val) => {
                self.whitelist_input = val;
                self.config.whitelist = parse_ip_list(&self.whitelist_input);
                self.schedule_acl_debounce()
            }
            Message::BlacklistChanged(val) => {
                self.blacklist_input = val;
                self.config.blacklist = parse_ip_list(&self.blacklist_input);
                self.schedule_acl_debounce()
            }
            Message::FlushAclSave(seq) => {
                if seq == self.acl_save_seq && self.pending_acl_save {
                    self.flush_acl_if_pending();
                }
                Task::none()
            }
            Message::LanguageSelected(opt) => {
                if opt.key == self.config.gui_language {
                    return Task::none();
                }
                self.config_generation += 1;
                self.config.gui_language = opt.key.to_string();
                let _ = save_config_at(&self.paths, &self.config);
                self.is_zh = resolve_gui_is_zh(&self.config.gui_language);
                self.show_toast(t(
                    self.is_zh,
                    "界面语言已切换",
                    "Interface language updated",
                ))
            }
            Message::ThemeSelected(opt) => {
                if opt.key == self.config.gui_theme {
                    return Task::none();
                }
                self.config_generation += 1;
                self.config.gui_theme = opt.key.to_string();
                let _ = save_config_at(&self.paths, &self.config);
                self.show_toast(t(
                    self.is_zh,
                    "界面外观已切换",
                    "Appearance theme updated",
                ))
            }
            Message::SystemThemeChanged(mode) => {
                self.system_dark = matches!(mode, Mode::Dark);
                Task::none()
            }
            Message::RestartService => {
                if self.service_action_count > 0 {
                    return Task::none();
                }
                self.service_action_count += 1;
                self.service_gen = self.service_gen.saturating_add(1);
                let paths = self.paths.clone();
                let toast = self.show_toast(t(self.is_zh, "服务正在重启...", "Restarting service..."));
                let restart_task = Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            let _ = service_restart_at(&paths);
                        })
                        .await
                    },
                    |_| Message::RestartActionFinished,
                );
                Task::batch([toast, restart_task])
            }
            Message::RestartActionFinished => {
                self.service_action_count = self.service_action_count.saturating_sub(1);
                let toast = self.show_toast(t(self.is_zh, "服务已重启", "Service restarted"));
                Task::batch([
                    toast,
                    self.request_probe(ProbeOptions {
                        include_autostart: false,
                    }),
                ])
            }
            Message::OpenLogs => {
                self.modal = Some(ModalView::Logs(
                    t(self.is_zh, "正在加载日志...", "Loading logs...").to_string(),
                ));
                self.load_logs_async()
            }
            Message::RefreshLogs => {
                if self.logs_loading {
                    return Task::none();
                }
                self.load_logs_async()
            }
            Message::LogsLoaded(seq, text) => {
                if seq != self.logs_seq {
                    return Task::none();
                }
                self.logs_loading = false;
                if matches!(self.modal, Some(ModalView::Logs(_))) {
                    self.modal = Some(ModalView::Logs(text));
                    operation::snap_to_end(self.log_scroll_id.clone())
                } else {
                    Task::none()
                }
            }
            Message::OpenHelp => {
                self.modal = Some(ModalView::Help);
                Task::none()
            }
            Message::OpenResetConfig => {
                self.modal = Some(ModalView::ResetConfigConfirm);
                Task::none()
            }
            Message::ConfirmResetConfig => {
                self.modal = None;
                let default_cfg = SilentInputConfig {
                    auth_token: generate_pairing_token(),
                    token_created_at: unix_timestamp_secs(),
                    ..SilentInputConfig::default()
                };
                self.config = default_cfg;
                self.whitelist_input = format_ip_list(&self.config.whitelist);
                self.blacklist_input = format_ip_list(&self.config.blacklist);
                let _ = save_config_at(&self.paths, &self.config);
                self.config_generation += 1;
                self.pending_acl_save = false;

                self.service_action_count += 1;
                self.service_gen += 1;
                let paths = self.paths.clone();
                let is_zh = self.is_zh;
                let restart_task = Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || service_restart_at(&paths)).await
                    },
                    move |res| match res {
                        Ok((ok, msg)) => Message::ServiceActionFinished(ok, msg),
                        Err(_) => Message::ServiceActionFinished(
                            false,
                            t(is_zh, "重启服务调用失败", "Failed to restart service").to_string(),
                        ),
                    },
                );
                let toast = self.show_toast(t(
                    self.is_zh,
                    "已恢复出厂默认设置，后台服务已重启",
                    "Restored factory defaults and restarted daemon",
                ));
                Task::batch([toast, restart_task])
            }
            Message::CloseModal => {
                if self.uninstall_in_flight {
                    return Task::none();
                }
                self.modal = None;
                self.logs_loading = false;
                Task::none()
            }
            Message::OpenUninstall => {
                let pkg_cmd = get_package_uninstall_command();
                if is_system_package_install() || pkg_cmd.is_some() {
                    let hint = pkg_cmd.unwrap_or("sudo apt remove silentinput").to_string();
                    self.modal = Some(ModalView::UninstallPackage(hint));
                } else {
                    self.modal = Some(ModalView::UninstallUserConfirm);
                }
                Task::none()
            }
            Message::StopServiceAndQuit => {
                if self.uninstall_in_flight {
                    return Task::none();
                }
                self.uninstall_in_flight = true;
                let paths = self.paths.clone();
                let toast = self.show_toast(t(self.is_zh, "正在停止服务...", "Stopping service..."));
                let stop_task = Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            let _ = service_stop_at(&paths);
                        })
                        .await
                    },
                    |_| Message::FinishedAndQuit,
                );
                Task::batch([toast, stop_task])
            }
            Message::ConfirmUserUninstall => {
                if self.uninstall_in_flight {
                    return Task::none();
                }
                self.uninstall_in_flight = true;
                let toast = self.show_toast(t(self.is_zh, "正在卸载...", "Uninstalling..."));
                let paths = self.paths.clone();
                let task = Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            let script = paths.home_dir.join(".local/share/silentinput/uninstall.sh");
                            if script.exists() {
                                let _ = Command::new("bash").arg(script).status();
                            } else {
                                perform_user_uninstall();
                            }
                        })
                        .await
                    },
                    |_| Message::FinishedAndQuit,
                );
                Task::batch([toast, task])
            }
            Message::FinishedAndQuit => {
                self.uninstall_in_flight = false;
                self.modal = None;
                self.flush_acl_if_pending();
                iced::exit()
            }
            Message::DismissToast(seq) => {
                if seq == self.toast_seq {
                    self.toast = None;
                }
                Task::none()
            }
            Message::WindowCloseRequested(id) => {
                self.flush_acl_if_pending();
                window::close(id)
            }
        }
    }

    fn schedule_acl_debounce(&mut self) -> Task<Message> {
        self.config_generation += 1;
        self.pending_acl_save = true;
        self.acl_save_seq = self.acl_save_seq.saturating_add(1);
        let seq = self.acl_save_seq;
        Task::perform(
            async move {
                tokio::time::sleep(Duration::from_millis(400)).await;
                seq
            },
            Message::FlushAclSave,
        )
    }

    fn title(&self) -> String {
        // Keep Wayland/X11 CSD window title ASCII so sctk-adwaita's Latin-only renderer never draws tofu.
        "SilentInput Control Center".to_string()
    }

    #[cfg_attr(not(test), allow(dead_code))]
    fn header_title(&self) -> &'static str {
        t(
            self.is_zh,
            "SilentInput 控制中心",
            "SilentInput Control Center",
        )
    }

    fn theme(&self) -> Theme {
        style::monochrome_theme(self.is_dark())
    }

    fn subscription(&self) -> Subscription<Message> {
        let probe_interval = if self.window_focused {
            Duration::from_secs(3)
        } else {
            Duration::from_secs(15)
        };

        let esc_sub = if !self.uninstall_in_flight && self.modal.is_some() {
            keyboard::listen().filter_map(|event| match event {
                keyboard::Event::KeyPressed {
                    key: keyboard::Key::Named(keyboard::key::Named::Escape),
                    ..
                } => Some(Message::CloseModal),
                _ => None,
            })
        } else if !self.uninstall_in_flight && self.page == ActivePage::Settings {
            keyboard::listen().filter_map(|event| match event {
                keyboard::Event::KeyPressed {
                    key: keyboard::Key::Named(keyboard::key::Named::Escape),
                    ..
                } => Some(Message::OpenHome),
                _ => None,
            })
        } else {
            Subscription::none()
        };

        Subscription::batch([
            iced::time::every(probe_interval).map(|_| Message::TickProbe),
            iced::system::theme_changes().map(Message::SystemThemeChanged),
            window::events().filter_map(|(_id, event)| match event {
                window::Event::Focused => Some(Message::WindowFocused),
                window::Event::Unfocused => Some(Message::WindowUnfocused),
                _ => None,
            }),
            window::close_requests().map(Message::WindowCloseRequested),
            esc_sub,
        ])
    }

    fn status_pill(&self) -> Element<'_, Message> {
        let zh = self.is_zh;
        let is_dark = self.is_dark();
        let dot_color = if self.running {
            style::success_color(is_dark)
        } else {
            style::muted_color(is_dark)
        };
        let label = if self.running {
            t(zh, "运行中", "Running")
        } else {
            t(zh, "已停止", "Stopped")
        };

        container(
            row![status_dot(7.0, dot_color), text(label).size(12)]
                .spacing(6)
                .align_y(Alignment::Center),
        )
        .style(style::status_pill_style(self.running))
        .padding([4, 10])
        .into()
    }

    fn view_home(&self) -> Element<'_, Message> {
        let zh = self.is_zh;
        let is_dark = self.is_dark();
        let primary = style::primary_color(is_dark);
        let muted = style::muted_color(is_dark);

        let header = row![
            icon(IconKind::BrandMark, 22.0, primary),
            text("SilentInput").size(19),
            self.status_pill(),
            toggler(self.running)
                .on_toggle_maybe(if self.service_action_count == 0 {
                    Some(Message::ToggleService)
                } else {
                    None
                })
                .style(style::toggler_style)
                .size(22),
            space::horizontal(),
            circle_icon_button(IconKind::Logs, primary, Message::OpenLogs),
            circle_icon_button(IconKind::Help, primary, Message::OpenHelp),
            circle_icon_button(IconKind::Settings, primary, Message::OpenSettings),
        ]
        .spacing(8)
        .align_y(Alignment::Center);

        let qr_data = self.build_qr_data();
        let mut qr_card_col = column![
            build_qr_code_element(&qr_data.uri),
            text(t(
                zh,
                "手机端扫码即可配对",
                "Scan with mobile app to pair",
            ))
            .size(13)
            .color(muted),
            row![
                button(
                    row![
                        icon(IconKind::Eye, 15.0, primary),
                        text(if self.show_key {
                            t(zh, "隐藏密钥", "Hide Key")
                        } else {
                            t(zh, "显示密钥", "Show Key")
                        })
                        .size(13),
                    ]
                    .spacing(7)
                    .align_y(Alignment::Center),
                )
                .style(style::pill_button_style)
                .padding([9, 18])
                .on_press(Message::ToggleShowKey),
                button(
                    row![
                        icon(IconKind::Refresh, 15.0, primary),
                        text(t(zh, "更换密钥", "New Key")).size(13),
                    ]
                    .spacing(7)
                    .align_y(Alignment::Center),
                )
                .style(style::pill_button_style)
                .padding([9, 18])
                .on_press(Message::RegenerateToken),
            ]
            .spacing(12)
            .align_y(Alignment::Center),
        ]
        .spacing(16)
        .align_x(Alignment::Center);

        if self.config.access_control_mode == "localhost_only" {
            qr_card_col = qr_card_col.push(
                button(
                    text(t(
                        zh,
                        "当前仅限本机 USB · 点此设置局域网",
                        "Localhost USB only · Tap to allow LAN",
                    ))
                    .size(12),
                )
                .style(style::warning_pill_button_style)
                .padding([6, 14])
                .on_press(Message::OpenSettings),
            );
        }

        let hero_card = container(
            container(qr_card_col)
                .width(Length::Fill)
                .center_x(Length::Fill),
        )
        .style(style::hero_card_style)
        .padding([24, 22])
        .width(Length::Fill);

        let mut home_col = column![header, hero_card].spacing(14);
        if self.show_key {
            let key_card = container(
                row![
                    text(self.config.auth_token.as_str())
                        .font(self.mono_font)
                        .size(12)
                        .color(primary)
                        .wrapping(text::Wrapping::Glyph)
                        .width(Length::Fill),
                    button(
                        row![
                            icon(IconKind::Copy, 14.0, primary),
                            text(t(zh, "复制", "Copy")).size(12),
                        ]
                        .spacing(5)
                        .align_y(Alignment::Center),
                    )
                    .style(style::pill_button_style)
                    .padding([7, 14])
                    .on_press(Message::CopyToken),
                ]
                .spacing(12)
                .align_y(Alignment::Center),
            )
            .style(style::card_style)
            .padding([12, 16])
            .width(Length::Fill);
            home_col = home_col.push(key_card);
        }

        container(scrollable(home_col.padding([18, 22])).height(Length::Fill))
            .width(Length::Fill)
            .height(Length::Fill)
            .into()
    }

    fn view_settings(&self) -> Element<'_, Message> {
        let zh = self.is_zh;
        let is_dark = self.is_dark();
        let primary = style::primary_color(is_dark);
        let muted = style::muted_color(is_dark);
        let success = style::success_color(is_dark);

        let acl_opts = acl_mode_options(zh);
        let exp_opts = expire_options(zh);
        let lang_opts = language_options(zh);
        let theme_opts = theme_options(zh);

        let header = row![
            circle_icon_button(IconKind::Back, primary, Message::OpenHome),
            text(t(zh, "设置", "Settings")).size(19),
            space::horizontal(),
            self.status_pill(),
        ]
        .spacing(10)
        .align_y(Alignment::Center);

        // Card 1: Connection & Access
        let selected_acl = find_option(&acl_opts, &self.config.access_control_mode);
        let mut access_col = column![setting_row(
            t(zh, "访问模式", "Access Mode"),
            pick_list(acl_opts, selected_acl, Message::AclModeSelected)
                .style(style::pick_list_style)
                .menu_style(style::pick_list_menu_style)
                .text_size(13)
                .padding([7, 12]),
        )]
        .spacing(12);

        match self.config.access_control_mode.as_str() {
            "whitelist" => {
                let invalid = find_invalid_ip_patterns(&parse_ip_list(&self.whitelist_input));
                let mut wl_col = column![
                    text(t(
                        zh,
                        "IP 白名单 (支持 * 通配符或 CIDR)",
                        "IP Whitelist (* wildcard or CIDR)",
                    ))
                    .size(12)
                    .color(muted),
                    text_input("127.0.0.1, 192.168.*, 172.16.0.0/12", &self.whitelist_input)
                        .on_input(Message::WhitelistChanged)
                        .style(style::input_style)
                        .font(self.mono_font)
                        .padding([9, 12])
                        .size(13),
                ]
                .spacing(6);

                if !invalid.is_empty() {
                    let err_items = invalid
                        .iter()
                        .map(|(p, _)| format!("\"{p}\""))
                        .collect::<Vec<_>>()
                        .join(", ");
                    wl_col = wl_col.push(
                        text(if zh {
                            format!("无法识别网段: {err_items}")
                        } else {
                            format!("Invalid subnet: {err_items}")
                        })
                        .size(12)
                        .color(style::warning_color(is_dark)),
                    );
                }
                access_col = access_col.push(wl_col);
            }
            "blacklist" => {
                let invalid = find_invalid_ip_patterns(&parse_ip_list(&self.blacklist_input));
                let mut bl_col = column![
                    text(t(zh, "IP 黑名单", "IP Blacklist"))
                        .size(12)
                        .color(muted),
                    text_input("192.168.1.50", &self.blacklist_input)
                        .on_input(Message::BlacklistChanged)
                        .style(style::input_style)
                        .font(self.mono_font)
                        .padding([9, 12])
                        .size(13),
                ]
                .spacing(6);

                if !invalid.is_empty() {
                    let err_items = invalid
                        .iter()
                        .map(|(p, _)| format!("\"{p}\""))
                        .collect::<Vec<_>>()
                        .join(", ");
                    bl_col = bl_col.push(
                        text(if zh {
                            format!("无法识别网段: {err_items}")
                        } else {
                            format!("Invalid subnet: {err_items}")
                        })
                        .size(12)
                        .color(style::warning_color(is_dark)),
                    );
                }
                access_col = access_col.push(bl_col);
            }
            _ => {}
        }

        access_col = access_col.push(setting_row(
            t(zh, "开机自动启动", "Start at Login"),
            toggler(self.autostart)
                .on_toggle_maybe(if self.autostart_action_count == 0 {
                    Some(Message::ToggleAutostart)
                } else {
                    None
                })
                .style(style::toggler_style)
                .size(22),
        ));

        let access_card = section_card(t(zh, "连接与权限", "Connection & Access"), is_dark, access_col);

        // Card 2: Language & Theme
        let selected_lang = find_option(&lang_opts, &self.config.gui_language);
        let selected_theme = find_option(&theme_opts, &self.config.gui_theme);
        let appearance_card = section_card(
            t(zh, "界面设置", "Interface"),
            is_dark,
            column![
                setting_row(
                    t(zh, "界面语言", "Language"),
                    pick_list(lang_opts, selected_lang, Message::LanguageSelected)
                        .style(style::pick_list_style)
                        .menu_style(style::pick_list_menu_style)
                        .text_size(13)
                        .padding([7, 12]),
                ),
                setting_row(
                    t(zh, "界面外观", "Theme"),
                    pick_list(theme_opts, selected_theme, Message::ThemeSelected)
                        .style(style::pick_list_style)
                        .menu_style(style::pick_list_menu_style)
                        .text_size(13)
                        .padding([7, 12]),
                ),
            ]
            .spacing(12),
        );

        // Card 3: Collapsible "更多设置"
        let chevron = icon(
            if self.advanced_expanded {
                IconKind::ChevronUp
            } else {
                IconKind::ChevronDown
            },
            16.0,
            muted,
        );

        let mut more_header_left = column![text(t(zh, "更多设置", "More Settings")).size(15)].spacing(2);
        if !self.advanced_expanded {
            more_header_left = more_header_left.push(
                text(t(
                    zh,
                    "密钥轮换 · USB · TLS · 本机 IP · 维护",
                    "Key rotation · USB · TLS · LAN IPs · System",
                ))
                .size(12)
                .color(muted),
            );
        }

        let more_toggle = button(
            row![more_header_left, space::horizontal(), chevron]
                .align_y(Alignment::Center)
                .width(Length::Fill),
        )
        .style(style::ghost_row_button_style)
        .padding([6, 4])
        .width(Length::Fill)
        .on_press(Message::ToggleAdvancedSettings);

        let mut more_col = column![more_toggle].spacing(12);

        if self.advanced_expanded {
            let selected_expire = find_option(&exp_opts, &self.config.token_expire_mode);
            let exp_desc = get_token_status_text(&self.config, zh);
            let usb_sub = if self.usb_devices.is_empty() {
                t(zh, "当前未检测到 USB 设备", "No USB device detected").to_string()
            } else if zh {
                format!("已连接: {}", self.usb_devices.join(", "))
            } else {
                format!("Connected: {}", self.usb_devices.join(", "))
            };
            let usb_color = if self.usb_devices.is_empty() {
                muted
            } else {
                success
            };

            let mut ip_section = column![
                text(t(zh, "本机 IP (点击复制)", "Host IPs (click to copy)"))
                    .size(12)
                    .color(muted),
            ]
            .spacing(6);

            if self.host_ips.is_empty() {
                ip_section = ip_section.push(
                    text(t(zh, "未检测到局域网 IP", "No LAN IP detected"))
                        .size(12)
                        .color(muted),
                );
            } else {
                let mut rows = column![].spacing(6);
                for pair in self.host_ips.chunks(2) {
                    let mut r = row![].spacing(8);
                    for ip in pair {
                        r = r.push(
                            button(text(ip.clone()).font(self.mono_font).size(12))
                                .style(style::pill_button_style)
                                .padding([6, 12])
                                .on_press(Message::CopyIp(ip.clone())),
                        );
                    }
                    rows = rows.push(r);
                }
                ip_section = ip_section.push(rows);
            }

            let actions_row = row![
                button(text(t(zh, "重启服务", "Restart")).size(12))
                    .style(style::pill_button_style)
                    .padding([7, 13])
                    .on_press_maybe(if self.service_action_count == 0 {
                        Some(Message::RestartService)
                    } else {
                        None
                    }),
                button(text(t(zh, "恢复默认", "Reset")).size(12))
                    .style(style::pill_button_style)
                    .padding([7, 13])
                    .on_press_maybe(if self.service_action_count == 0 {
                        Some(Message::OpenResetConfig)
                    } else {
                        None
                    }),
                space::horizontal(),
                button(text(t(zh, "卸载", "Uninstall")).size(12))
                    .style(style::danger_button_style)
                    .padding([7, 14])
                    .on_press(Message::OpenUninstall),
            ]
            .spacing(8)
            .align_y(Alignment::Center);

            more_col = more_col.push(
                column![
                    column![
                        setting_row(
                            t(zh, "密钥有效期", "Key Expiration"),
                            pick_list(exp_opts, selected_expire, Message::ExpireModeSelected)
                                .style(style::pick_list_style)
                                .menu_style(style::pick_list_menu_style)
                                .text_size(13)
                                .padding([7, 12]),
                        ),
                        text(exp_desc).size(12).color(muted),
                    ]
                    .spacing(3),
                    row![
                        column![
                            text(t(zh, "USB 免密直连", "USB Direct Connect")).size(14),
                            text(usb_sub).size(12).color(usb_color),
                        ]
                        .spacing(2)
                        .width(Length::Fill),
                        toggler(self.config.allow_usb_unauthenticated)
                            .on_toggle(Message::ToggleUsbNoAuth)
                            .style(style::toggler_style)
                            .size(22),
                    ]
                    .align_y(Alignment::Center),
                    setting_row(
                        t(zh, "TLS 传输加密 (WSS)", "TLS Encryption (WSS)"),
                        toggler(self.config.enable_tls)
                            .on_toggle_maybe(if self.service_action_count == 0 {
                                Some(Message::ToggleTls)
                            } else {
                                None
                            })
                            .style(style::toggler_style)
                            .size(22),
                    ),
                    ip_section,
                    actions_row,
                ]
                .spacing(14)
                .padding([4, 4]),
            );
        }

        let more_card = container(more_col)
            .style(style::card_style)
            .padding([12, 14])
            .width(Length::Fill);

        let body = scrollable(
            column![access_card, appearance_card, more_card]
                .spacing(14)
                .padding([4, 2]),
        )
        .height(Length::Fill);

        container(
            column![header, body]
                .spacing(14)
                .padding([20, 22]),
        )
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
    }

    fn view(&self) -> Element<'_, Message> {
        let zh = self.is_zh;
        let is_dark = self.is_dark();
        let muted = style::muted_color(is_dark);

        let page_view = match self.page {
            ActivePage::Home => self.view_home(),
            ActivePage::Settings => self.view_settings(),
        };

        let mut base_col = column![container(page_view).height(Length::Fill)].height(Length::Fill);
        if let Some(toast_msg) = &self.toast {
            base_col = base_col.push(
                container(
                    container(text(toast_msg.clone()).size(13))
                        .style(style::toast_style)
                        .padding([8, 16]),
                )
                .width(Length::Fill)
                .center_x(Length::Fill)
                .padding([4, 12]),
            );
        }

        let base: Element<'_, Message> = container(base_col)
            .width(Length::Fill)
            .height(Length::Fill)
            .into();

        if let Some(modal) = &self.modal {
            let dialog: Element<'_, Message> = match modal {
                ModalView::QrPairing(qr_data) => {
                    let ips_str = if qr_data.ips.is_empty() {
                        t(zh, "未检测到局域网 IP", "No LAN IP detected").to_string()
                    } else {
                        qr_data.ips.join(", ")
                    };
                    let tls_line = if qr_data.tls {
                        match &qr_data.fingerprint_short {
                            Some(fp) => {
                                if zh {
                                    format!("TLS 开启 · 指纹 {fp}")
                                } else {
                                    format!("TLS On · {fp}")
                                }
                            }
                            None => t(zh, "TLS 开启", "TLS On").to_string(),
                        }
                    } else {
                        t(zh, "TLS 已关闭 (明文 WS)", "TLS Off (Plain WS)").to_string()
                    };

                    let mut body = column![
                        row![
                            text(t(zh, "扫码配对手机端", "Scan QR Code to Pair"))
                                .size(17),
                            space::horizontal(),
                            button(text(t(zh, "更换密钥", "New Key")).size(12))
                                .style(style::secondary_button_style)
                                .padding([5, 10])
                                .on_press(Message::RegenerateToken),
                            button(text(t(zh, "关闭", "Close")).size(12))
                                .style(style::primary_button_style)
                                .padding([5, 12])
                                .on_press(Message::CloseModal),
                        ]
                        .spacing(8)
                        .align_y(Alignment::Center),
                        container(build_qr_code_element(&qr_data.uri))
                            .width(Length::Fill)
                            .center_x(Length::Fill),
                        text(t(
                            zh,
                            "在手机端「网络设置 -> 扫码配对」扫描即可写入主机、密钥与证书指纹。",
                            "Scan with the mobile app under Network Settings -> Scan QR to save host, key, and TLS fingerprint.",
                        ))
                        .size(12)
                        .color(muted),
                        container(
                            column![
                                text(format!(
                                    "{}: {}",
                                    t(zh, "主机", "Host"),
                                    qr_data.name
                                ))
                                .size(12),
                                text(format!(
                                    "{}: {ips_str}:{}",
                                    t(zh, "地址", "Address"),
                                    qr_data.port
                                ))
                                .font(self.mono_font)
                                .size(12),
                                text(tls_line).font(self.mono_font).size(11).color(muted),
                            ]
                            .spacing(4),
                        )
                        .style(style::subtle_box_style)
                        .padding(10)
                        .width(Length::Fill),
                    ]
                    .spacing(12);

                    if self.config.access_control_mode == "localhost_only" {
                        body = body.push(
                            text(t(
                                zh,
                                "提示：当前访问控制为「仅限本机」，局域网扫码连接前请先改为「白名单」。",
                                "Note: Access control is 'Localhost Only'. Switch to 'Whitelist' before LAN QR pairing.",
                            ))
                            .size(12)
                            .color(style::warning_color(is_dark)),
                        );
                    }

                    container(body)
                        .style(style::card_style)
                        .padding(18)
                        .width(Length::Fixed(390.0))
                        .into()
                }

                ModalView::Help => container(
                    column![
                        row![
                            text(t(zh, "SilentInput 电脑端 · 使用指引", "SilentInput · User Guide"))
                                .size(17),
                            space::horizontal(),
                            button(text(t(zh, "关闭", "Close")).size(12))
                                .style(style::primary_button_style)
                                .padding([5, 12])
                                .on_press(Message::CloseModal),
                        ]
                        .align_y(Alignment::Center),
                        container(
                            scrollable(
                                column![
                                    text(t(
                                        zh,
                                        "🔌 1. USB 有线连接（默认免密）",
                                        "🔌 1. USB Wired Connection",
                                    ))
                                    .size(14),
                                    text(t(
                                        zh,
                                        "• 手机开启「开发者选项」和「USB 调试」，用数据线连接电脑。\n• 手机端选择「USB 有线」即可自动通过 adb 转发连接，无需输入 IP 和密钥。",
                                        "• Enable Developer Options and USB Debugging on your phone and plug in via USB.\n• Select 'USB Wired' on the phone to connect over adb forward without IP or key.",
                                    ))
                                    .size(12)
                                    .color(muted),

                                    space::vertical().height(8),
                                    text(t(zh, "📶 2. Wi-Fi 局域网连接", "📶 2. Wi-Fi LAN Connection"))
                                        .size(14),
                                    text(t(
                                        zh,
                                        "• 确保手机与电脑在同一局域网，并将访问控制设为「白名单」。\n• 点击「扫码配对」用手机扫码，或手动输入本机 IP 与配对密钥。\n• 放行防火墙 53318/udp 可使用加密 UDP 鼠标通道（否则自动使用 TCP）。",
                                        "• Ensure phone and PC are on the same LAN and set Access Control to 'Whitelist'.\n• Click 'QR Pair' to scan from the mobile app, or enter IP and Pairing Key manually.\n• Allow 53318/udp in your firewall for encrypted UDP mouse packets (falls back to TCP).",
                                    ))
                                    .size(12)
                                    .color(muted),

                                    space::vertical().height(8),
                                    text(t(zh, "🔵 3. 蓝牙无线连接", "🔵 3. Bluetooth Connection"))
                                        .size(14),
                                    text(t(
                                        zh,
                                        "• 在系统蓝牙设置中配对此电脑与手机。\n• 手机端切换至「蓝牙」标签页，选择此电脑即可输入。",
                                        "• Pair the phone and PC in system Bluetooth settings.\n• Switch to 'Bluetooth' in the mobile app and select this PC.",
                                    ))
                                    .size(12)
                                    .color(muted),

                                    space::vertical().height(8),
                                    text(t(zh, "🛡️ 4. 访问控制与密钥", "🛡️ 4. Access Control & Keys"))
                                        .size(14),
                                    text(t(
                                        zh,
                                        "• 默认使用「仅限本机」，仅接收本机 USB 转发，不接受局域网连接。\n• 配对密钥默认每 1 小时自动轮换一次，可按需调整。\n• 白名单支持通配符（192.168.*）和 CIDR（172.16.0.0/12）。",
                                        "• Defaults to 'Localhost Only' so only local USB forwarding is accepted.\n• Pairing key rotates every 1 hour by default.\n• Whitelist supports wildcards (192.168.*) and CIDR (172.16.0.0/12).",
                                    ))
                                    .size(12)
                                    .color(muted),
                                ]
                                .spacing(6),
                            )
                            .height(Length::Fill),
                        )
                        .style(style::subtle_box_style)
                        .padding(12)
                        .width(Length::Fill)
                        .height(Length::Fixed(320.0)),
                    ]
                    .spacing(12),
                )
                .style(style::card_style)
                .padding(18)
                .width(Length::Fixed(480.0))
                .into(),

                ModalView::ResetConfigConfirm => container(
                    column![
                        text(t(zh, "确认恢复默认设置？", "Reset to Factory Defaults?"))
                            .size(17),
                        text(t(
                            zh,
                            "此操作将重置以下配置并重启服务：\n• 访问控制：仅限本机\n• 密钥有效期：1 小时\n• 生成新的 256 位配对密钥\n• 白名单恢复默认网段（127.0.0.1, 10.*, 192.168.*, 100.64.0.0/10, 172.16.0.0/12）",
                            "This resets the following settings and restarts the service:\n• Access mode: Localhost Only\n• Key expiration: 1 hour\n• Generate a new 256-bit pairing key\n• Restore default whitelist subnets",
                        ))
                        .size(13)
                        .color(muted),
                        row![
                            space::horizontal(),
                            button(text(t(zh, "取消", "Cancel")).size(13))
                                .style(style::secondary_button_style)
                                .padding([7, 14])
                                .on_press(Message::CloseModal),
                            button(text(t(zh, "确认恢复", "Confirm Reset")).size(13))
                                .style(style::danger_button_style)
                                .padding([7, 14])
                                .on_press(Message::ConfirmResetConfig),
                        ]
                        .spacing(8),
                    ]
                    .spacing(14),
                )
                .style(style::card_style)
                .padding(18)
                .width(Length::Fixed(440.0))
                .into(),

                ModalView::Logs(log_text) => container(
                    column![
                        row![
                            text(t(
                                zh,
                                "运行日志 (最近 80 行)",
                                "Service Logs (Last 80 lines)",
                            ))
                            .size(16),
                            space::horizontal(),
                            button(text(t(zh, "刷新", "Refresh")).size(12))
                                .style(style::secondary_button_style)
                                .padding([5, 10])
                                .on_press_maybe(if self.logs_loading {
                                    None
                                } else {
                                    Some(Message::RefreshLogs)
                                }),
                            button(text(t(zh, "关闭", "Close")).size(12))
                                .style(style::primary_button_style)
                                .padding([5, 12])
                                .on_press(Message::CloseModal),
                        ]
                        .spacing(8)
                        .align_y(Alignment::Center),
                        container(
                            scrollable(
                                text(log_text.clone())
                                    .font(self.mono_font)
                                    .size(12),
                            )
                            .id(self.log_scroll_id.clone())
                            .height(Length::Fill),
                        )
                        .style(style::subtle_box_style)
                        .padding(10)
                        .width(Length::Fill)
                        .height(Length::Fixed(340.0)),
                    ]
                    .spacing(12),
                )
                .style(style::card_style)
                .padding(16)
                .width(Length::Fixed(480.0))
                .into(),

                ModalView::UninstallPackage(cmd_hint) => {
                    let body = if zh {
                        format!(
                            "SilentInput 当前由系统包管理器安装。\n可在终端运行以下命令完整卸载：\n\n{cmd_hint}"
                        )
                    } else {
                        format!(
                            "SilentInput was installed via your system package manager.\nRun the command below in a terminal to uninstall:\n\n{cmd_hint}"
                        )
                    };
                    container(
                        column![
                            text(t(
                                zh,
                                "通过包管理器卸载",
                                "Uninstall via Package Manager",
                            ))
                            .size(17),
                            text(body).size(13).color(muted),
                            row![
                                space::horizontal(),
                                button(text(t(zh, "关闭", "Close")).size(13))
                                    .style(style::secondary_button_style)
                                    .padding([7, 14])
                                    .on_press_maybe(if self.uninstall_in_flight {
                                        None
                                    } else {
                                        Some(Message::CloseModal)
                                    }),
                                button(
                                    text(t(zh, "仅停止服务并退出", "Stop Service & Quit")).size(13),
                                )
                                .style(style::primary_button_style)
                                .padding([7, 14])
                                .on_press_maybe(if self.uninstall_in_flight {
                                    None
                                } else {
                                    Some(Message::StopServiceAndQuit)
                                }),
                            ]
                            .spacing(8),
                        ]
                        .spacing(14),
                    )
                    .style(style::card_style)
                    .padding(18)
                    .width(Length::Fixed(420.0))
                    .into()
                }

                ModalView::UninstallUserConfirm => container(
                    column![
                        text(t(zh, "确认卸载 SilentInput？", "Uninstall SilentInput?"))
                            .size(17),
                        text(t(
                            zh,
                            "这将停止正在运行的服务，并移除启动脚本与桌面图标。",
                            "This will stop the running daemon and remove startup scripts and desktop shortcuts.",
                        ))
                        .size(13)
                        .color(muted),
                        row![
                            space::horizontal(),
                            button(text(t(zh, "取消", "Cancel")).size(13))
                                .style(style::secondary_button_style)
                                .padding([7, 14])
                                .on_press_maybe(if self.uninstall_in_flight {
                                    None
                                } else {
                                    Some(Message::CloseModal)
                                }),
                            button(text(t(zh, "确认卸载", "Uninstall")).size(13))
                                .style(style::danger_button_style)
                                .padding([7, 14])
                                .on_press_maybe(if self.uninstall_in_flight {
                                    None
                                } else {
                                    Some(Message::ConfirmUserUninstall)
                                }),
                        ]
                        .spacing(8),
                    ]
                    .spacing(14),
                )
                .style(style::card_style)
                .padding(18)
                .width(Length::Fixed(400.0))
                .into(),
            };

            let backdrop = opaque(
                container(
                    container(dialog)
                        .width(Length::Fill)
                        .height(Length::Fill)
                        .center_x(Length::Fill)
                        .center_y(Length::Fill),
                )
                .style(style::modal_backdrop_style)
                .width(Length::Fill)
                .height(Length::Fill),
            );

            iced::widget::stack![base, backdrop].into()
        } else {
            base
        }
    }
}

fn build_qr_code_element<'a>(uri: &str) -> Element<'a, Message> {
    let Some((mods, dark_cells)) = get_qr_matrix(uri) else {
        return text("QR error").size(12).into();
    };
    let target_px = 216.0_f32;
    let module_px = (target_px / mods as f32).floor().clamp(3.0, 7.0);
    let mut rows_col = column![].spacing(0);
    for y in 0..mods {
        let mut r = row![].spacing(0);
        let mut x = 0;
        while x < mods {
            let is_dark = dark_cells[y * mods + x];
            let mut run = 1;
            while x + run < mods && dark_cells[y * mods + x + run] == is_dark {
                run += 1;
            }
            let cell_style = if is_dark {
                style::qr_dark_style
            } else {
                style::qr_light_style
            };
            r = r.push(
                container(space().width(Length::Fill).height(Length::Fill))
                    .width(Length::Fixed(run as f32 * module_px))
                    .height(Length::Fixed(module_px))
                    .style(cell_style),
            );
            x += run;
        }
        rows_col = rows_col.push(r);
    }
    container(rows_col)
        .style(style::qr_paper_style)
        .padding(14)
        .into()
}

fn circle_icon_button<'a>(
    kind: IconKind,
    color: iced::Color,
    msg: Message,
) -> Element<'a, Message> {
    button(icon(kind, 16.0, color))
        .style(style::pill_button_style)
        .padding(9)
        .on_press(msg)
        .into()
}

fn setting_row<'a>(
    label: &'a str,
    control: impl Into<Element<'a, Message>>,
) -> Element<'a, Message> {
    row![
        text(label).size(14).width(Length::Fill),
        control.into(),
    ]
    .align_y(Alignment::Center)
    .into()
}

fn section_card<'a>(
    title: &'a str,
    dark: bool,
    body: impl Into<Element<'a, Message>>,
) -> Element<'a, Message> {
    container(
        column![
            text(title).size(12).color(style::muted_color(dark)),
            body.into(),
        ]
        .spacing(12),
    )
    .style(style::card_style)
    .padding(18)
    .width(Length::Fill)
    .into()
}

pub fn main() -> iced::Result {
    let paths = SilentInputPaths::system();
    let cjk_font = detect_cjk_font_family()
        .map(Font::with_name)
        .unwrap_or(Font::DEFAULT);

    iced::application(
        move || ControlCenterState::new_with_paths(paths.clone()),
        ControlCenterState::update,
        ControlCenterState::view,
    )
    .title(ControlCenterState::title)
    .subscription(ControlCenterState::subscription)
    .theme(ControlCenterState::theme)
    .default_font(cjk_font)
    .window(window::Settings {
        size: Size::new(480.0, 560.0),
        min_size: Some(Size::new(400.0, 480.0)),
        exit_on_close_request: false,
        platform_specific: window::settings::PlatformSpecific {
            application_id: "com.silentinput.controlcenter".to_string(),
            ..Default::default()
        },
        ..Default::default()
    })
    .run()
}

#[cfg(test)]
mod tests {
    use std::fs;

    use iced::theme::Mode;
    use silentinput_core::config::{
        is_valid_strong_token, load_config_at, save_config_at, SilentInputConfig, STRONG_TOKEN_LEN,
    };
    use silentinput_core::paths::SilentInputPaths;

    use crate::helpers::parse_ip_list;
    use crate::i18n::{find_option, language_options, theme_options};
    use crate::style;
    use crate::{ControlCenterState, Message, ModalView, ProbeOptions, ProbeSnapshot};

    fn tmp_paths(tag: &str) -> SilentInputPaths {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir = std::env::temp_dir().join(format!(
            "silentinput_gui_test_{tag}_{}_{nanos}",
            std::process::id()
        ));
        SilentInputPaths::from_dir(dir)
    }

    #[test]
    fn acl_debounce_flushes_on_matching_seq_and_blocks_stale_probe() {
        let paths = tmp_paths("acl_debounce");
        let (mut state, _) = ControlCenterState::new_with_paths(paths.clone());

        let _ = state.update(Message::WhitelistChanged(
            "127.0.0.1, 100.64.0.0/10".to_string(),
        ));
        assert!(state.pending_acl_save);
        let first_seq = state.acl_save_seq;

        // Background probe from before user edited must NOT overwrite in-progress edit or toggle
        let stale_probe = ProbeSnapshot {
            gen_before: state.config_generation.saturating_sub(1),
            service_gen_before: state.service_gen,
            autostart_gen_before: state.autostart_gen,
            config: SilentInputConfig {
                whitelist: vec!["192.168.*".to_string()],
                ..SilentInputConfig::default()
            },
            running: true,
            pid: 1234,
            autostart: Some(false),
            usb_devices: vec![],
            host_ips: vec!["192.168.1.10".to_string()],
        };
        let _ = state.update(Message::ProbeCompleted(Box::new(stale_probe)));
        assert_eq!(state.whitelist_input, "127.0.0.1, 100.64.0.0/10");
        assert!(state.running);
        assert_eq!(state.pid, 1234);

        // Stale service probe captured before ToggleService must not bounce switch back
        let pre_toggle_gen = state.service_gen;
        let _ = state.update(Message::ToggleService(false));
        assert!(!state.running);
        let stale_service_probe = ProbeSnapshot {
            gen_before: state.config_generation,
            service_gen_before: pre_toggle_gen,
            autostart_gen_before: state.autostart_gen,
            config: load_config_at(&paths),
            running: true,
            pid: 1234,
            autostart: Some(false),
            usb_devices: vec![],
            host_ips: vec![],
        };
        // Simulate action finish, then late arrival of pre-toggle probe
        state.service_action_count = 0;
        let _ = state.update(Message::ProbeCompleted(Box::new(stale_service_probe)));
        assert!(!state.running);

        // Stale seq must not flush early; matching seq saves to disk
        let _ = state.update(Message::WhitelistChanged("127.0.0.1, 10.*".to_string()));
        let second_seq = state.acl_save_seq;
        let _ = state.update(Message::FlushAclSave(first_seq));
        assert!(state.pending_acl_save);

        let _ = state.update(Message::FlushAclSave(second_seq));
        assert!(!state.pending_acl_save);
        let reloaded = load_config_at(&paths);
        assert_eq!(reloaded.whitelist, vec!["127.0.0.1", "10.*"]);

        let _ = fs::remove_dir_all(&paths.config_dir);
    }

    #[test]
    fn language_switch_persists_and_updates_ui_locale() {
        let paths = tmp_paths("lang");
        save_config_at(
            &paths,
            &SilentInputConfig {
                gui_language: "zh".to_string(),
                ..SilentInputConfig::default()
            },
        )
        .unwrap();

        let (mut state, _) = ControlCenterState::new_with_paths(paths.clone());
        assert!(state.is_zh);
        assert_eq!(state.header_title(), "SilentInput 控制中心");

        let opts = language_options(state.is_zh);
        let en_opt = find_option(&opts, "en").unwrap();
        let _ = state.update(Message::LanguageSelected(en_opt));

        assert!(!state.is_zh);
        assert_eq!(state.header_title(), "SilentInput Control Center");
        assert_eq!(load_config_at(&paths).gui_language, "en");

        let _ = fs::remove_dir_all(&paths.config_dir);
    }

    #[test]
    fn parse_ip_list_trims_and_skips_empty() {
        assert_eq!(
            parse_ip_list(" 127.0.0.1 , , 10.* , "),
            vec!["127.0.0.1", "10.*"]
        );
    }

    #[test]
    fn modal_close_async_logs_and_uninstall_guard() {
        let paths = tmp_paths("modal_guard");
        save_config_at(&paths, &SilentInputConfig::default()).unwrap();

        let (mut state, _) = ControlCenterState::new_with_paths(paths.clone());

        // Async log loading sets initial loading modal, ignores stale sequences
        let _ = state.update(Message::OpenLogs);
        assert!(matches!(state.modal, Some(ModalView::Logs(_))));
        assert!(state.logs_loading);
        let first_seq = state.logs_seq;

        let _ = state.update(Message::LogsLoaded(first_seq.wrapping_sub(1), "stale".to_string()));
        assert!(state.logs_loading);

        let _ = state.update(Message::LogsLoaded(first_seq, "fresh log line".to_string()));
        assert!(!state.logs_loading);
        assert!(matches!(
            &state.modal,
            Some(ModalView::Logs(body)) if body == "fresh log line"
        ));

        // CloseModal dismisses logs modal (also triggered by Esc key subscription)
        let _ = state.update(Message::CloseModal);
        assert!(state.modal.is_none());

        // While uninstall_in_flight, CloseModal and repeated uninstall triggers are blocked
        state.modal = Some(ModalView::UninstallUserConfirm);
        state.uninstall_in_flight = true;
        let _ = state.update(Message::CloseModal);
        assert!(matches!(state.modal, Some(ModalView::UninstallUserConfirm)));
        let _ = state.update(Message::ConfirmUserUninstall);
        let _ = state.update(Message::StopServiceAndQuit);
        assert!(state.uninstall_in_flight);

        let _ = fs::remove_dir_all(&paths.config_dir);
    }

    #[test]
    fn theme_switch_follows_system_and_persists_override() {
        let paths = tmp_paths("theme");
        save_config_at(&paths, &SilentInputConfig::default()).unwrap();

        let (mut state, _) = ControlCenterState::new_with_paths(paths.clone());
        assert_eq!(state.config.gui_theme, "system");
        assert!(!style::is_dark_theme(&state.theme()));

        // In "system" mode, OS theme changes drive dark/light palette
        let _ = state.update(Message::SystemThemeChanged(Mode::Dark));
        assert!(state.is_dark());
        assert!(style::is_dark_theme(&state.theme()));

        let _ = state.update(Message::SystemThemeChanged(Mode::Light));
        assert!(!state.is_dark());
        assert!(!style::is_dark_theme(&state.theme()));

        // Manual "dark" override stays dark regardless of system light mode and persists
        let opts = theme_options(state.is_zh);
        let dark_opt = find_option(&opts, "dark").unwrap();
        let _ = state.update(Message::ThemeSelected(dark_opt));
        assert!(state.is_dark());
        assert!(style::is_dark_theme(&state.theme()));
        assert_eq!(load_config_at(&paths).gui_theme, "dark");

        // Manual "light" override stays light even when system switches to Dark
        let _ = state.update(Message::SystemThemeChanged(Mode::Dark));
        let light_opt = find_option(&opts, "light").unwrap();
        let _ = state.update(Message::ThemeSelected(light_opt));
        assert!(!state.is_dark());
        assert!(!style::is_dark_theme(&state.theme()));
        assert_eq!(load_config_at(&paths).gui_theme, "light");

        // Switching back to "system" immediately reflects current system_dark (true)
        let sys_opt = find_option(&opts, "system").unwrap();
        let _ = state.update(Message::ThemeSelected(sys_opt));
        assert!(state.is_dark());
        assert!(style::is_dark_theme(&state.theme()));
        assert_eq!(load_config_at(&paths).gui_theme, "system");

        let _ = fs::remove_dir_all(&paths.config_dir);
    }

    #[test]
    fn focus_transitions_and_queued_full_probe_preserve_guards() {
        let paths = tmp_paths("focus_probe");
        save_config_at(&paths, &SilentInputConfig::default()).unwrap();

        let (mut state, _) = ControlCenterState::new_with_paths(paths.clone());
        assert!(state.window_focused);
        state.autostart = true;
        state.usb_devices = vec!["1eda8461".to_string()];

        // Repeated Focused when already focused is a no-op
        let _ = state.update(Message::WindowFocused);
        assert!(!state.probe_in_flight);

        // Unfocused lowers polling cadence; starting a tick while in flight queues Focus refresh
        let _ = state.update(Message::WindowUnfocused);
        assert!(!state.window_focused);
        let _ = state.update(Message::TickProbe);
        assert!(state.probe_in_flight);

        let _ = state.update(Message::WindowFocused);
        assert!(state.window_focused);
        assert_eq!(
            state.queued_probe,
            Some(ProbeOptions {
                include_autostart: true,
            })
        );

        // Completing in-flight partial tick (autostart=None) keeps existing autostart
        // and immediately dispatches the queued full probe
        let partial_snap = ProbeSnapshot {
            gen_before: state.config_generation,
            service_gen_before: state.service_gen,
            autostart_gen_before: state.autostart_gen,
            config: load_config_at(&paths),
            running: true,
            pid: 4242,
            autostart: None,
            usb_devices: vec!["1eda8461".to_string()],
            host_ips: vec!["10.0.0.2".to_string()],
        };
        let _ = state.update(Message::ProbeCompleted(Box::new(partial_snap)));
        assert!(state.autostart);
        assert_eq!(state.usb_devices, vec!["1eda8461"]);
        assert_eq!(state.host_ips, vec!["10.0.0.2"]);
        assert!(state.queued_probe.is_none());
        assert!(state.probe_in_flight);

        let _ = fs::remove_dir_all(&paths.config_dir);
    }

    #[test]
    fn probe_failed_clears_in_flight_and_drains_queued_probe() {
        let paths = tmp_paths("probe_failed");
        save_config_at(&paths, &SilentInputConfig::default()).unwrap();

        let (mut state, _) = ControlCenterState::new_with_paths(paths.clone());

        // Simulate panic/cancellation inside spawn_blocking while another probe is queued
        let _ = state.update(Message::TickProbe);
        assert!(state.probe_in_flight);
        let _ = state.update(Message::WindowUnfocused);
        let _ = state.update(Message::WindowFocused);
        assert!(state.queued_probe.is_some());

        let _ = state.update(Message::ProbeFailed);
        assert!(state.queued_probe.is_none());
        assert!(state.probe_in_flight);

        // Next ProbeFailed with empty queue restores idle probe_in_flight = false
        let _ = state.update(Message::ProbeFailed);
        assert!(!state.probe_in_flight);

        let _ = fs::remove_dir_all(&paths.config_dir);
    }

    #[test]
    fn help_modal_and_reset_config_confirm() {
        let paths = tmp_paths("help_and_reset");
        // Start with customized non-default config
        let custom_cfg = SilentInputConfig {
            access_control_mode: "whitelist".to_string(),
            token_expire_mode: "never".to_string(),
            whitelist: vec!["100.*".to_string()],
            ..SilentInputConfig::default()
        };
        save_config_at(&paths, &custom_cfg).unwrap();

        let (mut state, _) = ControlCenterState::new_with_paths(paths.clone());
        assert_eq!(state.config.access_control_mode, "whitelist");
        assert_eq!(state.config.token_expire_mode, "never");
        assert_eq!(state.whitelist_input, "100.*");

        // 1. Open and close Help modal
        let _ = state.update(Message::OpenHelp);
        assert!(matches!(state.modal, Some(ModalView::Help)));
        let _ = state.update(Message::CloseModal);
        assert!(state.modal.is_none());

        // 2. Open Reset modal
        let _ = state.update(Message::OpenResetConfig);
        assert!(matches!(state.modal, Some(ModalView::ResetConfigConfirm)));

        // 3. Confirm Reset modal
        let _ = state.update(Message::ConfirmResetConfig);
        assert!(state.modal.is_none());
        assert_eq!(state.config.access_control_mode, "localhost_only");
        assert_eq!(state.config.token_expire_mode, "1h");
        assert_eq!(state.config.auth_token.len(), STRONG_TOKEN_LEN);
        assert!(is_valid_strong_token(&state.config.auth_token));
        assert!(state.whitelist_input.contains("127.0.0.1"));
        assert!(state.whitelist_input.contains("172.16.0.0/12"));
        assert!(state.toast.is_some());

        // Verify persisted to disk
        let disk_cfg = load_config_at(&paths);
        assert_eq!(disk_cfg.access_control_mode, "localhost_only");
        assert_eq!(disk_cfg.token_expire_mode, "1h");
        assert_eq!(disk_cfg.auth_token, state.config.auth_token);

        // 4. Open QR Pairing modal and regenerate key live inside it
        let old_token = state.config.auth_token.clone();
        let _ = state.update(Message::OpenQrPairing);
        let old_uri = match &state.modal {
            Some(ModalView::QrPairing(qr)) => {
                assert!(qr.uri.starts_with("silentinput://pair?v=1&"));
                assert!(qr.uri.contains(&old_token));
                qr.uri.clone()
            }
            other => panic!("expected QrPairing modal, got {other:?}"),
        };
        let _ = state.update(Message::RegenerateToken);
        assert_ne!(state.config.auth_token, old_token);
        match &state.modal {
            Some(ModalView::QrPairing(qr)) => {
                assert_ne!(qr.uri, old_uri);
                assert!(qr.uri.contains(&state.config.auth_token));
            }
            other => panic!("expected updated QrPairing modal, got {other:?}"),
        }
        let _ = state.update(Message::CloseModal);
        assert!(state.modal.is_none());

        let _ = fs::remove_dir_all(&paths.config_dir);
    }

    #[test]
    fn settings_navigation_advanced_toggle_and_acl_flush_on_back() {
        let paths = tmp_paths("nav_settings");
        save_config_at(&paths, &SilentInputConfig::default()).unwrap();

        let (mut state, _) = ControlCenterState::new_with_paths(paths.clone());
        assert_eq!(state.page, super::ActivePage::Home);
        assert!(!state.advanced_expanded);
        assert!(!state.show_key);

        let _ = state.update(Message::ToggleShowKey);
        assert!(state.show_key);
        let _ = state.update(Message::ToggleShowKey);
        assert!(!state.show_key);

        let _ = state.update(Message::OpenSettings);
        assert_eq!(state.page, super::ActivePage::Settings);

        let _ = state.update(Message::ToggleAdvancedSettings);
        assert!(state.advanced_expanded);
        let _ = state.update(Message::ToggleAdvancedSettings);
        assert!(!state.advanced_expanded);

        // Editing ACL and returning Home flushes pending ACL save immediately
        let _ = state.update(Message::WhitelistChanged("192.168.50.*".to_string()));
        assert!(state.pending_acl_save);
        let _ = state.update(Message::OpenHome);
        assert_eq!(state.page, super::ActivePage::Home);
        assert!(!state.pending_acl_save);
        assert_eq!(load_config_at(&paths).whitelist, vec!["192.168.50.*"]);

        let _ = fs::remove_dir_all(&paths.config_dir);
    }

    #[test]
    fn acl_mode_switch_between_localhost_and_lan_restarts_service() {
        let paths = tmp_paths("acl_restart");
        save_config_at(&paths, &SilentInputConfig::default()).unwrap();

        let (mut state, _) = ControlCenterState::new_with_paths(paths.clone());
        assert_eq!(state.config.access_control_mode, "localhost_only");

        let whitelist_opt = super::i18n::find_option(&super::i18n::acl_mode_options(false), "whitelist").unwrap();
        let _ = state.update(Message::AclModeSelected(whitelist_opt));
        assert_eq!(state.config.access_control_mode, "whitelist");
        assert!(state
            .toast
            .as_deref()
            .unwrap_or("")
            .contains("restart")
            || state.toast.as_deref().unwrap_or("").contains("重启"));

        let blacklist_opt = super::i18n::find_option(&super::i18n::acl_mode_options(false), "blacklist").unwrap();
        let _ = state.update(Message::AclModeSelected(blacklist_opt));
        assert_eq!(state.config.access_control_mode, "blacklist");
        assert!(!state.toast.as_deref().unwrap_or("").contains("restart"));
        assert!(!state.toast.as_deref().unwrap_or("").contains("重启"));

        let _ = fs::remove_dir_all(&paths.config_dir);
    }
}
