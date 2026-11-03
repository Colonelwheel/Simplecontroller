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

class FloatingRingOptionsPersistenceTest {
    @Test
    fun historicalRingWithMissingOptions_keepsLegacyBehaviorAndFormat() {
        val historical = """{"formatVersion":10,"homePageId":"page","pages":[{
            "id":"page","name":"Base","floatingZones":[{
            "id":"ring","kind":"RING","portraitGeometry":{},"ring":{}}]}]}"""
        val restored = decodeControllerProfile(historical, "Old").profile
        val zone = restored.pages.single().floatingZones.single()

        assertFalse(zone.finishIgnoresSwipes)
        assertFalse(zone.ring!!.compactAimBoosts)
        assertFalse(zone.ring.adjacentMovementBoosts)
        assertFalse(zone.ring.knobs.single().fire.returnFire.startOnBoostEntry)
        assertEquals(48f, zone.ring.innerRadiusDp, 0f)
        assertEquals(88f, zone.ring.movementRadiusDp, 0f)
        assertEquals(120f, zone.ring.boost1.distanceDp, 0f)
        assertEquals(160f, zone.ring.boost2.distanceDp, 0f)
        assertEquals(10, decodeControllerProfile(encodeControllerProfile(restored), "Old").profile.formatVersion)
    }

    @Test
    fun inactiveOptions_requireElevenIndependentlyWithoutChangingSavedRadii() {
        (0..15).forEach { options ->
            val zone = zoneWithOptions(options)
            val source = profile(zone)
            val restored = decodeControllerProfile(encodeControllerProfile(source), "Options").profile
            val actual = restored.pages.single().floatingZones.single()

            assertEquals(if (options == 0) 10 else 11, restored.formatVersion)
            assertEquals(zone, actual)
            assertFalse(actual.enabled)
            assertFalse(actual.ring!!.isActive)
            assertEquals(48f, actual.ring.innerRadiusDp, 0f)
            assertEquals(88f, actual.ring.movementRadiusDp, 0f)
            assertEquals(120f, actual.ring.boost1.distanceDp, 0f)
            assertEquals(160f, actual.ring.boost2.distanceDp, 0f)
        }
    }

    @Test
    fun turningOptionsOff_restoresLegacyWriterVersion() {
        val optedIn = zoneWithOptions(15)
        val restoredLegacy = zoneWithOptions(0)

        assertEquals(11, profile(optedIn).requiredFormatVersion())
        assertEquals(10, profile(restoredLegacy).requiredFormatVersion())
        assertEquals(5, ControllerProfile(homePageId = "page",
            pages = listOf(ControllerPage("page", "Base"))).requiredFormatVersion())
    }

    @Test
    fun transferAndControllerOnlyBackup_preserveOptionsWithoutChangingEnvelopes() {
        val zone = zoneWithOptions(15)
        val source = profile(zone)
        val transfer = encodeControllerProfileTransfer("Options", source)
        val transferred = decodeControllerProfileTransfer(transfer, "Fallback").result.profile
        val backup = decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup(source))).backup

        assertTrue(transfer.contains("\"transferVersion\": 1"))
        assertEquals(11, transferred.formatVersion)
        assertEquals(zone, transferred.pages.single().floatingZones.single())
        assertEquals(1, backup.backupVersion)
        assertEquals(11, backup.controllerProfiles.single().profile.formatVersion)
        assertEquals(zone, backup.controllerProfiles.single().profile.pages.single().floatingZones.single())
    }

    @Test
    fun finishPolicyAlsoRoundTripsOnFloatingButtonsWithoutRingConfiguration() {
        val button = Control("member", ControlType.BUTTON, 10f, 20f, 80f, 80f, "X360A")
        val zone = disabledZone().copy(kind = FloatingZoneKind.SINGLE_BUTTON,
            memberIds = listOf(button.id), startMemberId = button.id, ring = null,
            finishIgnoresSwipes = true)
        val source = ControllerProfile(homePageId = "page",
            pages = listOf(ControllerPage("page", "Base", controls = listOf(button), floatingZones = listOf(zone))))
        val restored = decodeControllerProfile(encodeControllerProfile(source), "Button").profile

        assertEquals(11, restored.formatVersion)
        assertEquals(zone, restored.pages.single().floatingZones.single())
    }

    @Test
    fun mislabeledControllerOptions_rejectBeforeUnknownNestedTypesInEveryImportPath() {
        NEW_OPTIONS.forEach { option ->
            val raw = """{"formatVersion":10,"pages":[{"floatingZones":[{"kind":"FUTURE",$option}]}]}"""
            assertRejectedWith("format 11") { decodeControllerProfile(raw, "Old") }
            assertRejectedWith("format 11") { decodeControllerProfileTransfer(
                """{"fileType":"simplecontroller-profile","transferVersion":1,"profileName":"Old","profile":$raw}""", "Old") }
            assertRejectedWith("format 11") { decodeControllerProfilesBackup(
                """{"fileType":"simplecontroller-profiles-backup","backupVersion":1,
                    "activeControllerProfileName":"Old","controllerProfiles":[{"name":"Old","profile":$raw}]}""") }
        }
    }

    @Test
    fun futureControllerVersion_stillRejectsBeforeUnknownNestedTypes() {
        val future = """{"formatVersion":${MAX_SUPPORTED_CONTROLLER_PROFILE_FORMAT_VERSION + 1},
            "pages":[{"floatingZones":[{"kind":"FUTURE"}]}]}"""
        assertRejectedWith("newer") { decodeControllerProfile(future, "Future") }
    }

    @Test
    fun detachedPageCopy_preservesOptionsAndBothOrientationsWhileRemappingIdentity() {
        val zone = zoneWithOptions(15)
        val original = profile(zone).pages.single()
        val copied = detachedControllerPageCopy(original, "copy-page", "Copy").floatingZones.single()

        assertNotEquals(zone.id, copied.id)
        assertNotEquals(zone.ring!!.knobs.single().id, copied.ring!!.knobs.single().id)
        assertTrue(copied.finishIgnoresSwipes)
        assertTrue(copied.ring.compactAimBoosts)
        assertTrue(copied.ring.adjacentMovementBoosts)
        assertTrue(copied.ring.knobs.single().fire.returnFire.startOnBoostEntry)
        assertEquals(zone.portraitGeometry, copied.portraitGeometry)
        assertEquals(zone.landscapeGeometry, copied.landscapeGeometry)
        assertEquals(zone.ring.movementRadiusDp, copied.ring.movementRadiusDp, 0f)
    }

    @Test
    fun fullPresetSchema_isConditionalAndNewOptionsSurviveInstantiation() {
        (0..15).forEach { options ->
            val zone = zoneWithOptions(options)
            val captured = requireNotNull(zone.captureRingPreset(PRESET_ID, "Ring", 1L))
            val restored = decodeFloatingRingPreset(encodeFloatingRingPreset(captured))
            val instance = requireNotNull(restored.instantiate("new-zone"))

            assertEquals(if (options == 0) 1 else 2, restored.schemaVersion)
            assertEquals(captured, restored)
            assertEquals(zone.copy(id = "new-zone"), instance)
            assertNotSame(zone.ring, instance.ring)
        }
    }

    @Test
    fun historicalPresetWithMissingOptions_keepsSchemaOneWhenReencoded() {
        val historical = """{"schemaVersion":1,"id":"$PRESET_ID","name":"Old",
            "createdAtEpochMs":1,"updatedAtEpochMs":1,"zone":{"id":"ring","kind":"RING",
            "portraitGeometry":{},"ring":{}}}"""
        val restored = decodeFloatingRingPreset(historical)
        val encoded = encodeFloatingRingPreset(restored)

        assertEquals(1, restored.schemaVersion)
        assertEquals(1, decodeFloatingRingPreset(encoded).schemaVersion)
        assertFalse(restored.zone.finishIgnoresSwipes)
        assertFalse(restored.zone.ring!!.compactAimBoosts)
        assertFalse(restored.zone.ring.adjacentMovementBoosts)
        assertFalse(restored.zone.ring.knobs.single().fire.returnFire.startOnBoostEntry)
    }

    @Test
    fun fullPresetBackup_staysEnvelopeThreeWithNestedSchemaTwo() {
        val zone = zoneWithOptions(15)
        val preset = requireNotNull(zone.captureRingPreset(PRESET_ID, "Ring", 1L))
        val source = backup(profile(disabledZone())).copy(floatingRingPresets = listOf(preset))
        val restored = decodeControllerProfilesBackup(encodeControllerProfilesBackup(source)).backup

        assertEquals(3, restored.backupVersion)
        assertEquals(10, restored.controllerProfiles.single().profile.formatVersion)
        assertEquals(2, restored.floatingRingPresets.single().schemaVersion)
        assertEquals(zone, restored.floatingRingPresets.single().zone)
    }

    @Test
    fun mislabeledPresetOptions_rejectBeforeUnknownNestedTypesIncludingBackup() {
        NEW_OPTIONS.forEach { option ->
            val raw = """{"schemaVersion":1,"zone":{"kind":"FUTURE",$option}}"""
            assertRejectedWith("preset version 2") { decodeFloatingRingPreset(raw) }
            assertRejectedWith("preset version 2") { decodeControllerProfilesBackup(
                """{"fileType":"simplecontroller-profiles-backup","backupVersion":3,
                    "activeControllerProfileName":"Old","controllerProfiles":[],"floatingRingPresets":[$raw]}""") }
        }
    }

    @Test
    fun boostOnlyProfile_preservesCompactGeometryAndLegacyBoostSchema() {
        val source = FloatingRingConfig(boost1 = RingBoostSettings(payload = "X360A"))
        val saved = requireNotNull(source.captureBoostProfile(PRESET_ID, "Boost", 1L, includeRingAimActions = true))
        val target = FloatingRingConfig(compactAimBoosts = true, adjacentMovementBoosts = true)
        val applied = requireNotNull(saved.applyBoostSettingsOnlyTo(target))
        val actions = requireNotNull(saved.applyRingAimActionsTo(target))

        assertEquals(1, saved.schemaVersion)
        assertTrue(applied.compactAimBoosts)
        assertTrue(actions.compactAimBoosts)
        assertTrue(applied.adjacentMovementBoosts)
        assertTrue(actions.adjacentMovementBoosts)
        assertEquals(target.innerRadiusDp, applied.innerRadiusDp, 0f)
        assertEquals(target.movementRadiusDp, applied.movementRadiusDp, 0f)
        assertEquals("X360A", applied.boost1.payload)
    }

    @Test
    fun boostEntryTimer_requiresNewBoostSchemaOnlyWhenRingActionsAreIncluded() {
        val source = zoneWithOptions(8).ring!!
        val boostOnly = requireNotNull(source.captureBoostProfile(PRESET_ID, "Boost only", 1L))
        val withActions = requireNotNull(source.captureBoostProfile(PRESET_ID, "Actions", 1L, includeRingAimActions = true))
        val target = FloatingRingConfig(compactAimBoosts = true, adjacentMovementBoosts = true)
        val appliedBoost = requireNotNull(withActions.applyBoostSettingsOnlyTo(target))
        val appliedActions = requireNotNull(withActions.applyRingAimActionsTo(target))

        assertEquals(1, boostOnly.schemaVersion)
        assertNull(boostOnly.ringAimActions)
        assertEquals(2, withActions.schemaVersion)
        assertNotNull(withActions.copy(schemaVersion = 1).validationError())
        assertFalse(appliedBoost.knobs.single().fire.returnFire.startOnBoostEntry)
        assertTrue(appliedActions.knobs.single().fire.returnFire.startOnBoostEntry)
        assertTrue(appliedActions.compactAimBoosts)
        assertTrue(appliedActions.adjacentMovementBoosts)
        assertEquals(target.knobs.single().id, appliedActions.knobs.single().id)
    }

    @Test
    fun boostEntryTimerSnapshot_roundTripsThroughBackupTwoWithoutUpgradingLegacyProfiles() {
        val saved = requireNotNull(zoneWithOptions(8).ring!!.captureBoostProfile(
            PRESET_ID, "Timed actions", 1L, includeRingAimActions = true))
        val source = backup(profile(disabledZone())).copy(floatingBoostProfiles = listOf(saved))
        val restored = decodeControllerProfilesBackup(encodeControllerProfilesBackup(source)).backup

        assertEquals(2, restored.backupVersion)
        assertEquals(10, restored.controllerProfiles.single().profile.formatVersion)
        assertEquals(2, restored.floatingBoostProfiles.single().schemaVersion)
        assertEquals(saved, restored.floatingBoostProfiles.single())
        assertTrue(restored.floatingBoostProfiles.single().ringAimActions!!.knobActions.single()
            .returnFire.startOnBoostEntry)
    }

    @Test
    fun historicalBoostSnapshot_withoutTimerOptionKeepsSchemaOne() {
        val saved = requireNotNull(FloatingRingConfig().captureBoostProfile(
            PRESET_ID, "Legacy actions", 1L, includeRingAimActions = true))
        val text = Json.encodeToString(FloatingBoostProfile.serializer(), saved)
        assertFalse(text.contains("startOnBoostEntry"))
        requireSupportedFloatingBoostSchema(Json.parseToJsonElement(text).jsonObject)
        val restored = Json.decodeFromString(FloatingBoostProfile.serializer(), text)

        assertEquals(1, restored.schemaVersion)
        assertNull(restored.validationError())
        assertFalse(restored.ringAimActions!!.knobActions.single().returnFire.startOnBoostEntry)
    }

    @Test
    fun mislabeledBoostEntryTimer_rejectsBeforeUnknownActionIncludingBackup() {
        listOf("\"schemaVersion\":1,", "").forEach { version ->
            val raw = """{$version"boost1":{"action":"FUTURE"},"ringAimActions":{
                "knobActions":[{"returnFire":{"enabled":false,"startOnBoostEntry":true}}]}}"""
            assertRejectedWith("boost profile version 2") {
                requireSupportedFloatingBoostSchema(Json.parseToJsonElement(raw).jsonObject)
            }
            assertRejectedWith("boost profile version 2") { decodeControllerProfilesBackup(
                """{"fileType":"simplecontroller-profiles-backup","backupVersion":2,
                    "activeControllerProfileName":"Old","controllerProfiles":[],"floatingBoostProfiles":[$raw]}""") }
        }
    }

    private fun zoneWithOptions(options: Int): FloatingZone {
        val zone = disabledZone()
        val ring = zone.ring!!
        return zone.copy(finishIgnoresSwipes = (options and 1) != 0, ring = ring.copy(
            compactAimBoosts = (options and 2) != 0,
            adjacentMovementBoosts = (options and 4) != 0,
            knobs = ring.knobs.map { knob -> knob.copy(enabled = false,
                fire = knob.fire.copy(returnFire = knob.fire.returnFire.copy(startOnBoostEntry = (options and 8) != 0))) }
        ))
    }

    private fun disabledZone() = FloatingZone(
        id = "ring", name = "Ring", kind = FloatingZoneKind.RING, enabled = false,
        portraitGeometry = FloatingZoneGeometry(1000f, 2000f, ControlGeometry(50.5f, 60.25f, 400f, 500f)),
        landscapeGeometry = FloatingZoneGeometry(2000f, 1000f, ControlGeometry(70.5f, 80.25f, 600f, 300f)),
        ring = FloatingRingConfig(movement = RingMovementSettings(enabled = false),
            aim = RingAimSettings(enabled = false))
    )

    private fun profile(zone: FloatingZone) = ControllerProfile(homePageId = "page",
        pages = listOf(ControllerPage("page", "Base", floatingZones = listOf(zone))))

    private fun backup(profile: ControllerProfile) = ControllerProfilesBackup(activeControllerProfileName = "Options",
        controllerProfiles = listOf(NamedControllerProfile("Options", profile)))

    private fun assertRejectedWith(expected: String, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertNotNull("Expected rejection containing '$expected'", failure)
        val message = failure!!.message.orEmpty()
        assertTrue(message, message.contains(expected))
    }

    private companion object {
        const val PRESET_ID = "11111111-1111-1111-1111-111111111111"
        val NEW_OPTIONS = listOf(
            "\"finishIgnoresSwipes\":true",
            "\"ring\":{\"compactAimBoosts\":true}",
            "\"ring\":{\"adjacentMovementBoosts\":true}",
            "\"ring\":{\"knobs\":[{\"fire\":{\"returnFire\":{\"startOnBoostEntry\":true}}}]}"
        )
    }
}
