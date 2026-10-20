#!/usr/bin/env python3
"""实机输出回归：逐个播放输出设备，按需录音回放和切换全局媒体。结束后恢复选择与原媒体设置。"""
import argparse
import importlib.util
import json
from pathlib import Path
import time

spec = importlib.util.spec_from_file_location("cli", Path(__file__).with_name("audioroute-cli.py"))
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--record-input", action="append", default=[], help="对每个输出录音回放，可多次提供设备 key / builtin")
    parser.add_argument("--global", dest="global_test", action="store_true")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    serial = args.serial
    before = cli.get(serial, "/api/status")
    assert not before["audio_test"]["running"], "已有试音，未开始测试"
    devices = cli.get(serial, "/api/devices")
    if args.global_test:
        assert before["shizuku"], "Shizuku 未连接"
        before = cli.post(serial, "/api/refresh", {})
        assert not (before.get("media_strategy") or {}).get("active"), "请先恢复自动，再运行全局回归"
        assert before["force"]["media"] == 0, "原 force 媒体策略不是自动，未开始测试"
    results = []
    global_changed = False

    def play(name, output, kind="beep", input_key=None, expected_types=None):
        selection = {"output": output}
        if input_key is not None:
            selection["input"] = input_key
        cli.post(serial, "/api/select", selection)
        cli.post(serial, "/api/test", {"kind": kind})
        deadline = time.monotonic() + 30
        while True:
            state = cli.get(serial, "/api/status")
            test = state["audio_test"]
            if not test["running"]:
                break
            assert time.monotonic() < deadline, test
            time.sleep(0.15)
        result = {"name": name, "selection": selection, "test": test}
        results.append(result)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps({"before": before, "cases": results}, ensure_ascii=False, indent=2))
        assert test["ok"], result
        assert test["routed_outputs"], result
        if output != "auto":
            assert test["routed_outputs"] == [output], result
        if expected_types:
            assert all(int(key.split(":", 1)[0][1:]) in expected_types for key in test["routed_outputs"]), result
        if kind == "record":
            assert test["recorded_bytes"] == 128000 and test["routed_input"], result
            if input_key not in {"auto", "builtin"}:
                assert test["routed_input"] == input_key, result
            elif input_key == "builtin":
                assert test["routed_input"].startswith("t15:"), result
        print(f"PASS {name}: {test['routed_input']} -> {test['routed_outputs']}", flush=True)

    try:
        for output in devices["outputs"]:
            play(f"beep-{output['type']}", output["key"])
            for input_key in args.record_input:
                play(f"record-{input_key}-to-{output['type']}", output["key"], "record", input_key)
        if args.global_test:
            for mode, expected in (("speaker", [2, 24]), ("bt_a2dp", [8]), ("none", None)):
                if mode == "bt_a2dp" and not any(d["type"] == 8 for d in devices["outputs"]):
                    continue
                global_changed = True
                cli.post(serial, "/api/global", {"media": mode})
                play(f"global-{mode}", "auto", expected_types=expected)
    finally:
        try:
            if global_changed:
                cli.post(serial, "/api/global", {"media": "none"})
        finally:
            cli.post(serial, "/api/select", {"input": before["selected_input"], "output": before["selected_output"]})
    print(f"PASS {len(results)} output cases; settings restored")


if __name__ == "__main__":
    main()
