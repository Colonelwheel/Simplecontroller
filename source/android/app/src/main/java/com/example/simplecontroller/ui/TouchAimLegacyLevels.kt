package com.example.simplecontroller.ui

/** Raw contact score shared by both manual TouchAim modes. */
fun manualTouchAimScore(
    size: Float,
    major: Float,
    minor: Float,
    useSize: Boolean,
    useMajor: Boolean,
    useMinor: Boolean,
    sizeScale: Float
): Float {
    val values = buildList {
        if (useSize) add(size * sizeScale.coerceAtLeast(0f))
        if (useMajor) add(major)
        if (useMinor) add(minor)
    }
    return if (values.isEmpty()) 0f else values.average().toFloat()
}

/** Exact legacy three-stage threshold behavior, extracted only for regression testing. */
fun legacyTouchAimLevelForScore(
    score: Float,
    currentLevel: TouchContactLevel,
    lowThreshold: Float,
    mediumThreshold: Float,
    highThreshold: Float,
    hysteresis: Float
): TouchContactLevel {
    val low = lowThreshold.coerceAtLeast(0f)
    val medium = mediumThreshold.coerceAtLeast(low)
    val high = highThreshold.coerceAtLeast(medium)
    val stages = listOf(
        TouchContactLevel.LOW to low,
        TouchContactLevel.MEDIUM to medium,
        TouchContactLevel.HIGH to high
    )
    val rawLevel = stages.lastOrNull { score >= it.second }?.first ?: TouchContactLevel.AIM_ONLY
    if (rawLevel.rank >= currentLevel.rank) return rawLevel
    val safeHysteresis = hysteresis.coerceAtLeast(0f)
    return stages.lastOrNull {
        score >= (it.second - safeHysteresis).coerceAtLeast(0f)
    }?.first ?: TouchContactLevel.AIM_ONLY
}
