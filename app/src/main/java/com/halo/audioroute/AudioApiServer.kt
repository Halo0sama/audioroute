package com.halo.audioroute

import android.content.Context
import android.media.AudioManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 声路的本地控制服务器（127.0.0.1:8767，CLI 经 adb forward 访问）。
 * GET  /api/health          存活与版本
 * GET  /api/devices         输入/输出设备（原始名+key）与当前选择
 * GET  /api/status          Shizuku 连接、全局 force 状态、当前选择
 * POST /api/select          {"input":key|null,"output":key|null|"speaker"|"auto"}
 * POST /api/test            {"kind":"record"|"beep"}（异步执行）
 * POST /api/global          {"media":"none|speaker|bt_a2dp","record":"none|bt_sco"}（可只传其一）
 * POST /api/call            {"speaker":true|false}
 */
class AudioApiServer(private val context: Context) : NanoHTTPD("127.0.0.1", RouteState.PORT) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private class HttpError(val status: Response.Status, message: String) : Exception(message)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.removeSuffix("/")
        return try {
            val json: JSONObject = when {
                uri == "/api/health" -> JSONObject().put("ok", true).put("app", "audioroute").put("version", "1.1.0")
                uri == "/api/devices" -> devicesJson()
                uri == "/api/status" -> statusJson()
                uri == "/api/select" && session.method == Method.POST -> {
                    val b = JSONObject(body(session))
                    b.optString("input", "").takeIf { it.isNotEmpty() && it != "auto" }?.let { RouteState.selectInput(context, it) }
                        ?: RouteState.selectInput(context, null)
                    b.optString("output", "").takeIf { it.isNotEmpty() && it != "auto" }?.let { RouteState.selectOutput(context, it) }
                        ?: RouteState.selectOutput(context, "auto")
                    statusJson()
                }
                uri == "/api/test" && session.method == Method.POST -> {
                    val kind = JSONObject(body(session)).optString("kind", "beep")
                    scope.launch {
                        if (kind == "record") RouteState.playPcm(context, RouteState.recordTest(context)) else RouteState.beep(context)
                    }
                    JSONObject().put("ok", true).put("kind", kind)
                }
                uri == "/api/global" && session.method == Method.POST -> {
                    // 异步 fire-and-forget：binder 调用可能挂起，绝不让 HTTP 层阻塞
                    val b = JSONObject(body(session))
                    val media = b.optString("media", "")
                    val record = b.optString("record", "")
                    scope.launch {
                        media.takeIf { it.isNotBlank() }?.let {
                            val force = mapOf("none" to RouteState.FORCE_NONE, "speaker" to RouteState.FORCE_SPEAKER, "bt_a2dp" to RouteState.FORCE_BT_A2DP)[it]
                            if (force != null) RouteState.setForceUse(RouteState.FOR_MEDIA, force)
                        }
                        record.takeIf { it.isNotBlank() }?.let {
                            val force = mapOf("none" to RouteState.FORCE_NONE, "bt_sco" to RouteState.FORCE_BT_SCO)[it]
                            if (force != null) RouteState.setForceUse(RouteState.FOR_RECORD, force)
                        }
                    }
                    JSONObject().put("ok", true).put("async", true)
                }
                uri == "/api/call" && session.method == Method.POST -> {
                    val speaker = JSONObject(body(session)).optBoolean("speaker", false)
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.isSpeakerphoneOn = speaker
                    JSONObject().put("ok", true).put("speaker", speaker)
                }
                else -> throw HttpError(Response.Status.NOT_FOUND, "not found")
            }
            ok(json.toString())
        } catch (e: HttpError) {
            error(e.status, e.message ?: "error")
        } catch (e: Throwable) {
            error(Response.Status.BAD_REQUEST, e.message ?: "error")
        }
    }

    private fun devicesJson(): JSONObject {
        val inputs = JSONArray()
        for (d in RouteState.inputDevices(context)) {
            inputs.put(JSONObject().put("key", RouteState.key(d)).put("name", RouteState.label(d)))
        }
        val outputs = JSONArray()
        for (d in RouteState.outputDevices(context)) {
            outputs.put(JSONObject().put("key", RouteState.key(d)).put("name", RouteState.label(d)))
        }
        return JSONObject()
            .put("inputs", inputs)
            .put("outputs", outputs)
            .put("selected_input", RouteState.selectedInputKey ?: "auto")
            .put("selected_output", RouteState.selectedOutputKey ?: "auto")
    }

    private fun forceJson(): JSONObject {
        fun f(usage: Int): Any = RouteState.getForceUse(usage) ?: JSONObject.NULL
        return JSONObject().put("media", f(RouteState.FOR_MEDIA)).put("record", f(RouteState.FOR_RECORD))
    }

    private fun statusJson(): JSONObject =
        JSONObject()
            .put("shizuku", RouteState.shizukuConnected())
            .put("force", forceJson())
            .put("selected_input", RouteState.selectedInputKey ?: "auto")
            .put("selected_output", RouteState.selectedOutputKey ?: "auto")
            .put("last_force_result", RouteState.lastForceError)

    private fun body(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val r = session.inputStream.read(buf, read, len - read)
            if (r < 0) break
            read += r
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun ok(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)

    private fun error(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, "application/json", JSONObject().put("error", message).toString())
}
