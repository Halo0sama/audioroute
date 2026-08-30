package com.halo.audioroute

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * 声路的大脑：设备枚举、选择持久化、试音、Shizuku 全局强制路由。
 * UI 与本地控制服务器（AudioApiServer）共用这一个单例。
 */
object RouteState {

    const val PORT = 8767

    // AudioSystem 隐藏常量（值固定）
    const val FOR_COMMUNICATION = 0
    const val FOR_MEDIA = 1
    const val FOR_RECORD = 2
    const val FORCE_NONE = 0
    const val FORCE_SPEAKER = 1
    const val FORCE_HEADPHONES = 2
    const val FORCE_BT_SCO = 3
    const val FORCE_BT_A2DP = 4

    @Volatile var selectedInputKey: String? = null   // null = 系统默认
    @Volatile var selectedOutputKey: String? = null  // null / "auto" = 跟随系统；"speaker" = 扬声器；tX:aY = 指定设备

    private var appContext: Context? = null
    private val prefs: SharedPreferences?
        get() = appContext?.getSharedPreferences("audioroute", Context.MODE_PRIVATE)

    @Volatile var forceService: IAudioForceService? = null
    @Volatile var lastForceError: String = ""

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            val p = prefs
            selectedInputKey = p?.getString("selected_input", null)
            selectedOutputKey = p?.getString("selected_output", null)
        }
    }

    fun am(context: Context): AudioManager = context.getSystemService(AudioManager::class.java)

    fun key(d: AudioDeviceInfo) = "t${d.type}:a${d.address}"

    fun label(d: AudioDeviceInfo): String =
        d.productName?.toString()?.ifBlank { null } ?: typeName(d.type)

    fun typeName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "手机麦克风"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "手机扬声器"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话麦克风"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙耳机"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线带麦耳机"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB音频设备"
        else -> "音频设备"
    }

    fun inputDevices(context: Context): List<AudioDeviceInfo> = am(context).getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
        it.type in intArrayOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
    }

    fun outputDevices(context: Context): List<AudioDeviceInfo> = am(context).getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
        it.type in intArrayOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET
        )
    }

    fun selectInput(context: Context, key: String?) {
        selectedInputKey = key
        prefs?.edit()?.putString("selected_input", key)?.apply()
    }

    fun selectOutput(context: Context, key: String?) {
        selectedOutputKey = key
        prefs?.edit()?.putString("selected_output", key)?.apply()
    }

    private fun findDevice(am: AudioManager, isInput: Boolean, sel: String): AudioDeviceInfo? {
        if (sel == null || sel == "builtin" || sel == "auto") return null
        if (sel == "speaker") {
            return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        }
        if (!sel.startsWith("t") || !sel.contains(":a")) return null
        return runCatching {
            val type = sel.substringAfter('t').substringBefore(":a").toInt()
            val addr = sel.substringAfter(":a")
            val flags = if (isInput) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
            am.getDevices(flags).firstOrNull { it.type == type && it.address == addr }
        }.getOrNull()
    }

    fun resolveInput(context: Context): AudioDeviceInfo? = findDevice(am(context), true, selectedInputKey ?: "")

    fun resolveOutput(context: Context): AudioDeviceInfo? = findDevice(am(context), false, selectedOutputKey ?: "auto")

    /* ───────────── 试音 ───────────── */

    fun recordTest(context: Context): ByteArray {
        val am = am(context)
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 16000 * 4))
        runCatching { resolveInput(context)?.let { rec.preferredDevice = it } }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(6400)
        rec.startRecording()
        val end = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < end) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) out.write(buf, 0, n)
        }
        runCatching { rec.stop() }
        runCatching { rec.release() }
        return out.toByteArray()
    }

    fun playPcm(context: Context, pcm: ByteArray) {
        val wav = File(context.cacheDir, "shilu_test.wav")
        writeWav(wav, pcm, 16000)
        val p = MediaPlayer()
        runCatching { resolveOutput(context)?.let { p.preferredDevice = it } }
        p.setDataSource(wav.absolutePath)
        p.prepare()
        p.setOnCompletionListener { it.release() }
        p.start()
    }

    fun beep(context: Context) {
        val sr = 16000
        val pcm = ByteArray(sr * 2)
        for (i in 0 until sr) {
            val s = (Math.sin(2 * Math.PI * 440 * i / sr) * 12000).toInt()
            pcm[i * 2] = (s and 0xff).toByte()
            pcm[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        playPcm(context, pcm)
    }

    private fun writeWav(f: File, pcm: ByteArray, sampleRate: Int) {
        fun leInt(v: Int) = byteArrayOf(
            (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
        )
        f.outputStream().use { o ->
            o.write("RIFF".toByteArray()); o.write(leInt(pcm.size + 36))
            o.write("WAVE".toByteArray()); o.write("fmt ".toByteArray())
            o.write(leInt(16)); o.write(leInt(1)); o.write(leInt(1))
            o.write(leInt(sampleRate)); o.write(leInt(sampleRate * 2))
            o.write(leInt(2)); o.write(leInt(16))
            o.write("data".toByteArray()); o.write(leInt(pcm.size))
            o.write(pcm)
        }
    }

    /* ───────────── 全局强制路由（Shizuku） ───────────── */

    fun shizukuConnected(): Boolean = forceService != null

    fun setForceUse(usage: Int, force: Int): Pair<Boolean, String> {
        val svc = forceService ?: return false to "Shizuku 服务未连接"
        return runCatching {
            val ok = svc.setForceUse(usage, force)
            ok to svc.lastResult()
        }.getOrElse { false to "err: ${it.message}" }
    }

    fun getForceUse(usage: Int): Int? {
        val svc = forceService ?: return null
        return runCatching { svc.forceUse(usage) }.getOrNull()
    }
}
