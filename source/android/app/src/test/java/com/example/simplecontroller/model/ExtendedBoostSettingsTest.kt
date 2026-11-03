package com.example.simplecontroller.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtendedBoostSettingsTest {
    @Test
    fun disabledSettings_preserveSharedThresholdSelection() {
        val control = stick().copy(boostThreshold = .5f, superBoostThreshold = .75f)
        assertEquals("normal", control.directionalPayload(StickDirection.UP, .5f))
        assertEquals("boost", control.directionalPayload(StickDirection.UP, .51f))
        assertEquals("super", control.directionalPayload(StickDirection.UP, .76f))
    }

    @Test
    fun replacementMode_usesSeparateDirectionsBeyondStickEdge() {
        val control = stick().copy(extendedBoost = ExtendedBoostSettings(
            enabled = true,
            regular = DirectionalThresholds(1.4f, 1.2f, .8f, 1.8f),
            superBoost = DirectionalThresholds(1.8f, 1.6f, 1.2f, 2.2f)
        ))
        assertEquals("normal", control.directionalPayload(StickDirection.UP, 1.4f))
        assertEquals("boost", control.directionalPayload(StickDirection.UP, 1.41f))
        assertEquals("super", control.directionalPayload(StickDirection.UP, 1.81f))
        assertEquals("normal", control.directionalPayload(StickDirection.RIGHT, 1.8f))
        assertEquals("boost", control.directionalPayload(StickDirection.LEFT, .9f))
    }

    @Test
    fun outerMode_usesItsOwnCommandsAfterSharedStages() {
        val control = stick().copy(extendedBoost = ExtendedBoostSettings(
            enabled = true,
            mode = ExtendedBoostMode.ADD_OUTER_STAGE,
            outer = DirectionalThresholds(1.3f, 1.5f, 1.7f, 1.9f),
            outerCommands = DirectionalCommands(up = "jump", right = "dash")
        ))
        assertEquals("boost", control.directionalPayload(StickDirection.UP, .6f))
        assertEquals("super", control.directionalPayload(StickDirection.UP, 1.3f))
        assertEquals("jump", control.directionalPayload(StickDirection.UP, 1.31f))
        assertEquals("super", control.directionalPayload(StickDirection.DOWN, 1.51f))
        assertEquals("dash", control.directionalPayload(StickDirection.RIGHT, 1.91f))
    }

    @Test
    fun extendedSettings_rejectUnreachableOrReversedStages() {
        assertNull(ExtendedBoostSettings().validationError(.75f))
        val invalid = ExtendedBoostSettings(
            enabled = true,
            regular = DirectionalThresholds(1.5f, .5f, .5f, .5f),
            superBoost = DirectionalThresholds(1.4f, .75f, .75f, .75f)
        )
        assertEquals(
            "Each Super Boost distance must exceed its Regular Boost distance.",
            invalid.validationError(.75f)
        )
    }

    private fun stick() = Control(
        id = "stick", type = ControlType.STICK,
        x = 0f, y = 0f, w = 200f, h = 200f, payload = "STICK_L",
        upCommand = "normal", upBoostCommand = "boost", upSuperBoostCommand = "super",
        downSuperBoostCommand = "super", leftBoostCommand = "boost",
        rightCommand = "normal", rightBoostCommand = "boost",
        rightSuperBoostCommand = "super"
    )
}
