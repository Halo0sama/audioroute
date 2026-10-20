package com.halo.audioroute

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException

/** Runs in Shizuku's shell process. A successful API return is not proof of audible routing. */
class AudioForceService(@Suppress("UNUSED_PARAMETER") context: Context) : IAudioForceService.Stub() {
    private var result = ""

    @Synchronized
    override fun setForceUse(usage: Int, force: Int): Boolean {
        if (!validForce(usage, force)) {
            result = "不支持的路由参数：usage=$usage, force=$force"
            return false
        }
        val candidates = Class.forName("android.media.AudioSystem").methods.filter {
            it.name == "setForceUse" &&
                (it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)) ||
                 it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)))
        }
        if (candidates.isEmpty()) result = "系统不提供 AudioSystem.setForceUse"
        for (method in candidates) {
            try {
                val status = if (method.parameterTypes.size == 3) {
                    method.invoke(null, usage, force, "audioroute") as Int
                } else {
                    method.invoke(null, usage, force) as Int
                }
                result = "setForceUse($usage,$force)=$status"
                Log.i("AudioForce", result)
                return status == 0
            } catch (e: Exception) {
                val cause = (e as? InvocationTargetException)?.targetException ?: e
                result = "${cause.javaClass.simpleName}: ${cause.message}"
                Log.w("AudioForce", result)
            }
        }
        return false
    }

    override fun forceUse(usage: Int): Int {
        require(usage == RouteState.FOR_MEDIA || usage == RouteState.FOR_RECORD)
        return Class.forName("android.media.AudioSystem")
            .getMethod("getForceUse", Int::class.javaPrimitiveType).invoke(null, usage) as Int
    }

    @Synchronized
    override fun lastResult() = result

    private val mediaRouter by lazy { SystemMediaRouter() }

    @Synchronized override fun mediaStrategyState(): String =
        if (android.os.Build.VERSION.SDK_INT >= 31) mediaRouter.state().toString()
        else org.json.JSONObject().put("supported", false).put("active", false).toString()

    @Synchronized override fun setMediaDevice(type: Int, address: String, owner: android.os.IBinder): Boolean = mediaOperation {
        mediaRouter.apply(type, address, owner)
        "已设置媒体首选设备：type=$type"
    }

    @Synchronized override fun restoreMediaDevice(): Boolean = mediaOperation {
        check(mediaRouter.restore()) { "其他程序已更改媒体首选设备，已停止管理并保留其设置" }
        "已恢复原媒体首选设备"
    }

    @Synchronized override fun recoverMediaDevice(journal: String): Boolean = mediaOperation {
        if (mediaRouter.recover(journal)) "已恢复上次会话的媒体首选设备"
        else "媒体首选设备已被其他程序改变，已停止管理并保留其设置"
    }

    private fun mediaOperation(action: () -> String): Boolean = try {
        result = action()
        true
    } catch (e: Exception) {
        val cause = (e as? InvocationTargetException)?.targetException ?: e
        result = "${cause.javaClass.simpleName}: ${cause.message}"
        Log.w("AudioForce", result, e)
        false
    }

    override fun destroy() {
        if (android.os.Build.VERSION.SDK_INT >= 31) runCatching { mediaRouter.restore() }
        kotlin.system.exitProcess(0)
    }

    companion object {
        fun validForce(usage: Int, force: Int) = when (usage) {
            RouteState.FOR_MEDIA -> force in setOf(RouteState.FORCE_NONE, RouteState.FORCE_SPEAKER, RouteState.FORCE_BT_A2DP)
            RouteState.FOR_RECORD -> force in setOf(RouteState.FORCE_NONE, RouteState.FORCE_BT_SCO)
            else -> false
        }
    }
}
