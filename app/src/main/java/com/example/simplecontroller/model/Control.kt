package com.example.simplecontroller.model

import kotlinx.serialization.Serializable

@Serializable
enum class ControlType { BUTTON, STICK, TOUCHPAD }   // ← keep the names you already use

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
    var rightSuperBoostCommand: String = "D,SHIFT,SPACE"
)

/* helper when we auto-create new controls */
fun ControlType.defaultPayload(): String = when (this) {
    ControlType.BUTTON   -> "BUTTON_PRESSED"
    ControlType.STICK    -> "STICK"
    ControlType.TOUCHPAD -> "TOUCHPAD"
}