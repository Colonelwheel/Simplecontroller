package com.example.simplecontroller.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotAlternateStateMachineTest {
    @Test
    fun successfulBaseCompletion_armsAlternate() {
        val result = reduce(OneShotAlternateState(), OneShotAlternateEvent.SUCCESSFUL_BASE_COMPLETION)

        assertEquals(OneShotAlternatePhase.ALTERNATE_ARMED, result.phase)
        assertFalse(result.recoveryResetArmed)
    }

    @Test
    fun baseCancellation_remainsBase() {
        val result = reduce(OneShotAlternateState(), OneShotAlternateEvent.BASE_CANCELLATION)

        assertEquals(OneShotAlternateState(), result)
    }

    @Test
    fun shortAlternateCompletion_remainsAlternateArmed() {
        val result = reduce(
            OneShotAlternateState(OneShotAlternatePhase.ALTERNATE_ARMED),
            OneShotAlternateEvent.NORMAL_ALTERNATE_COMPLETION
        )

        assertEquals(OneShotAlternatePhase.ALTERNATE_ARMED, result.phase)
        assertFalse(result.recoveryResetArmed)
    }

    @Test
    fun armedAlternateReset_thenCompletion_returnsToBase() {
        val thresholdReached = reduce(
            OneShotAlternateState(OneShotAlternatePhase.ALTERNATE_ARMED),
            OneShotAlternateEvent.RECOVERY_RESET_THRESHOLD_REACHED
        )
        assertEquals(OneShotAlternatePhase.ALTERNATE_ARMED, thresholdReached.phase)
        assertTrue(thresholdReached.recoveryResetArmed)

        val result = reduce(
            thresholdReached,
            OneShotAlternateEvent.NORMAL_ALTERNATE_COMPLETION
        )

        assertEquals(OneShotAlternateState(), result)
    }

    @Test
    fun alternateCancellation_entersRecovery() {
        val result = reduce(
            OneShotAlternateState(OneShotAlternatePhase.ALTERNATE_ARMED),
            OneShotAlternateEvent.ALTERNATE_CANCELLATION
        )

        assertEquals(OneShotAlternatePhase.ALTERNATE_RECOVERY, result.phase)
        assertFalse(result.recoveryResetArmed)
    }

    @Test
    fun recoveryShortCompletion_remainsRecovery() {
        val result = reduce(
            OneShotAlternateState(OneShotAlternatePhase.ALTERNATE_RECOVERY),
            OneShotAlternateEvent.RECOVERY_SHORT_COMPLETION
        )

        assertEquals(OneShotAlternatePhase.ALTERNATE_RECOVERY, result.phase)
        assertFalse(result.recoveryResetArmed)
    }

    @Test
    fun resetThresholdAlone_armsResetWithoutLeavingRecovery() {
        val result = reduce(
            OneShotAlternateState(OneShotAlternatePhase.ALTERNATE_RECOVERY),
            OneShotAlternateEvent.RECOVERY_RESET_THRESHOLD_REACHED
        )

        assertEquals(OneShotAlternatePhase.ALTERNATE_RECOVERY, result.phase)
        assertTrue(result.recoveryResetArmed)
    }

    @Test
    fun armedRecoveryReset_thenIntentionalRelease_returnsToBase() {
        val thresholdReached = reduce(
            OneShotAlternateState(OneShotAlternatePhase.ALTERNATE_RECOVERY),
            OneShotAlternateEvent.RECOVERY_RESET_THRESHOLD_REACHED
        )
        val result = reduce(
            thresholdReached,
            OneShotAlternateEvent.RECOVERY_INTENTIONAL_RELEASE
        )

        assertEquals(OneShotAlternateState(), result)
    }

    @Test
    fun intentionalReleaseWithoutThreshold_remainsRecovery() {
        val result = reduce(
            OneShotAlternateState(OneShotAlternatePhase.ALTERNATE_RECOVERY),
            OneShotAlternateEvent.RECOVERY_INTENTIONAL_RELEASE
        )

        assertEquals(OneShotAlternatePhase.ALTERNATE_RECOVERY, result.phase)
        assertFalse(result.recoveryResetArmed)
    }

    @Test
    fun canceledReset_disarmsResetAndRemainsRecovery() {
        val result = reduce(
            OneShotAlternateState(
                phase = OneShotAlternatePhase.ALTERNATE_RECOVERY,
                recoveryResetArmed = true
            ),
            OneShotAlternateEvent.CANCELED_RESET
        )

        assertEquals(OneShotAlternatePhase.ALTERNATE_RECOVERY, result.phase)
        assertFalse(result.recoveryResetArmed)
    }

    @Test
    fun hardReset_fromEveryPhase_returnsToBase() {
        val states = listOf(
            OneShotAlternateState(OneShotAlternatePhase.BASE),
            OneShotAlternateState(OneShotAlternatePhase.ALTERNATE_ARMED),
            OneShotAlternateState(OneShotAlternatePhase.ALTERNATE_RECOVERY),
            OneShotAlternateState(
                phase = OneShotAlternatePhase.ALTERNATE_RECOVERY,
                recoveryResetArmed = true
            )
        )

        states.forEach { state ->
            assertEquals(
                OneShotAlternateState(),
                reduce(state, OneShotAlternateEvent.HARD_RESET)
            )
        }
    }

    private fun reduce(
        state: OneShotAlternateState,
        event: OneShotAlternateEvent
    ): OneShotAlternateState = OneShotAlternateStateMachine.reduce(state, event)
}
