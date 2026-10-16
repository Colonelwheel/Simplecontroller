package com.example.simplecontroller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Isolated, Android-only touch sensor diagnostic. This activity deliberately does not use the
 * controller canvas or any network classes, so its touch ownership cannot affect controller input.
 */
class TouchDiagnosticActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showDiagnostic()
    }

    private fun showDiagnostic() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(15, 20, 24))
        }
        root.addView(
            TouchDiagnosticView(this) { report -> showReport(report) },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
    }

    private fun showReport(report: String) {
        val padding = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(15, 20, 24))
        }

        content.addView(TextView(this).apply {
            text = "Calibration summary"
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "Ranges include both still and moving stages. Scroll to review all results."
            textSize = 16f
            setTextColor(Color.rgb(154, 208, 199))
            setPadding(0, dp(8), 0, dp(16))
        })
        content.addView(TextView(this).apply {
            text = report
            textSize = 16f
            setTextColor(Color.rgb(235, 239, 241))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(20))
        }
        buttons.addView(Button(this).apply {
            text = "Copy results"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Touch diagnostic", report))
                Toast.makeText(this@TouchDiagnosticActivity, "Results copied", Toast.LENGTH_SHORT)
                    .show()
            }
        })
        buttons.addView(Button(this).apply {
            text = "Run again"
            setOnClickListener { showDiagnostic() }
        })
        buttons.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        })
        content.addView(buttons)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class TouchDiagnosticView(
    context: Context,
    private val onComplete: (String) -> Unit
) : View(context) {

    private enum class Gesture(val displayName: String) {
        NORMAL("NORMAL"),
        FLATTENED("FLATTENED"),
        PRESSED("PRESSED")
    }

    private data class Stage(
        val title: String,
        val instruction: String,
        val gesture: Gesture,
        val moving: Boolean
    )

    private data class SensorSample(
        val pressure: Float,
        val size: Float,
        val touchMajor: Float,
        val touchMinor: Float
    )

    private enum class Signal(val displayName: String, val unit: String) {
        PRESSURE("Pressure", ""),
        SIZE("Size", ""),
        TOUCH_MAJOR("TouchMajor", " px"),
        TOUCH_MINOR("TouchMinor", " px")
    }

    private class RunningStats {
        var current = 0f
        var minimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        var sum = 0.0
        var count = 0

        val average: Float
            get() = if (count == 0) 0f else (sum / count).toFloat()

        fun clear() {
            current = 0f
            minimum = Float.POSITIVE_INFINITY
            maximum = Float.NEGATIVE_INFINITY
            sum = 0.0
            count = 0
        }

        fun add(value: Float) {
            if (!value.isFinite()) return
            current = value
            minimum = min(minimum, value)
            maximum = max(maximum, value)
            sum += value
            count++
        }
    }

    private data class SummaryStats(
        val minimum: Float,
        val maximum: Float,
        val average: Float,
        val count: Int
    )

    private data class Separation(
        val label: String,
        val rank: Int,
        val detail: String
    )

    private data class Thresholds(
        val on: Float,
        val off: Float,
        val direction: String,
        val provisional: Boolean
    )

    private val stages = listOf(
        Stage("TOUCH NORMALLY", "Hold mostly still", Gesture.NORMAL, false),
        Stage("FLATTEN FINGER", "Keep holding mostly still", Gesture.FLATTENED, false),
        Stage("PRESS HARDER", "Keep holding mostly still", Gesture.PRESSED, false),
        Stage("MOVE: NORMAL TOUCH", "Drag around while touching normally", Gesture.NORMAL, true),
        Stage("MOVE: FLATTENED", "Drag around with your finger flat", Gesture.FLATTENED, true),
        Stage("MOVE: PRESS HARDER", "Drag around while pressing harder", Gesture.PRESSED, true)
    )

    private val samplesByStage = Array(stages.size) { mutableListOf<SensorSample>() }
    private val pressureStats = RunningStats()
    private val sizeStats = RunningStats()
    private val majorStats = RunningStats()
    private val minorStats = RunningStats()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 211, 190)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var touchStartTime = 0L
    private var stageIndex = 0
    private var isRunning = false
    private var runComplete = false
    private var restartMessage: String? = null
    private var currentX = 0f
    private var currentY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var movementDistance = 0f

    private val stageTicker = object : Runnable {
        override fun run() {
            if (!isRunning) return
            updateClock(SystemClock.uptimeMillis())
            if (isRunning) postDelayed(this, TIMER_UPDATE_MS)
        }
    }

    init {
        isFocusable = true
        contentDescription = "Touch sensor diagnostic area"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(15, 20, 24))

        if (!isRunning && !runComplete) {
            drawCentered(canvas, "TOUCH SENSOR TEST", 42f, 27f, boldPaint)
            drawCentered(
                canvas,
                restartMessage ?: "Put one finger down to begin",
                88f,
                22f,
                accentPaint
            )
            drawCentered(canvas, "Keep the same finger down for all six stages.", 124f, 17f, paint)
            drawCentered(canvas, "Each stage records for 2 seconds.", 150f, 17f, paint)
            drawCentered(canvas, "The last three stages include dragging.", 176f, 17f, paint)
            drawCentered(canvas, "Lift early at any time to restart safely.", 216f, 16f, paint)
            return
        }

        if (runComplete) {
            drawCentered(canvas, "CALIBRATION COMPLETE", 90f, 28f, accentPaint)
            drawCentered(canvas, "Lift your finger to see and copy the results.", 136f, 19f, paint)
            return
        }

        val stage = stages[stageIndex]
        val elapsedInStage = (SystemClock.uptimeMillis() - touchStartTime) % STAGE_DURATION_MS
        val recordingElapsed = (elapsedInStage - SETTLE_DURATION_MS).coerceAtLeast(0L)
        val remainingSeconds = ((RECORD_DURATION_MS - recordingElapsed) / 100L)
            .coerceAtLeast(0L) / 10f
        val timingText = if (elapsedInStage < SETTLE_DURATION_MS) {
            val settleSeconds = ((SETTLE_DURATION_MS - elapsedInStage) / 100L) / 10f
            "Get ready  |  recording in ${format(settleSeconds)} s"
        } else {
            "Stage ${stageIndex + 1}/${stages.size}  |  recording ${format(remainingSeconds)} s"
        }

        drawCentered(canvas, stage.title, 34f, 26f, accentPaint)
        drawCentered(canvas, stage.instruction, 65f, 17f, paint)
        drawCentered(
            canvas,
            timingText,
            91f,
            16f,
            paint
        )
        drawProgress(canvas, recordingElapsed.toFloat() / RECORD_DURATION_MS)

        val left = 16f
        val columnWidth = (width / resources.displayMetrics.density - 48f) / 2f
        drawMetric(canvas, "PRESSURE", pressureStats, left, 136f, columnWidth)
        drawMetric(canvas, "SIZE", sizeStats, left + columnWidth + 16f, 136f, columnWidth)
        drawMetric(canvas, "TOUCH MAJOR", majorStats, left, 288f, columnWidth)
        drawMetric(canvas, "TOUCH MINOR", minorStats, left + columnWidth + 16f, 288f, columnWidth)

        paint.color = Color.rgb(190, 200, 205)
        drawCentered(
            canvas,
            "X ${format(currentX)}   Y ${format(currentY)}",
            458f,
            18f,
            paint
        )
        drawCentered(
            canvas,
            "From start: X ${signed(currentX - downX)}   Y ${signed(currentY - downY)}",
            486f,
            16f,
            paint
        )
        drawCentered(
            canvas,
            "Path ${format(movementDistance)} px   Samples ${pressureStats.count}",
            512f,
            16f,
            paint
        )
        paint.color = Color.WHITE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startRun(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isRunning) {
                    captureEvent(event)
                    updateClock(event.eventTime)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    cancelRun("Active finger lifted. Touch normally to restart.")
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isRunning) {
                    captureEvent(event)
                    updateClock(event.eventTime)
                }
                if (runComplete) {
                    performClick()
                    onComplete(buildReport())
                } else {
                    cancelRun("Finger lifted early. Touch normally to restart.")
                }
                activePointerId = MotionEvent.INVALID_POINTER_ID
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelRun("Touch was cancelled. Touch normally to restart.")
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(stageTicker)
        super.onDetachedFromWindow()
    }

    private fun startRun(event: MotionEvent) {
        removeCallbacks(stageTicker)
        samplesByStage.forEach { it.clear() }
        listOf(pressureStats, sizeStats, majorStats, minorStats).forEach { it.clear() }

        activePointerId = event.getPointerId(event.actionIndex)
        touchStartTime = event.eventTime
        stageIndex = 0
        isRunning = true
        runComplete = false
        restartMessage = null
        currentX = event.getX(event.actionIndex)
        currentY = event.getY(event.actionIndex)
        downX = currentX
        downY = currentY
        lastX = currentX
        lastY = currentY
        movementDistance = 0f
        captureEvent(event)
        postDelayed(stageTicker, TIMER_UPDATE_MS)
        invalidate()
    }

    private fun cancelRun(message: String) {
        removeCallbacks(stageTicker)
        isRunning = false
        runComplete = false
        restartMessage = message
        activePointerId = MotionEvent.INVALID_POINTER_ID
        invalidate()
    }

    private fun updateClock(now: Long) {
        val elapsed = now - touchStartTime
        if (elapsed >= stages.size * STAGE_DURATION_MS) {
            isRunning = false
            runComplete = true
            removeCallbacks(stageTicker)
            invalidate()
            return
        }

        val nextStage = (elapsed / STAGE_DURATION_MS).toInt().coerceIn(stages.indices)
        if (nextStage != stageIndex) {
            stageIndex = nextStage
            announceForAccessibility("${stages[stageIndex].title}. ${stages[stageIndex].instruction}")
        }
        invalidate()
    }

    private fun captureEvent(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) return

        for (historyIndex in 0 until event.historySize) {
            addSample(
                time = event.getHistoricalEventTime(historyIndex),
                pressure = event.getHistoricalPressure(pointerIndex, historyIndex),
                size = event.getHistoricalSize(pointerIndex, historyIndex),
                touchMajor = event.getHistoricalTouchMajor(pointerIndex, historyIndex),
                touchMinor = event.getHistoricalTouchMinor(pointerIndex, historyIndex),
                x = event.getHistoricalX(pointerIndex, historyIndex),
                y = event.getHistoricalY(pointerIndex, historyIndex)
            )
        }

        addSample(
            time = event.eventTime,
            pressure = event.getPressure(pointerIndex),
            size = event.getSize(pointerIndex),
            touchMajor = event.getTouchMajor(pointerIndex),
            touchMinor = event.getTouchMinor(pointerIndex),
            x = event.getX(pointerIndex),
            y = event.getY(pointerIndex)
        )
    }

    private fun addSample(
        time: Long,
        pressure: Float,
        size: Float,
        touchMajor: Float,
        touchMinor: Float,
        x: Float,
        y: Float
    ) {
        val elapsed = time - touchStartTime
        val sampleStage = (elapsed / STAGE_DURATION_MS).toInt()
        if (sampleStage !in stages.indices) return

        val elapsedInStage = elapsed % STAGE_DURATION_MS
        if (elapsedInStage >= SETTLE_DURATION_MS) {
            samplesByStage[sampleStage].add(SensorSample(pressure, size, touchMajor, touchMinor))
        }
        pressureStats.add(pressure)
        sizeStats.add(size)
        majorStats.add(touchMajor)
        minorStats.add(touchMinor)

        movementDistance += hypot(x - lastX, y - lastY)
        lastX = x
        lastY = y
        currentX = x
        currentY = y
    }

    private fun buildReport(): String {
        val report = StringBuilder()
        report.appendLine("TOUCH SENSOR DIAGNOSTIC")
        report.appendLine("Each stage: 0.5 second settle + 2.0 seconds recorded")
        report.appendLine("Total samples: ${samplesByStage.sumOf { it.size }}")
        report.appendLine("Total path: ${format(movementDistance)} px")
        report.appendLine("Ranges below include still + moving samples.")

        Gesture.entries.forEach { gesture ->
            report.appendLine()
            report.appendLine(gesture.displayName)
            Signal.entries.forEach { signal ->
                val stats = summaryFor(gesture, signal)
                report.appendLine("${signal.displayName}: ${describe(stats, signal.unit)}")
            }
        }

        report.appendLine()
        report.appendLine("MOTION EFFECT (moving avg - still avg)")
        Gesture.entries.forEach { gesture ->
            val deltas = Signal.entries.joinToString("  ") { signal ->
                val still = summaryFor(gesture, signal, moving = false)
                val moving = summaryFor(gesture, signal, moving = true)
                val abbreviation = when (signal) {
                    Signal.PRESSURE -> "P"
                    Signal.SIZE -> "S"
                    Signal.TOUCH_MAJOR -> "Maj"
                    Signal.TOUCH_MINOR -> "Min"
                }
                if (still == null || moving == null) "$abbreviation n/a"
                else "$abbreviation ${signed(moving.average - still.average)}"
            }
            report.appendLine("${gesture.displayName}: $deltas")
        }

        val normal = Gesture.NORMAL
        val flattened = Gesture.FLATTENED
        val pressed = Gesture.PRESSED
        val pressureSeparation = separation(
            summaryFor(normal, Signal.PRESSURE),
            summaryFor(pressed, Signal.PRESSURE)
        )
        val flattenSeparations = listOf(
            Signal.SIZE,
            Signal.TOUCH_MAJOR,
            Signal.TOUCH_MINOR
        ).associateWith { signal ->
            separation(summaryFor(normal, signal), summaryFor(flattened, signal))
        }

        report.appendLine()
        report.appendLine("SIGNAL SEPARATION (full recorded ranges)")
        report.appendLine("Pressure, normal vs pressed: ${pressureSeparation.label} (${pressureSeparation.detail})")
        flattenSeparations.forEach { (signal, result) ->
            report.appendLine("${signal.displayName}, normal vs flat: ${result.label} (${result.detail})")
        }

        val bestFlattenSignal = flattenSeparations.maxByOrNull { it.value.rank }?.key
        if (bestFlattenSignal != null) {
            val normalStats = summaryFor(normal, bestFlattenSignal)
            val flatStats = summaryFor(flattened, bestFlattenSignal)
            val result = flattenSeparations.getValue(bestFlattenSignal)
            report.appendLine()
            report.appendLine("BEST OBSERVED FLATTENING SIGNAL")
            report.appendLine("${bestFlattenSignal.displayName}: ${result.label}")
            appendRecommendation(
                report,
                normalStats,
                flatStats,
                bestFlattenSignal.unit,
                "flattened"
            )
        }

        report.appendLine()
        report.appendLine("PRESSURE THRESHOLD CANDIDATE")
        appendRecommendation(
            report,
            summaryFor(normal, Signal.PRESSURE),
            summaryFor(pressed, Signal.PRESSURE),
            Signal.PRESSURE.unit,
            "pressed"
        )
        report.appendLine()
        report.append("Thresholds are diagnostic suggestions only; no controller input was sent.")
        return report.toString()
    }

    private fun appendRecommendation(
        report: StringBuilder,
        baseline: SummaryStats?,
        target: SummaryStats?,
        unit: String,
        targetName: String
    ) {
        if (baseline == null || target == null) {
            report.appendLine("Not enough samples for a threshold.")
            return
        }
        val result = separation(baseline, target)
        val thresholds = thresholds(baseline, target, result.rank < 3)
        report.appendLine("Normal range: ${format(baseline.minimum)}-${format(baseline.maximum)}$unit")
        report.appendLine("${targetName.replaceFirstChar { it.uppercase() }} range: ${format(target.minimum)}-${format(target.maximum)}$unit")
        report.appendLine("Direction: trigger when value is ${thresholds.direction}")
        val prefix = if (thresholds.provisional) "Provisional " else "Suggested "
        report.appendLine("${prefix}ON: ${format(thresholds.on)}$unit")
        report.appendLine("${prefix}OFF: ${format(thresholds.off)}$unit")
    }

    private fun summaryFor(
        gesture: Gesture,
        signal: Signal,
        moving: Boolean? = null
    ): SummaryStats? {
        val values = stages.indices
            .asSequence()
            .filter { stages[it].gesture == gesture }
            .filter { moving == null || stages[it].moving == moving }
            .flatMap { samplesByStage[it].asSequence() }
            .map { sample -> sample.value(signal) }
            .filter { it.isFinite() }
            .toList()
        if (values.isEmpty()) return null
        return SummaryStats(values.min(), values.max(), values.average().toFloat(), values.size)
    }

    private fun SensorSample.value(signal: Signal): Float = when (signal) {
        Signal.PRESSURE -> pressure
        Signal.SIZE -> size
        Signal.TOUCH_MAJOR -> touchMajor
        Signal.TOUCH_MINOR -> touchMinor
    }

    private fun separation(baseline: SummaryStats?, target: SummaryStats?): Separation {
        if (baseline == null || target == null) return Separation("Poor", 0, "not enough samples")

        val baselineRange = baseline.maximum - baseline.minimum
        val targetRange = target.maximum - target.minimum
        if (baselineRange < MIN_RANGE && targetRange < MIN_RANGE &&
            kotlin.math.abs(baseline.average - target.average) < MIN_RANGE
        ) {
            return Separation("Poor", 0, "constant and identical")
        }

        val overlap = min(baseline.maximum, target.maximum) - max(baseline.minimum, target.minimum)
        val baselineWidth = max(baselineRange, MIN_RANGE)
        val targetWidth = max(targetRange, MIN_RANGE)
        if (overlap <= 0f) {
            val gap = -overlap
            val relativeGap = gap / max((baselineWidth + targetWidth) / 2f, MIN_RANGE)
            return if (relativeGap >= 0.25f) {
                Separation("Excellent", 4, "no overlap, gap ${format(gap)}")
            } else {
                Separation("Good", 3, "no overlap, gap ${format(gap)}")
            }
        }

        val overlapFraction = overlap / min(baselineWidth, targetWidth)
        return when {
            overlapFraction <= 0.10f -> Separation("Good", 3, "${percent(overlapFraction)} overlap")
            overlapFraction <= 0.35f -> Separation("Fair", 2, "${percent(overlapFraction)} overlap")
            else -> Separation("Poor", 1, "${percent(overlapFraction)} overlap")
        }
    }

    private fun thresholds(
        baseline: SummaryStats,
        target: SummaryStats,
        provisional: Boolean
    ): Thresholds {
        val targetIsHigher = target.average >= baseline.average
        val separated = if (targetIsHigher) {
            target.minimum > baseline.maximum
        } else {
            target.maximum < baseline.minimum
        }

        if (separated) {
            val low = if (targetIsHigher) baseline.maximum else target.maximum
            val high = if (targetIsHigher) target.minimum else baseline.minimum
            val oneThird = (high - low) / 3f
            return if (targetIsHigher) {
                Thresholds(low + oneThird * 2f, low + oneThird, "at or above ON", provisional)
            } else {
                Thresholds(low + oneThird, low + oneThird * 2f, "at or below ON", provisional)
            }
        }

        val midpoint = (baseline.average + target.average) / 2f
        val hysteresis = max(kotlin.math.abs(target.average - baseline.average) * 0.15f, MIN_RANGE)
        return if (targetIsHigher) {
            Thresholds(midpoint + hysteresis, midpoint - hysteresis, "at or above ON", true)
        } else {
            Thresholds(midpoint - hysteresis, midpoint + hysteresis, "at or below ON", true)
        }
    }

    private fun describe(stats: SummaryStats?, unit: String): String {
        if (stats == null) return "no samples"
        return "avg ${format(stats.average)}  range ${format(stats.minimum)}-${format(stats.maximum)}$unit  n=${stats.count}"
    }

    private fun drawMetric(
        canvas: Canvas,
        label: String,
        stats: RunningStats,
        leftDp: Float,
        topDp: Float,
        widthDp: Float
    ) {
        val left = dp(leftDp)
        val top = dp(topDp)
        boldPaint.textSize = sp(19f)
        boldPaint.color = Color.rgb(255, 190, 92)
        canvas.drawText(label, left, top, boldPaint)

        paint.textSize = sp(16f)
        paint.color = Color.rgb(235, 239, 241)
        val minValue = if (stats.count == 0) 0f else stats.minimum
        val maxValue = if (stats.count == 0) 0f else stats.maximum
        canvas.drawText("Current  ${format(stats.current)}", left, top + dp(28f), paint)
        canvas.drawText("Min      ${format(minValue)}", left, top + dp(52f), paint)
        canvas.drawText("Max      ${format(maxValue)}", left, top + dp(76f), paint)
        canvas.drawText("Avg      ${format(stats.average)}", left, top + dp(100f), paint)

        paint.color = Color.rgb(55, 67, 73)
        paint.strokeWidth = dp(1f)
        canvas.drawLine(left, top + dp(116f), left + dp(widthDp), top + dp(116f), paint)
    }

    private fun drawProgress(canvas: Canvas, fraction: Float) {
        val left = dp(20f)
        val right = width - dp(20f)
        val top = dp(107f)
        val bottom = dp(115f)
        paint.color = Color.rgb(55, 67, 73)
        canvas.drawRect(left, top, right, bottom, paint)
        paint.color = Color.rgb(70, 211, 190)
        canvas.drawRect(left, top, left + (right - left) * fraction.coerceIn(0f, 1f), bottom, paint)
    }

    private fun drawCentered(canvas: Canvas, text: String, baselineDp: Float, textSp: Float, source: Paint) {
        source.textSize = sp(textSp)
        val x = (width - source.measureText(text)) / 2f
        canvas.drawText(text, max(dp(8f), x), dp(baselineDp), source)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private fun format(value: Float): String = String.format(Locale.US, "%.3f", value)

    private fun signed(value: Float): String = String.format(Locale.US, "%+.3f", value)

    private fun percent(value: Float): String = String.format(Locale.US, "%.0f%%", value * 100f)

    companion object {
        private const val SETTLE_DURATION_MS = 500L
        private const val RECORD_DURATION_MS = 2_000L
        private const val STAGE_DURATION_MS = SETTLE_DURATION_MS + RECORD_DURATION_MS
        private const val TIMER_UPDATE_MS = 50L
        private const val MIN_RANGE = 0.0001f
    }
}
