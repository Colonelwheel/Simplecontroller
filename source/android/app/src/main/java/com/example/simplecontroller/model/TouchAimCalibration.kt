package com.example.simplecontroller.model

import kotlinx.serialization.Serializable

@Serializable
enum class TouchAimCalibrationState { AIM, SHOOT }

@Serializable
enum class TouchAimSensor { SIZE, TOUCH_MAJOR, TOUCH_MINOR }

@Serializable
enum class TouchAimCalibrationQuality { GOOD, BORDERLINE, UNRELIABLE }

@Serializable
data class TouchAimSensorSample(
    val size: Float,
    val touchMajor: Float,
    val touchMinor: Float,
    val moving: Boolean,
    val elapsedMs: Long = 0L
) {
    fun value(sensor: TouchAimSensor): Float = when (sensor) {
        TouchAimSensor.SIZE -> size
        TouchAimSensor.TOUCH_MAJOR -> touchMajor
        TouchAimSensor.TOUCH_MINOR -> touchMinor
    }
}

@Serializable
data class TouchAimCalibrationPass(
    val state: TouchAimCalibrationState,
    val repetition: Int,
    val samples: List<TouchAimSensorSample>,
    val completed: Boolean = true,
    val durationMs: Long = samples.lastOrNull()?.elapsedMs ?: 0L
)

@Serializable
data class TouchAimSensorTransform(
    val sensor: TouchAimSensor,
    val center: Float,
    val scale: Float,
    val weight: Float = 1f
)

@Serializable
data class TouchAimDistributionSummary(
    val sensor: TouchAimSensor,
    val minimum: Float,
    val p10: Float,
    val median: Float,
    val p90: Float,
    val maximum: Float
)

@Serializable
data class TouchAimPassSummary(
    val state: TouchAimCalibrationState,
    val repetition: Int,
    val sampleCount: Int,
    val stillSampleCount: Int,
    val movingSampleCount: Int,
    val distributions: List<TouchAimDistributionSummary>,
    val stillDistributions: List<TouchAimDistributionSummary> = emptyList(),
    val movingDistributions: List<TouchAimDistributionSummary> = emptyList(),
    /** Bounded synchronized samples preserve correlations and transition timing for later review. */
    val recordedSamples: List<TouchAimSensorSample> = emptyList(),
    val recordingDurationMs: Long = 0L
)

@Serializable
data class TouchAimCalibrationProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deviceManufacturer: String = "",
    val deviceModel: String = "",
    val androidVersion: String = "",
    val orientation: String = "",
    val displayWidthPx: Int = 0,
    val displayHeightPx: Int = 0,
    val displayDensity: Float = 0f,
    val controlWidthPx: Float = 0f,
    val controlHeightPx: Float = 0f,
    val normalizedControlX: Float = 0f,
    val normalizedControlY: Float = 0f,
    val sensors: List<TouchAimSensorTransform>,
    val smoothing: Float,
    val shootOnThreshold: Float,
    val shootOffThreshold: Float,
    val enterShootConfirmationMs: Long,
    val returnToAimConfirmationMs: Long,
    val quality: TouchAimCalibrationQuality,
    val estimatedFalseActivationRate: Float,
    val estimatedMissedActivationRate: Float,
    val movementReducedReliability: Boolean,
    val consistencyScore: Float,
    val validationCompleted: Boolean = false,
    val validationUnexpectedTransitions: Int = 0,
    val passSummaries: List<TouchAimPassSummary>,
    val aimOutput: TouchAimOutput,
    val aimSensitivity: Float = 1f,
    val stickUsesTouchPosition: Boolean = true,
    val stickFullDisplacementPx: Float = 220f,
    val stickDeadzonePx: Float = 8f,
    val shootSensitivity: Float = 1f,
    val aimPayload: String,
    val shootPayload: String,
    val keepAimPayloadWhileShooting: Boolean,
    val shootBehavior: TouchAimShootBehavior
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class TouchAimCalibrationCollection(
    val schemaVersion: Int = TouchAimCalibrationProfile.CURRENT_SCHEMA_VERSION,
    val profiles: List<TouchAimCalibrationProfile> = emptyList()
)

fun TouchAimCalibrationProfile.isApplicable(allowUnreliableBestGuess: Boolean = false): Boolean =
    (quality != TouchAimCalibrationQuality.UNRELIABLE || allowUnreliableBestGuess) &&
        validationCompleted &&
        sensors.isNotEmpty() &&
        sensors.map { it.sensor }.distinct().size == sensors.size &&
        sensors.all {
            it.center.isFinite() && it.scale.isFinite() && it.scale > 0f &&
                it.weight.isFinite() && it.weight > 0f
        } &&
        smoothing.isFinite() && smoothing in 0.05f..1f &&
        shootOnThreshold.isFinite() && shootOffThreshold.isFinite() &&
        shootOffThreshold <= shootOnThreshold - 0.01f &&
        enterShootConfirmationMs in 1L..2000L &&
        returnToAimConfirmationMs in 1L..2000L &&
        aimSensitivity.isFinite() && aimSensitivity >= 0f &&
        stickFullDisplacementPx.isFinite() && stickFullDisplacementPx >= 1f &&
        stickDeadzonePx.isFinite() && stickDeadzonePx >= 0f &&
        stickDeadzonePx < stickFullDisplacementPx &&
        shootSensitivity.isFinite() && shootSensitivity >= 0f

/** Returns false without changing the control if the saved detector is not safe to run. */
fun TouchAimCalibrationProfile.applyTo(
    control: Control,
    allowUnreliableBestGuess: Boolean = false
): Boolean {
    if (!isApplicable(allowUnreliableBestGuess)) return false
    control.touchAimMode = TouchAimMode.CALIBRATED_TWO_STATE
    control.touchAimAppliedCalibrationId = id
    control.touchAimAppliedCalibrationName = name
    control.touchAimSensorTransforms = sensors
    control.touchAimTwoStateSmoothing = smoothing
    control.touchAimShootOnThreshold = shootOnThreshold
    control.touchAimShootOffThreshold = shootOffThreshold
    control.touchAimEnterShootConfirmationMs = enterShootConfirmationMs
    control.touchAimReturnToAimConfirmationMs = returnToAimConfirmationMs
    control.touchAimOutput = aimOutput
    control.sensitivity = aimSensitivity
    control.touchAimStickUsesTouchPosition = stickUsesTouchPosition
    control.touchAimStickFullDisplacementPx = stickFullDisplacementPx
    control.touchAimStickDeadzonePx = stickDeadzonePx
    control.touchAimShootSensitivity = shootSensitivity
    control.touchAimAimPayload = aimPayload
    control.touchAimShootPayload = shootPayload
    control.touchAimKeepAimPayloadWhileShooting = keepAimPayloadWhileShooting
    control.touchAimShootBehavior = shootBehavior
    return true
}

fun Control.touchAimCalibrationDisplayName(): String = when {
    touchAimMode == TouchAimMode.MANUAL_THREE_STAGE -> "Manual three-stage settings"
    touchAimMode == TouchAimMode.MANUAL_TWO_STATE -> "Manual two-state settings"
    touchAimAppliedCalibrationName.isNotBlank() -> touchAimAppliedCalibrationName
    else -> "Custom two-state settings"
}
