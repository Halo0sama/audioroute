# 声路 CLI 与本地 API

使用 Python 3.9+ 和 `adb`；App 需正在运行。手机 API 只监听 `127.0.0.1:8767`，CLI 使用 `adb forward tcp:0 tcp:8767` 分配本机端口，复用转发时同时核对设备、来源端口和目标端口。本机 HTTP 请求显式绕过代理，适合启用 VPN/系统代理的电脑。

```bash
python3 tools/audioroute-cli.py --serial <设备号> devices
python3 tools/audioroute-cli.py --serial <设备号> status
python3 tools/audioroute-cli.py --serial <设备号> refresh
python3 tools/audioroute-cli.py --serial <设备号> select-input auto
python3 tools/audioroute-cli.py --serial <设备号> select-input builtin
python3 tools/audioroute-cli.py --serial <设备号> select-output speaker
python3 tools/audioroute-cli.py --serial <设备号> test beep
python3 tools/audioroute-cli.py --serial <设备号> test record
python3 tools/audioroute-cli.py --serial <设备号> media speaker
python3 tools/audioroute-cli.py --serial <设备号> media bt_a2dp
python3 tools/audioroute-cli.py --serial <设备号> media none
python3 tools/audioroute-cli.py --serial <设备号> record bt_sco
python3 tools/audioroute-cli.py --serial <设备号> record none
python3 tools/audioroute-cli.py --serial <设备号> call on
python3 tools/audioroute-cli.py --serial <设备号> call off
```

`select-input` / `select-output` 也接受 `devices` 返回的设备 key。Android 8.0/8.1 无设备地址 API，key 使用当次系统设备 ID，重启后可能需要重选。只更新指定方向，另一个选择保持原值。`auto` / JSON `null` 恢复系统默认；`builtin` 明确请求本机麦克风。全局路由需 Shizuku；通话请求可能被通话 App 覆盖。`select-output` 只影响声路的测试音/录音回放，其他 App 的音乐需使用 `media speaker|bt_a2dp|none`。首页“声音输出”已改成全局媒体切换，并同步清除本 App 回放覆盖；CLI 的局部与全局命令保留独立语义。

## API

所有响应为 JSON，错误包含 `ok:false` 与 `error` 或 `message`。写请求需要 Content-Length，请求体最大 8192 字节。错误方法返回 405、无效参数 400、录音未授权 403、试音/策略忙 409、Shizuku 未连接/调用失败 503。

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| GET | `/api/health` | App 版本、版本号 |
| GET | `/api/devices` | 输入输出设备（key/name/type/type_name/id）与选择 |
| GET | `/api/status` | 连接、策略缓存、选择、通话请求、试音结果 |
| POST | `/api/select` | `{"input":"builtin"}` 或 `{"output":"speaker"}`，可同时提供；先校验全部字段再保存 |
| POST | `/api/test` | `{"kind":"beep"}` 或 `{"kind":"record"}`；返回 202 后轮询 status 的 `audio_test` |
| POST | `/api/global` | `{"media":"none|speaker|bt_a2dp","record":"none|bt_sco"}`，至少提供一项 |
| POST | `/api/refresh` | `{}`；主动读取系统策略 |
| POST | `/api/call` | `{"speaker":true}` 或 `false` |

`media_strategy` 是 Android 12+ 系统媒体首选设备读回，包含 `strategy_id/available_devices/preferred_devices/active/original_devices/owned_devices`。`active` 表示声路正在管理本次媒体会话。`media none` 还原原首选设备，声路进程死亡时也会尝试还原；用户服务被强制终止时，下次连接通过本地记录恢复。若其他程序后来改动了媒体设备，恢复操作会保留该改动。

`force` 是旧 force-use 接口的系统读回缓存，Android 12+ 媒体切换改用 `media_strategy`，因此媒体实际切到扬声器时 `force.media` 仍可为 0。`force_checked_at` 为读回时的 Unix 毫秒数；未知值为 null。`status` 本身不调用音频/Shizuku Binder，特权调用挂起时仍能返回。`requested_media_force` 保存最近一次尝试的媒体请求，与 `force.media` 的系统读回分开。多字段请求在全部操作后再次核对，若系统覆盖了先前设置，会返回失败。设置与刷新最多等待 8 秒，超时会清空连接和策略缓存；请在手机上重新连接并刷新。超时请求可能已经改变系统状态，应先查询，避免盲目重试。

`/api/global` 会在修改前验证全部参数。若请求同时包含媒体和录音，两项系统修改按顺序执行，**不构成系统级原子事务**；部分成功时应以响应中系统读回的 `force` 为准。Android 13+ 还使用音频属性查询预计媒体设备；若预计设备与请求不符，设置返回 503，并保留策略读回信息。`media_route` 与 `media_route_source` 明确标记这是预计值。即使返回 `ok:true`，策略与预计设备均一致，也仍需试音：厂商播放器的实际路由可能与预计值不同。

`audio_test` 包含 `running/kind/ok/message/routed_input/routed_output/routed_outputs/recorded_bytes`。Android 16+ 读取完整播放器输出列表，旧系统保留单设备结果；播放期间持续采样。实际设备来自录音器和 AudioTrack 的 `routedDevice(s)`；播放未报告设备或未匹配显式选择时明确失败；`ok:true` 表示录放流程结束，不表示已确认听感、音质或跨 App 行为。每次最多执行一个试音，选择快照在开始时固定。自动输出试音若与最近媒体请求不符，会返回试音失败并更新诊断信息。蓝牙麦/蓝牙通话输出试音临时请求通信模式和对应设备，结束后撤销请求、恢复模式。蓝牙麦录完再使用所选输出回放；已有通信会话时拒绝启动蓝牙通话设备试音。试音期间通话请求返回 409。

## 验证

```bash
python3 -m unittest discover -s tools/tests -v
python3 tools/device-smoke.py --serial <设备号> --global --record --restart \
  --report artifacts/device-smoke.json
```

设备回归会临时改选输入输出、播放测试音；`--record` 会录音 4 秒并回放，需预先授权；`--global` 会设置扬声器策略。`--restart` 在保存选择后立即终止并重启本 App，验证持久化。结束后恢复原选择和媒体策略。报告位于忽略的 `artifacts/` 目录，不提交设备信息和原 APK。

## 蓝牙实测（1.2.1）

SIMGOT OnClip ONE / Android 17 上，手机麦与蓝牙 SCO 麦分别录音，再经 A2DP 回放均有设备路由证据。当前连接使用 LDAC；SCO 是另一条通话音频链路，不能从 LDAC 状态推导录音链路。

扬声器全局策略出现“刚读回 force=1、预计扬声器，随后被系统恢复为自动，MediaPlayer 实际仍走 A2DP”的差异；应查看试音实测结果，不能将策略读回或预计设备当成第三方 App 已切换的证明。

## 输出回归（1.2.2）

```bash
python3 tools/output-smoke.py --serial <设备号> --global \
  --record-input builtin --record-input <蓝牙麦设备key> \
  --report artifacts/output-smoke.json
```

会对每个连接输出播放测试音，并为每个 `--record-input` 组合录音回放，核对完整实际路由；`--global` 另测试默认输出随扬声器/A2DP/恢复自动切换。录音需预先授权。结束后恢复选择及媒体设置，不修改录音权限。

1.2.2 的本 App 播放使用 AudioTrack 取代 MediaPlayer，修复蓝牙连接时显式选扬声器仍走 A2DP；全局媒体使用媒体首选设备接口，修复上文 1.2.1 的已知失败路径。测试证据见 `DEVELOPMENT.md`。

## 首页输出操作（1.2.3）

首页“声音输出”现在直接应用全局媒体请求，并在成功后把本 App 的回放恢复为跟随系统；不再把局部设备选择放在主输出入口。输入选择保持不变。首页的媒体测试音忽略高级局部回放覆盖，以验证当前全局媒体路径。原局部设备列表位于底部折叠的“本 App 回放设备（高级）”。CLI `select-output` 和 `media` 保留原来的独立语义。
