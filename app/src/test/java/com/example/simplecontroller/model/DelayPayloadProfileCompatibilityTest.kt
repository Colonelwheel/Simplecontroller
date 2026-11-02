package com.example.simplecontroller.model

import com.example.simplecontroller.io.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class DelayPayloadProfileCompatibilityTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun delayIncludingZero_selectsFormatSixteenAndRoundTripsWithoutLosingPayloads() {
        for (payload in listOf("X360A(D0)", "LT:1.0(I)(HRD200),RT:1.0(D250)(R100)")) {
            val original = profile(button().copy(payload = payload))
            assertEquals(16, original.requiredFormatVersion())
            val restored = decodeControllerProfile(encodeControllerProfile(original), "Delay").profile
            assertEquals(16, restored.formatVersion)
            assertEquals(original.pages, restored.pages)
            assertEquals(original.pages, decodeControllerProfileTransfer(
                encodeControllerProfileTransfer("Delay", original), "Fallback").result.profile.pages)
            assertEquals(original.pages.single().controls, deepCopyControls(original.pages.single().controls))
        }
        assertEquals(15, profile(button().copy(payload = "RT:1.0(R100)")).requiredFormatVersion())
        assertEquals(14, profile(button().copy(payload = "RT:1.0(I)(HRD200)")).requiredFormatVersion())
        assertEquals(14, profile(touchAim().copy(touchMediumSensitivity = .123f)).requiredFormatVersion())
        assertEquals(5, profile(button()).requiredFormatVersion())
    }

    @Test fun inactiveDelayCommands_requireNewReaderAcrossControlAndFloatingActionLocations() {
        val delayed = "X360A(D0)"
        val controls = listOf(
            button().copy(buttonAimAlternatePayload = delayed),
            button().copy(buttonDirectional = ButtonDirectionalSettings(right = ButtonDirectionStages(secondPayload = delayed))),
            touchAim().copy(touchLowPayload = delayed),
            touchAim().copy(touchMediumPayload = delayed),
            touchAim().copy(touchHighPayload = delayed),
            touchAim().copy(touchAimAimPayload = delayed),
            touchAim().copy(touchAimShootPayload = delayed),
            stick().copy(thresholdPayload = delayed),
            stick().copy(superThresholdPayload = delayed),
            stick().copy(upCommand = delayed),
            stick().copy(rightSuperBoostCommand = delayed),
            stick().copy(extendedBoost = ExtendedBoostSettings(outerCommands = DirectionalCommands(left = delayed)))
        )
        controls.forEach { control ->
            assertEquals(16, profile(control).requiredFormatVersion())
            val encoded = encodeControllerProfile(profile(control))
            val forged = JsonObject(json.parseToJsonElement(encoded).jsonObject + ("formatVersion" to JsonPrimitive(15)))
            assertTrue(runCatching { decodeControllerProfile(forged.toString(), "Old") }
                .exceptionOrNull()!!.message.orEmpty().contains("format 16"))
        }
        val banks = RingBoostBanks(aim = RingBoostBankSettings(boost2 = RingBoostSettings(
            distanceDp = 160f, directInnerDistanceDp = 160f, directOuterDistanceDp = 184f, payload = delayed)))
        val floating = profile().copy(pages = listOf(ControllerPage("base", "Base",
            floatingZones = listOf(zone(FloatingRingConfig(boostBanks = banks))))))
        assertEquals(16, floating.requiredFormatVersion())
        assertEquals(floating.pages, decodeControllerProfile(encodeControllerProfile(floating), "Ring").profile.pages)
    }

    @Test fun attemptedDelay_isRejectedBeforeUnknownTypedControlsEvenWhenMalformed() {
        for (payload in listOf("RT:1.0(D0)", "RT:1.0(dBAD)", "X360A(D)")) {
            val raw = """{"formatVersion":15,"pages":[{"controls":[{"type":"FUTURE_CONTROL","payload":"$payload"}]}]}"""
            assertTrue(runCatching { decodeControllerProfile(raw, "Old") }
                .exceptionOrNull()!!.message.orEmpty().contains("format 16"))
            assertEquals(16, profile(button().copy(payload = payload)).requiredFormatVersion())
        }
        assertTrue(runCatching { decodeControllerProfile("""[{"payload":"X360A(D0)"}]""", "Legacy") }.isFailure)
        assertTrue(runCatching { encodeControllerProfile(profile(button().copy(payload = "X360A(Dbad)"))) }.isFailure)
    }

    @Test fun reusableSchemas_areConditionalForDelayRepeatAndEarlierModifiers() {
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
        assertEquals(listOf(4, 4, 8, 5, 6, 7), versions("X360A(D0)"))
        assertEquals(listOf(4, 4, 8, 5, 6, 7), versions("X360A(D250)(R100)"))
        assertEquals(listOf(3, 3, 7, 4, 5, 6), versions("X360A(R100)"))
        assertEquals(listOf(2, 2, 6, 3, 4, 5), versions("X360A(I)"))
        assertEquals(listOf(1, 1, 3, 2, 1, 1), versions("X360A"))
    }

    @Test fun underVersionedReusableProfiles_rejectApplicationAtomically() {
        val payload = "X360A(D0)"
        val manual = touchAim().copy(touchMediumPayload = payload).captureManualTouchAimProfile(ID, "Manual", 1)!!
        val manualTarget = touchAim()
        val manualBefore = manualTarget.copy()
        assertFalse(manual.copy(schemaVersion = 3).applyTo(manualTarget))
        assertEquals(manualBefore, manualTarget)
        assertTrue(manual.applyTo(manualTarget))
        assertEquals(payload, manualTarget.touchMediumPayload)
        val buttonSaved = button().copy(buttonAimAlternatePayload = payload).captureButtonAimProfile(ID, "Button", 1)!!
        val buttonTarget = button()
        val buttonBefore = buttonTarget.copy()
        assertFalse(buttonSaved.copy(schemaVersion = 7).applyCompleteTo(buttonTarget))
        assertEquals(buttonBefore, buttonTarget)
        val directional = stick().copy(directionalMode = true, upCommand = payload).captureStickDirectionalProfile(ID, "Stick", 1)!!
        val stickTarget = stick()
        val stickBefore = stickTarget.copy()
        assertFalse(directional.copy(schemaVersion = 4).applyCommandsTo(stickTarget))
        assertEquals(stickBefore, stickTarget)
        val calibration = calibration(payload).let { it.copy(schemaVersion = it.requiredSchemaVersion()) }
        assertNotNull(calibration.copy(schemaVersion = 3).versionValidationError())
        val ring = FloatingRingConfig(knobs = listOf(RingAimKnob(fire = RingFireSettings(firePayload = payload))))
        val boost = ring.captureBoostProfile(ID, "Boost", 1, includeRingAimActions = true)!!
        assertEquals(6, boost.schemaVersion)
        assertNotNull(boost.copy(schemaVersion = 5).validationError())
        val preset = zone(ring).captureRingPreset(ID, "Ring", 1)!!
        assertNotNull(preset.copy(schemaVersion = 6).validationError())
        assertEquals(preset, decodeFloatingRingPreset(encodeFloatingRingPreset(preset)))
    }

    @Test fun allLibraryBackup_roundTripsDelayAndRejectsEveryUnderVersionedNestedShape() {
        val payload = "X360A(D0)"
        val ring = FloatingRingConfig(boost1 = RingBoostSettings(payload = payload))
        val calibration = calibration(payload).let { it.copy(schemaVersion = it.requiredSchemaVersion()) }
        val original = backup().copy(
            touchAimCalibrations = listOf(calibration),
            touchAimManualProfiles = listOf(touchAim().copy(touchMediumPayload = payload).captureManualTouchAimProfile(ID, "Manual", 1)!!),
            buttonAimProfiles = listOf(button().copy(payload = payload).captureButtonAimProfile(ID, "Button", 1)!!),
            stickDirectionalProfiles = listOf(stick().copy(directionalMode = true, upCommand = payload).captureStickDirectionalProfile(ID, "Stick", 1)!!),
            floatingBoostProfiles = listOf(ring.captureBoostProfile(ID, "Boost", 1)!!),
            floatingRingPresets = listOf(zone(ring).captureRingPreset(ID, "Ring", 1)!!)
        )
        assertEquals(original.copy(backupVersion = 3), decodeControllerProfilesBackup(encodeControllerProfilesBackup(original)).backup)
        val shapes = listOf(
            Triple("touchAimCalibrations", 3, """"shootPayload":"X360A(Dbad)""""),
            Triple("touchAimManualProfiles", 3, """"threeStage":{"mediumPayload":"$payload"}"""),
            Triple("buttonAimProfiles", 7, """"actions":{"alternatePayload":"$payload"}"""),
            Triple("stickDirectionalProfiles", 4, """"settings":{"extendedBoost":{"outerCommands":{"up":"$payload"}}}"""),
            Triple("floatingBoostProfiles", 5, """"ringAimActions":{"knobActions":[{"firePayload":"$payload"}]}"""),
            Triple("floatingRingPresets", 6, """"zone":{"ring":{"boostBanks":{"movement":{"boost2":{"payload":"$payload"}}}}}""")
        )
        shapes.forEach { (key, oldVersion, fields) ->
            val raw = """{"fileType":"simplecontroller-profiles-backup","backupVersion":3,
                "controllerProfiles":[],"$key":[{"schemaVersion":$oldVersion,"futureMode":"FUTURE",$fields}]}"""
            val failure = runCatching { decodeControllerProfilesBackup(raw) }.exceptionOrNull()
            assertTrue("$key: ${failure?.message}", failure!!.message.orEmpty().contains("version ${oldVersion + 1}"))
        }
    }

    @Test fun futureFormatAndLibrarySchema_stillRejectBeforeUnknownEnumDecoding() {
        val controller = """{"formatVersion":17,"pages":[{"controls":[{"type":"FUTURE_CONTROL"}]}]}"""
        assertTrue(runCatching { decodeControllerProfile(controller, "Future") }
            .exceptionOrNull()!!.message.orEmpty().contains("newer"))
        val preset = """{"schemaVersion":8,"zone":{"kind":"FUTURE_RING"}}"""
        assertTrue(runCatching { decodeFloatingRingPreset(preset) }.exceptionOrNull()!!.message.orEmpty().contains("newer"))
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
