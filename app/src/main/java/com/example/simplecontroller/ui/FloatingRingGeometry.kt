package com.example.simplecontroller.ui

import com.example.simplecontroller.model.FloatingRingConfig
import com.example.simplecontroller.model.FloatingStartRole
import com.example.simplecontroller.model.RingAimKnob
import com.example.simplecontroller.model.RingBoostStage
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class RingPoint(val x: Float, val y: Float)
data class RingRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}

/** All dimensions are available before an Android View is measured. No saved geometry is mutated. */
class FloatingRingGeometry(val config: FloatingRingConfig, val density: Float = 1f) {
    val innerRadius = config.innerRadiusDp * density
    val movementRadius = config.movementRadiusDp * density
    val finishDiameter = 48f * density
    val visibleRadius = max(finishDiameter / 2f, max(
        if (config.movement.enabled) movementRadius else 0f,
        if (config.aim.enabled) max(innerRadius, config.knobs.filter { it.enabled }.maxOfOrNull {
            hypot(it.x * innerRadius, it.y * innerRadius) + it.targetDiameterDp * density / 2f
        } ?: 0f) else 0f
    ))
    val width = visibleRadius * 2f
    val height = visibleRadius * 2f + finishDiameter
    val center = RingPoint(visibleRadius, visibleRadius + finishDiameter)
    val finish = RingRect(width - finishDiameter, 0f, width, finishDiameter)

    fun distance(x: Float, y: Float): Float = hypot(x - center.x, y - center.y)
    fun insideAim(x: Float, y: Float): Boolean = distance(x, y) <= innerRadius

    /** Only a captured Aim gesture may borrow Movement's band; saved/direct geometry stays fixed. */
    fun boostDistance(
        stage: RingBoostStage, compactAim: Boolean = false, adjacentMovement: Boolean = false
    ): Float =
        if (compactAim && config.compactAimBoosts) when (stage) {
            RingBoostStage.ONE -> innerRadius
            RingBoostStage.TWO -> movementRadius
        } else if (adjacentMovement && config.adjacentMovementBoosts) when (stage) {
            RingBoostStage.ONE -> movementRadius
            RingBoostStage.TWO -> movementRadius + (config.boost2.distanceDp - config.boost1.distanceDp) * density
        } else config.boost(stage).distanceDp * density

    fun boostHysteresis(
        stage: RingBoostStage, compactAim: Boolean = false, adjacentMovement: Boolean = false
    ): Float {
        val saved = config.boost(stage).hysteresisDp * density
        if (!(compactAim && config.compactAimBoosts) && !(adjacentMovement && config.adjacentMovementBoosts)) return saved
        val bandWidth = when (stage) {
            RingBoostStage.ONE -> boostDistance(stage, compactAim, adjacentMovement)
            RingBoostStage.TWO -> boostDistance(stage, compactAim, adjacentMovement) -
                boostDistance(RingBoostStage.ONE, compactAim, adjacentMovement)
        }
        // A large value valid for the original radius must not trap a compressed boost on.
        return min(saved, bandWidth / 2f)
    }
    fun knobCenter(knob: RingAimKnob): RingPoint =
        RingPoint(center.x + knob.x * innerRadius, center.y + knob.y * innerRadius)

    fun knobAt(x: Float, y: Float): RingAimKnob? {
        if (!config.aim.enabled || !insideAim(x, y)) return null
        val enabled = config.knobs.filter { it.enabled }
        // Actual knob target sizes select their modes. The shared inner background uses the saved
        // default knob, so resizing a target does not silently create a nearest-button region.
        val hits = enabled.filter { knob ->
            val point = knobCenter(knob)
            hypot(x - point.x, y - point.y) <= knob.targetDiameterDp * density / 2f
        }
        return hits.minByOrNull { knob ->
            val point = knobCenter(knob)
            hypot(x - point.x, y - point.y)
        } ?: enabled.firstOrNull { it.id == config.startKnobId } ?: enabled.firstOrNull()
    }

    fun roleAt(x: Float, y: Float): FloatingStartRole? {
        if (finish.contains(x, y)) return null
        if (knobAt(x, y) != null) return FloatingStartRole.AIM
        if (config.movement.enabled && distance(x, y) <= movementRadius) return FloatingStartRole.MOVEMENT
        if (directHit(RingBoostStage.TWO, x, y)) return FloatingStartRole.BOOST_TWO
        if (directHit(RingBoostStage.ONE, x, y)) return FloatingStartRole.BOOST_ONE
        return null
    }

    fun directHit(stage: RingBoostStage, x: Float, y: Float): Boolean {
        val settings = if (stage == RingBoostStage.ONE) config.boost1 else config.boost2
        val distance = distance(x, y)
        return settings.enabled && settings.directTapEnabled &&
            distance >= settings.directInnerDistanceDp * density &&
            distance <= settings.directOuterDistanceDp * density
    }

    /** Starting-role anchor, used to place the selected part under the real first DOWN. */
    fun startAnchor(role: FloatingStartRole, knobId: String = config.startKnobId): RingPoint = when (role) {
        FloatingStartRole.MOVEMENT -> RingPoint(
            center.x + config.movement.initialX * movementRadius,
            center.y + config.movement.initialY * movementRadius
        )
        FloatingStartRole.AIM -> config.knobs.firstOrNull { it.enabled && it.id == knobId }
            ?.let(::knobCenter) ?: center
        FloatingStartRole.BOOST_ONE, FloatingStartRole.BOOST_TWO -> {
            val boost = if (role == FloatingStartRole.BOOST_ONE) config.boost1 else config.boost2
            RingPoint(center.x, center.y -
                ((boost.directInnerDistanceDp + boost.directOuterDistanceDp) / 2f) * density)
        }
    }

    fun fitError(canvasWidth: Float, canvasHeight: Float): String? = when {
        width > canvasWidth || height > canvasHeight -> "The ring and its Finish target do not fit this canvas."
        else -> null
    }

    /** Editor feedback only: sector endpoints that cannot be reached in the usable canvas. */
    fun unreachableDirections(
        stage: RingBoostStage, origin: RingPoint, canvas: RingRect,
        compactAim: Boolean = false, adjacentMovement: Boolean = false
    ): Set<String> {
        val boost = if (stage == RingBoostStage.ONE) config.boost1 else config.boost2
        if (!boost.enabled) return emptySet()
        val radius = boostDistance(stage, compactAim, adjacentMovement)
        val cx = origin.x + center.x
        val cy = origin.y + center.y
        return buildSet {
            if (!canvas.contains(cx, cy - radius)) add("Up")
            if (!canvas.contains(cx, cy + radius)) add("Down")
            if (!canvas.contains(cx - radius, cy)) add("Left")
            if (!canvas.contains(cx + radius, cy)) add("Right")
        }
    }

    fun movementVector(x: Float, y: Float): RingPoint {
        val dx = x - center.x
        val dy = y - center.y
        val magnitude = hypot(dx, dy)
        if (magnitude == 0f || movementRadius <= 0f) return RingPoint(0f, 0f)
        val normalized = min(1f, magnitude / movementRadius)
        return RingPoint(dx / magnitude * normalized, dy / magnitude * normalized)
    }
}
