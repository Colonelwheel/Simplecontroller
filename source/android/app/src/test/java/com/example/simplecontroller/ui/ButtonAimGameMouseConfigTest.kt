package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ButtonAimMouseProfile
import com.example.simplecontroller.model.ButtonAimOutput
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.model.TouchAimOutput
import com.example.simplecontroller.net.mouseDeltaCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonAimGameMouseConfigTest {
    @Test
    fun gameMouseBase_keepsOriginalMouseMappingAndLeavesAlternateIndependent() {
        val old = button().buttonAimConfig()
        val game = button().copy(buttonAimOutput = ButtonAimOutput.GAME_MOUSE)
        assertEquals(old, game.buttonAimConfig().copy(gameMouse = false))
        assertTrue(game.buttonAimConfig().gameMouse)
        assertFalse(game.buttonAimAlternateConfig().gameMouse)
    }

    @Test
    fun gameMouseAlternate_keepsItsOwnMouseProfileSensitivityAndInversion() {
        val old = button().buttonAimAlternateConfig()
        val game = button().copy(buttonAimAlternateOutput = ButtonAimOutput.GAME_MOUSE)
        assertEquals(old, game.buttonAimAlternateConfig().copy(gameMouse = false))
        assertTrue(game.buttonAimAlternateConfig().gameMouse)
        assertFalse(game.buttonAimConfig().gameMouse)
    }

    @Test
    fun newConfigurations_routePcDeltasToExistingGameBackendAndCbv0ToExistingHid() {
        val source = button().copy(
            buttonAimOutput = ButtonAimOutput.GAME_MOUSE,
            buttonAimAlternateOutput = ButtonAimOutput.GAME_MOUSE
        )
        listOf(source.buttonAimConfig(), source.buttonAimAlternateConfig()).forEach { config ->
            assertEquals(TouchAimOutput.MOUSE, config.output)
            assertEquals("GAME_DELTA:1.250,-2.500", mouseDeltaCommand(1.25f, -2.5f, config.gameMouse, false))
            assertEquals("DELTA:1.250,-2.500", mouseDeltaCommand(1.25f, -2.5f, config.gameMouse, true))
        }
        assertEquals("DELTA:1.250,-2.500", mouseDeltaCommand(1.25f, -2.5f, button().buttonAimConfig().gameMouse, false))
    }

    @Test
    fun existingStickConfigurations_keepCanonicalOutputAndDisableGameBackend() {
        val source = button().copy(
            buttonAimOutput = ButtonAimOutput.RIGHT_STICK,
            buttonAimAlternateOutput = ButtonAimOutput.LEFT_STICK
        )
        assertEquals(TouchAimOutput.RIGHT_STICK, source.buttonAimConfig().output)
        assertEquals(TouchAimOutput.LEFT_STICK, source.buttonAimAlternateConfig().output)
        assertFalse(source.buttonAimConfig().gameMouse)
        assertFalse(source.buttonAimAlternateConfig().gameMouse)
    }

    private fun button() = Control(
        id = "aim", type = ControlType.BUTTON, x = 0f, y = 0f, w = 200f, h = 150f,
        payload = "X360A", buttonAimSensitivity = .08f, buttonAimInvertY = true,
        buttonAimMouseProfile = ButtonAimMouseProfile.LINEAR_RELATIVE,
        buttonAimAlternateSensitivity = .03f, buttonAimAlternateInvertY = false,
        buttonAimAlternateMouseProfile = ButtonAimMouseProfile.SMOOTHED_NONLINEAR
    )
}
