mod helpers;
mod i18n;
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
use silentinput_core::config::{
    generate_token_hex, get_token_status_text, load_config_at, regenerate_token_at,
    save_config_at, unix_timestamp_secs, SilentInputConfig,
};
use silentinput_core::i18n::{resolve_gui_is_zh, t};
use silentinput_core::logs::read_log_tail_at;
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
enum ModalView {
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
    ToggleService(bool),
    ServiceActionFinished(bool, String),
    ToggleAutostart(bool),
    AutostartActionFinished(bool, String),
    CopyToken,
    RegenerateToken,
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
            Message::RegenerateToken => {
                self.config_generation += 1;
                let new_tok = regenerate_token_at(&self.paths);
                self.config = load_config_at(&self.paths);
                let msg = if self.is_zh {
                    format!("已生成新密钥: {new_tok}")
                } else {
                    format!("Generated new key: {new_tok}")
                };
                self.show_toast(msg)
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
                self.config_generation += 1;
                self.config.access_control_mode = opt.key.to_string();
                let _ = save_config_at(&self.paths, &self.config);
                self.show_toast(t(self.is_zh, "控制模式已更新", "Access control mode updated"))
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
                    auth_token: generate_token_hex(4),
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

        let esc_sub = if self.modal.is_some() && !self.uninstall_in_flight {
            keyboard::listen().filter_map(|event| match event {
                keyboard::Event::KeyPressed {
                    key: keyboard::Key::Named(keyboard::key::Named::Escape),
                    ..
                } => Some(Message::CloseModal),
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

    fn view(&self) -> Element<'_, Message> {
        let zh = self.is_zh;
        let is_dark = self.is_dark();
        let muted = style::muted_color(is_dark);
        let success = style::success_color(is_dark);
        let exp_opts = expire_options(zh);
        let acl_opts = acl_mode_options(zh);
        let lang_opts = language_options(zh);
        let theme_opts = theme_options(zh);

        let running_label = if self.running {
            if self.pid > 0 {
                if zh {
                    format!("正在运行 (PID: {})", self.pid)
                } else {
                    format!("Running (PID: {})", self.pid)
                }
            } else {
                t(zh, "正在运行", "Running").to_string()
            }
        } else {
            t(zh, "已停止", "Stopped").to_string()
        };

        // Group 1: Service Status
        let service_card = section_card(
            t(zh, "服务运行状态", "Service Status"),
            is_dark,
            column![
                row![
                    column![
                        text(t(zh, "后台服务", "Background Service")).size(15),
                        text(running_label).size(12).color(if self.running {
                            success
                        } else {
                            muted
                        }),
                    ]
                    .spacing(2),
                    space::horizontal(),
                    toggler(self.running)
                        .on_toggle_maybe(if self.service_action_count == 0 {
                            Some(Message::ToggleService)
                        } else {
                            None
                        })
                        .size(22),
                ]
                .align_y(Alignment::Center),
                row![
                    column![
                        text(t(zh, "开机自动启动", "Start at Login")).size(15),
                        text(if self.autostart {
                            t(
                                zh,
                                "已启用 · 登录桌面时自动启动后台输入服务",
                                "Enabled · Starts daemon on desktop login",
                            )
                        } else {
                            t(
                                zh,
                                "已关闭 · 仅手动打开服务时运行",
                                "Disabled · Manual start only",
                            )
                        })
                        .size(12)
                        .color(muted),
                    ]
                    .spacing(2)
                    .width(Length::Fill),
                    toggler(self.autostart)
                        .on_toggle_maybe(if self.autostart_action_count == 0 {
                            Some(Message::ToggleAutostart)
                        } else {
                            None
                        })
                        .size(22),
                ]
                .align_y(Alignment::Center),
            ]
            .spacing(12),
        );

        // Group 2: Security & Pairing
        let exp_desc = get_token_status_text(&self.config, zh);
        let selected_expire = find_option(&exp_opts, &self.config.token_expire_mode);

        let usb_sub = if self.usb_devices.is_empty() {
            t(
                zh,
                "通过 USB 数据线直连输入（当前未检测到 USB 设备）",
                "Auto-trust USB adb reverse (No USB device connected)",
            )
            .to_string()
        } else if zh {
            format!(
                "通过 USB 数据线直连输入 · 已连接: {} (免配对直连，延迟最低)",
                self.usb_devices.join(", ")
            )
        } else {
            format!(
                "Auto-trust USB adb reverse · Connected: {} (trusted direct, lowest latency)",
                self.usb_devices.join(", ")
            )
        };
        let usb_status_color = if self.usb_devices.is_empty() {
            muted
        } else {
            success
        };

        let security_card = section_card(
            t(zh, "安全与配对", "Security & Pairing"),
            is_dark,
            column![
                row![
                    column![
                        text(t(zh, "配对密钥", "Pairing Key")).size(15),
                        row![
                            text(self.config.auth_token.as_str())
                                .font(self.mono_font)
                                .size(13)
                                .color(muted),
                            text(format!("  ({exp_desc})"))
                                .size(13)
                                .color(muted),
                        ]
                        .align_y(Alignment::Center),
                    ]
                    .spacing(3)
                    .width(Length::Fill),
                    row![
                        button(text(t(zh, "复制", "Copy")).size(13))
                            .style(style::secondary_button_style)
                            .padding([6, 12])
                            .on_press(Message::CopyToken),
                        button(text(t(zh, "重新生成", "Regenerate")).size(13))
                            .style(style::secondary_button_style)
                            .padding([6, 12])
                            .on_press(Message::RegenerateToken),
                    ]
                    .spacing(8),
                ]
                .align_y(Alignment::Center),
                row![
                    text(t(zh, "密钥有效期", "Key Expiration"))
                        .size(15)
                        .width(Length::Fill),
                    pick_list(exp_opts, selected_expire, Message::ExpireModeSelected)
                        .text_size(13)
                        .padding([6, 10]),
                ]
                .align_y(Alignment::Center),
                row![
                    column![
                        text(t(zh, "USB 有线免密直连", "USB Trusted Direct Connect")).size(15),
                        text(usb_sub)
                            .size(12)
                            .color(usb_status_color),
                    ]
                    .spacing(2)
                    .width(Length::Fill),
                    toggler(self.config.allow_usb_unauthenticated)
                        .on_toggle(Message::ToggleUsbNoAuth)
                        .size(22),
                ]
                .align_y(Alignment::Center),
                row![
                    column![
                        text(t(zh, "TLS 传输加密 (WSS)", "TLS Encryption (WSS)")).size(15),
                        text(t(
                            zh,
                            "强制端到端加密（手机端与电脑端需保持一致）",
                            "Require E2E TLS encryption (must match mobile app setting)",
                        ))
                        .size(12)
                        .color(muted),
                    ]
                    .spacing(2)
                    .width(Length::Fill),
                    toggler(self.config.enable_tls)
                        .on_toggle_maybe(if self.service_action_count == 0 {
                            Some(Message::ToggleTls)
                        } else {
                            None
                        })
                        .size(22),
                ]
                .align_y(Alignment::Center),
            ]
            .spacing(12),
        );

        // Group 3: Network & IPs
        let mut net_col = column![].spacing(8);

        if self.config.access_control_mode == "localhost_only" {
            net_col = net_col.push(
                container(
                    text(t(
                        zh,
                        "💡 当前访问控制为「仅限本机」最小权限模式（局域网不可连接，USB 可用）。若手机需通过 Wi-Fi 局域网输入，请在下方访问控制切换为「局域网白名单」。",
                        "💡 Access control is 'Localhost Only' (LAN blocked, USB active). Switch to 'Whitelist' below if connecting via Wi-Fi.",
                    ))
                    .size(12)
                    .color(muted),
                )
                .style(style::subtle_box_style)
                .padding([8, 10]),
            );
        }

        if self.host_ips.is_empty() {
            net_col = net_col.push(
                text(t(zh, "未检测到有效局域网 IP", "No LAN IP detected"))
                    .size(13)
                    .color(muted),
            );
        } else {
            for ip in &self.host_ips {
                let ip_row = container(
                    row![
                        text(ip.clone()).font(self.mono_font).size(14),
                        space::horizontal(),
                        button(text(t(zh, "复制 IP", "Copy IP")).size(12))
                            .style(style::secondary_button_style)
                            .padding([5, 10])
                            .on_press(Message::CopyIp(ip.clone())),
                    ]
                    .align_y(Alignment::Center),
                )
                .style(style::subtle_box_style)
                .padding([8, 12]);
                net_col = net_col.push(ip_row);
            }
        }

        let network_card = section_card(
            t(
                zh,
                "电脑局域网 IP (手机 Wi-Fi 局域网输入)",
                "PC LAN IP Addresses (Wi-Fi Input)",
            ),
            is_dark,
            net_col,
        );

        // Group 4: Access Control
        let selected_acl = find_option(&acl_opts, &self.config.access_control_mode);
        let mut acl_col = column![row![
            text(t(zh, "控制模式", "Access Mode"))
                .size(15)
                .width(Length::Fill),
            pick_list(acl_opts, selected_acl, Message::AclModeSelected)
                .text_size(13)
                .padding([6, 10]),
        ]
        .align_y(Alignment::Center)]
        .spacing(10);

        match self.config.access_control_mode.as_str() {
            "whitelist" => {
                let invalid_whitelist = find_invalid_ip_patterns(&parse_ip_list(&self.whitelist_input));
                let mut wl_col = column![
                    text(t(
                        zh,
                        "IP 白名单 (逗号分隔，支持 * 通配符如 192.168.* 或 CIDR 如 172.16.0.0/12)",
                        "IP Whitelist (comma-separated, * wildcard or CIDR e.g. 172.16.0.0/12)",
                    ))
                    .size(12)
                    .color(muted),
                    text_input("127.0.0.1, 192.168.*, 172.16.0.0/12", &self.whitelist_input)
                        .on_input(Message::WhitelistChanged)
                        .style(style::input_style)
                        .padding([8, 10])
                        .size(13),
                ]
                .spacing(4);

                if !invalid_whitelist.is_empty() {
                    let err_items = invalid_whitelist
                        .iter()
                        .map(|(p, _)| format!("\"{p}\""))
                        .collect::<Vec<_>>()
                        .join(", ");
                    wl_col = wl_col.push(
                        text(if zh {
                            format!("⚠️ 发现无法识别的网段格式: {err_items} (该规则将无法匹配任何设备，请检查修正)")
                        } else {
                            format!("⚠️ Unrecognized subnet format: {err_items} (will not match devices)")
                        })
                        .size(12)
                        .color(style::warning_color(is_dark)),
                    );
                }

                acl_col = acl_col.push(wl_col);
            }
            "blacklist" => {
                let invalid_blacklist = find_invalid_ip_patterns(&parse_ip_list(&self.blacklist_input));
                let mut bl_col = column![
                    text(t(
                        zh,
                        "IP 黑名单 (逗号分隔，命中后将拒绝连接)",
                        "IP Blacklist (comma-separated, matches will be blocked)",
                    ))
                    .size(12)
                    .color(muted),
                    text_input("192.168.1.50", &self.blacklist_input)
                        .on_input(Message::BlacklistChanged)
                        .style(style::input_style)
                        .padding([8, 10])
                        .size(13),
                ]
                .spacing(4);

                if !invalid_blacklist.is_empty() {
                    let err_items = invalid_blacklist
                        .iter()
                        .map(|(p, _)| format!("\"{p}\""))
                        .collect::<Vec<_>>()
                        .join(", ");
                    bl_col = bl_col.push(
                        text(if zh {
                            format!("⚠️ 发现无法识别的黑名单格式: {err_items} (该规则将无法生效)")
                        } else {
                            format!("⚠️ Unrecognized blacklist format: {err_items} (will not take effect)")
                        })
                        .size(12)
                        .color(style::warning_color(is_dark)),
                    );
                }

                acl_col = acl_col.push(bl_col);
            }
            _ => {}
        }

        let acl_card = section_card(
            t(zh, "访问控制 (白黑名单互斥)", "Access Control"),
            is_dark,
            acl_col,
        );

        // Group 5: Actions & Maintenance
        let selected_lang = find_option(&lang_opts, &self.config.gui_language);
        let selected_theme = find_option(&theme_opts, &self.config.gui_theme);
        let maintenance_card = section_card(
            t(zh, "操作与维护", "Actions & Maintenance"),
            is_dark,
            column![
                row![
                    text(t(zh, "界面语言", "Interface Language"))
                        .size(15)
                        .width(Length::Fill),
                    pick_list(lang_opts, selected_lang, Message::LanguageSelected)
                        .text_size(13)
                        .padding([6, 10]),
                ]
                .align_y(Alignment::Center),
                row![
                    text(t(zh, "界面外观", "Appearance"))
                        .size(15)
                        .width(Length::Fill),
                    pick_list(theme_opts, selected_theme, Message::ThemeSelected)
                        .text_size(13)
                        .padding([6, 10]),
                ]
                .align_y(Alignment::Center),
                row![
                    text(t(zh, "重启服务", "Restart Service"))
                        .size(15)
                        .width(Length::Fill),
                    button(text(t(zh, "重启", "Restart")).size(13))
                        .style(style::secondary_button_style)
                        .padding([6, 14])
                        .on_press_maybe(if self.service_action_count == 0 {
                            Some(Message::RestartService)
                        } else {
                            None
                        }),
                ]
                .align_y(Alignment::Center),
                row![
                    text(t(zh, "运行日志", "Service Logs"))
                        .size(15)
                        .width(Length::Fill),
                    button(text(t(zh, "查看日志", "View Logs")).size(13))
                        .style(style::secondary_button_style)
                        .padding([6, 14])
                        .on_press(Message::OpenLogs),
                ]
                .align_y(Alignment::Center),
                row![
                    column![
                        text(t(zh, "恢复出厂设置", "Reset to Factory Defaults")).size(15),
                        text(t(
                            zh,
                            "重置访问控制（仅限本机）、白名单网段与密钥有效期（1小时），并重启服务",
                            "Reset mode (localhost-only), whitelist, 1h key expiration, and restart",
                        ))
                        .size(12)
                        .color(muted),
                    ]
                    .spacing(2)
                    .width(Length::Fill),
                    button(text(t(zh, "恢复默认", "Reset Defaults")).size(13))
                        .style(style::secondary_button_style)
                        .padding([6, 14])
                        .on_press_maybe(if self.service_action_count == 0 {
                            Some(Message::OpenResetConfig)
                        } else {
                            None
                        }),
                ]
                .align_y(Alignment::Center),
                row![
                    column![
                        text(t(zh, "卸载 SilentInput", "Uninstall SilentInput")).size(15),
                        text(t(
                            zh,
                            "停止服务并从系统中移除快捷方式",
                            "Stop daemon and remove desktop integration",
                        ))
                        .size(12)
                        .color(muted),
                    ]
                    .spacing(2)
                    .width(Length::Fill),
                    button(text(t(zh, "卸载", "Uninstall")).size(13))
                        .style(style::danger_button_style)
                        .padding([6, 14])
                        .on_press(Message::OpenUninstall),
                ]
                .align_y(Alignment::Center),
            ]
            .spacing(12),
        );

        let header = row![
            text(self.header_title())
            .size(20),
            space::horizontal(),
            button(text(t(zh, "使用指引", "Help Guide")).size(12))
                .style(style::secondary_button_style)
                .padding([4, 10])
                .on_press(Message::OpenHelp),
            container(
                text(if self.running {
                    t(zh, "服务运行中", "Running")
                } else {
                    t(zh, "服务已停止", "Stopped")
                })
                .size(12)
                .color(if self.running {
                    success
                } else {
                    muted
                }),
            )
            .style(style::subtle_box_style)
            .padding([4, 10]),
        ]
        .spacing(8)
        .align_y(Alignment::Center);

        let content_list = scrollable(
            column![
                header,
                service_card,
                security_card,
                network_card,
                acl_card,
                maintenance_card,
            ]
            .spacing(14)
            .padding([16, 18]),
        )
        .height(Length::Fill);

        let mut base_col = column![content_list].height(Length::Fill);
        if let Some(toast_msg) = &self.toast {
            base_col = base_col.push(
                container(
                    container(text(toast_msg.clone()).size(13))
                        .style(style::toast_style)
                        .padding([8, 14]),
                )
                .width(Length::Fill)
                .center_x(Length::Fill)
                .padding([6, 12]),
            );
        }

        let base: Element<'_, Message> = container(base_col)
            .width(Length::Fill)
            .height(Length::Fill)
            .into();

        if let Some(modal) = &self.modal {
            let dialog: Element<'_, Message> = match modal {
                ModalView::Help => container(
                    column![
                        row![
                            text(t(zh, "SilentInput 电脑伴侣 · 使用指引", "SilentInput · User Guide"))
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
                                        "🔌 1. USB 有线直连（推荐，延迟极低且免密）",
                                        "🔌 1. USB Wired Connection (Recommended, Zero Latency)",
                                    ))
                                    .size(14),
                                    text(t(
                                        zh,
                                        "• 手机开启「开发者选项」并开启「USB 调试」。\n• 使用数据线连接手机与电脑，保持电脑后台服务运行。\n• 手机 App 连接模式选择「USB 有线」，无需输入电脑 IP 和密钥，即插即用自动信任直连！",
                                        "• Enable Developer Options and USB Debugging on phone.\n• Connect phone to PC via USB cable.\n• Switch to 'USB Wired' on phone, auto connects without IP or pairing key!",
                                    ))
                                    .size(12)
                                    .color(muted),

                                    space::vertical().height(8),
                                    text(t(zh, "📶 2. Wi-Fi 局域网连接", "📶 2. Wi-Fi LAN Connection"))
                                        .size(14),
                                    text(t(
                                        zh,
                                        "• 确保手机与电脑连接同一 Wi-Fi 网络（同一局域网）。\n• 在下方访问控制中将模式切换为「局域网白名单」（开箱支持 192.168.* 与 10.* 等网段）。\n• 在手机 App 中输入本页显示的电脑局域网 IP 与安全配对密钥即可连接。\n• 放行防火墙 53318/udp 可开启极速加密 UDP 鼠标通道（未放行会自动回退至 TCP）。",
                                        "• Ensure phone and PC are on the same Wi-Fi network.\n• Set Access Control mode to 'Whitelist' below.\n• Enter the PC LAN IP and Pairing Key in mobile app to connect.\n• Allow 53318/udp in firewall for low-latency encrypted UDP mouse (auto-falls back to TCP).",
                                    ))
                                    .size(12)
                                    .color(muted),

                                    space::vertical().height(8),
                                    text(t(zh, "🔵 3. 蓝牙无线连接", "🔵 3. Bluetooth Connection"))
                                        .size(14),
                                    text(t(
                                        zh,
                                        "• 在系统蓝牙设置中，搜索此电脑名称并完成配对。\n• 手机端切换至「蓝牙模式」，点击此电脑即可连接作为外接键盘输入文字与按键。",
                                        "• Pair phone with this PC in system Bluetooth settings.\n• Switch to 'Bluetooth' in mobile app to type directly.",
                                    ))
                                    .size(12)
                                    .color(muted),

                                    space::vertical().height(8),
                                    text(t(zh, "🛡️ 4. 最小权限安全防护", "🛡️ 4. Security & Least Privilege"))
                                        .size(14),
                                    text(t(
                                        zh,
                                        "• 出厂默认采用「仅限本机」最小权限原则，不暴露任何局域网端口，USB 走本机通道依然畅通。\n• 配对密钥默认 1 小时自动轮换过期，强化无线链路新鲜度。\n• 白名单网段支持通配符（如 192.168.*）与 CIDR 掩码（如 172.16.0.0/12）。输入非法网段将实时提示。",
                                        "• Defaults to 'Localhost Only' for zero-trust protection while USB remains active.\n• Pairing key expires in 1 hour by default.\n• Whitelist supports wildcards (192.168.*) and CIDR (172.16.0.0/12).",
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
                        text(t(zh, "确认恢复出厂默认设置？", "Reset to Factory Defaults?"))
                            .size(17),
                        text(t(
                            zh,
                            "此操作将重置以下安全配置：\n• 访问控制模式：恢复为「仅限本机」（遵循最小权限原则）\n• 密钥有效期：恢复为「1小时」（最短有效，定时轮换）\n• 重新生成一套全新的安全配对密钥\n• 白名单网段：恢复为标准默认网段（127.0.0.1, 10.*, 192.168.*, 100.*, 172.16.0.0/12）\n• 自动重启后台输入服务使设置生效",
                            "This will reset the following security settings:\n• Access mode: Localhost Only (least privilege)\n• Key expiration: 1 Hour (auto rotate)\n• Generate a new pairing key\n• Subnets: Standard whitelist\n• Automatically restart background daemon",
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

fn section_card<'a>(
    title: &'a str,
    dark: bool,
    body: impl Into<Element<'a, Message>>,
) -> Element<'a, Message> {
    container(
        column![
            text(title).size(13).color(style::muted_color(dark)),
            body.into(),
        ]
        .spacing(10),
    )
    .style(style::card_style)
    .padding(14)
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
        size: Size::new(540.0, 720.0),
        min_size: Some(Size::new(420.0, 560.0)),
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
    use silentinput_core::config::{load_config_at, save_config_at, SilentInputConfig};
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
        assert_eq!(state.config.auth_token.len(), 8);
        assert!(state.whitelist_input.contains("127.0.0.1"));
        assert!(state.whitelist_input.contains("172.16.0.0/12"));
        assert!(state.toast.is_some());

        // Verify persisted to disk
        let disk_cfg = load_config_at(&paths);
        assert_eq!(disk_cfg.access_control_mode, "localhost_only");
        assert_eq!(disk_cfg.token_expire_mode, "1h");
        assert_eq!(disk_cfg.auth_token, state.config.auth_token);

        let _ = fs::remove_dir_all(&paths.config_dir);
    }
}
