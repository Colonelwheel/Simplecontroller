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
    var holdToggle: Boolean = false,       // “latch” behaviour
    var holdDurationMs: Long = 500,        // long-press threshold
    var isHeld: Boolean     = false,       // current latched state

    /* 2a – mouse-pad one-finger drag */
    /**
     * When **true** this *TOUCHPAD* control automatically sends
     * a Left-Down on finger-down and Left-Up on finger-lift,
     * enabling a one-finger click-drag.
     */
    var holdLeftWhileTouch: Boolean = false
)

/* helper when we auto-create new controls */
fun ControlType.defaultPayload(): String = when (this) {
    ControlType.BUTTON   -> "BUTTON_PRESSED"
    ControlType.STICK    -> "STICK"
    ControlType.TOUCHPAD -> "TOUCHPAD"
}
