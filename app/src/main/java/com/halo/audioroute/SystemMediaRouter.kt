package com.halo.audioroute

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject

/** Shell-only access to AudioService's strategy preference APIs (Android 12+). */
@android.annotation.TargetApi(31)
class SystemMediaRouter {
    private val serviceClass = Class.forName("android.media.IAudioService")
    private val deviceClass = Class.forName("android.media.AudioDeviceAttributes")
    private val service: Any
    private val strategyId: Int
    private val lease: MediaRouteLease
    private var owner: IBinder? = null
    private val death = IBinder.DeathRecipient {
        synchronized(this) { runCatching { restore() }.onFailure { android.util.Log.e("AudioForce", "媒体路由自动恢复失败", it) } }
    }

    init {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "audio")
        service = Class.forName("android.media.IAudioService\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val strategyClass = Class.forName("android.media.audiopolicy.AudioProductStrategy")
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        // Android 17 moved discovery out of AudioProductStrategy; AudioManager is the stable facade.
        val strategies = AudioManager::class.java.getMethod("getAudioProductStrategies").invoke(null) as List<*>
        val strategy = strategies.firstOrNull {
            strategyClass.getMethod("supportsAudioAttributes", AudioAttributes::class.java).invoke(it, attributes) == true
        } ?: error("系统未提供媒体音频策略")
        strategyId = strategyClass.getMethod("getId").invoke(strategy) as Int
        lease = MediaRouteLease(::readDevices, ::writeDevices)
    }

    private fun readDevices(): List<MediaDevice> {
        val devices = serviceClass.getMethod("getPreferredDevicesForStrategy", Int::class.javaPrimitiveType).invoke(service, strategyId) as List<*>
        return devices.map { d -> MediaDevice(deviceClass.getMethod("getType").invoke(d) as Int, deviceClass.getMethod("getAddress").invoke(d) as String) }
    }

    private fun writeDevices(devices: List<MediaDevice>) {
        val status = if (devices.isEmpty()) {
            serviceClass.getMethod("removePreferredDevicesForStrategy", Int::class.javaPrimitiveType).invoke(service, strategyId) as Int
        } else {
            val attributes = devices.map { deviceClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java).newInstance(2, it.type, it.address) }
            serviceClass.getMethod("setPreferredDevicesForStrategy", Int::class.javaPrimitiveType, List::class.java).invoke(service, strategyId, attributes) as Int
        }
        check(status == 0) { "系统媒体首选设备接口返回 $status" }
    }

    @Synchronized fun apply(type: Int, address: String, token: IBinder) {
        require(type == 2 || type == 8) { "全局媒体仅支持扬声器或蓝牙 A2DP" }
        check(availableDevices().any { it.type == type && it.address == address }) { "所请求媒体设备已断开" }
        if (owner !== token) {
            check(owner == null || restore()) { "原媒体路由已被其他程序改变" }
            token.linkToDeath(death, 0)
            owner = token
        }
        try {
            check(token.isBinderAlive) { "声路进程已退出" }
            lease.apply(listOf(MediaDevice(type, address)))
            check(token.isBinderAlive) { "声路进程已退出" }
        } catch (error: Throwable) {
            runCatching { restore() }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    @Synchronized fun restore(): Boolean {
        val restored = lease.restore()
        owner?.let { runCatching { it.unlinkToDeath(death, 0) } }
        owner = null
        return restored
    }

    @Synchronized fun recover(journal: String): Boolean {
        val state = JSONObject(journal)
        require(state.getInt("strategy_id") == strategyId) { "媒体策略编号已改变，无法自动恢复" }
        if (lease.active) return restore()
        val candidates = state.getJSONArray("owned_devices")
        return lease.recover(parseDevices(state.getJSONArray("original_devices")), (0 until candidates.length()).map { parseDevices(candidates.getJSONArray(it)) })
    }

    private fun availableDevices(): List<MediaDevice> {
        val devices = AudioManager::class.java.getMethod("getDevicesStatic", Int::class.javaPrimitiveType)
            .invoke(null, AudioManager.GET_DEVICES_OUTPUTS) as Array<*>
        return devices.filterIsInstance<AudioDeviceInfo>().map { MediaDevice(it.type, it.address) }
    }

    @Synchronized fun state(): JSONObject = JSONObject().put("supported", true).put("strategy_id", strategyId)
        .put("available_devices", devicesJson(availableDevices()))
        .put("preferred_devices", devicesJson(readDevices())).put("active", lease.active)
        .put("original_devices", lease.original?.let(::devicesJson) ?: JSONObject.NULL)
        .put("owned_devices", JSONArray().apply { lease.owned.forEach { put(devicesJson(it)) } })

    companion object {
        fun devicesJson(devices: List<MediaDevice>) = JSONArray().apply {
            devices.forEach { put(JSONObject().put("type", it.type).put("address", it.address)) }
        }
        private fun parseDevices(devices: JSONArray) = (0 until devices.length()).map {
            devices.getJSONObject(it).let { d -> MediaDevice(d.getInt("type"), d.getString("address")) }
        }
    }
}
