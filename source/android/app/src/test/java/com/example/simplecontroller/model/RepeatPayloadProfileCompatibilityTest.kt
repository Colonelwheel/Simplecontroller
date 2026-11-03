package com.example.simplecontroller.model

import com.example.simplecontroller.io.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class RepeatPayloadProfileCompatibilityTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun repeatPayloads_controllerAndTransferRoundTripWithoutChangingExistingModifierVersion() {
        val original = profile(button().copy(payload = "LT:1.0(I)(HRD200),RT:1.0(R100)"))
        assertEquals(15, original.requiredFormatVersion())
        val restored = decodeControllerProfile(encodeControllerProfile(original), "Repeat").profile
        assertEquals(15, restored.formatVersion)
        assertEquals(original.pages, restored.pages)
        assertEquals(original.pages, decodeControllerProfileTransfer(
            encodeControllerProfileTransfer("Repeat", original), "Fallback").result.profile.pages)
        assertEquals(14, profile(button().copy(payload = "RT:1.0(I)(HRD200)")).requiredFormatVersion())
        assertEquals(14, profile(touchAim().copy(touchMediumSensitivity = .123f)).requiredFormatVersion())
        assertEquals(5, profile(button()).requiredFormatVersion())
    }

    @Test fun inactiveRepeatCommands_requireVersionFifteenAcrossAllStoredActionLocations() {
        val repeat = "RT:1.0(R100)"
        val controls = listOf(
            button().copy(payload = repeat),
            button().copy(buttonAimAlternatePayload = repeat),
            button().copy(buttonDirectional = ButtonDirectionalSettings(up = ButtonDirectionStages(firstPayload = repeat))),
            touchAim().copy(touchLowPayload = repeat),
            touchAim().copy(touchMediumPayload = repeat),
            touchAim().copy(touchHighPayload = repeat),
            touchAim().copy(touchAimAimPayload = repeat),
            touchAim().copy(touchAimShootPayload = repeat),
            stick().copy(thresholdPayload = repeat),
            stick().copy(superThresholdPayload = repeat),
            stick().copy(upCommand = repeat),
            stick().copy(rightSuperBoostCommand = repeat),
            stick().copy(extendedBoost = ExtendedBoostSettings(outerCommands = DirectionalCommands(left = repeat)))
        )
        controls.forEach { control ->
            val saved = profile(control)
            assertEquals(15, saved.requiredFormatVersion())
            val encoded = encodeControllerProfile(saved)
            val forged = JsonObject(json.parseToJsonElement(encoded).jsonObject + ("formatVersion" to JsonPrimitive(14)))
            assertTrue(runCatching { decodeControllerProfile(forged.toString(), "Old") }
                .exceptionOrNull()!!.message.orEmpty().contains("format 15"))
        }
        val bank = RingBoostBanks(aim = RingBoostBankSettings(boost2 = RingBoostSettings(
            distanceDp = 160f, directInnerDistanceDp = 160f, directOuterDistanceDp = 184f, payload = repeat)))
        val floating = profile().copy(pages = listOf(ControllerPage("base", "Base",
            floatingZones = listOf(zone(FloatingRingConfig(boostBanks = bank))))))
        assertEquals(15, floating.requiredFormatVersion())
        assertEquals(floating.pages, decodeControllerProfile(encodeControllerProfile(floating), "Ring").profile.pages)
    }

    @Test fun underVersionedRepeat_rejectedBeforeUnknownTypedControlsAndMalformedRepeatParsing() {
        for (repeat in listOf("RT:1.0(R100)", "RT:1.0(rBAD)")) {
            val raw = """{"formatVersion":14,"pages":[{"controls":[{"type":"UNKNOWN_FUTURE_CONTROL","payload":"$repeat"}]}]}"""
            val failure = runCatching { decodeControllerProfile(raw, "Old") }.exceptionOrNull()
            assertTrue(failure!!.message.orEmpty().contains("format 15"))
        }
        assertTrue(runCatching { decodeControllerProfile("""[{"payload":"RT:1.0(R100)"}]""", "Legacy") }.isFailure)
    }

    @Test fun reusableCaptures_selectNewSchemasOnlyForRepeatAndApplyTheExactPayload() {
        val repeat = "RT:1.0(R100)"
        val manual = requireNotNull(touchAim().copy(touchMediumPayload = repeat)
            .captureManualTouchAimProfile(ID, "Manual", 1))
        assertEquals(3, manual.schemaVersion)
        assertNotNull(manual.copy(schemaVersion = 2).validationError())
        val manualTarget = touchAim()
        assertTrue(manual.applyTo(manualTarget))
        assertEquals(repeat, manualTarget.touchMediumPayload)
        val buttonSaved = requireNotNull(button().copy(buttonAimAlternatePayload = repeat)
            .captureButtonAimProfile(ID, "Button", 1))
        assertEquals(7, buttonSaved.schemaVersion)
        assertNotNull(buttonSaved.copy(schemaVersion = 6).validationError())
        val buttonTarget = button()
        assertTrue(buttonSaved.applyCompleteTo(buttonTarget))
        assertEquals(repeat, buttonTarget.buttonAimAlternatePayload)
        val directional = requireNotNull(stick().copy(directionalMode = true, upCommand = "W(R100)")
            .captureStickDirectionalProfile(ID, "Stick", 1))
        assertEquals(4, directional.schemaVersion)
        assertNotNull(directional.copy(schemaVersion = 3).validationError())
        val stickTarget = stick()
        assertTrue(directional.applyCommandsTo(stickTarget))
        assertEquals("W(R100)", stickTarget.upCommand)
    }

    @Test fun existingModifierAndTuningReusableSchemas_remainUnchanged() {
        assertEquals(2, touchAim().copy(touchMediumPayload = "LT:1.0(HRD200)")
            .captureManualTouchAimProfile(ID, "Manual", 1)!!.schemaVersion)
        assertEquals(2, touchAim().copy(touchMediumSensitivity = .123f)
            .captureManualTouchAimProfile(ID, "Tuning", 1)!!.schemaVersion)
        assertEquals(6, button().copy(payload = "X360A(I)").captureButtonAimProfile(ID, "Button", 1)!!.schemaVersion)
        assertEquals(3, stick().copy(directionalMode = true, upCommand = "W(HRD200)")
            .captureStickDirectionalProfile(ID, "Stick", 1)!!.schemaVersion)
        val ring = FloatingRingConfig(boost1 = RingBoostSettings(payload = "X360A(I)"))
        assertEquals(4, ring.captureBoostProfile(ID, "Boost", 1)!!.schemaVersion)
        assertEquals(5, zone(ring).captureRingPreset(ID, "Ring", 1)!!.schemaVersion)
        assertEquals(2, calibration("RT:1.0(I)").requiredSchemaVersion())
    }

    @Test fun floatingCaptures_detectRepeatInInactiveBoostsAndOptionalKnobSnapshots() {
        val repeat = "RT:1.0(R100)"
        val ring = FloatingRingConfig(knobs = listOf(RingAimKnob(enabled = true,
            fire = RingFireSettings(mode = RingFireMode.NONE, firePayload = repeat))))
        val boost = ring.captureBoostProfile(ID, "Boost", 1, includeRingAimActions = true)!!
        assertEquals(5, boost.schemaVersion)
        assertNotNull(boost.copy(schemaVersion = 4).validationError())
        val preset = zone(ring).captureRingPreset(ID, "Ring", 1)!!
        assertEquals(6, preset.schemaVersion)
        assertNotNull(preset.copy(schemaVersion = 5).validationError())
        assertEquals(preset, decodeFloatingRingPreset(encodeFloatingRingPreset(preset)))
        val oldPreset = JsonObject(json.parseToJsonElement(encodeFloatingRingPreset(preset)).jsonObject +
            ("schemaVersion" to JsonPrimitive(5)))
        assertTrue(runCatching { decodeFloatingRingPreset(oldPreset.toString()) }.exceptionOrNull()!!.message.orEmpty().contains("version 6"))
    }

    @Test fun allLibrariesBackup_roundTripsRepeatAndRejectsEveryUnderVersionedNestedLibraryBeforeDecode() {
        val repeat = "RT:1.0(R100)"
        val ring = FloatingRingConfig(boost1 = RingBoostSettings(payload = repeat))
        val calibration = calibration(repeat).let { it.copy(schemaVersion = it.requiredSchemaVersion()) }
        assertEquals(3, calibration.schemaVersion)
        assertNotNull(calibration.copy(schemaVersion = 2).versionValidationError())
        val original = backup().copy(
            touchAimCalibrations = listOf(calibration),
            touchAimManualProfiles = listOf(touchAim().copy(touchMediumPayload = repeat).captureManualTouchAimProfile(ID, "Manual", 1)!!),
            buttonAimProfiles = listOf(button().copy(payload = repeat).captureButtonAimProfile(ID, "Button", 1)!!),
            stickDirectionalProfiles = listOf(stick().copy(directionalMode = true, upCommand = "W(R100)")
                .captureStickDirectionalProfile(ID, "Stick", 1)!!),
            floatingBoostProfiles = listOf(ring.captureBoostProfile(ID, "Boost", 1)!!),
            floatingRingPresets = listOf(zone(ring).captureRingPreset(ID, "Ring", 1)!!)
        )
        val restored = decodeControllerProfilesBackup(encodeControllerProfilesBackup(original)).backup
        assertEquals(original.copy(backupVersion = 3), restored)
        val libraries = listOf(
            Triple("touchAimCalibrations", 2, """"shootPayload":"$repeat""""),
            Triple("touchAimManualProfiles", 2, """"threeStage":{"mediumPayload":"$repeat"}"""),
            Triple("buttonAimProfiles", 6, """"actions":{"alternatePayload":"$repeat"}"""),
            Triple("stickDirectionalProfiles", 3, """"settings":{"extendedBoost":{"outerCommands":{"up":"$repeat"}}}"""),
            Triple("floatingBoostProfiles", 4, """"ringAimActions":{"knobActions":[{"firePayload":"$repeat"}]}"""),
            Triple("floatingRingPresets", 5, """"zone":{"ring":{"boostBanks":{"movement":{"boost2":{"payload":"$repeat"}}}}}""")
        )
        libraries.forEach { (key, oldVersion, fields) ->
            val raw = """{"fileType":"simplecontroller-profiles-backup","backupVersion":3,
                "controllerProfiles":[],"$key":[{"schemaVersion":$oldVersion,"futureMode":"FUTURE",$fields}]}"""
            val failure = runCatching { decodeControllerProfilesBackup(raw) }.exceptionOrNull()
            assertTrue("$key: ${failure?.message}", failure!!.message.orEmpty().contains("version ${oldVersion + 1}"))
        }
    }

    private fun profile(control: Control? = null) = ControllerProfile(homePageId = "base",
        pages = listOf(ControllerPage("base", "Base", listOfNotNull(control))))
    private fun backup() = ControllerProfilesBackup(activeControllerProfileName = "Controller",
        controllerProfiles = listOf(NamedControllerProfile("Controller", profile())))
    private fun button() = Control("control", ControlType.BUTTON, 0f, 0f, 200f, 200f, "X360A")
    private fun stick() = button().copy(type = ControlType.STICK, payload = "STICK")
    private fun touchAim() = button().copy(type = ControlType.TOUCH_AIM, payload = "TOUCH_AIM")
    private fun zone(ring: FloatingRingConfig) = FloatingZone("zone", kind = FloatingZoneKind.RING,
        ring = ring, enabled = false, portraitGeometry = FloatingZoneGeometry(1000f, 1000f))
    private fun calibration(payload: String) = TouchAimCalibrationProfile(
        id = ID, name = "Calibration", createdAtEpochMs = 1, updatedAtEpochMs = 1,
        sensors = emptyList(), smoothing = .25f, shootOnThreshold = 1f, shootOffThreshold = .5f,
        enterShootConfirmationMs = 64, returnToAimConfirmationMs = 64,
        quality = TouchAimCalibrationQuality.UNRELIABLE, estimatedFalseActivationRate = 0f,
        estimatedMissedActivationRate = 0f, movementReducedReliability = false, consistencyScore = 0f,
        passSummaries = emptyList(), aimOutput = TouchAimOutput.MOUSE, aimPayload = "", shootPayload = payload,
        keepAimPayloadWhileShooting = false, shootBehavior = TouchAimShootBehavior.HOLD_WHILE_ABOVE)
    private companion object { const val ID = "00000000-0000-0000-0000-000000000001" }
}
