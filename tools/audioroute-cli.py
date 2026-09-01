#!/usr/bin/env python3
"""声路 CLI：控制「声路 · 音频路由」App。

用法（需手机 USB/网络连接 adb）：
  python3 audioroute-cli.py status                     # 查看状态
  python3 audioroute-cli.py devices                    # 列出输入/输出设备
  python3 audioroute-cli.py select-input <key|auto>    # 选择录音设备（本App）
  python3 audioroute-cli.py select-output <key|auto|speaker>  # 选择输出设备（本App）
  python3 audioroute-cli.py test record|beep           # 试音
  python3 audioroute-cli.py media none|speaker|bt_a2dp # 全局：媒体输出强制路由
  python3 audioroute-cli.py record none|bt_sco         # 全局：录音输入强制路由
  python3 audioroute-cli.py call on|off                # 通话走扬声器（免 Shizuku）
  python3 audioroute-cli.py shizuku                    # Shizuku 状态

可加 --serial <设备号> 指定 adb 设备；默认自动选第一台。
"""
import argparse
import json
import subprocess
import time
import sys
import urllib.request

PORT = 8767


def adb(serial, *args):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        err = r.stderr.strip() or r.stdout.strip()
        sys.exit(f"adb 失败: {err[:200]}")


def ensure_forward(serial):
    # 只在转发不存在时建立：反复重建会把活跃连接打进黑洞
    out = subprocess.run(["adb", "-s", serial, "forward", "--list"],
                         capture_output=True, text=True).stdout
    if f"tcp:{PORT}" not in out:
        adb(serial, "forward", f"tcp:{PORT}", f"tcp:{PORT}")


def http(serial, path, body=None, tries=2):
    ensure_forward(serial)
    last_err = None
    for attempt in range(tries):
        try:
            if body is None:
                req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}")
            else:
                req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}",
                                             data=json.dumps(body).encode(),
                                             headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=15) as r:
                return json.loads(r.read())
        except Exception as e:
            last_err = e
    sys.exit(f"请求失败（{last_err}）；App 是否在前台运行？可尝试重启声路")


def get(serial, path):
    return http(serial, path)


def post(serial, path, body):
    return http(serial, path, body)


def pick_serial():
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    devs = [l.split()[0] for l in out.splitlines() if l.endswith("device")]
    if not devs:
        sys.exit("没有已连接的 adb 设备")
    return devs[0]


def main():
    ap = argparse.ArgumentParser(description="声路控制 CLI")
    ap.add_argument("--serial", help="adb 设备序列号")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status")
    sub.add_parser("devices")
    si = sub.add_parser("select-input"); si.add_argument("key")
    so = sub.add_parser("select-output"); so.add_argument("key")
    t = sub.add_parser("test"); t.add_argument("kind", choices=["record", "beep"])
    m = sub.add_parser("media"); m.add_argument("force", choices=["none", "speaker", "bt_a2dp"])
    r = sub.add_parser("record"); r.add_argument("force", choices=["none", "bt_sco"])
    c = sub.add_parser("call"); c.add_argument("mode", choices=["on", "off"])
    sub.add_parser("shizuku")
    args = ap.parse_args()

    serial = args.serial or pick_serial()

    if args.cmd == "status":
        s = get(serial, "/api/status")
        print(json.dumps(s, ensure_ascii=False, indent=1))
    elif args.cmd == "devices":
        d = get(serial, "/api/devices")
        print("== 输入设备 ==")
        for i in d["inputs"]:
            mark = "✓" if i["key"] == d["selected_input"] else " "
            print(f" {mark} {i['key']:24s} {i['name']}")
        print(f"   {'✓' if d['selected_input']=='auto' else ' '} {'auto':24s} 系统默认")
        print("== 输出设备 ==")
        for o in d["outputs"]:
            mark = "✓" if o["key"] == d["selected_output"] else " "
            print(f" {mark} {o['key']:24s} {o['name']}")
        for opt in ("auto", "speaker"):
            mark = "✓" if d["selected_output"] == opt else " "
            print(f"   {mark} {opt:24s} {'跟随系统' if opt=='auto' else '手机扬声器'}")
    elif args.cmd == "select-input":
        print(json.dumps(post(serial, "/api/select", {"input": args.key}), ensure_ascii=False, indent=1))
    elif args.cmd == "select-output":
        print(json.dumps(post(serial, "/api/select", {"output": args.key}), ensure_ascii=False, indent=1))
    elif args.cmd == "test":
        print(json.dumps(post(serial, "/api/test", {"kind": args.kind}), ensure_ascii=False))
    elif args.cmd == "media":
        r = post(serial, "/api/global", {"media": args.force})
        time.sleep(2)
        s = get(serial, "/api/status")
        print(f"媒体强制路由 → {args.force} | 当前 force.media = {s['force'].get('media')}")
    elif args.cmd == "record":
        r = post(serial, "/api/global", {"record": args.force})
        time.sleep(2)
        s = get(serial, "/api/status")
        print(f"录音强制路由 → {args.force} | 当前 force.record = {s['force'].get('record')}")
    elif args.cmd == "call":
        print(json.dumps(post(serial, "/api/call", {"speaker": args.mode == "on"}), ensure_ascii=False))
    elif args.cmd == "shizuku":
        s = get(serial, "/api/status")
        print("Shizuku 连接:", s["shizuku"])
        print("全局 force:", json.dumps(s["force"]))


if __name__ == "__main__":
    main()
