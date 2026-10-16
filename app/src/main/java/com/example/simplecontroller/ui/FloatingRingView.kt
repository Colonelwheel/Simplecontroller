package com.example.simplecontroller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.example.simplecontroller.model.FloatingRingConfig
import com.example.simplecontroller.model.FloatingStartRole
import kotlin.math.max

/** A detached surfaced ring; its config is immutable and its position is never written to a page. */
class FloatingRingView(
    context: Context,
    val config: FloatingRingConfig,
    backend: FloatingRingBackend,
    private val onFinish: () -> Unit,
    finishIgnoresSwipes: Boolean = false
) : View(context) {
    val geometry = FloatingRingGeometry(config, resources.displayMetrics.density)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var currentStatus = FloatingRingStatus()
    private var initialGesture = false
    val state = FloatingRingStateMachine(
        config, geometry, backend, HandlerButtonAimPayloadScheduler(), SystemClock::uptimeMillis,
        onStateChanged = { currentStatus = it; invalidate() },
        onFinish = onFinish,
        finishIgnoresSwipes = finishIgnoresSwipes
    )

    init { isClickable = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(geometry.width.toInt().coerceAtLeast(1), geometry.height.toInt().coerceAtLeast(1))
    }

    /** Forward the original DOWN once. Geometry is valid even before the first layout traversal. */
    fun beginInitial(
        event: MotionEvent,
        role: FloatingStartRole = config.startRole,
        logicalOriginOffsetX: Float = 0f,
        logicalOriginOffsetY: Float = 0f
    ): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        initialGesture = true
        val index = event.actionIndex
        val anchor = geometry.startAnchor(role)
        // A clamped placement preserves the chosen role's logical first-contact anchor.
        val offsetX = anchor.x - event.getX(index)
        val offsetY = anchor.y - event.getY(index)
        return state.down(
            event.getPointerId(index), event.getX(index), event.getY(index), event.eventTime,
            role, config.startKnobId,
            offsetX + logicalOriginOffsetX, offsetY + logicalOriginOffsetY,
            useConfiguredMovementStart = role == FloatingStartRole.MOVEMENT,
            checkFinish = false
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = playTouch(event)

    fun playTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialGesture = false
                if (geometry.finish.contains(event.x, event.y)) { state.finish(); return true }
                val role = geometry.roleAt(event.x, event.y) ?: return false
                val knob = geometry.knobAt(event.x, event.y)
                return state.down(event.getPointerId(event.actionIndex), event.x, event.y,
                    event.eventTime, role, knob?.id ?: config.startKnobId)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val id = state.status.pointerId
                if (id < 0) return true
                val index = event.findPointerIndex(id)
                if (index < 0) { state.cancel(); return true }
                for (history in 0 until event.historySize) {
                    state.sample(id, event.getHistoricalX(index, history), event.getHistoricalY(index, history),
                        event.getHistoricalEventTime(history))
                    if (state.status.pointerId < 0) return true
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (event.getPointerId(event.actionIndex) != id) state.cancel()
                    else state.up(id, event.getX(index), event.getY(index), event.eventTime)
                    initialGesture = false
                } else state.sample(id, event.getX(index), event.getY(index), event.eventTime)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == state.status.pointerId) state.cancel()
            }
            MotionEvent.ACTION_CANCEL -> { initialGesture = false; state.cancel() }
            // Additional pointers never inherit or redirect the captured role.
        }
        return true
    }

    fun finish() = state.finish()
    fun stopFiring() = state.stopFiring()
    fun releaseAds() = state.releaseAds()
    fun releaseAll() = state.hardCleanup()
    fun endSuppression() = state.endSuppression()

    /** Includes finite invisible direct-boost areas, used only after actual visible targets win. */
    fun acceptsDown(localX: Float, localY: Float): Boolean =
        geometry.finish.contains(localX, localY) || geometry.roleAt(localX, localY) != null

    override fun onDetachedFromWindow() {
        state.hardCleanup()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = geometry.center.x
        val cy = geometry.center.y
        val compactAim = config.compactAimBoosts && config.aim.participateInBoosts &&
            currentStatus.pointerId >= 0 && currentStatus.role == FloatingStartRole.AIM
        paint.style = Paint.Style.FILL
        if (config.movement.enabled && !compactAim) {
            paint.color = Color.argb(180, 42, 69, 96)
            canvas.drawCircle(cx, cy, geometry.movementRadius, paint)
        }
        if (config.aim.enabled) {
            paint.color = Color.argb(220, 30, 39, 53)
            canvas.drawCircle(cx, cy, geometry.innerRadius, paint)
            config.knobs.filter { it.enabled }.forEach { knob ->
                val point = geometry.knobCenter(knob)
                paint.color = Color.rgb(72, 109, 145)
                canvas.drawCircle(point.x, point.y, knob.targetDiameterDp * geometry.density / 2f, paint)
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 12f * geometry.density
                canvas.drawText(knob.name.take(8), point.x, point.y + 4f * geometry.density, paint)
            }
        }
        if (config.movement.enabled && !compactAim) {
            paint.color = Color.WHITE
            canvas.drawCircle(cx + currentStatus.movementX * geometry.movementRadius,
                cy + currentStatus.movementY * geometry.movementRadius, 7f * geometry.density, paint)
        }
        paint.color = Color.rgb(143, 58, 63)
        canvas.drawRoundRect(geometry.finish.left, geometry.finish.top, geometry.finish.right,
            geometry.finish.bottom, 12f * geometry.density, 12f * geometry.density, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 12f * geometry.density
        canvas.drawText("Finish", (geometry.finish.left + geometry.finish.right) / 2f,
            geometry.finish.bottom / 2f + 4f * geometry.density, paint)
        val labels = buildList {
            if (currentStatus.movementRetained) add("Move held")
            if (currentStatus.aimRetained) add("RS held")
            if (currentStatus.ads) add("ADS")
            if (currentStatus.repeating) add("Repeat") else if (currentStatus.firing) add("Fire held")
            if (currentStatus.armed) add("Shot armed")
            if (currentStatus.pending) add("Waiting")
            if (currentStatus.busy) add("Busy")
            if (currentStatus.fine) add("Fine")
            if (currentStatus.suppressed) add("CF paused")
            currentStatus.boosts.sortedBy { it.ordinal }.forEach { stage ->
                val action = when {
                    stage in currentStatus.repeatingBoosts -> "repeat"
                    stage in currentStatus.latchedBoosts -> "latched"
                    stage in currentStatus.heldBoosts -> if (config.boost(stage).action == com.example.simplecontroller.model.RingAction.TAP) "tap" else "held"
                    else -> ""
                }
                add("B${stage.ordinal + 1}${if (action.isNotEmpty()) " $action" else ""}")
            }
            if (currentStatus.overridden.isNotEmpty()) add("Lost owner")
        }
        paint.textSize = max(9f, 10f * geometry.density)
        val lines = mutableListOf<String>()
        var line = ""
        val maxTextWidth = geometry.width - 12f * geometry.density
        labels.flatMap { if (paint.measureText(it) > maxTextWidth) it.split(' ') else listOf(it) }.forEach { label ->
            val combined = if (line.isEmpty()) label else "$line · $label"
            if (line.isNotEmpty() && paint.measureText(combined) > maxTextWidth) {
                lines.add(line); line = label
            } else line = combined
        }
        if (line.isNotEmpty()) lines.add(line)
        if (currentStatus.blockedByOtherSource) {
            if (paint.measureText("Other source") <= maxTextWidth) {
                lines.add("Other source"); lines.add("holds output")
            } else lines.addAll(listOf("Other", "source", "holds", "output"))
        }
        val firstY = cy + geometry.visibleRadius - 8f * geometry.density -
            (lines.size - 1).coerceAtLeast(0) * 12f * geometry.density
        lines.forEachIndexed { index, text ->
            canvas.drawText(text, cx, firstY + index * 12f * geometry.density, paint)
        }
    }
}
