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
        super().__init__(application=app, title="SilentInput 控制中心")
        self.set_default_size(540, 720)

        self.config = silentinput_config.load_config()
        self._probe_in_flight = False
        self._service_action_count = 0
        self._autostart_action_count = 0
        self._config_generation = 0
        self._acl_save_timer_id = None

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
        status_group = Adw.PreferencesGroup(title="服务运行状态")
        page.add(status_group)

        self.service_row = Adw.SwitchRow(title="后台服务")
        self.service_row.connect("notify::active", self.on_service_switch_toggled)
        status_group.add(self.service_row)

        self.autostart_row = Adw.SwitchRow(title="开机自动启动")
        self.autostart_row.set_subtitle("登录桌面时自动启动后台输入服务")
        self.autostart_row.connect("notify::active", self.on_autostart_toggled)
        status_group.add(self.autostart_row)

        # --- Group 2: 配对密钥 (无线连接必须验证) ---
        sec_group = Adw.PreferencesGroup(title="安全与配对")
        page.add(sec_group)

        self.token_row = Adw.ActionRow(title="配对密钥")
        token_val = self.config.get("auth_token", "")
        exp_txt = silentinput_config.get_token_status_text(self.config)
        self.token_row.set_subtitle(f"{token_val}  ({exp_txt})")

        btn_copy_token = Gtk.Button.new_from_icon_name("edit-copy-symbolic")
        btn_copy_token.set_tooltip_text("复制密钥")
        btn_copy_token.set_valign(Gtk.Align.CENTER)
        btn_copy_token.connect("clicked", self.on_copy_token)
        self.token_row.add_suffix(btn_copy_token)

        btn_regen_token = Gtk.Button.new_from_icon_name("view-refresh-symbolic")
        btn_regen_token.set_tooltip_text("重新生成随机密钥")
        btn_regen_token.set_valign(Gtk.Align.CENTER)
        btn_regen_token.connect("clicked", self.on_regenerate_token)
        self.token_row.add_suffix(btn_regen_token)
        sec_group.add(self.token_row)

        self.expire_row = Adw.ComboRow(title="密钥有效期")
        self.expire_modes = ["永不过期", "24 小时自动轮换", "7 天自动轮换", "1 小时临时"]
        self.expire_keys = ["never", "24h", "7d", "1h"]
        expire_model = Gtk.StringList.new(self.expire_modes)
        self.expire_row.set_model(expire_model)
        current_expire = self.config.get("token_expire_mode", "never")
        sel_expire_idx = self.expire_keys.index(current_expire) if current_expire in self.expire_keys else 0
        self.expire_row.set_selected(sel_expire_idx)
        self.expire_row.connect("notify::selected", self.on_expire_mode_changed)
        sec_group.add(self.expire_row)

        self.switch_usb_no_auth = Adw.SwitchRow(title="USB 有线免密直连")
        self.switch_usb_no_auth.set_subtitle("通过 USB 数据线连接自动信任")
        self.switch_usb_no_auth.set_active(self.config.get("allow_usb_unauthenticated", True))
        self.switch_usb_no_auth.connect("notify::active", self.on_usb_auth_changed)
        sec_group.add(self.switch_usb_no_auth)

        self.switch_tls = Adw.SwitchRow(title="TLS 传输加密 (WSS)")
        self.switch_tls.set_subtitle("强制端到端加密（手机端需同步开启；若关闭两端均需关闭）")
        self.switch_tls.set_active(self.config.get("enable_tls", True))
        self.switch_tls.connect("notify::active", self.on_tls_changed)
        sec_group.add(self.switch_tls)

        # --- Group 3: 电脑 IP 地址列表 (支持单独复制每一个 IP) ---
        self.ip_group = Adw.PreferencesGroup(title="电脑 IP (局域网，点击右侧按钮复制)")
        page.add(self.ip_group)
        self.ip_rows = []
        self.usb_row = Adw.ActionRow(title="USB 有线状态")
        self.ip_group.add(self.usb_row)

        # --- Group 4: 访问控制 (白名单 / 黑名单 互斥) ---
        acl_group = Adw.PreferencesGroup(title="访问控制 (白黑名单互斥)")
        page.add(acl_group)

        self.bind_mode_row = Adw.ComboRow(title="控制模式")
        self.bind_modes = ["白名单模式 (仅允许白名单 IP)", "黑名单模式 (仅拒绝黑名单 IP)", "允许所有网络 (0.0.0.0)", "仅本机 USB (127.0.0.1)"]
        self.mode_keys = ["whitelist", "blacklist", "all", "localhost_only"]
        model = Gtk.StringList.new(self.bind_modes)
        self.bind_mode_row.set_model(model)
        current_mode = self.config.get("access_control_mode", "whitelist")
        selected_idx = self.mode_keys.index(current_mode) if current_mode in self.mode_keys else 0
        self.bind_mode_row.set_selected(selected_idx)
        self.bind_mode_row.connect("notify::selected", self.on_bind_mode_changed)
        acl_group.add(self.bind_mode_row)

        self.whitelist_row = Adw.EntryRow(title="IP 白名单 (逗号分隔，支持 * 通配符)")
        self.whitelist_row.set_text(", ".join(self.config.get("whitelist", [])))
        self.whitelist_row.connect("changed", self.on_whitelist_changed)
        acl_group.add(self.whitelist_row)

        self.blacklist_row = Adw.EntryRow(title="IP 黑名单 (逗号分隔)")
        self.blacklist_row.set_text(", ".join(self.config.get("blacklist", [])))
        self.blacklist_row.connect("changed", self.on_blacklist_changed)
        acl_group.add(self.blacklist_row)

        self.update_acl_fields_visibility(current_mode)

        # --- Group 5: 快捷操作与维护 ---
        actions_group = Adw.PreferencesGroup(title="操作与维护")
        page.add(actions_group)

        restart_row = Adw.ActionRow(title="重启服务")
        btn_restart = Gtk.Button(label="重启")
        btn_restart.set_valign(Gtk.Align.CENTER)
        btn_restart.connect("clicked", self.on_restart_service)
        restart_row.add_suffix(btn_restart)
        actions_group.add(restart_row)

        logs_row = Adw.ActionRow(title="运行日志")
        btn_logs = Gtk.Button(label="查看日志")
        btn_logs.set_valign(Gtk.Align.CENTER)
        btn_logs.connect("clicked", self.on_show_logs)
        logs_row.add_suffix(btn_logs)
        actions_group.add(logs_row)

        uninstall_row = Adw.ActionRow(title="卸载 SilentInput")
        uninstall_row.set_subtitle("停止服务并从系统中移除快捷方式")
        btn_uninstall = Gtk.Button(label="卸载")
        btn_uninstall.add_css_class("destructive-action")
        btn_uninstall.set_valign(Gtk.Align.CENTER)
        btn_uninstall.connect("clicked", self.on_uninstall_clicked)
        uninstall_row.add_suffix(btn_uninstall)
        actions_group.add(uninstall_row)

        # Initial refresh and periodic status update timer
        self.update_status()
        GLib.timeout_add_seconds(3, self.update_status)

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
                self.service_row.set_subtitle(f"正在运行{pid_info}")
            else:
                self.service_row.set_subtitle("已停止")

        if self._autostart_action_count == 0:
            self.autostart_row.handler_block_by_func(self.on_autostart_toggled)
            self.autostart_row.set_active(autostart)
            self.autostart_row.handler_unblock_by_func(self.on_autostart_toggled)
            self.autostart_row.set_subtitle(
                "已启用 · 登录桌面时自动启动后台输入服务" if autostart
                else "已关闭 · 仅手动打开服务时运行"
            )

        self.update_token_display()

        # Update USB device info
        if usb_devices:
            self.usb_row.set_subtitle(f"已连接: {', '.join(usb_devices)}")
        else:
            self.usb_row.set_subtitle("未连接")

        # Update Host IPs: create separate row with copy button for each IP
        current_ip_set = set(ips)
        existing_row_ips = set(getattr(self, "_rendered_ips", []))

        if current_ip_set != existing_row_ips:
            # Remove old IP rows
            for r in self.ip_rows:
                self.ip_group.remove(r)
            self.ip_rows.clear()

            if not ips:
                no_ip_row = Adw.ActionRow(title="未检测到有效局域网 IP")
                self.ip_group.add(no_ip_row)
                self.ip_rows.append(no_ip_row)
            else:
                for single_ip in ips:
                    row = Adw.ActionRow(title=single_ip)
                    btn_copy = Gtk.Button.new_from_icon_name("edit-copy-symbolic")
                    btn_copy.set_tooltip_text(f"复制 {single_ip}")
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
            self.show_toast(f"已复制 IP: {ip_str}")
        return _handler

    def on_service_switch_toggled(self, switch_row, param):
        active = switch_row.get_active()
        self._service_action_count += 1
        self.service_row.set_subtitle("正在启动..." if active else "正在停止...")

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
        self._autostart_action_count += 1
        self.autostart_row.set_subtitle("正在开启开机启动..." if active else "正在关闭开机启动...")

        def _worker():
            ok, msg = False, ""
            try:
                ok, msg = silentinput_config.set_autostart_enabled(active)
            finally:
                def _done():
                    self._autostart_action_count = max(0, self._autostart_action_count - 1)
                    if msg:
                        self.show_toast(msg)
                    if not ok:
                        self.update_status()
                    else:
                        self.autostart_row.set_subtitle(
                            "已启用 · 登录桌面时自动启动后台输入服务" if active
                            else "已关闭 · 仅手动打开服务时运行"
                        )
                    return False
                GLib.idle_add(_done)

        threading.Thread(target=_worker, daemon=True).start()

    def on_copy_token(self, button):
        token = self.config.get("auth_token", "")
        if token:
            clipboard = Gdk.Display.get_default().get_clipboard()
            clipboard.set(token)
            self.show_toast("配对密钥已复制")

    def on_regenerate_token(self, button):
        self._config_generation += 1
        new_token = silentinput_config.regenerate_token()
        self.config = silentinput_config.load_config()
        self.update_token_display()
        self.show_toast(f"已生成新密钥: {new_token}")

    def on_expire_mode_changed(self, combo_row, param):
        idx = combo_row.get_selected()
        if 0 <= idx < len(self.expire_keys):
            self._config_generation += 1
            self.config["token_expire_mode"] = self.expire_keys[idx]
            import time
            self.config["token_created_at"] = time.time()
            silentinput_config.save_config(self.config)
            self.update_token_display()
            self.show_toast(f"有效期已设为: {self.expire_modes[idx]}")

    def update_token_display(self):
        token = self.config.get("auth_token", "")
        exp_txt = silentinput_config.get_token_status_text(self.config)
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
        self.show_toast("已更新加密设置，正在重启服务...")
        self.on_restart_service(None)

    def on_bind_mode_changed(self, combo_row, param):
        idx = combo_row.get_selected()
        if 0 <= idx < len(self.mode_keys):
            self._config_generation += 1
            mode = self.mode_keys[idx]
            self.config["access_control_mode"] = mode
            silentinput_config.save_config(self.config)
            self.update_acl_fields_visibility(mode)
            self.show_toast("控制模式已更新")

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
        self.show_toast("服务正在重启...")
        def _worker():
            try:
                silentinput_config.service_restart()
            finally:
                def _done():
                    self._service_action_count = max(0, self._service_action_count - 1)
                    self.update_status()
                    self.show_toast("服务已重启")
                    return False
                GLib.idle_add(_done)
        threading.Thread(target=_worker, daemon=True).start()

    def on_show_logs(self, button):
        log_file = silentinput_config.LOG_FILE

        def read_log_tail():
            if not log_file.exists():
                return "暂无日志"
            try:
                size = log_file.stat().st_size
                with open(log_file, "rb") as f:
                    if size > 32768:
                        f.seek(size - 32768)
                    raw = f.read()
                lines = raw.decode("utf-8", errors="replace").splitlines()
                return "\n".join(lines[-80:]) if lines else "日志为空"
            except Exception as e:
                return f"读取日志失败: {e}"

        log_win = Adw.Window(
            transient_for=self,
            modal=True,
            title="运行日志 (最近 80 行)"
        )
        log_win.set_default_size(640, 480)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        log_win.set_content(box)

        header = Adw.HeaderBar()
        btn_refresh = Gtk.Button.new_from_icon_name("view-refresh-symbolic")
        btn_refresh.set_tooltip_text("刷新日志")
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
            mark = buf.create_mark("end", end_iter, False)
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
        btn_close = Gtk.Button(label="关闭")
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
                heading="通过包管理器卸载",
                body=f"SilentInput 当前由系统包管理器安装。\n可在终端运行以下命令完整卸载：\n\n{cmd_hint}"
            )
            dialog.add_response("cancel", "关闭")
            dialog.add_response("stop", "仅停止服务并退出")
            dialog.connect(
                "response",
                lambda _d, resp: (
                    silentinput_config.service_stop() if resp == "stop" else None,
                    self.close() if resp == "stop" else None,
                ),
            )
            dialog.present()
            return

        dialog = Adw.MessageDialog(
            transient_for=self,
            heading="确认卸载 SilentInput？",
            body="这将停止正在运行的服务，并移除启动脚本与桌面图标。"
        )
        dialog.add_response("cancel", "取消")
        dialog.add_response("uninstall", "确认卸载")
        dialog.set_response_appearance("uninstall", Adw.ResponseAppearance.DESTRUCTIVE)

        def on_response(dlg, response):
            if response == "uninstall":
                self.show_toast("正在卸载...")
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
