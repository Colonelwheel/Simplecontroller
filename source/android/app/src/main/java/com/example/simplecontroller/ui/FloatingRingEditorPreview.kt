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
import kotlin.math.min

/** Read-only preview. It creates no runtime session, output adapter, handlers, or touch claims. */
class FloatingRingEditorPreview(
    context: Context,
    private val config: FloatingRingConfig,
    private val usableWidthPx: Float,
    private val usableHeightPx: Float,
    private val bank: RingBoostBank = config.directBoostBank()
) : View(context) {
    private val density = resources.displayMetrics.density
    private val geometry = FloatingRingGeometry(config, density)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val usable = ControlGeometry(0f, 0f, usableWidthPx, usableHeightPx)
    private val anchor = geometry.startAnchor(config.startRole)
    private val placement = placeFloatingBounds(geometry.width, geometry.height, anchor.x, anchor.y,
        usableWidthPx / 2f, usableHeightPx / 2f, usable)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (340f * density).toInt()
        val measuredWidth = resolveSize(desiredWidth, widthMeasureSpec)
        val canvasHeight = if (usableWidthPx > 0f) measuredWidth * usableHeightPx / usableWidthPx else measuredWidth.toFloat()
        setMeasuredDimension(measuredWidth,
            resolveSize((canvasHeight + 200f * density).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = 12f * density
        val feedbackHeight = 190f * density
        if (usableWidthPx <= 0f || usableHeightPx <= 0f) {
            label(canvas, "Canvas is not measured yet.", padding, padding * 2f)
            return
        }
        val scale = min((width - padding * 2f) / usableWidthPx,
            (height - feedbackHeight - padding * 2f) / usableHeightPx).coerceAtLeast(.01f)
        val sceneWidth = usableWidthPx * scale
        val sceneHeight = usableHeightPx * scale
        val x0 = (width - sceneWidth) / 2f
        val y0 = padding
        canvas.save()
        canvas.translate(x0, y0)
        canvas.scale(scale, scale)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(20, 28, 38)
        canvas.drawRect(0f, 0f, usableWidthPx, usableHeightPx, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density / scale
        paint.color = Color.LTGRAY
        canvas.drawRect(0f, 0f, usableWidthPx, usableHeightPx, paint)
        canvas.clipRect(0f, 0f, usableWidthPx, usableHeightPx)
        // Oversize configurations stay explicit errors; this illustrative position does not fit/shrink them.
        val origin = placement?.let { RingPoint(it.x, it.y) }
            ?: RingPoint(usableWidthPx / 2f - anchor.x, usableHeightPx / 2f - anchor.y)
        val cx = origin.x + geometry.center.x
        val cy = origin.y + geometry.center.y
        circle(canvas, cx, cy, geometry.movementRadius, config.movement.enabled, Color.rgb(104, 177, 235), scale)
        circle(canvas, cx, cy, geometry.innerRadius, config.aim.enabled, Color.rgb(133, 222, 178), scale)
        config.knobs.forEach { knob ->
            val point = geometry.knobCenter(knob)
            circle(canvas, origin.x + point.x, origin.y + point.y, knob.targetDiameterDp * density / 2f,
                config.aim.enabled && knob.enabled, Color.WHITE, scale)
        }
        listOf(RingBoostStage.ONE, RingBoostStage.TWO).forEach { stage ->
            val settings = config.boost(stage, bank)
            val color = if (stage == RingBoostStage.ONE) Color.rgb(240, 194, 87) else Color.rgb(226, 119, 153)
            val radius = geometry.boostDistance(stage, bank = bank)
            val direct = geometry.directBand(stage, bank)
            val outer = if (stage == RingBoostStage.ONE) geometry.boostDistance(RingBoostStage.TWO, bank = bank)
                else radius + direct.outerRadius - direct.innerRadius
            circle(canvas, cx, cy, radius, settings.enabled && participates(), color, scale)
            circle(canvas, cx, cy, outer, settings.enabled && participates(), color, scale)
            circle(canvas, cx, cy, direct.innerRadius,
                settings.enabled && settings.directTapEnabled, color, scale, compactReference = true)
            circle(canvas, cx, cy, direct.outerRadius,
                settings.enabled && settings.directTapEnabled, color, scale, compactReference = true)
        }
        paint.pathEffect = null
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        canvas.drawRect(origin.x + geometry.finish.left, origin.y + geometry.finish.top,
            origin.x + geometry.finish.right, origin.y + geometry.finish.bottom, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(usableWidthPx / 2f, usableHeightPx / 2f, 5f * density / scale, paint)
        canvas.restore()

        val feedbackY = y0 + sceneHeight + 18f * density
        val fit = geometry.fitError(usableWidthPx, usableHeightPx)
        label(canvas, if (fit != null) "Ring + Finish cannot fit the usable canvas." else
            "Canvas ${usableWidthPx.toInt()} × ${usableHeightPx.toInt()} px · scaled preview", padding, feedbackY)
        val bankLabel = if (bank == RingBoostBank.MOVEMENT) "Movement" else "Aim"
        label(canvas, "$bankLabel bank · drag participation ${enabled(participates())}", padding, feedbackY + 18f * density)
        label(canvas, "Aim ${enabled(config.aim.enabled)} · Movement ${enabled(config.movement.enabled)}", padding, feedbackY + 36f * density)
        val canvasRect = RingRect(0f, 0f, usableWidthPx, usableHeightPx)
        listOf(RingBoostStage.ONE, RingBoostStage.TWO).forEachIndexed { index, stage ->
            val settings = config.boost(stage, bank)
            val unreachable = geometry.unreachableDirections(stage, origin, canvasRect, bank = bank)
            val blocked = if (unreachable.isEmpty()) "all four threshold directions reachable" else
                "unreachable: ${unreachable.joinToString()}"
            val reference = "$bankLabel B${index + 1}"
            label(canvas, "$reference ${enabled(settings.enabled)} · $blocked", padding,
                feedbackY + (54f + index * 18f) * density)
        }
        val directBank = if (config.directBoostBank() == RingBoostBank.MOVEMENT) "Movement" else "Aim"
        val directOne=config.boost(RingBoostStage.ONE,bank).let { it.enabled && it.directTapEnabled }
        val directTwo=config.boost(RingBoostStage.TWO,bank).let { it.enabled && it.directTapEnabled }
        label(canvas, "Direct reference B1 ${enabled(directOne)} · B2 ${enabled(directTwo)}", padding, feedbackY + 90f * density)
        label(canvas, "Dotted = direct bands · dashed = inactive drag", padding, feedbackY + 108f * density)
        label(canvas, "Rectangle = Finish · dot = initial finger", padding, feedbackY + 126f * density)
        label(canvas, "Actual direct touches use saved $directBank bank.", padding, feedbackY + 144f * density)
        label(canvas, "Existing controls may further limit placement.", padding, feedbackY + 162f * density)
    }

    private fun circle(canvas: Canvas, x: Float, y: Float, radius: Float, enabled: Boolean, color: Int, scale: Float,
                       compactReference: Boolean = false) {
        paint.style = Paint.Style.STROKE
        paint.color = if(enabled) color else Color.argb(120,Color.red(color),Color.green(color),Color.blue(color))
        paint.strokeWidth = 1.5f * density / scale
        paint.pathEffect = when {
            compactReference -> DashPathEffect(floatArrayOf(2f * density / scale, 3f * density / scale), 0f)
            enabled -> null
            else -> DashPathEffect(floatArrayOf(5f * density / scale, 4f * density / scale), 0f)
        }
        canvas.drawCircle(x, y, radius, paint)
    }

    private fun label(canvas: Canvas, text: String, x: Float, y: Float) {
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 11f * density
        canvas.drawText(text, x, y, paint)
    }

    private fun enabled(value: Boolean) = if (value) "ON" else "OFF"
    private fun participates(): Boolean = if (bank == RingBoostBank.MOVEMENT)
        config.movement.enabled && config.movement.participateInBoosts
        else config.aim.enabled && config.aim.participateInBoosts
}
