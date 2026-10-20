# 声路 · 音频路由（AudioRoute）

一款 Android 全局音频路由工具：手动选择**录音输入**与**播放输出**设备，并可通过 [Shizuku](https://shizuku.rikka.app/) 在**系统层面强制全局路由**——让所有 App 的媒体声音走扬声器或蓝牙耳机、让录音走指定麦克风，而不需要 root。

适合的场景：蓝牙耳机麦克风收音质量差、想"手机麦收音 + 耳机出声"、想强制某 App 的声音走指定输出设备，以及需要在电脑上远程控制手机音频路由的自动化玩法。

## 功能

- **设备枚举**：动态列出系统输入/输出音频设备，显示系统原始名称（如 `MOONDROP littlewhite`），支持蓝牙（A2DP / SCO）、有线、USB、内置设备
- **本 App 试音**：按选中输入录 4 秒并回放到选中输出；播放测试音——先验证再使用
- **全局强制路由（需 Shizuku）**：
  - 媒体输出 → 强制扬声器 / 强制蓝牙耳机（A2DP）/ 恢复自动
  - 录音输入 → 强制蓝牙麦克风（SCO）/ 恢复自动
  - 经 `AudioSystem.setForceUse` 在 AudioPolicyManager 层生效，影响**所有 App**
- **通话路由（免 Shizuku）**：通话 / 微信语音一键切扬声器
- **Material 3 动态取色 UI**（Android 12+ 莫奈取色，随壁纸变色）
- **本地控制服务器 + CLI**：App 启动后监听 `127.0.0.1:8767`，配套 Python CLI 可经 adb 远程控制全部功能，方便自动化与脚本集成

## 全局路由的原理与边界

- 原理：Shizuku 以 shell 权限（UID 2000）运行 App 提供的 UserService，反射调用隐藏 API `android.media.AudioSystem.setForceUse(usage, force)`，在系统音频策略层覆盖默认路由
- forceUse 是**设备类别级**强制（扬声器/蓝牙/有线），不能精确到某一个具体设备
- 路由状态在**设备插拔或重启后会被系统重置**，需要重新应用
- Shizuku server 重启手机后需重新启动一次（无线调试或连接电脑启动）

## 构建

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

要求：Android Gradle Plugin 8.7+、Kotlin 2.x、compileSdk 36、minSdk 26。

## CLI 用法

手机连接 USB 后（App 在前台运行）：

```bash
python3 tools/audioroute-cli.py devices          # 列出输入/输出设备（✓ 标当前选择）
python3 tools/audioroute-cli.py select-output t8:aXX...   # 选择输出设备（本 App 试音用）
python3 tools/audioroute-cli.py test beep        # 播放测试音
python3 tools/audioroute-cli.py media speaker    # 全局：媒体强制扬声器
python3 tools/audioroute-cli.py media bt_a2dp    # 全局：媒体强制蓝牙耳机
python3 tools/audioroute-cli.py record bt_sco    # 全局：录音强制蓝牙麦克风
python3 tools/audioroute-cli.py media none       # 恢复自动
python3 tools/audioroute-cli.py call on          # 通话走扬声器（免 Shizuku）
python3 tools/audioroute-cli.py status           # 查看状态
```

多设备用 `--serial <序列号>` 指定。

## 项目结构

```
app/src/main/java/com/halo/audioroute/
├── MainActivity.kt       # Material3 UI + Shizuku 授权流
├── RouteState.kt         # 核心：设备枚举/选择/试音/全局路由（Application 级单例）
├── AudioForceService.kt  # Shizuku UserService：反射 setForceUse（运行在 shell 进程）
└── AudioApiServer.kt     # 本地控制服务器（NanoHTTPD, 127.0.0.1:8767）
tools/
└── audioroute-cli.py     # Python CLI
```

## 开发踩坑记录

- `AudioSystem` 是 `@hide` 类，编译期不可见，必须运行期反射
- Shizuku `UserServiceArgs` 必须设置 `processNameSuffix`，否则 NPE
- Shizuku 的 binder 推送可能晚于 App 首次 bind：必须注册 `addBinderReceivedListener` 重绑
- ServiceConnection 必须挂在 Application 级对象上，挂 Activity 会因旋转/重建被 unbind
- UserService 进程被杀后 binder 调用会无限挂起：调用需独立线程 + 看门狗
- App 内建本地服务器别忘了 `INTERNET` 权限（否则 ServerSocket 直接 EPERM）

## License

[MIT](LICENSE)
