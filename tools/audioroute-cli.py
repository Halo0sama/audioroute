#!/usr/bin/env python3
"""声路 CLI。多设备时请指定 --serial；本机 API 始终绕过系统/VPN HTTP 代理。"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

PORT = 8767
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class CliError(Exception):
    pass


def adb(serial, *args):
    command = ["adb"] + (["-s", serial] if serial else []) + list(args)
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise CliError(f"adb 无法执行：{error}") from error
    if result.returncode:
        raise CliError(f"adb 失败：{(result.stderr.strip() or result.stdout.strip())[:300]}")
    return result.stdout.strip()


def ensure_forward(serial):
    # adb's forward list contains ALL serials. Only reuse this device's matching target.
    for line in adb(serial, "forward", "--list").splitlines():
        fields = line.split()
        if len(fields) == 3 and fields[0] == serial and fields[2] == f"tcp:{PORT}" and fields[1].startswith("tcp:"):
            return int(fields[1][4:])
    port = adb(serial, "forward", "tcp:0", f"tcp:{PORT}")
    try:
        return int(port)
    except ValueError as error:
        raise CliError(f"adb 没有返回有效本机端口：{port}") from error


def http(serial, path, body=None):
    port = ensure_forward(serial)
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        data=None if body is None else json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"} if body is not None else {},
    )
    # Mutations are never retried: a lost response does not mean the command failed.
    try:
        with OPENER.open(request, timeout=12) as response:
            data = json.load(response)
    except urllib.error.HTTPError as error:
        try:
            data = json.load(error)
            detail = data.get("error") or data.get("message") or str(data)
        except (ValueError, OSError):
            detail = error.reason
        raise CliError(f"HTTP {error.code}：{detail}") from error
    except (OSError, ValueError) as error:
        raise CliError(f"请求失败（{error}）；请确认声路正在运行。若为设置操作，请先查询状态再决定是否重试") from error
    if data.get("ok") is False:
        raise CliError(data.get("error") or data.get("message") or "操作失败")
    return data


def get(serial, path):
    return http(serial, path)


def post(serial, path, body):
    return http(serial, path, body)


def pick_serial():
    devices = [fields[0] for line in adb(None, "devices").splitlines()
               if len(fields := line.split()) == 2 and fields[1] == "device"]
    preferred = os.environ.get("ANDROID_SERIAL")
    if preferred:
        if preferred in devices:
            return preferred
        raise CliError(f"ANDROID_SERIAL 指定的设备未连接：{preferred}")
    if not devices:
        raise CliError("没有已连接的 adb 设备")
    if len(devices) > 1:
        raise CliError(f"检测到多台设备，请用 --serial 指定：{', '.join(devices)}")
    return devices[0]


def print_json(data):
    print(json.dumps(data, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="adb 设备序列号")
    sub = parser.add_subparsers(dest="cmd", required=True)
    for command in ("status", "devices", "shizuku", "refresh"):
        sub.add_parser(command)
    for command in ("select-input", "select-output"):
        sub.add_parser(command).add_argument("key", help="设备 key、auto、builtin（输入）或 speaker（输出）")
    sub.add_parser("test").add_argument("kind", choices=["record", "beep"])
    sub.add_parser("media").add_argument("force", choices=["none", "speaker", "bt_a2dp"])
    sub.add_parser("record").add_argument("force", choices=["none", "bt_sco"])
    sub.add_parser("call").add_argument("mode", choices=["on", "off"])
    args = parser.parse_args()
    try:
        serial = args.serial or pick_serial()
        if args.cmd in ("status", "shizuku"):
            print_json(get(serial, "/api/status"))
        elif args.cmd == "refresh":
            print_json(post(serial, "/api/refresh", {}))
        elif args.cmd == "devices":
            data = get(serial, "/api/devices")
            for group, selected, title in (("inputs", "selected_input", "输入设备"), ("outputs", "selected_output", "输出设备")):
                print(f"== {title} ==")
                for device in data[group]:
                    mark = "✓" if device["key"] == data[selected] else " "
                    print(f" {mark} {device['key']:24s} {device['name']} · {device.get('type_name', '')}")
                options = {"auto": "跟随系统", "builtin": "手机麦克风"} if group == "inputs" else {"auto": "跟随系统", "speaker": "手机扬声器"}
                for key, label in options.items():
                    print(f" {'✓' if data[selected] == key else ' '} {key:24s} {label}")
        elif args.cmd in ("select-input", "select-output"):
            field = "input" if args.cmd == "select-input" else "output"
            print_json(post(serial, "/api/select", {field: args.key}))
        elif args.cmd in ("media", "record"):
            print_json(post(serial, "/api/global", {args.cmd: args.force}))
        elif args.cmd == "test":
            post(serial, "/api/test", {"kind": args.kind})
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                result = get(serial, "/api/status")["audio_test"]
                if not result["running"]:
                    print_json(result)
                    if result.get("ok") is not True:
                        raise CliError(result.get("message") or "试音失败")
                    break
                time.sleep(0.25)
            else:
                raise CliError("等待试音超时，请查询 status")
        elif args.cmd == "call":
            print_json(post(serial, "/api/call", {"speaker": args.mode == "on"}))
    except CliError as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
