package com.example.simplecontroller.ui

import android.view.MotionEvent
import com.example.simplecontroller.model.ButtonDirectionStages
import com.example.simplecontroller.model.ButtonDirectionalSettings
import com.example.simplecontroller.model.Control
import kotlin.math.abs

internal enum class ButtonDirection { UP, LEFT, RIGHT, DOWN }

internal data class ButtonDirectionalSelection(val direction: ButtonDirection? = null, val stage: Int = 0)

/** Physical distance from the first contact, independent of Button Aim sensitivity. */
internal fun selectButtonDirection(
    dx: Float,
    dy: Float,
    settings: ButtonDirectionalSettings,
    previous: ButtonDirectionalSelection = ButtonDirectionalSelection()
): ButtonDirectionalSelection {
    val horizontal = abs(dx)
    val vertical = abs(dy)
    val proposed = if (horizontal >= vertical) {
        if (dx >= 0f) ButtonDirection.RIGHT else ButtonDirection.LEFT
    } else {
        if (dy >= 0f) ButtonDirection.DOWN else ButtonDirection.UP
    }
    val direction = if (previous.stage > 0 && previous.direction != null && proposed != previous.direction) {
        val oldAxis = if (previous.direction == ButtonDirection.LEFT ||
            previous.direction == ButtonDirection.RIGHT) horizontal else vertical
        val newAxis = if (proposed == ButtonDirection.LEFT || proposed == ButtonDirection.RIGHT)
            horizontal else vertical
        if (newAxis < oldAxis + 12f) previous.direction else proposed
    } else proposed
    val stages = settings.stages(direction)
    val distance = if (direction == ButtonDirection.LEFT || direction == ButtonDirection.RIGHT)
        horizontal else vertical
    val first = stages.firstDistancePx.coerceAtLeast(1f)
    val second = stages.secondDistancePx.coerceAtLeast(first + 1f)
    val sameDirection = previous.direction == direction
    val firstHysteresis = minOf(4f, first / 2f)
    val secondHysteresis = minOf(4f, (second - first) / 2f)
    val stage = when {
        distance >= second - if (sameDirection && previous.stage >= 2) secondHysteresis else 0f -> 2
        distance >= first - if (sameDirection && previous.stage >= 1) firstHysteresis else 0f -> 1
        else -> 0
    }
    return if (stage == 0) ButtonDirectionalSelection() else ButtonDirectionalSelection(direction, stage)
}

internal fun ButtonDirectionalSettings.stages(direction: ButtonDirection): ButtonDirectionStages =
    when (direction) {
        ButtonDirection.UP -> up
        ButtonDirection.LEFT -> left
        ButtonDirection.RIGHT -> right
        ButtonDirection.DOWN -> down
    }

/** Adds two reversible payload stages while the initiating finger owns the button. */
internal class ButtonDirectionalGestureHandler(
    private val model: Control,
    private val executor: ButtonAimPayloadExecutor,
    private val onPayloadError: (String) -> Unit,
    private val onPressedChanged: (Boolean) -> Unit
) {
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var originX = 0f
    private var originY = 0f
    private var selection = ButtonDirectionalSelection()
    private var baseLease: ButtonAimPayloadLease? = null
    private var firstLease: ButtonAimPayloadLease? = null
    private var secondLease: ButtonAimPayloadLease? = null

    fun hasRuntimeState(): Boolean = pointerId != MotionEvent.INVALID_POINTER_ID ||
        baseLease != null || firstLease != null || secondLease != null

    fun onTouch(event: MotionEvent, baseOwnedByAim: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                releaseAll()
                val index = event.actionIndex
                pointerId = event.getPointerId(index)
                originX = event.getX(index)
                originY = event.getY(index)
                if (!baseOwnedByAim) {
                    val reason = executor.statePayloadValidationError(model.payload)
                    if (reason != null || model.payload.isBlank()) {
                        onPayloadError(reason ?: "Base payload is blank")
                        releaseAll()
                        return
                    }
                    when (val result = executor.activate(ButtonAimPayloadOwner.DIRECTION_BASE, model.payload)) {
                        is ButtonAimPayloadActivationResult.Activated -> baseLease = result.lease
                        is ButtonAimPayloadActivationResult.Invalid -> {
                            onPayloadError(result.reason)
                            releaseAll()
                            return
                        }
                        ButtonAimPayloadActivationResult.ReleaseAll -> {
                            releaseAll()
                            return
                        }
                    }
                    onPressedChanged(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) {
                    releaseAll()
                } else {
                    val next = selectButtonDirection(
                        event.getX(index) - originX,
                        event.getY(index) - originY,
                        model.buttonDirectional,
                        selection
                    )
                    transition(next)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) releaseAll()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseAll()
        }
    }

    fun releaseAll() {
        val wasActive = hasRuntimeState()
        executor.release(secondLease)
        executor.release(firstLease)
        executor.release(baseLease)
        secondLease = null
        firstLease = null
        baseLease = null
        selection = ButtonDirectionalSelection()
        pointerId = MotionEvent.INVALID_POINTER_ID
        executor.releaseOwner(ButtonAimPayloadOwner.DIRECTION_ADDITION)
        executor.releaseOwner(ButtonAimPayloadOwner.DIRECTION_BASE)
        if (wasActive && !model.buttonAimEnabled) onPressedChanged(false)
    }

    private fun transition(next: ButtonDirectionalSelection) {
        if (next == selection) return
        val sameDirection = next.direction == selection.direction
        val stages = next.direction?.let(model.buttonDirectional::stages)
        val newFirst = if (next.stage >= 1 && (!sameDirection || selection.stage < 1)) {
            acquire(stages?.firstPayload.orEmpty())
        } else firstLease
        val newSecond = if (next.stage >= 2 && (!sameDirection || selection.stage < 2)) {
            acquire(stages?.secondPayload.orEmpty())
        } else secondLease
        if (!sameDirection || next.stage < 2) executor.release(secondLease)
        if (!sameDirection || next.stage < 1) executor.release(firstLease)
        firstLease = if (next.stage >= 1) newFirst else null
        secondLease = if (next.stage >= 2) newSecond else null
        selection = next
    }

    private fun acquire(payload: String): ButtonAimPayloadLease? {
        if (payload.isBlank()) return null
        executor.statePayloadValidationError(payload)?.let {
            onPayloadError(it)
            return null
        }
        return when (val result = executor.activate(ButtonAimPayloadOwner.DIRECTION_ADDITION, payload)) {
            is ButtonAimPayloadActivationResult.Activated -> result.lease
            is ButtonAimPayloadActivationResult.Invalid -> {
                onPayloadError(result.reason)
                null
            }
            ButtonAimPayloadActivationResult.ReleaseAll -> null
        }
    }
}
