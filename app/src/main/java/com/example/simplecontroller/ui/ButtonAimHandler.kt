package com.example.simplecontroller.ui

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import com.example.simplecontroller.model.ButtonAimPayloadTiming
import com.example.simplecontroller.model.Control

enum class ButtonAimVisualState {
    IDLE,
    AIMING,
    ARMED,
    LATCH_READY,
    WAITING_TO_FIRE,
    ALTERNATE_ARMED,
    ALTERNATE_AIMING,
    ALTERNATE_WAITING_TO_FIRE,
    ALTERNATE_RECOVERY,
    ALTERNATE_RESET_READY
}

/** Button Aim gesture state plus a separate, explicit one-shot alternate phase. */
class ButtonAimHandler(
    private val model: Control,
    private val aimOutput: AimOutputSession,
    private val payloadExecutor: ButtonAimPayloadExecutor,
    private val isLatched: () -> Boolean,
    private val setLatched: (Boolean) -> Unit,
    private val setPressed: (Boolean) -> Unit,
    private val fireLegacyPayload: () -> Unit,
    private val releaseLegacyPayload: () -> Unit,
    private val startTurbo: () -> Unit,
    private val stopTurbo: () -> Unit,
    private val isGlobalHold: () -> Boolean,
    private val isGlobalTurbo: () -> Boolean,
    private val shouldSkipImmediateRelease: () -> Boolean,
    private val vibrate: (Long) -> Unit,
    private val onPayloadError: (String) -> Unit,
    private val onStateChanged: () -> Unit
) {
    private enum class GestureState {
        IDLE,
        BASE_IMMEDIATE_ACTIVE,
        BASE_DELAYED_ARMED,
        BASE_WAITING_TO_FIRE,
        ALTERNATE_IMMEDIATE_ACTIVE,
        ALTERNATE_DELAYED_ARMED,
        ALTERNATE_FINITE_RELEASE
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gestureState = GestureState.IDLE
    private var phaseState = OneShotAlternateState()
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var holdThresholdReached = false
    private var legacyPayloadHeld = false
    private var legacyTurboActive = false
    private var baseLease: ButtonAimPayloadLease? = null
    private var alternateLease: ButtonAimPayloadLease? = null
    private var holdThresholdRunnable: Runnable? = null
    private var recoveryResetRunnable: Runnable? = null
    private var delayedFireRunnable: Runnable? = null
    private var finiteReleaseRunnable: Runnable? = null

    var visualState: ButtonAimVisualState = ButtonAimVisualState.IDLE
        private set

    fun hasRuntimeState(): Boolean =
        phaseState.phase != OneShotAlternatePhase.BASE ||
            gestureState != GestureState.IDLE || legacyPayloadHeld || legacyTurboActive ||
            baseLease != null || alternateLease != null ||
            payloadExecutor.activeLeaseCount() != 0 || delayedFireRunnable != null ||
            finiteReleaseRunnable != null || recoveryResetRunnable != null || aimOutput.isActive()

    fun runtimeLabel(): String? = if (phaseState.phase == OneShotAlternatePhase.BASE) {
        null
    } else {
        model.buttonAimAlternateDisplayName.trim().ifEmpty {
            model.buttonAimAlternatePayload.trim()
        }
    }

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startGesture(event)
            MotionEvent.ACTION_MOVE -> updateGesture(event)
            MotionEvent.ACTION_POINTER_UP -> {
                if (activePointerId != MotionEvent.INVALID_POINTER_ID &&
                    event.getPointerId(event.actionIndex) == activePointerId
                ) {
                    cancelUnexpectedGesture()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    completeGesture(event)
                } else {
                    cancelUnexpectedGesture()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (activePointerId != MotionEvent.INVALID_POINTER_ID) cancelUnexpectedGesture()
            }
        }
    }

    /** Lifecycle/profile/property cleanup. Unlike an alternate touch cancel, this always resets. */
    fun hardReset() {
        cancelTimers()
        stopTurbo()
        if (legacyPayloadHeld || legacyTurboActive) {
            releaseLegacyPayload()
        }
        legacyPayloadHeld = false
        legacyTurboActive = false
        payloadExecutor.releaseAllLeases()
        baseLease = null
        alternateLease = null
        setPressed(false)
        if (isLatched()) setLatched(false)
        aimOutput.finish(flushMouse = false)
        activePointerId = MotionEvent.INVALID_POINTER_ID
        holdThresholdReached = false
        gestureState = GestureState.IDLE
        phaseState = OneShotAlternateStateMachine.reduce(
            phaseState,
            OneShotAlternateEvent.HARD_RESET
        )
        setVisualState(ButtonAimVisualState.IDLE)
    }

    private fun startGesture(event: MotionEvent) {
        when (gestureState) {
            GestureState.BASE_WAITING_TO_FIRE -> cancelBaseGesture()
            GestureState.ALTERNATE_FINITE_RELEASE -> finishPendingAlternateRelease()
            GestureState.IDLE -> Unit
            else -> cancelUnexpectedGesture()
        }

        val alternate = isAlternatePhase()
        if (!alternate && isLatched()) {
            setPressed(false)
            setLatched(false)
            if (usesOneShotExecutor()) releaseBaseOwnership() else {
                releaseLegacyPayload()
                legacyPayloadHeld = false
            }
            if (model.buttonAimHaptics) vibrate(35L)
            setVisualState(ButtonAimVisualState.IDLE)
            return
        }

        activePointerId = event.getPointerId(event.actionIndex)
        holdThresholdReached = false
        setPressed(true)
        if (if (alternate) model.buttonAimAlternateHaptics else model.buttonAimHaptics) vibrate(30L)

        val ownsAim = aimOutput.begin(
            pointerId = activePointerId,
            x = event.getX(event.actionIndex),
            y = event.getY(event.actionIndex),
            eventTime = event.eventTime,
            newConfig = if (alternate) model.buttonAimAlternateConfig() else model.buttonAimConfig()
        )
        if (!ownsAim) {
            cancelUnexpectedGesture()
            return
        }

        if (alternate) startAlternateGesture() else startBaseGesture()
    }

    private fun startBaseGesture() {
        if (model.buttonAimPayloadTiming == ButtonAimPayloadTiming.IMMEDIATE) {
            gestureState = GestureState.BASE_IMMEDIATE_ACTIVE
            setVisualState(ButtonAimVisualState.AIMING)
            if (isGlobalTurbo()) {
                startTurbo()
                legacyTurboActive = true
                legacyPayloadHeld = true
            } else if (usesOneShotExecutor()) {
                if (isGlobalHold() && !model.holdToggle) setLatched(true)
                val pulseMode = if (isLatched()) ButtonAimPulseMode.REPEAT else ButtonAimPulseMode.ONCE
                when (val result = payloadExecutor.activate(
                    ButtonAimPayloadOwner.BASE,
                    model.payload,
                    pulseMode
                )) {
                    is ButtonAimPayloadActivationResult.Activated -> baseLease = result.lease
                    ButtonAimPayloadActivationResult.ReleaseAll -> {
                        hardReset()
                        return
                    }
                    is ButtonAimPayloadActivationResult.Invalid -> {
                        onPayloadError(result.reason)
                        hardReset()
                        return
                    }
                }
                if (model.holdToggle) scheduleHoldThreshold(latchImmediately = true)
            } else {
                if (isGlobalHold() && !model.holdToggle) setLatched(true)
                fireLegacyPayload()
                legacyPayloadHeld = true
                if (model.holdToggle) scheduleHoldThreshold(latchImmediately = true)
            }
        } else {
            // Turbo is deliberately ignored for delayed Button Aim payloads.
            gestureState = GestureState.BASE_DELAYED_ARMED
            setVisualState(ButtonAimVisualState.ARMED)
            if (model.holdToggle) scheduleHoldThreshold(latchImmediately = false)
        }
    }

    private fun startAlternateGesture() {
        scheduleAlternateResetThreshold()

        if (model.buttonAimAlternatePayloadTiming == ButtonAimPayloadTiming.IMMEDIATE) {
            gestureState = GestureState.ALTERNATE_IMMEDIATE_ACTIVE
            when (val result = payloadExecutor.activate(
                ButtonAimPayloadOwner.ALTERNATE,
                model.buttonAimAlternatePayload
            )) {
                is ButtonAimPayloadActivationResult.Activated -> alternateLease = result.lease
                ButtonAimPayloadActivationResult.ReleaseAll -> {
                    hardReset()
                    return
                }
                is ButtonAimPayloadActivationResult.Invalid -> {
                    onPayloadError(result.reason)
                    hardReset()
                    return
                }
            }
            updateAlternateVisual(active = true)
        } else {
            gestureState = GestureState.ALTERNATE_DELAYED_ARMED
            updateAlternateVisual(waitingToFire = true)
        }
    }

    private fun updateGesture(event: MotionEvent) {
        if (gestureState == GestureState.IDLE ||
            gestureState == GestureState.BASE_WAITING_TO_FIRE ||
            gestureState == GestureState.ALTERNATE_FINITE_RELEASE
        ) return

        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) {
            cancelUnexpectedGesture()
            return
        }
        if (!aimOutput.update(
                activePointerId,
                event.getX(pointerIndex),
                event.getY(pointerIndex),
                event.eventTime
            )
        ) cancelUnexpectedGesture()
    }

    private fun completeGesture(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) {
            cancelUnexpectedGesture()
            return
        }

        aimOutput.update(
            activePointerId,
            event.getX(pointerIndex),
            event.getY(pointerIndex),
            event.eventTime,
            forceSend = true
        )
        cancelHoldThreshold()
        cancelRecoveryResetThreshold()
        setPressed(false)

        when (gestureState) {
            GestureState.BASE_IMMEDIATE_ACTIVE -> completeImmediateBase()
            GestureState.BASE_DELAYED_ARMED -> completeDelayedBase()
            GestureState.ALTERNATE_IMMEDIATE_ACTIVE -> completeImmediateAlternate()
            GestureState.ALTERNATE_DELAYED_ARMED -> completeDelayedAlternate()
            GestureState.IDLE,
            GestureState.BASE_WAITING_TO_FIRE,
            GestureState.ALTERNATE_FINITE_RELEASE -> cancelUnexpectedGesture()
        }
    }

    private fun completeImmediateBase() {
        stopTurbo()
        if (legacyTurboActive) {
            if (!isLatched() && (!shouldSkipImmediateRelease() || usesOneShotExecutor())) {
                // One-shot cannot leave a legacy pulse timer outside its ownership model.
                releaseLegacyPayload()
            }
            legacyTurboActive = false
            legacyPayloadHeld = false
        } else if (usesOneShotExecutor()) {
            if (!isLatched() && !shouldSkipImmediateRelease()) {
                payloadExecutor.release(baseLease)
                baseLease = null
            }
        } else if (!isLatched()) {
            if (!shouldSkipImmediateRelease()) releaseLegacyPayload()
            legacyPayloadHeld = false
        }

        aimOutput.finish(flushMouse = false)
        resetGestureOnly()
        armAlternateAfterSuccessfulBase()
    }

    private fun completeDelayedBase() {
        val latchOnFire = isGlobalHold() || (model.holdToggle && holdThresholdReached)
        val delayMs = model.buttonAimReleaseDelayMs.coerceAtLeast(0L)
        if (delayMs == 0L) {
            fireDelayedBase(latchOnFire)
            aimOutput.finish(flushMouse = false)
            resetGestureOnly(preservePresentation = true)
        } else {
            activePointerId = MotionEvent.INVALID_POINTER_ID
            aimOutput.detachPointerKeepingOutput()
            gestureState = GestureState.BASE_WAITING_TO_FIRE
            setVisualState(ButtonAimVisualState.WAITING_TO_FIRE)
            delayedFireRunnable = Runnable {
                delayedFireRunnable = null
                if (gestureState != GestureState.BASE_WAITING_TO_FIRE) return@Runnable
                fireDelayedBase(latchOnFire)
                aimOutput.finish(flushMouse = false)
                resetGestureOnly(preservePresentation = true)
            }.also { handler.postDelayed(it, delayMs) }
        }
    }

    private fun fireDelayedBase(latchOnFire: Boolean) {
        if (latchOnFire) setLatched(true)
        if (usesOneShotExecutor()) {
            val pulseMode = if (latchOnFire) ButtonAimPulseMode.REPEAT else ButtonAimPulseMode.ONCE
            when (val result = payloadExecutor.activate(
                ButtonAimPayloadOwner.BASE,
                model.payload,
                pulseMode
            )) {
                is ButtonAimPayloadActivationResult.Activated -> {
                    baseLease = result.lease
                    if (!latchOnFire) scheduleBaseFiniteRelease()
                    armAlternateAfterSuccessfulBase()
                }
                ButtonAimPayloadActivationResult.ReleaseAll -> hardReset()
                is ButtonAimPayloadActivationResult.Invalid -> {
                    onPayloadError(result.reason)
                    hardReset()
                }
            }
        } else {
            fireLegacyPayload()
            legacyPayloadHeld = true
            if (!latchOnFire) scheduleLegacyFiniteRelease()
        }
    }

    private fun completeImmediateAlternate() {
        val returnToBase = shouldReturnToBaseAfterAlternateGesture()
        releaseAlternateForCompletion(returnToBase)
        aimOutput.finish(flushMouse = false)
        resetGestureOnly(preservePresentation = true)
        finishAlternatePhase(returnToBase)
    }

    private fun completeDelayedAlternate() {
        val returnToBase = shouldReturnToBaseAfterAlternateGesture()
        when (val result = payloadExecutor.activate(
            ButtonAimPayloadOwner.ALTERNATE,
            model.buttonAimAlternatePayload
        )) {
            is ButtonAimPayloadActivationResult.Activated -> alternateLease = result.lease
            ButtonAimPayloadActivationResult.ReleaseAll -> {
                hardReset()
                return
            }
            is ButtonAimPayloadActivationResult.Invalid -> {
                onPayloadError(result.reason)
                hardReset()
                return
            }
        }

        // Required order: final aim, alternate press, center, finite release, then base release.
        aimOutput.finish(flushMouse = false)
        activePointerId = MotionEvent.INVALID_POINTER_ID
        holdThresholdReached = false
        gestureState = GestureState.ALTERNATE_FINITE_RELEASE
        updateAlternateVisual(waitingToFire = true)
        finiteReleaseRunnable = Runnable {
            finiteReleaseRunnable = null
            if (gestureState != GestureState.ALTERNATE_FINITE_RELEASE) return@Runnable
            releaseAlternateForCompletion(returnToBase)
            gestureState = GestureState.IDLE
            finishAlternatePhase(returnToBase)
        }.also { handler.postDelayed(it, FINITE_PRESS_DURATION_MS) }
    }

    private fun finishAlternatePhase(returnToBase: Boolean) {
        when (phaseState.phase) {
            OneShotAlternatePhase.ALTERNATE_ARMED -> {
                if (returnToBase) {
                    releaseBaseOwnership()
                    setLatched(false)
                }
                reducePhase(OneShotAlternateEvent.NORMAL_ALTERNATE_COMPLETION)
            }
            OneShotAlternatePhase.ALTERNATE_RECOVERY -> {
                if (returnToBase) {
                    releaseBaseOwnership()
                    setLatched(false)
                    reducePhase(OneShotAlternateEvent.RECOVERY_INTENTIONAL_RELEASE)
                } else {
                    reducePhase(OneShotAlternateEvent.RECOVERY_SHORT_COMPLETION)
                }
            }
            OneShotAlternatePhase.BASE -> Unit
        }
        updateIdleVisualForPhase()
    }

    private fun shouldReturnToBaseAfterAlternateGesture(): Boolean =
        phaseState.recoveryResetArmed

    private fun cancelUnexpectedGesture() {
        if (isAlternatePhase()) cancelAlternateGestureIntoRecovery() else cancelBaseGesture()
    }

    private fun cancelBaseGesture() {
        cancelTimers()
        stopTurbo()
        if (legacyPayloadHeld || legacyTurboActive) {
            releaseLegacyPayload()
        }
        legacyPayloadHeld = false
        legacyTurboActive = false
        payloadExecutor.releaseOwner(ButtonAimPayloadOwner.BASE)
        baseLease = null
        setPressed(false)
        if (isLatched()) setLatched(false)
        aimOutput.finish(flushMouse = false)
        activePointerId = MotionEvent.INVALID_POINTER_ID
        holdThresholdReached = false
        gestureState = GestureState.IDLE
        reducePhase(OneShotAlternateEvent.BASE_CANCELLATION)
        setVisualState(ButtonAimVisualState.IDLE)
    }

    private fun cancelAlternateGestureIntoRecovery() {
        cancelTimers()
        payloadExecutor.releaseOwner(ButtonAimPayloadOwner.ALTERNATE)
        alternateLease = null
        setPressed(false)
        aimOutput.finish(flushMouse = false)
        activePointerId = MotionEvent.INVALID_POINTER_ID
        holdThresholdReached = false
        gestureState = GestureState.IDLE
        reducePhase(
            if (phaseState.phase == OneShotAlternatePhase.ALTERNATE_RECOVERY) {
                OneShotAlternateEvent.CANCELED_RESET
            } else {
                OneShotAlternateEvent.ALTERNATE_CANCELLATION
            }
        )
        if (model.buttonAimAlternateHaptics) vibrate(120L)
        setVisualState(ButtonAimVisualState.ALTERNATE_RECOVERY)
    }

    private fun armAlternateAfterSuccessfulBase() {
        if (!usesOneShotExecutor()) return
        payloadExecutor.validationError(model.buttonAimAlternatePayload)?.let { reason ->
            onPayloadError(reason)
            return
        }
        reducePhase(OneShotAlternateEvent.SUCCESSFUL_BASE_COMPLETION)
        if (model.buttonAimAlternateHaptics) vibrate(55L)
        updateIdleVisualForPhase()
    }

    private fun scheduleHoldThreshold(latchImmediately: Boolean) {
        holdThresholdRunnable = Runnable {
            holdThresholdRunnable = null
            if (gestureState != GestureState.BASE_IMMEDIATE_ACTIVE &&
                gestureState != GestureState.BASE_DELAYED_ARMED
            ) return@Runnable
            holdThresholdReached = true
            if (latchImmediately) setLatched(true)
            if (model.buttonAimHaptics) vibrate(80L)
            setVisualState(ButtonAimVisualState.LATCH_READY)
        }.also { handler.postDelayed(it, model.holdDurationMs.coerceAtLeast(0L)) }
    }

    private fun scheduleAlternateResetThreshold() {
        recoveryResetRunnable = Runnable {
            recoveryResetRunnable = null
            if (phaseState.phase == OneShotAlternatePhase.BASE ||
                (gestureState != GestureState.ALTERNATE_IMMEDIATE_ACTIVE &&
                    gestureState != GestureState.ALTERNATE_DELAYED_ARMED)
            ) return@Runnable
            reducePhase(OneShotAlternateEvent.RECOVERY_RESET_THRESHOLD_REACHED)
            if (model.buttonAimAlternateHaptics) vibrate(100L)
            setVisualState(ButtonAimVisualState.ALTERNATE_RESET_READY)
        }.also {
            handler.postDelayed(it, model.buttonAimAlternateResetHoldDurationMs.coerceAtLeast(0L))
        }
    }

    private fun scheduleBaseFiniteRelease() {
        finiteReleaseRunnable = Runnable {
            finiteReleaseRunnable = null
            if (!isLatched()) {
                payloadExecutor.release(baseLease)
                baseLease = null
            }
        }.also { handler.postDelayed(it, FINITE_PRESS_DURATION_MS) }
    }

    private fun scheduleLegacyFiniteRelease() {
        finiteReleaseRunnable = Runnable {
            finiteReleaseRunnable = null
            if (!isLatched() && legacyPayloadHeld) releaseLegacyPayload()
            legacyPayloadHeld = false
        }.also { handler.postDelayed(it, FINITE_PRESS_DURATION_MS) }
    }

    private fun finishPendingAlternateRelease() {
        finiteReleaseRunnable?.let(handler::removeCallbacks)
        finiteReleaseRunnable = null
        if (gestureState != GestureState.ALTERNATE_FINITE_RELEASE) return
        val returnToBase = shouldReturnToBaseAfterAlternateGesture()
        releaseAlternateForCompletion(returnToBase)
        gestureState = GestureState.IDLE
        finishAlternatePhase(returnToBase)
    }

    /** Avoid a one-frame base restoration when normal completion is releasing both owners. */
    private fun releaseAlternateForCompletion(returnToBase: Boolean) {
        if (returnToBase) {
            payloadExecutor.releaseTogether(alternateLease, baseLease)
            alternateLease = null
            baseLease = null
        } else {
            payloadExecutor.release(alternateLease)
            alternateLease = null
        }
    }

    private fun releaseBaseOwnership() {
        payloadExecutor.releaseOwner(ButtonAimPayloadOwner.BASE)
        baseLease = null
    }

    private fun cancelHoldThreshold() {
        holdThresholdRunnable?.let(handler::removeCallbacks)
        holdThresholdRunnable = null
    }

    private fun cancelRecoveryResetThreshold() {
        recoveryResetRunnable?.let(handler::removeCallbacks)
        recoveryResetRunnable = null
    }

    private fun cancelTimers() {
        holdThresholdRunnable?.let(handler::removeCallbacks)
        recoveryResetRunnable?.let(handler::removeCallbacks)
        delayedFireRunnable?.let(handler::removeCallbacks)
        finiteReleaseRunnable?.let(handler::removeCallbacks)
        holdThresholdRunnable = null
        recoveryResetRunnable = null
        delayedFireRunnable = null
        finiteReleaseRunnable = null
    }

    private fun resetGestureOnly(preservePresentation: Boolean = false) {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        holdThresholdReached = false
        gestureState = GestureState.IDLE
        if (!preservePresentation) setVisualState(ButtonAimVisualState.IDLE)
    }

    private fun reducePhase(event: OneShotAlternateEvent) {
        phaseState = OneShotAlternateStateMachine.reduce(phaseState, event)
        onStateChanged()
    }

    private fun updateIdleVisualForPhase() {
        setVisualState(
            when (phaseState.phase) {
                OneShotAlternatePhase.BASE -> ButtonAimVisualState.IDLE
                OneShotAlternatePhase.ALTERNATE_ARMED -> ButtonAimVisualState.ALTERNATE_ARMED
                OneShotAlternatePhase.ALTERNATE_RECOVERY -> ButtonAimVisualState.ALTERNATE_RECOVERY
            }
        )
    }

    private fun updateAlternateVisual(active: Boolean = false, waitingToFire: Boolean = false) {
        setVisualState(
            when {
                phaseState.recoveryResetArmed -> ButtonAimVisualState.ALTERNATE_RESET_READY
                waitingToFire -> ButtonAimVisualState.ALTERNATE_WAITING_TO_FIRE
                active -> ButtonAimVisualState.ALTERNATE_AIMING
                phaseState.phase == OneShotAlternatePhase.ALTERNATE_RECOVERY ->
                    ButtonAimVisualState.ALTERNATE_RECOVERY
                else -> ButtonAimVisualState.ALTERNATE_ARMED
            }
        )
    }

    private fun setVisualState(newState: ButtonAimVisualState) {
        visualState = newState
        onStateChanged()
    }

    private fun isAlternatePhase(): Boolean =
        usesOneShotExecutor() && phaseState.phase != OneShotAlternatePhase.BASE

    private fun usesOneShotExecutor(): Boolean = model.buttonAimOneShotAlternateEnabled

    companion object {
        private const val FINITE_PRESS_DURATION_MS = 90L
    }
}

private fun Control.buttonAimConfig(): AimOutputConfig = AimOutputConfig(
    output = buttonAimOutput,
    sensitivity = buttonAimSensitivity,
    invertY = buttonAimInvertY,
    stickProfile = buttonAimStickProfile,
    mouseProfile = buttonAimMouseProfile,
    stickFullDisplacementPx = buttonAimStickFullDisplacementPx,
    stickDeadzonePx = buttonAimStickDeadzonePx,
    originMode = AimOriginMode.INITIAL_TOUCH,
    sendNeutralOnBegin = true
)

private fun Control.buttonAimAlternateConfig(): AimOutputConfig = AimOutputConfig(
    output = buttonAimAlternateOutput,
    sensitivity = buttonAimAlternateSensitivity,
    invertY = buttonAimAlternateInvertY,
    stickProfile = buttonAimAlternateStickProfile,
    mouseProfile = buttonAimAlternateMouseProfile,
    stickFullDisplacementPx = buttonAimAlternateStickFullDisplacementPx,
    stickDeadzonePx = buttonAimAlternateStickDeadzonePx,
    originMode = AimOriginMode.INITIAL_TOUCH,
    sendNeutralOnBegin = true
)
