package com.halo.audioroute

/** Remembers the route this session replaced, without overwriting later changes by other clients. */
data class MediaDevice(val type: Int, val address: String)

class MediaRouteLease(
    private val read: () -> List<MediaDevice>,
    private val write: (List<MediaDevice>) -> Unit
) {
    var original: List<MediaDevice>? = null; private set
    var owned: List<List<MediaDevice>> = emptyList(); private set
    val active get() = original != null

    fun apply(devices: List<MediaDevice>) {
        require(devices.isNotEmpty())
        if (!active) original = read().toList()
        // Keep both possibilities if the system applies a write but loses its reply.
        owned = (owned + listOf(devices.toList())).distinct()
        write(devices)
        check(same(read(), devices)) { "系统未保留请求的媒体首选设备" }
        owned = listOf(devices.toList())
    }

    fun restore(): Boolean {
        val baseline = original ?: return true
        val current = read()
        if (same(current, baseline)) { forget(); return true }
        if (owned.none { same(it, current) }) { forget(); return false }
        write(baseline)
        check(same(read(), baseline)) { "未能恢复原媒体首选设备" }
        forget()
        return true
    }

    fun recover(baseline: List<MediaDevice>, candidates: List<List<MediaDevice>>): Boolean {
        check(!active) { "当前已有媒体路由会话" }
        original = baseline.toList()
        owned = candidates.map { it.toList() }
        return restore()
    }

    private fun forget() { original = null; owned = emptyList() }
    private fun same(a: List<MediaDevice>, b: List<MediaDevice>) = a.size == b.size && a.toSet() == b.toSet()
}
