package com.halo.audioroute

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

object RouteState {
    const val PORT = 8767
    const val FOR_MEDIA = 1
    const val FOR_RECORD = 2
    const val FORCE_NONE = 0
    const val FORCE_SPEAKER = 1
    const val FORCE_BT_SCO = 3
    const val FORCE_BT_A2DP = 4
    const val SHIZUKU_REQ = 7001

    data class Selection(val input: String? = null, val output: String? = null)
    data class MediaRouteDevice(val key: String, val name: String, val type: Int)
    data class ForceState(val values: Map<Int, Int> = emptyMap(), val checkedAt: Long? = null, val mediaDevices: List<MediaRouteDevice>? = null, val mediaStrategy: JSONObject? = null)
    data class ForceResult(val ok: Boolean, val message: String)
    data class TestState(
        val running: Boolean = false, val kind: String = "", val ok: Boolean? = null,
        val message: String = "", val input: String? = null, val output: String? = null,
        val recordedBytes: Int = 0, val outputs: List<String> = emptyList()
    )

    var selection by mutableStateOf(Selection()); private set
    val selectedInputKey get() = selection.input
    val selectedOutputKey get() = selection.output
    var inputs by mutableStateOf(emptyList<AudioDeviceInfo>()); private set
    var outputs by mutableStateOf(emptyList<AudioDeviceInfo>()); private set
    val connectedState = mutableStateOf(false)
    var bindError by mutableStateOf(""); private set
    var lastForceResult by mutableStateOf(""); private set
    var lastForceOk by mutableStateOf<Boolean?>(null); private set
    var forceState by mutableStateOf(ForceState()); private set
    var testState by mutableStateOf(TestState()); private set
    var callSpeakerRequested by mutableStateOf(false); private set
    var callResult by mutableStateOf(""); private set
    var requestedMediaForce by mutableStateOf<Int?>(null); private set

    private lateinit var appContext: Context
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binderExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "AudioRoute-binder").apply { isDaemon = true } }
    private val binderBusy = AtomicBoolean(false)
    private val testBusy = AtomicBoolean(false)
    private var initialized = false
    @Volatile private var forceService: IAudioForceService? = null
    @Volatile private var binding = false
    private val bindGeneration = AtomicInteger()
    private val serviceLock = Any()
    private val mediaOwner = Binder()
    @Volatile private var recoveredMediaService: IAudioForceService? = null
    private const val MEDIA_JOURNAL = "media_route_recovery"
    val forceBusy get() = binderBusy.get()
    private fun serviceArgs() = Shizuku.UserServiceArgs(ComponentName(appContext, AudioForceService::class.java))
        .processNameSuffix("audioroute").version(BuildConfig.VERSION_CODE)

    private var serviceConnection: ServiceConnection? = null
    private fun newConnection(attempt: Int) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(serviceLock) {
                if (attempt != bindGeneration.get()) return
                binding = false
                if (binder?.isBinderAlive != true) {
                    disconnect("Shizuku 用户服务未返回有效 Binder")
                    return
                }
                forceService = IAudioForceService.Stub.asInterface(binder)
                connectedState.value = true
                forceState = ForceState()
                bindError = ""
            }
            scope.launch { refreshForceState() }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(serviceLock) {
                if (attempt == bindGeneration.get()) disconnect("Shizuku 用户服务已断开，请重新连接")
            }
        }
    }

    private fun disconnect(message: String) = synchronized(serviceLock) {
        bindGeneration.incrementAndGet()
        forceService = null
        binding = false
        connectedState.value = false
        forceState = ForceState()
        lastForceOk = null
        lastForceResult = ""
        requestedMediaForce = null
        bindError = message
    }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("audioroute", Context.MODE_PRIVATE)
        selection = Selection(prefs.getString("selected_input", null), prefs.getString("selected_output", null))
        refreshDevices()
        am(appContext).registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { devicesChanged() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { devicesChanged() }
        }, handler)
        initialized = true
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code == SHIZUKU_REQ) {
                if (result == PackageManager.PERMISSION_GRANTED) bindShizuku(appContext)
                else disconnect("未获得 Shizuku 授权")
            }
        }
        Shizuku.addBinderDeadListener { disconnect("Shizuku 已停止，请启动后重新连接") }
        Shizuku.addBinderReceivedListenerSticky { bindShizuku(appContext) }
    }

    fun bindShizuku(context: Context, reconnect: Boolean = false) {
        init(context)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { bindShizuku(appContext, reconnect) }
            return
        }
        if (!reconnect && (forceService != null || binding)) return
        runCatching {
            check(Shizuku.pingBinder()) { "Shizuku 未启动" }
            check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "请先授予 Shizuku 权限" }
            // Detach the previous callback before replacing the remote process. Its late death
            // event must never clear a newly connected Binder.
            val previous = serviceConnection
            disconnect("")
            if (previous != null) runCatching { Shizuku.unbindUserService(serviceArgs(), previous, false) }
            if (reconnect) Shizuku.unbindUserService(serviceArgs(), null, true)
            val attempt = bindGeneration.incrementAndGet()
            val connection = newConnection(attempt)
            serviceConnection = connection
            binding = true
            bindError = "正在连接…"
            Shizuku.bindUserService(serviceArgs(), connection)
            handler.postDelayed({
                if (binding && attempt == bindGeneration.get()) disconnect("连接超时，请点击重新连接")
            }, 8000)
        }.onFailure { disconnect(it.message ?: "Shizuku 连接失败") }
    }

    fun requestShizuku(context: Context) {
        runCatching {
            check(Shizuku.pingBinder()) { "请先启动 Shizuku" }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindShizuku(context, reconnect = true)
            else Shizuku.requestPermission(SHIZUKU_REQ)
        }.onFailure { bindError = it.message ?: "授权失败" }
    }

    fun am(context: Context): AudioManager = context.getSystemService(AudioManager::class.java)
    fun key(d: AudioDeviceInfo) = if (Build.VERSION.SDK_INT >= 28) "t${d.type}:a${d.address}" else "t${d.type}:i${d.id}"
    fun label(d: AudioDeviceInfo) = d.productName?.toString()?.ifBlank { null } ?: typeName(d.type)
    fun displayLabel(d: AudioDeviceInfo): String = buildString {
        append(label(d)); append(" · "); append(typeName(d.type))
        if (Build.VERSION.SDK_INT >= 28 && d.address.isNotBlank() && d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) append(" (${d.address})")
    }
    fun routedLabel(deviceKey: String?): String = (inputs + outputs).firstOrNull { key(it) == deviceKey }
        ?.let(::displayLabel) ?: "设备已断开或系统未报告"
    fun typeName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "手机麦克风"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "手机扬声器"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话设备"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙媒体设备"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙 LE 耳机"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "蓝牙 LE 扬声器"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "蓝牙广播"
        AudioDeviceInfo.TYPE_HEARING_AID -> "助听器"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线带麦耳机"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 音频设备"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI 音频"
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "线路音频"
        else -> "音频设备 ($t)"
    }

    private fun refreshDevices() {
        val supported = mutableSetOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE
        )
        if (Build.VERSION.SDK_INT >= 28) supported += AudioDeviceInfo.TYPE_HEARING_AID
        if (Build.VERSION.SDK_INT >= 30) supported += AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
        if (Build.VERSION.SDK_INT >= 31) supported += setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HDMI_EARC)
        if (Build.VERSION.SDK_INT >= 33) supported += AudioDeviceInfo.TYPE_BLE_BROADCAST
        inputs = am(appContext).getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in supported }
        outputs = am(appContext).getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in supported }
    }
    private fun devicesChanged() {
        refreshDevices()
        forceState = ForceState()
        if (forceService != null) scope.launch { refreshForceState() }
    }
    fun inputDevices(context: Context): List<AudioDeviceInfo> { init(context); return inputs }
    fun outputDevices(context: Context): List<AudioDeviceInfo> { init(context); return outputs }

    fun validateSelection(isInput: Boolean, value: String?) {
        if (value == null || value == "auto") return
        if (isInput && value == "builtin") return
        if (!isInput && value == "speaker") return
        val devices = if (isInput) inputs else outputs
        require(devices.any { key(it) == value }) { "设备不存在或已断开：$value" }
    }

    @Synchronized
    fun select(context: Context, inputPresent: Boolean, input: String?, outputPresent: Boolean, output: String?) {
        init(context)
        if (inputPresent) validateSelection(true, input)
        if (outputPresent) validateSelection(false, output)
        val next = Selection(
            if (inputPresent) input?.takeUnless { it == "auto" } else selection.input,
            if (outputPresent) output?.takeUnless { it == "auto" } else selection.output
        )
        // The HTTP reply must not acknowledge a choice that a process kill can still lose.
        check(appContext.getSharedPreferences("audioroute", Context.MODE_PRIVATE).edit()
            .putString("selected_input", next.input).putString("selected_output", next.output).commit()) {
            "保存设备选择失败，请重试"
        }
        selection = next
    }
    fun selectInput(context: Context, value: String?) = select(context, true, value, false, null)
    fun selectOutput(context: Context, value: String?) = select(context, false, null, true, value)

    private fun resolve(isInput: Boolean, selected: String?): AudioDeviceInfo? {
        if (selected == null || selected == "auto") return null
        val flag = if (isInput) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
        val devices = am(appContext).getDevices(flag)
        val device = when (selected) {
            "builtin" -> devices.firstOrNull { isInput && it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            "speaker" -> devices.firstOrNull { !isInput && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            else -> devices.firstOrNull { key(it) == selected }
        }
        return checkNotNull(device) { "选中设备已断开，请重新选择或跟随系统：$selected" }
    }

    fun shizukuConnected() = forceService != null && connectedState.value
    fun getForceUse(usage: Int) = forceState.values[usage]

    private fun publishForceResult(result: ForceResult): ForceResult {
        lastForceResult = result.message
        lastForceOk = result.ok
        return result
    }

    /** At most one Binder task can exist, including one still stuck after its timeout. */
    private fun serviceCall(requests: Map<Int, Int>): ForceResult {
        val service = forceService ?: return publishForceResult(ForceResult(false, "Shizuku 服务未连接"))
        if (!binderBusy.compareAndSet(false, true)) return publishForceResult(ForceResult(false, "路由查询或设置正在执行，请稍后重试"))
        requests[FOR_MEDIA]?.let { requestedMediaForce = it }
        val future = binderExecutor.submit<Pair<ForceResult, ForceState>> {
            try {
                val messages = mutableListOf<String>()
                var ok = true
                var strategy = JSONObject(service.mediaStrategyState())
                val modernMedia = Build.VERSION.SDK_INT >= 31
                val prefs = appContext.getSharedPreferences("audioroute", Context.MODE_PRIVATE)
                if (modernMedia && recoveredMediaService !== service) {
                    prefs.getString(MEDIA_JOURNAL, null)?.let { journal ->
                        val recovered = service.recoverMediaDevice(journal)
                        messages += service.lastResult()
                        strategy = JSONObject(service.mediaStrategyState())
                        check(recovered) { "上次媒体设置仍未恢复：${service.lastResult()}" }
                        check(prefs.edit().remove(MEDIA_JOURNAL).commit()) { "无法清除媒体恢复记录" }
                    }
                    recoveredMediaService = service
                }
                for ((usage, force) in requests) {
                    if (usage == FOR_MEDIA && modernMedia) {
                        check(strategy.optBoolean("supported")) { strategy.optString("error", "系统不支持媒体首选设备") }
                        val accepted = if (force == FORCE_NONE) {
                            service.restoreMediaDevice()
                        } else {
                            val type = if (force == FORCE_SPEAKER) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            // App-side Bluetooth addresses may be privacy-masked; use the shell's actual audio port addresses.
                            val available = strategy.getJSONArray("available_devices")
                            val devices = (0 until available.length()).map { available.getJSONObject(it) }.filter { it.getInt("type") == type }
                            check(devices.isNotEmpty()) { "所请求媒体设备未连接：${typeName(type)}" }
                            check(devices.size == 1) { "存在多个同类媒体设备，请先在系统中选择目标设备" }
                            val device = devices.single()
                            val target = JSONArray().put(device)
                            val owned = if (strategy.optBoolean("active")) JSONArray(strategy.getJSONArray("owned_devices").toString()) else JSONArray()
                            owned.put(target)
                            val journal = JSONObject().put("strategy_id", strategy.getInt("strategy_id"))
                                .put("original_devices", if (strategy.optBoolean("active")) strategy.getJSONArray("original_devices") else strategy.getJSONArray("preferred_devices"))
                                .put("owned_devices", owned)
                            check(prefs.edit().putString(MEDIA_JOURNAL, journal.toString()).commit()) { "无法保存媒体恢复记录；未修改系统" }
                            service.setMediaDevice(type, device.getString("address"), mediaOwner)
                        }
                        messages += service.lastResult()
                        ok = ok && accepted
                        strategy = JSONObject(service.mediaStrategyState())
                        if (!strategy.optBoolean("active")) check(prefs.edit().remove(MEDIA_JOURNAL).commit()) { "无法清除媒体恢复记录" }
                    } else {
                        val accepted = service.setForceUse(usage, force)
                        val detail = service.lastResult()
                        val readBack = service.forceUse(usage)
                        ok = ok && accepted && readBack == force
                        messages += "${if (usage == FOR_MEDIA) "媒体" else "录音"}：请求 $force，系统读回 $readBack；$detail"
                        if (usage == FOR_RECORD && force == FORCE_BT_SCO) {
                            messages += "录音策略读回不代表蓝牙音频已建链；其他 App 仍需自行建立通话音频并确认麦克风路由"
                        }
                    }
                }
                if (requests.isNotEmpty()) Thread.sleep(200)
                if (requests.isEmpty()) messages += "已读取系统音频策略（实际音频设备请以试音为准）"
                var mediaDevices = observeMediaRoute()
                val requestedMedia = requests[FOR_MEDIA]
                val deadline = SystemClock.elapsedRealtime() + 1000
                while (MediaRouteVerification.matches(requestedMedia, mediaDevices?.map { it.type }) == false && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(50)
                    mediaDevices = observeMediaRoute()
                }
                val actual = mapOf(FOR_MEDIA to service.forceUse(FOR_MEDIA), FOR_RECORD to service.forceUse(FOR_RECORD))
                val legacyRequests = requests.filterKeys { it != FOR_MEDIA || !modernMedia }
                if (!MediaRouteVerification.policyMatches(legacyRequests, actual)) {
                    ok = false
                    messages += "系统随后覆盖了请求的 force 策略，最终读回：媒体 ${actual[FOR_MEDIA]}、录音 ${actual[FOR_RECORD]}"
                }
                strategy = JSONObject(service.mediaStrategyState())
                if (modernMedia && requestedMedia != null && requestedMedia != FORCE_NONE) {
                    val preferred = strategy.optJSONArray("preferred_devices")
                    val types = preferred?.let { (0 until it.length()).map { index -> it.getJSONObject(index).getInt("type") } }
                    if (!strategy.optBoolean("active") || MediaRouteVerification.matches(requestedMedia, types) != true) {
                        ok = false
                        messages += "系统未保留请求的媒体首选设备"
                    }
                }
                if (MediaRouteVerification.matches(requestedMedia, mediaDevices?.map { it.type }) == false) {
                    ok = false
                    messages += "系统预计输出仍为：${mediaDevices?.joinToString { it.name }?.ifBlank { "未知" }}；未确认按请求切换"
                }
                ForceResult(ok, messages.joinToString("\n")) to ForceState(actual, System.currentTimeMillis(), mediaDevices, strategy)
            } finally {
                binderBusy.set(false)
            }
        }
        val result = try {
            val (outcome, observed) = future.get(8, TimeUnit.SECONDS)
            synchronized(serviceLock) {
                if (forceService === service) {
                    forceState = observed
                    outcome
                } else ForceResult(false, "连接已变化，结果未知，请重新刷新系统状态")
            }
        } catch (e: TimeoutException) {
            synchronized(serviceLock) {
                if (forceService === service) disconnect("Shizuku 调用超时，请重新连接")
            }
            ForceResult(false, "调用超时，结果未知；请重新连接后读取系统状态")
        } catch (e: Exception) {
            synchronized(serviceLock) {
                if (forceService === service) disconnect("Shizuku 调用失败，请重新连接")
            }
            ForceResult(false, e.cause?.message ?: e.message ?: "路由调用失败")
        }
        return publishForceResult(result)
    }
    private fun observeMediaRoute(): List<MediaRouteDevice>? {
        if (Build.VERSION.SDK_INT < 33) return null
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        return runCatching {
            am(appContext).getAudioDevicesForAttributes(attributes).map { MediaRouteDevice(key(it), displayLabel(it), it.type) }
        }.getOrNull()
    }

    fun refreshForceState() = serviceCall(emptyMap())
    fun setForceUse(usage: Int, force: Int) = setForces(mapOf(usage to force))
    fun setForces(requests: Map<Int, Int>): ForceResult {
        require(requests.isNotEmpty() && requests.all { AudioForceService.validForce(it.key, it.value) }) { "无效全局路由参数" }
        return serviceCall(requests)
    }

    /** Main UI selection applies the system route and lets our own playback follow it. */
    fun chooseMediaOutput(context: Context, force: Int): ForceResult {
        val result = setForceUse(FOR_MEDIA, force)
        if (!result.ok) return result
        return runCatching {
            selectOutput(context, null)
            result
        }.getOrElse { publishForceResult(ForceResult(false, "系统媒体请求已提交，但未能同步本 App 回放选择：${it.message}")) }
    }

    fun setCallSpeaker(context: Context, enabled: Boolean): ForceResult {
        return runCatching {
            val manager = am(context)
            if (Build.VERSION.SDK_INT >= 31) {
                if (enabled) {
                    val speaker = manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    check(speaker != null && manager.setCommunicationDevice(speaker)) { "系统未接受通话扬声器请求" }
                } else manager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = enabled
            }
            callSpeakerRequested = enabled
            ForceResult(true, if (enabled) "已提交通话扬声器请求；通话 App 或系统可能覆盖" else "已撤销本 App 的通话设备请求")
        }.getOrElse { ForceResult(false, it.message ?: "通话路由请求失败") }.also { callResult = it.message }
    }

    fun startTest(context: Context, kind: String, followMediaOutput: Boolean = false): Boolean {
        require(kind == "beep" || kind == "record") { "kind 必须是 beep 或 record" }
        if (!testBusy.compareAndSet(false, true)) return false
        val selected = if (followMediaOutput) selection.copy(output = null) else selection
        testState = TestState(running = true, kind = kind, message = if (kind == "record") "录音中 4 秒…" else "正在播放测试音…")
        scope.launch {
            try {
                val pcm = if (kind == "record") recordTest(context, selected.input) else beepPcm()
                testState = testState.copy(message = "正在回放…")
                playPcm(context, pcm, selected.output)
                testState = testState.copy(running = false, ok = true, message = "试音播放结束；请结合实际听感确认")
            } catch (e: Exception) {
                testState = testState.copy(running = false, ok = false, message = e.message ?: "试音失败")
            } finally { testBusy.set(false) }
        }
        return true
    }

    private fun recordTest(context: Context, selected: String?): ByteArray {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "未授予录音权限，请在手机上授权" }
        val device = resolve(true, selected)
        val bluetooth = usesBluetoothCommunication(device)
        return withBluetoothRoute(context, device, "蓝牙麦克风录音中 4 秒…") {
            recordPcm(device, if (bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC)
        }
    }

    private fun usesBluetoothCommunication(device: AudioDeviceInfo?) =
        device?.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)

    /** AudioRecord/AudioTrack preferences alone do not establish Bluetooth communication audio. */
    @Suppress("DEPRECATION")
    private fun <T> withBluetoothRoute(context: Context, device: AudioDeviceInfo?, activeMessage: String, action: () -> T): T {
        if (!usesBluetoothCommunication(device)) return action()
        val manager = am(context)
        val previousMode = manager.mode
        check(previousMode == AudioManager.MODE_NORMAL) { "当前有通话或通信会话，请结束后再进行蓝牙通话设备试音" }
        val restoreSpeaker = callSpeakerRequested
        val connected = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED) connected.countDown()
            }
        }
        var receiverRegistered = false
        var modeChanged = false
        var requested = false
        try {
            testState = testState.copy(message = "正在连接蓝牙通话音频…")
            if (Build.VERSION.SDK_INT < 31) {
                ContextCompat.registerReceiver(context, receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_EXPORTED)
                receiverRegistered = true
            }
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            modeChanged = true
            requested = true
            if (Build.VERSION.SDK_INT >= 31) {
                val target = manager.availableCommunicationDevices.firstOrNull { key(it) == key(device!!) }
                check(target != null && manager.setCommunicationDevice(target)) { "系统未接受蓝牙通话设备请求" }
                val deadline = SystemClock.elapsedRealtime() + 6000
                while (manager.communicationDevice?.id != target.id) {
                    check(SystemClock.elapsedRealtime() < deadline) { "蓝牙通话音频连接超时" }
                    Thread.sleep(25)
                }
            } else {
                manager.startBluetoothSco()
                check(connected.await(6, TimeUnit.SECONDS)) { "蓝牙 SCO 连接超时" }
                manager.isBluetoothScoOn = true
            }
            testState = testState.copy(message = activeMessage)
            return action()
        } finally {
            var cleanupError: Throwable? = null
            fun restore(action: () -> Unit) { runCatching(action).onFailure { cleanupError = it } }
            if (requested) restore {
                if (Build.VERSION.SDK_INT >= 31) {
                    val speaker = if (restoreSpeaker) manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } else null
                    if (speaker != null) check(manager.setCommunicationDevice(speaker)) else manager.clearCommunicationDevice()
                } else {
                    manager.isBluetoothScoOn = false
                    manager.stopBluetoothSco()
                }
            }
            if (modeChanged) restore { manager.mode = previousMode }
            if (receiverRegistered) restore { context.unregisterReceiver(receiver) }
            if (Build.VERSION.SDK_INT >= 31 && requested) {
                // Wait for HFP to release before replaying through the independent media path.
                val deadline = SystemClock.elapsedRealtime() + 3000
                while (manager.communicationDevice?.type == device?.type && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            }
            check(cleanupError == null) { "恢复蓝牙试音前的音频状态失败：${cleanupError?.message}" }
        }
    }

    @Suppress("MissingPermission")
    private fun recordPcm(device: AudioDeviceInfo?, source: Int): ByteArray {
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuf > 0) { "设备不支持 16kHz 单声道录音：$minBuf" }
        val recorder = AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 16000))
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "录音初始化失败" }
            if (device != null) check(recorder.setPreferredDevice(device)) { "系统拒绝了录音设备选择" }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(6400)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "录音启动失败" }
            val deadline = SystemClock.elapsedRealtime() + 6000
            while (out.size() < 16000 * 2 * 4) {
                check(SystemClock.elapsedRealtime() < deadline) { "录音读取超时" }
                val n = recorder.read(buffer, 0, minOf(buffer.size, 16000 * 2 * 4 - out.size()), AudioRecord.READ_NON_BLOCKING)
                check(n >= 0) { "录音读取失败：$n" }
                if (n > 0) out.write(buffer, 0, n) else Thread.sleep(10)
            }
            val routed = recorder.routedDevice
            testState = testState.copy(input = routed?.let(::key), recordedBytes = out.size())
            check(device == null || routed == null || key(device) == key(routed)) { "系统未使用所选录音设备，请检查实际路由" }
            return out.toByteArray()
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /** PCM playback must bind its AudioTrack to the device before starting.
     * MediaPlayer's accepted preference still routed to A2DP on the tested phone. */
    private fun playPcm(context: Context, pcm: ByteArray, selected: String?) {
        val device = resolve(false, selected)
        withBluetoothRoute(context, device, "正在经蓝牙通话设备播放…") {
            playTrack(pcm, device)
        }
    }

    private fun playTrack(pcm: ByteArray, device: AudioDeviceInfo?) {
        val format = AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val minimum = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "设备不支持测试音格式" }
        val communication = usesBluetoothCommunication(device)
        val attributes = AudioAttributes.Builder()
            .setUsage(if (communication) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(if (communication) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val track = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(format).setBufferSizeInBytes(max(minimum, 6400)).setTransferMode(AudioTrack.MODE_STREAM).build()
        val mediaForce = requestedMediaForce ?: forceState.values[FOR_MEDIA]
        var routed = emptyList<AudioDeviceInfo>()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "播放初始化失败" }
            if (device != null) check(track.setPreferredDevice(device)) { "系统拒绝了播放设备选择" }
            track.play()
            val deadline = SystemClock.elapsedRealtime() + 10000
            var offset = 0
            while (offset < pcm.size || track.playbackHeadPosition < pcm.size / 2) {
                check(SystemClock.elapsedRealtime() < deadline) { "播放超时" }
                if (offset < pcm.size) {
                    val n = track.write(pcm, offset, minOf(6400, pcm.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                    check(n >= 0) { "播放写入失败：$n" }
                    offset += n
                }
                val current = if (Build.VERSION.SDK_INT >= 36) track.routedDevices else listOfNotNull(track.routedDevice)
                if (current.isNotEmpty()) {
                    routed = current
                    testState = testState.copy(output = routed.firstOrNull()?.let(::key), outputs = routed.map(::key))
                }
                Thread.sleep(10)
            }
            check(MediaRouteVerification.matchesDevice(device?.let(::key), routed.map(::key))) {
                "所选输出：${device?.let(::displayLabel) ?: "跟随系统"}；实际输出：${routed.joinToString { displayLabel(it) }.ifEmpty { "系统未报告" }}，未按请求播放"
            }
            if (device == null && MediaRouteVerification.matches(mediaForce, routed.map { it.type }) == false) {
                val message = "试音实际输出为 ${routed.joinToString { displayLabel(it) }}，未匹配最近的媒体请求；系统可能已覆盖策略"
                publishForceResult(ForceResult(false, message))
                error(message)
            }
        } finally { runCatching { track.stop() }; track.release() }
    }

    private fun beepPcm(): ByteArray = ByteArray(16000 * 2).also { pcm ->
        for (i in 0 until 16000) {
            val sample = (kotlin.math.sin(2 * Math.PI * 440 * i / 16000) * 6000).toInt()
            pcm[i * 2] = sample.toByte(); pcm[i * 2 + 1] = (sample shr 8).toByte()
        }
    }
}
