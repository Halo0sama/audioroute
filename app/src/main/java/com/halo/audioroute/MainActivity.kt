package com.halo.audioroute

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RouteState.init(this)
        RouteState.bindShizuku(applicationContext)
        ApiServerHolder.start(this)
        setContent { App() }
    }

    override fun onResume() {
        super.onResume()
        RouteState.bindShizuku(applicationContext)
    }

}


object ApiServerHolder {
    @Volatile private var server: AudioApiServer? = null
    @Synchronized
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
        if (dark) darkColorScheme() else lightColorScheme()
    }
    androidx.compose.material3.MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("声路", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                            Text("本 App 试音 · 系统路由策略", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                InputCard()
                OutputCard()
                GlobalCard()
                PlaybackSettings()
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
    else -> Icons.AutoMirrored.Filled.VolumeUp
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
private fun DeviceRow(name: String, icon: ImageVector, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
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
            TextButton(onClick = { RouteState.requestShizuku(context) }) { Text("连接 / 授权") }
        }
        if (RouteState.bindError.isNotBlank()) {
            Text(RouteState.bindError, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OutputCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RouteState.ForceResult?>(null) }
    val strategy = RouteState.forceState.mediaStrategy
    val media = if (strategy?.optBoolean("supported") == true) {
        if (!strategy.optBoolean("active")) RouteState.FORCE_NONE
        else when (strategy.optJSONArray("preferred_devices")?.optJSONObject(0)?.optInt("type")) {
            2 -> RouteState.FORCE_SPEAKER
            8 -> RouteState.FORCE_BT_A2DP
            else -> null
        }
    } else RouteState.forceState.values[RouteState.FOR_MEDIA]
    val available = RouteState.connectedState.value && !busy && !RouteState.forceBusy && !RouteState.testState.running
    val bluetooth = RouteState.outputs.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

    fun apply(force: Int) {
        if (!available) return
        busy = true
        result = null
        scope.launch {
            try { result = withContext(Dispatchers.IO) { RouteState.chooseMediaOutput(context, force) } }
            finally { busy = false }
        }
    }

    SectionCard("声音输出", "选择后立即切换媒体输出，适用于声路及使用系统默认输出的音乐、视频等 App") {
        DeviceRow("手机扬声器", Icons.Filled.Speaker, media == RouteState.FORCE_SPEAKER, enabled = available) {
            apply(RouteState.FORCE_SPEAKER)
        }
        DeviceRow(bluetooth?.let { "${RouteState.label(it)} · 蓝牙耳机" } ?: "蓝牙耳机（未连接）",
            Icons.Filled.Bluetooth, media == RouteState.FORCE_BT_A2DP, enabled = available && bluetooth != null) {
            apply(RouteState.FORCE_BT_A2DP)
        }
        DeviceRow("跟随系统 · 恢复原设置", Icons.AutoMirrored.Filled.VolumeUp, media == RouteState.FORCE_NONE, enabled = available) {
            apply(RouteState.FORCE_NONE)
        }
        if (!RouteState.connectedState.value) Text("请先连接并授权 Shizuku，才能切换媒体输出", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        if (busy) Text("正在应用输出选择…", fontSize = 12.sp)
        result?.let { state ->
            Text(if (state.ok) "系统已接受输出选择，请以实际声音确认" else state.message, fontSize = 12.sp,
                color = if (state.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
        RouteState.forceState.mediaDevices?.let { devices ->
            Text("系统预计输出：${devices.joinToString { it.name }.ifBlank { "未知" }}", fontSize = 12.sp)
        }
        TextButton(enabled = !busy && !RouteState.testState.running, onClick = {
            RouteState.startTest(context, "beep", followMediaOutput = true)
        }) { Text("播放测试音 · 验证当前媒体输出") }
        if (RouteState.testState.kind == "beep") TestResult()
    }
}

@Composable
private fun PlaybackSettings() {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "收起本 App 回放设备设置" else "本 App 回放设备（高级）")
    }
    if (expanded) PlaybackOutputCard()
}

@Composable
private fun PlaybackOutputCard() {
    val context = LocalContext.current
    val sel = RouteState.selectedOutputKey ?: "auto"
    val outputs = RouteState.outputDevices(context)
    SectionCard("本 App 回放设备", "单独覆盖声路的测试音和录音回放；不改变其他 App 的媒体输出") {
        DeviceRow("跟随系统", Icons.AutoMirrored.Filled.VolumeUp, sel == "auto") { RouteState.selectOutput(context, null) }
        outputs.forEach { d ->
            val key = RouteState.key(d)
            DeviceRow(RouteState.displayLabel(d), deviceIcon(d.type), sel == key || (sel == "speaker" && d.type == 2)) {
                runCatching { RouteState.selectOutput(context, key) }
            }
        }
        if (sel !in setOf("auto", "speaker") && outputs.none { RouteState.key(it) == sel }) {
            Text("所选输出已断开：$sel", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        TextButton(enabled = !RouteState.testState.running, onClick = { RouteState.startTest(context, "beep") }) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  播放测试音到选中输出")
        }
        if (RouteState.testState.kind == "beep") TestResult()
    }
}

@Composable
private fun InputCard() {
    val context = LocalContext.current
    val sel = RouteState.selectedInputKey ?: "auto"
    var permissionMessage by remember { mutableStateOf("") }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionMessage = if (granted) "" else "未授予录音权限"
        if (granted) RouteState.startTest(context, "record")
    }
    val inputs = RouteState.inputDevices(context)
    val test = RouteState.testState
    SectionCard("声音输入", "选择仅用于本 App 录音；试音显示系统报告的实际设备") {
        DeviceRow("跟随系统", Icons.Filled.Mic, sel == "auto") { RouteState.selectInput(context, null) }
        DeviceRow("手机麦克风", Icons.Filled.Mic, sel == "builtin") { RouteState.selectInput(context, "builtin") }
        inputs.forEach { d ->
            val key = RouteState.key(d)
            DeviceRow(RouteState.displayLabel(d), deviceIcon(d.type), sel == key) {
                runCatching { RouteState.selectInput(context, key) }
            }
        }
        if (sel !in setOf("auto", "builtin") && inputs.none { RouteState.key(it) == sel }) {
            Text("所选输入已断开：$sel", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        TextButton(enabled = !test.running, onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                micPerm.launch(Manifest.permission.RECORD_AUDIO)
            } else RouteState.startTest(context, "record")
        }) { Text(if (test.running) "试音中…" else "● 录 4 秒并回放") }
        if (permissionMessage.isNotBlank()) Text(permissionMessage, color = MaterialTheme.colorScheme.error)
        if (test.kind == "record") TestResult()
    }
}

@Composable
private fun TestResult() {
    val test = RouteState.testState
    if (test.message.isNotBlank()) Text(test.message, fontSize = 12.sp,
        color = if (test.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    if (test.input != null) Text("录音设备：${RouteState.routedLabel(test.input)}", fontSize = 12.sp)
    if (test.outputs.isNotEmpty()) Text("系统报告输出：${test.outputs.joinToString { RouteState.routedLabel(it) }}", fontSize = 12.sp)
    else if (test.output != null) Text("系统报告输出：${RouteState.routedLabel(test.output)}", fontSize = 12.sp)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GlobalCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RouteState.ForceResult?>(null) }
    val connected = RouteState.connectedState.value
    val force = RouteState.forceState.values
    fun doForce(usage: Int, value: Int) {
        if (busy) return
        busy = true
        scope.launch {
            try { result = withContext(Dispatchers.IO) { RouteState.setForceUse(usage, value) } }
            finally { busy = false }
        }
    }

    SectionCard("其他 App 的录音与通话", "录音策略不代表其他 App 已建立蓝牙麦链路；通话请求可能被正在通话的 App 覆盖") {
        Text("录音输入（其他 App）", fontWeight = FontWeight.Bold)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(enabled = connected && !busy, selected = force[RouteState.FOR_RECORD] == RouteState.FORCE_BT_SCO,
                onClick = { doForce(RouteState.FOR_RECORD, RouteState.FORCE_BT_SCO) }, label = { Text("蓝牙麦克风策略") })
            FilterChip(enabled = connected && !busy, selected = force[RouteState.FOR_RECORD] == RouteState.FORCE_NONE,
                onClick = { doForce(RouteState.FOR_RECORD, RouteState.FORCE_NONE) }, label = { Text("恢复自动") })
        }
        TextButton(enabled = connected && !busy, onClick = {
            busy = true
            scope.launch {
                try { result = withContext(Dispatchers.IO) { RouteState.refreshForceState() } }
                finally { busy = false }
            }
        }) { Text(if (busy) "正在查询 / 设置…" else "刷新系统策略") }
        if (RouteState.forceState.checkedAt == null) Text("系统策略尚未读回", fontSize = 12.sp)
        result?.let { state -> Text(
            (if (state.ok) "" else "失败：") + state.message, fontSize = 12.sp,
            color = if (state.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            Text("请求通话扬声器（免 Shizuku，通话 App 可能覆盖）", Modifier.weight(1f), fontSize = 14.sp)
            Switch(enabled = !RouteState.testState.running, checked = RouteState.callSpeakerRequested, onCheckedChange = { enabled ->
                RouteState.setCallSpeaker(context, enabled)
            })
        }
        if (RouteState.callResult.isNotBlank()) Text(RouteState.callResult, fontSize = 12.sp)
    }
}
