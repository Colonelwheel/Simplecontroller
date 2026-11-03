package com.example.simplecontroller.model

import com.example.simplecontroller.io.ControllerProfilesBackup
import com.example.simplecontroller.io.NamedControllerProfile
import com.example.simplecontroller.io.decodeControllerProfilesBackup
import com.example.simplecontroller.io.decodeFloatingRingPreset
import com.example.simplecontroller.io.encodeControllerProfilesBackup
import com.example.simplecontroller.io.encodeFloatingRingPreset
import org.junit.Assert.*
import org.junit.Test

class FloatingRingPresetTest {
    @Test fun completeSnapshot_roundTripsBothOrientationsActionsAndDisabledSettings() {
        val source = fullZone()
        val preset = requireNotNull(source.captureRingPreset(PRESET_ID, "  Tested combat  ", 12L))
        val restored = decodeFloatingRingPreset(encodeFloatingRingPreset(preset))

        assertEquals("Tested combat", restored.name)
        assertEquals(preset, restored)
        assertEquals(source, restored.zone)
        assertFalse(restored.zone.enabled)
        assertFalse(restored.zone.ring!!.aim.enabled)
        assertFalse(restored.zone.ring!!.boost2.enabled)
        assertEquals(RingAction.REPEAT, restored.zone.ring!!.boost2.action)
        assertEquals("secondary", restored.zone.ring!!.directBoostAimKnobId)
        assertTrue(restored.zone.ring!!.directBoostTwoToOne)
        assertEquals(300L, restored.zone.ring!!.knobs.first().fire.returnFire.delayMs)
        assertEquals(2000f, restored.zone.landscapeGeometry!!.canvasWidth, 0f)
        assertEquals(321.5f, restored.zone.portraitGeometry!!.zone.x, 0f)
    }

    @Test fun captureAndRepeatedInstantiation_detachNestedMutableCollections() {
        val qualifiers = mutableSetOf(RingBoostStage.ONE)
        val knobs = fullZone().ring!!.knobs.toMutableList()
        val ring = fullZone().ring!!.let { it.copy(knobs = knobs, aim = it.aim.copy(
            fine = it.aim.fine.copy(qualifyingStages = qualifiers)
        )) }
        val source = fullZone().copy(ring = ring)
        val preset = requireNotNull(source.captureRingPreset(PRESET_ID, "Snapshot", 1L))
        qualifiers.add(RingBoostStage.TWO)
        knobs.clear()

        assertEquals(setOf(RingBoostStage.ONE), preset.zone.ring!!.aim.fine.qualifyingStages)
        assertEquals(2, preset.zone.ring!!.knobs.size)
        val first = requireNotNull(preset.instantiate("first"))
        val second = requireNotNull(preset.instantiate("second"))
        assertNotSame(preset.zone.ring, first.ring)
        assertNotSame(first.ring!!.knobs, second.ring!!.knobs)
        assertNotSame(first.ring.aim.fine.qualifyingStages, second.ring.aim.fine.qualifyingStages)
        assertEquals(preset.zone.ring, first.ring)
        assertEquals(preset.zone.ring, second.ring)
    }

    @Test fun instantiate_addsIndependentIdentityAndPreservesOrExplicitlyChangesZoneName() {
        val preset = requireNotNull(fullZone().captureRingPreset(PRESET_ID, "Library name", 1L))
        val first = requireNotNull(preset.instantiate())
        val second = requireNotNull(preset.instantiate())
        assertNotEquals(preset.zone.id, first.id)
        assertNotEquals(first.id, second.id)
        assertEquals(preset.zone.name, first.name)
        assertEquals("New controller ring", preset.instantiate("another", "New controller ring")!!.name)
        assertEquals(preset.zone.copy(id = first.id), first)
        assertNull(preset.instantiate(preset.zone.id))
        assertNull(preset.instantiate(" "))
    }

    @Test fun ringOnlyCapture_rejectsGroupsAndEveryStoredMemberField() {
        val zone = fullZone()
        val memberGeometry = mapOf("button" to ControlGeometry(1f, 2f, 30f, 40f))
        val invalid = listOf(
            zone.copy(kind = FloatingZoneKind.BUTTON_GROUP),
            zone.copy(kind = FloatingZoneKind.SINGLE_BUTTON),
            zone.copy(ring = null),
            zone.copy(memberIds = listOf("button")),
            zone.copy(startMemberId = "button"),
            zone.copy(groupSwipe = true),
            zone.copy(portraitMemberGeometry = memberGeometry),
            zone.copy(landscapeMemberGeometry = memberGeometry),
            zone.copy(portraitRestorationGeometry = memberGeometry),
            zone.copy(landscapeRestorationGeometry = memberGeometry),
            zone.copy(portraitGeometry = null, landscapeGeometry = null)
        )
        invalid.forEach { source ->
            assertNull(source.captureRingPreset(PRESET_ID, "Invalid", 1L))
            val forged = FloatingRingPreset(id = PRESET_ID, name = "Invalid",
                createdAtEpochMs = 1L, updatedAtEpochMs = 1L, zone = source)
            assertNotNull(forged.validationError())
            assertNull(forged.instantiate("new"))
        }
    }

    @Test fun futurePresetSchema_isRejectedBeforeAnUnknownRingShape() {
        val future = """{"schemaVersion":999,"zone":{"kind":"FUTURE_RING"}}"""
        assertTrue(runCatching { decodeFloatingRingPreset(future) }.exceptionOrNull()!!.message!!.contains("newer"))
        assertTrue(runCatching { decodeFloatingRingPreset("""{"zone":{}}""") }.isFailure)
        assertNull(fullZone().captureRingPreset("invalid-id", "Name", 1L))
        assertNull(fullZone().captureRingPreset(PRESET_ID, " ", 1L))
    }

    @Test fun serializedSnapshot_containsSavedConfigurationWithoutRuntimeSessionState() {
        val preset = requireNotNull(fullZone().captureRingPreset(PRESET_ID, "Saved", 1L))
        val text = encodeFloatingRingPreset(preset)
        listOf("pointerId", "gestureGeneration", "outputRoot", "armed", "movementRetained", "currentStatus")
            .forEach { assertFalse(text.contains("\"$it\"")) }
        assertTrue(text.contains("\"portraitGeometry\""))
        assertTrue(text.contains("\"landscapeGeometry\""))
        assertTrue(text.contains("\"suppressCameraFollow\""))
    }

    @Test fun fullRingLibrary_requiresBackupThreeAndPreservesLegacyOneAndBoostOnlyTwo() {
        val preset = requireNotNull(fullZone().captureRingPreset(PRESET_ID, "Ring", 1L))
        val legacy = ControllerProfilesBackup(activeControllerProfileName = "Controller",
            controllerProfiles = listOf(NamedControllerProfile("Controller", ControllerProfile(
                homePageId = "base", pages = listOf(ControllerPage("base", "Base"))
            ))))
        val withRing = legacy.copy(floatingRingPresets = listOf(preset))
        val restored = decodeControllerProfilesBackup(encodeControllerProfilesBackup(withRing)).backup
        assertEquals(3, restored.backupVersion)
        assertEquals(listOf(preset), restored.floatingRingPresets)
        assertEquals(5, restored.controllerProfiles.single().profile.formatVersion)
        assertEquals(1, decodeControllerProfilesBackup(encodeControllerProfilesBackup(legacy)).backup.backupVersion)
        val boost = requireNotNull(FloatingRingConfig().captureBoostProfile(PRESET_ID, "Boost", 1L))
        assertEquals(2, decodeControllerProfilesBackup(encodeControllerProfilesBackup(
            legacy.copy(floatingBoostProfiles = listOf(boost)))).backup.backupVersion)
    }

    @Test fun futureOrUnderVersionedBackupLibrary_isRejectedBeforeUnknownNestedShapes() {
        val future = """{"fileType":"simplecontroller-profiles-backup","backupVersion":3,
            "activeControllerProfileName":"Old","controllerProfiles":[],
            "floatingRingPresets":[{"schemaVersion":999,"zone":{"kind":"FUTURE_RING"}}]}"""
        assertTrue(runCatching { decodeControllerProfilesBackup(future) }.exceptionOrNull()!!.message!!.contains("newer"))
        listOf(1, 2).forEach { version ->
            val older = future.replace("\"backupVersion\":3", "\"backupVersion\":$version")
            assertTrue(runCatching { decodeControllerProfilesBackup(older) }
                .exceptionOrNull()!!.message!!.contains("backup version 3"))
        }
    }

    @Test fun existingBackupTwo_withoutNewLibraryRemainsReadable() {
        val previous = """{"fileType":"simplecontroller-profiles-backup","backupVersion":2,
            "activeControllerProfileName":"Previous","controllerProfiles":[{"name":"Previous",
            "profile":{"formatVersion":5,"homePageId":"base","pages":[{"id":"base","name":"Base"}]}}],
            "floatingBoostProfiles":[]}"""
        val restored = decodeControllerProfilesBackup(previous).backup
        assertEquals(2, restored.backupVersion)
        assertTrue(restored.floatingRingPresets.isEmpty())
        assertEquals("Previous", restored.activeControllerProfileName)
    }

    @Test fun backupRejectsDuplicatePresetIdentitiesAndNamesWithoutWriting() {
        val first = requireNotNull(fullZone().captureRingPreset(PRESET_ID, "Combat", 1L))
        val backup = ControllerProfilesBackup(activeControllerProfileName = "Controller",
            controllerProfiles = listOf(NamedControllerProfile("Controller", ControllerProfile(
                homePageId = "base", pages = listOf(ControllerPage("base", "Base"))
            ))))
        assertTrue(runCatching { encodeControllerProfilesBackup(backup.copy(
            floatingRingPresets = listOf(first, first.copy(name = "Other")))) }.isFailure)
        assertTrue(runCatching { encodeControllerProfilesBackup(backup.copy(
            floatingRingPresets = listOf(first, first.copy(id = "00000000-0000-0000-0000-000000000012", name = " combat ")))) }.isFailure)
    }

    private fun fullZone() = FloatingZone(
        id = "tested-ring", name = "My combat ring", kind = FloatingZoneKind.RING, enabled = false,
        portraitGeometry = FloatingZoneGeometry(1000f, 2000f, ControlGeometry(321.5f, 222.25f, 244.5f, 333.75f)),
        landscapeGeometry = FloatingZoneGeometry(2000f, 1000f, ControlGeometry(550f, 110f, 400f, 220f)),
        ring = FloatingRingConfig(
            innerRadiusDp = 52f, movementRadiusDp = 94f,
            movement = RingMovementSettings(sensitivity = 1.3f, profile = ButtonAimStickProfile.RADIAL_RESPONSE_CURVE,
                autoCenter = false, excludeFromCameraFollow = true, initialX = 0.2f, initialY = -0.4f,
                participateInBoosts = false),
            aim = RingAimSettings(enabled = false,
                tuning = defaultRingAimTuning().copy(output = ButtonAimOutput.GAME_MOUSE, sensitivity = 1.7f,
                    invertY = true, autoCenter = false), continueOutside = false, participateInBoosts = true,
                suppressCameraFollow = true, fine = RingFineAimSettings(enabled = true, sensitivity = 0.35f,
                    qualifyingStages = setOf(RingBoostStage.ONE, RingBoostStage.TWO), persistWhileAdsLatched = false)),
            knobs = listOf(
                RingAimKnob(id = "primary", name = "Shoot", x = -0.3f, targetDiameterDp = 24f,
                    fire = RingFireSettings(mode = RingFireMode.RELEASE_SHOT, adsPayload = "LT:1",
                        pulsePressMs = 44L, pulseGapMs = 83L, retainAdsOnLift = true,
                        continueAdsOutside = false, continueFireOutside = false,
                        cancelReleaseShotOnBoundaryExit = true, rearmReleaseShotOnBoundaryReturn = true,
                        returnFire = RingReturnFireSettings(enabled = true, delayMs = 300L,
                            qualifyingStages = setOf(RingBoostStage.TWO), cancelOnExit = false))),
                RingAimKnob(id = "secondary", name = "Other", enabled = false, x = 0.3f, targetDiameterDp = 24f,
                    fire = RingFireSettings(mode = RingFireMode.REPEAT, firePayload = "X360B", retainFireOnLift = true))
            ),
            boost1 = RingBoostSettings(enabled = true, payload = "LT:1", latchOnExit = true, retainOnLift = true,
                directionalPayloads = DirectionalCommands(up = "X360Y"), hysteresisDp = 4f),
            boost2 = RingBoostSettings(enabled = false, distanceDp = 190f, directInnerDistanceDp = 190f,
                directOuterDistanceDp = 215f, action = RingAction.REPEAT, payload = "RT:1", repeatAfterExit = true,
                retainOnLift = true, activationDelayMs = 275L, cancelDelayOnExit = false,
                directTapEnabled = true, pulsePressMs = 37L, pulseGapMs = 73L),
            startRole = FloatingStartRole.MOVEMENT, startKnobId = "primary",
            directBoostToAim = false, directBoostAimKnobId = "secondary", directBoostTwoToOne = true
        )
    )

    companion object { private const val PRESET_ID = "00000000-0000-0000-0000-000000000011" }
}
