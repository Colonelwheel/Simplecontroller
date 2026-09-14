package com.example.simplecontroller.model

import kotlinx.serialization.Serializable

@Serializable
enum class StickDirectionalProfileMode { WASD, STICK_PLUS }

@Serializable
data class StickDirectionalSettings(
    val upCommand: String,
    val downCommand: String,
    val leftCommand: String,
    val rightCommand: String,
    val boostThreshold: Float,
    val upBoostCommand: String,
    val downBoostCommand: String,
    val leftBoostCommand: String,
    val rightBoostCommand: String,
    val superBoostThreshold: Float,
    val upSuperBoostCommand: String,
    val downSuperBoostCommand: String,
    val leftSuperBoostCommand: String,
    val rightSuperBoostCommand: String
) {
    fun validationError(): String? {
        if (!boostThreshold.isFinite() || boostThreshold !in 0.1f..1f ||
            !superBoostThreshold.isFinite() || superBoostThreshold !in 0.1f..1f ||
            superBoostThreshold < boostThreshold + MIN_THRESHOLD_GAP
        ) return "The saved Boost thresholds are invalid."
        if (commands().any { it.length > MAX_COMMAND_LENGTH }) {
            return "A saved directional command is too long."
        }
        return null
    }

    private fun commands(): List<String> = listOf(
        upCommand,
        downCommand,
        leftCommand,
        rightCommand,
        upBoostCommand,
        downBoostCommand,
        leftBoostCommand,
        rightBoostCommand,
        upSuperBoostCommand,
        downSuperBoostCommand,
        leftSuperBoostCommand,
        rightSuperBoostCommand
    )

    private companion object {
        const val MAX_COMMAND_LENGTH = 1000
        const val MIN_THRESHOLD_GAP = 0.01f
    }
}

@Serializable
data class StickDirectionalProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val mode: StickDirectionalProfileMode,
    val settings: StickDirectionalSettings
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_NAME_LENGTH = 80
    }

    fun validationError(): String? {
        if (schemaVersion !in 1..CURRENT_SCHEMA_VERSION) {
            return "This profile version is not supported."
        }
        if (name.trim().isEmpty() || name.trim().length > MAX_NAME_LENGTH) {
            return "Choose a profile name from 1 to $MAX_NAME_LENGTH characters."
        }
        return settings.validationError()
    }

    /** Copies shared directional commands and thresholds without changing the target's mode. */
    fun applyCommandsTo(control: Control): Boolean {
        if (!control.isStickControl() || validationError() != null) return false
        applySettingsUnchecked(control)
        return true
    }

    /** Copies the settings and switches only between Directional/WASD and Stick+ mode. */
    fun applyAndSwitchModeTo(control: Control): Boolean {
        if (!control.isStickControl() || validationError() != null) return false
        applySettingsUnchecked(control)
        control.directionalMode = mode == StickDirectionalProfileMode.WASD
        control.stickPlusMode = mode == StickDirectionalProfileMode.STICK_PLUS
        return true
    }

    private fun applySettingsUnchecked(control: Control) = with(settings) {
        control.upCommand = upCommand
        control.downCommand = downCommand
        control.leftCommand = leftCommand
        control.rightCommand = rightCommand
        control.boostThreshold = boostThreshold
        control.upBoostCommand = upBoostCommand
        control.downBoostCommand = downBoostCommand
        control.leftBoostCommand = leftBoostCommand
        control.rightBoostCommand = rightBoostCommand
        control.superBoostThreshold = superBoostThreshold
        control.upSuperBoostCommand = upSuperBoostCommand
        control.downSuperBoostCommand = downSuperBoostCommand
        control.leftSuperBoostCommand = leftSuperBoostCommand
        control.rightSuperBoostCommand = rightSuperBoostCommand
    }
}

fun Control.captureStickDirectionalProfile(
    id: String,
    name: String,
    nowMs: Long
): StickDirectionalProfile? {
    if (!isStickControl() || directionalMode == stickPlusMode) return null
    val profile = StickDirectionalProfile(
        id = id,
        name = name.trim(),
        createdAtEpochMs = nowMs,
        updatedAtEpochMs = nowMs,
        mode = if (directionalMode) {
            StickDirectionalProfileMode.WASD
        } else {
            StickDirectionalProfileMode.STICK_PLUS
        },
        settings = StickDirectionalSettings(
            upCommand = upCommand,
            downCommand = downCommand,
            leftCommand = leftCommand,
            rightCommand = rightCommand,
            boostThreshold = boostThreshold,
            upBoostCommand = upBoostCommand,
            downBoostCommand = downBoostCommand,
            leftBoostCommand = leftBoostCommand,
            rightBoostCommand = rightBoostCommand,
            superBoostThreshold = superBoostThreshold,
            upSuperBoostCommand = upSuperBoostCommand,
            downSuperBoostCommand = downSuperBoostCommand,
            leftSuperBoostCommand = leftSuperBoostCommand,
            rightSuperBoostCommand = rightSuperBoostCommand
        )
    )
    return profile.takeIf { it.validationError() == null }
}

private fun Control.isStickControl(): Boolean =
    type == ControlType.STICK || type == ControlType.CURVED_STICK
