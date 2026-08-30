package com.halo.audioroute

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import android.content.ServiceConnection
import android.os.IBinder

class MainActivity : ComponentActivity() {

    companion object {
        // AudioSystem 常量（隐藏 API，值固定）
        const val FOR_COMMUNICATION = 0
        const val FOR_MEDIA = 1
        const val FOR_RECORD = 2
        const val FORCE_NONE = 0
        const val FORCE_SPEAKER = 1
        const val FORCE_HEADPHONES = 2
        const val FORCE_BT_SCO = 3
        const val FORCE_BT_A2DP = 4
        private const val SHIZUKU_REQ = 7001
    }

    private var forceService: IAudioForceService? = null
    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                forceService = IAudioForceService.Stub.asInterface(ShizukuBinderWrapper(binder))
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            forceService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQ && grantResult == PackageManager.PERMISSION_GRANTED) {
                bindForceService()
            }
        }
        setContent { AppUi() }
    }

    override fun onResume() {
        super.onResume()
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindForceService()
        }
    }

    private fun bindForceService(onError: (String) -> Unit = {}) {
        val args = Shizuku.UserServiceArgs(ComponentName(this, AudioForceService::class.java))
            .processNameSuffix("audioroute")
            .version(1)
        runCatching {
            Shizuku.bindUserService(args, serviceConn)
        }.onFailure {
            android.util.Log.e("AudioForce", "bindUserService failed", it)
            onError("绑定失败：${it.message}")
        }
    }

    private fun requestShizuku(setStatus: (String) -> Unit = {}) {
        // pingBinder=false 时也要走 requestPermission：它内部会经 provider 重新获取 server binder
        // （server 后启动的场景，进程缓存的失败 binder 必须由 requestPermission 刷新）
        Shizuku.requestPermission(SHIZUKU_REQ)
        bindForceService { err -> setStatus(err) }
    }

    private fun forceGlobal(usage: Int, force: Int, onResult: (String) -> Unit) {
        val svc = forceService
        if (svc == null) {
            onResult("Shizuku 服务未连接")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val ok = runCatching { svc.setForceUse(usage, force) }.getOrElse { false }
            val msg = svc.lastResult()
            withContext(Dispatchers.Main) { onResult("${if (ok) "✓ 已生效" else "✗ 失败"} ($msg)") }
        }
    }

    @Composable
    private fun AppUi() {
        val context = LocalContext.current
        val am = context.getSystemService(AudioManager::class.java)
        var tick by remember { mutableStateOf(0) }
        var status by remember { mutableStateOf("") }
        var recording by remember { mutableStateOf(false) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        var selectedInput by remember { mutableStateOf<String?>(null) }
        var selectedOutput by remember { mutableStateOf<String?>(null) }

        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) status = "麦克风权限已授，可试音"
        }

        val inputs = remember(tick) {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
                it.type in intArrayOf(
                    AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET
                )
            }
        }
        val outputs = remember(tick) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
                it.type in intArrayOf(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET
                )
            }
        }
        fun typeName(t: Int) = when (t) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "手机麦克风"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "手机扬声器"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话麦克风"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙耳机"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线带麦耳机"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB音频设备"
            else -> "音频设备"
        }
        fun key(d: AudioDeviceInfo) = "t${d.type}:a${d.address}"
        fun resolveInput(): AudioDeviceInfo? = selectedInput?.let { s ->
            inputs.firstOrNull { key(it) == s }
        }
        fun resolveOutput(): AudioDeviceInfo? = selectedOutput?.let { s ->
            outputs.firstOrNull { key(it) == s }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text("声路", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("手动选择输入输出设备 · 全局生效（需 Shizuku）", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(16.dp))

            Card2("声音输入（本 App 试音）") {
                TextButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (!recording) {
                        recording = true
                        status = "录音中 4 秒（${resolveInput()?.productName ?: "默认"}）…"
                        CoroutineScope(Dispatchers.IO).launch {
                            val pcm = recordPcm(resolveInput())
                            withContext(Dispatchers.Main) {
                                status = "回放录音…"
                                playPcm(pcm, resolveOutput())
                                status = "试音完成"
                                recording = false
                            }
                        }
                    }
                }) { Text(if (recording) "录音中…" else "▶ 按选中输入录 4 秒并回放") }
                inputs.forEach { d ->
                    val k = key(d)
                    TextButton(onClick = { selectedInput = k; tick++ }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (selectedInput == k) "✓ " else "") + (d.productName?.toString()?.ifBlank { null } ?: typeName(d.type)))
                    }
                }
                TextButton(onClick = { selectedInput = null; tick++ }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selectedInput == null) "✓ 系统默认" else "系统默认")
                }
            }

            Spacer(Modifier.size(16.dp))
            Card2("声音输出（本 App 试音）") {
                outputs.forEach { d ->
                    val k = key(d)
                    TextButton(onClick = { selectedOutput = k; tick++ }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (selectedOutput == k) "✓ " else "") + (d.productName?.toString()?.ifBlank { null } ?: typeName(d.type)))
                    }
                }
                TextButton(onClick = {
                    player?.release()
                    player = MediaPlayer().apply {
                        runCatching { resolveOutput()?.let { preferredDevice = it } }
                    }
                    status = "输出已设为 ${resolveOutput()?.productName ?: "系统默认"}，播放测试音"
                    playBeep(resolveOutput())
                }) { Text("♪ 播放测试音到选中输出") }
            }

            Spacer(Modifier.size(16.dp))
            Card2("全局路由（影响所有 App · 需 Shizuku）") {
                val shizukuOk = Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED && forceService != null
                Text(
                    if (shizukuOk) "Shizuku 已连接" else "Shizuku 未连接：安装 Shizuku 并通过无线调试/ADB 启动，然后点下方授权",
                    color = if (shizukuOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
                TextButton(onClick = { requestShizuku { status = it } }) { Text("连接 / 授权 Shizuku") }
                Spacer(Modifier.size(4.dp))
                Text("媒体输出（音乐、视频、游戏）", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { forceGlobal(FOR_MEDIA, FORCE_SPEAKER) { status = it } }) { Text("强制扬声器") }
                    Button(onClick = { forceGlobal(FOR_MEDIA, FORCE_BT_A2DP) { status = it } }) { Text("强制蓝牙耳机") }
                    Button(onClick = { forceGlobal(FOR_MEDIA, FORCE_NONE) { status = it } }) { Text("恢复自动") }
                }
                Spacer(Modifier.size(4.dp))
                Text("录音输入（其他 App 的录音）", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { forceGlobal(FOR_RECORD, FORCE_BT_SCO) { status = it } }) { Text("强制蓝牙麦克风") }
                    Button(onClick = { forceGlobal(FOR_RECORD, FORCE_NONE) { status = it } }) { Text("恢复自动") }
                }
                Spacer(Modifier.size(4.dp))
                Text("通话 / 语音聊天（微信、电话，免 Shizuku）", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        am.isSpeakerphoneOn = true
                        status = "通话已切到扬声器"
                    }) { Text("通话走扬声器") }
                    Button(onClick = {
                        am.isSpeakerphoneOn = false
                        status = "通话已恢复正常路由"
                    }) { Text("恢复") }
                }
            }

            Spacer(Modifier.size(12.dp))
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
        }
    }

    @Composable
    private fun Card2(title: String, content: @Composable () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(6.dp))
            content()
        }
    }

    private fun recordPcm(device: AudioDeviceInfo?): ByteArray {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(android.media.MediaRecorder.AudioSource.MIC, sampleRate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, max(minBuf, sampleRate * 4))
        runCatching { device?.let { rec.preferredDevice = it } }
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(sampleRate * 2 / 5) // 200ms
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

    private fun playPcm(pcm: ByteArray, device: AudioDeviceInfo?) {
        val wav = java.io.File(cacheDir, "shilu_test.wav")
        writeWav(wav, pcm, 16000)
        val p = MediaPlayer()
        runCatching { device?.let { p.preferredDevice = it } }
        p.setDataSource(wav.absolutePath)
        p.prepare()
        p.setOnCompletionListener { it.release() }
        p.start()
    }

    private fun playBeep(device: AudioDeviceInfo?) {
        val sr = 16000
        val pcm = ByteArray(sr * 2) // 1秒 440Hz 正弦
        for (i in 0 until sr) {
            val s = (Math.sin(2 * Math.PI * 440 * i / sr) * 12000).toInt()
            pcm[i * 2] = (s and 0xff).toByte()
            pcm[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        playPcm(pcm, device)
    }

    private fun writeWav(f: java.io.File, pcm: ByteArray, sampleRate: Int) {
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
}
