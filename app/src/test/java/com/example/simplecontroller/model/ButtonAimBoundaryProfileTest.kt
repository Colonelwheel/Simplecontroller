package com.example.simplecontroller.model

import com.example.simplecontroller.io.ControllerProfilesBackup
import com.example.simplecontroller.io.NamedControllerProfile
import com.example.simplecontroller.io.decodeControllerProfileTransfer
import com.example.simplecontroller.io.decodeControllerProfilesBackup
import com.example.simplecontroller.io.encodeControllerProfileTransfer
import com.example.simplecontroller.io.encodeControllerProfilesBackup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonAimBoundaryProfileTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun oldControlAndReusableActions_defaultToNoBoundaryCancellation() {
        val source = button(false)
        val encodedControl = json.encodeToString(source)
            .replace("\"buttonAimCancelOnBoundaryExit\":false,", "")
            .replace("\"buttonAimRearmOnBoundaryReturn\":false,", "")
        assertFalse(json.decodeFromString<Control>(encodedControl).buttonAimCancelOnBoundaryExit)
        assertFalse(json.decodeFromString<Control>(encodedControl).buttonAimRearmOnBoundaryReturn)
        val saved = requireNotNull(source.captureButtonAimProfile(ID, "Old", 1L))
        val encodedProfile = json.encodeToString(saved).replace(
            ",\"cancelOnBoundaryExit\":false", ""
        ).replace(
            ",\"rearmOnBoundaryReturn\":false", ""
        )
        assertFalse(json.decodeFromString<ButtonAimProfile>(encodedProfile).actions.cancelOnBoundaryExit)
        assertFalse(json.decodeFromString<ButtonAimProfile>(encodedProfile).actions.rearmOnBoundaryReturn)
    }

    @Test
    fun fullTransfer_roundTripsOptInEvenWhenAimIsDisabled() {
        val source = button(true).copy(buttonAimEnabled = false, buttonAimRearmOnBoundaryReturn = true)
        val decoded = decodeControllerProfileTransfer(
            encodeControllerProfileTransfer("Aim", profile(source)), "Fallback"
        ).result.profile
        assertEquals(9, decoded.formatVersion)
        assertEquals(source, decoded.pages.single().controls.single())
    }

    @Test
    fun conditionalFormats_preserveAllPriorFeatureVersions() {
        val plain = button(false)
        val controls = listOf(
            plain,
            plain.copy(type = ControlType.TOUCH_AIM, touchAimGameMouse = true),
            plain.copy(buttonAimAlternateOutput = ButtonAimOutput.GAME_MOUSE),
            plain.copy(excludeFromCameraFollow = true),
            plain.copy(buttonAimCancelOnBoundaryExit = true, excludeFromCameraFollow = true)
        )
        assertEquals(listOf(5, 6, 7, 8, 9), controls.map { profile(it).requiredFormatVersion() })
    }

    @Test
    fun reusableProfile_aimOnlyPreservesTargetActionAndCompleteCopiesIt() {
        val saved = requireNotNull(button(true).copy(buttonAimRearmOnBoundaryReturn = true)
            .captureButtonAimProfile(ID, "Cancel", 1L))
        val loaded = json.decodeFromString<ButtonAimProfile>(json.encodeToString(saved))
        assertEquals(5, loaded.schemaVersion)
        val target = button(false).copy(payload = "X360B")
        assertTrue(loaded.applyAimTuningTo(target))
        assertFalse(target.buttonAimCancelOnBoundaryExit)
        assertFalse(target.buttonAimRearmOnBoundaryReturn)
        assertEquals("X360B", target.payload)
        assertTrue(loaded.applyCompleteTo(target))
        assertTrue(target.buttonAimCancelOnBoundaryExit)
        assertTrue(target.buttonAimRearmOnBoundaryReturn)
        assertEquals("X360A", target.payload)
        val legacy = requireNotNull(button(false).captureButtonAimProfile(ID, "Legacy", 1L))
        assertTrue(legacy.applyAimTuningTo(target))
        assertTrue(target.buttonAimCancelOnBoundaryExit)
        assertTrue(target.buttonAimRearmOnBoundaryReturn)
        assertTrue(legacy.applyCompleteTo(target))
        assertFalse(target.buttonAimCancelOnBoundaryExit)
        assertFalse(target.buttonAimRearmOnBoundaryReturn)
    }

    @Test
    fun backup_roundTripsOptInAlongsideLegacyReusableSchemas() {
        val cancel = button(true).copy(buttonAimRearmOnBoundaryReturn = true)
        val plain = button(false)
        val saved = requireNotNull(cancel.captureButtonAimProfile(ID, "Cancel", 1L))
        val legacy = requireNotNull(plain.captureButtonAimProfile(OLD_ID, "Old", 1L))
            .copy(schemaVersion = 2)
        val backup = ControllerProfilesBackup(
            activeControllerProfileName = "Cancel",
            controllerProfiles = listOf(NamedControllerProfile("Cancel", profile(cancel))),
            buttonAimProfiles = listOf(saved, legacy)
        )
        val loaded = decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup)).backup
        assertEquals(9, loaded.controllerProfiles.single().profile.formatVersion)
        assertEquals(listOf(5, 2), loaded.buttonAimProfiles.map { it.schemaVersion })
        assertTrue(loaded.buttonAimProfiles.first().actions.cancelOnBoundaryExit)
        assertTrue(loaded.buttonAimProfiles.first().actions.rearmOnBoundaryReturn)
    }

    @Test
    fun mislabeledNewAction_isRejectedWithoutMutatingTarget() {
        val saved = requireNotNull(button(true).captureButtonAimProfile(ID, "Cancel", 1L))
            .copy(schemaVersion = 4)
        val target = button(false)
        val original = target.copy()
        assertNotNull(saved.validationError())
        assertFalse(saved.applyAimTuningTo(target))
        assertFalse(saved.applyCompleteTo(target))
        assertEquals(original, target)
    }

    @Test
    fun schemaSelection_keepsGameMouseAtFourAndHistoricalSchemasUnchanged() {
        val old = requireNotNull(button(false).captureButtonAimProfile(ID, "Old", 1L))
        assertEquals(3, old.schemaVersion)
        for (version in 1..3) assertEquals(version, old.copy(schemaVersion = version).requiredSchemaVersion())
        val game = requireNotNull(button(false).copy(buttonAimOutput = ButtonAimOutput.GAME_MOUSE)
            .captureButtonAimProfile(ID, "Game", 1L))
        assertEquals(4, game.schemaVersion)
        assertEquals(4, game.requiredSchemaVersion())
        assertEquals(3, old.copy(schemaVersion = 5).requiredSchemaVersion())
    }

    @Test
    fun latentRearmOption_requiresNewVersionsAndRoundTripsEvenWithCancellationOff() {
        val source = button(false).copy(buttonAimRearmOnBoundaryReturn = true)
        assertEquals(9, profile(source).requiredFormatVersion())
        val saved = requireNotNull(source.captureButtonAimProfile(ID, "Return", 1L))
        assertEquals(5, saved.schemaVersion)
        assertEquals(5, saved.requiredSchemaVersion())
        assertFalse(saved.actions.cancelOnBoundaryExit)
        assertTrue(saved.actions.rearmOnBoundaryReturn)
        assertNotNull(saved.copy(schemaVersion = 4).validationError())
        val target = button(true)
        assertTrue(saved.applyCompleteTo(target))
        assertFalse(target.buttonAimCancelOnBoundaryExit)
        assertTrue(target.buttonAimRearmOnBoundaryReturn)
    }

    private fun profile(control: Control) = ControllerProfile(
        homePageId = "base", pages = listOf(ControllerPage("base", "Base", listOf(control)))
    )

    private fun button(cancel: Boolean) = Control(
        id = "aim", type = ControlType.BUTTON, x = 0f, y = 0f, w = 100f, h = 80f,
        payload = "X360A", buttonAimEnabled = true,
        buttonAimPayloadTiming = ButtonAimPayloadTiming.SEND_ON_RELEASE,
        buttonAimCancelOnBoundaryExit = cancel
    )

    private companion object {
        const val ID = "457326e1-7a92-46be-aafe-a4658425bfdc"
        const val OLD_ID = "691b3fe5-6f2f-4fbf-89dc-f05507f72861"
    }
}
