package com.example.simplecontroller.ui

import kotlin.math.abs
import kotlin.math.pow

/** Response mapping used only by the opt-in Response Curve Stick control. */
internal object StickResponseCurve {
    fun apply(value: Float, sensitivity: Float): Float {
        val input = value.coerceIn(-1f, 1f)
        if (input == 0f || sensitivity <= 0f) return 0f

        // Keep the endpoints fixed while making sensitivity intuitive:
        // 0.5 -> exponent 2 (gentler center), 1.0 -> linear, 2.0 -> exponent 0.5.
        val exponent = 1.0 / sensitivity.coerceAtMost(5f).toDouble()
        val magnitude = abs(input).toDouble().pow(exponent).toFloat()
        return if (input < 0f) -magnitude else magnitude
    }
}
