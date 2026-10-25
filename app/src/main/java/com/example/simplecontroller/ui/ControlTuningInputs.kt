package com.example.simplecontroller.ui

/** Numeric property entry keeps fine values intact and never stores invalid runtime settings. */
internal fun parseSensitivityInput(text: String, fallback: Float): Float =
    text.trim().toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..5f } ?: fallback

internal fun parseOptionalSensitivityInput(text: String, fallback: Float?): Float? =
    if (text.isBlank()) null else
        text.trim().toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..5f } ?: fallback

internal fun parseStageConfirmationInput(text: String, fallback: Long): Long =
    if (text.isBlank()) 0L else
        text.trim().toLongOrNull()?.takeIf { it in 0L..600_000L } ?: fallback
