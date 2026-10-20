#!/usr/bin/env python3
"""真机 API 回归。临时更改本 App 选择并播放短测试音，结束后恢复选择。
--global 额外验证并恢复全局媒体策略；--record 额外录音回放，需预先授权。
"""
import argparse
import importlib.util
import json
from pathlib import Path
import time
import urllib.error
import urllib.request

spec = importlib.util.spec_from_file_location("cli", Path(__file__).with_name("audioroute-cli.py"))
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--global", dest="global_test", action="store_true")
    parser.add_argument("--record", action="store_true")
    parser.add_argument("--restart", action="store_true", help="额外验证选择后立即重启 App 的持久化")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    port = cli.ensure_forward(args.serial)
    base = f"http://127.0.0.1:{port}"
    results = []

    def request(path, data=None, method=None, expected=200, raw=None):
        payload = raw if raw is not None else (json.dumps(data).encode() if data is not None else None)
        req = urllib.request.Request(base + path, data=payload, method=method)
        try:
            response = cli.OPENER.open(req, timeout=12)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status, body = response.code, json.load(response)
        allowed = expected if isinstance(expected, tuple) else (expected,)
        assert status in allowed, (path, status, body, expected)
        results.append({"path": path, "method": req.get_method(), "expected_status": expected, "response": body})
        return body

    def wait_ready():
        # Only read requests are retried, including right after adb am start.
        deadline = time.monotonic() + 10
        while True:
            try:
                with cli.OPENER.open(base + "/api/status", timeout=1) as response:
                    ready = json.load(response)
                if not args.global_test or (ready["shizuku"] and not ready.get("force_busy")):
                    return ready
            except (OSError, ValueError):
                pass
            assert time.monotonic() < deadline, "App 或 Shizuku 未就绪"
            time.sleep(0.2)

    wait_ready()
    original = request("/api/refresh", {}) if args.global_test else request("/api/status")
    original_media = original["force"]["media"]
    initial_devices = request("/api/devices")
    assert original["selected_input"] in {"auto", "builtin", *(d["key"] for d in initial_devices["inputs"])}, "原输入已断开；未开始测试"
    assert original["selected_output"] in {"auto", "speaker", *(d["key"] for d in initial_devices["outputs"])}, "原输出已断开；未开始测试"
    if args.global_test:
        assert original_media in {0, 1, 4}, "原媒体策略无法通过此 API 恢复；未开始测试"
    media_changed = False
    try:
        health = request("/api/health")
        assert health["version"] == "1.2.3", health
        devices = request("/api/devices")
        assert devices["inputs"] and devices["outputs"], devices
        request("/api/status", {}, expected=405)
        request("/api/global", method="GET", expected=405)
        request("/api/missing", expected=404)
        request("/api/select", {}, expected=400)
        request("/api/select", raw=b'{', expected=400)
        request("/api/select", raw=b' ' * 8193, expected=413)
        request("/api/select", {"input": "builtin"})
        changed = request("/api/select", {"output": "speaker"})
        assert changed["selected_input"] == "builtin", changed
        request("/api/select", {"output": "auto", "input": "missing-device"}, expected=400)
        unchanged = request("/api/status")
        assert unchanged["selected_input"] == "builtin" and unchanged["selected_output"] == "speaker", unchanged
        changed = request("/api/select", {"input": None})
        assert changed["selected_input"] == "auto" and changed["selected_output"] == "speaker", changed
        request("/api/select", {"output": False}, expected=400)
        request("/api/global", {"media": "invalid"}, expected=400)
        request("/api/global", {"media": "speaker", "record": "invalid"}, expected=400)
        request("/api/call", {"speaker": "false"}, expected=400)
        request("/api/test", {"kind": "invalid"}, expected=400)
        for kind in (["beep", "record"] if args.record else ["beep"]):
            request("/api/test", {"kind": kind}, expected=202)
            request("/api/test", {"kind": "beep"}, expected=409)
            deadline = time.monotonic() + 30
            while True:
                start = time.monotonic()
                status = request("/api/status")
                assert time.monotonic() - start < 1, "status blocked by audio test"
                test = status["audio_test"]
                if not test["running"]:
                    assert test["ok"], test
                    assert test["routed_output"] == "t2:a", test
                    if kind == "record":
                        assert test["recorded_bytes"] == 128000 and test["routed_input"], test
                    break
                assert time.monotonic() < deadline, test
                time.sleep(0.3)
        if args.global_test:
            # Only touch states this test can restore with public API options.
            names = {0: "none", 1: "speaker", 4: "bt_a2dp"}
            assert original["shizuku"] and original_media in names, original
            media_changed = True
            force = request("/api/global", {"media": "speaker"}, expected=(200, 503))
            actual_force = force["force"]["media"]
            if force["ok"]:
                strategy = force.get("media_strategy") or {}
                if strategy.get("supported"):
                    assert strategy["active"] and [d["type"] for d in strategy["preferred_devices"]] == [2], force
                else:
                    assert actual_force == 1, force
            else:
                assert force["shizuku"] and actual_force is not None and force["message"], force
            policy = cli.adb(args.serial, "shell", "dumpsys", "media.audio_policy")
            # Some systems promptly restore NONE; treat their explicit rejection as a limitation.
            if force["ok"] and not (force.get("media_strategy") or {}).get("supported"):
                assert "Force use for media: 1" in policy, "system overrode policy after verification"
            request("/api/refresh", {})
        if args.restart:
            request("/api/select", {"input": "builtin", "output": "speaker"})
            cli.adb(args.serial, "shell", "am", "force-stop", "com.halo.audioroute")
            cli.adb(args.serial, "shell", "am", "start", "-n", "com.halo.audioroute/.MainActivity")
            wait_ready()
            persisted = request("/api/status")
            assert persisted["selected_input"] == "builtin" and persisted["selected_output"] == "speaker", persisted
    finally:
        request("/api/select", {"input": original["selected_input"], "output": original["selected_output"]})
        if media_changed:
            request("/api/global", {"media": {0: "none", 1: "speaker", 4: "bt_a2dp"}[original_media]})
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps({"checks": results}, ensure_ascii=False, indent=2))
    print(f"PASS: {len(results)} HTTP checks; selections and media policy restored")


if __name__ == "__main__":
    main()
