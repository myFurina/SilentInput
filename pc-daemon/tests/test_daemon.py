import asyncio
import base64
import importlib.util
import json
import os
import socket
import stat
import struct
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

DAEMON_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(DAEMON_DIR))

import silentinput_config  # noqa: E402

_daemon_spec = importlib.util.spec_from_file_location(
    "silentinput_daemon", DAEMON_DIR / "silentinput-daemon.py"
)
silentinput_daemon = importlib.util.module_from_spec(_daemon_spec)
_daemon_spec.loader.exec_module(silentinput_daemon)


def _build_ws_frame(payload: bytes, opcode: int = 0x1, fin: bool = True, masked: bool = True) -> bytes:
    b1 = (0x80 if fin else 0x00) | (opcode & 0x0F)
    length = len(payload)
    mask_bit = 0x80 if masked else 0x00
    header = bytearray([b1])
    if length <= 125:
        header.append(mask_bit | length)
    elif length <= 65535:
        header.append(mask_bit | 126)
        header.extend(struct.pack("!H", length))
    else:
        header.append(mask_bit | 127)
        header.extend(struct.pack("!Q", length))

    if masked:
        mask_key = b"\x12\x34\x56\x78"
        header.extend(mask_key)
        masked_payload = bytes(b ^ mask_key[i % 4] for i, b in enumerate(payload))
        return bytes(header) + masked_payload
    return bytes(header) + payload


class _FakeWriter:
    def __init__(self, peername=("192.168.1.50", 12345), sock=None):
        self.buf = bytearray()
        self.closed = False
        self.wait_closed_called = False
        self._peername = peername
        self._sock = sock

    def write(self, data: bytes):
        self.buf.extend(data)

    async def drain(self):
        return

    def close(self):
        self.closed = True

    async def wait_closed(self):
        self.wait_closed_called = True
        return

    def get_extra_info(self, name, default=None):
        if name == "peername":
            return self._peername
        if name == "socket":
            return self._sock
        return default


class TestConfigSecurity(unittest.TestCase):
    def test_empty_whitelist_denies_remote_allows_localhost(self):
        cfg = {"access_control_mode": "whitelist", "whitelist": []}
        self.assertFalse(silentinput_config.is_ip_allowed("192.168.1.100", cfg))
        self.assertTrue(silentinput_config.is_ip_allowed("127.0.0.1", cfg))

    def test_config_dir_and_file_permissions(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg_dir = Path(tmp) / "silentinput"
            cfg_file = cfg_dir / "config.json"
            with mock.patch.object(silentinput_config, "CONFIG_DIR", cfg_dir), \
                 mock.patch.object(silentinput_config, "CONFIG_FILE", cfg_file):
                os.chmod(tmp, 0o755)
                cfg = silentinput_config.DEFAULT_CONFIG.copy()
                cfg["auth_token"] = "abcd1234"
                silentinput_config.save_config(cfg)

                dir_mode = stat.S_IMODE(cfg_dir.stat().st_mode)
                file_mode = stat.S_IMODE(cfg_file.stat().st_mode)
                self.assertEqual(dir_mode, 0o700)
                self.assertEqual(file_mode, 0o600)


class TestWaylandAndMouseHelpers(unittest.TestCase):
    def test_detect_wayland_env_ignores_lock_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            sock_path = runtime_dir / "wayland-1"
            srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            try:
                srv.bind(str(sock_path))
                lock_path = runtime_dir / "wayland-0.lock"
                lock_path.write_text("locked")
                os.utime(lock_path, (9999999999, 9999999999))

                with mock.patch.dict(os.environ, {"XDG_RUNTIME_DIR": tmp, "WAYLAND_DISPLAY": ""}, clear=False):
                    env = silentinput_daemon._detect_wayland_env()
                self.assertEqual(env["WAYLAND_DISPLAY"], "wayland-1")
            finally:
                srv.close()

    def test_safe_round_int(self):
        self.assertEqual(silentinput_daemon._safe_round_int(float("nan")), 0)
        self.assertEqual(silentinput_daemon._safe_round_int(float("inf")), 0)
        self.assertEqual(silentinput_daemon._safe_round_int(float("-inf")), 0)
        self.assertEqual(silentinput_daemon._safe_round_int("bad"), 0)
        self.assertEqual(silentinput_daemon._safe_round_int(None), 0)
        self.assertEqual(silentinput_daemon._safe_round_int(True), 0)
        self.assertEqual(silentinput_daemon._safe_round_int(1.6), 2)
        self.assertEqual(silentinput_daemon._safe_round_int(-2.4), -2)


class TestDaemonProtocolAsync(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        silentinput_daemon._auth_failures.clear()

    async def test_ws_recv_frame_handles_tcp_segmentation(self):
        long_text = ("你好SilentInput-" * 800)  # > 10 KB UTF-8 payload
        frame = _build_ws_frame(long_text.encode("utf-8"))

        reader = asyncio.StreamReader()
        writer = _FakeWriter()

        async def drip_feed():
            # Feed in tiny 137-byte chunks to force partial StreamReader.read() behavior
            for i in range(0, len(frame), 137):
                reader.feed_data(frame[i:i + 137])
                await asyncio.sleep(0)
            reader.feed_eof()

        feeder = asyncio.create_task(drip_feed())
        msg = await silentinput_daemon.ws_recv_frame(reader, writer)
        await feeder
        self.assertEqual(msg, long_text)

    async def test_ws_recv_frame_handles_fragmented_with_ping(self):
        part1 = _build_ws_frame("hello ".encode("utf-8"), opcode=0x1, fin=False)
        ping = _build_ws_frame(b"hb", opcode=0x9, fin=True)
        part2 = _build_ws_frame("world".encode("utf-8"), opcode=0x0, fin=True)

        reader = asyncio.StreamReader()
        writer = _FakeWriter()
        reader.feed_data(part1 + ping + part2)
        reader.feed_eof()

        msg = await silentinput_daemon.ws_recv_frame(reader, writer)
        self.assertEqual(msg, "hello world")
        # Pong frame (0x8A, len=2, b"hb") should have been written
        self.assertEqual(bytes(writer.buf), b"\x8a\x02hb")

    async def test_ws_recv_frame_rejects_oversized_frame_and_oversized_ping(self):
        oversized_hdr = bytearray([0x81, 0x80 | 127]) + struct.pack("!Q", silentinput_daemon.MAX_WS_FRAME_SIZE + 1)
        reader = asyncio.StreamReader()
        reader.feed_data(bytes(oversized_hdr))
        self.assertIsNone(await silentinput_daemon.ws_recv_frame(reader, _FakeWriter()))

        oversized_ping = _build_ws_frame(b"x" * 126, opcode=0x9, fin=True)
        reader2 = asyncio.StreamReader()
        reader2.feed_data(oversized_ping)
        self.assertIsNone(await silentinput_daemon.ws_recv_frame(reader2, _FakeWriter()))

    async def test_handle_client_rejects_http_post_and_raw_tcp_and_origin(self):
        cfg = {
            "auth_token": "secret12",
            "allow_usb_unauthenticated": True,
            "access_control_mode": "all",
        }
        with mock.patch.object(silentinput_config, "load_config", return_value=cfg):
            # 1. HTTP POST drive-by attempt on localhost
            r1 = asyncio.StreamReader()
            r1.feed_data(b'POST / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n{"type":"paste","content":"evil","enter":true}\n')
            r1.feed_eof()
            w1 = _FakeWriter(peername=("127.0.0.1", 55555))
            await silentinput_daemon.handle_client(r1, w1)
            self.assertTrue(bytes(w1.buf).startswith(b"HTTP/1.1 400 Bad Request"))

            # 2. Raw TCP JSON attempt
            r2 = asyncio.StreamReader()
            r2.feed_data(b'{"type":"paste","content":"evil"}\n')
            r2.feed_eof()
            w2 = _FakeWriter(peername=("127.0.0.1", 55556))
            await silentinput_daemon.handle_client(r2, w2)
            self.assertTrue(bytes(w2.buf).startswith(b"HTTP/1.1 400 Bad Request"))

            # 3. WebSocket handshake with browser Origin header
            ws_key = base64.b64encode(b"1234567890123456").decode("ascii")
            r3 = asyncio.StreamReader()
            r3.feed_data(
                (
                    "GET / HTTP/1.1\r\n"
                    "Host: 127.0.0.1:53318\r\n"
                    "Upgrade: websocket\r\n"
                    "Connection: Upgrade\r\n"
                    f"Sec-WebSocket-Key: {ws_key}\r\n"
                    "Origin: https://evil.example\r\n\r\n"
                ).encode("utf-8")
            )
            r3.feed_eof()
            w3 = _FakeWriter(peername=("127.0.0.1", 55557))
            await silentinput_daemon.handle_client(r3, w3)
            self.assertTrue(bytes(w3.buf).startswith(b"HTTP/1.1 403 Forbidden"))

    async def test_wireless_ignores_url_token_and_rate_limits_failures(self):
        cfg = {
            "auth_token": "goodtoken",
            "allow_usb_unauthenticated": True,
            "access_control_mode": "all",
        }
        ws_key = base64.b64encode(b"1234567890123456").decode("ascii")
        # Even if URL contains ?token=goodtoken, wireless client must still send auth message
        r = asyncio.StreamReader()
        req = (
            "GET /?token=goodtoken HTTP/1.1\r\n"
            "Host: 192.168.1.10:53318\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {ws_key}\r\n\r\n"
        ).encode("utf-8")
        r.feed_data(req)
        r.feed_data(_build_ws_frame(json.dumps({"type": "key", "key": "enter"}).encode("utf-8")))
        r.feed_data(_build_ws_frame(b"", opcode=0x8))
        r.feed_eof()

        w = _FakeWriter(peername=("192.168.1.50", 40000))
        with mock.patch.object(silentinput_config, "load_config", return_value=cfg):
            await silentinput_daemon.handle_client(r, w)
        self.assertIn(b"auth_required", bytes(w.buf))

        # Rate limiting after 5 wrong tokens
        for i in range(5):
            auth_ok, should_close = await silentinput_daemon.process_message(
                json.dumps({"type": "auth", "token": "wrong"}),
                _FakeWriter(),
                is_websocket=True,
                is_authenticated=False,
                config=cfg,
                peer_ip="192.168.1.99",
            )
            self.assertFalse(auth_ok)
            self.assertEqual(should_close, i == 4)

        self.assertTrue(silentinput_daemon._is_auth_locked("192.168.1.99"))

    async def test_event_loop_not_blocked_during_typing(self):
        fake_uinput = mock.MagicMock()
        cfg = {"auth_token": "tok"}
        ping_writer = _FakeWriter()

        async def slow_type():
            return await silentinput_daemon.process_message(
                json.dumps({"type": "paste", "content": "a" * 50, "mode": "keys"}),
                _FakeWriter(),
                is_websocket=True,
                is_authenticated=True,
                config=cfg,
            )

        with mock.patch.object(silentinput_daemon, "uinput_device", fake_uinput):
            t_task = asyncio.create_task(slow_type())
            await asyncio.sleep(0.01)
            await silentinput_daemon.process_message(
                json.dumps({"type": "ping", "t": 123}),
                ping_writer,
                is_websocket=False,
                is_authenticated=False,
                config=cfg,
            )
            self.assertIn(b'"type": "pong"', bytes(ping_writer.buf))
            await t_task

    async def test_mouse_nan_does_not_crash(self):
        fake_uinput = mock.MagicMock()
        fake_ecodes = types.SimpleNamespace(
            EV_REL=2, REL_X=0, REL_Y=1, REL_WHEEL=8,
            EV_KEY=1, BTN_LEFT=0x110, BTN_RIGHT=0x111,
        )
        fake_evdev = types.SimpleNamespace(ecodes=fake_ecodes)
        cfg = {"auth_token": "tok"}
        with mock.patch.object(silentinput_daemon, "uinput_device", fake_uinput), \
             mock.patch.object(silentinput_daemon, "evdev", fake_evdev):
            auth_ok, should_close = await silentinput_daemon.process_message(
                json.dumps({"type": "mouse", "dx": "NaN", "dy": 3.6, "wheel": "inf"}),
                _FakeWriter(),
                is_websocket=False,
                is_authenticated=True,
                config=cfg,
            )
        self.assertTrue(auth_ok)
        self.assertFalse(should_close)
        fake_uinput.write.assert_called_once_with(fake_ecodes.EV_REL, fake_ecodes.REL_Y, 4)
        fake_uinput.syn.assert_called_once()

    async def test_preauthenticated_channel_ignores_stale_auth_token(self):
        cfg = {"auth_token": "correct-token"}
        writer = _FakeWriter()
        auth_ok, should_close = await silentinput_daemon.process_message(
            json.dumps({"type": "auth", "token": "wrong-stale-token"}),
            writer,
            is_websocket=False,
            is_authenticated=True,
            config=cfg,
            peer_ip="bluetooth",
        )
        self.assertTrue(auth_ok)
        self.assertFalse(should_close)
        self.assertIn(b'"status": "ok"', bytes(writer.buf))
        self.assertFalse(silentinput_daemon._is_auth_locked("bluetooth"))

    async def test_ws_fragmented_binary_skipped_then_text_read(self):
        r = asyncio.StreamReader()
        w = _FakeWriter()
        r.feed_data(_build_ws_frame(b"\x01\x02", opcode=0x2, fin=False))
        r.feed_data(_build_ws_frame(b"\x03\x04", opcode=0x0, fin=True))
        r.feed_data(_build_ws_frame(b'{"type":"ping","t":42}', opcode=0x1, fin=True))
        r.feed_eof()

        first = await silentinput_daemon.ws_recv_frame(r, w)
        self.assertEqual(first, "")
        msg = await silentinput_daemon.ws_recv_frame(r, w)
        self.assertEqual(msg, '{"type":"ping","t":42}')

        # Interleaved non-continuation frame inside a fragmented text frame must fail cleanly
        r_bad = asyncio.StreamReader()
        r_bad.feed_data(_build_ws_frame(b'{"type":', opcode=0x1, fin=False))
        r_bad.feed_data(_build_ws_frame(b"oops", opcode=0x2, fin=True))
        r_bad.feed_eof()
        self.assertIsNone(await silentinput_daemon.ws_recv_frame(r_bad, _FakeWriter()))

    def test_find_daemon_pids_matches_exact_script_only(self):
        script_path = os.path.realpath(silentinput_config.DAEMON_SCRIPT)
        with tempfile.TemporaryDirectory() as tmpdir:
            proc_root = Path(tmpdir)
            # Matching python-invoked daemon process
            pid1 = proc_root / "101"
            pid1.mkdir()
            (pid1 / "cmdline").write_bytes(b"/usr/bin/python3\x00-u\x00" + script_path.encode() + b"\x00")

            # Unrelated process merely mentioning the filename as tail target
            pid2 = proc_root / "102"
            pid2.mkdir()
            (pid2 / "cmdline").write_bytes(b"/usr/bin/tail\x00-f\x00" + script_path.encode() + b"\x00")

            # Non-numeric dir & self PID should be ignored
            (proc_root / "self").mkdir()
            pid_self = proc_root / str(os.getpid())
            pid_self.mkdir()
            (pid_self / "cmdline").write_bytes(b"/usr/bin/python3\x00" + script_path.encode() + b"\x00")

            pids = silentinput_config._find_daemon_pids(proc_root)
            self.assertEqual(pids, [101])

    def test_daemon_import_does_not_open_uinput(self):
        self.assertIsNone(silentinput_daemon.uinput_device)

    async def test_clipboard_failure_skips_paste_shortcut(self):
        cfg = {"auth_token": "tok"}
        with mock.patch.object(silentinput_daemon, "copy_to_wayland_clipboard", return_value=False) as mock_copy, \
             mock.patch.object(silentinput_daemon, "emit_key_combination") as mock_emit:
            auth_ok, should_close = await silentinput_daemon.process_message(
                json.dumps({"type": "paste", "content": "rm -rf /", "mode": "terminal", "enter": True}),
                _FakeWriter(),
                is_websocket=True,
                is_authenticated=True,
                config=cfg,
            )
        self.assertTrue(auth_ok)
        self.assertFalse(should_close)
        mock_copy.assert_awaited_once()
        mock_emit.assert_not_called()

    def test_multithreaded_save_config_safe(self):
        import concurrent.futures
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            cfg_file = tmp_path / "config.json"
            with mock.patch.object(silentinput_config, "CONFIG_DIR", tmp_path), \
                 mock.patch.object(silentinput_config, "CONFIG_FILE", cfg_file):
                def write_one(i):
                    silentinput_config.save_config({"auth_token": f"tok{i}", "port": 53318})
                with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
                    list(pool.map(write_one, range(20)))
                loaded = json.loads(cfg_file.read_text(encoding="utf-8"))
                self.assertEqual(loaded["port"], 53318)
                self.assertEqual(list(tmp_path.glob(".config.*.tmp")), [])

    def test_uinput_lazy_recovery_after_boot_permission_error(self):
        fake_dev = mock.MagicMock()
        fake_evdev = types.SimpleNamespace(
            UInput=mock.MagicMock(side_effect=[PermissionError("/dev/uinput"), fake_dev]),
            ecodes=types.SimpleNamespace(EV_KEY=1),
        )
        with mock.patch.object(silentinput_daemon, "HAVE_UINPUT", True), \
             mock.patch.object(silentinput_daemon, "evdev", fake_evdev), \
             mock.patch.object(silentinput_daemon, "UINPUT_CAPABILITIES", {1: [30]}), \
             mock.patch.object(silentinput_daemon, "uinput_device", None), \
             mock.patch.object(silentinput_daemon, "_uinput_warned", False):
            self.assertFalse(silentinput_daemon.init_uinput_device())
            self.assertIsNone(silentinput_daemon.uinput_device)
            # Once logind ACL settles, ensure_uinput_device() recovers without service restart
            self.assertTrue(silentinput_daemon.ensure_uinput_device())
            self.assertIs(silentinput_daemon.uinput_device, fake_dev)
            silentinput_daemon.close_uinput_device()

    def test_autostart_xdg_fallback_and_systemd_override(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            xdg_file = tmp_path / "autostart" / "com.silentinput.daemon.desktop"
            with mock.patch.object(silentinput_config, "has_active_systemd", return_value=False), \
                 mock.patch.object(silentinput_config, "_xdg_autostart_file", return_value=xdg_file):
                self.assertFalse(silentinput_config.is_autostart_enabled())
                ok, _ = silentinput_config.set_autostart_enabled(True)
                self.assertTrue(ok)
                self.assertTrue(xdg_file.exists())
                self.assertTrue(silentinput_config.is_autostart_enabled())
                self.assertIn(f'Exec="{sys.executable}" -u "{silentinput_config.DAEMON_SCRIPT}"', xdg_file.read_text(encoding="utf-8"))
                ok, _ = silentinput_config.set_autostart_enabled(False)
                self.assertTrue(ok)
                self.assertFalse(xdg_file.exists())

            user_override = tmp_path / "user" / "default.target.wants" / "silentinput.service"
            global_link = tmp_path / "global" / "silentinput.service"
            global_link.parent.mkdir(parents=True)
            global_link.write_text("stub")
            systemctl_state = {"enabled": True}

            def fake_systemctl(cmd, *args, **kwargs):
                action = cmd[2] if len(cmd) > 2 else ""
                if action == "enable":
                    systemctl_state["enabled"] = True
                    return types.SimpleNamespace(returncode=0, stdout="", stderr="")
                if action == "disable":
                    systemctl_state["enabled"] = False
                    return types.SimpleNamespace(returncode=0, stdout="", stderr="")
                if action == "is-enabled":
                    if systemctl_state["enabled"]:
                        return types.SimpleNamespace(returncode=0, stdout="enabled\n", stderr="")
                    return types.SimpleNamespace(returncode=1, stdout="disabled\n", stderr="")
                return types.SimpleNamespace(returncode=0, stdout="", stderr="")

            with mock.patch.object(silentinput_config, "has_active_systemd", return_value=True), \
                 mock.patch.object(silentinput_config, "_user_wants_override_link", return_value=user_override), \
                 mock.patch.object(silentinput_config, "_global_wants_link", return_value=global_link), \
                 mock.patch.object(silentinput_config.subprocess, "run", side_effect=fake_systemctl):
                self.assertTrue(silentinput_config.is_autostart_enabled())
                ok, _ = silentinput_config.set_autostart_enabled(False)
                self.assertTrue(ok)
                self.assertTrue(user_override.is_symlink())
                self.assertEqual(os.readlink(user_override), "/dev/null")
                self.assertFalse(silentinput_config.is_autostart_enabled())

                # Even if global wants link would still make systemctl say "enabled",
                # /dev/null override makes is_autostart_enabled() return False until re-enabled.
                systemctl_state["enabled"] = True
                self.assertFalse(silentinput_config.is_autostart_enabled())

                ok, _ = silentinput_config.set_autostart_enabled(True)
                self.assertTrue(ok)
                self.assertFalse(user_override.exists())
                self.assertTrue(silentinput_config.is_autostart_enabled())

    def test_prtsc_key_mapped_to_sysrq(self):
        if silentinput_daemon.HAVE_UINPUT and silentinput_daemon.evdev is not None:
            sysrq = silentinput_daemon.evdev.ecodes.KEY_SYSRQ
            self.assertEqual(silentinput_daemon.KEY_MAP.get("prtsc"), sysrq)
            self.assertEqual(silentinput_daemon.KEY_MAP.get("printscreen"), sysrq)

    def test_emit_uinput_events_recovers_after_oserror(self):
        dev1 = mock.MagicMock()
        dev1.write.side_effect = OSError("ENODEV")
        dev2 = mock.MagicMock()
        fake_evdev = types.SimpleNamespace(
            UInput=mock.MagicMock(side_effect=[dev1, dev2]),
            ecodes=types.SimpleNamespace(EV_KEY=1),
        )
        with mock.patch.object(silentinput_daemon, "HAVE_UINPUT", True), \
             mock.patch.object(silentinput_daemon, "evdev", fake_evdev), \
             mock.patch.object(silentinput_daemon, "UINPUT_CAPABILITIES", {1: [30]}), \
             mock.patch.object(silentinput_daemon, "uinput_device", None), \
             mock.patch.object(silentinput_daemon, "_uinput_warned", False):
            ok = silentinput_daemon._emit_uinput_events([(1, 30, 1), (1, 30, 0)])
            self.assertTrue(ok)
            dev1.close.assert_called_once()
            dev2.write.assert_has_calls([mock.call(1, 30, 1), mock.call(1, 30, 0)])
            dev2.syn.assert_called_once()
            self.assertIs(silentinput_daemon.uinput_device, dev2)
            silentinput_daemon.close_uinput_device()

    def test_bluez_adapter_powered_signal_handles_dbus_boolean_subclass(self):
        class _FakeDbusBoolean(int):
            pass

        val_true = _FakeDbusBoolean(1)
        val_false = _FakeDbusBoolean(0)
        self.assertIsNot(val_true, True)
        self.assertTrue(bool(val_true))

        signal_callbacks = {}
        timeouts = []
        mock_mgr = mock.MagicMock()
        mock_bus = mock.MagicMock()
        mock_bus.get_object.return_value = object()

        def fake_add_signal_receiver(cb, signal_name=None, **kwargs):
            signal_callbacks[signal_name] = cb

        mock_bus.add_signal_receiver.side_effect = fake_add_signal_receiver

        fake_dbus = types.ModuleType("dbus")
        fake_dbus.SystemBus = mock.MagicMock(return_value=mock_bus)
        fake_dbus.Interface = mock.MagicMock(return_value=mock_mgr)
        fake_dbus.UInt16 = int
        class _FakeDbusObject:
            def __init__(self, *args, **kwargs):
                pass

        fake_dbus_service = types.ModuleType("dbus.service")
        fake_dbus_service.Object = _FakeDbusObject
        fake_dbus_service.method = lambda *a, **kw: (lambda f: f)
        fake_dbus_exceptions = types.ModuleType("dbus.exceptions")
        fake_dbus_exceptions.DBusException = Exception
        fake_dbus_mainloop = types.ModuleType("dbus.mainloop")
        fake_dbus_mainloop_glib = types.ModuleType("dbus.mainloop.glib")
        fake_dbus_mainloop_glib.DBusGMainLoop = mock.MagicMock()
        fake_dbus_mainloop.glib = fake_dbus_mainloop_glib
        fake_dbus.service = fake_dbus_service
        fake_dbus.exceptions = fake_dbus_exceptions
        fake_dbus.mainloop = fake_dbus_mainloop

        fake_glib = types.SimpleNamespace(
            MainLoop=lambda: types.SimpleNamespace(run=lambda: None),
            timeout_add=lambda delay, fn: timeouts.append((delay, fn)),
            timeout_add_seconds=lambda delay, fn: timeouts.append((delay * 1000, fn)),
        )
        fake_gi = types.ModuleType("gi")
        fake_gi_repo = types.ModuleType("gi.repository")
        fake_gi_repo.GLib = fake_glib

        with mock.patch.dict(sys.modules, {
            "dbus": fake_dbus,
            "dbus.service": fake_dbus_service,
            "dbus.exceptions": fake_dbus_exceptions,
            "dbus.mainloop": fake_dbus_mainloop,
            "dbus.mainloop.glib": fake_dbus_mainloop_glib,
            "gi": fake_gi,
            "gi.repository": fake_gi_repo,
        }), mock.patch.object(silentinput_daemon, "_bt_dbus_refs", []):
            silentinput_daemon.setup_bluetooth_server(loop=None)
            self.assertIn("PropertiesChanged", signal_callbacks)
            mock_mgr.RegisterProfile.reset_mock()

            props_cb = signal_callbacks["PropertiesChanged"]
            props_cb("org.bluez.Adapter1", {"Powered": val_false}, [])
            self.assertEqual(timeouts, [])

            props_cb("org.bluez.Adapter1", {"Powered": val_true}, [])
            self.assertEqual(len(timeouts), 1)
            self.assertEqual(timeouts[0][0], 300)
            timeouts[0][1]()
            mock_mgr.RegisterProfile.assert_called_once()

    async def test_mouse_down_up_left_and_release_on_disconnect(self):
        emitted = []
        fake_evdev = types.SimpleNamespace(
            ecodes=types.SimpleNamespace(EV_KEY=1, EV_REL=2, BTN_LEFT=272, BTN_RIGHT=273, REL_X=0, REL_Y=1, REL_WHEEL=8)
        )
        cfg = {"auth_token": "tok"}
        with mock.patch.object(silentinput_daemon, "evdev", fake_evdev), \
             mock.patch.object(silentinput_daemon, "ensure_uinput_device", return_value=True), \
             mock.patch.object(silentinput_daemon, "uinput_device", object()), \
             mock.patch.object(silentinput_daemon, "_emit_uinput_events", side_effect=lambda evs: emitted.append(evs) or True), \
             mock.patch.object(silentinput_daemon, "_held_mouse_buttons", {}):
            tok_active = object()
            tok_other = object()
            tok_unauth = object()
            await silentinput_daemon.process_message(
                json.dumps({"type": "mouse", "action": "down_left"}),
                _FakeWriter(),
                is_websocket=True,
                is_authenticated=True,
                config=cfg,
                conn_token=tok_active,
            )
            self.assertEqual(emitted[-1], [(1, 272, 1)])
            self.assertIn(272, silentinput_daemon._held_mouse_buttons)

            # Unauthenticated or unrelated connection closing must NOT release tok_active's held button
            silentinput_daemon._release_held_mouse_buttons(tok_unauth)
            silentinput_daemon._release_held_mouse_buttons(tok_other)
            self.assertEqual(len(emitted), 1)
            self.assertIn(tok_active, silentinput_daemon._held_mouse_buttons[272])

            # Only when tok_active disconnects is BTN_LEFT released cleanly
            silentinput_daemon._release_held_mouse_buttons(tok_active)
            self.assertEqual(emitted[-1], [(1, 272, 0)])
            self.assertEqual(len(silentinput_daemon._held_mouse_buttons), 0)

    def test_auth_failures_ttl_and_capacity_eviction(self):
        silentinput_daemon._auth_failures.clear()
        fake_now = [1000.0]
        with mock.patch.object(silentinput_daemon.time, "monotonic", side_effect=lambda: fake_now[0]), \
             mock.patch.object(silentinput_daemon, "AUTH_FAILURE_MAX_ENTRIES", 3):
            silentinput_daemon._record_auth_failure("10.0.0.1")
            self.assertIn("10.0.0.1", silentinput_daemon._auth_failures)

            # Advance past TTL -> old partial-failure IP is pruned on next write
            fake_now[0] += silentinput_daemon.AUTH_FAILURE_TTL_SECONDS + 1.0
            silentinput_daemon._record_auth_failure("10.0.0.2")
            self.assertNotIn("10.0.0.1", silentinput_daemon._auth_failures)
            self.assertIn("10.0.0.2", silentinput_daemon._auth_failures)

            # Fill up to capacity and verify oldest entry is evicted
            fake_now[0] += 1.0
            silentinput_daemon._record_auth_failure("10.0.0.3")
            fake_now[0] += 1.0
            silentinput_daemon._record_auth_failure("10.0.0.4")
            fake_now[0] += 1.0
            silentinput_daemon._record_auth_failure("10.0.0.5")
            self.assertEqual(len(silentinput_daemon._auth_failures), 3)
            self.assertNotIn("10.0.0.2", silentinput_daemon._auth_failures)
            self.assertIn("10.0.0.5", silentinput_daemon._auth_failures)

    async def test_eof_awaits_wait_closed_and_idle_timeout_cleans_up(self):
        cfg = {"auth_token": "tok", "allow_usb_unauthenticated": True, "access_control_mode": "all"}
        fake_evdev = types.SimpleNamespace(
            ecodes=types.SimpleNamespace(EV_KEY=1, EV_REL=2, BTN_LEFT=272, BTN_RIGHT=273, REL_X=0, REL_Y=1, REL_WHEEL=8)
        )
        emitted = []

        # 1. Immediate EOF on handle_client must close & await wait_closed()
        r_eof = asyncio.StreamReader()
        r_eof.feed_eof()
        w_eof = _FakeWriter(peername=("127.0.0.1", 50001))
        with mock.patch.object(silentinput_config, "load_config", return_value=cfg):
            await silentinput_daemon.handle_client(r_eof, w_eof)
        self.assertTrue(w_eof.closed)
        self.assertTrue(w_eof.wait_closed_called)

        # 2. Authenticated WS idle timeout releases held buttons & closes writer
        ws_key = base64.b64encode(b"1234567890123456").decode("ascii")
        r_ws = asyncio.StreamReader()
        r_ws.feed_data(
            (
                "GET / HTTP/1.1\r\n"
                "Host: 127.0.0.1:53318\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Key: {ws_key}\r\n\r\n"
            ).encode("utf-8")
        )
        r_ws.feed_data(_build_ws_frame(json.dumps({"type": "mouse", "action": "down_left"}).encode("utf-8")))
        w_ws = _FakeWriter(peername=("127.0.0.1", 50002))

        with mock.patch.object(silentinput_config, "load_config", return_value=cfg), \
             mock.patch.object(silentinput_daemon, "WS_IDLE_TIMEOUT_SECONDS", 0.05), \
             mock.patch.object(silentinput_daemon, "evdev", fake_evdev), \
             mock.patch.object(silentinput_daemon, "ensure_uinput_device", return_value=True), \
             mock.patch.object(silentinput_daemon, "uinput_device", object()), \
             mock.patch.object(silentinput_daemon, "_emit_uinput_events", side_effect=lambda evs: emitted.append(evs) or True), \
             mock.patch.object(silentinput_daemon, "_held_mouse_buttons", {}):
            await silentinput_daemon.handle_client(r_ws, w_ws)
            self.assertEqual(len(silentinput_daemon._held_mouse_buttons), 0)
        self.assertTrue(w_ws.closed)
        self.assertTrue(w_ws.wait_closed_called)
        self.assertEqual(emitted, [[(1, 272, 1)], [(1, 272, 0)]])
        self.assertEqual(silentinput_daemon._active_ws_connections, 0)

        # 3. Authenticated Bluetooth idle timeout releases held buttons & closes writer
        emitted.clear()
        r_bt = asyncio.StreamReader()
        r_bt.feed_data((json.dumps({"type": "mouse", "action": "down_left"}) + "\n").encode("utf-8"))
        w_bt = _FakeWriter(peername=("Bluetooth(dev)", 0))
        with mock.patch.object(silentinput_config, "load_config", return_value=cfg), \
             mock.patch.object(silentinput_daemon, "BT_IDLE_TIMEOUT_SECONDS", 0.05), \
             mock.patch.object(silentinput_daemon, "evdev", fake_evdev), \
             mock.patch.object(silentinput_daemon, "ensure_uinput_device", return_value=True), \
             mock.patch.object(silentinput_daemon, "uinput_device", object()), \
             mock.patch.object(silentinput_daemon, "_emit_uinput_events", side_effect=lambda evs: emitted.append(evs) or True), \
             mock.patch.object(silentinput_daemon, "_held_mouse_buttons", {}):
            await silentinput_daemon.handle_bt_client(r_bt, w_bt, "Bluetooth(dev)")
            self.assertEqual(len(silentinput_daemon._held_mouse_buttons), 0)
        self.assertTrue(w_bt.closed)
        self.assertTrue(w_bt.wait_closed_called)
        self.assertEqual(emitted, [[(1, 272, 1)], [(1, 272, 0)]])

    async def test_cancellation_releases_mouse_and_closes_writer(self):
        cfg = {"auth_token": "tok", "allow_usb_unauthenticated": True, "access_control_mode": "all"}
        fake_evdev = types.SimpleNamespace(
            ecodes=types.SimpleNamespace(EV_KEY=1, EV_REL=2, BTN_LEFT=272, BTN_RIGHT=273, REL_X=0, REL_Y=1, REL_WHEEL=8)
        )
        emitted = []
        r_bt = asyncio.StreamReader()
        r_bt.feed_data((json.dumps({"type": "mouse", "action": "down_left"}) + "\n").encode("utf-8"))
        w_bt = _FakeWriter(peername=("Bluetooth(dev)", 0))

        with mock.patch.object(silentinput_config, "load_config", return_value=cfg), \
             mock.patch.object(silentinput_daemon, "BT_IDLE_TIMEOUT_SECONDS", 30.0), \
             mock.patch.object(silentinput_daemon, "evdev", fake_evdev), \
             mock.patch.object(silentinput_daemon, "ensure_uinput_device", return_value=True), \
             mock.patch.object(silentinput_daemon, "uinput_device", object()), \
             mock.patch.object(silentinput_daemon, "_emit_uinput_events", side_effect=lambda evs: emitted.append(evs) or True), \
             mock.patch.object(silentinput_daemon, "_held_mouse_buttons", {}):
            task = asyncio.create_task(silentinput_daemon.handle_bt_client(r_bt, w_bt, "Bluetooth(dev)"))
            await asyncio.sleep(0.02)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            self.assertEqual(len(silentinput_daemon._held_mouse_buttons), 0)
        self.assertTrue(w_bt.closed)
        self.assertTrue(w_bt.wait_closed_called)
        self.assertEqual(emitted, [[(1, 272, 1)], [(1, 272, 0)]])

    async def test_ws_connection_limit_and_tcp_keepalive(self):
        mock_sock = mock.MagicMock()
        w_sock = _FakeWriter(sock=mock_sock)
        silentinput_daemon._enable_tcp_keepalive(w_sock)
        mock_sock.setsockopt.assert_any_call(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)

        r_over = asyncio.StreamReader()
        w_over = _FakeWriter(peername=("127.0.0.1", 50009))
        with mock.patch.object(silentinput_daemon, "MAX_WS_CONNECTIONS", 2), \
             mock.patch.object(silentinput_daemon, "_active_ws_connections", 2):
            await silentinput_daemon.handle_client(r_over, w_over)
        self.assertTrue(w_over.closed)
        self.assertTrue(w_over.wait_closed_called)

    async def test_emit_key_combination_wtype_validation(self):
        calls = []

        async def fake_exec(*cmd, **kwargs):
            calls.append(cmd)
            return types.SimpleNamespace(wait=mock.AsyncMock(return_value=0))

        with mock.patch.object(silentinput_daemon, "ensure_uinput_device", return_value=False), \
             mock.patch.object(silentinput_daemon, "HAVE_UINPUT", False), \
             mock.patch.object(silentinput_daemon, "KEY_MAP", {}), \
             mock.patch.object(silentinput_daemon.asyncio, "create_subprocess_exec", side_effect=fake_exec):
            # Valid fallback keys execute wtype
            await silentinput_daemon.emit_key_combination("ctrl+prtsc")
            await silentinput_daemon.emit_key_combination("alt+a")
            self.assertEqual(len(calls), 2)
            self.assertEqual(calls[0], ("wtype", "-M", "ctrl", "-k", "Print"))
            self.assertEqual(calls[1], ("wtype", "-M", "alt", "-k", "a"))

            # Malicious / unknown fallback keys are rejected before spawning subprocess
            calls.clear()
            await silentinput_daemon.emit_key_combination("--help")
            await silentinput_daemon.emit_key_combination("ctrl+-M")
            await silentinput_daemon.emit_key_combination("bad;cmd")
            await silentinput_daemon.emit_key_combination("unknownlongkey")
            self.assertEqual(calls, [])

    def test_config_i18n_token_status_and_autostart_messages(self):
        self.assertEqual(
            silentinput_config.get_token_status_text({"token_expire_mode": "never"}, is_zh=False),
            "Never expires",
        )
        with mock.patch.object(silentinput_config.time, "time", return_value=5000.0):
            self.assertEqual(
                silentinput_config.get_token_status_text(
                    {"token_expire_mode": "1h", "token_created_at": 4900.0},
                    is_zh=False,
                ),
                "1h (58m left)",
            )
            self.assertEqual(
                silentinput_config.get_token_status_text(
                    {"token_expire_mode": "1h", "token_created_at": 100.0},
                    is_zh=False,
                ),
                "1h (expired, rotates on next connect)",
            )
        with tempfile.TemporaryDirectory() as tmpdir:
            xdg_file = Path(tmpdir) / "autostart" / "com.silentinput.daemon.desktop"
            with mock.patch.object(silentinput_config, "has_active_systemd", return_value=False), \
                 mock.patch.object(silentinput_config, "_xdg_autostart_file", return_value=xdg_file):
                ok, msg_on = silentinput_config.set_autostart_enabled(True, is_zh=False)
                self.assertTrue(ok)
                self.assertEqual(msg_on, "Autostart enabled (XDG)")
                ok, msg_off = silentinput_config.set_autostart_enabled(False, is_zh=False)
                self.assertTrue(ok)
                self.assertEqual(msg_off, "Autostart disabled")

    def test_has_graphical_session_detection(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            rt_dir = Path(tmpdir)
            lock_file = rt_dir / "wayland-0.lock"
            lock_file.write_text("", encoding="utf-8")
            with mock.patch.dict(os.environ, {"XDG_RUNTIME_DIR": str(rt_dir)}, clear=True):
                self.assertFalse(silentinput_daemon._has_graphical_session())

            # Non-existent local X11 display socket should stay headless
            with mock.patch.dict(os.environ, {"XDG_RUNTIME_DIR": str(rt_dir), "DISPLAY": ":99"}, clear=True):
                self.assertFalse(silentinput_daemon._has_graphical_session())

            # Real unix socket named wayland-0 triggers graphical session detection
            sock_path = rt_dir / "wayland-0"
            srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            try:
                srv.bind(str(sock_path))
                with mock.patch.dict(os.environ, {"XDG_RUNTIME_DIR": str(rt_dir)}, clear=True):
                    self.assertTrue(silentinput_daemon._has_graphical_session())
                with mock.patch.dict(
                    os.environ,
                    {"XDG_RUNTIME_DIR": str(rt_dir), "WAYLAND_DISPLAY": "wayland-0"},
                    clear=True,
                ):
                    self.assertTrue(silentinput_daemon._has_graphical_session())
            finally:
                srv.close()

    async def test_type_text_adaptive_unicode_desktop_vs_headless(self):
        emitted = []
        combos = []

        async def fake_emit_combo(combo):
            combos.append(combo)

        def fake_emit_events(events):
            emitted.append(list(events))
            return True

        fake_dev = mock.MagicMock()
        with mock.patch.object(silentinput_daemon, "uinput_device", fake_dev), \
             mock.patch.object(silentinput_daemon, "ensure_uinput_device", return_value=True), \
             mock.patch.object(silentinput_daemon, "_emit_uinput_events", side_effect=fake_emit_events), \
             mock.patch.object(silentinput_daemon, "emit_key_combination", side_effect=fake_emit_combo):
            # Desktop mode: "A中" -> ASCII 'A', then ctrl+shift+u + hex '4e2d' + enter
            with mock.patch.object(silentinput_daemon, "_has_graphical_session", return_value=True):
                ok = await silentinput_daemon.type_text("A中")
                self.assertTrue(ok)
            self.assertEqual(combos, ["ctrl+shift+u", "enter"])
            pressed_codes = [ev[0][1] if ev[0][1] != silentinput_daemon.evdev.ecodes.KEY_LEFTSHIFT else ev[1][1] for ev in emitted[::2]]
            ec = silentinput_daemon.evdev.ecodes
            self.assertEqual(
                pressed_codes,
                [ec.KEY_A, ec.KEY_4, ec.KEY_E, ec.KEY_2, ec.KEY_D],
            )

            # Headless mode: "hi中文!" -> skips Chinese without Ctrl+Shift+U, types only 'h', 'i', '!'
            emitted.clear()
            combos.clear()
            with mock.patch.object(silentinput_daemon, "_has_graphical_session", return_value=False):
                ok = await silentinput_daemon.type_text("hi中文!")
                self.assertTrue(ok)
            self.assertEqual(combos, [])
            headless_codes = [ev[-1][1] for ev in emitted[::2]]
            self.assertEqual(headless_codes, [ec.KEY_H, ec.KEY_I, ec.KEY_1])

    async def test_process_message_exposes_unicode_direct_typing(self):
        writer = _FakeWriter()
        cfg = {"access_control_mode": "whitelist", "whitelist": [], "auth_token": "secret-tok"}
        with mock.patch.object(silentinput_daemon, "_has_graphical_session", return_value=False):
            authed, _ = await silentinput_daemon.process_message(
                json.dumps({"type": "get_status"}), writer, False, False, cfg, "127.0.0.1"
            )
            self.assertFalse(authed)
            status_payload = json.loads(bytes(writer.buf).decode("utf-8").splitlines()[-1])
            self.assertEqual(status_payload["type"], "status")
            self.assertFalse(status_payload["unicode_direct_typing"])

            writer.buf.clear()
            authed, _ = await silentinput_daemon.process_message(
                json.dumps({"type": "auth", "token": "secret-tok"}), writer, False, False, cfg, "192.168.1.50"
            )
            self.assertTrue(authed)
            auth_payload = json.loads(bytes(writer.buf).decode("utf-8").splitlines()[-1])
            self.assertEqual(auth_payload["type"], "auth_result")
            self.assertEqual(auth_payload["status"], "ok")
            self.assertFalse(auth_payload["unicode_direct_typing"])

        writer.buf.clear()
        with mock.patch.object(silentinput_daemon, "_has_graphical_session", return_value=True):
            await silentinput_daemon.process_message(
                json.dumps({"type": "get_status"}), writer, False, True, cfg, "127.0.0.1"
            )
            status_payload = json.loads(bytes(writer.buf).decode("utf-8").splitlines()[-1])
            self.assertTrue(status_payload["unicode_direct_typing"])

    async def test_landscape_punctuation_key_map_and_wtype_fallback(self):
        ec = silentinput_daemon.evdev.ecodes
        expected_map = {
            "[": ec.KEY_LEFTBRACE,
            "lbracket": ec.KEY_LEFTBRACE,
            "]": ec.KEY_RIGHTBRACE,
            "rbracket": ec.KEY_RIGHTBRACE,
            ";": ec.KEY_SEMICOLON,
            "semicolon": ec.KEY_SEMICOLON,
            "'": ec.KEY_APOSTROPHE,
            "apostrophe": ec.KEY_APOSTROPHE,
            "`": ec.KEY_GRAVE,
            "grave": ec.KEY_GRAVE,
            ",": ec.KEY_COMMA,
            ".": ec.KEY_DOT,
            "-": ec.KEY_MINUS,
            "=": ec.KEY_EQUAL,
            "/": ec.KEY_SLASH,
            "\\": ec.KEY_BACKSLASH,
        }
        for name, code in expected_map.items():
            self.assertEqual(silentinput_daemon.KEY_MAP.get(name), code)

        calls = []

        async def fake_exec(*cmd, **kwargs):
            calls.append(cmd)
            return types.SimpleNamespace(wait=mock.AsyncMock(return_value=0))

        with mock.patch.object(silentinput_daemon, "ensure_uinput_device", return_value=False), \
             mock.patch.object(silentinput_daemon, "HAVE_UINPUT", False), \
             mock.patch.object(silentinput_daemon.asyncio, "create_subprocess_exec", side_effect=fake_exec):
            for key_name in ("[", "]", ";", "'", "`", ",", ".", "ctrl+["):
                await silentinput_daemon.emit_key_combination(key_name)
        self.assertEqual(
            calls,
            [
                ("wtype", "-k", "bracketleft"),
                ("wtype", "-k", "bracketright"),
                ("wtype", "-k", "semicolon"),
                ("wtype", "-k", "apostrophe"),
                ("wtype", "-k", "grave"),
                ("wtype", "-k", "comma"),
                ("wtype", "-k", "period"),
                ("wtype", "-M", "ctrl", "-k", "bracketleft"),
            ],
        )


if __name__ == "__main__":
    unittest.main()
