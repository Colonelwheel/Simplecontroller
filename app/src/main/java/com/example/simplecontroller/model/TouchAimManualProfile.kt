package com.example.simplecontroller.model

import kotlinx.serialization.Serializable

@Serializable
data class TouchAimManualThreeStageSettings(
    val scoreSmoothing: Float,
    val lowThreshold: Float,
    val mediumThreshold: Float,
    val highThreshold: Float,
    val hysteresis: Float,
    val lowPayload: String,
    val mediumPayload: String,
    val highPayload: String,
    val lowAction: TouchStageAction,
    val mediumAction: TouchStageAction,
    val highAction: TouchStageAction,
    val keepLowerHolds: Boolean
)

@Serializable
data class TouchAimManualTwoStateSettings(
    val shootOnThreshold: Float,
    val shootOffThreshold: Float,
    val smoothing: Float,
    val enterShootConfirmationMs: Long,
    val returnToAimConfirmationMs: Long,
    val aimPayload: String,
    val shootPayload: String,
    val keepAimPayloadWhileShooting: Boolean,
    val shootSensitivity: Float,
    val shootBehavior: TouchAimShootBehavior
)

@Serializable
data class TouchAimManualProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val mode: TouchAimMode,
    val aimOutput: TouchAimOutput,
    val aimSensitivity: Float,
    val useSize: Boolean,
    val useMajor: Boolean,
    val useMinor: Boolean,
    val sizeScale: Float,
    val stickFullSpeed: Float,
    val invertY: Boolean,
    val useResponseCurve: Boolean,
    val threeStage: TouchAimManualThreeStageSettings? = null,
    val twoState: TouchAimManualTwoStateSettings? = null
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_NAME_LENGTH = 80
        const val MAX_PAYLOAD_LENGTH = 1000
    }

    fun validationError(): String? {
        if (schemaVersion !in 1..CURRENT_SCHEMA_VERSION) return "This profile version is not supported."
        if (name.trim().isEmpty() || name.trim().length > MAX_NAME_LENGTH) {
            return "Choose a profile name from 1 to $MAX_NAME_LENGTH characters."
        }
        if (!aimSensitivity.isFinite() || aimSensitivity !in 0f..5f ||
            !sizeScale.isFinite() || sizeScale < 0f ||
            !stickFullSpeed.isFinite() || stickFullSpeed < 1f) {
            return "The saved manual aim values are invalid."
        }
        val payloads = listOfNotNull(
            threeStage?.lowPayload,
            threeStage?.mediumPayload,
            threeStage?.highPayload,
            twoState?.aimPayload,
            twoState?.shootPayload
        )
        if (payloads.any { it.length > MAX_PAYLOAD_LENGTH }) return "A saved payload is too long."
        return when (mode) {
            TouchAimMode.MANUAL_THREE_STAGE -> {
                val settings = threeStage
                    ?: return "The three-stage settings are missing."
                if (twoState != null) return "This profile contains conflicting mode settings."
                if (!settings.scoreSmoothing.isFinite() || settings.scoreSmoothing !in .05f..1f ||
                    !settings.lowThreshold.isFinite() || settings.lowThreshold < 0f ||
                    !settings.mediumThreshold.isFinite() ||
                    settings.mediumThreshold < settings.lowThreshold + .01f ||
                    !settings.highThreshold.isFinite() ||
                    settings.highThreshold < settings.mediumThreshold + .01f ||
                    !settings.hysteresis.isFinite() || settings.hysteresis < 0f) {
                    "The saved three-stage thresholds are invalid."
                } else null
            }

            TouchAimMode.MANUAL_TWO_STATE -> {
                val settings = twoState
                    ?: return "The two-state settings are missing."
                if (threeStage != null) return "This profile contains conflicting mode settings."
                ButtonAimPayloadPolicy.stateValidationError(settings.aimPayload)?.let { return it }
                ButtonAimPayloadPolicy.stateValidationError(settings.shootPayload)?.let { return it }
                val hasEffectiveSensor = useMajor || useMinor || (useSize && sizeScale > 0f)
                if (!hasEffectiveSensor ||
                    !settings.shootOnThreshold.isFinite() || settings.shootOnThreshold < .01f ||
                    !settings.shootOffThreshold.isFinite() || settings.shootOffThreshold < 0f ||
                    settings.shootOffThreshold > settings.shootOnThreshold - .01f ||
                    !settings.smoothing.isFinite() || settings.smoothing !in .05f..1f ||
                    settings.enterShootConfirmationMs !in 32L..2000L ||
                    settings.returnToAimConfirmationMs !in 32L..2000L ||
                    !settings.shootSensitivity.isFinite() || settings.shootSensitivity !in 0f..5f) {
                    "The saved manual two-state detector is invalid."
                } else null
            }

            TouchAimMode.CALIBRATED_TWO_STATE -> "Calibrated settings cannot be saved as a manual profile."
        }
    }

    fun applyTo(control: Control): Boolean {
        if (control.type != ControlType.TOUCH_AIM || validationError() != null) return false
        control.touchAimOutput = aimOutput
        control.sensitivity = aimSensitivity
        control.touchUseSize = useSize
        control.touchUseMajor = useMajor
        control.touchUseMinor = useMinor
        control.touchSizeScale = sizeScale
        control.touchStickFullSpeed = stickFullSpeed
        control.touchInvertY = invertY
        control.touchUseResponseCurve = useResponseCurve
        when (mode) {
            TouchAimMode.MANUAL_THREE_STAGE -> checkNotNull(threeStage).also { settings ->
                control.touchScoreSmoothing = settings.scoreSmoothing
                control.touchLowThreshold = settings.lowThreshold
                control.touchMediumThreshold = settings.mediumThreshold
                control.touchHighThreshold = settings.highThreshold
                control.touchHysteresis = settings.hysteresis
                control.touchLowPayload = settings.lowPayload
                control.touchMediumPayload = settings.mediumPayload
                control.touchHighPayload = settings.highPayload
                control.touchLowAction = settings.lowAction
                control.touchMediumAction = settings.mediumAction
                control.touchHighAction = settings.highAction
                control.touchKeepLowerHolds = settings.keepLowerHolds
            }

            TouchAimMode.MANUAL_TWO_STATE -> checkNotNull(twoState).also { settings ->
                control.touchAimManualThresholdsInitialized = true
                control.touchAimManualShootOnThreshold = settings.shootOnThreshold
                control.touchAimManualShootOffThreshold = settings.shootOffThreshold
                control.touchAimTwoStateSmoothing = settings.smoothing
                control.touchAimEnterShootConfirmationMs = settings.enterShootConfirmationMs
                control.touchAimReturnToAimConfirmationMs = settings.returnToAimConfirmationMs
                control.touchAimAimPayload = settings.aimPayload
                control.touchAimShootPayload = settings.shootPayload
                control.touchAimKeepAimPayloadWhileShooting = settings.keepAimPayloadWhileShooting
                control.touchAimShootSensitivity = settings.shootSensitivity
                control.touchAimShootBehavior = settings.shootBehavior
            }

            TouchAimMode.CALIBRATED_TWO_STATE -> return false
        }
        control.touchAimAppliedCalibrationId = ""
        control.touchAimAppliedCalibrationName = ""
        control.touchAimSensorTransforms = emptyList()
        control.touchAimMode = mode
        return true
    }
}

fun Control.captureManualTouchAimProfile(
    id: String,
    name: String,
    nowMs: Long
): TouchAimManualProfile? {
    if (type != ControlType.TOUCH_AIM || touchAimMode == TouchAimMode.CALIBRATED_TWO_STATE) return null
    val profile = TouchAimManualProfile(
        id = id,
        name = name.trim(),
        createdAtEpochMs = nowMs,
        updatedAtEpochMs = nowMs,
        mode = touchAimMode,
        aimOutput = touchAimOutput,
        aimSensitivity = sensitivity,
        useSize = touchUseSize,
        useMajor = touchUseMajor,
        useMinor = touchUseMinor,
        sizeScale = touchSizeScale,
        stickFullSpeed = touchStickFullSpeed,
        invertY = touchInvertY,
        useResponseCurve = touchUseResponseCurve,
        threeStage = if (touchAimMode == TouchAimMode.MANUAL_THREE_STAGE) {
            TouchAimManualThreeStageSettings(
                touchScoreSmoothing,
                touchLowThreshold,
                touchMediumThreshold,
                touchHighThreshold,
                touchHysteresis,
                touchLowPayload,
                touchMediumPayload,
                touchHighPayload,
                touchLowAction,
                touchMediumAction,
                touchHighAction,
                touchKeepLowerHolds
            )
        } else null,
        twoState = if (touchAimMode == TouchAimMode.MANUAL_TWO_STATE) {
            TouchAimManualTwoStateSettings(
                if (touchAimManualThresholdsInitialized) touchAimManualShootOnThreshold
                else touchHighThreshold,
                if (touchAimManualThresholdsInitialized) touchAimManualShootOffThreshold
                else (touchHighThreshold - touchHysteresis).coerceAtLeast(0f),
                touchAimTwoStateSmoothing,
                touchAimEnterShootConfirmationMs,
                touchAimReturnToAimConfirmationMs,
                touchAimAimPayload,
                touchAimShootPayload,
                touchAimKeepAimPayloadWhileShooting,
                touchAimShootSensitivity,
                touchAimShootBehavior
            )
        } else null
    )
    return profile.takeIf { it.validationError() == null }
}
