package com.example.simplecontroller.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.example.simplecontroller.model.ButtonAimMouseProfile
import com.example.simplecontroller.model.ButtonAimStickProfile
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.PayloadModifiers
import com.example.simplecontroller.model.TouchAimOutput
import com.example.simplecontroller.model.TouchAimMode
import com.example.simplecontroller.model.TouchAimSensorSample
import com.example.simplecontroller.model.TouchStageAction
import com.example.simplecontroller.net.UdpClient
import com.example.simplecontroller.net.PhoneOutputScope

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
    private val onPayloadError: (String) -> Unit = {},
    private val scopeProvider: () -> PhoneOutputScope? = { null },
    private val stateScopeProvider: () -> PhoneOutputScope? = scopeProvider,
    private val onMouseAimEngaged: () -> Unit = {}
) {
    private data class OutputCommand(val raw: String, val key: String)

    private val handler = Handler(Looper.getMainLooper())
    private val heldCommands = linkedMapOf<String, OutputCommand>()
    private val pendingPresses = linkedMapOf<String, OutputCommand>()
    private val pressGenerations = mutableMapOf<String, Int>()
    private var stateOutputScope: PhoneOutputScope? = null
    private val aimOutput = AimOutputSession(
        ownerToken = Any(),
        controlSize = controlSize,
        beforeStickAcquire = SwipeManager::stopContinuousSendingForStick,
        scopeProvider = scopeProvider
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
    private val stageConfirmation = TouchAimStageConfirmation(::stageConfirmationMs)
    private val stagePayloads = TouchAimStagePayloadController(payloadExecutor, onPayloadError)
    private val finitePayloadLeases = linkedMapOf<Long, ButtonAimPayloadLease>()
    private val finitePayloadStages = mutableMapOf<Long, TwoStateTouchAimState>()

    fun hasRuntimeState(): Boolean = activePointerId != MotionEvent.INVALID_POINTER_ID ||
        heldCommands.isNotEmpty() || pendingPresses.isNotEmpty() || aimPayloadLease != null ||
        shootPayloadLease != null || aimOutput.isActive() || payloadExecutor.activeLeaseCount() > 0

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startGesture(event)
            MotionEvent.ACTION_MOVE -> {
                updateContact(event)
                updateAim(event)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) finishGesture()
            }
            MotionEvent.ACTION_UP -> finishGesture()
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
    }

    fun releaseAll() = finishGesture(cancelled = true)

    private fun finishGesture(cancelled: Boolean = false) {
        touchSessionGeneration++
        confirmationRunnable?.let(handler::removeCallbacks)
        confirmationRunnable = null
        handler.removeCallbacksAndMessages(null)

        twoStateMachine?.terminate(
            if (cancelled) TwoStateTouchAimTermination.CANCEL else TwoStateTouchAimTermination.FINGER_LIFT
        )
        if (!cancelled) {
            payloadExecutor.releaseNormallyTogether(
                aimPayloadLease, shootPayloadLease,
                *finitePayloadLeases.values.toTypedArray()
            )
        }
        stagePayloads.finish(cancelled)
        twoStateMachine = null
        if (cancelled) payloadExecutor.releaseAllLeases()
        aimPayloadLease = null
        shootPayloadLease = null
        finitePayloadLeases.clear()
        finitePayloadStages.clear()
        stageConfirmation.reset()

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
        // Delayed modifier output owns the state scope independently of movement. Its executor
        // timers survive natural UP and a later contact, but hard cancellation always closes it.
        if (cancelled || payloadExecutor.activeLeaseCount() == 0) stateOutputScope?.finish()
        stateOutputScope = null

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
        stateOutputScope = stateScopeProvider()
        touchSessionGeneration++
        val detectorError = if (isTwoStateMode()) twoStateDetectorError() else null
        val payloadError = if (isTwoStateMode()) {
            payloadExecutor.statePayloadValidationError(model.touchAimAimPayload)
                ?: payloadExecutor.statePayloadValidationError(model.touchAimShootPayload)
        } else touchAimStagePayloadValidationError(stageConfigs())
        if (detectorError != null || payloadError != null) {
            onPayloadError(detectorError ?: payloadError!!)
            releaseAll()
            return
        }
        val aimStart = aimOutput.begin(
            pointerId = activePointerId,
            x = event.getX(event.actionIndex),
            y = event.getY(event.actionIndex),
            eventTime = event.eventTime,
            newConfig = model.touchAimConfig().copy(
                sensitivity = if (isTwoStateMode()) model.sensitivity
                else model.touchAimStageSensitivity(TouchContactLevel.AIM_ONLY)
            ),
            allowRetainedTakeover = true
        )
        if (aimStart.started && model.touchAimOutput == TouchAimOutput.MOUSE) onMouseAimEngaged()
        if (aimStart.started && aimStart.replacedRetainedOwner &&
            model.touchAimConfig().originMode == AimOriginMode.CONTROL_CENTER
        ) {
            aimOutput.update(
                activePointerId,
                event.getX(event.actionIndex),
                event.getY(event.actionIndex),
                event.eventTime,
                forceSend = true
            )
        }
        if (isTwoStateMode()) {
            if (!aimStart.started) {
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
            val actions = machine.onScore(rawScore, timeMs)
            releaseDepartedTwoStatePresses(previousState, machine.state)
            applyTwoStateActions(actions)
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
        stageConfirmation.onTarget(nextLevel, timeMs)?.let(::transitionTo)
        scheduleStageConfirmation()
        onStateChanged()
    }

    private fun scheduleStageConfirmation() {
        confirmationRunnable?.let(handler::removeCallbacks)
        confirmationRunnable = null
        val deadline = stageConfirmation.nextDeadlineMs() ?: return
        val generation = touchSessionGeneration
        val runnable = Runnable {
            confirmationRunnable = null
            if (generation != touchSessionGeneration ||
                activePointerId == MotionEvent.INVALID_POINTER_ID || isTwoStateMode()
            ) return@Runnable
            stageConfirmation.onTime(SystemClock.uptimeMillis())?.let(::transitionTo)
            onStateChanged()
            scheduleStageConfirmation()
        }
        confirmationRunnable = runnable
        handler.postDelayed(runnable, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private fun stageConfirmationMs(level: TouchContactLevel): Long = when (level) {
        TouchContactLevel.AIM_ONLY -> model.touchAimAimOnlyEnterConfirmationMs
        TouchContactLevel.LOW -> model.touchLowEnterConfirmationMs
        TouchContactLevel.MEDIUM -> model.touchMediumEnterConfirmationMs
        TouchContactLevel.HIGH -> model.touchHighEnterConfirmationMs
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
            val actions = machine.onTime(SystemClock.uptimeMillis())
            releaseDepartedTwoStatePresses(previous, machine.state)
            applyTwoStateActions(actions)
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

    private fun releaseDepartedTwoStatePresses(
        previous: TwoStateTouchAimState,
        next: TwoStateTouchAimState
    ) {
        if (previous == next) return
        finitePayloadStages.filterValues { it == previous }.keys.toList().forEach { id ->
            payloadExecutor.releaseNormally(
                finitePayloadLeases[id], applyHoldReleaseDelay = false, preserveFinitePress = true
            )
            finitePayloadStages.remove(id)
        }
    }

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
                    payloadExecutor.releaseNormally(aimPayloadLease, applyHoldReleaseDelay = false)
                    aimPayloadLease = null
                }
                TwoStateTouchAimAction.HOLD_SHOOT_PAYLOAD -> {
                    shootPayloadLease = activatePayload(
                        ButtonAimPayloadOwner.ALTERNATE,
                        model.touchAimShootPayload
                    )
                }
                TwoStateTouchAimAction.RELEASE_SHOOT_PAYLOAD -> {
                    payloadExecutor.releaseNormally(shootPayloadLease, applyHoldReleaseDelay = false)
                    shootPayloadLease = null
                }
                TwoStateTouchAimAction.PRESS_SHOOT_PAYLOAD -> {
                    val modified = PayloadModifiers.hasModifiers(model.touchAimShootPayload)
                    val lease = activatePayload(
                        ButtonAimPayloadOwner.ALTERNATE,
                        model.touchAimShootPayload,
                        immediatePressDurationMs = if (modified) PRESS_DURATION_MS else null
                    )
                    lease?.let { finitePayloadLeases[it.id] = it }
                    if (modified) {
                        lease?.let { active ->
                            twoStateMachine?.state?.let { finitePayloadStages[active.id] = it }
                        }
                        return@forEach
                    }
                    val generation = touchSessionGeneration
                    handler.postDelayed({
                        if (generation == touchSessionGeneration) {
                            lease?.let { finitePayloadLeases.remove(it.id) }
                            payloadExecutor.release(lease)
                        }
                    }, PRESS_DURATION_MS)
                }
            }
        }
    }

    private fun activatePayload(
        owner: ButtonAimPayloadOwner,
        payload: String,
        immediatePressDurationMs: Long? = null
    ): ButtonAimPayloadLease? = when (
        val result = payloadExecutor.activate(
            owner, payload, ButtonAimPulseMode.ONCE,
            immediatePressDurationMs = immediatePressDurationMs
        )
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
        if (stageConfigs().any { PayloadModifiers.hasModifiers(it.payload) }) {
            transitionLeasedStages(previousLevel, nextLevel)
            currentLevel = nextLevel
            updateStageSensitivity(previousLevel, nextLevel)
            return
        }
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
                .filter { it.level in enteredPressLevels(previousLevel, nextLevel) }
                .filter { it.action == TouchStageAction.PRESS }
                .flatMap { commands(it.payload) }
                .forEach(::sendPress)
        }

        currentLevel = nextLevel
        updateStageSensitivity(previousLevel, nextLevel)
    }

    private fun updateStageSensitivity(previous: TouchContactLevel, next: TouchContactLevel) {
        val sensitivity = model.touchAimStageSensitivity(next)
        if (sensitivity != model.touchAimStageSensitivity(previous)) {
            aimOutput.updateSensitivity(sensitivity)
        }
    }

    private fun enteredPressLevels(
        previous: TouchContactLevel,
        next: TouchContactLevel
    ): Set<TouchContactLevel> = enteredTouchAimPressLevels(
        previous, next,
        hasStageConfirmation = TouchContactLevel.entries.any { stageConfirmationMs(it) > 0L }
    )

    /** Only controls opting into modifiers take this lease-backed path. Legacy controls above
     * retain their command spelling, PRESS crossings, and hold reconciliation unchanged. */
    private fun transitionLeasedStages(previous: TouchContactLevel, next: TouchContactLevel) {
        stagePayloads.transition(
            stageConfigs(), next, model.touchKeepLowerHolds, enteredPressLevels(previous, next)
        )
    }

    private fun stageConfigs(): List<TouchAimPayloadStage> {
        return listOf(
            TouchAimPayloadStage(TouchContactLevel.LOW, model.touchLowPayload, model.touchLowAction),
            TouchAimPayloadStage(
                TouchContactLevel.MEDIUM,
                model.touchMediumPayload,
                model.touchMediumAction
            ),
            TouchAimPayloadStage(TouchContactLevel.HIGH, model.touchHighPayload, model.touchHighAction)
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
            upper.startsWith("X360") -> sendStateCommand("${upper.substringBefore('_')}_HOLD")
            upper.startsWith("LT:") || upper.startsWith("RT:") -> sendStateCommand(upper)
            upper.startsWith("MOUSE_") -> sendStateCommand(mouseDown(upper))
            upper.startsWith("KEY_DOWN:") -> sendStateCommand(upper)
            else -> sendStateKey(raw, true)
        }
    }

    private fun sendStateCommand(command: String) {
        if (command.startsWith("MOUSE_", true) && !command.endsWith("_UP", true)) onMouseAimEngaged()
        val scope = stateOutputScope
        if (scope != null) scope.sendCommand(command)
        else if (!UdpClient.floatingSupported) UdpClient.sendCommand(command)
    }

    private fun sendStateKey(key: String, pressed: Boolean) {
        val scope = stateOutputScope
        if (scope != null) scope.sendKey(key, pressed)
        else if (!UdpClient.floatingSupported) UdpClient.sendKeyCommand(key, pressed)
    }

    private fun sendPress(command: OutputCommand) {
        val raw = command.raw.trim()
        val upper = raw.uppercase()
        when {
            upper.startsWith("X360") -> sendStateCommand(upper.substringBefore('_'))
            upper.startsWith("LT:") || upper.startsWith("RT:") -> {
                sendStateCommand(upper)
                scheduleRelease(command)
            }
            upper.startsWith("MOUSE_") -> {
                sendStateCommand(mouseDown(upper))
                scheduleRelease(command)
            }
            upper.startsWith("KEY_DOWN:") -> {
                sendStateCommand(upper)
                scheduleRelease(command)
            }
            else -> {
                sendStateKey(raw, true)
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
            upper.startsWith("X360") -> sendStateCommand("${upper.substringBefore('_')}_RELEASE")
            upper.startsWith("LT:") -> sendStateCommand("LT:0.0")
            upper.startsWith("RT:") -> sendStateCommand("RT:0.0")
            upper.startsWith("MOUSE_") -> sendStateCommand(mouseUp(upper))
            upper.startsWith("KEY_DOWN:") || upper.startsWith("KEY_UP:") -> {
                sendStateCommand("KEY_UP:${upper.substringAfter(':')}")
            }
            else -> sendStateKey(raw, false)
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

internal fun Control.touchAimStageSensitivity(level: TouchContactLevel): Float = when (level) {
    TouchContactLevel.AIM_ONLY -> touchAimAimOnlySensitivity
    TouchContactLevel.LOW -> touchLowSensitivity
    TouchContactLevel.MEDIUM -> touchMediumSensitivity
    TouchContactLevel.HIGH -> touchHighSensitivity
}?.takeIf { it.isFinite() && it >= 0f } ?: sensitivity

internal fun Control.touchAimConfig(): AimOutputConfig = AimOutputConfig(
    output = touchAimOutput,
    sensitivity = sensitivity,
    invertY = touchInvertY,
    stickProfile = if (touchUseResponseCurve) {
        ButtonAimStickProfile.RESPONSE_CURVE
    } else {
        ButtonAimStickProfile.LINEAR
    },
    mouseProfile = ButtonAimMouseProfile.SMOOTHED_NONLINEAR,
    stickFullDisplacementPx = touchAimStickFullDisplacementPx,
    stickDeadzonePx = touchAimStickDeadzonePx,
    originMode = if (touchAimStickUsesTouchPosition) {
        AimOriginMode.CONTROL_CENTER
    } else {
        AimOriginMode.INITIAL_TOUCH
    },
    sendNeutralOnBegin = !touchAimStickUsesTouchPosition,
    gameMouse = touchAimGameMouse,
    excludeFromCameraFollow = excludeFromCameraFollow
)
