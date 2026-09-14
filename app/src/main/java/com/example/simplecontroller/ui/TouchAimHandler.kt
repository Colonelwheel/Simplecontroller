package com.example.simplecontroller.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.example.simplecontroller.model.ButtonAimMouseProfile
import com.example.simplecontroller.model.ButtonAimStickProfile
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.TouchAimOutput
import com.example.simplecontroller.model.TouchAimMode
import com.example.simplecontroller.model.TouchAimSensorSample
import com.example.simplecontroller.model.TouchStageAction
import com.example.simplecontroller.net.UdpClient

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
    private val controlSize: () -> Pair<Float, Float>,
    private val payloadExecutor: ButtonAimPayloadExecutor,
    private val onPayloadError: (String) -> Unit = {}
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
    private val aimOutput = AimOutputSession(
        ownerToken = Any(),
        controlSize = controlSize,
        beforeStickAcquire = SwipeManager::stopContinuousSendingForStick
    )

    var currentLevel: TouchContactLevel = TouchContactLevel.AIM_ONLY
        private set
    var currentScore: Float = 0f
        private set
    val isTwoStateShooting: Boolean
        get() = isTwoStateMode() &&
            twoStateMachine?.state == TwoStateTouchAimState.SHOOT

    private var hasScore = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var twoStateMachine: TwoStateTouchAimStateMachine? = null
    private var aimPayloadLease: ButtonAimPayloadLease? = null
    private var shootPayloadLease: ButtonAimPayloadLease? = null
    private var touchSessionGeneration = 0
    private var confirmationRunnable: Runnable? = null

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
        touchSessionGeneration++
        confirmationRunnable?.let(handler::removeCallbacks)
        confirmationRunnable = null
        handler.removeCallbacksAndMessages(null)

        twoStateMachine?.terminate(TwoStateTouchAimTermination.CANCEL)
        twoStateMachine = null
        payloadExecutor.releaseAllLeases()
        aimPayloadLease = null
        shootPayloadLease = null

        val commandsToRelease = linkedMapOf<String, OutputCommand>()
        commandsToRelease.putAll(pendingPresses)
        commandsToRelease.putAll(heldCommands)
        commandsToRelease.values.forEach(::sendRelease)

        pendingPresses.clear()
        heldCommands.clear()
        pressGenerations.keys.toList().forEach { key ->
            pressGenerations[key] = (pressGenerations[key] ?: 0) + 1
        }

        aimOutput.finish(flushMouse = false)

        activePointerId = MotionEvent.INVALID_POINTER_ID
        currentLevel = TouchContactLevel.AIM_ONLY
        currentScore = 0f
        hasScore = false
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
        touchSessionGeneration++
        if (isTwoStateMode()) {
            val detectorError = twoStateDetectorError()
            val payloadError = payloadExecutor.statePayloadValidationError(model.touchAimAimPayload)
                ?: payloadExecutor.statePayloadValidationError(model.touchAimShootPayload)
            if (detectorError != null || payloadError != null) {
                onPayloadError(detectorError ?: payloadError!!)
                releaseAll()
                return
            }
        }
        val aimStarted = aimOutput.begin(
            pointerId = activePointerId,
            x = event.getX(event.actionIndex),
            y = event.getY(event.actionIndex),
            eventTime = event.eventTime,
            newConfig = AimOutputConfig(
                output = model.touchAimOutput,
                sensitivity = model.sensitivity,
                invertY = model.touchInvertY,
                stickProfile = if (model.touchUseResponseCurve) {
                    ButtonAimStickProfile.RESPONSE_CURVE
                } else {
                    ButtonAimStickProfile.LINEAR
                },
                mouseProfile = ButtonAimMouseProfile.SMOOTHED_NONLINEAR,
                stickFullDisplacementPx = 1f,
                stickDeadzonePx = 0f,
                originMode = AimOriginMode.CONTROL_CENTER
            )
        )
        if (isTwoStateMode()) {
            if (!aimStarted) {
                releaseAll()
                return
            }
            val shootOnThreshold = activeShootOnThreshold()
            val shootOffThreshold = activeShootOffThreshold()
            twoStateMachine = TwoStateTouchAimStateMachine(
                TwoStateTouchAimConfig(
                    shootOnThreshold = shootOnThreshold,
                    shootOffThreshold = shootOffThreshold
                        .coerceAtMost(shootOnThreshold - MIN_HYSTERESIS_GAP),
                    smoothing = model.touchAimTwoStateSmoothing,
                    enterShootConfirmationMs = model.touchAimEnterShootConfirmationMs,
                    returnToAimConfirmationMs = model.touchAimReturnToAimConfirmationMs,
                    shootBehavior = model.touchAimShootBehavior,
                    hasAimPayload = model.touchAimAimPayload.isNotBlank(),
                    keepAimPayloadWhileShooting = model.touchAimKeepAimPayloadWhileShooting,
                    hasShootPayload = model.touchAimShootPayload.isNotBlank()
                )
            ).also { machine ->
                applyTwoStateActions(machine.onContactStarted(event.eventTime))
            }
        }
        updateContact(event)
    }

    private fun updateContact(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) return

        for (historyIndex in 0 until event.historySize) {
            updateScore(
                event.getHistoricalSize(pointerIndex, historyIndex),
                event.getHistoricalTouchMajor(pointerIndex, historyIndex),
                event.getHistoricalTouchMinor(pointerIndex, historyIndex),
                event.getHistoricalEventTime(historyIndex)
            )
        }
        updateScore(
            event.getSize(pointerIndex),
            event.getTouchMajor(pointerIndex),
            event.getTouchMinor(pointerIndex),
            event.eventTime
        )
    }

    private fun updateScore(size: Float, major: Float, minor: Float, timeMs: Long) {
        if (isTwoStateMode()) {
            val rawScore = if (model.touchAimMode == TouchAimMode.CALIBRATED_TWO_STATE) {
                TouchAimCalibrationAnalyzer.score(
                    TouchAimSensorSample(size, major, minor, moving = false),
                    model.touchAimSensorTransforms
                )
            } else {
                manualTouchAimScore(
                    size = size,
                    major = major,
                    minor = minor,
                    useSize = model.touchUseSize,
                    useMajor = model.touchUseMajor,
                    useMinor = model.touchUseMinor,
                    sizeScale = model.touchSizeScale
                )
            }
            if (!rawScore.isFinite()) {
                releaseAll()
                return
            }
            val machine = twoStateMachine ?: return
            val previousState = machine.state
            applyTwoStateActions(machine.onScore(rawScore, timeMs))
            applySensitivityTransition(previousState, machine.state)
            scheduleConfirmation(machine)
            currentScore = machine.filteredScore
            currentLevel = TouchContactLevel.AIM_ONLY
            onStateChanged()
            return
        }

        val rawScore = manualTouchAimScore(
            size = size,
            major = major,
            minor = minor,
            useSize = model.touchUseSize,
            useMajor = model.touchUseMajor,
            useMinor = model.touchUseMinor,
            sizeScale = model.touchSizeScale
        )
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

    private fun scheduleConfirmation(machine: TwoStateTouchAimStateMachine) {
        confirmationRunnable?.let(handler::removeCallbacks)
        confirmationRunnable = null
        val deadline = machine.nextDeadlineMs() ?: return
        val generation = touchSessionGeneration
        val runnable = Runnable {
            confirmationRunnable = null
            if (generation != touchSessionGeneration || twoStateMachine !== machine) return@Runnable
            val previous = machine.state
            applyTwoStateActions(machine.onTime(SystemClock.uptimeMillis()))
            applySensitivityTransition(previous, machine.state)
            currentScore = machine.filteredScore
            onStateChanged()
            scheduleConfirmation(machine)
        }
        confirmationRunnable = runnable
        handler.postDelayed(runnable, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private fun applySensitivityTransition(
        previous: TwoStateTouchAimState,
        next: TwoStateTouchAimState
    ) {
        if (next == previous) return
        aimOutput.updateSensitivity(
            if (next == TwoStateTouchAimState.SHOOT) model.touchAimShootSensitivity
            else model.sensitivity
        )
    }

    private fun isTwoStateMode(): Boolean = model.touchAimMode != TouchAimMode.MANUAL_THREE_STAGE

    private fun activeShootOnThreshold(): Float =
        if (model.touchAimMode == TouchAimMode.MANUAL_TWO_STATE) {
            if (model.touchAimManualThresholdsInitialized) {
                model.touchAimManualShootOnThreshold
            } else {
                model.touchHighThreshold
            }
        } else {
            model.touchAimShootOnThreshold
        }

    private fun activeShootOffThreshold(): Float =
        if (model.touchAimMode == TouchAimMode.MANUAL_TWO_STATE) {
            if (model.touchAimManualThresholdsInitialized) {
                model.touchAimManualShootOffThreshold
            } else {
                (model.touchHighThreshold - model.touchHysteresis).coerceAtLeast(0f)
            }
        } else {
            model.touchAimShootOffThreshold
        }

    private fun twoStateDetectorError(): String? {
        val detectorValid = when (model.touchAimMode) {
            TouchAimMode.CALIBRATED_TWO_STATE -> model.touchAimSensorTransforms.isNotEmpty() &&
                model.touchAimSensorTransforms.map { it.sensor }.distinct().size ==
                model.touchAimSensorTransforms.size &&
                model.touchAimSensorTransforms.all {
                    it.center.isFinite() && it.scale.isFinite() && it.scale > 0f &&
                        it.weight.isFinite() && it.weight > 0f
                }
            TouchAimMode.MANUAL_TWO_STATE ->
                (model.touchUseMajor || model.touchUseMinor ||
                    (model.touchUseSize && model.touchSizeScale > 0f)) &&
                    model.touchSizeScale.isFinite() && model.touchSizeScale >= 0f
            TouchAimMode.MANUAL_THREE_STAGE -> false
        }
        val shootOnThreshold = activeShootOnThreshold()
        val shootOffThreshold = activeShootOffThreshold()
        val thresholdsValid = shootOnThreshold.isFinite() && shootOffThreshold.isFinite() &&
            shootOffThreshold <= shootOnThreshold - MIN_HYSTERESIS_GAP &&
            (model.touchAimMode != TouchAimMode.MANUAL_TWO_STATE ||
                (shootOnThreshold >= MIN_HYSTERESIS_GAP && shootOffThreshold >= 0f))
        val timingValid = model.touchAimTwoStateSmoothing.isFinite() &&
            model.touchAimTwoStateSmoothing in 0.05f..1f &&
            model.touchAimEnterShootConfirmationMs in 1L..2000L &&
            model.touchAimReturnToAimConfirmationMs in 1L..2000L
        val sensitivityValid = model.sensitivity.isFinite() && model.sensitivity >= 0f &&
            model.touchAimShootSensitivity.isFinite() && model.touchAimShootSensitivity >= 0f
        return if (detectorValid && thresholdsValid && timingValid && sensitivityValid) null else
            "This two-state TouchAim setup is invalid. Check its sensors and thresholds, re-run calibration, or use manual three-stage mode."
    }

    private fun applyTwoStateActions(actions: List<TwoStateTouchAimAction>) {
        actions.forEach { action ->
            when (action) {
                TwoStateTouchAimAction.HOLD_AIM_PAYLOAD -> {
                    aimPayloadLease = activatePayload(
                        ButtonAimPayloadOwner.BASE,
                        model.touchAimAimPayload
                    )
                }
                TwoStateTouchAimAction.RELEASE_AIM_PAYLOAD -> {
                    payloadExecutor.release(aimPayloadLease)
                    aimPayloadLease = null
                }
                TwoStateTouchAimAction.HOLD_SHOOT_PAYLOAD -> {
                    shootPayloadLease = activatePayload(
                        ButtonAimPayloadOwner.ALTERNATE,
                        model.touchAimShootPayload
                    )
                }
                TwoStateTouchAimAction.RELEASE_SHOOT_PAYLOAD -> {
                    payloadExecutor.release(shootPayloadLease)
                    shootPayloadLease = null
                }
                TwoStateTouchAimAction.PRESS_SHOOT_PAYLOAD -> {
                    val lease = activatePayload(
                        ButtonAimPayloadOwner.ALTERNATE,
                        model.touchAimShootPayload
                    )
                    val generation = touchSessionGeneration
                    handler.postDelayed({
                        if (generation == touchSessionGeneration) payloadExecutor.release(lease)
                    }, PRESS_DURATION_MS)
                }
            }
        }
    }

    private fun activatePayload(
        owner: ButtonAimPayloadOwner,
        payload: String
    ): ButtonAimPayloadLease? = when (
        val result = payloadExecutor.activate(owner, payload, ButtonAimPulseMode.ONCE)
    ) {
        is ButtonAimPayloadActivationResult.Activated -> result.lease
        is ButtonAimPayloadActivationResult.Invalid -> {
            onPayloadError(result.reason)
            null
        }
        ButtonAimPayloadActivationResult.ReleaseAll -> {
            releaseAll()
            null
        }
    }

    private fun levelForScore(score: Float): TouchContactLevel {
        return legacyTouchAimLevelForScore(
            score = score,
            currentLevel = currentLevel,
            lowThreshold = model.touchLowThreshold,
            mediumThreshold = model.touchMediumThreshold,
            highThreshold = model.touchHighThreshold,
            hysteresis = model.touchHysteresis
        )
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
        if (pointerIndex < 0) {
            releaseAll()
            return
        }
        if (!aimOutput.update(
                activePointerId,
                event.getX(pointerIndex),
                event.getY(pointerIndex),
                event.eventTime
            )
        ) {
            releaseAll()
        }
    }

    companion object {
        private const val PRESS_DURATION_MS = 90L
        private const val MIN_HYSTERESIS_GAP = 0.01f
    }
}
