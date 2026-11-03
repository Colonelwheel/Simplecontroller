package com.example.simplecontroller.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickDirectionalProfileTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun directionalProfile_appliesToRadialStickWithoutChangingItsType() {
        val profile = requireNotNull(
            stick().copy(directionalMode = true, upCommand = "X360Y")
                .captureStickDirectionalProfile(ID, "Directions", 10L)
        )
        val target = stick(ControlType.RADIAL_CURVED_STICK).copy(sensitivity = 0.2f)

        assertTrue(profile.applyCommandsTo(target))
        assertEquals(ControlType.RADIAL_CURVED_STICK, target.type)
        assertEquals(0.2f, target.sensitivity)
        assertEquals("X360Y", target.upCommand)
    }

    @Test
    fun captureRoundTripAndCommandsOnlyApply_preservesTargetStickBehavior() {
        val source = stick().copy(
            directionalMode = false,
            stickPlusMode = true,
            upCommand = "X360Y",
            downCommand = "X360A",
            leftCommand = "X360LB",
            rightCommand = "X360RB",
            boostThreshold = .55f,
            upBoostCommand = "X360Y,X360LB",
            downBoostCommand = "X360A,X360LB",
            leftBoostCommand = "X360LB,X360X",
            rightBoostCommand = "X360RB,X360B",
            superBoostThreshold = .9f,
            extendedBoost = ExtendedBoostSettings(
                enabled = true,
                mode = ExtendedBoostMode.ADD_OUTER_STAGE,
                outer = DirectionalThresholds(1.3f, 1.4f, 1.5f, 1.6f),
                outerCommands = DirectionalCommands(up = "X360A", right = "X360B")
            ),
            upSuperBoostCommand = "X360Y,X360A",
            downSuperBoostCommand = "X360A,X360B",
            leftSuperBoostCommand = "X360LB,X360A",
            rightSuperBoostCommand = "X360RB,X360A"
        )
        val captured = requireNotNull(
            source.captureStickDirectionalProfile(ID, "Platforming", 10L)
        )
        val profile = json.decodeFromString<StickDirectionalProfile>(
            json.encodeToString(StickDirectionalProfile.serializer(), captured)
        )
        assertEquals(captured, profile)
        assertEquals(StickDirectionalProfileMode.STICK_PLUS, profile.mode)

        val target = stick(ControlType.CURVED_STICK).copy(
            id = "target",
            x = 80f,
            y = 90f,
            w = 360f,
            h = 340f,
            payload = "STICK_R",
            sensitivity = .42f,
            autoCenter = false,
            directionalMode = true,
            stickPlusMode = false
        )

        assertTrue(profile.applyCommandsTo(target))
        assertEquals("X360Y", target.upCommand)
        assertEquals(.55f, target.boostThreshold)
        assertEquals("X360RB,X360A", target.rightSuperBoostCommand)
        assertEquals(source.extendedBoost, target.extendedBoost)
        assertTrue(target.directionalMode)
        assertFalse(target.stickPlusMode)
        assertEquals("target", target.id)
        assertEquals(80f, target.x)
        assertEquals(360f, target.w)
        assertEquals("STICK_R", target.payload)
        assertEquals(.42f, target.sensitivity)
        assertFalse(target.autoCenter)
        assertEquals(ControlType.CURVED_STICK, target.type)
    }

    @Test
    fun applyAndSwitchMode_selectsSavedModeOnly() {
        val profile = requireNotNull(
            stick().copy(
                directionalMode = true,
                stickPlusMode = false,
                upCommand = "W",
                boostThreshold = .4f,
                superBoostThreshold = .8f
            ).captureStickDirectionalProfile(ID, "Keyboard", 10L)
        )
        val target = stick().copy(
            directionalMode = false,
            stickPlusMode = true,
            thresholdEnabled = true,
            threshold = .6f,
            thresholdPayload = "X360A"
        )

        assertTrue(profile.applyAndSwitchModeTo(target))
        assertTrue(target.directionalMode)
        assertFalse(target.stickPlusMode)
        assertTrue(target.thresholdEnabled)
        assertEquals(.6f, target.threshold)
        assertEquals("X360A", target.thresholdPayload)
    }

    @Test
    fun invalidProfileAndWrongTarget_areAtomicNoOps() {
        val valid = requireNotNull(
            stick().copy(stickPlusMode = true)
                .captureStickDirectionalProfile(ID, "Valid", 10L)
        )
        val invalid = valid.copy(
            settings = valid.settings.copy(superBoostThreshold = valid.settings.boostThreshold)
        )
        val target = stick()
        val before = target.copy()

        assertFalse(invalid.applyCommandsTo(target))
        assertEquals(before, target)
        assertFalse(valid.applyCommandsTo(button()))
    }

    @Test
    fun captureRequiresExactlyOneDirectionalMode() {
        assertNull(stick().captureStickDirectionalProfile(ID, "Neither", 10L))
        assertNull(
            stick().copy(directionalMode = true, stickPlusMode = true)
                .captureStickDirectionalProfile(ID, "Both", 10L)
        )
    }

    @Test
    fun versionOneProfile_withoutExtendedFieldsRemainsUsable() {
        val source = requireNotNull(
            stick().copy(stickPlusMode = true)
                .captureStickDirectionalProfile(ID, "Old", 10L)
        ).copy(schemaVersion = 1)
        val encoded = Json { encodeDefaults = false }
            .encodeToString(StickDirectionalProfile.serializer(), source)
        assertFalse(encoded.contains("extendedBoost"))

        val decoded = json.decodeFromString<StickDirectionalProfile>(encoded)
        val target = stick(ControlType.CURVED_STICK)
        assertTrue(decoded.applyCommandsTo(target))
        assertFalse(target.extendedBoost.enabled)
    }

    private fun stick(type: ControlType = ControlType.STICK) = Control(
        id = "stick",
        type = type,
        x = 1f,
        y = 2f,
        w = 300f,
        h = 300f,
        payload = "STICK_L"
    )

    private fun button() = Control(
        id = "button",
        type = ControlType.BUTTON,
        x = 0f,
        y = 0f,
        w = 120f,
        h = 120f,
        payload = "X360A"
    )

    private companion object {
        const val ID = "00000000-0000-0000-0000-000000000201"
    }
}
