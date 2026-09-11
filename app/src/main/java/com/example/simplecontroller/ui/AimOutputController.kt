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

/**
 * First-active-gesture ownership for ordinary/manual analog stick output.
 * Directional button macros intentionally stay outside this arbiter because the receiver
 * already gives their STICK_MACRO packets higher priority.
 */
object ManualStickArbiter {
    private val owners = mutableMapOf<String, Any>()

    @Synchronized
    fun acquire(stickName: String, owner: Any): Boolean {
        val canonical = canonicalStickName(stickName)
        val current = owners[canonical]
        if (current != null && current !== owner) return false
        owners[canonical] = owner
        return true
    }

    @Synchronized
    fun owns(stickName: String, owner: Any): Boolean =
        owners[canonicalStickName(stickName)] === owner

    fun send(stickName: String, owner: Any, x: Float, y: Float): Boolean {
        if (!owns(stickName, owner)) return false
        UdpClient.sendStickPosition(stickName, x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
        return true
    }

    fun release(stickName: String, owner: Any, center: Boolean = true): Boolean {
        val canonical = canonicalStickName(stickName)
        val released = synchronized(this) {
            if (owners[canonical] !== owner) false else {
                owners.remove(canonical)
                true
            }
        }
        if (released && center) UdpClient.sendStickPosition(canonical, 0f, 0f)
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
    private val beforeStickAcquire: (String) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private var config: AimOutputConfig? = null
    private var sessionActive = false
    private var activePointerId = INVALID_POINTER_ID
    private var ownsStick = false
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

    private val resendStick = object : Runnable {
        override fun run() {
            val activeConfig = config
            val stickName = activeConfig?.output?.stickName()
            if (!sessionActive || !ownsStick || stickName == null) return
            ManualStickArbiter.send(stickName, ownerToken, lastStickX, lastStickY)
            handler.postDelayed(this, STICK_SEND_INTERVAL_MS)
        }
    }

    fun begin(
        pointerId: Int,
        x: Float,
        y: Float,
        eventTime: Long,
        newConfig: AimOutputConfig
    ): Boolean {
        if (sessionActive) finish(flushMouse = false)
        config = newConfig
        sessionActive = true
        activePointerId = pointerId
        originX = x
        originY = y
        lastX = x
        lastY = y
        lastAimSendTime = eventTime
        resetMotionState()

        val stickName = newConfig.output.stickName()
        if (stickName != null) {
            beforeStickAcquire(stickName)
            ownsStick = ManualStickArbiter.acquire(stickName, ownerToken)
            if (ownsStick) {
                if (stickName == "STICK_R") UdpClient.setManualRightStickActive(ownerToken, true)
                if (newConfig.sendNeutralOnBegin) {
                    ManualStickArbiter.send(stickName, ownerToken, 0f, 0f)
                    startStickResend()
                }
            }
        }
        return stickName == null || ownsStick
    }

    /** Returns false when the initiating pointer is no longer present/owned. */
    fun update(pointerId: Int, x: Float, y: Float, eventTime: Long, forceSend: Boolean = false): Boolean {
        val activeConfig = config ?: return false
        if (!sessionActive || activePointerId != pointerId) return false

        val stickName = activeConfig.output.stickName()
        if (stickName != null) {
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
    }

    fun finish(flushMouse: Boolean = true) {
        val activeConfig = config
        if (!sessionActive && activeConfig == null) return
        if (flushMouse && activeConfig?.output == TouchAimOutput.MOUSE) sendMouse(activeConfig)

        handler.removeCallbacks(resendStick)
        val stickName = activeConfig?.output?.stickName()
        if (stickName != null && ownsStick) {
            ManualStickArbiter.release(stickName, ownerToken, center = true)
            if (stickName == "STICK_R") UdpClient.setManualRightStickActive(ownerToken, false)
        }

        config = null
        sessionActive = false
        activePointerId = INVALID_POINTER_ID
        ownsStick = false
        originX = 0f
        originY = 0f
        lastX = 0f
        lastY = 0f
        lastAimSendTime = 0L
        resetMotionState()
    }

    fun isActive(): Boolean = sessionActive

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
        handler.removeCallbacks(resendStick)
        handler.postDelayed(resendStick, STICK_SEND_INTERVAL_MS)
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

fun TouchAimOutput.stickName(): String? = when (this) {
    TouchAimOutput.MOUSE -> null
    TouchAimOutput.RIGHT_STICK -> "STICK_R"
    TouchAimOutput.LEFT_STICK -> "STICK_L"
}
