package com.example.simplecontroller.model

import kotlinx.serialization.Serializable

/** Finger distance is measured from the control center; 1.0 is its edge on that axis. */
@Serializable
enum class ExtendedBoostMode { REPLACE_THRESHOLDS, ADD_OUTER_STAGE }

@Serializable
enum class StickDirection { UP, DOWN, LEFT, RIGHT }

@Serializable
data class DirectionalThresholds(
    val up: Float,
    val down: Float,
    val left: Float,
    val right: Float
) {
    fun forDirection(direction: StickDirection): Float = when (direction) {
        StickDirection.UP -> up
        StickDirection.DOWN -> down
        StickDirection.LEFT -> left
        StickDirection.RIGHT -> right
    }

    fun values(): List<Float> = listOf(up, down, left, right)
}

@Serializable
data class DirectionalCommands(
    val up: String = "",
    val down: String = "",
    val left: String = "",
    val right: String = ""
) {
    fun forDirection(direction: StickDirection): String = when (direction) {
        StickDirection.UP -> up
        StickDirection.DOWN -> down
        StickDirection.LEFT -> left
        StickDirection.RIGHT -> right
    }

    fun values(): List<String> = listOf(up, down, left, right)
}

@Serializable
data class ExtendedBoostSettings(
    val enabled: Boolean = false,
    val mode: ExtendedBoostMode = ExtendedBoostMode.REPLACE_THRESHOLDS,
    val regular: DirectionalThresholds = DirectionalThresholds(.5f, .5f, .5f, .5f),
    val superBoost: DirectionalThresholds = DirectionalThresholds(.75f, .75f, .75f, .75f),
    val outer: DirectionalThresholds = DirectionalThresholds(1.25f, 1.25f, 1.25f, 1.25f),
    val outerCommands: DirectionalCommands = DirectionalCommands()
) {
    fun validationError(sharedSuperThreshold: Float): String? {
        val values = regular.values() + superBoost.values() + outer.values()
        if (values.any { !it.isFinite() || it !in .1f..MAX_DISTANCE }) {
            return "Extended Boost distances must be between 0.10 and $MAX_DISTANCE."
        }
        if (regular.values().zip(superBoost.values()).any { (regular, superBoost) ->
                superBoost < regular + MIN_GAP
            }) return "Each Super Boost distance must exceed its Regular Boost distance."
        if (outer.values().any { it < maxOf(1f, sharedSuperThreshold) + MIN_GAP }) {
            return "Outer Boost distances must be beyond the stick edge and Super Boost."
        }
        if (outerCommands.values().any { it.length > MAX_COMMAND_LENGTH }) {
            return "An Outer Boost command is too long."
        }
        return null
    }

    companion object {
        const val MAX_DISTANCE = 5f
        const val MIN_GAP = .01f
        const val MAX_COMMAND_LENGTH = 1000
    }
}

/** Selects one payload per axis. Existing layouts use the shared thresholds unchanged. */
fun Control.directionalPayload(
    direction: StickDirection,
    intensity: Float
): String {
    val normal = when (direction) {
        StickDirection.UP -> upCommand
        StickDirection.DOWN -> downCommand
        StickDirection.LEFT -> leftCommand
        StickDirection.RIGHT -> rightCommand
    }
    val boost = when (direction) {
        StickDirection.UP -> upBoostCommand
        StickDirection.DOWN -> downBoostCommand
        StickDirection.LEFT -> leftBoostCommand
        StickDirection.RIGHT -> rightBoostCommand
    }
    val superBoost = when (direction) {
        StickDirection.UP -> upSuperBoostCommand
        StickDirection.DOWN -> downSuperBoostCommand
        StickDirection.LEFT -> leftSuperBoostCommand
        StickDirection.RIGHT -> rightSuperBoostCommand
    }
    val extended = extendedBoost
    if (extended.enabled && extended.mode == ExtendedBoostMode.ADD_OUTER_STAGE &&
        intensity > extended.outer.forDirection(direction)
    ) return extended.outerCommands.forDirection(direction).ifBlank { superBoost }

    val regularThreshold = if (extended.enabled && extended.mode == ExtendedBoostMode.REPLACE_THRESHOLDS) {
        extended.regular.forDirection(direction)
    } else boostThreshold
    val superThreshold = if (extended.enabled && extended.mode == ExtendedBoostMode.REPLACE_THRESHOLDS) {
        extended.superBoost.forDirection(direction)
    } else superBoostThreshold
    return when {
        intensity > superThreshold -> superBoost
        intensity > regularThreshold -> boost
        else -> normal
    }
}
