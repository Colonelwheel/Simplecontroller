package com.example.simplecontroller.net

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CameraFollowCommandsTest {
    @Test fun excludedManualMovement_keepsSessionAndSequence() {
        assertEquals("STICK_L_NO_FOLLOW:abc:17:0.50,-0.75", orderedStickCommand("STICK_L", false, true, "abc", 17, .5f, -.75f))
    }
    @Test fun excludedMacroMovement_andMatchedCenterKeepMetadata() {
        assertEquals("STICK_MACRO_L_NO_FOLLOW:abc:18:1.00,0.00", orderedStickCommand("STICK_L", true, true, "abc", 18, 1f, 0f))
        assertEquals("STICK_MACRO_L_NO_FOLLOW:abc:19:0.00,0.00", orderedStickCommand("STICK_L", true, true, "abc", 19, 0f, 0f))
    }
    @Test fun rightStick_priorityPacketsUnaffected() {
        for (excluded in listOf(false, true)) {
            assertEquals("STICK_R:abc:3:0.25,0.00", orderedStickCommand("STICK_R", false, excluded, "abc", 3, .25f, 0f))
            assertEquals("STICK_MACRO_R:abc:3:0.25,0.00", orderedStickCommand("STICK_R", true, excluded, "abc", 3, .25f, 0f))
        }
    }
    @Test fun eligibleMovement_preservesLegacyWire() {
        assertEquals("STICK_L:abc:3:0.25,0.00", orderedStickCommand("STICK_L", false, false, "abc", 3, .25f, 0f))
        assertEquals("STICK_MACRO_L:abc:3:0.25,0.00", orderedStickCommand("STICK_L", true, false, "abc", 3, .25f, 0f))
    }
    @Test fun strength_isSeparateCommandWithOrderedIdentity() {
        assertEquals("CAMERA_FOLLOW_STRENGTH:abc:20:125.00", cameraFollowStrengthCommand("abc", 20, 125f))
        assertTrue(isCameraFollowPayload("camera_follow:1"))
        assertFalse(isCameraFollowPayload("CAMERA_FOLLOW:125"))
    }
    @Test fun strength_boundsAndNonfiniteInputAreSafe() {
        assertEquals(0f, cameraFollowStrengthPercent(-1f))
        assertEquals(200f, cameraFollowStrengthPercent(300f))
        assertEquals(100f, cameraFollowStrengthPercent(Float.NaN))
        assertEquals(100f, cameraFollowStrengthPercent(Float.POSITIVE_INFINITY))
    }
    @Test fun changedLocale_preservesReceiverDecimalSeparator() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("CAMERA_FOLLOW_STRENGTH:abc:20:125.50", cameraFollowStrengthCommand("abc", 20, 125.5f))
            assertEquals("STICK_L_NO_FOLLOW:abc:3:0.25,0.00", orderedStickCommand("STICK_L", false, true, "abc", 3, .25f, 0f))
        } finally { Locale.setDefault(old) }
    }
    @Test fun releaseBarrier_invalidatesPendingStrengthTicket() {
        val epoch = OutputSafetyEpoch("session")
        val queued = epoch.nextTicket()
        epoch.invalidateQueuedSends()
        assertFalse(epoch.isCurrent(queued))
        assertTrue(epoch.isCurrent(epoch.nextTicket()))
    }
}
