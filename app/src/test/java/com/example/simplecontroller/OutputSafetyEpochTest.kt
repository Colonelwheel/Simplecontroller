package com.example.simplecontroller.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputSafetyEpochTest {
    @Test
    fun invalidateQueuedSends_oldTicketsCannotSend() {
        val epoch = OutputSafetyEpoch("test-session")
        val hold = epoch.nextTicket()
        val turbo = epoch.nextTicket()
        val macro = epoch.nextTicket()
        val touchAim = epoch.nextTicket()
        val cameraFollow = epoch.nextTicket()
        val pulse = epoch.nextTicket()
        val delayedRelease = epoch.nextTicket()
        val clickLock = epoch.nextTicket()
        val stickResend = epoch.nextTicket()
        val keySync = epoch.nextTicket()
        val swipe = epoch.nextTicket()

        epoch.invalidateQueuedSends()

        listOf(
            hold,
            turbo,
            macro,
            touchAim,
            cameraFollow,
            pulse,
            delayedRelease,
            clickLock,
            stickResend,
            keySync,
            swipe
        ).forEach {
            assertFalse(epoch.isCurrent(it))
        }
    }

    @Test
    fun releaseAllCommand_duplicateRetriesKeepSameIdentity() {
        val epoch = OutputSafetyEpoch("test-session")
        epoch.invalidateQueuedSends()
        val release = epoch.nextTicket()

        val first = epoch.releaseAllCommand(release)
        val retry = epoch.releaseAllCommand(release)

        assertEquals("RELEASE_ALL:test-session:0", first)
        assertEquals(first, retry)
        assertEquals(
            "RELEASE_ALL_ACK:test-session:0",
            epoch.releaseAllAcknowledgement(release)
        )
    }

    @Test
    fun newInputAfterRelease_usesCurrentGenerationAndHigherSequence() {
        val epoch = OutputSafetyEpoch("test-session")
        val stale = epoch.nextTicket()
        epoch.invalidateQueuedSends()
        val release = epoch.nextTicket()
        val intentionalNewInput = epoch.nextTicket()

        assertFalse(epoch.isCurrent(stale))
        assertTrue(epoch.isCurrent(release))
        assertTrue(epoch.isCurrent(intentionalNewInput))
        assertTrue(intentionalNewInput.sequence > release.sequence)
        assertEquals(
            "OUTPUT:test-session:2:X360A_HOLD",
            epoch.wrapOutput(intentionalNewInput, "X360A_HOLD")
        )
    }
}
