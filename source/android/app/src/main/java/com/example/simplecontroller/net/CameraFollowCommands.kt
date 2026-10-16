package com.example.simplecontroller.net

import java.util.Locale

internal fun cameraFollowStrengthPercent(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 200f) else 100f

internal fun isCameraFollowPayload(payload: String): Boolean =
    payload.trim().uppercase(Locale.ROOT) in setOf("CAMERA_FOLLOW", "CAMERA_FOLLOW:0", "CAMERA_FOLLOW:1")

internal fun orderedStickCommand(
    canonical: String, isMacro: Boolean, excludeFromCameraFollow: Boolean,
    session: String, sequence: Long, x: Float, y: Float
): String {
    val right = canonical.contains("_R")
    val name = when {
        isMacro && right -> "STICK_MACRO_R"
        isMacro && excludeFromCameraFollow -> "STICK_MACRO_L_NO_FOLLOW"
        isMacro -> "STICK_MACRO_L"
        !right && excludeFromCameraFollow -> "STICK_L_NO_FOLLOW"
        else -> canonical
    }
    return "$name:$session:$sequence:${"%.2f".format(Locale.US, x)},${"%.2f".format(Locale.US, y)}"
}

internal fun cameraFollowStrengthCommand(session: String, sequence: Long, percent: Float): String =
    "CAMERA_FOLLOW_STRENGTH:$session:$sequence:${"%.2f".format(Locale.US, cameraFollowStrengthPercent(percent))}"
