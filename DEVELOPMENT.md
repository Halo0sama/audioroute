# 开发接管记录

2026-10-20，本地工作目录 `/Users/halo/dsharness/audioroute`，基线提交 `43b1e2b`，开发分支 `codex/stability-2026-10-20`。

## 项目审查结论

原项目规模较小：一个 Compose Activity、共享状态单例、Shizuku UserService、本地 NanoHTTPD 服务器和 Python CLI。缺少自动化回归，文档对全局路由的保证超出了代码能证明的范围。

本轮优先修复实际使用路径中的问题：

| 问题 | 修复 |
| --- | --- |
| `/api/select` 缺少任一字段会重置另一个方向；异步保存可能在立刻终止进程时丢失 | 只修改提供的字段，先校验全部字段，写入成功后再确认选择 |
| WAV 的四个 uint16 字段错误地按 uint32 写入 | 标准 44 字节头；使用独立音频解码器校验采样率、帧数和完整 PCM |
| `builtin` 实际跟随系统，可能仍使用蓝牙麦克风 | 明确解析到内置麦克风；默认项独立命名“跟随系统” |
| 录放异常缺少 finally，UI/HTTP 可以重叠试音 | 单次试音互斥、异常可见、读取截止时间与资源释放 |
| 全局调用失败仍缓存请求值，HTTP 提交即报成功 | 系统读回与请求比较，失败返回错误；状态缓存带时间戳 |
| Binder 超时后连接 UI 仍显示成功，允许不断积累线程 | 清空连接/缓存，单个 Binder 执行器限制挂起任务；提供重新连接 |
| Activity 重建重复注册 Shizuku 监听器 | 进程级只注册一次，监听服务死亡 |
| 重连后 Shizuku 用户服务进程残留、旧断开事件覆盖新连接 | 实现保留的 AIDL destroy 事务号 16777114；每次绑定独立回调和代次，解绑旧监听后重连 |
| UI 不响应 CLI 选择或设备插拔 | 共用 Compose 状态，注册设备回调；保留断开设备选择并提示 |
| 过时通话 API、固定行宽按钮、状态栏对比度 | API 31+ 通话设备请求，FlowRow 换行，edge-to-edge 与可选中行语义 |
| CLI 默认选第一台设备，转发列表可能属于另一台设备 | 多设备要求明确指定 serial，按 serial 和完整转发目标复用，动态本机端口 |
| CLI 受 HTTP 代理影响，写命令失败后自动重试 | 本机请求绕过代理，写操作不重试，显示服务端错误并返回非零退出码 |
| API 版本写死、方法/参数校验不足、Manifest 遗留文本 | 版本来自 BuildConfig，明确 400/403/405/409/413/503，清理无效文本 |
| minSdk 26 路径直接调用 API 28 的设备地址和 MediaPlayer 路由方法 | API 26/27 使用设备 ID 与 AudioTrack 播放；Shizuku Binder 不再误注册为普通 Android Service |
| AGP 8.7.3 的 lint 与当前 Compose 检查器 ABI 冲突 | AGP 8.13.2 + Gradle 8.13 + Build Tools 36.0.0；保留 lint 检查 |

构建工具兼容性依据：[Android 官方 AGP 8.13 发布说明](https://developer.android.com/build/releases/agp-8-13-0-release-notes)。通话请求的优先级与限制：[AudioManager 官方文档](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo))。

## 验证与回滚

详细设备响应、日志、截图、原 APK 与原选择设置保存在本地忽略目录 `artifacts/takeover-2026-10-20/`。此目录可能包含设备标识，不提交仓库。没有卸载 App 或清除其数据。

最终验证结果：

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline` 通过；4 项音频单元测试、11 项 CLI 测试通过。
- lint 为 0 错误、13 条警告，主要是依赖更新提示、私有 API、图标及样式建议，未全局关闭检查。
- 最终 APK 的真机回归完成 63 次 HTTP 检查，包含 4 秒录音回放（128,000 字节 PCM）、媒体策略与 `dumpsys` 读回一致、保存后立即重启的持久化验证。
- 暂停声路自己的 Shizuku 用户服务后，状态查询约 6 毫秒返回，8.01 秒明确超时；恢复服务后，迟到的结果不会重新填充已失效缓存。重连时旧进程退出，新服务恢复。
- 连续 3 次正常重连，每次均只剩一个用户服务进程；强制终止并重启 App 后选择及连接可恢复。
- 通话请求可被系统接受并撤销；这不等于已验证第三方通话 App 的行为。模拟器上的未启动 Shizuku 路径返回 503，CLI 非零退出；多设备未指定序列号时明确报错。
- 已安装 APK 的 SHA256 与最终构建一致；测试结束时输入输出均恢复“跟随系统”，媒体/录音策略恢复 0，临时录音授权恢复为未授权。

完整结果见 `validation.json`、`device-smoke.json`、`binder-timeout.json`、`repeat-reconnect.json`。构建初期的 lint ABI 崩溃日志保留为 `legacy-toolchain-failure.log`。

回退代码可切回原分支，或基于 `43b1e2b` 检查；不要使用 `git reset --hard` 丢弃本轮未提交修改。原 APK 版本号较低，设备可能拒绝普通降级安装；如需回滚，先保存当前数据，再使用适合设备的降级安装流程，避免通过卸载回滚而清掉设置。

## 仍需设备验证的行为

- 本轮连接的手机为 Android 17（API 37）；minSdk 26 的旧系统兼容路径尚无对应真机覆盖。
- 首轮 1.2.0 未连接外部音频设备；A2DP/SCO 的补充验证见下方蓝牙跟进。LE Audio、有线、USB、插拔恢复与客观音质仍需后续实测。
- 系统报告的 `routedDevice` 与 `getForceUse` 是不同层面的证据。系统策略读回一致不能推导出第三方 App、通话或音频硬件一定按期望工作。
- 本地服务器跟随 App 进程；Android 杀后台时会停止，不提供持续后台保活保证。
- 不自动重施全局策略，不接管其他 App 的音频会话，不在设备重连时偷偷覆盖用户设置。

## 后续值得考虑

1. **设备路由预设**：保存常用输入输出组合，一键应用；全局策略与本 App 选择明确分开。
2. **诊断报告导出**：包含设备、策略读回、试音实际路由、版本和错误，默认不包含录音内容。
3. **可选后台控制服务**：有常驻通知和明确停止入口；先确认用户确有熄屏自动化需求再做。


## 蓝牙跟进：1.2.1

连接 SIMGOT OnClip ONE 后，在同一台 Android 17 手机完成了 A2DP 与 HFP/SCO 检查。原始蓝牙信息报告当前媒体编码为 LDAC / 96 kHz；这只描述媒体链路，不是录音质量的证据。

发现仅给 AudioRecord 设置 SCO preferredDevice、或仅写入 FOR_RECORD=BT_SCO，实际仍会使用手机麦克风，蓝牙堆栈处于 SCO_STATE_INACTIVE。修复后，本 App 的蓝牙麦试音临时进入通信模式，Android 12+ 使用 setCommunicationDevice 建链，并使用 VOICE_COMMUNICATION 音源；结束后撤销临时请求、恢复模式，再经媒体链路回放。Android 8–11 使用兼容 SCO 流程，尚未实机验证旧系统。

实测组合包括：A2DP 测试音、手机底部麦克风 → A2DP、SCO 麦克风 → A2DP、SCO 试音后再次手机麦 → A2DP。SCO 录音期间，录音器报告蓝牙设备，AudioService 为 SCO_STATE_ACTIVE_INTERNAL，耳机服务 mAudioState=12；录音后恢复媒体链路。每次录音为 128,000 字节的 4 秒 PCM，不保留录音内容。

全局媒体策略存在本机限制：策略和预计设备的瞬时读回可以正确，系统随后又将媒体 force 恢复为 0，播放器实际使用 A2DP。为避免误报，新增全部写操作后的最终读回、独立的 requested_media_force、Android 13+ 的预计媒体设备缓存，以及播放期间连续采样实际输出。Android 16+ 读取完整输出列表；默认输出试音与最近请求不符时明确失败。系统预计设备、真实播放器路由、主观听感是不同证据，不能相互替代。

试音期间拒绝更改通话请求，防止临时 SCO 清理覆盖同时发起的扬声器请求。本轮增加了媒体路由不符、重复输出、未知观测、后续录音操作覆盖先前媒体策略等回归用例。

蓝牙证据与修复前后结果保存在忽略目录 `artifacts/bluetooth-2026-10-20/`；保留修复前 APK、策略/蓝牙/音频日志及 JSON 报告。此前“未连接蓝牙”的限制已补充 A2DP/SCO 设备证据；LE Audio、USB/有线、第三方 App 通话、客观音质仍未验证。

接口依据：[通话设备请求](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo))、[预计音频设备](https://developer.android.com/reference/android/media/AudioManager#getAudioDevicesForAttributes(android.media.AudioAttributes))、[播放器完整路由](https://developer.android.com/reference/android/media/MediaPlayer#getRoutedDevices())。


1.2.1 最终回归：构建、10 项 Android 单元测试、11 项 CLI 测试通过；lint 为 0 错误、14 条警告。蓝牙连接状态下通用 API 回归完成 64 次 HTTP 检查。蓝牙麦录音过程中尝试更改通话设备返回 409，原 SCO 录音继续正常。扬声器限制作为已确认的失败路径记录，不计为扬声器功能验证通过。

## 输出故障修复：1.2.2

用户确认蓝牙连接后，本 App 试音与其他 App 媒体输出都无法切换，输入选择基本正常。本轮沿用录音逻辑，修复两条输出路径：

- 本 App 的 MediaPlayer 在接受 preferredDevice 后仍走 A2DP。统一改为 AudioTrack，在 play 前绑定设备，播放期间读取完整实际路由。没有实际输出、输出不符或出现额外输出均不能报成功。SCO/LE 通话输出复用原有临时通信链路流程，结束后释放。
- Android 12+ 全局媒体改用 AudioService 的媒体策略首选设备接口，不再依赖会被本机重置的 FOR_MEDIA force-use 值。通过 AudioManager 发现策略，兼容本机 Android 17 已移除 AudioProductStrategy.getAudioProductStrategies 的变化。通过 shell 枚举真实端口地址，避免将普通 App 中被隐私遮蔽的蓝牙地址写入系统策略。旧系统保留原 force-use 兼容路径。
- 切换前同步保存恢复记录，服务记住原首选设备。恢复自动、主进程死亡、正常销毁用户服务时撤销本次媒体设置；用户服务被强制杀死时，下次连接恢复。恢复前比较当前设备与本次写入值，遇到外部改动保留新设置。
- API 增加 media_strategy，单独呈现首选设备和是否由声路管理。force 继续呈现真实旧接口值；在新路径中媒体 force=0 不表示扬声器切换失败。界面按钮按首选设备读回状态显示。

本轮输出与恢复证据保存在忽略目录 artifacts/output-routing-2026-10-20/。测试使用原手机与 SIMGOT OnClip ONE，另安装无特权、无显式设备请求的临时 MediaPlayer 测试 App，以独立 UID 核验全局路由；完成后移除。系统路由证据不代替用户的实际听感确认，第三方 App 显式路由仍可优先。

接口依据：[Android 系统媒体首选设备](https://source.android.com/docs/core/audio/combined-audio-routing)、[AudioTrack](https://developer.android.com/reference/android/media/AudioTrack)、[播放实际路由](https://developer.android.com/reference/android/media/AudioRouting#getRoutedDevices())。本节修复了上文 1.2.1 扬声器限制；旧记录保留作为修复前证据。

1.2.2 实机结果：四种输出（听筒、扬声器、蓝牙 SCO、蓝牙 A2DP）的测试音，以及手机麦、蓝牙麦分别到四种输出的录音回放均按所选设备报告，共 15 组输出/全局用例通过。无显式设备请求的独立 MediaPlayer 测试 App 随全局策略播放至扬声器/A2DP，播放中切换的实际路由序列为 A2DP → 扬声器 → A2DP。主进程强制退出后独立播放器回到默认 A2DP；强制杀死自己的 Shizuku 用户服务并重启 App 后，恢复记录撤销了残留首选设备。20 项 Android 单元测试、11 项 CLI 测试通过；lint 为 0 错误、23 条警告。

## 实听复核与操作入口修正：1.2.3

用户再次报告“仍然始终从耳机播放”。检查当时设备状态，局部 selected_output=t2:a，但全局媒体 preferred_devices 为空、active=false、requested_media_force=null。局部回放设备选择没有提交全局媒体操作，是本轮已确认的操作入口问题；不能据此推断用户此前所有失败均由同一原因造成。

手动明确应用全局扬声器请求并保留蓝牙连接后，用户回复“手机扬声器已经出声”。这次实听确认了该受控状态下扬声器可以发声，因此不放弃输出功能。此前仅凭路由报告就称输出修复完成，结论过早；历史测试报告保留，但其通过项只证明软件流程与 Android 路由报告。

1.2.3 将首页“声音输出”改成直接执行全局媒体切换，完成后将本 App 回放恢复跟随系统，输入选择保持原值。首页测试音显式使用媒体路由，避免旧的本 App 回放覆盖误导验证。原局部输出列表移至底部折叠的“本 App 回放设备（高级）”；录音与建链实现保持原样。界面区分系统预计/报告设备与用户实际声音，显示应用中与错误状态。CLI 与既有 API 保留原语义。

证据目录：artifacts/output-recheck-2026-10-20/，包含修改前状态、受控请求、用户听感回复、原 1.2.2 APK 与新版本构建/界面验证。受控扬声器实听成功不等于任意第三方 App、所有设备均支持。

1.2.3 验证：构建、20 项 Android 单元测试、11 项 CLI 测试通过，lint 无错误。真机实际点击首页扬声器 → 蓝牙耳机 → 跟随系统 → 扬声器，确认每一步均提交对应全局请求、清除局部回放覆盖，输入始终保持原选项。另故意设置局部 A2DP 覆盖，点击首页媒体测试音，系统报告仍走全局扬声器且未改写高级局部设置。最后将局部覆盖恢复为自动，保留用户本次确认的全局扬声器输出。
