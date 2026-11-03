package com.example.simplecontroller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import com.example.simplecontroller.model.ControlGeometry
import com.example.simplecontroller.model.FloatingRingConfig
import com.example.simplecontroller.model.RingBoostStage
import com.example.simplecontroller.model.RingBoostBank
import kotlin.math.max

/** Actual-size Edit guides only: no backend, pointer capture, timer or runtime instance. */
internal class FloatingRingEditOverlay(
    context: Context,
    private val config: FloatingRingConfig,
    private val zoneName: String,
    private val zoneEnabled: Boolean,
    initialRectangle: ControlGeometry,
    private val usable: ControlGeometry
) : View(context) {
    private val density = resources.displayMetrics.density
    private val geometry = FloatingRingGeometry(config, density)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rectangle = initialRectangle

    init {
        isClickable = false
        isLongClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateRectangle(value: ControlGeometry) {
        rectangle = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val anchor = geometry.startAnchor(config.startRole)
        val touchX = rectangle.x + rectangle.w / 2f
        val touchY = rectangle.y + rectangle.h / 2f
        val placement = placeFloatingBounds(geometry.width, geometry.height, anchor.x, anchor.y,
            touchX, touchY, usable)
        val origin = placement?.let { RingPoint(it.x, it.y) }
            ?: RingPoint(touchX - anchor.x, touchY - anchor.y)
        val cx = origin.x + geometry.center.x
        val cy = origin.y + geometry.center.y
        canvas.save()
        canvas.clipRect(usable.x, usable.y, usable.x + usable.w, usable.y + usable.h)
        val bank = config.boostBankForRole(config.startRole)
        val bankLabel = if (bank == RingBoostBank.MOVEMENT) "Move" else "Aim"
        val participates = if (bank == RingBoostBank.MOVEMENT)
            config.movement.enabled && config.movement.participateInBoosts
            else config.aim.enabled && config.aim.participateInBoosts
        listOf(RingBoostStage.ONE, RingBoostStage.TWO).forEach { stage ->
            val settings = config.boost(stage, bank)
            val enabled = zoneEnabled && settings.enabled
            val color = if (stage == RingBoostStage.ONE) Color.rgb(240, 194, 87) else Color.rgb(226, 119, 153)
            val name = zoneName.ifBlank { "Ring" }.take(18)
            val label = "$name $bankLabel B${stage.ordinal + 1}" + if (enabled && participates) "" else " OFF"
            val radius = geometry.boostDistance(stage, bank = bank)
            val direct = geometry.directBand(stage, bank)
            val outer = if (stage == RingBoostStage.ONE) geometry.boostDistance(RingBoostStage.TWO, bank = bank)
                else radius + direct.outerRadius - direct.innerRadius
            circle(canvas, cx, cy, outer, enabled && participates, color)
            circle(canvas, cx, cy, radius, enabled && participates, color, false,
                label + if (placement == null) " (does not fit)" else "", stage)
            circle(canvas, cx, cy, direct.innerRadius, enabled && settings.directTapEnabled, color, true)
            circle(canvas, cx, cy, direct.outerRadius, enabled && settings.directTapEnabled, color, true,
                "$name Direct $bankLabel B${stage.ordinal + 1}" + if (enabled && settings.directTapEnabled) "" else " OFF", stage)
        }
        canvas.restore()
    }

    private fun circle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        enabled: Boolean,
        color: Int,
        compactReference: Boolean = false,
        label: String? = null,
        stage: RingBoostStage = RingBoostStage.ONE
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = if (enabled) color else Color.argb(120, 150, 150, 150)
        paint.pathEffect = when {
            compactReference -> DashPathEffect(floatArrayOf(2f * density, 3f * density), 0f)
            !enabled -> DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
            else -> null
        }
        canvas.drawCircle(cx, cy, radius, paint)
        if (label == null) return
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.textSize = 10f * resources.displayMetrics.scaledDensity
        val margin = 4f * density
        val labelX = (cx + radius * .70710677f).coerceIn(usable.x + margin,
            max(usable.x + margin, usable.x + usable.w - paint.measureText(label) - margin))
        val labelY = (cy + (if (stage == RingBoostStage.ONE) -1f else 1f) * radius * .70710677f)
            .coerceIn(usable.y + paint.textSize, max(usable.y + paint.textSize, usable.y + usable.h - margin))
        canvas.drawText(label, labelX, labelY, paint)
    }
}
