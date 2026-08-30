package com.halo.audioroute

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * 运行在 Shizuku（shell UID 2000）进程里的特权服务：
 * 通过反射调用隐藏 API android.media.AudioSystem.setForceUse(usage, force[, op])，
 * 在 AudioPolicyManager 层强制覆盖全局路由，影响手机上所有应用的媒体/录音/通话音频。
 */
class AudioForceService : IAudioForceService.Stub {

    private var appContext: Context? = null

    constructor(context: Context) {
        appContext = context.applicationContext
    }

    override fun setForceUse(usage: Int, force: Int): Boolean {
        // android.media.AudioSystem 是 @hide 类，编译期不可见，运行期反射
        val candidates = runCatching {
            Class.forName("android.media.AudioSystem").methods
                .filter { it.name == "setForceUse" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == Int::class.javaPrimitiveType }
        }.getOrElse { emptyList() }
        for (m in candidates) {
            try {
                val ok = if (m.parameterTypes.size >= 3) {
                    m.invoke(null, usage, force, "audioroute") as Int
                } else {
                    m.invoke(null, usage, force) as Int
                }
                Log.i(TAG, "setForceUse($usage,$force) via ${m.parameterTypes.size} args -> $ok")
                lastResultValue = "setForceUse($usage,$force)=$ok"
                return ok == 0
            } catch (e: Throwable) {
                Log.w(TAG, "setForceUse attempt failed: ${e.message}")
                lastResultValue = "err: ${e.message}"
            }
        }
        lastResultValue = "no setForceUse method found"
        return false
    }

    override fun lastResult(): String = lastResultValue

    companion object {
        const val SERVICE_ID = "audioroute_force"
        const val SERVICE_VERSION = 1
        private const val TAG = "AudioForce"
        private var lastResultValue = ""
    }
}
