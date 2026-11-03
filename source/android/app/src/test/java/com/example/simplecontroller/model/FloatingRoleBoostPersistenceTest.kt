package com.example.simplecontroller.model

import com.example.simplecontroller.io.ControllerProfilesBackup
import com.example.simplecontroller.io.NamedControllerProfile
import com.example.simplecontroller.io.decodeControllerProfile
import com.example.simplecontroller.io.decodeControllerProfileTransfer
import com.example.simplecontroller.io.decodeControllerProfilesBackup
import com.example.simplecontroller.io.decodeFloatingRingPreset
import com.example.simplecontroller.io.detachedControllerPageCopy
import com.example.simplecontroller.io.encodeControllerProfile
import com.example.simplecontroller.io.encodeControllerProfileTransfer
import com.example.simplecontroller.io.encodeControllerProfilesBackup
import com.example.simplecontroller.io.encodeFloatingRingPreset
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FloatingRoleBoostPersistenceTest {
    @Test fun legacyMissingBankFields_keepsOldDefaultsAndVersions() {
        val config = Json.decodeFromString(FloatingRingConfig.serializer(), "{}")
        assertNull(config.boostBanks)
        assertFalse(config.directBoostStartsAim)
        assertEquals(10, profile(config).requiredFormatVersion())
        assertEquals(11, profile(config.copy(compactAimBoosts = true)).requiredFormatVersion())
        val old = requireNotNull(zone(config).captureRingPreset(ID, "Old", 1L))
        val previous = requireNotNull(zone(config.copy(compactAimBoosts = true)).captureRingPreset(ID, "Previous", 1L))
        assertEquals(1, decodeFloatingRingPreset(encodeFloatingRingPreset(old)).schemaVersion)
        assertEquals(2, decodeFloatingRingPreset(encodeFloatingRingPreset(previous)).schemaVersion)
    }

    @Test fun seedingBanks_isIndependentAndDoesNotEnableFeaturesOrRewriteLegacyRadii() {
        val config = FloatingRingConfig(compactAimBoosts = true, adjacentMovementBoosts = true,
            boost1 = RingBoostSettings(payload = "X360A"))
        val banks = config.seedBoostBanks()
        assertFalse(banks.enabled)
        assertFalse(banks.movement.geometry.enabled)
        assertFalse(banks.aim.geometry.enabled)
        assertEquals(48f, banks.aim.geometry.boost1DistanceDp, 0f)
        assertEquals(40f, banks.aim.geometry.boost1ThicknessDp, 0f)
        assertEquals(88f, banks.movement.geometry.boost1DistanceDp, 0f)
        assertEquals(40f, banks.movement.geometry.boost1ThicknessDp, 0f)
        assertEquals(24f, banks.aim.geometry.boost2ThicknessDp, 0f)
        assertEquals(88f, banks.aim.geometry.directStartDistanceDp, 0f)
        assertEquals(88f, banks.movement.geometry.directStartDistanceDp, 0f)
        val aimOnly = config.copy(movement = config.movement.copy(enabled = false), startRole = FloatingStartRole.AIM)
        assertEquals(48f, aimOnly.seedBoostBanks().aim.geometry.directStartDistanceDp, 0f)
        assertEquals(48f, aimOnly.seedBoostBanks().movement.geometry.directStartDistanceDp, 0f)
        assertEquals(config.boost1, banks.aim.boost1)
        assertNotSame(config.boost1, banks.aim.boost1)
        assertNotSame(banks.aim.boost1, banks.movement.boost1)
        assertNotSame(banks.aim.boost1.directionalPayloads, banks.movement.boost1.directionalPayloads)
        assertEquals(120f, config.boost1.distanceDp, 0f)
        assertNull(config.boostBanks)
        assertSame(banks, config.copy(boostBanks = banks).seedBoostBanks())
        assertEquals(120f, FloatingRingConfig().seedBoostBanks().movement.geometry.directStartDistanceDp, 0f)
    }

    @Test fun actionSelectionAndCustomGeometry_haveIndependentEnableSwitches() {
        val banks = banks().copy(enabled = false,
            aim = banks().aim.copy(geometry = aimGeometry()))
        val shared = FloatingRingConfig(boost1 = RingBoostSettings(payload = "X360A"), boostBanks = banks)
        assertEquals("X360A", shared.boost(RingBoostStage.ONE, RingBoostBank.AIM).payload)
        assertTrue(shared.boostBanks!!.aim.geometry.enabled)
        assertNull(shared.validationError())
        val separate = shared.copy(boostBanks = banks.copy(enabled = true))
        assertEquals("X360LB", separate.boost(RingBoostStage.ONE, RingBoostBank.AIM).payload)
        assertEquals("X360X", separate.boost(RingBoostStage.ONE, RingBoostBank.MOVEMENT).payload)
        assertEquals("X360A", separate.boost(RingBoostStage.ONE).payload)
    }

    @Test fun directBankFollowsSavedSummonRole_andExplicitFallbackForBoostStarts() {
        val config = FloatingRingConfig(startRole = FloatingStartRole.AIM,
            boostBanks = banks().copy(directStartBank = RingBoostBank.MOVEMENT))
        assertEquals(RingBoostBank.AIM, config.directBoostBank())
        assertEquals(RingBoostBank.AIM, config.boostBankForRole(FloatingStartRole.BOOST_ONE))
        assertEquals(RingBoostBank.MOVEMENT, config.boostBankForRole(FloatingStartRole.MOVEMENT))
        val movement = config.copy(startRole = FloatingStartRole.MOVEMENT,
            boostBanks = banks().copy(directStartBank = RingBoostBank.AIM))
        assertEquals(RingBoostBank.MOVEMENT, movement.directBoostBank())
        val direct = movement.copy(startRole = FloatingStartRole.BOOST_TWO)
        assertEquals(RingBoostBank.AIM, direct.directBoostBank())
        assertEquals(RingBoostBank.AIM, direct.boostBankForRole(FloatingStartRole.BOOST_ONE))
        assertEquals(RingBoostBank.MOVEMENT, FloatingRingConfig(startRole = FloatingStartRole.BOOST_ONE).directBoostBank())
    }

    @Test fun directStartingRoleAndActiveState_useSelectedBankRatherThanDormantRoot() {
        val selected = banks().copy(movement = RingBoostBankSettings(), directStartBank = RingBoostBank.AIM)
        val config = FloatingRingConfig(startRole = FloatingStartRole.BOOST_ONE,
            movement = RingMovementSettings(enabled = false), aim = RingAimSettings(enabled = false),
            boostBanks = selected)
        assertFalse(config.boost1.enabled)
        assertTrue(config.isActive)
        assertNull(config.validationError())
        assertNotNull(config.copy(boostBanks = selected.copy(directStartBank = RingBoostBank.MOVEMENT)).validationError())
    }

    @Test fun customBandValidation_rejectsInvalidNumbersAndProtectedAreaOverlap() {
        val invalid = listOf(
            RingBoostBandLayout(boost1DistanceDp = Float.NaN),
            RingBoostBandLayout(boost1DistanceDp = -1f),
            RingBoostBandLayout(boost1ThicknessDp = 0f),
            RingBoostBandLayout(boost2ThicknessDp = -1f),
            RingBoostBandLayout(directStartDistanceDp = Float.POSITIVE_INFINITY),
            RingBoostBandLayout(boost1ThicknessDp = Float.MAX_VALUE, boost2ThicknessDp = Float.MAX_VALUE),
            RingBoostBandLayout(boost1DistanceDp = Float.MAX_VALUE, boost1ThicknessDp = 1f, boost2ThicknessDp = 1f),
            RingBoostBandLayout(directStartDistanceDp = Float.MAX_VALUE, boost1ThicknessDp = 1f, boost2ThicknessDp = 1f),
            RingBoostBandLayout(boost1DistanceDp = 1f, boost1ThicknessDp = 1e30f, boost2ThicknessDp = 1f)
        )
        invalid.forEach { assertNotNull(it.validationError()) }
        val config = FloatingRingConfig(boostBanks = banks().copy(aim = banks().aim.copy(geometry = aimGeometry())))
        assertNull(config.validationError())
        assertNotNull(config.copy(boostBanks = config.boostBanks!!.withBank(RingBoostBank.AIM,
            config.boostBanks.aim.copy(geometry = aimGeometry().copy(boost1DistanceDp = 47f)))).validationError())
        assertNotNull(config.copy(boostBanks = config.boostBanks!!.withBank(RingBoostBank.AIM,
            config.boostBanks.aim.copy(geometry = aimGeometry().copy(directStartDistanceDp = 87f)))).validationError())
        assertNotNull(config.copy(boostBanks = config.boostBanks!!.withBank(RingBoostBank.MOVEMENT,
            config.boostBanks.movement.copy(geometry = aimGeometry().copy(boost1DistanceDp = 87f)))).validationError())
        assertNull(config.copy(startRole = FloatingStartRole.AIM, movement = config.movement.copy(enabled = false),
            boostBanks = config.boostBanks!!.withBank(RingBoostBank.AIM,
                config.boostBanks.aim.copy(geometry = aimGeometry().copy(directStartDistanceDp = 48f)))).validationError())
    }

    @Test fun inactiveBanksStillValidatePayloadsAndWidths_withoutConstrainingDormantPosition() {
        val banks = banks().copy(enabled = false,
            aim = banks().aim.copy(geometry = aimGeometry().copy(enabled = false, boost1DistanceDp = 1f,
                directStartDistanceDp = 1f)))
        val config = FloatingRingConfig(boostBanks = banks)
        assertNull(config.validationError())
        assertNotNull(config.copy(boostBanks = banks.copy(aim = banks.aim.copy(
            boost1 = banks.aim.boost1.copy(payload = "CAMERA_FOLLOW")))).validationError())
        assertNotNull(config.copy(boostBanks = banks.copy(aim = banks.aim.copy(
            geometry = banks.aim.geometry.copy(boost1ThicknessDp = 0f)))).validationError())
        assertNotNull(config.copy(boostBanks = banks.copy(aim = banks.aim.copy(
            boost1 = banks.aim.boost1.copy(activationDelayMs = 1L)))).validationError())
    }

    @Test fun sharedLegacyThresholdsRemainProtectedForEachUncustomizedRole() {
        val aimOnly = FloatingRingConfig(movementRadiusDp = 150f, boostBanks = RingBoostBanks(
            aim = RingBoostBankSettings(geometry = aimGeometry().copy(directStartDistanceDp = 150f))))
        assertEquals("Boost distances and finite direct areas must be ordered beyond movement.", aimOnly.validationError())
        assertNull(aimOnly.copy(adjacentMovementBoosts = true).validationError())

        val movementOnly = FloatingRingConfig(innerRadiusDp = 130f, movementRadiusDp = 170f,
            boostBanks = RingBoostBanks(movement = RingBoostBankSettings(geometry =
                RingBoostBandLayout(enabled = true, boost1DistanceDp = 170f, directStartDistanceDp = 170f))))
        assertEquals("Shared boost thresholds must start beyond the Aim area.", movementOnly.validationError())
        assertNull(movementOnly.copy(compactAimBoosts = true).validationError())
    }

    @Test fun noStoredWrapperRetainsLegacyThresholdFloor_evenWithDerivedGeometryFlags() {
        val legacy = FloatingRingConfig(movementRadiusDp = 150f,
            compactAimBoosts = true, adjacentMovementBoosts = true)
        assertEquals(11, profile(legacy).requiredFormatVersion())
        assertEquals("Boost distances and finite direct areas must be ordered beyond movement.", legacy.validationError())
        val explicit = legacy.copy(boostBanks = RingBoostBanks())
        assertEquals(12, profile(explicit).requiredFormatVersion())
        assertNull(explicit.validationError())
    }

    @Test fun dormantBankActionsDoNotConflictWithSharedActionsAndAim_untilSeparateActionsEnabled() {
        val stored = banks().copy(enabled = false, aim = banks().aim.copy(
            boost1 = banks().aim.boost1.copy(payload = "X360A", action = RingAction.TAP),
            geometry = aimGeometry()))
        val config = FloatingRingConfig(boost1 = RingBoostSettings(enabled = true, payload = "X360B"),
            boostBanks = stored,
            knobs = listOf(RingAimKnob(fire = RingFireSettings(mode = RingFireMode.HOLD, firePayload = "X360A"))))
        assertNull(config.validationError())
        assertEquals("Boost and inner firing outputs cannot mask each other's finite or Repeat edges.",
            config.copy(boostBanks = stored.copy(enabled = true)).validationError())
    }

    @Test fun retainedMovementRepeatCannotMaskLaterAimAdsOrFire_evenWithoutDirectAiming() {
        listOf(
            Triple("LT:0.75", RingFireSettings(adsPayload = "LT:1"),
                "A boost Tap or Repeat cannot share a held ADS output."),
            Triple("RT:0.75", RingFireSettings(mode = RingFireMode.HOLD, firePayload = "RT:1"),
                "Boost and inner firing outputs cannot mask each other's finite or Repeat edges.")
        ).forEach { (payload, fire, expected) ->
            val stored = banks()
            val movement = stored.movement.copy(boost1 = stored.movement.boost1.copy(
                payload = payload, action = RingAction.REPEAT, repeatAfterExit = true, retainOnLift = true))
            val config = FloatingRingConfig(boostBanks = stored.copy(movement = movement),
                knobs = listOf(RingAimKnob(fire = fire)))
            assertFalse(config.directBoostToAim)
            assertFalse(config.directBoostStartsAim)
            assertNull(config.copy(boostBanks = stored.copy(movement = movement.copy(
                boost1 = movement.boost1.copy(payload = "X360A")))).validationError())
            assertEquals(expected, config.validationError())
        }
    }

    @Test fun immediateDirectAim_isIndependentFromInnerEntryOptionAndNeedsEnabledDestination() {
        val config = FloatingRingConfig(directBoostStartsAim = true, directBoostToAim = false)
        assertNull(config.validationError())
        assertNotNull(config.copy(aim = config.aim.copy(enabled = false)).validationError())
        assertNotNull(config.copy(directBoostAimKnobId = "missing").validationError())
    }

    @Test fun movementOnlyBankDoesNotConflictWithAimOnlyTimer_untilDirectAimingCanUseIt() {
        val settings = banks().copy(movement = banks().movement.copy(boost2 =
            banks().movement.boost2.copy(payload = "RT:1", activationDelayMs = 300L)))
        val config = FloatingRingConfig(boostBanks = settings,
            knobs = listOf(RingAimKnob(fire = RingFireSettings(mode = RingFireMode.HOLD,
                returnFire = RingReturnFireSettings(enabled = true, delayMs = 300L)))))
        assertNull(config.validationError())
        assertNotNull(config.copy(directBoostStartsAim = true).validationError())
    }

    @Test fun disabledBanksAndImmediateAim_useTwelveAndPresetThreeWithoutChangingEnvelopes() {
        listOf(FloatingRingConfig(boostBanks = RingBoostBanks()),
            FloatingRingConfig(directBoostStartsAim = true),
            FloatingRingConfig(boostBanks = banks(), directBoostStartsAim = true)).forEach { config ->
            val source = profile(config)
            val transfer = encodeControllerProfileTransfer("Banks", source)
            val decoded = decodeControllerProfileTransfer(transfer, "Banks").result.profile
            assertEquals(12, decoded.formatVersion)
            assertEquals(config, decoded.pages.single().floatingZones.single().ring)
            val preset = requireNotNull(zone(config).captureRingPreset(ID, "Banks", 1L))
            assertEquals(3, preset.schemaVersion)
            assertEquals(preset, decodeFloatingRingPreset(encodeFloatingRingPreset(preset)))
            val backup = decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup(source).copy(
                floatingRingPresets = listOf(preset)))).backup
            assertEquals(3, backup.backupVersion)
            assertEquals(12, backup.controllerProfiles.single().profile.formatVersion)
            assertEquals(3, backup.floatingRingPresets.single().schemaVersion)
            assertEquals(1, decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup(source))).backup.backupVersion)
        }
    }

    @Test fun fullPresetAndPageCopies_preserveBothBanksLayoutsOrientationsAndIndependentInstances() {
        val config = FloatingRingConfig(boostBanks = banks().copy(aim = banks().aim.copy(geometry = aimGeometry())),
            directBoostStartsAim = true)
        val original = zone(config)
        val preset = requireNotNull(original.captureRingPreset(ID, "Full", 1L))
        val instance = requireNotNull(preset.instantiate("new-ring"))
        assertEquals(original.copy(id = "new-ring"), instance)
        assertNotSame(config.boostBanks, instance.ring!!.boostBanks)
        assertNotSame(config.boostBanks!!.aim, instance.ring.boostBanks!!.aim)
        val copied = detachedControllerPageCopy(profile(config).pages.single(), "copy-page", "Copy").floatingZones.single()
        assertEquals(original.portraitGeometry, copied.portraitGeometry)
        assertEquals(original.landscapeGeometry, copied.landscapeGeometry)
        assertEquals(config.boostBanks, copied.ring!!.boostBanks)
        assertTrue(copied.ring.directBoostStartsAim)
        assertNotEquals(config.startKnobId, copied.ring.startKnobId)
    }

    @Test fun oldVersionLabelsRejectNewBanksAndDirectAim_beforeUnknownNestedEnums() {
        listOf("\"boostBanks\":{\"enabled\":false,\"directStartBank\":\"FUTURE\"}",
            "\"directBoostStartsAim\":true").forEach { option ->
            val raw = """{"formatVersion":11,"pages":[{"floatingZones":[{"kind":"FUTURE","ring":{$option}}]}]}"""
            assertRejected("format 12") { decodeControllerProfile(raw, "Old") }
            assertRejected("format 12") { decodeControllerProfileTransfer(
                """{"fileType":"simplecontroller-profile","transferVersion":1,"profileName":"Old","profile":$raw}""", "Old") }
            assertRejected("format 12") { decodeControllerProfilesBackup(
                """{"fileType":"simplecontroller-profiles-backup","backupVersion":1,
                    "activeControllerProfileName":"Old","controllerProfiles":[{"name":"Old","profile":$raw}]}""") }
            val preset = """{"schemaVersion":2,"zone":{"kind":"FUTURE","ring":{$option}}}"""
            assertRejected("preset version 3") { decodeFloatingRingPreset(preset) }
            assertRejected("preset version 3") { decodeControllerProfilesBackup(
                """{"fileType":"simplecontroller-profiles-backup","backupVersion":3,
                    "activeControllerProfileName":"Old","controllerProfiles":[],"floatingRingPresets":[$preset]}""") }
        }
    }

    @Test fun selectedBankCapture_keepsExistingBoostSchemasAndDoesNotSaveGeometry() {
        val config = FloatingRingConfig(boostBanks = banks())
        assertNull(config.captureBoostProfile(ID, "Ambiguous", 1L))
        val selected = requireNotNull(config.captureBoostProfile(ID, "Aim", 1L, bank = RingBoostBank.AIM))
        assertEquals("X360LB", selected.boost1.payload)
        assertEquals("X360RB", selected.boost2.payload)
        assertEquals(1, selected.schemaVersion)
        assertFalse(Json.encodeToString(FloatingBoostProfile.serializer(), selected).contains("boostBanks"))
        val timed = config.copy(knobs = listOf(RingAimKnob(fire = RingFireSettings(returnFire =
            RingReturnFireSettings(startOnBoostEntry = true)))))
        val withActions = requireNotNull(timed.captureBoostProfile(ID, "Timed", 1L, true, RingBoostBank.MOVEMENT))
        assertEquals(2, withActions.schemaVersion)
        val restored = decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup(profile(FloatingRingConfig())).copy(
            floatingBoostProfiles = listOf(withActions)))).backup
        assertEquals(2, restored.backupVersion)
        assertEquals(withActions, restored.floatingBoostProfiles.single())
    }

    @Test fun selectedBankApplication_preservesTargetGeometryEnableSwitchesAndOtherBank() {
        val saved = requireNotNull(FloatingRingConfig(boost1 = RingBoostSettings(payload = "X360A"))
            .captureBoostProfile(ID, "Shared", 1L))
        val targetBanks = banks().copy(aim = banks().aim.copy(boost1 = banks().aim.boost1.copy(enabled = false),
            geometry = aimGeometry()))
        val target = FloatingRingConfig(boostBanks = targetBanks, directBoostStartsAim = true)
        assertNull(saved.applyBoostSettingsOnlyTo(target))
        val applied = requireNotNull(saved.applyBoostSettingsOnlyTo(target, RingBoostBank.AIM))
        assertEquals("X360A", applied.boostBanks!!.aim.boost1.payload)
        assertFalse(applied.boostBanks.aim.boost1.enabled)
        assertTrue(applied.boostBanks.enabled)
        assertEquals(targetBanks.aim.geometry, applied.boostBanks.aim.geometry)
        assertEquals(targetBanks.movement, applied.boostBanks.movement)
        assertEquals(target.boost1, applied.boost1)
        assertEquals(target.boost2, applied.boost2)
        assertEquals(target.knobs, applied.knobs)
        assertEquals(target.aim, applied.aim)
        assertTrue(applied.directBoostStartsAim)
    }

    @Test fun explicitStoredBankEditing_doesNotEnableSeparateActions() {
        val target = FloatingRingConfig(boostBanks = banks().copy(enabled = false))
        val captured = requireNotNull(target.captureBoostProfile(ID, "Stored Aim", 1L, bank = RingBoostBank.AIM))
        assertEquals("X360LB", captured.boost1.payload)
        val applied = requireNotNull(captured.applyBoostSettingsOnlyTo(FloatingRingConfig(), RingBoostBank.MOVEMENT))
        assertFalse(applied.boostBanks!!.enabled)
        assertFalse(applied.boostBanks.movement.geometry.enabled)
        assertEquals("X360LB", applied.boostBanks.movement.boost1.payload)
        assertEquals("", applied.boost(RingBoostStage.ONE, RingBoostBank.MOVEMENT).payload)
    }

    private fun aimGeometry() = RingBoostBandLayout(enabled = true, boost1DistanceDp = 48f,
        boost1ThicknessDp = 40f, boost2ThicknessDp = 24f, directStartDistanceDp = 88f)

    private fun banks() = RingBoostBanks(enabled = true,
        movement = bank("X360X", "X360Y"), aim = bank("X360LB", "X360RB"))

    private fun bank(first: String, second: String) = RingBoostBankSettings(
        boost1 = RingBoostSettings(enabled = true, payload = first, directTapEnabled = true),
        boost2 = RingBoostSettings(enabled = true, payload = second, directTapEnabled = true,
            distanceDp = 160f, directInnerDistanceDp = 160f, directOuterDistanceDp = 184f))

    private fun zone(config: FloatingRingConfig) = FloatingZone(id = "ring", kind = FloatingZoneKind.RING,
        enabled = false, ring = config,
        portraitGeometry = FloatingZoneGeometry(1000f, 2000f, ControlGeometry(50f, 60f, 400f, 500f)),
        landscapeGeometry = FloatingZoneGeometry(2000f, 1000f, ControlGeometry(70f, 80f, 600f, 300f)))

    private fun profile(config: FloatingRingConfig) = ControllerProfile(homePageId = "page",
        pages = listOf(ControllerPage("page", "Base", floatingZones = listOf(zone(config)))))

    private fun backup(profile: ControllerProfile) = ControllerProfilesBackup(activeControllerProfileName = "Banks",
        controllerProfiles = listOf(NamedControllerProfile("Banks", profile)))

    private fun assertRejected(expected: String, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertNotNull("Expected rejection containing '$expected'", error)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains(expected))
    }

    private companion object { const val ID = "11111111-1111-1111-1111-111111111111" }
}
