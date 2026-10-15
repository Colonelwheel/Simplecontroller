package com.example.simplecontroller.ui

import android.view.MotionEvent

/** Start the captured pointer at its latest canvas position without replaying waiting samples. */
internal fun floatingActivationDown(
    source: MotionEvent,
    pointer: Int,
    x: Float,
    y: Float,
    time: Long
): MotionEvent? {
    val index = source.findPointerIndex(pointer)
    if (index < 0 || !x.isFinite() || !y.isFinite()) return null
    val rawOffsetX = source.rawX - source.x
    val rawOffsetY = source.rawY - source.y
    val coordinates = MotionEvent.PointerCoords().also {
        source.getPointerCoords(index, it)
        it.x = x + rawOffsetX
        it.y = y + rawOffsetY
    }
    return obtainFloatingPointerEvent(source, index, time, time, coordinates).also {
        it.action = MotionEvent.ACTION_DOWN
        it.offsetLocation(-rawOffsetX, -rawOffsetY)
    }
}

/** Keep only this pointer and samples belonging to its fresh post-activation gesture. */
internal fun resumedFloatingEvent(
    source: MotionEvent,
    pointer: Int,
    startedAt: Long
): MotionEvent? {
    val index = source.findPointerIndex(pointer)
    if (index < 0) return null
    var action = when (source.actionMasked) {
        MotionEvent.ACTION_MOVE -> MotionEvent.ACTION_MOVE
        MotionEvent.ACTION_UP -> MotionEvent.ACTION_UP
        MotionEvent.ACTION_CANCEL -> MotionEvent.ACTION_CANCEL
        MotionEvent.ACTION_POINTER_UP -> {
            if (source.getPointerId(source.actionIndex) != pointer) return null
            MotionEvent.ACTION_CANCEL
        }
        else -> return null
    }
    if (source.eventTime < startedAt) {
        if (action == MotionEvent.ACTION_MOVE) return null
        // A lift that predates activation is cancellation, never an eligible release-shot.
        action = MotionEvent.ACTION_CANCEL
    }
    val eventTime = maxOf(startedAt, source.eventTime)
    val rawOffsetX = source.rawX - source.x
    val rawOffsetY = source.rawY - source.y
    fun coordinates(history: Int? = null): MotionEvent.PointerCoords =
        MotionEvent.PointerCoords().also {
            if (history == null) source.getPointerCoords(index, it)
            else source.getHistoricalPointerCoords(index, history, it)
            // Build in the raw frame, then restore the source's canvas translation once.
            it.x += rawOffsetX
            it.y += rawOffsetY
        }

    val history = (0 until source.historySize).filter {
        source.getHistoricalEventTime(it) >= startedAt
    }
    val result = if (history.isEmpty()) {
        obtainFloatingPointerEvent(source, index, startedAt, eventTime, coordinates())
    } else {
        obtainFloatingPointerEvent(source, index, startedAt,
            source.getHistoricalEventTime(history.first()), coordinates(history.first())).also { event ->
            history.drop(1).forEach {
                event.addBatch(source.getHistoricalEventTime(it), arrayOf(coordinates(it)), source.metaState)
            }
            event.addBatch(eventTime, arrayOf(coordinates()), source.metaState)
        }
    }
    result.action = action
    result.offsetLocation(-rawOffsetX, -rawOffsetY)
    return result
}

/** addBatch accepts MOVE events; the caller assigns the final action after rebuilding history. */
private fun obtainFloatingPointerEvent(
    source: MotionEvent,
    index: Int,
    downTime: Long,
    eventTime: Long,
    coordinates: MotionEvent.PointerCoords
): MotionEvent {
    val properties = MotionEvent.PointerProperties().also { source.getPointerProperties(index, it) }
    return MotionEvent.obtain(
        downTime, eventTime, MotionEvent.ACTION_MOVE, 1,
        arrayOf(properties), arrayOf(coordinates), source.metaState, source.buttonState,
        source.xPrecision, source.yPrecision, source.deviceId, source.edgeFlags, source.source, source.flags
    )
}
