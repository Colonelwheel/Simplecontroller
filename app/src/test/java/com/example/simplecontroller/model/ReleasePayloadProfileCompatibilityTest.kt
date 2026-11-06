package com.example.simplecontroller.model

import com.example.simplecontroller.io.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ReleasePayloadProfileCompatibilityTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun timedReleaseAndMacroMode_roundTripThroughProfileTransferBackupAndDuplicate() {
        val control = button().copy(macroModeEnabled = true,
            payload = "X360A(U100),X360A(D500)(U100),X360B(D800)(U100)")
        val source = profile(control)
        assertEquals(17, source.requiredFormatVersion())
        val saved = decodeControllerProfile(encodeControllerProfile(source), "Macro").profile
        assertEquals(17, saved.formatVersion)
        assertEquals(source.pages, saved.pages)
        assertEquals(source.pages, decodeControllerProfileTransfer(
            encodeControllerProfileTransfer("Macro", source), "Fallback").result.profile.pages)
        assertEquals(control, deepCopyControls(listOf(control)).single())
        val duplicate = deepCopyControls(listOf(control), regenerateIds = true).single()
        assertNotEquals(control.id, duplicate.id)
        assertEquals(control.copy(id = duplicate.id), duplicate)
        val backup = backup().copy(activeControllerProfileName = "Macro",
            controllerProfiles = listOf(NamedControllerProfile("Macro", source)))
        val restored = decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup)).backup
        assertEquals(source.pages, restored.controllerProfiles.single().profile.pages)
    }

    @Test fun legacyControlDefaultsMacroOff_andPreviousConditionalVersionsStayUnchanged() {
        val oldControl = json.encodeToString(button()).replace("\"macroModeEnabled\":false,", "")
        assertFalse(json.decodeFromString<Control>(oldControl).macroModeEnabled)
        assertEquals(17, profile(button().copy(macroModeEnabled = true)).requiredFormatVersion())
        assertEquals(17, profile(button().copy(payload = "X360A(U100)")).requiredFormatVersion())
        assertEquals(16, profile(button().copy(payload = "X360A(D0)")).requiredFormatVersion())
        assertEquals(15, profile(button().copy(payload = "X360A(R100)")).requiredFormatVersion())
        assertEquals(14, profile(button().copy(payload = "X360A(I)")).requiredFormatVersion())
        assertEquals(5, profile(button()).requiredFormatVersion())
    }

    @Test fun inactiveReleaseCommandsAndMacroFlag_selectFormatSeventeenBeforeDecoding() {
        val release = "X360A(U100)"
        val controls = listOf(
            button().copy(buttonAimAlternatePayload = release),
            button().copy(buttonDirectional = ButtonDirectionalSettings(right = ButtonDirectionStages(secondPayload = release))),
            touchAim().copy(touchLowPayload = release),
            touchAim().copy(touchMediumPayload = release),
            touchAim().copy(touchHighPayload = release),
            touchAim().copy(touchAimAimPayload = release),
            touchAim().copy(touchAimShootPayload = release),
            stick().copy(thresholdPayload = release),
            stick().copy(superThresholdPayload = release),
            stick().copy(upCommand = release),
            stick().copy(rightSuperBoostCommand = release),
            stick().copy(extendedBoost = ExtendedBoostSettings(outerCommands = DirectionalCommands(left = release))),
            stick().copy(macroModeEnabled = true)
        )
        controls.forEach { control ->
            assertEquals(17, profile(control).requiredFormatVersion())
            val raw = JsonObject(json.parseToJsonElement(encodeControllerProfile(profile(control))).jsonObject +
                ("formatVersion" to JsonPrimitive(16)))
            assertTrue(runCatching { decodeControllerProfile(raw.toString(), "Old") }
                .exceptionOrNull()!!.message.orEmpty().contains("format 17"))
        }
        val bank = RingBoostBanks(aim = RingBoostBankSettings(boost2 = RingBoostSettings(
            distanceDp = 160f, directInnerDistanceDp = 160f, directOuterDistanceDp = 184f, payload = release)))
        val floating = profile().copy(pages = listOf(ControllerPage("base", "Base",
            floatingZones = listOf(zone(FloatingRingConfig(boostBanks = bank))))))
        assertEquals(17, floating.requiredFormatVersion())
        assertEquals(floating.pages, decodeControllerProfile(encodeControllerProfile(floating), "Ring").profile.pages)
    }

    @Test fun underVersionedMacroAndAttemptedRelease_rejectBeforeUnknownControlDecoding() {
        for (fields in listOf("\"macroModeEnabled\":true", "\"payload\":\"X360A(U100)\"",
                "\"payload\":\"X360A(uBAD)\"", "\"payload\":\"X360A(U)\"")) {
            val raw = """{"formatVersion":16,"pages":[{"controls":[{"type":"FUTURE_CONTROL",$fields}]}]}"""
            assertTrue(runCatching { decodeControllerProfile(raw, "Old") }
                .exceptionOrNull()!!.message.orEmpty().contains("format 17"))
            assertTrue(runCatching { decodeControllerProfile("""[{$fields}]""", "Array") }.isFailure)
        }
        assertTrue(runCatching { encodeControllerProfile(profile(button().copy(payload = "X360A(Ubad)"))) }.isFailure)
    }

    @Test fun reusableSchemas_requireReleaseReadersWithoutChangingDelayOrRepeat() {
        fun versions(payload: String): List<Int> {
            val ring = FloatingRingConfig(boost1 = RingBoostSettings(payload = payload))
            return listOf(
                touchAim().copy(touchMediumPayload = payload).captureManualTouchAimProfile(ID, "Manual", 1)!!.schemaVersion,
                calibration(payload).requiredSchemaVersion(),
                button().copy(buttonAimAlternatePayload = payload).captureButtonAimProfile(ID, "Button", 1)!!.schemaVersion,
                stick().copy(directionalMode = true, upCommand = payload).captureStickDirectionalProfile(ID, "Stick", 1)!!.schemaVersion,
                ring.captureBoostProfile(ID, "Boost", 1)!!.schemaVersion,
                zone(ring).captureRingPreset(ID, "Ring", 1)!!.schemaVersion
            )
        }
        assertEquals(listOf(5, 5, 9, 6, 7, 8), versions("X360A(U100)"))
        assertEquals(listOf(5, 5, 9, 6, 7, 8), versions("X360A(D200)(U30)(R100)"))
        assertEquals(listOf(4, 4, 8, 5, 6, 7), versions("X360A(D0)"))
        assertEquals(listOf(3, 3, 7, 4, 5, 6), versions("X360A(R100)"))
        assertEquals(listOf(1, 1, 3, 2, 1, 1), versions("X360A"))
    }

    @Test fun reusableApplication_rejectsOldReleaseSchemasWithoutChangingTarget() {
        val release = "X360A(U100)"
        val manual = touchAim().copy(touchMediumPayload = release).captureManualTouchAimProfile(ID, "Manual", 1)!!
        val manualTarget = touchAim()
        assertFalse(manual.copy(schemaVersion = 4).applyTo(manualTarget))
        assertEquals(touchAim(), manualTarget)
        assertTrue(manual.applyTo(manualTarget))
        assertEquals(release, manualTarget.touchMediumPayload)
        val savedButton = button().copy(buttonAimAlternatePayload = release).captureButtonAimProfile(ID, "Button", 1)!!
        val buttonTarget = button()
        assertFalse(savedButton.copy(schemaVersion = 8).applyCompleteTo(buttonTarget))
        assertEquals(button(), buttonTarget)
        val directional = stick().copy(directionalMode = true, upCommand = release).captureStickDirectionalProfile(ID, "Stick", 1)!!
        val stickTarget = stick()
        assertFalse(directional.copy(schemaVersion = 5).applyCommandsTo(stickTarget))
        assertEquals(stick(), stickTarget)
        assertNotNull(calibration(release).copy(schemaVersion = 4).versionValidationError())
    }

    @Test fun enablingButtonAimViaReusableProfile_disablesConflictingMacroMode() {
        val source = button().captureButtonAimProfile(ID, "Button", 1)!!
        val aimOnlyTarget = button().copy(macroModeEnabled = true)
        assertTrue(source.applyAimTuningTo(aimOnlyTarget))
        assertFalse(aimOnlyTarget.macroModeEnabled)
        assertTrue(aimOnlyTarget.buttonAimEnabled)
        val completeTarget = button().copy(macroModeEnabled = true)
        assertTrue(source.applyCompleteTo(completeTarget))
        assertFalse(completeTarget.macroModeEnabled)
        assertTrue(completeTarget.buttonAimEnabled)
        assertEquals(3, source.schemaVersion)
    }

    @Test fun allReusableLibraries_roundTripReleasePayloadsAndRejectUnderVersionedRawShapes() {
        val release = "X360A(U100)"
        val ring = FloatingRingConfig(boost1 = RingBoostSettings(payload = release))
        val calibration = calibration(release).let { it.copy(schemaVersion = it.requiredSchemaVersion()) }
        val original = backup().copy(
            touchAimCalibrations = listOf(calibration),
            touchAimManualProfiles = listOf(touchAim().copy(touchMediumPayload = release).captureManualTouchAimProfile(ID, "Manual", 1)!!),
            buttonAimProfiles = listOf(button().copy(payload = release).captureButtonAimProfile(ID, "Button", 1)!!),
            stickDirectionalProfiles = listOf(stick().copy(directionalMode = true, upCommand = release).captureStickDirectionalProfile(ID, "Stick", 1)!!),
            floatingBoostProfiles = listOf(ring.captureBoostProfile(ID, "Boost", 1)!!),
            floatingRingPresets = listOf(zone(ring).captureRingPreset(ID, "Ring", 1)!!)
        )
        assertEquals(original.copy(backupVersion = 3), decodeControllerProfilesBackup(encodeControllerProfilesBackup(original)).backup)
        assertEquals(original.floatingRingPresets.single(), decodeFloatingRingPreset(encodeFloatingRingPreset(original.floatingRingPresets.single())))
        val shapes = listOf(
            Triple("touchAimCalibrations", 4, """"shootPayload":"X360A(Ubad)""""),
            Triple("touchAimManualProfiles", 4, """"threeStage":{"mediumPayload":"$release"}"""),
            Triple("buttonAimProfiles", 8, """"actions":{"alternatePayload":"$release"}"""),
            Triple("stickDirectionalProfiles", 5, """"settings":{"extendedBoost":{"outerCommands":{"up":"$release"}}}"""),
            Triple("floatingBoostProfiles", 6, """"ringAimActions":{"knobActions":[{"firePayload":"$release"}]}"""),
            Triple("floatingRingPresets", 7, """"zone":{"ring":{"boostBanks":{"movement":{"boost2":{"payload":"$release"}}}}}""")
        )
        shapes.forEach { (key, oldVersion, fields) ->
            val raw = """{"fileType":"simplecontroller-profiles-backup","backupVersion":3,
                "controllerProfiles":[],"$key":[{"schemaVersion":$oldVersion,"futureMode":"FUTURE",$fields}]}"""
            val error = runCatching { decodeControllerProfilesBackup(raw) }.exceptionOrNull()
            assertTrue("$key: ${error?.message}", error!!.message.orEmpty().contains("version ${oldVersion + 1}"))
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
