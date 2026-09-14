package com.example.simplecontroller.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchAimLegacyLevelsTest {
    @Test
    fun manualScore_usesOnlySelectedSensorsAndSizeMultiplier() {
        assertEquals(
            3f,
            manualTouchAimScore(
                size = 0.5f,
                major = 4f,
                minor = 9f,
                useSize = true,
                useMajor = true,
                useMinor = false,
                sizeScale = 4f
            )
        )
        assertEquals(
            0f,
            manualTouchAimScore(0.5f, 4f, 9f, false, false, false, 4f)
        )
    }

    @Test
    fun oldThreeStageThresholdsAndHysteresis_remainUnchanged() {
        assertEquals(
            TouchContactLevel.AIM_ONLY,
            legacyTouchAimLevelForScore(1.9f, TouchContactLevel.AIM_ONLY, 2f, 4f, 5.2f, .25f)
        )
        assertEquals(
            TouchContactLevel.LOW,
            legacyTouchAimLevelForScore(2f, TouchContactLevel.AIM_ONLY, 2f, 4f, 5.2f, .25f)
        )
        assertEquals(
            TouchContactLevel.HIGH,
            legacyTouchAimLevelForScore(5.2f, TouchContactLevel.MEDIUM, 2f, 4f, 5.2f, .25f)
        )
        assertEquals(
            TouchContactLevel.HIGH,
            legacyTouchAimLevelForScore(5.0f, TouchContactLevel.HIGH, 2f, 4f, 5.2f, .25f)
        )
        assertEquals(
            TouchContactLevel.MEDIUM,
            legacyTouchAimLevelForScore(4.8f, TouchContactLevel.HIGH, 2f, 4f, 5.2f, .25f)
        )
    }
}
