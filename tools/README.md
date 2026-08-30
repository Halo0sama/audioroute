# 声路 CLI

控制「声路 · 音频路由」App。App 启动后本地服务器监听 127.0.0.1:8767，
CLI 自动建立 adb forward 并调用。

快速上手（手机 USB 连接后）：
  python3 tools/audioroute-cli.py devices          # 列设备
  python3 tools/audioroute-cli.py media speaker    # 全局媒体强制扬声器
  python3 tools/audioroute-cli.py media none       # 恢复自动
  python3 tools/audioroute-cli.py status           # 状态

多设备用 --serial 指定。全局路由需手机上 Shizuku 已启动并授权声路。
