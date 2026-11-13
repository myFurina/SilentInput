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
    def __init__(self, peername=("192.168.1.50", 12345)):
        self.buf = bytearray()
        self.closed = False
        self._peername = peername

    def write(self, data: bytes):
        self.buf.extend(data)

    async def drain(self):
        return

    def close(self):
        self.closed = True

    async def wait_closed(self):
        return

    def get_extra_info(self, name, default=None):
        if name == "peername":
            return self._peername
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


if __name__ == "__main__":
    unittest.main()
