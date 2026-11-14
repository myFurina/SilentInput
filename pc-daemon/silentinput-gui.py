#!/usr/bin/env python3
"""
SilentInput Control Center (GNOME / Libadwaita)
Native Linux desktop UI for managing the SilentInput PC companion daemon.
"""

import os
import sys
import subprocess
import threading
from pathlib import Path

# Add script directory to sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import silentinput_config

import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Gtk, Adw, GLib, Gdk


def _is_zh_locale() -> bool:
    for key in ("LC_ALL", "LC_MESSAGES", "LANG", "LANGUAGE"):
        val = os.environ.get(key, "").strip()
        if val:
            lowered = val.lower()
            if lowered.startswith("zh"):
                return True
            if lowered not in ("c", "c.utf-8", "c.utf8", "posix"):
                return False
    return True


def resolve_gui_is_zh(lang_pref: str | None) -> bool:
    if lang_pref == "zh":
        return True
    if lang_pref == "en":
        return False
    return _is_zh_locale()


_IS_ZH = _is_zh_locale()


def _set_gui_language(lang_pref: str | None) -> bool:
    global _IS_ZH
    _IS_ZH = resolve_gui_is_zh(lang_pref)
    return _IS_ZH


def _t(zh: str, en: str) -> str:
    return zh if _IS_ZH else en

class SilentInputApp(Adw.Application):
    def __init__(self):
        super().__init__(application_id="com.silentinput.controlcenter")
        self.window = None

    def do_activate(self):
        if not self.window:
            self.window = MainWindow(self)
        self.window.present()

class MainWindow(Adw.ApplicationWindow):
    def __init__(self, app):
        self.config = silentinput_config.load_config()
        _set_gui_language(self.config.get("gui_language", "system"))
        super().__init__(application=app, title=_t("SilentInput 控制中心", "SilentInput Control Center"))
        self.set_default_size(540, 720)

        self._probe_in_flight = False
        self._service_action_count = 0
        self._autostart_action_count = 0
        self._config_generation = 0
        self._acl_save_timer_id = None
        self._status_timer_id = None
        self._rendered_ips = []
        self.ip_rows = []
        self.connect("close-request", self.on_close_request)

        self._build_ui()

        # Initial refresh and periodic status update timer
        self.update_status()
        self._status_timer_id = GLib.timeout_add_seconds(3, self.update_status)

    def _build_ui(self):
        self.set_title(_t("SilentInput 控制中心", "SilentInput Control Center"))
        self._rendered_ips = []
        self.ip_rows = []

        # Build UI layout
        main_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.set_content(main_box)

        # HeaderBar
        header = Adw.HeaderBar()
        main_box.append(header)

        # ToastOverlay wrapping Preferences ScrolledWindow
        self.toast_overlay = Adw.ToastOverlay()
        self.toast_overlay.set_vexpand(True)
        main_box.append(self.toast_overlay)

        scrolled = Gtk.ScrolledWindow()
        scrolled.set_vexpand(True)
        self.toast_overlay.set_child(scrolled)

        page = Adw.PreferencesPage()
        scrolled.set_child(page)

        # --- Group 1: 运行状态 ---
        status_group = Adw.PreferencesGroup(title=_t("服务运行状态", "Service Status"))
        page.add(status_group)

        self.service_row = Adw.SwitchRow(title=_t("后台服务", "Background Service"))
        self.service_row.connect("notify::active", self.on_service_switch_toggled)
        status_group.add(self.service_row)

        self.autostart_row = Adw.SwitchRow(title=_t("开机自动启动", "Start at Login"))
        self.autostart_row.set_subtitle(_t("登录桌面时自动启动后台输入服务", "Launch background input daemon on desktop login"))
        self.autostart_row.connect("notify::active", self.on_autostart_toggled)
        status_group.add(self.autostart_row)

        # --- Group 2: 配对密钥 (无线连接必须验证) ---
        sec_group = Adw.PreferencesGroup(title=_t("安全与配对", "Security & Pairing"))
        page.add(sec_group)

        self.token_row = Adw.ActionRow(title=_t("配对密钥", "Pairing Key"))
        token_val = self.config.get("auth_token", "")
        exp_txt = silentinput_config.get_token_status_text(self.config, is_zh=_IS_ZH)
        self.token_row.set_subtitle(f"{token_val}  ({exp_txt})")

        btn_copy_token = Gtk.Button.new_from_icon_name("edit-copy-symbolic")
        btn_copy_token.set_tooltip_text(_t("复制密钥", "Copy Pairing Key"))
        btn_copy_token.set_valign(Gtk.Align.CENTER)
        btn_copy_token.connect("clicked", self.on_copy_token)
        self.token_row.add_suffix(btn_copy_token)

        btn_regen_token = Gtk.Button.new_from_icon_name("view-refresh-symbolic")
        btn_regen_token.set_tooltip_text(_t("重新生成随机密钥", "Regenerate Random Key"))
        btn_regen_token.set_valign(Gtk.Align.CENTER)
        btn_regen_token.connect("clicked", self.on_regenerate_token)
        self.token_row.add_suffix(btn_regen_token)
        sec_group.add(self.token_row)

        self.expire_row = Adw.ComboRow(title=_t("密钥有效期", "Key Expiration"))
        self.expire_modes = [
            _t("永不过期", "Never expires"),
            _t("24 小时自动轮换", "Rotate every 24h"),
            _t("7 天自动轮换", "Rotate every 7d"),
            _t("1 小时临时", "1h temporary"),
        ]
        self.expire_keys = ["never", "24h", "7d", "1h"]
        expire_model = Gtk.StringList.new(self.expire_modes)
        self.expire_row.set_model(expire_model)
        current_expire = self.config.get("token_expire_mode", "never")
        sel_expire_idx = self.expire_keys.index(current_expire) if current_expire in self.expire_keys else 0
        self.expire_row.set_selected(sel_expire_idx)
        self.expire_row.connect("notify::selected", self.on_expire_mode_changed)
        sec_group.add(self.expire_row)

        self.switch_usb_no_auth = Adw.SwitchRow(title=_t("USB 有线免密直连", "USB Trusted Direct Connect"))
        self.switch_usb_no_auth.set_subtitle(_t("通过 USB 数据线连接自动信任", "Auto-trust local USB adb reverse connections"))
        self.switch_usb_no_auth.set_active(self.config.get("allow_usb_unauthenticated", True))
        self.switch_usb_no_auth.connect("notify::active", self.on_usb_auth_changed)
        sec_group.add(self.switch_usb_no_auth)

        self.switch_tls = Adw.SwitchRow(title=_t("TLS 传输加密 (WSS)", "TLS Encryption (WSS)"))
        self.switch_tls.set_subtitle(_t("强制端到端加密（手机端需同步开启；若关闭两端均需关闭）", "Require E2E TLS encryption (must match mobile app setting)"))
        self.switch_tls.set_active(self.config.get("enable_tls", True))
        self.switch_tls.connect("notify::active", self.on_tls_changed)
        sec_group.add(self.switch_tls)

        # --- Group 3: 电脑 IP 地址列表 (支持单独复制每一个 IP) ---
        self.ip_group = Adw.PreferencesGroup(title=_t("电脑 IP (局域网，点击右侧按钮复制)", "PC IP Addresses (LAN, click button to copy)"))
        page.add(self.ip_group)
        self.ip_rows = []
        self.usb_row = Adw.ActionRow(title=_t("USB 有线状态", "USB Status"))
        self.ip_group.add(self.usb_row)

        # --- Group 4: 访问控制 (白名单 / 黑名单 互斥) ---
        acl_group = Adw.PreferencesGroup(title=_t("访问控制 (白黑名单互斥)", "Access Control"))
        page.add(acl_group)

        self.bind_mode_row = Adw.ComboRow(title=_t("控制模式", "Access Mode"))
        self.bind_modes = [
            _t("白名单模式 (仅允许白名单 IP)", "Whitelist (allow listed IPs only)"),
            _t("黑名单模式 (仅拒绝黑名单 IP)", "Blacklist (block listed IPs only)"),
            _t("允许所有网络 (0.0.0.0)", "Allow all networks (0.0.0.0)"),
            _t("仅本机 USB (127.0.0.1)", "Localhost USB only (127.0.0.1)"),
        ]
        self.mode_keys = ["whitelist", "blacklist", "all", "localhost_only"]
        model = Gtk.StringList.new(self.bind_modes)
        self.bind_mode_row.set_model(model)
        current_mode = self.config.get("access_control_mode", "whitelist")
        selected_idx = self.mode_keys.index(current_mode) if current_mode in self.mode_keys else 0
        self.bind_mode_row.set_selected(selected_idx)
        self.bind_mode_row.connect("notify::selected", self.on_bind_mode_changed)
        acl_group.add(self.bind_mode_row)

        self.whitelist_row = Adw.EntryRow(title=_t("IP 白名单 (逗号分隔，支持 * 通配符)", "IP Whitelist (comma-separated, * wildcard supported)"))
        self.whitelist_row.set_text(", ".join(self.config.get("whitelist", [])))
        self.whitelist_row.connect("changed", self.on_whitelist_changed)
        acl_group.add(self.whitelist_row)

        self.blacklist_row = Adw.EntryRow(title=_t("IP 黑名单 (逗号分隔)", "IP Blacklist (comma-separated)"))
        self.blacklist_row.set_text(", ".join(self.config.get("blacklist", [])))
        self.blacklist_row.connect("changed", self.on_blacklist_changed)
        acl_group.add(self.blacklist_row)

        self.update_acl_fields_visibility(current_mode)

        # --- Group 5: 快捷操作与维护 ---
        actions_group = Adw.PreferencesGroup(title=_t("操作与维护", "Actions & Maintenance"))
        page.add(actions_group)

        self.lang_row = Adw.ComboRow(title=_t("界面语言", "Interface Language"))
        self.lang_labels = [_t("跟随系统", "Follow System"), "简体中文", "English"]
        self.lang_keys = ["system", "zh", "en"]
        self.lang_row.set_model(Gtk.StringList.new(self.lang_labels))
        current_lang = self.config.get("gui_language", "system")
        sel_lang_idx = self.lang_keys.index(current_lang) if current_lang in self.lang_keys else 0
        self.lang_row.set_selected(sel_lang_idx)
        self.lang_row.connect("notify::selected", self.on_language_changed)
        actions_group.add(self.lang_row)

        restart_row = Adw.ActionRow(title=_t("重启服务", "Restart Service"))
        btn_restart = Gtk.Button(label=_t("重启", "Restart"))
        btn_restart.set_valign(Gtk.Align.CENTER)
        btn_restart.connect("clicked", self.on_restart_service)
        restart_row.add_suffix(btn_restart)
        actions_group.add(restart_row)

        logs_row = Adw.ActionRow(title=_t("运行日志", "Service Logs"))
        btn_logs = Gtk.Button(label=_t("查看日志", "View Logs"))
        btn_logs.set_valign(Gtk.Align.CENTER)
        btn_logs.connect("clicked", self.on_show_logs)
        logs_row.add_suffix(btn_logs)
        actions_group.add(logs_row)

        uninstall_row = Adw.ActionRow(title=_t("卸载 SilentInput", "Uninstall SilentInput"))
        uninstall_row.set_subtitle(_t("停止服务并从系统中移除快捷方式", "Stop daemon and remove desktop integration"))
        btn_uninstall = Gtk.Button(label=_t("卸载", "Uninstall"))
        btn_uninstall.add_css_class("destructive-action")
        btn_uninstall.set_valign(Gtk.Align.CENTER)
        btn_uninstall.connect("clicked", self.on_uninstall_clicked)
        uninstall_row.add_suffix(btn_uninstall)
        actions_group.add(uninstall_row)

    def on_language_changed(self, combo_row, param):
        idx = combo_row.get_selected()
        if 0 <= idx < len(self.lang_keys):
            new_lang = self.lang_keys[idx]
            if new_lang == self.config.get("gui_language", "system"):
                return
            self._config_generation += 1
            self.config["gui_language"] = new_lang
            silentinput_config.save_config(self.config)
            _set_gui_language(new_lang)
            self._build_ui()
            self.update_status()
            self.show_toast(_t("界面语言已切换", "Interface language updated"))

    def on_close_request(self, _win):
        if self._status_timer_id is not None:
            GLib.source_remove(self._status_timer_id)
            self._status_timer_id = None
        if self._acl_save_timer_id is not None:
            GLib.source_remove(self._acl_save_timer_id)
            self._acl_save_timer_id = None
            silentinput_config.save_config(self.config)
        return False

    def update_acl_fields_visibility(self, mode: str):
        self.whitelist_row.set_visible(mode == "whitelist")
        self.blacklist_row.set_visible(mode == "blacklist")

    def update_status(self):
        if self._probe_in_flight:
            return True
        self._probe_in_flight = True
        gen_before = self._config_generation

        def _worker():
            try:
                cfg = silentinput_config.load_config()
                running, pid = silentinput_config.is_daemon_running()
                autostart = silentinput_config.is_autostart_enabled()
                usb_devices = silentinput_config.get_connected_usb_devices()
                ips = silentinput_config.get_all_host_ips()
                GLib.idle_add(self._apply_status_update, cfg, running, pid, autostart, usb_devices, ips, gen_before)
            except Exception:
                GLib.idle_add(self._finish_probe)

        threading.Thread(target=_worker, daemon=True).start()
        return True  # continue GLib timeout

    def _finish_probe(self):
        self._probe_in_flight = False
        return False

    def _sync_config_widgets(self):
        cfg_lang = self.config.get("gui_language", "system")
        lang_idx = self.lang_keys.index(cfg_lang) if cfg_lang in self.lang_keys else 0
        self.lang_row.handler_block_by_func(self.on_language_changed)
        self.lang_row.set_selected(lang_idx)
        self.lang_row.handler_unblock_by_func(self.on_language_changed)

        exp = self.config.get("token_expire_mode", "never")
        exp_idx = self.expire_keys.index(exp) if exp in self.expire_keys else 0
        self.expire_row.handler_block_by_func(self.on_expire_mode_changed)
        self.expire_row.set_selected(exp_idx)
        self.expire_row.handler_unblock_by_func(self.on_expire_mode_changed)

        self.switch_usb_no_auth.handler_block_by_func(self.on_usb_auth_changed)
        self.switch_usb_no_auth.set_active(bool(self.config.get("allow_usb_unauthenticated", True)))
        self.switch_usb_no_auth.handler_unblock_by_func(self.on_usb_auth_changed)

        self.switch_tls.handler_block_by_func(self.on_tls_changed)
        self.switch_tls.set_active(bool(self.config.get("enable_tls", True)))
        self.switch_tls.handler_unblock_by_func(self.on_tls_changed)

        mode = self.config.get("access_control_mode", "whitelist")
        mode_idx = self.mode_keys.index(mode) if mode in self.mode_keys else 0
        self.bind_mode_row.handler_block_by_func(self.on_bind_mode_changed)
        self.bind_mode_row.set_selected(mode_idx)
        self.bind_mode_row.handler_unblock_by_func(self.on_bind_mode_changed)
        self.update_acl_fields_visibility(mode)

        wl_text = ", ".join(self.config.get("whitelist", []))
        if self.whitelist_row.get_text() != wl_text:
            self.whitelist_row.handler_block_by_func(self.on_whitelist_changed)
            self.whitelist_row.set_text(wl_text)
            self.whitelist_row.handler_unblock_by_func(self.on_whitelist_changed)

        bl_text = ", ".join(self.config.get("blacklist", []))
        if self.blacklist_row.get_text() != bl_text:
            self.blacklist_row.handler_block_by_func(self.on_blacklist_changed)
            self.blacklist_row.set_text(bl_text)
            self.blacklist_row.handler_unblock_by_func(self.on_blacklist_changed)

    def _apply_status_update(self, cfg, running, pid, autostart, usb_devices, ips, gen_before):
        self._probe_in_flight = False
        # Do not overwrite local config if user edited settings while background probe ran
        if gen_before == self._config_generation and self._acl_save_timer_id is None:
            self.config = cfg
            self._sync_config_widgets()

        if self._service_action_count == 0:
            self.service_row.handler_block_by_func(self.on_service_switch_toggled)
            self.service_row.set_active(running)
            self.service_row.handler_unblock_by_func(self.on_service_switch_toggled)

            if running:
                pid_info = f" (PID: {pid})" if pid > 0 else ""
                self.service_row.set_subtitle(_t(f"正在运行{pid_info}", f"Running{pid_info}"))
            else:
                self.service_row.set_subtitle(_t("已停止", "Stopped"))

        if self._autostart_action_count == 0:
            self.autostart_row.handler_block_by_func(self.on_autostart_toggled)
            self.autostart_row.set_active(autostart)
            self.autostart_row.handler_unblock_by_func(self.on_autostart_toggled)
            self.autostart_row.set_subtitle(
                _t("已启用 · 登录桌面时自动启动后台输入服务", "Enabled · Starts daemon on desktop login")
                if autostart
                else _t("已关闭 · 仅手动打开服务时运行", "Disabled · Manual start only")
            )

        self.update_token_display()

        # Update USB device info
        if usb_devices:
            self.usb_row.set_subtitle(_t(f"已连接: {', '.join(usb_devices)}", f"Connected: {', '.join(usb_devices)}"))
        else:
            self.usb_row.set_subtitle(_t("未连接", "Disconnected"))

        # Update Host IPs: create separate row with copy button for each IP
        current_ip_set = set(ips)
        existing_row_ips = set(getattr(self, "_rendered_ips", []))

        if current_ip_set != existing_row_ips:
            # Remove old IP rows
            for r in self.ip_rows:
                self.ip_group.remove(r)
            self.ip_rows.clear()

            if not ips:
                no_ip_row = Adw.ActionRow(title=_t("未检测到有效局域网 IP", "No LAN IP detected"))
                self.ip_group.add(no_ip_row)
                self.ip_rows.append(no_ip_row)
            else:
                for single_ip in ips:
                    row = Adw.ActionRow(title=single_ip)
                    btn_copy = Gtk.Button.new_from_icon_name("edit-copy-symbolic")
                    btn_copy.set_tooltip_text(_t(f"复制 {single_ip}", f"Copy {single_ip}"))
                    btn_copy.set_valign(Gtk.Align.CENTER)
                    btn_copy.connect("clicked", self.make_copy_ip_handler(single_ip))
                    row.add_suffix(btn_copy)
                    self.ip_group.add(row)
                    self.ip_rows.append(row)
            self._rendered_ips = list(ips)

        return False

    def make_copy_ip_handler(self, ip_str: str):
        def _handler(button):
            clipboard = Gdk.Display.get_default().get_clipboard()
            clipboard.set(ip_str)
            self.show_toast(_t(f"已复制 IP: {ip_str}", f"Copied IP: {ip_str}"))
        return _handler

    def on_service_switch_toggled(self, switch_row, param):
        active = switch_row.get_active()
        self._service_action_count += 1
        self.service_row.set_subtitle(_t("正在启动...", "Starting...") if active else _t("正在停止...", "Stopping..."))

        def _worker():
            try:
                if active:
                    silentinput_config.service_start()
                else:
                    silentinput_config.service_stop()
            finally:
                def _done():
                    self._service_action_count = max(0, self._service_action_count - 1)
                    self.update_status()
                    return False
                GLib.idle_add(_done)

        threading.Thread(target=_worker, daemon=True).start()

    def on_autostart_toggled(self, switch_row, param):
        active = switch_row.get_active()
        cur_is_zh = _IS_ZH
        self._autostart_action_count += 1
        self.autostart_row.set_subtitle(
            _t("正在开启开机启动...", "Enabling autostart...") if active else _t("正在关闭开机启动...", "Disabling autostart...")
        )

        def _worker():
            ok, msg = False, ""
            try:
                ok, msg = silentinput_config.set_autostart_enabled(active, is_zh=cur_is_zh)
            finally:
                def _done():
                    self._autostart_action_count = max(0, self._autostart_action_count - 1)
                    if msg:
                        self.show_toast(msg)
                    if not ok:
                        self.update_status()
                    else:
                        self.autostart_row.set_subtitle(
                            _t("已启用 · 登录桌面时自动启动后台输入服务", "Enabled · Starts daemon on desktop login")
                            if active
                            else _t("已关闭 · 仅手动打开服务时运行", "Disabled · Manual start only")
                        )
                    return False
                GLib.idle_add(_done)

        threading.Thread(target=_worker, daemon=True).start()

    def on_copy_token(self, button):
        token = self.config.get("auth_token", "")
        if token:
            clipboard = Gdk.Display.get_default().get_clipboard()
            clipboard.set(token)
            self.show_toast(_t("配对密钥已复制", "Pairing key copied"))

    def on_regenerate_token(self, button):
        self._config_generation += 1
        new_token = silentinput_config.regenerate_token()
        self.config = silentinput_config.load_config()
        self.update_token_display()
        self.show_toast(_t(f"已生成新密钥: {new_token}", f"Generated new key: {new_token}"))

    def on_expire_mode_changed(self, combo_row, param):
        idx = combo_row.get_selected()
        if 0 <= idx < len(self.expire_keys):
            self._config_generation += 1
            self.config["token_expire_mode"] = self.expire_keys[idx]
            import time
            self.config["token_created_at"] = time.time()
            silentinput_config.save_config(self.config)
            self.update_token_display()
            self.show_toast(_t(f"有效期已设为: {self.expire_modes[idx]}", f"Expiration set to: {self.expire_modes[idx]}"))

    def update_token_display(self):
        token = self.config.get("auth_token", "")
        exp_txt = silentinput_config.get_token_status_text(self.config, is_zh=_IS_ZH)
        self.token_row.set_subtitle(f"{token}  ({exp_txt})")

    def on_usb_auth_changed(self, switch_row, param):
        self._config_generation += 1
        self.config["allow_usb_unauthenticated"] = switch_row.get_active()
        silentinput_config.save_config(self.config)

    def on_tls_changed(self, switch_row, param):
        self._config_generation += 1
        enabled = switch_row.get_active()
        self.config["enable_tls"] = enabled
        silentinput_config.save_config(self.config)
        self.show_toast(_t("已更新加密设置，正在重启服务...", "TLS setting updated, restarting service..."))
        self.on_restart_service(None)

    def on_bind_mode_changed(self, combo_row, param):
        idx = combo_row.get_selected()
        if 0 <= idx < len(self.mode_keys):
            self._config_generation += 1
            mode = self.mode_keys[idx]
            self.config["access_control_mode"] = mode
            silentinput_config.save_config(self.config)
            self.update_acl_fields_visibility(mode)
            self.show_toast(_t("控制模式已更新", "Access control mode updated"))

    def _schedule_acl_save(self):
        self._config_generation += 1
        if self._acl_save_timer_id is not None:
            GLib.source_remove(self._acl_save_timer_id)
        def _flush():
            self._acl_save_timer_id = None
            silentinput_config.save_config(self.config)
            return False
        self._acl_save_timer_id = GLib.timeout_add(400, _flush)

    def on_whitelist_changed(self, entry_row):
        text = entry_row.get_text()
        parts = [p.strip() for p in text.split(",") if p.strip()]
        self.config["whitelist"] = parts
        self._schedule_acl_save()

    def on_blacklist_changed(self, entry_row):
        text = entry_row.get_text()
        parts = [p.strip() for p in text.split(",") if p.strip()]
        self.config["blacklist"] = parts
        self._schedule_acl_save()

    def on_restart_service(self, button):
        self._service_action_count += 1
        self.show_toast(_t("服务正在重启...", "Restarting service..."))
        def _worker():
            try:
                silentinput_config.service_restart()
            finally:
                def _done():
                    self._service_action_count = max(0, self._service_action_count - 1)
                    self.update_status()
                    self.show_toast(_t("服务已重启", "Service restarted"))
                    return False
                GLib.idle_add(_done)
        threading.Thread(target=_worker, daemon=True).start()

    def on_show_logs(self, button):
        log_file = silentinput_config.LOG_FILE

        def read_log_tail():
            if not log_file.exists():
                return _t("暂无日志", "No logs yet")
            try:
                size = log_file.stat().st_size
                with open(log_file, "rb") as f:
                    if size > 32768:
                        f.seek(size - 32768)
                    raw = f.read()
                lines = raw.decode("utf-8", errors="replace").splitlines()
                return "\n".join(lines[-80:]) if lines else _t("日志为空", "Log is empty")
            except Exception as e:
                return _t(f"读取日志失败: {e}", f"Failed to read logs: {e}")

        log_win = Adw.Window(
            transient_for=self,
            modal=True,
            title=_t("运行日志 (最近 80 行)", "Service Logs (Last 80 lines)")
        )
        log_win.set_default_size(640, 480)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        log_win.set_content(box)

        header = Adw.HeaderBar()
        btn_refresh = Gtk.Button.new_from_icon_name("view-refresh-symbolic")
        btn_refresh.set_tooltip_text(_t("刷新日志", "Refresh Logs"))
        header.pack_start(btn_refresh)
        box.append(header)

        scrolled = Gtk.ScrolledWindow()
        scrolled.set_vexpand(True)
        scrolled.set_hexpand(True)
        scrolled.set_margin_start(12)
        scrolled.set_margin_end(12)
        scrolled.set_margin_top(8)
        scrolled.set_margin_bottom(8)

        text_view = Gtk.TextView()
        text_view.set_editable(False)
        text_view.set_cursor_visible(False)
        text_view.set_monospace(True)
        text_view.set_wrap_mode(Gtk.WrapMode.WORD_CHAR)
        text_view.set_top_margin(8)
        text_view.set_bottom_margin(8)
        text_view.set_left_margin(8)
        text_view.set_right_margin(8)

        def load_and_scroll():
            buf = text_view.get_buffer()
            buf.set_text(read_log_tail())
            end_iter = buf.get_end_iter()
            mark = buf.get_mark("end")
            if mark is None:
                mark = buf.create_mark("end", end_iter, False)
            else:
                buf.move_mark(mark, end_iter)
            text_view.scroll_mark_onscreen(mark)

        load_and_scroll()
        btn_refresh.connect("clicked", lambda _btn: load_and_scroll())

        scrolled.set_child(text_view)
        box.append(scrolled)

        bottom_bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        bottom_bar.set_halign(Gtk.Align.END)
        bottom_bar.set_margin_start(12)
        bottom_bar.set_margin_end(12)
        bottom_bar.set_margin_bottom(12)
        btn_close = Gtk.Button(label=_t("关闭", "Close"))
        btn_close.add_css_class("suggested-action")
        btn_close.connect("clicked", lambda _btn: log_win.close())
        bottom_bar.append(btn_close)
        box.append(bottom_bar)

        log_win.present()

    def on_uninstall_clicked(self, button):
        pkg_cmd = silentinput_config.get_package_uninstall_command()
        is_pkg = silentinput_config.is_system_package_install() or pkg_cmd is not None
        if is_pkg:
            cmd_hint = pkg_cmd or "sudo apt remove silentinput"
            dialog = Adw.MessageDialog(
                transient_for=self,
                heading=_t("通过包管理器卸载", "Uninstall via Package Manager"),
                body=_t(
                    f"SilentInput 当前由系统包管理器安装。\n可在终端运行以下命令完整卸载：\n\n{cmd_hint}",
                    f"SilentInput was installed via your system package manager.\nRun the command below in a terminal to uninstall:\n\n{cmd_hint}",
                ),
            )
            dialog.add_response("cancel", _t("关闭", "Close"))
            dialog.add_response("stop", _t("仅停止服务并退出", "Stop Service & Quit"))

            def on_pkg_response(_d, resp):
                if resp == "stop":
                    self.show_toast(_t("正在停止服务...", "Stopping service..."))
                    def _worker():
                        try:
                            silentinput_config.service_stop()
                        finally:
                            GLib.idle_add(lambda: (self.close(), False)[1])
                    threading.Thread(target=_worker, daemon=True).start()

            dialog.connect("response", on_pkg_response)
            dialog.present()
            return

        dialog = Adw.MessageDialog(
            transient_for=self,
            heading=_t("确认卸载 SilentInput？", "Uninstall SilentInput?"),
            body=_t("这将停止正在运行的服务，并移除启动脚本与桌面图标。", "This will stop the running daemon and remove startup scripts and desktop shortcuts."),
        )
        dialog.add_response("cancel", _t("取消", "Cancel"))
        dialog.add_response("uninstall", _t("确认卸载", "Uninstall"))
        dialog.set_response_appearance("uninstall", Adw.ResponseAppearance.DESTRUCTIVE)

        def on_response(dlg, response):
            if response == "uninstall":
                self.show_toast(_t("正在卸载...", "Uninstalling..."))
                def _worker():
                    try:
                        uninstall_script = silentinput_config.SCRIPT_DIR / "uninstall.sh"
                        if uninstall_script.exists():
                            subprocess.run(["bash", str(uninstall_script)], check=False)
                        else:
                            silentinput_config.stop_daemon_process()
                    finally:
                        GLib.idle_add(lambda: (self.close(), False)[1])
                threading.Thread(target=_worker, daemon=True).start()

        dialog.connect("response", on_response)
        dialog.present()

    def show_toast(self, message: str):
        self.toast_overlay.add_toast(Adw.Toast.new(message))

if __name__ == "__main__":
    app = SilentInputApp()
    app.run(sys.argv)
