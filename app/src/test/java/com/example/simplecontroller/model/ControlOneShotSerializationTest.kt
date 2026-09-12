package com.example.simplecontroller.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ControlOneShotSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun oldLayoutControl_usesSafeOneShotDefaults() {
        val oldJson = """
            {
              "id":"legacy",
              "type":"BUTTON",
              "x":1.0,
              "y":2.0,
              "w":100.0,
              "h":100.0,
              "payload":"X360A",
              "buttonAimEnabled":true,
              "buttonAimOutput":"RIGHT_STICK"
            }
        """.trimIndent()

        val control = json.decodeFromString<Control>(oldJson)

        assertFalse(control.autoTapEnabled)
        assertEquals(100L, control.autoTapIntervalMs)
        assertFalse(control.buttonAimOneShotAlternateEnabled)
        assertEquals("", control.buttonAimAlternatePayload)
        assertEquals(ButtonAimPayloadTiming.IMMEDIATE, control.buttonAimAlternatePayloadTiming)
        assertEquals(2000L, control.buttonAimAlternateResetHoldDurationMs)
        assertEquals(0L, control.buttonAimAlternateBaseUnlatchDelayMs)
        assertEquals(TouchAimOutput.MOUSE, control.buttonAimAlternateOutput)
        assertFalse(control.buttonAimStickUsesTouchPosition)
        assertFalse(control.buttonAimAlternateStickUsesTouchPosition)
    }

    @Test
    fun independentAlternateSettings_roundTrip() {
        val source = Control(
            id = "alternate",
            type = ControlType.BUTTON,
            x = 1f,
            y = 2f,
            w = 100f,
            h = 100f,
            payload = "LT:0.4",
            autoTapEnabled = true,
            autoTapIntervalMs = 175L,
            buttonAimEnabled = true,
            buttonAimStickUsesTouchPosition = true,
            buttonAimOneShotAlternateEnabled = true,
            buttonAimAlternatePayload = "RT:1.0,X360A",
            buttonAimAlternatePayloadTiming = ButtonAimPayloadTiming.SEND_ON_RELEASE,
            buttonAimAlternateResetHoldDurationMs = 2750L,
            buttonAimAlternateBaseUnlatchDelayMs = 140L,
            buttonAimAlternateDisplayName = "Fire",
            buttonAimAlternateOutput = TouchAimOutput.LEFT_STICK,
            buttonAimAlternateSensitivity = 1.7f,
            buttonAimAlternateInvertY = true,
            buttonAimAlternateStickProfile = ButtonAimStickProfile.RESPONSE_CURVE,
            buttonAimAlternateMouseProfile = ButtonAimMouseProfile.LINEAR_RELATIVE,
            buttonAimAlternateStickFullDisplacementPx = 310f,
            buttonAimAlternateStickDeadzonePx = 12f,
            buttonAimAlternateStickUsesTouchPosition = true,
            buttonAimAlternateHaptics = false
        )

        assertEquals(source, json.decodeFromString<Control>(json.encodeToString(source)))
    }
}
