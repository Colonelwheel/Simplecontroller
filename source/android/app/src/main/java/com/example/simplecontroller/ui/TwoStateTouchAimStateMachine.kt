package com.example.simplecontroller.ui

import com.example.simplecontroller.model.TouchAimShootBehavior
import kotlin.math.pow

enum class TwoStateTouchAimState { AIM, SHOOT }

enum class TwoStateTouchAimTermination {
    FINGER_LIFT,
    CANCEL,
    POINTER_LOSS,
    APP_PAUSE,
    EDIT_MODE,
    CONTROL_DELETED,
    LAYOUT_CHANGE,
    CONNECTION_FAILURE,
    RELEASE_ALL
}

enum class TwoStateTouchAimAction {
    HOLD_AIM_PAYLOAD,
    RELEASE_AIM_PAYLOAD,
    HOLD_SHOOT_PAYLOAD,
    RELEASE_SHOOT_PAYLOAD,
    PRESS_SHOOT_PAYLOAD
}

data class TwoStateTouchAimConfig(
    val shootOnThreshold: Float,
    val shootOffThreshold: Float,
    val smoothing: Float,
    val enterShootConfirmationMs: Long,
    val returnToAimConfirmationMs: Long,
    val shootBehavior: TouchAimShootBehavior,
    val hasAimPayload: Boolean,
    val keepAimPayloadWhileShooting: Boolean,
    val hasShootPayload: Boolean
)

/** Pure two-state hysteresis/confirmation machine. It owns no Android callbacks. */
class TwoStateTouchAimStateMachine(private val config: TwoStateTouchAimConfig) {
    var state: TwoStateTouchAimState = TwoStateTouchAimState.AIM
        private set
    var filteredScore: Float = 0f
        private set
    var contactActive: Boolean = false
        private set

    private var hasScore = false
    private var lastScoreTimeMs = 0L
    private var enterCandidateSince: Long? = null
    private var returnCandidateSince: Long? = null
    private var returnPressArmed = false
    private var aimPayloadHeld = false
    private var shootPayloadHeld = false

    fun onContactStarted(timeMs: Long): List<TwoStateTouchAimAction> {
        if (contactActive) terminate(TwoStateTouchAimTermination.POINTER_LOSS)
        contactActive = true
        state = TwoStateTouchAimState.AIM
        hasScore = false
        lastScoreTimeMs = timeMs
        filteredScore = 0f
        enterCandidateSince = null
        returnCandidateSince = null
        returnPressArmed = false
        return if (config.hasAimPayload) {
            aimPayloadHeld = true
            listOf(TwoStateTouchAimAction.HOLD_AIM_PAYLOAD)
        } else {
            emptyList()
        }
    }

    fun onScore(rawScore: Float, timeMs: Long): List<TwoStateTouchAimAction> {
        if (!contactActive || !rawScore.isFinite()) return emptyList()
        val referenceAlpha = config.smoothing.coerceIn(0.05f, 1f)
        val elapsedMs = (timeMs - lastScoreTimeMs).coerceAtLeast(0L)
        val alpha = if (!hasScore || referenceAlpha >= 1f) 1f else {
            (1f - (1f - referenceAlpha).pow(elapsedMs / SMOOTHING_REFERENCE_MS)).coerceIn(0f, 1f)
        }
        filteredScore = if (hasScore) filteredScore + alpha * (rawScore - filteredScore) else rawScore
        hasScore = true
        lastScoreTimeMs = timeMs
        return when (state) {
            TwoStateTouchAimState.AIM -> evaluateEnter(timeMs)
            TwoStateTouchAimState.SHOOT -> evaluateReturn(timeMs)
        }
    }

    /** Deadline for a stationary-contact confirmation; callers should schedule [onTime]. */
    fun nextDeadlineMs(): Long? = when (state) {
        TwoStateTouchAimState.AIM -> enterCandidateSince?.plus(
            config.enterShootConfirmationMs.coerceAtLeast(0L)
        )
        TwoStateTouchAimState.SHOOT -> returnCandidateSince?.plus(
            config.returnToAimConfirmationMs.coerceAtLeast(0L)
        )
    }

    /** Re-evaluates the last filtered score without applying smoothing to a duplicate sample. */
    fun onTime(timeMs: Long): List<TwoStateTouchAimAction> {
        if (!contactActive || !hasScore) return emptyList()
        return when (state) {
            TwoStateTouchAimState.AIM -> evaluateEnter(timeMs)
            TwoStateTouchAimState.SHOOT -> evaluateReturn(timeMs)
        }
    }

    fun terminate(reason: TwoStateTouchAimTermination): List<TwoStateTouchAimAction> {
        if (!contactActive && !aimPayloadHeld && !shootPayloadHeld) return emptyList()
        val actions = mutableListOf<TwoStateTouchAimAction>()
        if (shootPayloadHeld) actions += TwoStateTouchAimAction.RELEASE_SHOOT_PAYLOAD
        if (aimPayloadHeld) actions += TwoStateTouchAimAction.RELEASE_AIM_PAYLOAD
        contactActive = false
        state = TwoStateTouchAimState.AIM
        hasScore = false
        lastScoreTimeMs = 0L
        filteredScore = 0f
        enterCandidateSince = null
        returnCandidateSince = null
        returnPressArmed = false
        aimPayloadHeld = false
        shootPayloadHeld = false
        // A terminal event never completes a return-to-Aim action.
        @Suppress("UNUSED_VARIABLE") val documentedReason = reason
        return actions
    }

    private fun evaluateEnter(timeMs: Long): List<TwoStateTouchAimAction> {
        if (filteredScore < config.shootOnThreshold) {
            enterCandidateSince = null
            return emptyList()
        }
        val since = enterCandidateSince ?: timeMs.also { enterCandidateSince = it }
        if (timeMs - since < config.enterShootConfirmationMs.coerceAtLeast(0L)) return emptyList()

        state = TwoStateTouchAimState.SHOOT
        enterCandidateSince = null
        returnCandidateSince = null
        val actions = mutableListOf<TwoStateTouchAimAction>()
        if (!config.keepAimPayloadWhileShooting && aimPayloadHeld) {
            aimPayloadHeld = false
            actions += TwoStateTouchAimAction.RELEASE_AIM_PAYLOAD
        }
        when (config.shootBehavior) {
            TouchAimShootBehavior.HOLD_WHILE_ABOVE -> if (config.hasShootPayload) {
                shootPayloadHeld = true
                actions += TwoStateTouchAimAction.HOLD_SHOOT_PAYLOAD
            }
            TouchAimShootBehavior.PRESS_ON_ENTER -> if (config.hasShootPayload) {
                actions += TwoStateTouchAimAction.PRESS_SHOOT_PAYLOAD
            }
            TouchAimShootBehavior.PRESS_ON_RETURN_TO_AIM -> returnPressArmed = true
        }
        return actions
    }

    private fun evaluateReturn(timeMs: Long): List<TwoStateTouchAimAction> {
        if (filteredScore > config.shootOffThreshold) {
            returnCandidateSince = null
            return emptyList()
        }
        val since = returnCandidateSince ?: timeMs.also { returnCandidateSince = it }
        if (timeMs - since < config.returnToAimConfirmationMs.coerceAtLeast(0L)) return emptyList()

        state = TwoStateTouchAimState.AIM
        returnCandidateSince = null
        enterCandidateSince = null
        val actions = mutableListOf<TwoStateTouchAimAction>()
        if (shootPayloadHeld) {
            shootPayloadHeld = false
            actions += TwoStateTouchAimAction.RELEASE_SHOOT_PAYLOAD
        }
        if (returnPressArmed && config.hasShootPayload) {
            returnPressArmed = false
            actions += TwoStateTouchAimAction.PRESS_SHOOT_PAYLOAD
        }
        if (config.hasAimPayload && !aimPayloadHeld) {
            aimPayloadHeld = true
            actions += TwoStateTouchAimAction.HOLD_AIM_PAYLOAD
        }
        return actions
    }

    companion object {
        /** Stored smoothing is the EMA alpha for a 20 ms reference interval. */
        private const val SMOOTHING_REFERENCE_MS = 20f
    }
}
