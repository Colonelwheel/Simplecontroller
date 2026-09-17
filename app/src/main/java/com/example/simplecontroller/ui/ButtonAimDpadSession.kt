package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ButtonAimDpadMode
import com.example.simplecontroller.model.ButtonAimDpadOrigin
import kotlin.math.abs
import kotlin.math.hypot

data class ButtonAimDpadConfig(
    val mode: ButtonAimDpadMode,
    val origin: ButtonAimDpadOrigin,
    val activationDistancePx: Float,
    val owner: ButtonAimPayloadOwner
)

enum class ButtonAimDpadDirection(val command: String) {
    UP("X360UP"),
    DOWN("X360DOWN"),
    LEFT("X360LEFT"),
    RIGHT("X360RIGHT")
}

/**
 * Converts one Button Aim pointer into independently owned Xbox D-pad holds.
 * The direction lease is separate from the surface payload lease, so changing or releasing
 * a direction never releases the accompanying button (for example, X360B).
 */
class ButtonAimDpadSession(
    private val payloadExecutor: ButtonAimPayloadExecutor,
    private val controlSize: () -> Pair<Float, Float>
) {
    private var config: ButtonAimDpadConfig? = null
    private var activePointerId = INVALID_POINTER_ID
    private var originX = 0f
    private var originY = 0f
    private var directionLease: ButtonAimPayloadLease? = null
    private var directions: List<ButtonAimDpadDirection> = emptyList()

    fun begin(pointerId: Int, x: Float, y: Float, newConfig: ButtonAimDpadConfig): Boolean {
        finish()
        config = newConfig
        activePointerId = pointerId
        originX = x
        originY = y
        return true
    }

    fun update(pointerId: Int, x: Float, y: Float): Boolean {
        val activeConfig = config ?: return false
        if (activePointerId != pointerId) return false

        val next = resolveDirections(activeConfig, x, y)
        if (next == directions) return true

        payloadExecutor.release(directionLease)
        directionLease = null
        directions = emptyList()
        if (next.isEmpty()) return true

        val payload = next.joinToString(",") { it.command }
        return when (val result = payloadExecutor.activate(activeConfig.owner, payload)) {
            is ButtonAimPayloadActivationResult.Activated -> {
                directionLease = result.lease
                directions = next
                true
            }
            ButtonAimPayloadActivationResult.ReleaseAll,
            is ButtonAimPayloadActivationResult.Invalid -> false
        }
    }

    /** Preserve the final direction while a configured send-on-release delay runs. */
    fun detachPointerKeepingOutput() {
        activePointerId = INVALID_POINTER_ID
    }

    fun finish() {
        payloadExecutor.release(directionLease)
        config = null
        activePointerId = INVALID_POINTER_ID
        originX = 0f
        originY = 0f
        directionLease = null
        directions = emptyList()
    }

    fun isActive(): Boolean = config != null

    fun currentDirections(): List<ButtonAimDpadDirection> = directions

    private fun resolveDirections(
        activeConfig: ButtonAimDpadConfig,
        x: Float,
        y: Float
    ): List<ButtonAimDpadDirection> {
        val (dx, dy) = when (activeConfig.origin) {
            ButtonAimDpadOrigin.CONTROL_CENTER -> {
                val (width, height) = controlSize()
                if (width <= 0f || height <= 0f) return emptyList()
                x - width / 2f to y - height / 2f
            }
            ButtonAimDpadOrigin.INITIAL_TOUCH -> x - originX to y - originY
        }

        if (hypot(dx, dy) <= activeConfig.activationDistancePx.coerceAtLeast(0f)) {
            return emptyList()
        }

        val horizontal = if (dx < 0f) ButtonAimDpadDirection.LEFT else ButtonAimDpadDirection.RIGHT
        val vertical = if (dy < 0f) ButtonAimDpadDirection.UP else ButtonAimDpadDirection.DOWN
        val absoluteX = abs(dx)
        val absoluteY = abs(dy)

        if (activeConfig.mode == ButtonAimDpadMode.FOUR_WAY) {
            return listOf(if (absoluteX >= absoluteY) horizontal else vertical)
        }

        return when {
            absoluteY <= absoluteX * TAN_22_5_DEGREES -> listOf(horizontal)
            absoluteY >= absoluteX * TAN_67_5_DEGREES -> listOf(vertical)
            else -> listOf(vertical, horizontal)
        }
    }

    companion object {
        private const val INVALID_POINTER_ID = -1
        private const val TAN_22_5_DEGREES = 0.41421357f
        private const val TAN_67_5_DEGREES = 2.4142137f
    }
}
