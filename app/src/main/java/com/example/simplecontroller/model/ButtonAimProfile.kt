package com.example.simplecontroller.model

import kotlinx.serialization.Serializable

@Serializable
data class ButtonAimTuning(
    val output: ButtonAimOutput,
    val sensitivity: Float,
    val invertY: Boolean,
    val stickProfile: ButtonAimStickProfile,
    val mouseProfile: ButtonAimMouseProfile,
    val stickFullDisplacementPx: Float,
    val stickDeadzonePx: Float,
    val stickUsesTouchPosition: Boolean,
    val haptics: Boolean,
    val dpadMode: ButtonAimDpadMode = ButtonAimDpadMode.EIGHT_WAY,
    val dpadOrigin: ButtonAimDpadOrigin = ButtonAimDpadOrigin.CONTROL_CENTER,
    val dpadActivationDistancePx: Float = 8f
) {
    fun validationError(): String? = if (
        !sensitivity.isFinite() || sensitivity !in 0f..5f ||
        !stickFullDisplacementPx.isFinite() || stickFullDisplacementPx < 1f ||
        !stickDeadzonePx.isFinite() || stickDeadzonePx < 0f ||
        stickDeadzonePx >= stickFullDisplacementPx ||
        !dpadActivationDistancePx.isFinite() || dpadActivationDistancePx < 0f
    ) {
        "The saved sensitivity, displacement, or deadzone is invalid."
    } else null
}

@Serializable
data class ButtonAimActionSettings(
    val payload: String,
    val holdToggle: Boolean,
    val holdDurationMs: Long,
    val payloadTiming: ButtonAimPayloadTiming,
    val releaseDelayMs: Long,
    val oneShotAlternateEnabled: Boolean,
    val alternatePayload: String,
    val alternatePayloadTiming: ButtonAimPayloadTiming,
    val alternateResetHoldDurationMs: Long,
    val alternateBaseUnlatchDelayMs: Long,
    val alternateDisplayName: String
) {
    fun validationError(): String? {
        if (payload.length > ButtonAimProfile.MAX_PAYLOAD_LENGTH ||
            alternatePayload.length > ButtonAimProfile.MAX_PAYLOAD_LENGTH
        ) return "A saved payload is too long."
        if (payload.isBlank()) {
            if (oneShotAlternateEnabled) return "The saved Base payload is blank."
        } else {
            ButtonAimPayloadPolicy.validationError(payload)?.let { return it }
        }
        if (alternatePayload.isNotBlank()) {
            ButtonAimPayloadPolicy.validationError(alternatePayload)?.let { return it }
        } else if (oneShotAlternateEnabled) {
            return "The saved one-shot alternate payload is blank."
        }
        if (holdDurationMs < 0L || releaseDelayMs < 0L ||
            alternateResetHoldDurationMs < 0L || alternateBaseUnlatchDelayMs < 0L
        ) return "A saved Button Aim timing value is invalid."
        if (alternateDisplayName.length > ButtonAimProfile.MAX_DISPLAY_NAME_LENGTH) {
            return "The saved alternate display name is too long."
        }
        return null
    }
}

@Serializable
data class ButtonAimProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val sourceControlWidthPx: Float,
    val sourceControlHeightPx: Float,
    val base: ButtonAimTuning,
    val alternate: ButtonAimTuning,
    val actions: ButtonAimActionSettings
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val MAX_NAME_LENGTH = 80
        const val MAX_PAYLOAD_LENGTH = 1000
        const val MAX_DISPLAY_NAME_LENGTH = 120
    }

    fun validationError(): String? {
        if (schemaVersion !in 1..CURRENT_SCHEMA_VERSION) return "This profile version is not supported."
        if (name.trim().isEmpty() || name.trim().length > MAX_NAME_LENGTH) {
            return "Choose a profile name from 1 to $MAX_NAME_LENGTH characters."
        }
        if (!sourceControlWidthPx.isFinite() || sourceControlWidthPx <= 0f ||
            !sourceControlHeightPx.isFinite() || sourceControlHeightPx <= 0f) {
            return "The saved source size is invalid."
        }
        return base.validationError() ?: alternate.validationError() ?: actions.validationError()
    }

    /** Applies aim feel only; the target button's payload and action behavior remain untouched. */
    fun applyAimTuningTo(control: Control): Boolean {
        if (control.type != ControlType.BUTTON || validationError() != null) return false
        applyTuningUnchecked(control)
        control.autoTapEnabled = false
        control.buttonAimEnabled = true
        return true
    }

    /** Applies both aim feel and the saved Button Aim payload/action behavior. */
    fun applyCompleteTo(control: Control): Boolean {
        if (control.type != ControlType.BUTTON || validationError() != null) return false
        applyTuningUnchecked(control)
        control.payload = actions.payload
        control.holdToggle = actions.holdToggle
        control.holdDurationMs = actions.holdDurationMs
        control.buttonAimPayloadTiming = actions.payloadTiming
        control.buttonAimReleaseDelayMs = actions.releaseDelayMs
        control.buttonAimOneShotAlternateEnabled = actions.oneShotAlternateEnabled
        control.buttonAimAlternatePayload = actions.alternatePayload
        control.buttonAimAlternatePayloadTiming = actions.alternatePayloadTiming
        control.buttonAimAlternateResetHoldDurationMs = actions.alternateResetHoldDurationMs
        control.buttonAimAlternateBaseUnlatchDelayMs = actions.alternateBaseUnlatchDelayMs
        control.buttonAimAlternateDisplayName = actions.alternateDisplayName
        control.autoTapEnabled = false
        control.buttonAimEnabled = true
        return true
    }

    private fun applyTuningUnchecked(control: Control) {
        control.buttonAimOutput = base.output
        control.buttonAimSensitivity = base.sensitivity
        control.buttonAimInvertY = base.invertY
        control.buttonAimStickProfile = base.stickProfile
        control.buttonAimMouseProfile = base.mouseProfile
        control.buttonAimStickFullDisplacementPx = base.stickFullDisplacementPx
        control.buttonAimStickDeadzonePx = base.stickDeadzonePx
        control.buttonAimStickUsesTouchPosition = base.stickUsesTouchPosition
        control.buttonAimDpadMode = base.dpadMode
        control.buttonAimDpadOrigin = base.dpadOrigin
        control.buttonAimDpadActivationDistancePx = base.dpadActivationDistancePx
        control.buttonAimHaptics = base.haptics

        control.buttonAimAlternateOutput = alternate.output
        control.buttonAimAlternateSensitivity = alternate.sensitivity
        control.buttonAimAlternateInvertY = alternate.invertY
        control.buttonAimAlternateStickProfile = alternate.stickProfile
        control.buttonAimAlternateMouseProfile = alternate.mouseProfile
        control.buttonAimAlternateStickFullDisplacementPx = alternate.stickFullDisplacementPx
        control.buttonAimAlternateStickDeadzonePx = alternate.stickDeadzonePx
        control.buttonAimAlternateStickUsesTouchPosition = alternate.stickUsesTouchPosition
        control.buttonAimAlternateDpadMode = alternate.dpadMode
        control.buttonAimAlternateDpadOrigin = alternate.dpadOrigin
        control.buttonAimAlternateDpadActivationDistancePx =
            alternate.dpadActivationDistancePx
        control.buttonAimAlternateHaptics = alternate.haptics
    }
}

fun Control.captureButtonAimProfile(id: String, name: String, nowMs: Long): ButtonAimProfile? {
    if (type != ControlType.BUTTON) return null
    val profile = ButtonAimProfile(
        id = id,
        name = name.trim(),
        createdAtEpochMs = nowMs,
        updatedAtEpochMs = nowMs,
        sourceControlWidthPx = w,
        sourceControlHeightPx = h,
        base = ButtonAimTuning(
            buttonAimOutput,
            buttonAimSensitivity,
            buttonAimInvertY,
            buttonAimStickProfile,
            buttonAimMouseProfile,
            buttonAimStickFullDisplacementPx,
            buttonAimStickDeadzonePx,
            buttonAimStickUsesTouchPosition,
            buttonAimHaptics,
            buttonAimDpadMode,
            buttonAimDpadOrigin,
            buttonAimDpadActivationDistancePx
        ),
        alternate = ButtonAimTuning(
            buttonAimAlternateOutput,
            buttonAimAlternateSensitivity,
            buttonAimAlternateInvertY,
            buttonAimAlternateStickProfile,
            buttonAimAlternateMouseProfile,
            buttonAimAlternateStickFullDisplacementPx,
            buttonAimAlternateStickDeadzonePx,
            buttonAimAlternateStickUsesTouchPosition,
            buttonAimAlternateHaptics,
            buttonAimAlternateDpadMode,
            buttonAimAlternateDpadOrigin,
            buttonAimAlternateDpadActivationDistancePx
        ),
        actions = ButtonAimActionSettings(
            payload = payload.trim(),
            holdToggle = holdToggle,
            holdDurationMs = holdDurationMs,
            payloadTiming = buttonAimPayloadTiming,
            releaseDelayMs = buttonAimReleaseDelayMs,
            oneShotAlternateEnabled = buttonAimOneShotAlternateEnabled,
            alternatePayload = buttonAimAlternatePayload.trim(),
            alternatePayloadTiming = buttonAimAlternatePayloadTiming,
            alternateResetHoldDurationMs = buttonAimAlternateResetHoldDurationMs,
            alternateBaseUnlatchDelayMs = buttonAimAlternateBaseUnlatchDelayMs,
            alternateDisplayName = buttonAimAlternateDisplayName.trim()
        )
    )
    return profile.takeIf { it.validationError() == null }
}
