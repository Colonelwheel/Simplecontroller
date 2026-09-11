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
    WAITING_TO_FIRE
}

/** Explicit state machine for one Button Aim gesture. Aim and payload state are independent. */
class ButtonAimHandler(
    private val model: Control,
    private val aimOutput: AimOutputSession,
    private val isLatched: () -> Boolean,
    private val setLatched: (Boolean) -> Unit,
    private val setPressed: (Boolean) -> Unit,
    private val firePayload: () -> Unit,
    private val releasePayload: () -> Unit,
    private val startTurbo: () -> Unit,
    private val stopTurbo: () -> Unit,
    private val isGlobalHold: () -> Boolean,
    private val isGlobalTurbo: () -> Boolean,
    private val shouldSkipImmediateRelease: () -> Boolean,
    private val vibrate: (Long) -> Unit,
    private val onStateChanged: () -> Unit
) {
    private enum class GestureState { IDLE, IMMEDIATE_ACTIVE, DELAYED_ARMED, WAITING_TO_FIRE }

    private val handler = Handler(Looper.getMainLooper())
    private var gestureState = GestureState.IDLE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var holdThresholdReached = false
    private var payloadHeld = false
    private var holdThresholdRunnable: Runnable? = null
    private var delayedFireRunnable: Runnable? = null
    private var finiteReleaseRunnable: Runnable? = null

    var visualState: ButtonAimVisualState = ButtonAimVisualState.IDLE
        private set

    fun hasRuntimeState(): Boolean =
        gestureState != GestureState.IDLE || payloadHeld || delayedFireRunnable != null ||
            finiteReleaseRunnable != null || aimOutput.isActive()

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startGesture(event)
            MotionEvent.ACTION_MOVE -> updateGesture(event)
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) cancelForSafety()
            }
            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) completeGesture(event)
                else cancelForSafety()
            }
            MotionEvent.ACTION_CANCEL -> cancelForSafety()
        }
    }

    fun cancelForSafety() {
        cancelTimers()
        stopTurbo()
        setPressed(false)

        val latched = isLatched()
        if (latched) setLatched(false)
        if (payloadHeld || latched) {
            releasePayload()
        }
        payloadHeld = false

        aimOutput.finish(flushMouse = false)
        activePointerId = MotionEvent.INVALID_POINTER_ID
        holdThresholdReached = false
        gestureState = GestureState.IDLE
        setVisualState(ButtonAimVisualState.IDLE)
    }

    private fun startGesture(event: MotionEvent) {
        // A second touch during a configured post-release delay is a safe one-finger cancel.
        if (gestureState != GestureState.IDLE || delayedFireRunnable != null || finiteReleaseRunnable != null) {
            cancelForSafety()
        }

        if (isLatched()) {
            setPressed(false)
            setLatched(false)
            releasePayload()
            payloadHeld = false
            if (model.buttonAimHaptics) vibrate(35L)
            setVisualState(ButtonAimVisualState.IDLE)
            return
        }

        activePointerId = event.getPointerId(event.actionIndex)
        holdThresholdReached = false
        setPressed(true)
        if (model.buttonAimHaptics) vibrate(30L)

        val aimOwnershipValid = aimOutput.begin(
            pointerId = activePointerId,
            x = event.getX(event.actionIndex),
            y = event.getY(event.actionIndex),
            eventTime = event.eventTime,
            newConfig = model.buttonAimConfig()
        )
        if (!aimOwnershipValid) {
            // First active stick pointer keeps ownership. An unowned delayed surface must not
            // arm or fire its payload, and an immediate surface must not activate either.
            cancelForSafety()
            return
        }

        if (model.buttonAimPayloadTiming == ButtonAimPayloadTiming.IMMEDIATE) {
            gestureState = GestureState.IMMEDIATE_ACTIVE
            setVisualState(ButtonAimVisualState.AIMING)
            if (isGlobalTurbo()) {
                startTurbo()
                payloadHeld = true
            } else {
                if (isGlobalHold() && !model.holdToggle) setLatched(true)
                firePayload()
                payloadHeld = true
                if (model.holdToggle) scheduleHoldThreshold(latchImmediately = true)
            }
        } else {
            // Turbo is deliberately ignored for delayed Button Aim payloads.
            gestureState = GestureState.DELAYED_ARMED
            setVisualState(ButtonAimVisualState.ARMED)
            if (model.holdToggle) scheduleHoldThreshold(latchImmediately = false)
        }
    }

    private fun updateGesture(event: MotionEvent) {
        if (gestureState == GestureState.IDLE || gestureState == GestureState.WAITING_TO_FIRE) return
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) {
            cancelForSafety()
            return
        }
        if (!aimOutput.update(
                activePointerId,
                event.getX(pointerIndex),
                event.getY(pointerIndex),
                event.eventTime
            )
        ) {
            cancelForSafety()
        }
    }

    private fun completeGesture(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) {
            cancelForSafety()
            return
        }

        // Force the last relative mouse sample/stick coordinate out before payload ordering.
        aimOutput.update(
            activePointerId,
            event.getX(pointerIndex),
            event.getY(pointerIndex),
            event.eventTime,
            forceSend = true
        )
        cancelHoldThreshold()
        setPressed(false)

        when (gestureState) {
            GestureState.IMMEDIATE_ACTIVE -> {
                stopTurbo()
                if (!isLatched()) {
                    if (!shouldSkipImmediateRelease()) releasePayload()
                    // Pulse payloads own their finite release timer inside ControlViewHelper.
                    payloadHeld = false
                }
                aimOutput.finish(flushMouse = false)
                resetGestureOnly()
            }

            GestureState.DELAYED_ARMED -> {
                val latchOnFire = isGlobalHold() || (model.holdToggle && holdThresholdReached)
                val delayMs = model.buttonAimReleaseDelayMs.coerceAtLeast(0L)
                if (delayMs == 0L) {
                    fireDelayedPayload(latchOnFire)
                    aimOutput.finish(flushMouse = false)
                    resetGestureOnly()
                } else {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    aimOutput.detachPointerKeepingOutput()
                    gestureState = GestureState.WAITING_TO_FIRE
                    setVisualState(ButtonAimVisualState.WAITING_TO_FIRE)
                    delayedFireRunnable = Runnable {
                        delayedFireRunnable = null
                        if (gestureState != GestureState.WAITING_TO_FIRE) return@Runnable
                        fireDelayedPayload(latchOnFire)
                        aimOutput.finish(flushMouse = false)
                        resetGestureOnly()
                    }.also { handler.postDelayed(it, delayMs) }
                }
            }

            GestureState.IDLE, GestureState.WAITING_TO_FIRE -> cancelForSafety()
        }
    }

    private fun fireDelayedPayload(latchOnFire: Boolean) {
        if (latchOnFire) setLatched(true)
        firePayload()
        payloadHeld = true
        if (!latchOnFire) scheduleFiniteRelease()
    }

    private fun scheduleHoldThreshold(latchImmediately: Boolean) {
        holdThresholdRunnable = Runnable {
            holdThresholdRunnable = null
            if (gestureState != GestureState.IMMEDIATE_ACTIVE &&
                gestureState != GestureState.DELAYED_ARMED
            ) return@Runnable

            holdThresholdReached = true
            if (latchImmediately) setLatched(true)
            if (model.buttonAimHaptics) vibrate(80L)
            setVisualState(ButtonAimVisualState.LATCH_READY)
        }.also { handler.postDelayed(it, model.holdDurationMs.coerceAtLeast(0L)) }
    }

    private fun scheduleFiniteRelease() {
        finiteReleaseRunnable = Runnable {
            finiteReleaseRunnable = null
            if (!isLatched() && payloadHeld) releasePayload()
            payloadHeld = false
        }.also { handler.postDelayed(it, FINITE_PRESS_DURATION_MS) }
    }

    private fun cancelHoldThreshold() {
        holdThresholdRunnable?.let(handler::removeCallbacks)
        holdThresholdRunnable = null
    }

    private fun cancelTimers() {
        holdThresholdRunnable?.let(handler::removeCallbacks)
        delayedFireRunnable?.let(handler::removeCallbacks)
        finiteReleaseRunnable?.let(handler::removeCallbacks)
        holdThresholdRunnable = null
        delayedFireRunnable = null
        finiteReleaseRunnable = null
    }

    private fun resetGestureOnly() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        holdThresholdReached = false
        gestureState = GestureState.IDLE
        setVisualState(ButtonAimVisualState.IDLE)
    }

    private fun setVisualState(newState: ButtonAimVisualState) {
        visualState = newState
        onStateChanged()
    }

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
