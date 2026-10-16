package com.example.simplecontroller.ui

/** Runtime phase for the optional one-shot alternate button behavior. */
enum class OneShotAlternatePhase {
    BASE,
    ALTERNATE_ARMED,
    ALTERNATE_RECOVERY
}

/**
 * Pure state used by [OneShotAlternateStateMachine].
 *
 * [recoveryResetArmed] is deliberately separate from [phase]: reaching the reset threshold
 * only arms a reset. The state returns to [OneShotAlternatePhase.BASE] only after a later,
 * intentional release, whether the alternate is normally armed or recovering from cancellation.
 */
data class OneShotAlternateState(
    val phase: OneShotAlternatePhase = OneShotAlternatePhase.BASE,
    val recoveryResetArmed: Boolean = false
)

/** Inputs that can change the one-shot alternate runtime phase. */
enum class OneShotAlternateEvent {
    SUCCESSFUL_BASE_COMPLETION,
    BASE_CANCELLATION,
    NORMAL_ALTERNATE_COMPLETION,
    ALTERNATE_CANCELLATION,
    RECOVERY_SHORT_COMPLETION,
    RECOVERY_RESET_THRESHOLD_REACHED,
    RECOVERY_INTENTIONAL_RELEASE,
    CANCELED_RESET,
    HARD_RESET
}

/** Deterministic, side-effect-free reducer for one-shot alternate phase transitions. */
object OneShotAlternateStateMachine {
    fun reduce(
        state: OneShotAlternateState,
        event: OneShotAlternateEvent
    ): OneShotAlternateState {
        if (event == OneShotAlternateEvent.HARD_RESET) return OneShotAlternateState()

        return when (state.phase) {
            OneShotAlternatePhase.BASE -> reduceBase(event)
            OneShotAlternatePhase.ALTERNATE_ARMED -> reduceAlternateArmed(state, event)
            OneShotAlternatePhase.ALTERNATE_RECOVERY -> reduceAlternateRecovery(state, event)
        }
    }

    private fun reduceBase(event: OneShotAlternateEvent): OneShotAlternateState = when (event) {
        OneShotAlternateEvent.SUCCESSFUL_BASE_COMPLETION ->
            OneShotAlternateState(OneShotAlternatePhase.ALTERNATE_ARMED)

        else -> OneShotAlternateState()
    }

    private fun reduceAlternateArmed(
        state: OneShotAlternateState,
        event: OneShotAlternateEvent
    ): OneShotAlternateState =
        when (event) {
            OneShotAlternateEvent.RECOVERY_RESET_THRESHOLD_REACHED ->
                OneShotAlternateState(
                    phase = OneShotAlternatePhase.ALTERNATE_ARMED,
                    recoveryResetArmed = true
                )

            OneShotAlternateEvent.NORMAL_ALTERNATE_COMPLETION -> {
                if (state.recoveryResetArmed) OneShotAlternateState()
                else OneShotAlternateState(OneShotAlternatePhase.ALTERNATE_ARMED)
            }
            OneShotAlternateEvent.ALTERNATE_CANCELLATION ->
                OneShotAlternateState(OneShotAlternatePhase.ALTERNATE_RECOVERY)

            else -> OneShotAlternateState(OneShotAlternatePhase.ALTERNATE_ARMED)
        }

    private fun reduceAlternateRecovery(
        state: OneShotAlternateState,
        event: OneShotAlternateEvent
    ): OneShotAlternateState = when (event) {
        OneShotAlternateEvent.RECOVERY_RESET_THRESHOLD_REACHED ->
            state.copy(recoveryResetArmed = true)

        OneShotAlternateEvent.RECOVERY_INTENTIONAL_RELEASE -> {
            if (state.recoveryResetArmed) OneShotAlternateState() else state
        }

        OneShotAlternateEvent.CANCELED_RESET -> state.copy(recoveryResetArmed = false)
        OneShotAlternateEvent.RECOVERY_SHORT_COMPLETION ->
            state.copy(recoveryResetArmed = false)

        else -> state
    }
}
