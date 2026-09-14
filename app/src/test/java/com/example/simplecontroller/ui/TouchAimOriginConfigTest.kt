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
