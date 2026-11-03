package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ButtonAimPayloadTiming
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonAimReleaseBoundaryTest {
    @Test
    fun optedOut_releaseOutsideRemainsEligible() {
        val policy = ButtonAimReleaseBoundaryPolicy()
        policy.begin(false, ButtonAimPayloadTiming.SEND_ON_RELEASE)
        policy.observe(-10f, 300f, 100f, 100f)
        assertFalse(policy.canceled)
    }

    @Test
    fun immediatePayload_boundaryOptionNeverCancels() {
        val policy = ButtonAimReleaseBoundaryPolicy()
        policy.begin(true, ButtonAimPayloadTiming.IMMEDIATE)
        policy.observe(150f, -50f, 100f, 100f)
        assertFalse(policy.canceled)
    }

    @Test
    fun releaseInside_staysEligibleIncludingLeftAndTopEdges() {
        val policy = armed()
        policy.observe(0f, 0f, 100f, 80f)
        policy.observe(99.99f, 79.99f, 100f, 80f)
        assertFalse(policy.canceled)
    }

    @Test
    fun eachOutsideEdge_irrevocablyCancelsEvenAfterReentry() {
        for ((x, y) in listOf(-.01f to 40f, 100f to 40f, 50f to -.01f, 50f to 80f)) {
            val policy = armed()
            policy.observe(x, y, 100f, 80f)
            policy.observe(50f, 40f, 100f, 80f)
            assertTrue("Exit at $x,$y must remain canceled after re-entry", policy.canceled)
        }
    }

    @Test
    fun historicalExit_followedByCurrentInsideSampleCancels() {
        val policy = armed()
        // Same order as MotionEvent history and current-point processing in the handler.
        listOf(20f to 30f, 101f to 30f, 40f to 30f).forEach { (x, y) ->
            policy.observe(x, y, 100f, 80f)
        }
        assertTrue(policy.canceled)
    }

    @Test
    fun firstExitOnFingerUp_alsoCancels() {
        val policy = armed()
        policy.observe(50f, 40f, 100f, 80f)
        assertFalse(policy.canceled)
        policy.observe(100f, 40f, 100f, 80f)
        assertTrue(policy.canceled)
    }

    @Test
    fun newGesture_rearmsAfterCanceledGesture() {
        val policy = armed()
        policy.observe(120f, 40f, 100f, 80f)
        assertTrue(policy.canceled)
        policy.begin(true, ButtonAimPayloadTiming.SEND_ON_RELEASE)
        policy.observe(50f, 40f, 100f, 80f)
        assertFalse(policy.canceled)
    }

    @Test
    fun hardReset_disarmsAndClearsPriorExit() {
        val policy = armed()
        policy.observe(-1f, 0f, 100f, 80f)
        policy.reset()
        policy.observe(-1f, 0f, 100f, 80f)
        assertFalse(policy.canceled)
    }

    @Test
    fun nonfiniteCoordinates_cannotQualifyAsInside() {
        for (x in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val policy = armed()
            policy.observe(x, 40f, 100f, 80f)
            assertTrue(policy.canceled)
        }
    }

    @Test
    fun optedInReturn_insideSampleRearmsCanceledRelease() {
        val policy = armed(rearm = true)
        policy.observe(110f, 40f, 100f, 80f)
        assertTrue(policy.canceled)
        policy.observe(50f, 40f, 100f, 80f)
        assertFalse(policy.canceled)
    }

    @Test
    fun rearmOptionWithoutCancellation_hasNoEffect() {
        val policy = ButtonAimReleaseBoundaryPolicy()
        policy.begin(false, ButtonAimPayloadTiming.SEND_ON_RELEASE, rearmOnReturn = true)
        policy.observe(150f, 40f, 100f, 80f)
        assertFalse(policy.canceled)
    }

    @Test
    fun rearmedRelease_leavingAgainBeforeUpCancelsAgain() {
        val policy = armed(rearm = true)
        listOf(110f, 50f, 120f).forEach { x -> policy.observe(x, 40f, 100f, 80f) }
        assertTrue(policy.canceled)
    }

    @Test
    fun coalescedExitAndReturn_exposesBothTransitionsForDwellTimerReset() {
        val policy = armed(rearm = true)
        val canceledStates = listOf(20f, 110f, 40f).map { x ->
            policy.observe(x, 30f, 100f, 80f)
            policy.canceled
        }
        // Exit must cancel qualification/timers even though the event ends inside;
        // the following return requires a fresh Hold/reset threshold interval.
        org.junit.Assert.assertEquals(listOf(false, true, false), canceledStates)
    }

    @Test
    fun rearmOption_neverCancelsImmediateInputOrLeaksToNextGesture() {
        val policy = armed(rearm = true)
        policy.begin(true, ButtonAimPayloadTiming.IMMEDIATE, rearmOnReturn = true)
        policy.observe(150f, 40f, 100f, 80f)
        assertFalse(policy.canceled)
        policy.begin(true, ButtonAimPayloadTiming.SEND_ON_RELEASE)
        policy.observe(150f, 40f, 100f, 80f)
        policy.observe(40f, 40f, 100f, 80f)
        assertTrue(policy.canceled)
    }

    private fun armed(rearm: Boolean = false) = ButtonAimReleaseBoundaryPolicy().apply {
        begin(true, ButtonAimPayloadTiming.SEND_ON_RELEASE, rearm)
    }
}
