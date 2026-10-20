package com.halo.audioroute

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Loopback control API. Reads never call Binder; mutations return verified results. */
class AudioApiServer(private val context: Context) : NanoHTTPD("127.0.0.1", RouteState.PORT) {
    private class HttpError(val status: Response.Status, message: String) : Exception(message)
    private val routes = mapOf(
        "/api/health" to Method.GET, "/api/devices" to Method.GET, "/api/status" to Method.GET,
        "/api/select" to Method.POST, "/api/test" to Method.POST, "/api/global" to Method.POST,
        "/api/call" to Method.POST, "/api/refresh" to Method.POST
    )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.removeSuffix("/")
        val method = routes[uri] ?: return error(Response.Status.NOT_FOUND, "接口不存在")
        if (session.method != method) return error(Response.Status.METHOD_NOT_ALLOWED, "请使用 $method").apply { addHeader("Allow", method.name) }
        return try {
            var status = Response.Status.OK
            val json = when (uri) {
                "/api/health" -> JSONObject().put("ok", true).put("app", "audioroute")
                    .put("version", BuildConfig.VERSION_NAME).put("version_code", BuildConfig.VERSION_CODE)
                "/api/devices" -> devicesJson()
                "/api/status" -> statusJson()
                "/api/select" -> {
                    val body = body(session)
                    requireFields(body, setOf("input", "output"))
                    val input = optionalSelection(body, "input")
                    val output = optionalSelection(body, "output")
                    RouteState.select(context, body.has("input"), input, body.has("output"), output)
                    statusJson()
                }
                "/api/test" -> {
                    val body = body(session)
                    requireFields(body, setOf("kind"))
                    val kind = body.getString("kind")
                    require(kind in setOf("record", "beep")) { "kind 必须是 record 或 beep" }
                    if (kind == "record" && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        throw HttpError(Response.Status.FORBIDDEN, "请先在手机上授予录音权限")
                    }
                    if (!RouteState.startTest(context, kind)) throw HttpError(Response.Status.CONFLICT, "已有试音正在执行")
                    status = Response.Status.ACCEPTED
                    JSONObject().put("ok", true).put("async", true).put("kind", kind).put("poll", "/api/status")
                }
                "/api/global" -> {
                    val body = body(session)
                    requireFields(body, setOf("media", "record"))
                    val requests = linkedMapOf<Int, Int>()
                    if (body.has("media")) requests[RouteState.FOR_MEDIA] = forceValue(body, "media", mapOf(
                        "none" to RouteState.FORCE_NONE, "speaker" to RouteState.FORCE_SPEAKER, "bt_a2dp" to RouteState.FORCE_BT_A2DP))
                    if (body.has("record")) requests[RouteState.FOR_RECORD] = forceValue(body, "record", mapOf(
                        "none" to RouteState.FORCE_NONE, "bt_sco" to RouteState.FORCE_BT_SCO))
                    requireService()
                    val result = RouteState.setForces(requests)
                    if (!result.ok) status = Response.Status.SERVICE_UNAVAILABLE
                    statusJson().put("ok", result.ok).put("message", result.message)
                }
                "/api/refresh" -> {
                    requireService()
                    val result = RouteState.refreshForceState()
                    if (!result.ok) status = Response.Status.SERVICE_UNAVAILABLE
                    statusJson().put("ok", result.ok).put("message", result.message)
                }
                "/api/call" -> {
                    if (RouteState.testState.running) throw HttpError(Response.Status.CONFLICT, "试音正在使用音频设备，请结束后再修改通话请求")
                    val body = body(session)
                    requireFields(body, setOf("speaker"))
                    require(body.get("speaker") is Boolean) { "speaker 必须是布尔值" }
                    val result = RouteState.setCallSpeaker(context, body.getBoolean("speaker"))
                    if (!result.ok) status = Response.Status.CONFLICT
                    statusJson().put("ok", result.ok).put("message", result.message)
                }
                else -> error("unreachable")
            }
            response(status, json)
        } catch (e: HttpError) {
            error(e.status, e.message ?: "请求失败")
        } catch (e: JSONException) {
            error(Response.Status.BAD_REQUEST, "无效 JSON 或字段类型：${e.message}")
        } catch (e: IllegalArgumentException) {
            error(Response.Status.BAD_REQUEST, e.message ?: "无效参数")
        } catch (e: Exception) {
            android.util.Log.e("AudioApi", "request failed: $uri", e)
            error(Response.Status.INTERNAL_ERROR, e.message ?: "内部错误")
        }
    }

    private fun requireService() {
        if (!RouteState.shizukuConnected()) throw HttpError(Response.Status.SERVICE_UNAVAILABLE, "Shizuku 服务未连接，请在手机上启动并授权")
        if (RouteState.forceBusy) throw HttpError(Response.Status.CONFLICT, "路由查询或设置正在执行，请稍后重试")
    }
    private fun requireFields(body: JSONObject, allowed: Set<String>) {
        require(body.length() > 0) { "请至少提供一个字段" }
        require(body.keys().asSequence().all { it in allowed }) { "包含未知字段，允许：${allowed.joinToString()}" }
    }
    private fun optionalSelection(body: JSONObject, field: String): String? {
        if (!body.has(field) || body.isNull(field)) return null
        require(body.get(field) is String) { "$field 必须是设备 key、auto 或 null" }
        return body.getString(field)
    }
    private fun forceValue(body: JSONObject, field: String, values: Map<String, Int>): Int {
        require(body.get(field) is String) { "$field 必须是字符串" }
        return values[body.getString(field)] ?: throw IllegalArgumentException("$field 允许：${values.keys.joinToString()}")
    }

    private fun devicesJson(): JSONObject {
        fun list(devices: List<android.media.AudioDeviceInfo>) = JSONArray().apply {
            devices.forEach { d -> put(JSONObject().put("key", RouteState.key(d)).put("name", RouteState.label(d))
                .put("type", d.type).put("type_name", RouteState.typeName(d.type)).put("id", d.id)) }
        }
        val selected = RouteState.selection
        return JSONObject().put("ok", true).put("inputs", list(RouteState.inputDevices(context)))
            .put("outputs", list(RouteState.outputDevices(context)))
            .put("selected_input", selected.input ?: "auto").put("selected_output", selected.output ?: "auto")
    }

    private fun statusJson(): JSONObject {
        val force = RouteState.forceState
        val selected = RouteState.selection
        val test = RouteState.testState
        return JSONObject().put("ok", true).put("shizuku", RouteState.shizukuConnected())
            .put("bind_error", RouteState.bindError).put("force_busy", RouteState.forceBusy)
            .put("force", JSONObject().put("media", force.values[RouteState.FOR_MEDIA] ?: JSONObject.NULL)
                .put("record", force.values[RouteState.FOR_RECORD] ?: JSONObject.NULL))
            .put("force_checked_at", force.checkedAt ?: JSONObject.NULL)
            .put("force_source", "system_readback_cache")
            .put("media_strategy", force.mediaStrategy ?: JSONObject.NULL)
            .put("requested_media_force", RouteState.requestedMediaForce ?: JSONObject.NULL)
            .put("media_route", force.mediaDevices?.let { devices -> JSONArray().apply {
                devices.forEach { device -> put(JSONObject().put("key", device.key).put("name", device.name).put("type", device.type)) }
            } } ?: JSONObject.NULL)
            .put("media_route_source", "predicted_by_audio_attributes")
            .put("selected_input", selected.input ?: "auto").put("selected_output", selected.output ?: "auto")
            .put("last_force_result", RouteState.lastForceResult).put("last_force_ok", RouteState.lastForceOk ?: JSONObject.NULL)
            .put("call_speaker_requested", RouteState.callSpeakerRequested).put("call_result", RouteState.callResult)
            .put("audio_test", JSONObject().put("running", test.running).put("kind", test.kind)
                .put("ok", test.ok ?: JSONObject.NULL).put("message", test.message)
                .put("routed_input", test.input ?: JSONObject.NULL).put("routed_output", test.output ?: JSONObject.NULL)
                .put("routed_outputs", JSONArray(test.outputs))
                .put("recorded_bytes", test.recordedBytes))
    }

    private fun body(session: IHTTPSession): JSONObject {
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: throw HttpError(Response.Status.LENGTH_REQUIRED, "需要 Content-Length")
        if (length > 8192) throw HttpError(Response.Status.PAYLOAD_TOO_LARGE, "请求体最大为 8192 字节")
        require(length > 0) { "请求体不能为空" }
        val bytes = ByteArray(length.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = session.inputStream.read(bytes, read, bytes.size - read)
            require(n > 0) { "请求体不完整" }
            read += n
        }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    private fun response(status: Response.Status, json: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", json.toString()).apply { addHeader("Cache-Control", "no-store") }
    private fun error(status: Response.Status, message: String) = response(status, JSONObject().put("ok", false).put("error", message))
}
