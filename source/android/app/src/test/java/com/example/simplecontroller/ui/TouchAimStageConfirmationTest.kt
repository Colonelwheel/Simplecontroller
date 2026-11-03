package com.example.simplecontroller.ui

import org.junit.Assert.*
import org.junit.Test

class TouchAimStageConfirmationTest {
    @Test fun zeroDelay_keepsImmediateLegacyStageJumps() {
        val confirmation = TouchAimStageConfirmation { 0L }
        assertEquals(TouchContactLevel.HIGH, confirmation.onTarget(TouchContactLevel.HIGH, 100L))
        assertEquals(TouchContactLevel.HIGH, confirmation.confirmedLevel)
        assertEquals(TouchContactLevel.AIM_ONLY, confirmation.onTarget(TouchContactLevel.AIM_ONLY, 101L))
        assertNull(confirmation.nextDeadlineMs())
    }

    @Test fun candidate_waitsWithoutChangingConfirmedStage_andMaturesWithoutMove() {
        val confirmation = TouchAimStageConfirmation { 125L }
        assertNull(confirmation.onTarget(TouchContactLevel.MEDIUM, 100L))
        assertEquals(TouchContactLevel.AIM_ONLY, confirmation.confirmedLevel)
        assertEquals(225L, confirmation.nextDeadlineMs())
        assertNull(confirmation.onTime(224L))
        assertEquals(TouchContactLevel.MEDIUM, confirmation.onTime(225L))
        assertEquals(TouchContactLevel.MEDIUM, confirmation.confirmedLevel)
        assertNull(confirmation.nextDeadlineMs())
    }

    @Test fun targetChange_restartsFullDestinationConfirmation() {
        val confirmation = TouchAimStageConfirmation {
            if (it == TouchContactLevel.HIGH) 200L else 100L
        }
        confirmation.onTarget(TouchContactLevel.MEDIUM, 100L)
        assertNull(confirmation.onTarget(TouchContactLevel.HIGH, 190L))
        assertEquals(390L, confirmation.nextDeadlineMs())
        assertNull(confirmation.onTime(300L))
        assertNull(confirmation.onTarget(TouchContactLevel.MEDIUM, 310L))
        assertEquals(410L, confirmation.nextDeadlineMs())
        assertEquals(TouchContactLevel.MEDIUM, confirmation.onTime(410L))
    }

    @Test fun returnToConfirmedStage_cancelsCandidate() {
        val confirmation = TouchAimStageConfirmation { 100L }
        confirmation.onTarget(TouchContactLevel.HIGH, 100L)
        assertNull(confirmation.onTarget(TouchContactLevel.AIM_ONLY, 150L))
        assertNull(confirmation.nextDeadlineMs())
        assertNull(confirmation.onTime(500L))
        assertEquals(TouchContactLevel.AIM_ONLY, confirmation.confirmedLevel)
    }

    @Test fun aimOnly_hasItsOwnReturnConfirmation() {
        val confirmation = TouchAimStageConfirmation {
            if (it == TouchContactLevel.AIM_ONLY) 175L else 0L
        }
        confirmation.onTarget(TouchContactLevel.HIGH, 0L)
        assertNull(confirmation.onTarget(TouchContactLevel.AIM_ONLY, 20L))
        assertEquals(TouchContactLevel.HIGH, confirmation.confirmedLevel)
        assertEquals(TouchContactLevel.AIM_ONLY, confirmation.onTime(195L))
    }

    @Test fun reset_cancelsPendingDeadlineAndRestoresInitialStage() {
        val confirmation = TouchAimStageConfirmation { 100L }
        confirmation.onTarget(TouchContactLevel.HIGH, 1L)
        confirmation.reset()
        assertNull(confirmation.onTime(1000L))
        assertNull(confirmation.nextDeadlineMs())
        assertEquals(TouchContactLevel.AIM_ONLY, confirmation.confirmedLevel)
        confirmation.onTarget(TouchContactLevel.HIGH, 2000L)
        assertEquals(2100L, confirmation.nextDeadlineMs())
    }

    @Test fun legacyZeroDelayJump_retainsEveryCrossedPressStage() {
        assertEquals(
            setOf(TouchContactLevel.LOW, TouchContactLevel.MEDIUM, TouchContactLevel.HIGH),
            enteredTouchAimPressLevels(TouchContactLevel.AIM_ONLY, TouchContactLevel.HIGH, false)
        )
    }

    @Test fun confirmedJump_doesNotFireUnconfirmedIntermediatePressStages() {
        assertEquals(
            setOf(TouchContactLevel.HIGH),
            enteredTouchAimPressLevels(TouchContactLevel.AIM_ONLY, TouchContactLevel.HIGH, true)
        )
        assertTrue(enteredTouchAimPressLevels(TouchContactLevel.HIGH, TouchContactLevel.LOW, true).isEmpty())
    }
}
