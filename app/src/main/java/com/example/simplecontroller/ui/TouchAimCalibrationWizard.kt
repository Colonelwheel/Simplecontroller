package com.example.simplecontroller.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import com.example.simplecontroller.MainActivity
import com.example.simplecontroller.R
import com.example.simplecontroller.io.TouchAimCalibrationStore
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.TouchAimCalibrationPass
import com.example.simplecontroller.model.TouchAimCalibrationProfile
import com.example.simplecontroller.model.TouchAimCalibrationQuality
import com.example.simplecontroller.model.TouchAimCalibrationState
import com.example.simplecontroller.model.TouchAimSensorSample
import com.example.simplecontroller.model.applyTo
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.hypot

/** One-finger, output-suppressed calibration overlay for one existing TouchAim surface. */
class TouchAimCalibrationWizard(
    private val activity: MainActivity,
    private val targetView: ControlView,
    private val model: Control,
    private val existingProfile: TouchAimCalibrationProfile? = null,
    private val onClosed: (applied: Boolean) -> Unit = {}
) {
    private enum class Phase { READY, COUNTDOWN, RECORDING, REVIEW, RESULTS, VALIDATING, CLOSED }

    private data class Step(val state: TouchAimCalibrationState, val repetition: Int)

    private val steps = listOf(
        Step(TouchAimCalibrationState.AIM, 0),
        Step(TouchAimCalibrationState.SHOOT, 0),
        Step(TouchAimCalibrationState.AIM, 1),
        Step(TouchAimCalibrationState.SHOOT, 1)
    )
    private val handler = Handler(Looper.getMainLooper())
    private val canvas = activity.findViewById<FrameLayout>(R.id.canvas)
    private val overlay = FrameLayout(activity).apply {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.argb(80, 0, 0, 0))
        contentDescription = "TouchAim calibration wizard"
    }
    private val captureView = View(activity).apply {
        isClickable = true
        background = GradientDrawable().apply {
            setColor(Color.argb(35, 33, 150, 243))
            setStroke(dp(4), Color.CYAN)
            cornerRadius = dp(10).toFloat()
        }
        contentDescription = "TouchAim calibration area"
        setOnTouchListener { _, event ->
            handleCaptureTouch(event)
            true
        }
    }
    private val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18))
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(activity, R.color.dark_surface))
            cornerRadius = dp(12).toFloat()
        }
        isClickable = true
    }
    private val panelScroll = ScrollView(activity).apply {
        isFillViewport = true
        addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val title = TextView(activity).apply {
        textSize = 24f
        setTextColor(Color.WHITE)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private val status = TextView(activity).apply {
        textSize = 19f
        setTextColor(Color.WHITE)
        setPadding(0, dp(8), 0, dp(8))
    }
    private val detail = TextView(activity).apply {
        textSize = 16f
        setTextColor(Color.LTGRAY)
        setPadding(0, 0, 0, dp(8))
    }
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        visibility = View.GONE
    }
    private val prepSeconds = durationField("10")
    private val recordSeconds = durationField("9")
    private val timingRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(labeledField("Prepare seconds", prepSeconds), weighted())
        addView(labeledField("Record seconds", recordSeconds), weighted())
    }
    private val buttonColumn = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }

    private var phase = Phase.READY
    private var generation = 0
    private var stepIndex = 0
    private val passes = mutableListOf<TouchAimCalibrationPass>()
    private var pendingPass: TouchAimCalibrationPass? = null
    private val recordingSamples = mutableListOf<TouchAimSensorSample>()
    private var recordingStartedAt = 0L
    private var recordingDurationMs = 9000L
    private var movementCueSent = false
    private var recommendation: TouchAimCalibrationRecommendation? = null
    private var validationMachine: TwoStateTouchAimStateMachine? = null
    private var validationLastState = TwoStateTouchAimState.AIM
    private var validationLastTransitionAt = 0L
    private var validationUnexpectedTransitions = 0
    private var validationCompleted = false
    private var contactActive = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var recordingContactStarted = false
    private var recordingInterrupted = false
    private var movementPhaseStarted = false
    private var movementDistancePx = 0f
    private var movementObserved = false
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var validationSawShoot = false
    private var validationSawReturn = false
    private var validationDeadlineRunnable: Runnable? = null
    private var applyDialog: AlertDialog? = null

    fun show() {
        if (phase == Phase.CLOSED) return
        activity.activateReleaseAll(showFeedback = false)
        buildOverlay()
        canvas.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        showReady()
        overlay.requestFocus()
        overlay.announceForAccessibility(
            "TouchAim calibration. Four recordings: Aim twice and Aim plus Shoot twice."
        )
    }

    fun cancel(reason: String? = null) {
        if (phase == Phase.CLOSED) return
        reason?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }
        close(applied = false)
    }

    private fun buildOverlay() {
        overlay.removeAllViews()
        val targetLocation = IntArray(2)
        val canvasLocation = IntArray(2)
        targetView.getLocationOnScreen(targetLocation)
        canvas.getLocationOnScreen(canvasLocation)
        val captureParams = FrameLayout.LayoutParams(
            targetView.width.coerceAtLeast(1),
            targetView.height.coerceAtLeast(1)
        ).apply {
            leftMargin = targetLocation[0] - canvasLocation[0]
            topMargin = targetLocation[1] - canvasLocation[1]
        }
        overlay.addView(captureView, captureParams)

        if (panel.childCount == 0) {
            panel.addView(title)
            panel.addView(status)
            panel.addView(detail)
            panel.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
            panel.addView(timingRow)
            panel.addView(buttonColumn)
        }
        val targetCenterY = captureParams.topMargin + captureParams.height / 2
        val placeAbove = targetCenterY > canvas.height / 2
        val availableHeight = if (placeAbove) {
            captureParams.topMargin - dp(12)
        } else {
            canvas.height - (captureParams.topMargin + captureParams.height) - dp(12)
        }.coerceAtLeast(dp(80))
        overlay.addView(
            panelScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                availableHeight,
                if (placeAbove) Gravity.TOP else Gravity.BOTTOM
            ).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
        )
    }

    private fun showReady() {
        phase = Phase.READY
        generation++
        progress.visibility = View.GONE
        timingRow.visibility = View.VISIBLE
        val step = steps[stepIndex]
        title.text = "TouchAim calibration ${stepIndex + 1} of ${steps.size}"
        status.text = if (step.state == TouchAimCalibrationState.AIM) {
            "Touch the way you naturally AIM"
        } else {
            "Touch the way you naturally AIM AND SHOOT"
        }
        detail.text = "Press Start, lift from Start, then use the outlined TouchAim area. " +
            "Begin still. Halfway through, a cue will ask you to move naturally. " +
            "Use your normal screen position, hand angle, posture, and control size because each can change the measured contact. " +
            "The app sends no controller, mouse, or keyboard output."
        setButtons(
            "Start" to ::startCountdown,
            "Cancel calibration" to { cancel() }
        )
        overlay.announceForAccessibility(status.text)
    }

    private fun startCountdown() {
        if (phase != Phase.READY) return
        val prepMs = ((prepSeconds.text.toString().toIntOrNull() ?: 10).coerceIn(3, 30) * 1000L)
        recordingDurationMs =
            ((recordSeconds.text.toString().toIntOrNull() ?: 9).coerceIn(4, 30) * 1000L)
        phase = Phase.COUNTDOWN
        val token = ++generation
        timingRow.visibility = View.GONE
        progress.visibility = View.VISIBLE
        recordingSamples.clear()
        contactActive = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        recordingContactStarted = false
        recordingInterrupted = false
        val started = SystemClock.uptimeMillis()
        fun tick() {
            if (token != generation || phase != Phase.COUNTDOWN) return
            val elapsed = SystemClock.uptimeMillis() - started
            val remaining = (prepMs - elapsed).coerceAtLeast(0L)
            status.text = "Prepare: ${((remaining + 999L) / 1000L)} seconds"
            detail.text = "Move your finger to the outlined TouchAim area when comfortable. Recording will begin automatically."
            progress.progress = ((elapsed.coerceAtMost(prepMs) * 1000L) / prepMs).toInt()
            if (elapsed >= prepMs) startRecording(token) else handler.postDelayed(::tick, 200L)
        }
        tick()
        setButtons("Cancel calibration" to { cancel() })
    }

    private fun startRecording(token: Int) {
        if (token != generation) return
        phase = Phase.RECORDING
        recordingStartedAt = SystemClock.uptimeMillis()
        recordingContactStarted = contactActive
        movementCueSent = false
        movementPhaseStarted = false
        movementDistancePx = 0f
        movementObserved = false
        lastX = Float.NaN
        lastY = Float.NaN
        vibrate(70L)
        overlay.announceForAccessibility("Recording started. Hold naturally.")
        fun tick() {
            if (token != generation || phase != Phase.RECORDING) return
            val elapsed = SystemClock.uptimeMillis() - recordingStartedAt
            val halfway = recordingDurationMs / 2L
            if (!movementCueSent && elapsed >= halfway) {
                movementCueSent = true
                vibrate(110L)
                overlay.announceForAccessibility("Now move naturally while continuing the same touch posture")
            }
            status.text = if (movementCueSent) "MOVE NATURALLY" else "HOLD NATURALLY"
            detail.text = "Recording ${((recordingDurationMs - elapsed).coerceAtLeast(0L) + 999L) / 1000L} seconds remaining"
            progress.progress = ((elapsed.coerceAtMost(recordingDurationMs) * 1000L) / recordingDurationMs).toInt()
            if (elapsed >= recordingDurationMs) finishRecording() else handler.postDelayed(::tick, 100L)
        }
        tick()
    }

    private fun finishRecording(interrupted: Boolean = false) {
        if (phase != Phase.RECORDING) return
        phase = Phase.REVIEW
        generation++
        recordingInterrupted = recordingInterrupted || interrupted
        val step = steps[stepIndex]
        val still = recordingSamples.count { !it.moving }
        val moving = recordingSamples.count { it.moving }
        pendingPass = TouchAimCalibrationPass(
            state = step.state,
            repetition = step.repetition,
            samples = recordingSamples.toList(),
            completed = recordingContactStarted && !recordingInterrupted &&
                movementObserved && still >= 6 && moving >= 6,
            durationMs = recordingDurationMs
        )
        progress.visibility = View.GONE
        status.text = if (pendingPass?.completed == true) "Recording complete" else "Recording incomplete"
        detail.text = if (pendingPass?.completed == true) {
            "Captured ${recordingSamples.size} samples: $still still and $moving moving."
        } else {
            when {
                recordingInterrupted -> "The recording ended because the active finger lifted or was canceled. Retake it; an interrupted touch never advances the wizard."
                !movementObserved -> "Natural movement was not detected after the cue. Retake and move across the outlined area after the vibration."
                else -> "The TouchAim area did not receive enough still and moving samples. Retake this recording; an unexpected touch never advances the wizard."
            }
        }
        setButtons(
            "Retake" to ::showReady,
            "Continue" to ::continueAfterRecording,
            "Cancel calibration" to { cancel() }
        )
        buttonColumn.getChildAt(1).isEnabled = pendingPass?.completed == true
        overlay.announceForAccessibility(status.text)
    }

    private fun continueAfterRecording() {
        val pass = pendingPass?.takeIf { it.completed } ?: return
        passes.removeAll { it.state == pass.state && it.repetition == pass.repetition }
        passes += pass
        pendingPass = null
        stepIndex++
        if (stepIndex < steps.size) showReady() else analyzeRecordings()
    }

    private fun analyzeRecordings() {
        phase = Phase.RESULTS
        val result = TouchAimCalibrationAnalyzer.analyze(passes)
        recommendation = (result as? TouchAimCalibrationAnalysis.Success)?.recommendation
        validationCompleted = false
        showResults(
            (result as? TouchAimCalibrationAnalysis.Insufficient)?.reason
        )
    }

    private fun showResults(insufficientReason: String? = null) {
        phase = Phase.RESULTS
        generation++
        progress.visibility = View.GONE
        timingRow.visibility = View.GONE
        title.text = "Calibration result"
        val rec = recommendation
        if (rec == null) {
            status.text = "UNRELIABLE"
            detail.text = insufficientReason ?: "The recordings could not be evaluated."
            setButtons(
                "Retake calibration" to ::restartAll,
                "Return to manual TouchAim settings" to ::returnToManualSettings,
                "Cancel" to { cancel() }
            )
            return
        }
        status.text = rec.quality.name.lowercase().replaceFirstChar { it.uppercase() }
        val sensorName = if (rec.sensors.isEmpty()) "No dependable sensor" else
            rec.sensors.joinToString(" + ") { TouchAimCalibrationAnalyzer.run { it.sensor.displayName() } }
        val thresholdText = if (rec.hasBestGuess) {
            "Shoot ON ${format(rec.shootOnThreshold)}; OFF ${format(rec.shootOffThreshold)}\n" +
                "Smoothing ${format(rec.smoothing)}; enter ${rec.enterShootConfirmationMs} ms; return ${rec.returnToAimConfirmationMs} ms\n"
        } else {
            "No threshold is recommended from these recordings.\n"
        }
        detail.text = "$sensorName\n$thresholdText" +
            "Estimated false activations ${percent(rec.falseActivationRate)}; missed activations ${percent(rec.missedActivationRate)}.\n" +
            (if (rec.movementReducedReliability) "Movement made the result less reliable.\n" else "Movement did not materially reduce this result.\n") +
            rec.explanation +
            (if (!rec.isUsable && rec.hasBestGuess)
                "\nThis is an experimental best guess, not a reliable calibration. You may validate and apply it, then edit its thresholds, smoothing, and confirmation times in TouchAim properties."
            else if (!rec.hasBestGuess)
                "\nNo meaningful sensor direction was found, so there is no safe best guess to apply. Retake the calibration or use Manual two-state."
            else "") +
            if (validationCompleted) "\nLive validation completed; quick unexpected transitions: $validationUnexpectedTransitions." else ""

        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (rec.hasBestGuess && !validationCompleted) actions += "Start live validation" to ::startValidation
        if (rec.hasBestGuess && validationCompleted) {
            actions += (if (rec.isUsable) "Apply and save calibration" else "Apply editable best guess") to ::promptApply
        }
        actions += "Retake calibration" to ::restartAll
        actions += "Return to manual TouchAim settings" to ::returnToManualSettings
        actions += "Cancel" to { cancel() }
        setButtons(*actions.toTypedArray())
        overlay.announceForAccessibility("Calibration result: ${status.text}")
    }

    private fun startValidation() {
        val rec = recommendation?.takeIf { it.hasBestGuess } ?: return
        phase = Phase.VALIDATING
        generation++
        validationUnexpectedTransitions = 0
        validationLastTransitionAt = 0L
        validationLastState = TwoStateTouchAimState.AIM
        validationSawShoot = false
        validationSawReturn = false
        validationMachine = TwoStateTouchAimStateMachine(
            TwoStateTouchAimConfig(
                rec.shootOnThreshold,
                rec.shootOffThreshold,
                rec.smoothing,
                rec.enterShootConfirmationMs,
                rec.returnToAimConfirmationMs,
                model.touchAimShootBehavior,
                hasAimPayload = false,
                keepAimPayloadWhileShooting = true,
                hasShootPayload = false
            )
        )
        title.text = "Live validation — outputs disabled"
        status.text = "AIM"
        detail.text = "Use the outlined TouchAim area. Intentionally move between Aim and Shoot. Quick reversals are counted as unexpected transitions."
        setButtons(
            "Stop validation" to {
                if (!validationSawShoot || !validationSawReturn) {
                    Toast.makeText(activity, "Complete at least one Aim to Shoot to Aim cycle before stopping.", Toast.LENGTH_LONG).show()
                } else {
                    validationMachine?.terminate(TwoStateTouchAimTermination.CANCEL)
                    validationMachine = null
                    validationCompleted = true
                    showResults()
                }
            },
            "Cancel calibration" to { cancel() }
        )
    }

    private fun restartAll() {
        passes.clear()
        pendingPass = null
        recommendation = null
        stepIndex = 0
        validationCompleted = false
        validationUnexpectedTransitions = 0
        showReady()
    }

    private fun promptApply() {
        val rec = recommendation?.takeIf { it.hasBestGuess && validationCompleted } ?: return
        val isUnreliableBestGuess = rec.quality == TouchAimCalibrationQuality.UNRELIABLE
        val input = EditText(activity).apply {
            hint = "Calibration name"
            setText(existingProfile?.name ?: suggestedProfileName())
            selectAll()
        }
        val dialogGeneration = generation
        applyDialog = AlertDialog.Builder(activity)
            .setTitle(if (isUnreliableBestGuess) "Save and apply best guess" else "Save and apply calibration")
            .setMessage(
                if (isUnreliableBestGuess) {
                    "This result was rated Unreliable. Applying it is an explicit experiment, not a claim that it will work reliably. It remains editable in TouchAim properties; use Release All if output behaves unexpectedly. Other controls are not changed."
                } else {
                    "This copies the calibration into this TouchAim control. Other controls are not changed."
                }
            )
            .setView(input)
            .setPositiveButton("Save and apply") { _, _ ->
                if (phase == Phase.CLOSED || dialogGeneration != generation) return@setPositiveButton
                val profile = buildProfile(input.text.toString().trim(), rec)
                val result = TouchAimCalibrationStore.save(activity, profile)
                if (result.succeeded) {
                    if (profile.applyTo(model, allowUnreliableBestGuess = isUnreliableBestGuess)) {
                        Toast.makeText(activity, "Applied \"${profile.name}\"", Toast.LENGTH_LONG).show()
                        close(applied = true)
                    } else {
                        Toast.makeText(activity, "This calibration is no longer safe to apply.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(activity, result.error, Toast.LENGTH_LONG).show()
                    showResults()
                }
            }
            .setNegativeButton("Cancel", null)
            .create().also { dialog ->
                dialog.setOnDismissListener { if (applyDialog === dialog) applyDialog = null }
                dialog.show()
            }
    }

    private fun buildProfile(
        name: String,
        rec: TouchAimCalibrationRecommendation
    ): TouchAimCalibrationProfile {
        val now = System.currentTimeMillis()
        val metrics = activity.resources.displayMetrics
        return TouchAimCalibrationProfile(
            id = existingProfile?.id ?: UUID.randomUUID().toString(),
            name = name.ifBlank { suggestedProfileName() },
            createdAtEpochMs = existingProfile?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
            deviceManufacturer = Build.MANUFACTURER.orEmpty(),
            deviceModel = Build.MODEL.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            orientation = activity.resources.configuration.orientation.toString(),
            displayWidthPx = metrics.widthPixels,
            displayHeightPx = metrics.heightPixels,
            displayDensity = metrics.density,
            controlWidthPx = model.w,
            controlHeightPx = model.h,
            normalizedControlX = model.x / canvas.width.coerceAtLeast(1),
            normalizedControlY = model.y / canvas.height.coerceAtLeast(1),
            sensors = rec.sensors,
            smoothing = rec.smoothing,
            shootOnThreshold = rec.shootOnThreshold,
            shootOffThreshold = rec.shootOffThreshold,
            enterShootConfirmationMs = rec.enterShootConfirmationMs,
            returnToAimConfirmationMs = rec.returnToAimConfirmationMs,
            quality = rec.quality,
            estimatedFalseActivationRate = rec.falseActivationRate,
            estimatedMissedActivationRate = rec.missedActivationRate,
            movementReducedReliability = rec.movementReducedReliability,
            consistencyScore = rec.consistencyScore,
            validationCompleted = validationCompleted,
            validationUnexpectedTransitions = validationUnexpectedTransitions,
            passSummaries = rec.passSummaries,
            aimOutput = existingProfile?.aimOutput ?: model.touchAimOutput,
            aimSensitivity = existingProfile?.aimSensitivity ?: model.sensitivity,
            shootSensitivity = existingProfile?.shootSensitivity ?: model.touchAimShootSensitivity,
            aimPayload = existingProfile?.aimPayload ?: model.touchAimAimPayload,
            shootPayload = existingProfile?.shootPayload ?: model.touchAimShootPayload,
            keepAimPayloadWhileShooting = existingProfile?.keepAimPayloadWhileShooting
                ?: model.touchAimKeepAimPayloadWhileShooting,
            shootBehavior = existingProfile?.shootBehavior ?: model.touchAimShootBehavior
        )
    }

    private fun handleCaptureTouch(event: MotionEvent) {
        if (phase == Phase.CLOSED) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                contactActive = true
                activePointerId = event.getPointerId(event.actionIndex)
                if (phase == Phase.RECORDING) recordingContactStarted = true
                if (phase == Phase.VALIDATING) {
                    validationMachine?.onContactStarted(event.eventTime)
                }
            }
            MotionEvent.ACTION_MOVE -> Unit
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP &&
                    event.getPointerId(event.actionIndex) != activePointerId) return
                contactActive = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                validationDeadlineRunnable?.let(handler::removeCallbacks)
                validationDeadlineRunnable = null
                validationMachine?.terminate(
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        TwoStateTouchAimTermination.FINGER_LIFT
                    } else {
                        TwoStateTouchAimTermination.CANCEL
                    }
                )
                if (phase == Phase.RECORDING) finishRecording(interrupted = true)
                return
            }
            else -> return
        }
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex !in 0 until event.pointerCount) {
            if (phase == Phase.RECORDING) finishRecording(interrupted = true)
            return
        }
        for (historyIndex in 0 until event.historySize) {
            acceptSample(
                event.getHistoricalX(pointerIndex, historyIndex),
                event.getHistoricalY(pointerIndex, historyIndex),
                event.getHistoricalSize(pointerIndex, historyIndex),
                event.getHistoricalTouchMajor(pointerIndex, historyIndex),
                event.getHistoricalTouchMinor(pointerIndex, historyIndex),
                event.getHistoricalEventTime(historyIndex)
            )
        }
        acceptSample(
            event.getX(pointerIndex),
            event.getY(pointerIndex),
            event.getSize(pointerIndex),
            event.getTouchMajor(pointerIndex),
            event.getTouchMinor(pointerIndex),
            event.eventTime
        )
    }

    private fun acceptSample(x: Float, y: Float, size: Float, major: Float, minor: Float, eventTime: Long) {
        if (!x.isFinite() || !y.isFinite() || !size.isFinite() || !major.isFinite() || !minor.isFinite()) return
        when (phase) {
            Phase.RECORDING -> {
                val elapsed = (eventTime - recordingStartedAt).coerceAtLeast(0L)
                val afterCue = elapsed >= recordingDurationMs / 2L
                if (afterCue && !movementPhaseStarted) {
                    movementPhaseStarted = true
                    movementDistancePx = 0f
                    lastX = x
                    lastY = y
                } else if (afterCue && lastX.isFinite() && lastY.isFinite()) {
                    movementDistancePx += hypot(x - lastX, y - lastY)
                    if (movementDistancePx >= dp(12)) movementObserved = true
                    lastX = x
                    lastY = y
                } else {
                    lastX = x
                    lastY = y
                }
                recordingSamples += TouchAimSensorSample(
                    size.coerceAtLeast(0f),
                    major.coerceAtLeast(0f),
                    minor.coerceAtLeast(0f),
                    moving = afterCue && movementObserved,
                    elapsedMs = elapsed
                )
            }
            Phase.VALIDATING -> {
                val rec = recommendation ?: return
                val score = TouchAimCalibrationAnalyzer.score(
                    TouchAimSensorSample(size, major, minor, moving = false, elapsedMs = eventTime),
                    rec.sensors
                )
                val machine = validationMachine ?: return
                val previous = machine.state
                machine.onScore(score, eventTime)
                processValidationTransition(previous, machine.state, eventTime)
                scheduleValidationDeadline(machine)
                val next = machine.state
                status.text = if (next == TwoStateTouchAimState.SHOOT) "SHOOT" else "AIM"
                detail.text = "Score ${format(machine.filteredScore)} — quick unexpected transitions: $validationUnexpectedTransitions\nAll controller outputs remain disabled."
            }
            else -> Unit
        }
    }

    private fun processValidationTransition(
        previous: TwoStateTouchAimState,
        next: TwoStateTouchAimState,
        timeMs: Long
    ) {
        if (next == previous) return
        if (validationLastTransitionAt > 0L && timeMs - validationLastTransitionAt < 300L) {
            validationUnexpectedTransitions++
        }
        if (previous == TwoStateTouchAimState.AIM && next == TwoStateTouchAimState.SHOOT) {
            validationSawShoot = true
        } else if (previous == TwoStateTouchAimState.SHOOT && next == TwoStateTouchAimState.AIM && validationSawShoot) {
            validationSawReturn = true
        }
        validationLastTransitionAt = timeMs
        validationLastState = next
        overlay.announceForAccessibility(if (next == TwoStateTouchAimState.SHOOT) "SHOOT" else "AIM")
    }

    private fun scheduleValidationDeadline(machine: TwoStateTouchAimStateMachine) {
        validationDeadlineRunnable?.let(handler::removeCallbacks)
        validationDeadlineRunnable = null
        val deadline = machine.nextDeadlineMs() ?: return
        val token = generation
        val runnable = Runnable {
            validationDeadlineRunnable = null
            if (token != generation || phase != Phase.VALIDATING || validationMachine !== machine) return@Runnable
            val previous = machine.state
            val now = SystemClock.uptimeMillis()
            machine.onTime(now)
            processValidationTransition(previous, machine.state, now)
            status.text = if (machine.state == TwoStateTouchAimState.SHOOT) "SHOOT" else "AIM"
            detail.text = "Score ${format(machine.filteredScore)} — quick unexpected transitions: $validationUnexpectedTransitions\nAll controller outputs remain disabled."
            scheduleValidationDeadline(machine)
        }
        validationDeadlineRunnable = runnable
        handler.postDelayed(runnable, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private fun returnToManualSettings() {
        close(applied = false)
        targetView.post { targetView.showProps() }
    }

    private fun close(applied: Boolean) {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        generation++
        handler.removeCallbacksAndMessages(null)
        validationDeadlineRunnable = null
        applyDialog?.dismiss()
        applyDialog = null
        validationMachine?.terminate(TwoStateTouchAimTermination.CANCEL)
        validationMachine = null
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        activity.activateReleaseAll(showFeedback = false)
        onClosed(applied)
    }

    private fun setButtons(vararg buttons: Pair<String, () -> Unit>) {
        buttonColumn.removeAllViews()
        buttons.forEach { (label, action) ->
            buttonColumn.addView(Button(activity).apply {
                text = label
                textSize = 18f
                minHeight = dp(58)
                isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
            })
        }
    }

    private fun durationField(value: String) = EditText(activity).apply {
        setText(value)
        inputType = InputType.TYPE_CLASS_NUMBER
        gravity = Gravity.CENTER
        minHeight = dp(50)
        setTextColor(Color.WHITE)
    }

    private fun labeledField(label: String, field: EditText) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(activity).apply {
            text = label
            setTextColor(Color.LTGRAY)
        })
        addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(8)
    }

    private fun suggestedProfileName(): String = when {
        model.y > canvas.height * 0.65f -> "Bottom of screen thresholds"
        model.y < canvas.height * 0.25f -> "Top TouchAim"
        else -> "Middle of screen"
    }

    private fun vibrate(durationMs: Long) {
        val vibrator = activity.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun format(value: Float): String = String.format(Locale.US, "%.3f", value)
    private fun percent(value: Float): String = "${(value * 100f).roundToInt()}%"
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()
}
