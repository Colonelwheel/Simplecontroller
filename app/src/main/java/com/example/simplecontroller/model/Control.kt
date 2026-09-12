package com.example.simplecontroller.model

import kotlinx.serialization.Serializable

@Serializable
enum class ControlType { BUTTON, STICK, CURVED_STICK, TOUCHPAD, TOUCH_AIM, RECENTER }

@Serializable
enum class TouchAimOutput { MOUSE, RIGHT_STICK, LEFT_STICK }

@Serializable
enum class TouchStageAction { PRESS, HOLD }

@Serializable
enum class ButtonAimPayloadTiming { IMMEDIATE, SEND_ON_RELEASE }

@Serializable
enum class ButtonAimStickProfile { LINEAR, RESPONSE_CURVE }

@Serializable
enum class ButtonAimMouseProfile { LINEAR_RELATIVE, SMOOTHED_NONLINEAR }

@Serializable
data class Control(
    val id: String,
    val type: ControlType,
    var x: Float,
    var y: Float,
    var w: Float,
    var h: Float,
    var payload: String,

    /* ────────── editable settings ────────── */
    var name: String        = "",
    var sensitivity: Float  = 1f,          // sticks / touch-pads
    var autoCenter: Boolean = true,        // sticks / touch-pads

    /* button-specific */
    var holdToggle: Boolean = false,       // "latch" behaviour
    var holdDurationMs: Long = 500,        // long-press threshold
    var autoTapEnabled: Boolean = false,
    var autoTapIntervalMs: Long = 100L,

    /* Button Aim Surface (disabled by default for layout compatibility) */
    var buttonAimEnabled: Boolean = false,
    var buttonAimOutput: TouchAimOutput = TouchAimOutput.MOUSE,
    var buttonAimPayloadTiming: ButtonAimPayloadTiming = ButtonAimPayloadTiming.IMMEDIATE,
    var buttonAimReleaseDelayMs: Long = 0,
    var buttonAimSensitivity: Float = 1f,
    var buttonAimInvertY: Boolean = false,
    var buttonAimStickProfile: ButtonAimStickProfile = ButtonAimStickProfile.LINEAR,
    var buttonAimMouseProfile: ButtonAimMouseProfile = ButtonAimMouseProfile.SMOOTHED_NONLINEAR,
    var buttonAimStickFullDisplacementPx: Float = 220f,
    var buttonAimStickDeadzonePx: Float = 8f,
    var buttonAimStickUsesTouchPosition: Boolean = false,
    var buttonAimHaptics: Boolean = true,

    /* One-Shot Alternate Button Phase (disabled by default) */
    var buttonAimOneShotAlternateEnabled: Boolean = false,
    var buttonAimAlternatePayload: String = "",
    var buttonAimAlternatePayloadTiming: ButtonAimPayloadTiming = ButtonAimPayloadTiming.IMMEDIATE,
    var buttonAimAlternateResetHoldDurationMs: Long = 2000L,
    var buttonAimAlternateBaseUnlatchDelayMs: Long = 0L,
    var buttonAimAlternateDisplayName: String = "",
    var buttonAimAlternateOutput: TouchAimOutput = TouchAimOutput.MOUSE,
    var buttonAimAlternateSensitivity: Float = 1f,
    var buttonAimAlternateInvertY: Boolean = false,
    var buttonAimAlternateStickProfile: ButtonAimStickProfile = ButtonAimStickProfile.LINEAR,
    var buttonAimAlternateMouseProfile: ButtonAimMouseProfile = ButtonAimMouseProfile.SMOOTHED_NONLINEAR,
    var buttonAimAlternateStickFullDisplacementPx: Float = 220f,
    var buttonAimAlternateStickDeadzonePx: Float = 8f,
    var buttonAimAlternateStickUsesTouchPosition: Boolean = false,
    var buttonAimAlternateHaptics: Boolean = true,

    /* 2a – mouse-pad one-finger drag */
    /**
     * When **true** this *TOUCHPAD* control automatically sends
     * a Left-Down on finger-down and Left-Up on finger-lift,
     * enabling a one-finger click-drag.
     */
    var holdLeftWhileTouch: Boolean = false,

    /* Touchpad click lock mode */
    /**
     * When **true**, this *TOUCHPAD* toggles the mouse left button state
     * each time it's touched, rather than holding only while touching.
     * This allows for clicking and then moving without holding.
     */
    var toggleLeftClick: Boolean = false,

    /* NEW: Touchpad double-tap click-lock (Unified Remote style) */
    /**
     * When true, a quick double-tap locks the left mouse button down,
     * and the next quick tap (with minimal movement) unlocks it.
     */
    var doubleTapClickLock: Boolean = false,

    /* 2b - per-control swipe activation */
    /**
     * When **true** this control can be activated by swiping onto it
     * from another control, without lifting the finger.
     */
    var swipeActivate: Boolean = true,

    /* Directional mode for sticks */
    var directionalMode: Boolean = false,
    var upCommand: String = "W",
    var downCommand: String = "S",
    var leftCommand: String = "A",
    var rightCommand: String = "D",
    var boostThreshold: Float = 0.5f,
    var upBoostCommand: String = "W,SHIFT",
    var downBoostCommand: String = "S,CTRL",
    var leftBoostCommand: String = "A,SHIFT",
    var rightBoostCommand: String = "D,SHIFT",
    var superBoostThreshold: Float = 0.75f,
    var upSuperBoostCommand: String = "W,SHIFT,SPACE",
    var downSuperBoostCommand: String = "S,CTRL,SPACE",
    var leftSuperBoostCommand: String = "A,SHIFT,SPACE",
    var rightSuperBoostCommand: String = "D,SHIFT,SPACE",

    /* Stick+ mode - hybrid analog + directional */
    var stickPlusMode: Boolean = false,

    /* Analog threshold mode (for non-directional sticks) */
    /**
     * When **true**, the stick will send additional commands when
     * pushed beyond the threshold value, regardless of direction.
     */
    var thresholdEnabled: Boolean = false,

    /**
     * Threshold value (0.0-1.0) at which point threshold actions are triggered.
     */
    var threshold: Float = 0.5f,

    /**
     * Comma-separated commands to send when the stick exceeds the threshold.
     */
    var thresholdPayload: String = "",

    /**
     * When **true**, the stick will send additional commands when
     * pushed beyond the super threshold value, regardless of direction.
     */
    var superThresholdEnabled: Boolean = false,

    /**
     * Super threshold value (0.0-1.0) at which point super threshold actions are triggered.
     */
    var superThreshold: Float = 0.8f,

    /**
     * Comma-separated commands to send when the stick exceeds the super threshold.
     */
    var superThresholdPayload: String = "",

    /* Touch Aim: continuous aim plus cumulative contact stages */
    var touchAimOutput: TouchAimOutput = TouchAimOutput.MOUSE,
    var touchUseSize: Boolean = true,
    var touchUseMajor: Boolean = true,
    var touchUseMinor: Boolean = true,
    var touchSizeScale: Float = 100f,
    var touchScoreSmoothing: Float = 0.35f,
    var touchLowThreshold: Float = 2.0f,
    var touchMediumThreshold: Float = 4.0f,
    var touchHighThreshold: Float = 5.2f,
    var touchHysteresis: Float = 0.25f,
    var touchLowPayload: String = "",
    var touchMediumPayload: String = "LT:1.0",
    var touchHighPayload: String = "RT:1.0",
    var touchLowAction: TouchStageAction = TouchStageAction.PRESS,
    var touchMediumAction: TouchStageAction = TouchStageAction.HOLD,
    var touchHighAction: TouchStageAction = TouchStageAction.HOLD,
    var touchKeepLowerHolds: Boolean = true,
    var touchStickFullSpeed: Float = 900f,
    var touchInvertY: Boolean = false,
    var touchUseResponseCurve: Boolean = false
)

/* helper when we auto-create new controls */
fun ControlType.defaultPayload(): String = when (this) {
    ControlType.BUTTON   -> "BUTTON_PRESSED"
    ControlType.STICK    -> "STICK"
    ControlType.CURVED_STICK -> "STICK"
    ControlType.TOUCHPAD -> "TOUCHPAD"
    ControlType.TOUCH_AIM -> "TOUCH_AIM"
    ControlType.RECENTER -> "RECENTER"
}
