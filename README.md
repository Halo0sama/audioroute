# 声路 · 音频路由（AudioRoute）

Android 音频设备选择与路由诊断工具，无需 root。使用 Material 3 动态取色界面，可通过 Shizuku 请求系统级音频策略，也可通过 Python CLI 经 adb 控制。

## 下载

[v1.2.3 发布页与 APK](https://github.com/Halo0sama/audioroute/releases/tag/v1.2.3) · [更新记录](CHANGELOG.md)

当前下载包为真机验证过的 **debug 构建、调试签名版本**，与本次开发安装使用同一签名；尚未提供正式发布签名包。安装后请启动并授权 Shizuku，再在“声音输出”中切换媒体设备。

## 功能与边界

- **本 App 试音**：独立保存输入、输出选择；播放 1 秒测试音，或录音 4 秒后回放。显示系统报告的设备；蓝牙麦试音会临时建立通话音频，录完释放链路，再使用媒体输出回放。录音结束后立即释放麦克风，PCM 直接交给 AudioTrack 回放，播放前绑定所选设备。蓝牙通话输出也会临时建立并释放通信链路。
- **设备列表**：设备插拔自动更新，区分手机不同麦克风、扬声器、听筒、蓝牙 A2DP/SCO/LE、有线、USB 与 HDMI 设备。设备断开时保留选择并提示，不会静默改用其他设备。
- **全局策略（需 Shizuku）**：请求媒体使用扬声器、蓝牙 A2DP 或自动；录音使用蓝牙 SCO 或自动。Android 12+ 通过系统媒体首选设备切换输出并读回设备；恢复自动还原切换前的首选设置。录音策略与旧系统媒体策略使用 `AudioSystem.getForceUse` 读回。Android 13+ 另查预计媒体设备，试音期间持续读取实际输出。
- **通话设备请求**：Android 12+ 使用 `setCommunicationDevice` / `clearCommunicationDevice`；旧版本使用兼容 API。此请求可能被正在通话的其他 App 或系统覆盖。
- **本地 API + CLI**：仅监听手机 `127.0.0.1:8767`；CLI 为指定 adb 设备分配独立本机转发端口，并绕过 HTTP 代理。

**首页“声音输出”直接应用系统媒体切换，选择成功后本 App 回放也随之切换。** “声音输入”仍用于本 App 录音；底部“本 App 回放设备（高级）”和 CLI 的 `select-output` 只覆盖本 App 播放。媒体输出选择当前连接的扬声器或 A2DP 耳机。全局请求的作用范围是媒体，其他 App 自己指定的路由仍可能优先。蓝牙 SCO 麦克风需要建立通话音频链路，单独写入录音策略不能保证其他 App 建链。系统策略读回一致也不证明其他 App 的声音已经使用该设备；厂商策略、设备连接状态和其他 App 的显式路由都可能影响结果。

目前不提供可靠的“任意 App 手机麦收音 + 蓝牙耳机出声”全局保证；恢复自动策略也不等于强制使用手机麦克风。SCO、A2DP、LE Audio 是不同链路，需要用实际设备验证。Android 12+ 全局媒体会话在声路进程退出时恢复原设置；若 Shizuku 用户服务被强制杀死，下次连接会根据本地恢复记录撤销本次设置。恢复时保留其他程序后来改动的设备。设备插拔或其他程序改动策略后，请点击“刷新系统策略”或使用 `refresh`。

## 界面使用

首页“声音输出”里的“手机扬声器 / 蓝牙耳机 / 跟随系统”会立即提交全局媒体请求，需要 Shizuku。切换成功后清除本 App 的回放覆盖，让测试音和录音回放跟随同一媒体路由；输入选择保持不变。切换失败会显示原因，并按系统读回显示选中项。

“播放测试音 · 验证当前媒体输出”始终使用当前全局媒体路由。只想单独测试听筒、蓝牙通话或其他输出，可展开底部“本 App 回放设备（高级）”。系统设备报告与实际发声位置是不同证据；1.2.3 前的测试计数只证明软件流程和路由报告，不能代替实听。

## 构建与验证

要求 JDK 17+、Android SDK 36。项目固定使用 AGP 8.13.2、Gradle 8.13、Kotlin 2.3.21，支持 Android 8.0（API 26）及以上。

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 -m unittest discover -s tools/tests -v
adb -s <手机序列号> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <手机序列号> shell am start -n com.halo.audioroute/.MainActivity
```

Java/Gradle 不一定自动读取 macOS 系统代理。如果下载依赖超时，可显式传入代理地址（下面端口仅为示例，按本机代理修改）：

```bash
./gradlew -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 :app:assembleDebug
```

运行依赖安装在本机的 `adb` 和 Python 3.9+。首次使用全局策略，需要在手机上启动 [Shizuku](https://shizuku.rikka.app/) 并授权声路。手机重启后通常需重新启动 Shizuku。

## CLI

App 进程运行期间可用 API；后台存活由 Android 管理。多设备连接时必须指定 `--serial`，或设置 `ANDROID_SERIAL`。

```bash
python3 tools/audioroute-cli.py --serial <手机序列号> devices
python3 tools/audioroute-cli.py --serial <手机序列号> select-input builtin
python3 tools/audioroute-cli.py --serial <手机序列号> select-output speaker
python3 tools/audioroute-cli.py --serial <手机序列号> test record
python3 tools/audioroute-cli.py --serial <手机序列号> media speaker
python3 tools/audioroute-cli.py --serial <手机序列号> media none
python3 tools/audioroute-cli.py --serial <手机序列号> refresh
```

`test record` 需要先在手机上授予录音权限；CLI 等待试音结束并输出实际设备与错误信息。全局策略同步返回系统读回结果；失败返回非零退出码，设置操作不会自动重试。

更多命令、API 格式和设备回归测试见 [tools/README.md](tools/README.md)。

## 项目结构

```text
app/src/main/java/com/halo/audioroute/
├── MainActivity.kt       # Material 3 UI，与 CLI 共用可观察状态
├── RouteState.kt         # 设备/选择/试音/连接管理与有超时的特权调用
├── PcmWav.kt             # 标准 16-bit mono WAV 编码
├── MediaRouteVerification.kt # 核对请求的媒体设备类别与观测路由
├── MediaRouteLease.kt   # 媒体首选设备的保存、恢复与外部更改保护
├── SystemMediaRouter.kt # Shizuku 中的系统媒体首选设备接口
├── AudioForceService.kt  # Shizuku shell UserService
└── AudioApiServer.kt     # NanoHTTPD loopback API
app/src/test/             # 输出核验、媒体设置恢复、WAV 格式回归测试
tools/
├── audioroute-cli.py     # Python CLI
├── device-smoke.py       # 真机 API 回归（临时操作后恢复设置）
├── output-smoke.py       # 逐个输出设备与全局媒体回归
└── tests/                # 转发、代理、失败处理回归测试
```

开发接管记录与验证边界见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## License

[MIT](LICENSE)
