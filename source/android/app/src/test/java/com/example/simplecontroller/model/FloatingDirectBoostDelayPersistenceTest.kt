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
import com.example.simplecontroller.io.requireSupportedFloatingBoostSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class FloatingDirectBoostDelayPersistenceTest {
    @Test fun missingOrFalseFlag_keepsPreviousConditionalVersionsPinned() {
        assertFalse(Json.decodeFromString(RingBoostSettings.serializer(), "{}").skipDelayOnDirectTouch)
        listOf(
            Triple(FloatingRingConfig(), 10, 1),
            Triple(FloatingRingConfig(compactAimBoosts = true), 11, 2),
            Triple(FloatingRingConfig(boostBanks = RingBoostBanks()), 12, 3)
        ).forEach { (config, format, schema) ->
            val restored = decodeControllerProfile(encodeControllerProfile(profile(config)), "Old").profile
            assertEquals(format, restored.formatVersion)
            assertFalse(restored.pages.single().floatingZones.single().ring!!.boost2.skipDelayOnDirectTouch)
            val preset = requireNotNull(zone(config).captureRingPreset(ID, "Old", 1L))
            assertEquals(schema, decodeFloatingRingPreset(encodeFloatingRingPreset(preset)).schemaVersion)
        }
        val ordinary = requireNotNull(FloatingRingConfig().captureBoostProfile(ID, "Old", 1L))
        val timed = requireNotNull(FloatingRingConfig(knobs = listOf(RingAimKnob(fire = RingFireSettings(
            returnFire = RingReturnFireSettings(startOnBoostEntry = true)))))
            .captureBoostProfile(ID, "Timed", 1L, includeRingAimActions = true))
        assertEquals(1, ordinary.schemaVersion)
        assertEquals(2, timed.schemaVersion)
        listOf(ordinary, timed).forEach {
            requireSupportedFloatingBoostSchema(Json.parseToJsonElement(Json.encodeToString(FloatingBoostProfile.serializer(), it)).jsonObject)
            assertNull(it.validationError())
        }
    }

    @Test fun disabledSharedOrStoredBankFlag_requiresThirteenAndFullPresetFour_withoutEnvelopeChanges() {
        val base = FloatingRingConfig()
        val configurations = listOf(base.copy(boost2 = base.boost2.copy(skipDelayOnDirectTouch = true))) +
            RingBoostBank.entries.map { role ->
                val banks = base.seedBoostBanks()
                val selected = banks.settings(role)
                base.copy(boostBanks = banks.withBank(role,
                    selected.copy(boost2 = selected.boost2.copy(skipDelayOnDirectTouch = true))))
            }
        configurations.forEach { config ->
            assertFalse(config.boost2.enabled)
            assertTrue(config.boostBanks?.enabled != true)
            val source = profile(config)
            assertEquals(13, source.requiredFormatVersion())
            val bare = decodeControllerProfile(encodeControllerProfile(source), "Saved").profile
            val transfer = decodeControllerProfileTransfer(encodeControllerProfileTransfer("Saved", source), "Saved").result.profile
            val restored = decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup(source))).backup
            assertEquals(13, bare.formatVersion)
            assertEquals(config, bare.pages.single().floatingZones.single().ring)
            assertEquals(bare, transfer)
            assertEquals(1, restored.backupVersion)
            assertEquals(bare, restored.controllerProfiles.single().profile)

            val preset = requireNotNull(zone(config).captureRingPreset(ID, "Saved", 1L))
            assertEquals(4, preset.schemaVersion)
            assertEquals(preset, decodeFloatingRingPreset(encodeFloatingRingPreset(preset)))
            val library = decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup(source).copy(
                floatingRingPresets = listOf(preset)))).backup
            assertEquals(3, library.backupVersion)
            assertEquals(preset, library.floatingRingPresets.single())
        }
    }

    @Test fun selectedBankCaptureAndApply_copyBypassAndTimingWithoutReplacingOtherSettings() {
        val base = FloatingRingConfig()
        val banks = base.seedBoostBanks()
        val source = base.copy(boostBanks = banks.copy(aim = banks.aim.copy(boost2 = banks.aim.boost2.copy(
            activationDelayMs = 300L, skipDelayOnDirectTouch = true, pulsePressMs = 45L, pulseGapMs = 60L))))
        val saved = requireNotNull(source.captureBoostProfile(ID, "Direct", 1L, bank = RingBoostBank.AIM))
        assertEquals(3, saved.schemaVersion)
        assertTrue(saved.boost2.skipDelayOnDirectTouch)
        assertEquals(1, source.captureBoostProfile(ID, "Shared", 1L)!!.schemaVersion)
        assertEquals(1, source.captureBoostProfile(ID, "Movement", 1L, bank = RingBoostBank.MOVEMENT)!!.schemaVersion)

        val targetBanks = banks.copy(aim = banks.aim.copy(geometry = RingBoostBandLayout(enabled = true)))
        val target = base.copy(boostBanks = targetBanks)
        val applied = requireNotNull(saved.applyBoostSettingsOnlyTo(target, RingBoostBank.AIM))
        assertFalse(applied.boostBanks!!.enabled)
        assertFalse(applied.boostBanks.aim.boost2.enabled)
        assertTrue(applied.boostBanks.aim.boost2.skipDelayOnDirectTouch)
        assertEquals(300L, applied.boostBanks.aim.boost2.activationDelayMs)
        assertEquals(45L, applied.boostBanks.aim.boost2.pulsePressMs)
        assertEquals(60L, applied.boostBanks.aim.boost2.pulseGapMs)
        assertEquals(targetBanks.aim.geometry, applied.boostBanks.aim.geometry)
        assertEquals(targetBanks.movement, applied.boostBanks.movement)
        assertEquals(target.boost2, applied.boost2)
        assertEquals(target.aim, applied.aim)
        assertEquals(target.knobs, applied.knobs)
        assertTrue(requireNotNull(saved.applyBoostSettingsOnlyTo(base)).boost2.skipDelayOnDirectTouch)
    }

    @Test fun namedBoostSchemaThree_roundTripsInBackupTwoWithLegacyControllerProfile() {
        val base = FloatingRingConfig()
        val saved = requireNotNull(base.copy(boost2 = base.boost2.copy(skipDelayOnDirectTouch = true))
            .captureBoostProfile(ID, "Direct", 1L))
        assertNotNull(saved.copy(schemaVersion = 2).validationError())
        val legacy = ControllerProfile(homePageId = "page", pages = listOf(ControllerPage("page", "Base")))
        val restored = decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup(legacy).copy(
            floatingBoostProfiles = listOf(saved)))).backup
        assertEquals(2, restored.backupVersion)
        assertEquals(5, restored.controllerProfiles.single().profile.formatVersion)
        assertEquals(saved, restored.floatingBoostProfiles.single())
    }

    @Test fun fullPresetAndPageCopies_preserveFlagAndBothOrientationGeometries() {
        val base = FloatingRingConfig()
        val original = zone(base.copy(boost2 = base.boost2.copy(skipDelayOnDirectTouch = true)))
        val preset = requireNotNull(original.captureRingPreset(ID, "Direct", 1L))
        val instance = requireNotNull(preset.instantiate("new-ring"))
        assertNotSame(original.ring, instance.ring)
        assertTrue(instance.ring!!.boost2.skipDelayOnDirectTouch)
        assertEquals(original.portraitGeometry, instance.portraitGeometry)
        assertEquals(original.landscapeGeometry, instance.landscapeGeometry)
        val copied = detachedControllerPageCopy(ControllerPage("page", "Base", floatingZones = listOf(original)),
            "copy-page", "Copy").floatingZones.single()
        assertTrue(copied.ring!!.boost2.skipDelayOnDirectTouch)
        assertEquals(original.portraitGeometry, copied.portraitGeometry)
        assertEquals(original.landscapeGeometry, copied.landscapeGeometry)
    }

    @Test fun boostOneRejectsBypass_inSharedInactiveBanksAndNamedProfiles() {
        val invalid = RingBoostSettings(skipDelayOnDirectTouch = true)
        val base = FloatingRingConfig()
        val expected = "Only Boost 2 supports skipping delay on direct touch."
        assertEquals(expected, base.copy(boost1 = invalid).validationError())
        RingBoostBank.entries.forEach { role ->
            val banks = base.seedBoostBanks()
            val config = base.copy(boostBanks = banks.withBank(role, banks.settings(role).copy(boost1 = invalid)))
            assertEquals(expected, config.validationError())
            assertNull(config.captureBoostProfile(ID, "Invalid", 1L, bank = role))
        }
        val named = FloatingBoostProfile(schemaVersion = 3, id = ID, name = "Invalid",
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L, boost1 = invalid, boost2 = base.boost2)
        assertEquals(expected, named.validationError())
    }

    @Test fun underVersionedFlag_rejectsBeforeUnknownEnums_inProfilesPresetsAndBackups() {
        val stage = """{"enabled":false,"skipDelayOnDirectTouch":true}"""
        listOf(""""boost2":$stage""",
            """"boostBanks":{"enabled":false,"movement":{"boost2":$stage}}""",
            """"boostBanks":{"enabled":false,"aim":{"boost2":$stage}}""").forEach { option ->
            val raw = """{"formatVersion":12,"pages":[{"floatingZones":[{"kind":"FUTURE","enabled":false,"ring":{$option}}]}]}"""
            assertRejected("format 13") { decodeControllerProfile(raw, "Old") }
            assertRejected("format 13") { decodeControllerProfileTransfer(
                """{"fileType":"simplecontroller-profile","transferVersion":1,"profileName":"Old","profile":$raw}""", "Old") }
            assertRejected("format 13") { decodeControllerProfilesBackup(
                """{"fileType":"simplecontroller-profiles-backup","backupVersion":1,
                    "activeControllerProfileName":"Old","controllerProfiles":[{"name":"Old","profile":$raw}]}""") }
            val preset = """{"schemaVersion":3,"zone":{"kind":"FUTURE","ring":{$option}}}"""
            assertRejected("preset version 4") { decodeFloatingRingPreset(preset) }
            assertRejected("preset version 4") { decodeControllerProfilesBackup(
                """{"fileType":"simplecontroller-profiles-backup","backupVersion":3,
                    "activeControllerProfileName":"Old","controllerProfiles":[],"floatingRingPresets":[$preset]}""") }
        }
        val named = """{"schemaVersion":2,"boost1":{"action":"FUTURE"},"boost2":$stage}"""
        assertRejected("boost profile version 3") {
            requireSupportedFloatingBoostSchema(Json.parseToJsonElement(named).jsonObject)
        }
        assertRejected("boost profile version 3") { decodeControllerProfilesBackup(
            """{"fileType":"simplecontroller-profiles-backup","backupVersion":2,
                "activeControllerProfileName":"Old","controllerProfiles":[],"floatingBoostProfiles":[$named]}""") }
    }

    private fun zone(config: FloatingRingConfig) = FloatingZone(id = "ring", kind = FloatingZoneKind.RING,
        enabled = false, ring = config,
        portraitGeometry = FloatingZoneGeometry(1000f, 2000f, ControlGeometry(50f, 60f, 400f, 500f)),
        landscapeGeometry = FloatingZoneGeometry(2000f, 1000f, ControlGeometry(70f, 80f, 600f, 300f)))

    private fun profile(config: FloatingRingConfig) = ControllerProfile(homePageId = "page",
        pages = listOf(ControllerPage("page", "Base", floatingZones = listOf(zone(config)))))

    private fun backup(profile: ControllerProfile) = ControllerProfilesBackup(activeControllerProfileName = "Saved",
        controllerProfiles = listOf(NamedControllerProfile("Saved", profile)))

    private fun assertRejected(expected: String, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertNotNull("Expected rejection containing '$expected'", error)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains(expected))
    }

    private companion object { const val ID = "11111111-1111-1111-1111-111111111111" }
}
