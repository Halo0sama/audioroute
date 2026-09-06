package com.halo.audioroute

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

class MainActivity : ComponentActivity() {

    companion object {
        private const val SHIZUKU_REQ = 7001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RouteState.init(this)
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQ && grantResult == PackageManager.PERMISSION_GRANTED) {
                RouteState.bindShizuku(applicationContext)
            }
        }
        // server 的 binder 推送常晚于本进程的首次 bind 尝试：收到推送时必须重新绑定
        Shizuku.addBinderReceivedListener {
            RouteState.bindShizuku(applicationContext)
        }
        RouteState.bindShizuku(applicationContext)
        ApiServerHolder.start(this)
        setContent { App() }
    }

    override fun onResume() {
        super.onResume()
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            RouteState.bindShizuku(applicationContext)
        }
    }
}


object ApiServerHolder {
    @Volatile private var server: AudioApiServer? = null
    fun start(context: android.content.Context) {
        if (server != null) return
        val s = AudioApiServer(context.applicationContext)
        runCatching { s.start(500, false) }
            .onSuccess { server = s; android.util.Log.i("ShiluApi", "server up on ${RouteState.PORT}") }
            .onFailure { android.util.Log.e("ShiluApi", "server start failed", it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        MaterialTheme.colorScheme
    }
    androidx.compose.material3.MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("声路", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                            Text("输入输出路由 · 全局生效", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        val ok = RouteState.connectedState.value
                        Text(
                            if (ok) "Shizuku 已连接" else "Shizuku 未连接",
                            fontSize = 12.sp,
                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ShizukuCard()
                OutputCard()
                InputCard()
                GlobalCard()
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

private fun deviceIcon(type: Int): ImageVector = when (type) {
    android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> Icons.Filled.GraphicEq
    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> Icons.Filled.Bluetooth
    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> Icons.Filled.Headphones
    android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
    android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> Icons.Filled.Usb
    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Icons.Filled.Speaker
    else -> Icons.Filled.VolumeUp
}

@Composable
private fun SectionCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DeviceRow(name: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = name, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(name, Modifier.weight(1f).padding(start = 10.dp), fontSize = 15.sp)
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ShizukuCard() {
    val context = LocalContext.current
    val connected = RouteState.connectedState.value
    val bindError = RouteState.bindError
    SectionCard(
        "Shizuku",
        "全局路由需要 Shizuku（无线调试/ADB 启动）。应用商店或 GitHub RikkaApps/Shizuku 获取"
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (connected) "已连接" else "未连接",
                Modifier.weight(1f),
                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = {
                runCatching { Shizuku.requestPermission(7001) }
                    .onFailure { RouteState.bindShizuku(context.applicationContext) }
            }) { Text("连接 / 授权") }
        }
    }
}

@Composable
private fun OutputCard() {
    val context = LocalContext.current
    var sel by remember { mutableStateOf(RouteState.selectedOutputKey ?: "auto") }
    val outputs = RouteState.outputDevices(context)
    SectionCard("声音输出", "试音与选择；其他 App 的输出请用全局路由") {
        DeviceRow("跟随系统", Icons.Filled.VolumeUp, sel == "auto") { sel = "auto"; RouteState.selectOutput(context, "auto") }
        outputs.forEach { d ->
            val k = RouteState.key(d)
            DeviceRow(RouteState.label(d), deviceIcon(d.type), sel == k) { sel = k; RouteState.selectOutput(context, k) }
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { RouteState.beep(context) }) {
            Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  播放测试音到选中输出")
        }
    }
}

@Composable
private fun InputCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sel by remember { mutableStateOf(RouteState.selectedInputKey ?: "builtin") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) status = "权限已授，可录音"
    }
    val inputs = RouteState.inputDevices(context)
    SectionCard("声音输入", "录音并回放以验证") {
        DeviceRow("手机麦克风", Icons.Filled.Mic, sel == "builtin") { sel = "builtin"; RouteState.selectInput(context, "builtin") }
        inputs.forEach { d ->
            val k = RouteState.key(d)
            DeviceRow(RouteState.label(d), deviceIcon(d.type), sel == k) { sel = k; RouteState.selectInput(context, k) }
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                micPerm.launch(Manifest.permission.RECORD_AUDIO)
                return@TextButton
            }
            if (busy) return@TextButton
            busy = true
            status = "录音中 4 秒…"
            scope.launch {
                val pcm = withContext(Dispatchers.IO) { RouteState.recordTest(context) }
                status = "回放录音…"
                withContext(Dispatchers.IO) { RouteState.playPcm(context, pcm) }
                status = "试音完成"
                busy = false
            }
        }) { Text(if (busy) "录音中…" else "● 按选中输入录 4 秒并回放") }
        if (status.isNotBlank()) Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GlobalCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var callSpeaker by remember { mutableStateOf(false) }

    fun doForce(usage: Int, force: Int, label: String) {
        scope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) { RouteState.setForceUse(usage, force) }
            status = "$label：${if (ok) "✓ 已生效" else "✗ $msg"}"
        }
    }

    SectionCard("全局路由 · 影响所有 App", "经 Shizuku 强制系统级音频路由；插拔设备/重启后需重新应用") {
        Text("媒体输出（音乐、视频、游戏）", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            FilterChip(selected = false, onClick = { doForce(RouteState.FOR_MEDIA, RouteState.FORCE_SPEAKER, "媒体→扬声器") }, label = { Text("强制扬声器") })
            FilterChip(selected = false, onClick = { doForce(RouteState.FOR_MEDIA, RouteState.FORCE_BT_A2DP, "媒体→蓝牙耳机") }, label = { Text("强制蓝牙耳机") })
            FilterChip(selected = false, onClick = { doForce(RouteState.FOR_MEDIA, RouteState.FORCE_NONE, "媒体→自动") }, label = { Text("恢复自动") })
        }
        Text("录音输入（其他 App 的录音）", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            FilterChip(selected = false, onClick = { doForce(RouteState.FOR_RECORD, RouteState.FORCE_BT_SCO, "录音→蓝牙麦克风") }, label = { Text("强制蓝牙麦克风") })
            FilterChip(selected = false, onClick = { doForce(RouteState.FOR_RECORD, RouteState.FORCE_NONE, "录音→自动") }, label = { Text("恢复自动") })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            Text("通话 / 语音聊天走扬声器（免 Shizuku）", Modifier.weight(1f), fontSize = 14.sp)
            Switch(checked = callSpeaker, onCheckedChange = {
                callSpeaker = it
                context.getSystemService(android.media.AudioManager::class.java).isSpeakerphoneOn = it
                status = if (it) "通话已切到扬声器" else "通话已恢复"
            })
        }
        if (status.isNotBlank()) {
            Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
