package com.example.simplecontroller.ui

import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.model.TouchAimOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchAimOriginConfigTest {
    @Test
    fun gameMouse_configKeepsAllOriginalAimSettingsExceptMouseBackend() {
        val legacy = touchAim().touchAimConfig()
        val game = touchAim().copy(touchAimGameMouse = true).touchAimConfig()
        assertFalse(legacy.gameMouse)
        assertTrue(game.gameMouse)
        assertEquals(legacy, game.copy(gameMouse = false))
        assertTrue(game.copy(sensitivity = .42f).gameMouse)
    }

    @Test
    fun existingTouchPositionMode_usesControlCenterWithoutNeutralBegin() {
        val config = touchAim().copy(
            touchAimOutput = TouchAimOutput.RIGHT_STICK,
            touchAimStickUsesTouchPosition = true
        ).touchAimConfig()

        assertEquals(AimOriginMode.CONTROL_CENTER, config.originMode)
        assertFalse(config.sendNeutralOnBegin)
    }

    @Test
    fun relativeMode_usesInitialTouchAndConfiguredTravel() {
        val config = touchAim().copy(
            touchAimOutput = TouchAimOutput.LEFT_STICK,
            touchAimStickUsesTouchPosition = false,
            touchAimStickFullDisplacementPx = 175f,
            touchAimStickDeadzonePx = 4f
        ).touchAimConfig()

        assertEquals(AimOriginMode.INITIAL_TOUCH, config.originMode)
        assertTrue(config.sendNeutralOnBegin)
        assertEquals(175f, config.stickFullDisplacementPx)
        assertEquals(4f, config.stickDeadzonePx)
    }

    @Test
    fun stageSensitivity_overridesAreIndependentAndUnsetStagesInheritGlobal() {
        val control = touchAim().copy(
            sensitivity = .507f,
            touchAimAimOnlySensitivity = .123f,
            touchLowSensitivity = 0f,
            touchMediumSensitivity = .456f
        )
        assertEquals(.123f, control.touchAimStageSensitivity(TouchContactLevel.AIM_ONLY))
        assertEquals(0f, control.touchAimStageSensitivity(TouchContactLevel.LOW))
        assertEquals(.456f, control.touchAimStageSensitivity(TouchContactLevel.MEDIUM))
        assertEquals(.507f, control.touchAimStageSensitivity(TouchContactLevel.HIGH))
    }

    @Test
    fun invalidStageSensitivity_fallsBackToGlobal() {
        val control = touchAim().copy(
            sensitivity = .321f, touchLowSensitivity = Float.NaN, touchMediumSensitivity = -1f
        )
        assertEquals(.321f, control.touchAimStageSensitivity(TouchContactLevel.LOW))
        assertEquals(.321f, control.touchAimStageSensitivity(TouchContactLevel.MEDIUM))
    }

    private fun touchAim() = Control(
        id = "touch",
        type = ControlType.TOUCH_AIM,
        x = 0f,
        y = 0f,
        w = 500f,
        h = 320f,
        payload = "TOUCH_AIM"
    )
}
