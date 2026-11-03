package com.example.simplecontroller.model

import com.example.simplecontroller.io.ControllerProfilesBackup
import com.example.simplecontroller.io.NamedControllerProfile
import com.example.simplecontroller.io.decodeControllerProfileTransfer
import com.example.simplecontroller.io.decodeControllerProfilesBackup
import com.example.simplecontroller.io.encodeControllerProfileTransfer
import com.example.simplecontroller.io.encodeControllerProfilesBackup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchAimGameMouseTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun legacyControl_withoutFlag_retainsOriginalBackend() {
        val control = json.decodeFromString<Control>("""
            {"id":"old","type":"TOUCH_AIM","x":0.0,"y":0.0,
             "w":500.0,"h":320.0,"payload":"TOUCH_AIM"}
        """.trimIndent())
        assertFalse(control.touchAimGameMouse)
        assertEquals(TouchAimMode.MANUAL_THREE_STAGE, control.touchAimMode)
    }

    @Test
    fun completeProfileTransfer_roundTripsMouseChoiceInEveryTouchAimMode() {
        TouchAimMode.entries.forEach { mode ->
            val game = control(true).copy(touchAimMode = mode, touchAimShootSensitivity = .42f)
            val profile = profile(game, control(false).copy(id = "legacy"))
            val encoded = encodeControllerProfileTransfer("Borderlands", profile)
            val wireVersion = json.parseToJsonElement(encoded).jsonObject
                .getValue("profile").jsonObject.getValue("formatVersion").jsonPrimitive.int
            assertEquals(6, wireVersion)
            val loaded = decodeControllerProfileTransfer(encoded, "Fallback").result.profile
            assertTrue(loaded.pages.single().controls.first().touchAimGameMouse)
            assertFalse(loaded.pages.single().controls.last().touchAimGameMouse)
            assertEquals(mode, loaded.pages.single().controls.first().touchAimMode)
            assertEquals(.42f, loaded.pages.single().controls.first().touchAimShootSensitivity)
        }
    }

    @Test
    fun legacyOnlyTransfer_retainsFormatFive() {
        val encoded = encodeControllerProfileTransfer("Legacy", profile(control(false)))
        val wireVersion = json.parseToJsonElement(encoded).jsonObject
            .getValue("profile").jsonObject.getValue("formatVersion").jsonPrimitive.int
        assertEquals(5, wireVersion)
        assertEquals(5, decodeControllerProfileTransfer(encoded, "Fallback").result.profile.formatVersion)
    }

    @Test
    fun allProfileBackup_marksOnlyGameMouseProfileAsRequiringFormatSix() {
        val backup = ControllerProfilesBackup(
            activeControllerProfileName = "Game",
            controllerProfiles = listOf(
                NamedControllerProfile("Game", profile(control(true))),
                NamedControllerProfile("Legacy", profile(control(false)))
            )
        )
        val loaded = decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup)).backup
        assertEquals(1, loaded.backupVersion)
        assertEquals(listOf(6, 5), loaded.controllerProfiles.map { it.profile.formatVersion })
        assertTrue(loaded.controllerProfiles.first().profile.pages.single().controls.single().touchAimGameMouse)
    }

    @Test
    fun manualProfileApply_keepsTargetBackendAcrossSourceVersionsAndManualModes() {
        listOf(TouchAimMode.MANUAL_THREE_STAGE, TouchAimMode.MANUAL_TWO_STATE).forEach { mode ->
            listOf(false, true).forEach { sourceBackend ->
                val source = control(sourceBackend).copy(touchAimMode = mode)
                val captured = requireNotNull(source.captureManualTouchAimProfile("manual", "Settings", 1L))
                listOf(false, true).forEach { targetBackend ->
                    val target = control(targetBackend)
                    assertTrue(captured.applyTo(target))
                    assertEquals(targetBackend, target.touchAimGameMouse)
                    assertEquals(mode, target.touchAimMode)
                }
            }
        }
    }

    private fun profile(vararg controls: Control) = ControllerProfile(
        homePageId = "base", pages = listOf(ControllerPage("base", "Base", controls.toList()))
    )

    private fun control(gameMouse: Boolean) = Control(
        id = "touch", type = ControlType.TOUCH_AIM, x = 0f, y = 0f,
        w = 500f, h = 320f, payload = "TOUCH_AIM", touchAimGameMouse = gameMouse
    )
}
