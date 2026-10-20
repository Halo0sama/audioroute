package com.halo.audioroute

import org.junit.Assert.*
import org.junit.Test

class MediaRouteLeaseTest {
    private val speaker = listOf(MediaDevice(2, ""))
    private val bluetooth = listOf(MediaDevice(8, "headset"))
    private val usb = listOf(MediaDevice(22, "card1"))

    @Test fun restoringAutomaticRemovesOurPreference() {
        var system = emptyList<MediaDevice>()
        val lease = MediaRouteLease({ system }, { system = it })
        lease.apply(speaker)
        assertEquals(speaker, system)
        assertTrue(lease.restore())
        assertEquals(emptyList<MediaDevice>(), system)
        assertFalse(lease.active)
    }
    @Test fun switchingSeveralTimesKeepsTheOriginalExternalPreference() {
        var system = usb
        val lease = MediaRouteLease({ system }, { system = it })
        lease.apply(speaker)
        lease.apply(bluetooth)
        assertEquals(usb, lease.original)
        assertTrue(lease.restore())
        assertEquals(usb, system)
    }
    @Test fun restorePreservesANewerExternalChoice() {
        var system = emptyList<MediaDevice>()
        val lease = MediaRouteLease({ system }, { system = it })
        lease.apply(speaker)
        system = usb
        assertFalse(lease.restore())
        assertEquals(usb, system)
        assertFalse(lease.active)
    }
    @Test fun recoveryHandlesALostSuccessfulWriteReply() {
        var system = speaker
        val lease = MediaRouteLease({ system }, { system = it })
        assertTrue(lease.recover(emptyList(), listOf(bluetooth, speaker)))
        assertEquals(emptyList<MediaDevice>(), system)
    }
    @Test fun failedRestoreRetainsTheRecoverySnapshot() {
        var system = emptyList<MediaDevice>()
        var fail = false
        val lease = MediaRouteLease({ system }, { if (fail) error("binder failed") else system = it })
        lease.apply(speaker)
        fail = true
        assertThrows(IllegalStateException::class.java) { lease.restore() }
        assertTrue(lease.active)
        assertEquals(emptyList<MediaDevice>(), lease.original)
        fail = false
        assertTrue(lease.restore())
    }
    @Test fun systemIgnoringAWriteCannotBeVerifiedAsSuccess() {
        val lease = MediaRouteLease({ bluetooth }, {})
        assertThrows(IllegalStateException::class.java) { lease.apply(speaker) }
        assertTrue(lease.active)
        assertTrue(lease.restore())
    }
    @Test fun alreadyRestoredRecoveryDoesNotWriteAgain() {
        val lease = MediaRouteLease({ usb }, { error("unnecessary write") })
        assertTrue(lease.recover(usb, listOf(speaker)))
        assertFalse(lease.active)
    }
}
