package com.example.simplecontroller.ui

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.TouchAimOutput
import com.example.simplecontroller.model.TouchStageAction
import com.example.simplecontroller.net.UdpClient
import kotlin.math.abs

enum class TouchContactLevel(val rank: Int, val displayName: String) {
    AIM_ONLY(-1, "AIM"),
    LOW(0, "LOW"),
    MEDIUM(1, "MED"),
    HIGH(2, "HIGH")
}

/** Owns all state for a Touch Aim gesture, including explicit command releases. */
class TouchAimHandler(
    private val model: Control,
    private val onStateChanged: () -> Unit,
    private val controlSize: () -> Pair<Float, Float>
) {
    private data class StageConfig(
        val level: TouchContactLevel,
        val threshold: Float,
        val payload: String,
        val action: TouchStageAction
    )

    private data class OutputCommand(val raw: String, val key: String)

    private val handler = Handler(Looper.getMainLooper())
    private val heldCommands = linkedMapOf<String, OutputCommand>()
    private val pendingPresses = linkedMapOf<String, OutputCommand>()
    private val pressGenerations = mutableMapOf<String, Int>()

    var currentLevel: TouchContactLevel = TouchContactLevel.AIM_ONLY
        private set
    var currentScore: Float = 0f
        private set

    private var hasScore = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var lastAimSendTime = 0L
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var previousMouseDx = 0f
    private var previousMouseDy = 0f
    private var lastStickX = 0f
    private var lastStickY = 0f
    private var isStickResending = false

    private val resendStick = object : Runnable {
        override fun run() {
            val stickName = model.touchAimOutput.stickName()
            if (!isStickResending ||
                activePointerId == MotionEvent.INVALID_POINTER_ID ||
                stickName == null
            ) {
                isStickResending = false
                return
            }

            UdpClient.sendStickPosition(stickName, lastStickX, lastStickY)
            handler.postDelayed(this, STICK_SEND_INTERVAL_MS)
        }
    }

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startGesture(event)
            MotionEvent.ACTION_MOVE -> {
                updateContact(event)
                updateAim(event)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) releaseAll()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseAll()
        }
    }

    fun releaseAll() {
        isStickResending = false
        handler.removeCallbacksAndMessages(null)

        val commandsToRelease = linkedMapOf<String, OutputCommand>()
        commandsToRelease.putAll(pendingPresses)
        commandsToRelease.putAll(heldCommands)
        commandsToRelease.values.forEach(::sendRelease)

        pendingPresses.clear()
        heldCommands.clear()
        pressGenerations.keys.toList().forEach { key ->
            pressGenerations[key] = (pressGenerations[key] ?: 0) + 1
        }

        model.touchAimOutput.stickName()?.let { UdpClient.sendStickPosition(it, 0f, 0f) }

        activePointerId = MotionEvent.INVALID_POINTER_ID
        currentLevel = TouchContactLevel.AIM_ONLY
        currentScore = 0f
        hasScore = false
        pendingDx = 0f
        pendingDy = 0f
        previousMouseDx = 0f
        previousMouseDy = 0f
        lastStickX = 0f
        lastStickY = 0f
        onStateChanged()
    }

    private fun startGesture(event: MotionEvent) {
        // A normal completed gesture is already reset. Avoid emitting an extra center
        // packet on the next DOWN; center is reserved for release/cancel. If Android
        // omitted the previous terminal event, close that stale gesture first.
        if (activePointerId != MotionEvent.INVALID_POINTER_ID ||
            pendingPresses.isNotEmpty() ||
            heldCommands.isNotEmpty()
        ) {
            releaseAll()
        }
        activePointerId = event.getPointerId(event.actionIndex)
        lastX = event.getX(event.actionIndex)
        lastY = event.getY(event.actionIndex)
        lastAimSendTime = event.eventTime
        updateContact(event)
    }

    private fun updateContact(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) return

        for (historyIndex in 0 until event.historySize) {
            updateScore(
                event.getHistoricalSize(pointerIndex, historyIndex),
                event.getHistoricalTouchMajor(pointerIndex, historyIndex),
                event.getHistoricalTouchMinor(pointerIndex, historyIndex)
            )
        }
        updateScore(
            event.getSize(pointerIndex),
            event.getTouchMajor(pointerIndex),
            event.getTouchMinor(pointerIndex)
        )
    }

    private fun updateScore(size: Float, major: Float, minor: Float) {
        val values = buildList {
            if (model.touchUseSize) add(size * model.touchSizeScale.coerceAtLeast(0f))
            if (model.touchUseMajor) add(major)
            if (model.touchUseMinor) add(minor)
        }
        val rawScore = if (values.isEmpty()) 0f else values.average().toFloat()
        val alpha = model.touchScoreSmoothing.coerceIn(0.05f, 1f)
        currentScore = if (hasScore) {
            currentScore + alpha * (rawScore - currentScore)
        } else {
            rawScore
        }
        hasScore = true

        val nextLevel = levelForScore(currentScore)
        if (nextLevel != currentLevel) transitionTo(nextLevel)
        onStateChanged()
    }

    private fun levelForScore(score: Float): TouchContactLevel {
        val stages = stageConfigs()
        val rawLevel = stages.lastOrNull { score >= it.threshold }?.level
            ?: TouchContactLevel.AIM_ONLY
        if (rawLevel.rank >= currentLevel.rank) return rawLevel

        val hysteresis = model.touchHysteresis.coerceAtLeast(0f)
        return stages.lastOrNull {
            score >= (it.threshold - hysteresis).coerceAtLeast(0f)
        }?.level ?: TouchContactLevel.AIM_ONLY
    }

    private fun transitionTo(nextLevel: TouchContactLevel) {
        val previousLevel = currentLevel
        val activeStages = if (model.touchKeepLowerHolds) {
            stageConfigs().filter { it.level.rank <= nextLevel.rank }
        } else {
            stageConfigs().filter { it.level == nextLevel }
        }
        val desiredHolds = linkedMapOf<String, OutputCommand>()
        activeStages
            .filter { it.action == TouchStageAction.HOLD }
            .flatMap { commands(it.payload) }
            .forEach { desiredHolds[it.key] = it }

        heldCommands.keys.filter { it !in desiredHolds }.toList().forEach { key ->
            heldCommands.remove(key)?.let(::sendRelease)
        }
        desiredHolds.forEach { (key, command) ->
            val existing = heldCommands[key]
            if (existing == null || existing.raw.trim() != command.raw.trim()) {
                pendingPresses.remove(key)
                pressGenerations[key] = (pressGenerations[key] ?: 0) + 1
                sendHold(command)
                heldCommands[key] = command
            }
        }

        if (nextLevel.rank > previousLevel.rank) {
            stageConfigs()
                .filter { it.level.rank in (previousLevel.rank + 1)..nextLevel.rank }
                .filter { it.action == TouchStageAction.PRESS }
                .flatMap { commands(it.payload) }
                .forEach(::sendPress)
        }

        currentLevel = nextLevel
    }

    private fun stageConfigs(): List<StageConfig> {
        val low = model.touchLowThreshold.coerceAtLeast(0f)
        val medium = model.touchMediumThreshold.coerceAtLeast(low)
        val high = model.touchHighThreshold.coerceAtLeast(medium)
        return listOf(
            StageConfig(TouchContactLevel.LOW, low, model.touchLowPayload, model.touchLowAction),
            StageConfig(
                TouchContactLevel.MEDIUM,
                medium,
                model.touchMediumPayload,
                model.touchMediumAction
            ),
            StageConfig(TouchContactLevel.HIGH, high, model.touchHighPayload, model.touchHighAction)
        )
    }

    private fun commands(payload: String): List<OutputCommand> = payload
        .split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { raw -> OutputCommand(raw, commandKey(raw)) }

    private fun commandKey(raw: String): String {
        val command = raw.trim().uppercase()
        return when {
            command.startsWith("X360") -> command.substringBefore('_')
            command.startsWith("LT:") -> "LT"
            command.startsWith("RT:") -> "RT"
            command.startsWith("MOUSE_LEFT") -> "MOUSE_LEFT"
            command.startsWith("MOUSE_RIGHT") -> "MOUSE_RIGHT"
            command.startsWith("MOUSE_MIDDLE") -> "MOUSE_MIDDLE"
            command.startsWith("KEY_DOWN:") -> "KEY:${command.substringAfter(':')}"
            command.startsWith("KEY_UP:") -> "KEY:${command.substringAfter(':')}"
            else -> "KEY:$command"
        }
    }

    private fun sendHold(command: OutputCommand) {
        val raw = command.raw.trim()
        val upper = raw.uppercase()
        when {
            upper.startsWith("X360") -> UdpClient.sendCommand("${upper.substringBefore('_')}_HOLD")
            upper.startsWith("LT:") || upper.startsWith("RT:") -> UdpClient.sendCommand(upper)
            upper.startsWith("MOUSE_") -> UdpClient.sendCommand(mouseDown(upper))
            upper.startsWith("KEY_DOWN:") -> UdpClient.sendCommand(upper)
            else -> UdpClient.sendKeyCommand(raw, true)
        }
    }

    private fun sendPress(command: OutputCommand) {
        val raw = command.raw.trim()
        val upper = raw.uppercase()
        when {
            upper.startsWith("X360") -> UdpClient.sendCommand(upper.substringBefore('_'))
            upper.startsWith("LT:") || upper.startsWith("RT:") -> {
                UdpClient.sendCommand(upper)
                scheduleRelease(command)
            }
            upper.startsWith("MOUSE_") -> {
                UdpClient.sendCommand(mouseDown(upper))
                scheduleRelease(command)
            }
            upper.startsWith("KEY_DOWN:") -> {
                UdpClient.sendCommand(upper)
                scheduleRelease(command)
            }
            else -> {
                UdpClient.sendKeyCommand(raw, true)
                scheduleRelease(command)
            }
        }
    }

    private fun scheduleRelease(command: OutputCommand) {
        val generation = (pressGenerations[command.key] ?: 0) + 1
        pressGenerations[command.key] = generation
        pendingPresses[command.key] = command
        handler.postDelayed({
            if (pressGenerations[command.key] == generation && command.key !in heldCommands) {
                pendingPresses.remove(command.key)
                sendRelease(command)
            }
        }, PRESS_DURATION_MS)
    }

    private fun sendRelease(command: OutputCommand) {
        val raw = command.raw.trim()
        val upper = raw.uppercase()
        when {
            upper.startsWith("X360") -> UdpClient.sendCommand("${upper.substringBefore('_')}_RELEASE")
            upper.startsWith("LT:") -> UdpClient.sendCommand("LT:0.0")
            upper.startsWith("RT:") -> UdpClient.sendCommand("RT:0.0")
            upper.startsWith("MOUSE_") -> UdpClient.sendCommand(mouseUp(upper))
            upper.startsWith("KEY_DOWN:") || upper.startsWith("KEY_UP:") -> {
                UdpClient.sendCommand("KEY_UP:${upper.substringAfter(':')}")
            }
            else -> UdpClient.sendKeyCommand(raw, false)
        }
    }

    private fun mouseDown(command: String): String = when {
        command.startsWith("MOUSE_LEFT") -> "MOUSE_LEFT_DOWN"
        command.startsWith("MOUSE_RIGHT") -> "MOUSE_RIGHT_DOWN"
        else -> "MOUSE_MIDDLE_DOWN"
    }

    private fun mouseUp(command: String): String = when {
        command.startsWith("MOUSE_LEFT") -> "MOUSE_LEFT_UP"
        command.startsWith("MOUSE_RIGHT") -> "MOUSE_RIGHT_UP"
        else -> "MOUSE_MIDDLE_UP"
    }

    private fun updateAim(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) return

        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        val stickName = model.touchAimOutput.stickName()
        if (stickName != null) {
            sendStickPosition(stickName, x, y)
            return
        }

        val dx = x - lastX
        val dy = y - lastY
        lastX = x
        lastY = y
        pendingDx += dx
        pendingDy += dy

        if (event.eventTime - lastAimSendTime < AIM_SEND_INTERVAL_MS) return

        sendMouseAim()
        lastAimSendTime = event.eventTime
        pendingDx = 0f
        pendingDy = 0f
    }

    /** Uses the same center-to-edge position calculation as a normal stick control. */
    private fun sendStickPosition(stickName: String, touchX: Float, touchY: Float) {
        val (controlWidth, controlHeight) = controlSize()
        if (controlWidth <= 0f || controlHeight <= 0f) return

        val centerX = controlWidth / 2f
        val centerY = controlHeight / 2f
        val rawX = ((touchX - centerX) / centerX).coerceIn(-1f, 1f)
        val rawY = ((touchY - centerY) / centerY).coerceIn(-1f, 1f)
        val x = if (model.touchUseResponseCurve) {
            StickResponseCurve.apply(rawX, model.sensitivity)
        } else {
            rawX * model.sensitivity
        }
        val curvedOrLinearY = if (model.touchUseResponseCurve) {
            StickResponseCurve.apply(rawY, model.sensitivity)
        } else {
            rawY * model.sensitivity
        }
        val y = if (model.touchInvertY) -curvedOrLinearY else curvedOrLinearY

        lastStickX = x
        lastStickY = y
        UdpClient.sendStickPosition(stickName, x, y)
        startStickResending()
    }

    private fun startStickResending() {
        if (isStickResending) return
        isStickResending = true
        handler.postDelayed(resendStick, STICK_SEND_INTERVAL_MS)
    }

    private fun sendMouseAim() {
        val dx = pendingDx * model.sensitivity
        val dy = pendingDy * model.sensitivity * if (model.touchInvertY) -1f else 1f
        val smoothedDx = dx * 0.5f + previousMouseDx * 0.5f
        val smoothedDy = dy * 0.5f + previousMouseDy * 0.5f
        previousMouseDx = smoothedDx
        previousMouseDy = smoothedDy
        if (abs(smoothedDx) < 0.02f && abs(smoothedDy) < 0.02f) return
        UdpClient.sendTouchpadDelta(scaleMouse(smoothedDx), scaleMouse(smoothedDy))
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
        private const val PRESS_DURATION_MS = 90L
        private const val AIM_SEND_INTERVAL_MS = 8L
        private const val STICK_SEND_INTERVAL_MS = 16L
    }
}

private fun TouchAimOutput.stickName(): String? = when (this) {
    TouchAimOutput.MOUSE -> null
    TouchAimOutput.RIGHT_STICK -> "STICK_R"
    TouchAimOutput.LEFT_STICK -> "STICK_L"
}
