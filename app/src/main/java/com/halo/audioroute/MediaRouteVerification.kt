package com.halo.audioroute

/** null means no requested media category, or this Android version cannot observe it. */
object MediaRouteVerification {
    /** A selected device requires a nonempty, exclusive actual route; an accepted request is insufficient. */
    fun matchesDevice(requestedKey: String?, actualKeys: List<String>): Boolean =
        actualKeys.isNotEmpty() && (requestedKey == null || actualKeys.all { it == requestedKey })

    fun policyMatches(requested: Map<Int, Int>, actual: Map<Int, Int>) =
        requested.all { (usage, force) -> actual[usage] == force }

    fun matches(force: Int?, deviceTypes: List<Int>?): Boolean? {
        val expected = when (force) {
            1 -> setOf(2, 24) // speaker, speaker-safe
            4 -> setOf(8) // Bluetooth A2DP
            else -> return null
        }
        if (deviceTypes == null) return null
        return deviceTypes.isNotEmpty() && deviceTypes.all { it in expected }
    }
}
