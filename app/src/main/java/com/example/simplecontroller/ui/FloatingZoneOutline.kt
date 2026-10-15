package com.example.simplecontroller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.example.simplecontroller.model.ControlGeometry
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Edit-only outline. A completed one-pointer drag changes activation geometry, never its template. */
internal class FloatingZoneOutline(
    context: Context,
    private val label: String,
    private val zoneEnabled: Boolean,
    initialRectangle: ControlGeometry,
    private val onEdit: () -> Unit,
    private val onMoved: (ControlGeometry) -> Boolean
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var rectangle = initialRectangle
    private var origin = initialRectangle
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    private var longPressed = false
    private var canvasWidth = 0
    private var canvasHeight = 0
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean =
            if (!dragged && !longPressed && pointerId >= 0) performClick() else true
        override fun onLongPress(e: MotionEvent) {
            if (pointerId >= 0 && !dragged && isAttachedToWindow) {
                longPressed = true
                performLongClick()
            }
        }
    })

    init {
        isClickable = true
        contentDescription = "Floating zone " + label + ". Drag to move; tap or hold for size and position."
        setOnClickListener { onEdit() }
        setOnLongClickListener { onEdit(); true }
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pointerId = event.getPointerId(0)
            origin = rectangle
            downX = event.rawX
            downY = event.rawY
            dragged = false
            longPressed = false
            canvasWidth = (parent as? View)?.width ?: 0
            canvasHeight = (parent as? View)?.height ?: 0
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (pointerId < 0) return true
        val pointer = event.findPointerIndex(pointerId)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || pointer < 0 ||
            (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) == pointerId)) {
            cancelGesture(event)
            return true
        }
        if (!longPressed && (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP)) {
            // rawX/Y refer to index zero; retain the original pointer even when another finger appears.
            val rawX = event.rawX + event.getX(pointer) - event.getX(0)
            val rawY = event.rawY + event.getY(pointer) - event.getY(0)
            val dx = rawX - downX
            val dy = rawY - downY
            if (hypot(dx,dy) > slop) dragged = true
            if (dragged) applyRectangle(origin.copy(x = origin.x + dx,y = origin.y + dy))
        }
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val commit = dragged && !longPressed
            pointerId = -1
            parent?.requestDisallowInterceptTouchEvent(false)
            if (commit && ((parent as? View)?.width != canvasWidth ||
                    (parent as? View)?.height != canvasHeight || !onMoved(rectangle))) applyRectangle(origin)
        }
        return true
    }

    private fun applyRectangle(value: ControlGeometry) {
        rectangle = value
        val container = parent as? ViewGroup ?: return
        val margins = layoutParams as? ViewGroup.MarginLayoutParams ?: return
        margins.leftMargin = (value.x - container.paddingLeft).roundToInt()
        margins.topMargin = (value.y - container.paddingTop).roundToInt()
        layoutParams = margins
    }

    private fun cancelGesture(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        gestures.onTouchEvent(cancel)
        cancel.recycle()
        applyRectangle(origin)
        pointerId = -1
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    fun cancelEditingGesture() {
        if (pointerId < 0) return
        val time = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(time,time,MotionEvent.ACTION_CANCEL,0f,0f,0)
        cancelGesture(cancel)
        cancel.recycle()
    }

    override fun onDetachedFromWindow() {
        cancelEditingGesture()
        pointerId = -1
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = if (zoneEnabled) Color.rgb(70,150,220) else Color.GRAY
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawRect(2f,2f,width-2f,height-2f,paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 16f * resources.displayMetrics.scaledDensity
        canvas.drawText(label,8f,24f * resources.displayMetrics.density,paint)
    }
}
