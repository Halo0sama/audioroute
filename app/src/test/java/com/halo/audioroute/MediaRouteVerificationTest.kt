package com.halo.audioroute

import org.junit.Assert.*
import org.junit.Test

class MediaRouteVerificationTest {
    @Test fun explicitSpeakerCannotPassWithBluetoothOrMissingRoute() {
        assertFalse(MediaRouteVerification.matchesDevice("t2:a", listOf("t8:aheadset")))
        assertFalse(MediaRouteVerification.matchesDevice("t2:a", emptyList()))
        assertTrue(MediaRouteVerification.matchesDevice("t2:a", listOf("t2:a")))
    }
    @Test fun explicitDeviceRequiresEveryActualOutputToMatch() {
        assertFalse(MediaRouteVerification.matchesDevice("t2:a", listOf("t2:a", "t8:aheadset")))
        assertFalse(MediaRouteVerification.matchesDevice("t8:aone", listOf("t8:atwo")))
        assertTrue(MediaRouteVerification.matchesDevice("t8:aone", listOf("t8:aone")))
    }
    @Test fun automaticPlaybackStillRequiresAnObservedRoute() {
        assertFalse(MediaRouteVerification.matchesDevice(null, emptyList()))
        assertTrue(MediaRouteVerification.matchesDevice(null, listOf("t8:aheadset")))
    }

    @Test fun subsequentRecordChangeCanInvalidateEarlierMediaReadback() {
        val requested = mapOf(1 to 1, 2 to 0)
        assertTrue(MediaRouteVerification.policyMatches(requested, mapOf(1 to 1, 2 to 0)))
        assertFalse(MediaRouteVerification.policyMatches(requested, mapOf(1 to 0, 2 to 0)))
    }
    @Test fun speakerPolicyDoesNotProveSpeakerWhenBluetoothStillWins() {
        assertEquals(false, MediaRouteVerification.matches(1, listOf(8)))
    }
    @Test fun matchingMediaCategoriesAreVerified() {
        assertEquals(true, MediaRouteVerification.matches(1, listOf(2)))
        assertEquals(true, MediaRouteVerification.matches(1, listOf(24)))
        assertEquals(true, MediaRouteVerification.matches(4, listOf(8)))
    }
    @Test fun duplicatedSpeakerAndBluetoothOutputIsNotSpeakerOnly() {
        assertEquals(false, MediaRouteVerification.matches(1, listOf(2, 8)))
    }
    @Test fun noReportedRouteCannotVerifyARequest() {
        assertEquals(false, MediaRouteVerification.matches(4, emptyList()))
    }
    @Test fun automaticAndUnsupportedObservationStayUnknown() {
        assertNull(MediaRouteVerification.matches(0, listOf(8)))
        assertNull(MediaRouteVerification.matches(1, null))
    }
}
