package com.example.simplecontroller.model

import com.example.simplecontroller.io.ControllerProfilesBackup
import com.example.simplecontroller.io.NamedControllerProfile
import com.example.simplecontroller.io.decodeControllerProfileTransfer
import com.example.simplecontroller.io.decodeControllerProfilesBackup
import com.example.simplecontroller.io.detachedControlCopy
import com.example.simplecontroller.io.detachedControllerPageCopy
import com.example.simplecontroller.io.encodeControllerProfile
import com.example.simplecontroller.io.encodeControllerProfileTransfer
import com.example.simplecontroller.io.encodeControllerProfilesBackup
import com.example.simplecontroller.io.validateControllerProfileForSave
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class FloatingControlsPersistenceTest {
    @Test
    fun ringSavedBeforeInwardBoostOption_keepsPriorDirectBoostBehavior() {
        val restored = Json.decodeFromString(FloatingRingConfig.serializer(), "{}")
        assertFalse(restored.directBoostTwoToOne)
        assertNull(restored.validationError())
    }

    @Test
    fun disabledRing_roundTripsEveryStoredActionAndRequiresFormatTen() {
        val ring = FloatingRingConfig(
            movement = RingMovementSettings(enabled = false, initialX = 0.5f, autoCenter = false),
            aim = RingAimSettings(
                enabled = false,
                tuning = defaultRingAimTuning().copy(output = ButtonAimOutput.GAME_MOUSE),
                continueOutside = false,
                fine = RingFineAimSettings(enabled = true, sensitivity = 0.4f,
                    qualifyingStages = setOf(RingBoostStage.TWO))
            ),
            knobs = listOf(RingAimKnob(fire = RingFireSettings(
                mode = RingFireMode.RELEASE_SHOT, adsPayload = "LT:1.0", retainAdsOnLift = true,
                cancelReleaseShotOnBoundaryExit = true, rearmReleaseShotOnBoundaryReturn = true,
                returnFire = RingReturnFireSettings(enabled = true, delayMs = 300, cancelOnExit = false)
            ))),
            boost1 = RingBoostSettings(payload = "LT:1.0", latchOnExit = true, retainOnLift = true),
            boost2 = RingBoostSettings(distanceDp = 160f, directInnerDistanceDp = 160f,
                directOuterDistanceDp = 184f, payload = "RT:1.0", activationDelayMs = 300,
                cancelDelayOnExit = false, latchOnExit = true),
            directBoostToAim = true,
            directBoostTwoToOne = true
        )
        val source = profile(ControllerPage("page", "Base", floatingZones = listOf(ringZone(ring))))
        val encoded = encodeControllerProfileTransfer("Combat", source)
        val restored = decodeControllerProfileTransfer(encoded, "Fallback").result.profile

        assertEquals(10, restored.formatVersion)
        assertEquals(ring, restored.pages.single().floatingZones.single().ring)
        assertFalse(restored.pages.single().floatingZones.single().ring!!.isActive)
        assertTrue(encoded.contains("\"formatVersion\": 10"))
    }

    @Test
    fun legacyAndNewSuppression_useConditionalFeatureVersion() {
        val ordinary = button("a")
        assertEquals(5, profile(ControllerPage("page", "Base", listOf(ordinary))).requiredFormatVersion())
        ordinary.mouseSuppressCameraFollow = true
        assertEquals(10, profile(ControllerPage("page", "Base", listOf(ordinary))).requiredFormatVersion())
        ordinary.mouseSuppressCameraFollow = false
        ordinary.floatingActionTargetId = "retained-disabled-target"
        assertEquals(10, profile(ControllerPage("page", "Base", listOf(ordinary))).requiredFormatVersion())
    }

    @Test
    fun pageDuplicate_remapsMembersKnobsDestinationsScopedActionsAndBothGeometries() {
        val source = groupedPage().copy(
            controls = groupedPage().controls + button("finish").copy(
                floatingLocalAction = FloatingLocalAction.FINISH, floatingActionTargetId = "ring"
            ),
            floatingZones = groupedPage().floatingZones + ringZone()
        )
        val copy = detachedControllerPageCopy(source, "new-page", "Copy")
        val group = copy.floatingZones.first { it.kind == FloatingZoneKind.BUTTON_GROUP }
        val ringZone = copy.floatingZones.first { it.kind == FloatingZoneKind.RING }
        val ring = ringZone.ring!!

        assertNotEquals(source.id, copy.id)
        assertEquals(2, group.memberIds.size)
        assertTrue(group.memberIds.all { it in copy.controls.map(Control::id) })
        assertEquals(group.memberIds.toSet(), group.portraitMemberGeometry.keys)
        assertEquals(group.memberIds.toSet(), group.landscapeRestorationGeometry.keys)
        assertEquals(group.memberIds.first(), group.startMemberId)
        assertNotEquals("aim_1", ring.knobs.single().id)
        assertEquals(ring.knobs.single().id, ring.startKnobId)
        assertEquals(ring.knobs.single().id, ring.directBoostAimKnobId)
        assertEquals(ringZone.id, copy.controls.first { it.floatingLocalAction == FloatingLocalAction.FINISH }.floatingActionTargetId)
        assertNull(validateControllerProfileForSave(profile(copy)))

        copy.controls.first { it.id == group.memberIds.first() }.buttonDirectional.up.firstPayload = "Changed"
        assertEquals("X360UP", source.controls.first().buttonDirectional.up.firstPayload)
    }

    @Test
    fun singleControlDuplicate_doesNotShareDirectionalConfiguration() {
        val original = button("a").copy(buttonDirectional = ButtonDirectionalSettings(
            up = ButtonDirectionStages(firstPayload = "X360UP")
        ))
        val copy = detachedControlCopy(original)
        copy.buttonDirectional.up.firstPayload = "X360DOWN"

        assertEquals("X360UP", original.buttonDirectional.up.firstPayload)
        assertNotEquals(original.id, copy.id)
        assertEquals(original.x + 40f, copy.x, 0.001f)
    }

    @Test
    fun navigationOnlyCopy_isExplicitlyStandaloneAndPartialGroupCopyIsRejected() {
        val page = groupedPage()
        assertTrue(runCatching {
            detachedControllerPageCopy(page, "other", "Other", include = { it.id == "a" })
        }.isFailure)
        val standalone = detachedControllerPageCopy(
            page, "other", "Other", include = { it.id == "a" }, includeFloatingZones = false
        )
        assertEquals(1, standalone.controls.size)
        assertTrue(standalone.floatingZones.isEmpty())
        assertEquals(page.controls.first().payload, standalone.controls.single().payload)
    }

    @Test
    fun boostOnlyApply_preservesTargetLayersMovementGeometryNormalAndFineAim() {
        val target = FloatingRingConfig(
            aim = RingAimSettings(tuning = defaultRingAimTuning().copy(sensitivity = 1.7f),
                fine = RingFineAimSettings(enabled = true, sensitivity = 0.6f,
                    qualifyingStages = setOf(RingBoostStage.TWO))),
            boost1 = RingBoostSettings(enabled = true)
        )
        val saved = FloatingRingConfig(
            boost1 = RingBoostSettings(payload = "LT:1.0", latchOnExit = true),
            boost2 = RingBoostSettings(distanceDp = 180f, directInnerDistanceDp = 180f,
                directOuterDistanceDp = 200f, payload = "RT:1.0", activationDelayMs = 300)
        ).captureBoostProfile(UUID_ONE, "Combat", 1, includeRingAimActions = true)!!
        val applied = saved.applyBoostSettingsOnlyTo(target)!!

        assertEquals(target.aim, applied.aim)
        assertEquals(target.movement, applied.movement)
        assertEquals(target.knobs, applied.knobs)
        assertEquals(target.movementRadiusDp, applied.movementRadiusDp, 0.001f)
        assertTrue(applied.boost1.enabled)
        assertFalse(applied.boost2.enabled)
        assertEquals("LT:1.0", applied.boost1.payload)
        assertEquals(300L, applied.boost2.activationDelayMs)
        assertEquals("", target.boost1.payload)
    }

    @Test
    fun ringActionPreset_mapsCompatibleSlotsAtomicallyAndPreservesIdentities() {
        val saved = FloatingRingConfig(knobs = listOf(RingAimKnob(fire = RingFireSettings(
            mode = RingFireMode.RELEASE_SHOT,
            returnFire = RingReturnFireSettings(enabled = true, delayMs = 300)
        )))).captureBoostProfile(UUID_ONE, "Aimed shot", 1, includeRingAimActions = true)!!
        val target = FloatingRingConfig(knobs = listOf(RingAimKnob(id = "target-knob")),
            startKnobId = "target-knob", directBoostAimKnobId = "target-knob")
        val applied = saved.applyRingAimActionsTo(target)!!
        assertEquals("target-knob", applied.knobs.single().id)
        assertEquals(RingFireMode.RELEASE_SHOT, applied.knobs.single().fire.mode)
        assertEquals(300L, applied.knobs.single().fire.returnFire.delayMs)
        val incompatible = target.copy(knobs = listOf(RingAimKnob(id = "one"), RingAimKnob(id = "two")),
            startKnobId = "one", directBoostAimKnobId = "one")
        assertNull(saved.applyRingAimActionsTo(incompatible))
        assertEquals(RingFireMode.NONE, incompatible.knobs.first().fire.mode)
    }

    @Test
    fun boostLibrary_requiresBackupTwoEvenWithOnlyLegacyControllerProfiles() {
        val legacy = profile(ControllerPage("page", "Base", listOf(button("a"))))
        val backup = ControllerProfilesBackup(
            activeControllerProfileName = "Legacy", controllerProfiles = listOf(NamedControllerProfile("Legacy", legacy)),
            floatingBoostProfiles = listOf(FloatingRingConfig().captureBoostProfile(UUID_ONE, "Boost", 1)!!)
        )
        val restored = decodeControllerProfilesBackup(encodeControllerProfilesBackup(backup)).backup
        assertEquals(2, restored.backupVersion)
        assertEquals(5, restored.controllerProfiles.single().profile.formatVersion)
        assertEquals(backup.floatingBoostProfiles, restored.floatingBoostProfiles)
        val historical = backup.copy(floatingBoostProfiles = emptyList())
        assertEquals(1, decodeControllerProfilesBackup(encodeControllerProfilesBackup(historical)).backup.backupVersion)
    }

    @Test
    fun futureVersionsAndUnderVersionedLibraries_areRejectedBeforeUnknownShapes() {
        val futureProfile = """{"fileType":"simplecontroller-profile","transferVersion":1,"profileName":"Future",
            "profile":{"formatVersion":12,"pages":[{"floatingZones":[{"kind":"FUTURE"}]}]}}"""
        assertTrue(runCatching { decodeControllerProfileTransfer(futureProfile, "Future") }
            .exceptionOrNull()!!.message!!.contains("newer"))
        val futureLibrary = """{"fileType":"simplecontroller-profiles-backup","backupVersion":2,
            "activeControllerProfileName":"Old","controllerProfiles":[],
            "floatingBoostProfiles":[{"schemaVersion":999,"futureShape":true}]}"""
        assertTrue(runCatching { decodeControllerProfilesBackup(futureLibrary) }
            .exceptionOrNull()!!.message!!.contains("newer"))
        val mislabeled = futureLibrary.replace("\"backupVersion\":2", "\"backupVersion\":1")
        assertTrue(runCatching { decodeControllerProfilesBackup(mislabeled) }
            .exceptionOrNull()!!.message!!.contains("backup version 2"))
    }

    @Test
    fun invalidMembershipDisabledStartAndIrreversibleRingPayloads_areRejected() {
        assertNotNull(profile(groupedPage().copy(floatingZones = listOf(
            groupedPage().floatingZones.single().copy(memberIds = listOf("missing"))
        ))).let(::validateControllerProfileForSave))
        assertNotNull(FloatingRingConfig(movement = RingMovementSettings(enabled = false)).validationError())
        assertNotNull(FloatingRingConfig(directBoostToAim = false, directBoostAimKnobId = "missing").validationError())
        assertNotNull(RingBoostSettings(payload = "CAMERA_FOLLOW").validationError())
        assertNotNull(RingFireSettings(adsPayload = "RT:0").validationError())
        assertNull(RingFireSettings(adsPayload = "", firePayload = "").validationError())
    }

    @Test
    fun detachedRuntimePlacement_doesNotAlterSerializedCanonicalGeometry() {
        val canonical = profile(groupedPage())
        val before = encodeControllerProfile(canonical)
        val runtime = detachedControllerPageCopy(canonical.pages.single(), "runtime", "Runtime")
        runtime.controls.forEach { it.x += 200f; it.y += 80f }
        assertEquals(before, encodeControllerProfile(canonical))
    }

    @Test
    fun logicalOutputConflicts_rejectDuplicateTimersAndMaskedPulseEdges() {
        val returned = FloatingRingConfig(
            aim = RingAimSettings(participateInBoosts = true),
            knobs = listOf(RingAimKnob(fire = RingFireSettings(mode = RingFireMode.HOLD,
                returnFire = RingReturnFireSettings(enabled = true, delayMs = 300)))),
            boost2 = RingBoostSettings(enabled = true, distanceDp = 160f,
                directInnerDistanceDp = 160f, directOuterDistanceDp = 184f,
                payload = "RT:0.8", activationDelayMs = 300)
        )
        assertNotNull(returned.validationError())
        assertNull(returned.copy(boost2 = returned.boost2.copy(payload = "X360A")).validationError())
        val masked = FloatingRingConfig(
            boost1 = RingBoostSettings(enabled = true, payload = "MOUSE_RIGHT", latchOnExit = true),
            boost2 = RingBoostSettings(enabled = true, distanceDp = 160f,
                directInnerDistanceDp = 160f, directOuterDistanceDp = 184f,
                payload = "MOUSE_RIGHT_DOWN", action = RingAction.REPEAT)
        )
        assertNotNull(masked.validationError())
        assertNotNull(RingBoostSettings(payload = "RT:1P0.3").validationError())
    }

    private fun ringZone(config: FloatingRingConfig = FloatingRingConfig()) = FloatingZone(
        id = "ring", kind = FloatingZoneKind.RING,
        portraitGeometry = FloatingZoneGeometry(800f, 600f, ControlGeometry(400f, 0f, 300f, 300f)), ring = config
    )

    private fun groupedPage(): ControllerPage {
        val members = listOf(
            button("a").copy(buttonDirectional = ButtonDirectionalSettings(up = ButtonDirectionStages(firstPayload = "X360UP"))),
            button("b").copy(x = 160f)
        )
        val original = members.associate { it.id to ControlGeometry(it.x, it.y, it.w, it.h) }
        val relative = original.mapValues { (_, value) -> value.copy(x = value.x - 40f, y = value.y - 40f) }
        return ControllerPage("page", "Base", members,
            portraitGeometry = capturePageGeometry(members, 800f, 600f),
            landscapeGeometry = capturePageGeometry(members, 600f, 800f),
            floatingZones = listOf(FloatingZone(
                id = "group", kind = FloatingZoneKind.BUTTON_GROUP, memberIds = listOf("a", "b"), startMemberId = "a",
                portraitGeometry = FloatingZoneGeometry(800f, 600f, ControlGeometry(0f, 0f, 300f, 200f)),
                landscapeGeometry = FloatingZoneGeometry(600f, 800f, ControlGeometry(0f, 0f, 300f, 200f)),
                portraitMemberGeometry = relative, landscapeMemberGeometry = relative,
                portraitRestorationGeometry = original, landscapeRestorationGeometry = original
            )))
    }

    private fun profile(page: ControllerPage) = ControllerProfile(homePageId = page.id, pages = listOf(page))
    private fun button(id: String) = Control(id, ControlType.BUTTON, 40f, 40f, 80f, 80f, "X360A")

    private companion object { const val UUID_ONE = "11111111-1111-1111-1111-111111111111" }
}
