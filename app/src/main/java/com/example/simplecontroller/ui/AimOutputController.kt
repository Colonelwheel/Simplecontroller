package com.example.simplecontroller.ui

import android.os.Handler
import android.os.Looper
import com.example.simplecontroller.model.ButtonAimMouseProfile
import com.example.simplecontroller.model.ButtonAimStickProfile
import com.example.simplecontroller.model.TouchAimOutput
import com.example.simplecontroller.net.UdpClient
import kotlin.math.abs
import kotlin.math.hypot

enum class AimOriginMode { CONTROL_CENTER, INITIAL_TOUCH }

data class AimOutputConfig(
    val output: TouchAimOutput,
    val sensitivity: Float,
    val invertY: Boolean,
    val stickProfile: ButtonAimStickProfile,
    val mouseProfile: ButtonAimMouseProfile,
    val stickFullDisplacementPx: Float,
    val stickDeadzonePx: Float,
    val originMode: AimOriginMode,
    val sendNeutralOnBegin: Boolean = false
)

internal class DeliberateSwipeTracker(private val touchSlopPx: Float) {
    private var downX = 0f
    private var downY = 0f
    var crossed: Boolean = false
        private set

    fun reset(x: Float, y: Float) {
        downX = x
        downY = y
        crossed = false
    }

    fun update(x: Float, y: Float): Boolean {
        if (!crossed && hypot(x - downX, y - downY) > touchSlopPx.coerceAtLeast(0f)) {
            crossed = true
        }
        return crossed
    }
}

internal fun shouldDeferButtonAimStick(autoCenter: Boolean, hasRetainedOwner: Boolean): Boolean =
    !autoCenter || hasRetainedOwner

internal class CallbackGeneration {
    private var value = 0

    fun invalidate() {
        value++
    }

    fun snapshot(): Int = value

    fun isCurrent(snapshot: Int): Boolean = snapshot == value
}

internal enum class ManualStickOwnerState { ACTIVE, DELAY_LOCKED, RETAINED, CENTERED_PENDING }

internal data class ManualStickOwnership(
    val owner: Any,
    val state: ManualStickOwnerState,
    val onYielded: (String) -> Unit
)

data class ManualStickAcquireResult(
    val acquired: Boolean,
    val replacedRetainedOwner: Boolean = false
)

internal class ManualStickOwnershipRegistry {
    private val owners = mutableMapOf<String, ManualStickOwnership>()

    fun acquire(
        stickName: String,
        owner: Any,
        state: ManualStickOwnerState,
        allowRetainedTakeover: Boolean,
        onYielded: (String) -> Unit
    ): Pair<ManualStickAcquireResult, ManualStickOwnership?> {
        val current = owners[stickName]
        if (current != null && current.owner !== owner) {
            if (!allowRetainedTakeover || current.state != ManualStickOwnerState.RETAINED) {
                return ManualStickAcquireResult(false) to null
            }
            owners[stickName] = ManualStickOwnership(owner, state, onYielded)
            return ManualStickAcquireResult(true, replacedRetainedOwner = true) to current
        }
        owners[stickName] = ManualStickOwnership(owner, state, onYielded)
        return ManualStickAcquireResult(true) to null
    }

    fun owns(stickName: String, owner: Any): Boolean = owners[stickName]?.owner === owner

    fun state(stickName: String): ManualStickOwnerState? = owners[stickName]?.state

    fun updateState(stickName: String, owner: Any, state: ManualStickOwnerState): Boolean {
        val current = owners[stickName] ?: return false
        if (current.owner !== owner) return false
        owners[stickName] = current.copy(state = state)
        return true
    }

    fun release(stickName: String, owner: Any): Boolean {
        if (owners[stickName]?.owner !== owner) return false
        owners.remove(stickName)
        return true
    }

    fun hasRetainedOwner(stickName: String): Boolean =
        owners[stickName]?.state == ManualStickOwnerState.RETAINED

    fun isOccupied(stickName: String): Boolean = owners.containsKey(stickName)
}

/**
 * First-active-gesture ownership for ordinary/manual analog stick output.
 * Directional button macros intentionally stay outside this arbiter because the receiver
 * already gives their STICK_MACRO packets higher priority.
 */
object ManualStickArbiter {
    private val registry = ManualStickOwnershipRegistry()
    private val rightStickSuppressionToken = Any()

    fun acquire(
        stickName: String,
        owner: Any,
        allowRetainedTakeover: Boolean = false,
        onYielded: (String) -> Unit = {}
    ): ManualStickAcquireResult {
        val canonical = canonicalStickName(stickName)
        val wasOccupied: Boolean
        val transition: Pair<ManualStickAcquireResult, ManualStickOwnership?>
        synchronized(this) {
            wasOccupied = registry.isOccupied(canonical)
            transition = registry.acquire(
                canonical,
                owner,
                ManualStickOwnerState.ACTIVE,
                allowRetainedTakeover,
                onYielded
            )
            if (transition.first.acquired && canonical == "STICK_R" && !wasOccupied) {
                UdpClient.setManualRightStickActive(rightStickSuppressionToken, true)
            }
        }
        if (!transition.first.acquired) return transition.first
        transition.second?.onYielded?.invoke(canonical)
        return transition.first
    }

    @Synchronized
    fun owns(stickName: String, owner: Any): Boolean =
        registry.owns(canonicalStickName(stickName), owner)

    @Synchronized
    fun hasRetainedOwner(stickName: String): Boolean =
        registry.hasRetainedOwner(canonicalStickName(stickName))

    @Synchronized
    internal fun setState(stickName: String, owner: Any, state: ManualStickOwnerState): Boolean =
        registry.updateState(canonicalStickName(stickName), owner, state)

    @Synchronized
    fun send(stickName: String, owner: Any, x: Float, y: Float): Boolean {
        val canonical = canonicalStickName(stickName)
        if (!registry.owns(canonical, owner)) return false
        UdpClient.sendStickPosition(canonical, x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
        return true
    }

    fun release(stickName: String, owner: Any, center: Boolean = true): Boolean {
        val canonical = canonicalStickName(stickName)
        val released = synchronized(this) {
            val didRelease = registry.release(canonical, owner)
            if (didRelease && center) UdpClient.sendStickPosition(canonical, 0f, 0f)
            if (didRelease && canonical == "STICK_R") {
                UdpClient.setManualRightStickActive(rightStickSuppressionToken, false)
            }
            didRelease
        }
        return released
    }

    fun canonicalStickName(raw: String): String {
        val upper = raw.trim().uppercase()
        return when {
            upper == "R" || upper == "RS" || upper == "RIGHT" || upper.contains("_R") -> "STICK_R"
            else -> "STICK_L"
        }
    }
}

/**
 * Reusable pointer-to-mouse/stick output session. Payload timing and latch state deliberately
 * live elsewhere so aim can always terminate without accidentally firing a delayed action.
 */
class AimOutputSession(
    private val ownerToken: Any,
    private val controlSize: () -> Pair<Float, Float>,
    private val beforeStickAcquire: (String) -> Unit = {},
    private val onRetainedStickChanged: (String?) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private var config: AimOutputConfig? = null
    private var sessionActive = false
    private var activePointerId = INVALID_POINTER_ID
    private var ownsStick = false
    private var ownerState = ManualStickOwnerState.ACTIVE
    private var originX = 0f
    private var originY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var previousMouseDx = 0f
    private var previousMouseDy = 0f
    private var lastAimSendTime = 0L
    private var lastStickX = 0f
    private var lastStickY = 0f
    private val resendGeneration = CallbackGeneration()
    private var resendStick: Runnable? = null

    fun begin(
        pointerId: Int,
        x: Float,
        y: Float,
        eventTime: Long,
        newConfig: AimOutputConfig,
        allowRetainedTakeover: Boolean = false
    ): AimOutputBeginResult {
        val newStickName = newConfig.output.stickName()
        val existingStickName = config?.output?.stickName()
        val continuingRetainedStick = sessionActive && ownsStick &&
            ownerState == ManualStickOwnerState.RETAINED &&
            existingStickName != null && existingStickName == newStickName

        if (sessionActive && !continuingRetainedStick) finish(flushMouse = false)
        if (continuingRetainedStick) {
            invalidateStickResend()
            onRetainedStickChanged(null)
        }
        config = newConfig
        sessionActive = true
        activePointerId = pointerId
        originX = x
        originY = y
        lastX = x
        lastY = y
        lastAimSendTime = eventTime
        resetMotionState()

        val stickName = newStickName
        var replacedRetainedOwner = false
        if (stickName != null) {
            beforeStickAcquire(stickName)
            val acquisition = if (continuingRetainedStick) {
                ManualStickArbiter.setState(stickName, ownerToken, ManualStickOwnerState.ACTIVE)
                ManualStickAcquireResult(acquired = true)
            } else {
                ManualStickArbiter.acquire(
                    stickName,
                    ownerToken,
                    allowRetainedTakeover,
                    ::yieldWithoutCenterFromArbiter
                )
            }
            ownsStick = acquisition.acquired
            replacedRetainedOwner = acquisition.replacedRetainedOwner
            if (ownsStick) {
                ownerState = ManualStickOwnerState.ACTIVE
                if (newConfig.sendNeutralOnBegin) {
                    ManualStickArbiter.send(stickName, ownerToken, 0f, 0f)
                    startStickResend()
                }
            }
        }
        if (stickName != null && !ownsStick) resetSessionState()
        return AimOutputBeginResult(stickName == null || ownsStick, replacedRetainedOwner)
    }

    /** Returns false when the initiating pointer is no longer present/owned. */
    fun update(pointerId: Int, x: Float, y: Float, eventTime: Long, forceSend: Boolean = false): Boolean {
        val activeConfig = config ?: return false
        if (!sessionActive || activePointerId != pointerId) return false

        val stickName = activeConfig.output.stickName()
        if (stickName != null) {
            lastX = x
            lastY = y
            if (ownsStick) sendStick(activeConfig, stickName, x, y)
            return true
        }

        pendingDx += x - lastX
        pendingDy += y - lastY
        lastX = x
        lastY = y
        if (!forceSend && eventTime - lastAimSendTime < AIM_SEND_INTERVAL_MS) return true
        sendMouse(activeConfig)
        lastAimSendTime = eventTime
        pendingDx = 0f
        pendingDy = 0f
        return true
    }

    /** Keep the final absolute stick value alive while a configured release delay runs. */
    fun detachPointerKeepingOutput() {
        activePointerId = INVALID_POINTER_ID
        val stickName = config?.output?.stickName() ?: return
        if (ownsStick) {
            ownerState = ManualStickOwnerState.DELAY_LOCKED
            ManualStickArbiter.setState(stickName, ownerToken, ownerState)
        }
    }

    fun completeGesture(autoCenter: Boolean) {
        if (autoCenter) finish(flushMouse = false) else retainStick()
    }

    fun completePending(autoCenter: Boolean) {
        if (ownerState == ManualStickOwnerState.CENTERED_PENDING) {
            finishCenteredPending()
        } else {
            completeGesture(autoCenter)
        }
    }

    fun centerRetainedOrPending(): Boolean {
        val stickName = config?.output?.stickName() ?: return false
        if (!sessionActive || !ownsStick || ownerState !in setOf(
                ManualStickOwnerState.RETAINED,
                ManualStickOwnerState.DELAY_LOCKED
            )
        ) return false
        invalidateStickResend()
        onRetainedStickChanged(null)
        if (ownerState == ManualStickOwnerState.DELAY_LOCKED) {
            ManualStickArbiter.send(stickName, ownerToken, 0f, 0f)
            ownerState = ManualStickOwnerState.CENTERED_PENDING
            ManualStickArbiter.setState(stickName, ownerToken, ownerState)
        } else {
            ManualStickArbiter.release(stickName, ownerToken, center = true)
            resetSessionState()
        }
        return true
    }

    fun finish(flushMouse: Boolean = true) {
        val activeConfig = config
        if (!sessionActive && activeConfig == null) return
        if (flushMouse && activeConfig?.output == TouchAimOutput.MOUSE) sendMouse(activeConfig)

        invalidateStickResend()
        val stickName = activeConfig?.output?.stickName()
        if (stickName != null && ownsStick) {
            ManualStickArbiter.release(stickName, ownerToken, center = true)
        }
        onRetainedStickChanged(null)
        resetSessionState()
    }

    fun isActive(): Boolean = sessionActive

    fun isRetained(): Boolean = sessionActive && ownerState == ManualStickOwnerState.RETAINED

    fun retainedStickName(): String? =
        config?.output?.stickName()?.takeIf { isRetained() }

    /** Change only the response strength while preserving pointer ownership and aim continuity. */
    fun updateSensitivity(sensitivity: Float) {
        val updated = config?.copy(sensitivity = sensitivity.coerceAtLeast(0f)) ?: return
        config = updated
        val stickName = updated.output.stickName()
        if (sessionActive && ownsStick && stickName != null) {
            sendStick(updated, stickName, lastX, lastY)
        }
    }

    private fun sendStick(activeConfig: AimOutputConfig, stickName: String, x: Float, y: Float) {
        val (rawX, rawY) = when (activeConfig.originMode) {
            AimOriginMode.CONTROL_CENTER -> {
                val (width, height) = controlSize()
                if (width <= 0f || height <= 0f) return
                ((x - width / 2f) / (width / 2f)).coerceIn(-1f, 1f) to
                    ((y - height / 2f) / (height / 2f)).coerceIn(-1f, 1f)
            }
            AimOriginMode.INITIAL_TOUCH -> radialDisplacement(activeConfig, x - originX, y - originY)
        }

        val mappedX = mapStickAxis(activeConfig, rawX)
        val mappedY = mapStickAxis(activeConfig, rawY)
        lastStickX = mappedX.coerceIn(-1f, 1f)
        lastStickY = (if (activeConfig.invertY) -mappedY else mappedY).coerceIn(-1f, 1f)
        ManualStickArbiter.send(stickName, ownerToken, lastStickX, lastStickY)
        startStickResend()
    }

    private fun radialDisplacement(config: AimOutputConfig, dx: Float, dy: Float): Pair<Float, Float> {
        val magnitude = hypot(dx, dy)
        val deadzone = config.stickDeadzonePx.coerceAtLeast(0f)
        if (magnitude <= deadzone || magnitude == 0f) return 0f to 0f
        val full = config.stickFullDisplacementPx.coerceAtLeast(deadzone + 1f)
        val normalizedMagnitude = ((magnitude - deadzone) / (full - deadzone)).coerceIn(0f, 1f)
        return (dx / magnitude) * normalizedMagnitude to (dy / magnitude) * normalizedMagnitude
    }

    private fun mapStickAxis(config: AimOutputConfig, value: Float): Float =
        when (config.stickProfile) {
            ButtonAimStickProfile.LINEAR -> value * config.sensitivity.coerceAtLeast(0f)
            ButtonAimStickProfile.RESPONSE_CURVE ->
                StickResponseCurve.apply(value, config.sensitivity.coerceAtLeast(0f))
        }

    private fun sendMouse(activeConfig: AimOutputConfig) {
        if (pendingDx == 0f && pendingDy == 0f) return
        val dx = pendingDx * activeConfig.sensitivity.coerceAtLeast(0f)
        val dy = pendingDy * activeConfig.sensitivity.coerceAtLeast(0f) *
            if (activeConfig.invertY) -1f else 1f
        val (outX, outY) = when (activeConfig.mouseProfile) {
            ButtonAimMouseProfile.LINEAR_RELATIVE -> dx to dy
            ButtonAimMouseProfile.SMOOTHED_NONLINEAR -> {
                val smoothedX = dx * 0.5f + previousMouseDx * 0.5f
                val smoothedY = dy * 0.5f + previousMouseDy * 0.5f
                previousMouseDx = smoothedX
                previousMouseDy = smoothedY
                scaleMouse(smoothedX) to scaleMouse(smoothedY)
            }
        }
        if (abs(outX) >= MOUSE_DEADZONE || abs(outY) >= MOUSE_DEADZONE) {
            UdpClient.sendTouchpadDelta(outX, outY)
        }
    }

    private fun startStickResend() {
        invalidateStickResend()
        val generation = resendGeneration.snapshot()
        resendStick = object : Runnable {
            override fun run() {
                val activeConfig = config
                val stickName = activeConfig?.output?.stickName()
                if (!resendGeneration.isCurrent(generation) || !sessionActive || !ownsStick ||
                    stickName == null || ownerState == ManualStickOwnerState.CENTERED_PENDING
                ) return
                ManualStickArbiter.send(stickName, ownerToken, lastStickX, lastStickY)
                handler.postDelayed(this, STICK_SEND_INTERVAL_MS)
            }
        }.also { handler.postDelayed(it, STICK_SEND_INTERVAL_MS) }
    }

    private fun retainStick() {
        val stickName = config?.output?.stickName()
        if (stickName == null || !ownsStick) {
            finish(flushMouse = false)
            return
        }
        activePointerId = INVALID_POINTER_ID
        ownerState = ManualStickOwnerState.RETAINED
        ManualStickArbiter.setState(stickName, ownerToken, ownerState)
        onRetainedStickChanged(stickName)
        startStickResend()
    }

    private fun finishCenteredPending() {
        val stickName = config?.output?.stickName()
        invalidateStickResend()
        if (stickName != null && ownsStick) {
            ManualStickArbiter.release(stickName, ownerToken, center = false)
        }
        onRetainedStickChanged(null)
        resetSessionState()
    }

    private fun yieldWithoutCenterFromArbiter(stickName: String) {
        if (config?.output?.stickName() != stickName) return
        invalidateStickResend()
        onRetainedStickChanged(null)
        resetSessionState()
    }

    private fun invalidateStickResend() {
        resendGeneration.invalidate()
        resendStick?.let(handler::removeCallbacks)
        resendStick = null
    }

    private fun resetSessionState() {
        config = null
        sessionActive = false
        activePointerId = INVALID_POINTER_ID
        ownsStick = false
        ownerState = ManualStickOwnerState.ACTIVE
        originX = 0f
        originY = 0f
        lastX = 0f
        lastY = 0f
        lastAimSendTime = 0L
        resetMotionState()
    }

    private fun resetMotionState() {
        pendingDx = 0f
        pendingDy = 0f
        previousMouseDx = 0f
        previousMouseDy = 0f
        lastStickX = 0f
        lastStickY = 0f
    }

    private fun scaleMouse(value: Float): Float {
        val magnitude = abs(value)
        val sign = if (value >= 0f) 1f else -1f
        return when {
            magnitude < 0.2f -> sign * magnitude * 1.5f
            magnitude < 0.6f -> value
            else -> sign * (0.6f + (magnitude - 0.6f) * 0.8f)
        }
    }

    companion object {
        private const val INVALID_POINTER_ID = -1
        private const val AIM_SEND_INTERVAL_MS = 8L
        private const val STICK_SEND_INTERVAL_MS = 16L
        private const val MOUSE_DEADZONE = 0.02f
    }
}

data class AimOutputBeginResult(
    val started: Boolean,
    val replacedRetainedOwner: Boolean = false
)

fun TouchAimOutput.stickName(): String? = when (this) {
    TouchAimOutput.MOUSE -> null
    TouchAimOutput.RIGHT_STICK -> "STICK_R"
    TouchAimOutput.LEFT_STICK -> "STICK_L"
}
