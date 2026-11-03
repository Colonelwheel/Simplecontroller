package com.example.simplecontroller.ui

import com.example.simplecontroller.model.FloatingRingConfig
import com.example.simplecontroller.model.FloatingStartRole
import com.example.simplecontroller.model.RingAimKnob
import com.example.simplecontroller.model.RingBoostBank
import com.example.simplecontroller.model.RingBoostBandLayout
import com.example.simplecontroller.model.RingBoostStage
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class RingPoint(val x: Float, val y: Float)
data class RingBand(val innerRadius: Float, val outerRadius: Float)
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

    private fun customLayout(bank: RingBoostBank?): RingBoostBandLayout? = when (bank) {
        RingBoostBank.MOVEMENT -> config.boostBanks?.movement?.geometry
        RingBoostBank.AIM -> config.boostBanks?.aim?.geometry
        null -> null
    }?.takeIf { it.enabled }

    /** Captured role selects swipe geometry; direct areas have their own protected placement. */
    fun boostDistance(
        stage: RingBoostStage, compactAim: Boolean = false, adjacentMovement: Boolean = false,
        bank: RingBoostBank? = null
    ): Float {
        customLayout(bank)?.let { layout ->
            return (layout.boost1DistanceDp + if (stage == RingBoostStage.TWO) layout.boost1ThicknessDp else 0f) * density
        }
        return if ((compactAim || bank == RingBoostBank.AIM) && config.compactAimBoosts) when (stage) {
            RingBoostStage.ONE -> innerRadius
            RingBoostStage.TWO -> movementRadius
        } else if ((adjacentMovement || bank == RingBoostBank.MOVEMENT) && config.adjacentMovementBoosts) when (stage) {
            RingBoostStage.ONE -> movementRadius
            RingBoostStage.TWO -> movementRadius +
                (config.boost(RingBoostStage.TWO, bank).distanceDp - config.boost(RingBoostStage.ONE, bank).distanceDp) * density
        } else config.boost(stage, bank).distanceDp * density
    }

    fun boostHysteresis(
        stage: RingBoostStage, compactAim: Boolean = false, adjacentMovement: Boolean = false,
        bank: RingBoostBank? = null
    ): Float {
        val saved = config.boost(stage, bank).hysteresisDp * density
        customLayout(bank)?.let { layout ->
            val boundary = boostDistance(stage, bank = bank)
            return min(saved, min(boundary / 2f, layout.boost1ThicknessDp * density / 2f))
        }
        if (!((compactAim || bank == RingBoostBank.AIM) && config.compactAimBoosts) &&
            !((adjacentMovement || bank == RingBoostBank.MOVEMENT) && config.adjacentMovementBoosts)) return saved
        val bandWidth = when (stage) {
            RingBoostStage.ONE -> boostDistance(stage, compactAim, adjacentMovement, bank)
            RingBoostStage.TWO -> boostDistance(stage, compactAim, adjacentMovement, bank) -
                boostDistance(RingBoostStage.ONE, compactAim, adjacentMovement, bank)
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

    /** Compact direct taps always sit outside visible Movement, including Aim-start templates. */
    fun directBand(stage: RingBoostStage, bank: RingBoostBank = config.directBoostBank()): RingBand {
        val settings = config.boost(stage, bank)
        customLayout(bank)?.let { layout ->
            val start = layout.directStartDistanceDp + if (stage == RingBoostStage.TWO) layout.boost1ThicknessDp else 0f
            val width = if (stage == RingBoostStage.ONE) layout.boost1ThicknessDp else layout.boost2ThicknessDp
            return RingBand(start * density, (start + width) * density)
        }
        if (config.compactAimBoosts || config.adjacentMovementBoosts) {
            val base = if (config.movement.enabled) movementRadius else innerRadius
            val widthOne = if (bank == RingBoostBank.AIM && config.compactAimBoosts) movementRadius - innerRadius else
                (config.boost(RingBoostStage.TWO, bank).distanceDp - config.boost(RingBoostStage.ONE, bank).distanceDp) * density
            val start = base + if (stage == RingBoostStage.TWO) widthOne else 0f
            val width = if (stage == RingBoostStage.ONE) widthOne else
                (settings.directOuterDistanceDp - settings.directInnerDistanceDp) * density
            return RingBand(start, start + width)
        }
        return RingBand(settings.directInnerDistanceDp * density, settings.directOuterDistanceDp * density)
    }

    fun directHit(
        stage: RingBoostStage, x: Float, y: Float, bank: RingBoostBank = config.directBoostBank()
    ): Boolean {
        val settings = config.boost(stage, bank)
        val band = directBand(stage, bank)
        val distance = distance(x, y)
        return settings.enabled && settings.directTapEnabled &&
            distance >= band.innerRadius && distance <= band.outerRadius
    }

    /** Starting-role anchor, used to place the selected part under the real first DOWN. */
    fun startAnchor(
        role: FloatingStartRole, knobId: String = config.startKnobId,
        bank: RingBoostBank = config.boostBankForRole(role)
    ): RingPoint = when (role) {
        FloatingStartRole.MOVEMENT -> RingPoint(
            center.x + config.movement.initialX * movementRadius,
            center.y + config.movement.initialY * movementRadius
        )
        FloatingStartRole.AIM -> config.knobs.firstOrNull { it.enabled && it.id == knobId }
            ?.let(::knobCenter) ?: center
        FloatingStartRole.BOOST_ONE, FloatingStartRole.BOOST_TWO -> {
            val stage = if (role == FloatingStartRole.BOOST_ONE) RingBoostStage.ONE else RingBoostStage.TWO
            val band = directBand(stage, bank)
            RingPoint(center.x, center.y - (band.innerRadius + band.outerRadius) / 2f)
        }
    }

    fun fitError(canvasWidth: Float, canvasHeight: Float): String? = when {
        width > canvasWidth || height > canvasHeight -> "The ring and its Finish target do not fit this canvas."
        else -> null
    }

    /** Editor feedback only: sector endpoints that cannot be reached in the usable canvas. */
    fun unreachableDirections(
        stage: RingBoostStage, origin: RingPoint, canvas: RingRect,
        compactAim: Boolean = false, adjacentMovement: Boolean = false, bank: RingBoostBank? = null
    ): Set<String> {
        val boost = config.boost(stage, bank)
        if (!boost.enabled) return emptySet()
        val radius = boostDistance(stage, compactAim, adjacentMovement, bank)
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
