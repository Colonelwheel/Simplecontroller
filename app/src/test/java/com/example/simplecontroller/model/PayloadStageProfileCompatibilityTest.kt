package com.example.simplecontroller.model

import com.example.simplecontroller.io.ControllerProfilesBackup
import com.example.simplecontroller.io.NamedControllerProfile
import com.example.simplecontroller.io.decodeControllerProfile
import com.example.simplecontroller.io.decodeControllerProfilesBackup
import com.example.simplecontroller.io.deepCopyControls
import com.example.simplecontroller.io.encodeControllerProfile
import com.example.simplecontroller.io.encodeControllerProfilesBackup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PayloadStageProfileCompatibilityTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun legacyControl_inheritsSharedSensitivityAndImmediateEntry() {
        val old = """{"id":"touch","type":"TOUCH_AIM","x":0,"y":0,"w":200,"h":200,"payload":"TOUCH_AIM","sensitivity":0.783}"""
        val control = json.decodeFromString<Control>(old)
        assertEquals(.783f, control.sensitivity)
        assertNull(control.touchMediumSensitivity)
        assertNull(control.touchAimAimOnlySensitivity)
        assertEquals(0L, control.touchHighEnterConfirmationMs)
        assertEquals(5, profile(control).requiredFormatVersion())
    }

    @Test fun stageTuning_controllerRoundTripAndDuplicatePreserveAllFourStages() {
        val source = tunedTouchAim()
        val encoded = encodeControllerProfile(profile(source))
        val decoded = decodeControllerProfile(encoded, "Stages").profile
        assertEquals(14, decoded.formatVersion)
        assertEquals(source, decoded.pages.single().controls.single())
        assertEquals(source, deepCopyControls(listOf(source)).single())
        assertEquals(.001f, decoded.pages.single().controls.single().touchHighSensitivity)
    }

    @Test fun stageTuning_underVersionedControllerRejectedBeforeDecode() {
        val encoded = encodeControllerProfile(profile(tunedTouchAim()))
        val oldVersion = JsonObject(json.parseToJsonElement(encoded).jsonObject +
            ("formatVersion" to JsonPrimitive(13)))
        val failure = runCatching { decodeControllerProfile(oldVersion.toString(), "Stages") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("format 14"))
    }

    @Test fun inactiveModifiers_requireAwareControllerReader() {
        val button = control(ControlType.BUTTON).copy(
            buttonAimOneShotAlternateEnabled = false,
            buttonAimAlternatePayload = "RT:1.0(SOR)",
            directionalMode = false,
            extendedBoost = ExtendedBoostSettings(outerCommands = DirectionalCommands(up = "X360A(HRD200)"))
        )
        assertEquals(14, profile(button).requiredFormatVersion())
        val encoded = encodeControllerProfile(profile(button))
        assertEquals(button, decodeControllerProfile(encoded, "Modifiers").profile.pages.single().controls.single())
    }

    @Test fun manualCapture_roundTripApplyPreservesStageTuningAndPayloadModifiers() {
        val source = tunedTouchAim().copy(touchMediumPayload = "LT:1.0(I)(HRD200)")
        val saved = requireNotNull(source.captureManualTouchAimProfile(ID, "Stages", 1))
        assertEquals(2, saved.schemaVersion)
        val restored = json.decodeFromString<TouchAimManualProfile>(json.encodeToString(saved))
        val target = control(ControlType.TOUCH_AIM)
        assertTrue(restored.applyTo(target))
        assertEquals(source.touchAimAimOnlySensitivity, target.touchAimAimOnlySensitivity)
        assertEquals(source.touchLowSensitivity, target.touchLowSensitivity)
        assertEquals(source.touchMediumSensitivity, target.touchMediumSensitivity)
        assertEquals(source.touchHighSensitivity, target.touchHighSensitivity)
        assertEquals(source.touchAimAimOnlyEnterConfirmationMs, target.touchAimAimOnlyEnterConfirmationMs)
        assertEquals(source.touchLowEnterConfirmationMs, target.touchLowEnterConfirmationMs)
        assertEquals(source.touchMediumEnterConfirmationMs, target.touchMediumEnterConfirmationMs)
        assertEquals(source.touchHighEnterConfirmationMs, target.touchHighEnterConfirmationMs)
        assertEquals(source.touchMediumPayload, target.touchMediumPayload)
    }

    @Test fun legacyManualApplication_restoresInheritedStageValues() {
        val saved = requireNotNull(control(ControlType.TOUCH_AIM).captureManualTouchAimProfile(ID, "Legacy", 1))
        assertEquals(1, saved.schemaVersion)
        val target = tunedTouchAim()
        assertTrue(saved.applyTo(target))
        assertNull(target.touchAimAimOnlySensitivity)
        assertNull(target.touchLowSensitivity)
        assertNull(target.touchMediumSensitivity)
        assertNull(target.touchHighSensitivity)
        assertEquals(0L, target.touchMediumEnterConfirmationMs)
    }

    @Test fun invalidStageTuning_rejectedWithoutChangingTarget() {
        val valid = requireNotNull(tunedTouchAim().captureManualTouchAimProfile(ID, "Valid", 1))
        for (settings in listOf(
            valid.threeStage!!.copy(lowSensitivity = Float.NaN),
            valid.threeStage.copy(highSensitivity = 5.001f),
            valid.threeStage.copy(mediumEnterConfirmationMs = -1),
            valid.threeStage.copy(aimOnlyEnterConfirmationMs = 600_001)
        )) {
            val invalid = valid.copy(threeStage = settings)
            val target = control(ControlType.TOUCH_AIM)
            val before = target.copy()
            assertFalse(invalid.applyTo(target))
            assertEquals(before, target)
        }
        assertNotNull(valid.copy(schemaVersion = 1).validationError())
    }

    @Test fun payloadModifiers_reusableSchemasUpgradeOnlyWhenNeeded() {
        val button = control(ControlType.BUTTON).copy(payload = "LT:1.0(I)(HRD200),RT:1.0(SOR)")
        val buttonSaved = requireNotNull(button.captureButtonAimProfile(ID, "Button", 1))
        assertEquals(6, buttonSaved.schemaVersion)
        assertNotNull(buttonSaved.copy(schemaVersion = 5).validationError())
        val directional = control(ControlType.STICK).copy(directionalMode = true, upCommand = "W(HRD200)")
        val directionalSaved = requireNotNull(directional.captureStickDirectionalProfile(ID, "Stick", 1))
        assertEquals(3, directionalSaved.schemaVersion)
        assertNotNull(directionalSaved.copy(schemaVersion = 2).validationError())
        assertEquals(3, requireNotNull(control(ControlType.BUTTON).captureButtonAimProfile(ID, "Old", 1)).schemaVersion)
        assertEquals(2, requireNotNull(directional.copy(upCommand = "W").captureStickDirectionalProfile(ID, "Old", 1)).schemaVersion)
    }

    @Test fun allProfileBackup_roundTripsNewTuningAndReusableSchema() {
        val source = tunedTouchAim()
        val saved = requireNotNull(source.captureManualTouchAimProfile(ID, "Stages", 1))
        val backup = ControllerProfilesBackup(
            activeControllerProfileName = "Stages",
            controllerProfiles = listOf(NamedControllerProfile("Stages", profile(source))),
            touchAimManualProfiles = listOf(saved)
        )
        val restored = decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup)).backup
        assertEquals(source, restored.controllerProfiles.single().profile.pages.single().controls.single())
        assertEquals(saved, restored.touchAimManualProfiles.single())
    }

    @Test fun manualBackup_underVersionedOrFutureSchemaRejectedBeforeTypedDecode() {
        val source = tunedTouchAim()
        val saved = requireNotNull(source.captureManualTouchAimProfile(ID, "Stages", 1))
        val backup = ControllerProfilesBackup(
            activeControllerProfileName = "Stages",
            controllerProfiles = listOf(NamedControllerProfile("Stages", profile(source))),
            touchAimManualProfiles = listOf(saved)
        )
        val encoded = encodeControllerProfilesBackup(backup)
        assertTrue(runCatching { decodeControllerProfilesBackup(encoded.replace("\"schemaVersion\": 2", "\"schemaVersion\": 1")) }.isFailure)
        val raw = """{"fileType":"simplecontroller-profiles-backup","backupVersion":1,
            "controllerProfiles":[],"touchAimManualProfiles":[{"schemaVersion":999,"mode":"UNKNOWN_FUTURE_MODE"}]}"""
        val failure = runCatching { decodeControllerProfilesBackup(raw) }.exceptionOrNull()
        assertTrue(failure!!.message.orEmpty().contains("999"))
    }

    @Test fun malformedModifier_controllerSaveRejectedRatherThanChangingLegacyCommandMeaning() {
        val invalid = control(ControlType.BUTTON).copy(payload = "X360A(HRD-2)")
        assertTrue(runCatching { encodeControllerProfile(profile(invalid)) }.isFailure)
        val legacy = control(ControlType.BUTTON).copy(payload = "LEGACY_CUSTOM_COMMAND")
        assertEquals(5, profile(legacy).requiredFormatVersion())
        assertTrue(runCatching { encodeControllerProfile(profile(legacy)) }.isSuccess)
    }

    @Test fun modifiedManualStage_rejectsAnIrreversibleCommandInAnotherStage() {
        val legacy = control(ControlType.TOUCH_AIM).copy(touchLowPayload = "CAMERA_FOLLOW")
        assertNotNull(legacy.captureManualTouchAimProfile(ID, "Legacy", 1))
        assertNull(legacy.copy(touchMediumPayload = "LT:1.0(HRD200)")
            .captureManualTouchAimProfile(ID, "Unsafe mix", 1))
    }

    private fun profile(control: Control) = ControllerProfile(
        homePageId = "base", pages = listOf(ControllerPage("base", "Base", listOf(control)))
    )

    private fun control(type: ControlType) = Control("control", type, 0f, 0f, 200f, 200f,
        if (type == ControlType.TOUCH_AIM) "TOUCH_AIM" else if (type == ControlType.STICK) "STICK" else "X360A")

    private fun tunedTouchAim() = control(ControlType.TOUCH_AIM).copy(
        touchAimAimOnlySensitivity = 1.123f,
        touchLowSensitivity = .783f,
        touchMediumSensitivity = .257f,
        touchHighSensitivity = .001f,
        touchAimAimOnlyEnterConfirmationMs = 20,
        touchLowEnterConfirmationMs = 50,
        touchMediumEnterConfirmationMs = 100,
        touchHighEnterConfirmationMs = 600_000
    )

    private companion object { const val ID = "00000000-0000-0000-0000-000000000001" }
}
