import importlib.util
import io
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch
import urllib.error

spec = importlib.util.spec_from_file_location("audioroute_cli", Path(__file__).parents[1] / "audioroute-cli.py")
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class CliTests(unittest.TestCase):
    def test_reuses_only_target_devices_forward(self):
        with patch.object(cli, "adb", return_value="phone tcp:45000 tcp:8767\nemulator tcp:8767 tcp:8767") as adb:
            self.assertEqual(45000, cli.ensure_forward("phone"))
            adb.assert_called_once_with("phone", "forward", "--list")

    def test_other_device_forward_is_not_reused(self):
        with patch.object(cli, "adb", side_effect=["emulator tcp:8767 tcp:8767", "45001"]) as adb:
            self.assertEqual(45001, cli.ensure_forward("phone"))
            self.assertEqual(("phone", "forward", "tcp:0", "tcp:8767"), adb.call_args.args)

    def test_same_port_different_remote_is_not_reused(self):
        with patch.object(cli, "adb", side_effect=["phone tcp:8767 tcp:1234", "45002"]):
            self.assertEqual(45002, cli.ensure_forward("phone"))

    def test_multiple_devices_require_serial(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(cli, "adb", return_value="List of devices attached\nphone\tdevice\nemulator\tdevice\n"):
            with self.assertRaisesRegex(cli.CliError, "多台设备"):
                cli.pick_serial()

    def test_ignores_unauthorized_and_offline(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(cli, "adb", return_value="phone\tdevice\nother\tunauthorized\nold\toffline"):
            self.assertEqual("phone", cli.pick_serial())

    def test_respects_android_serial(self):
        with patch.dict(os.environ, {"ANDROID_SERIAL": "emulator"}), patch.object(cli, "adb", return_value="phone\tdevice\nemulator\tdevice"):
            self.assertEqual("emulator", cli.pick_serial())

    def test_missing_preferred_device_fails(self):
        with patch.dict(os.environ, {"ANDROID_SERIAL": "missing"}), patch.object(cli, "adb", return_value="phone\tdevice"):
            with self.assertRaisesRegex(cli.CliError, "未连接"):
                cli.pick_serial()

    def test_http_uses_allocated_port(self):
        with patch.object(cli, "ensure_forward", return_value=45003), patch.object(cli.OPENER, "open", return_value=io.BytesIO(b'{"ok":true}')) as request:
            self.assertEqual({"ok": True}, cli.get("phone", "/api/status"))
            self.assertEqual("http://127.0.0.1:45003/api/status", request.call_args.args[0].full_url)

    def test_failed_mutation_is_not_retried(self):
        with patch.object(cli, "ensure_forward", return_value=45003), patch.object(cli.OPENER, "open", side_effect=OSError("timeout")) as request:
            with self.assertRaisesRegex(cli.CliError, "请先查询状态"):
                cli.post("phone", "/api/global", {"media": "speaker"})
            self.assertEqual(1, request.call_count)

    def test_http_error_displays_server_diagnostic(self):
        error = urllib.error.HTTPError("http://localhost", 503, "unavailable", {}, io.BytesIO('{"message":"Shizuku 未连接"}'.encode()))
        with patch.object(cli, "ensure_forward", return_value=45003), patch.object(cli.OPENER, "open", side_effect=error):
            with self.assertRaisesRegex(cli.CliError, "503.*Shizuku 未连接"):
                cli.post("phone", "/api/global", {"media": "speaker"})

    def test_proxy_environment_cannot_redirect_loopback(self):
        # ProxyHandler({}) is omitted from opener's handler chain; no defaults are installed.
        with patch.dict(os.environ, {"http_proxy": "http://127.0.0.1:1", "HTTP_PROXY": "http://127.0.0.1:1"}):
            opener = cli.urllib.request.build_opener(cli.urllib.request.ProxyHandler({}))
            self.assertFalse(any(isinstance(h, cli.urllib.request.ProxyHandler) and h.proxies for h in opener.handlers))


if __name__ == "__main__":
    unittest.main()
