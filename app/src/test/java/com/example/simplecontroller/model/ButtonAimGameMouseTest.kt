package com.example.simplecontroller.model

import com.example.simplecontroller.io.ControllerProfilesBackup
import com.example.simplecontroller.io.NamedControllerProfile
import com.example.simplecontroller.io.decodeControllerProfileTransfer
import com.example.simplecontroller.io.decodeControllerProfilesBackup
import com.example.simplecontroller.io.encodeControllerProfileTransfer
import com.example.simplecontroller.io.encodeControllerProfilesBackup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonAimGameMouseTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun legacyButton_withoutAimFields_defaultsBothPhasesToOriginalMouse() {
        val control = json.decodeFromString<Control>("""
            {"id":"old","type":"BUTTON","x":0,"y":0,"w":100,"h":100,"payload":"X360A"}
        """.trimIndent())
        assertEquals(ButtonAimOutput.MOUSE, control.buttonAimOutput)
        assertEquals(ButtonAimOutput.MOUSE, control.buttonAimAlternateOutput)
    }

    @Test
    fun transfer_roundTripsEveryIndependentBaseAndAlternateOutput() {
        ButtonAimOutput.entries.forEach { base ->
            ButtonAimOutput.entries.forEach { alternate ->
                val source = button(base, alternate)
                val encoded = encodeControllerProfileTransfer("Aim", profile(source))
                val game = base == ButtonAimOutput.GAME_MOUSE || alternate == ButtonAimOutput.GAME_MOUSE
                val version = json.parseToJsonElement(encoded).jsonObject.getValue("profile")
                    .jsonObject.getValue("formatVersion").jsonPrimitive.int
                assertEquals(if (game) 7 else 5, version)
                val loaded = decodeControllerProfileTransfer(encoded, "Fallback").result.profile
                assertEquals(source, loaded.pages.single().controls.single())
            }
        }
    }

    @Test
    fun reusableProfile_roundTripsNewOutputAndAppliesAimOnlyOrCompleteIndependently() {
        listOf(
            button(ButtonAimOutput.GAME_MOUSE, ButtonAimOutput.MOUSE),
            button(ButtonAimOutput.LEFT_STICK, ButtonAimOutput.GAME_MOUSE)
        ).forEach { source ->
            val saved = requireNotNull(source.captureButtonAimProfile("aim", "Aim", 1L))
            assertEquals(4, saved.schemaVersion)
            val loaded = json.decodeFromString<ButtonAimProfile>(json.encodeToString(saved))
            val aimOnly = button().copy(payload = "X360B", buttonAimAlternatePayload = "X360Y")
            assertTrue(loaded.applyAimTuningTo(aimOnly))
            assertEquals(source.buttonAimOutput, aimOnly.buttonAimOutput)
            assertEquals(source.buttonAimAlternateOutput, aimOnly.buttonAimAlternateOutput)
            assertEquals(source.buttonAimSensitivity, aimOnly.buttonAimSensitivity)
            assertEquals(source.buttonAimAlternateMouseProfile, aimOnly.buttonAimAlternateMouseProfile)
            assertEquals("X360B", aimOnly.payload)
            assertEquals("X360Y", aimOnly.buttonAimAlternatePayload)
            assertTrue(loaded.applyCompleteTo(aimOnly))
            assertEquals(source.payload, aimOnly.payload)
            assertEquals(source.buttonAimAlternatePayload, aimOnly.buttonAimAlternatePayload)
            assertEquals(source.buttonAimPayloadTiming, aimOnly.buttonAimPayloadTiming)
            assertEquals(source.buttonAimAlternatePayloadTiming, aimOnly.buttonAimAlternatePayloadTiming)
            assertEquals(source.holdToggle, aimOnly.holdToggle)
        }
    }

    @Test
    fun backup_writesConditionalControllerAndReusableVersions() {
        val game = button(ButtonAimOutput.GAME_MOUSE)
        val old = button()
        val backup = ControllerProfilesBackup(
            activeControllerProfileName = "Game",
            controllerProfiles = listOf(
                NamedControllerProfile("Game", profile(game)),
                NamedControllerProfile("Original", profile(old)),
                NamedControllerProfile("Touch", profile(old.copy(
                    type = ControlType.TOUCH_AIM, touchAimGameMouse = true
                )))
            ),
            buttonAimProfiles = listOf(
                requireNotNull(game.captureButtonAimProfile(GAME_ID, "Game", 1L)),
                requireNotNull(old.captureButtonAimProfile(OLD_ID, "Original", 1L))
            )
        )
        val loaded = decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup)).backup
        assertEquals(1, loaded.backupVersion)
        assertEquals(listOf(7, 5, 6), loaded.controllerProfiles.map { it.profile.formatVersion })
        assertEquals(listOf(4, 3), loaded.buttonAimProfiles.map { it.schemaVersion })
        assertEquals(ButtonAimOutput.GAME_MOUSE, loaded.buttonAimProfiles.first().base.output)
    }

    @Test
    fun futureButtonAimSchema_rejectedBeforeUnknownOutputDeserialization() {
        val backup = """
            {"fileType":"simplecontroller-profiles-backup","backupVersion":1,
             "activeControllerProfileName":"Future","controllerProfiles":[],
             "buttonAimProfiles":[{"schemaVersion":5,"base":{"output":"FUTURE_MOUSE"}}]}
        """.trimIndent()
        val error = runCatching { decodeControllerProfilesBackup(backup) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Button Aim profile 1 version 5 is newer"))
    }

    @Test
    fun backup_keepsGameSchemaAndHistoricalNonGameSchema() {
        val game = requireNotNull(button(ButtonAimOutput.GAME_MOUSE)
            .captureButtonAimProfile(GAME_ID, "Game", 1L))
        val old = requireNotNull(button().captureButtonAimProfile(OLD_ID, "Old", 1L))
            .copy(schemaVersion = 2)
        val backup = ControllerProfilesBackup(
            activeControllerProfileName = "Original",
            controllerProfiles = listOf(NamedControllerProfile("Original", profile(button()))),
            buttonAimProfiles = listOf(game, old)
        )
        val loaded = decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup)).backup
        assertEquals(listOf(4, 2), loaded.buttonAimProfiles.map { it.schemaVersion })
        assertEquals(3, requireNotNull(button().captureButtonAimProfile("old", "Old", 1L)).schemaVersion)
    }

    @Test
    fun olderReusableSchema_withGameOutput_rejectedWithoutApplyingSettings() {
        val game = requireNotNull(button(ButtonAimOutput.GAME_MOUSE)
            .captureButtonAimProfile(GAME_ID, "Game", 1L)).copy(schemaVersion = 3)
        val target = button()
        val original = target.copy()
        assertTrue(game.validationError().orEmpty().contains("requires Button Aim profile version 4"))
        assertEquals(false, game.applyAimTuningTo(target))
        assertEquals(false, game.applyCompleteTo(target))
        assertEquals(original, target)
    }

    private fun profile(control: Control) = ControllerProfile(
        homePageId = "base", pages = listOf(ControllerPage("base", "Base", listOf(control)))
    )

    private fun button(
        base: ButtonAimOutput = ButtonAimOutput.MOUSE,
        alternate: ButtonAimOutput = ButtonAimOutput.MOUSE
    ) = Control(
        id = "aim", type = ControlType.BUTTON, x = 0f, y = 0f, w = 200f, h = 150f,
        payload = "MOUSE_LEFT_DOWN", buttonAimEnabled = true, buttonAimOutput = base,
        buttonAimAlternateOutput = alternate, buttonAimSensitivity = .08f,
        buttonAimInvertY = true, buttonAimMouseProfile = ButtonAimMouseProfile.LINEAR_RELATIVE,
        buttonAimAlternateSensitivity = .04f,
        buttonAimAlternateMouseProfile = ButtonAimMouseProfile.SMOOTHED_NONLINEAR,
        buttonAimAlternatePayload = "MOUSE_RIGHT_DOWN", buttonAimOneShotAlternateEnabled = true,
        buttonAimPayloadTiming = ButtonAimPayloadTiming.SEND_ON_RELEASE,
        buttonAimAlternatePayloadTiming = ButtonAimPayloadTiming.IMMEDIATE, holdToggle = true
    )

    private companion object {
        const val GAME_ID = "a4f55a1d-82c3-4c57-9756-b6baf94285a8"
        const val OLD_ID = "f247c84c-cd72-4192-91f1-63b8700945a3"
    }
}
