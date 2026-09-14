package com.example.simplecontroller.model

import com.example.simplecontroller.io.TouchAimCalibrationCatalog
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchAimCalibrationProfileTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun oldTouchAimLayout_defaultsToManualThreeStage() {
        val oldJson = """
            {
              "id":"touch_old","type":"TOUCH_AIM","x":1.0,"y":2.0,
              "w":500.0,"h":320.0,"payload":"TOUCH_AIM",
              "touchMediumThreshold":4.25,"touchHighPayload":"RT:1.0"
            }
        """.trimIndent()
        val control = json.decodeFromString<Control>(oldJson)
        assertEquals(TouchAimMode.MANUAL_THREE_STAGE, control.touchAimMode)
        assertEquals(4.25f, control.touchMediumThreshold)
        assertEquals("RT:1.0", control.touchHighPayload)
        assertTrue(control.touchAimSensorTransforms.isEmpty())
        assertFalse(control.touchAimManualThresholdsInitialized)
        assertEquals(5.2f, control.touchAimManualShootOnThreshold)
        assertEquals(4.95f, control.touchAimManualShootOffThreshold)
        assertTrue(control.touchAimStickUsesTouchPosition)
        assertEquals(220f, control.touchAimStickFullDisplacementPx)
        assertEquals(8f, control.touchAimStickDeadzonePx)
    }

    @Test
    fun calibratedControl_roundTripsSeparateShootSensitivity() {
        val control = control().copy(
            touchAimMode = TouchAimMode.CALIBRATED_TWO_STATE,
            touchAimShootSensitivity = 0.42f,
            touchAimStickUsesTouchPosition = false,
            touchAimStickFullDisplacementPx = 175f,
            touchAimStickDeadzonePx = 4f,
            touchAimShootBehavior = TouchAimShootBehavior.PRESS_ON_RETURN_TO_AIM,
            touchAimSensorTransforms = listOf(
                TouchAimSensorTransform(TouchAimSensor.TOUCH_MAJOR, 3f, 1.2f)
            )
        )
        val decoded = json.decodeFromString<Control>(json.encodeToString(Control.serializer(), control))
        assertEquals(0.42f, decoded.touchAimShootSensitivity)
        assertFalse(decoded.touchAimStickUsesTouchPosition)
        assertEquals(175f, decoded.touchAimStickFullDisplacementPx)
        assertEquals(4f, decoded.touchAimStickDeadzonePx)
        assertEquals(TouchAimShootBehavior.PRESS_ON_RETURN_TO_AIM, decoded.touchAimShootBehavior)
        assertEquals(TouchAimSensor.TOUCH_MAJOR, decoded.touchAimSensorTransforms.single().sensor)
    }

    @Test
    fun manualTwoStateControl_roundTripsWithoutCalibrationTransforms() {
        val control = control().copy(
            touchAimMode = TouchAimMode.MANUAL_TWO_STATE,
            touchUseSize = true,
            touchUseMajor = false,
            touchUseMinor = true,
            touchAimManualThresholdsInitialized = true,
            touchAimManualShootOnThreshold = 6.25f,
            touchAimManualShootOffThreshold = 5.75f,
            touchAimSensorTransforms = emptyList()
        )
        val decoded = json.decodeFromString<Control>(json.encodeToString(Control.serializer(), control))
        assertEquals(TouchAimMode.MANUAL_TWO_STATE, decoded.touchAimMode)
        assertTrue(decoded.touchUseSize)
        assertFalse(decoded.touchUseMajor)
        assertTrue(decoded.touchUseMinor)
        assertTrue(decoded.touchAimManualThresholdsInitialized)
        assertEquals(6.25f, decoded.touchAimManualShootOnThreshold)
        assertEquals(5.75f, decoded.touchAimManualShootOffThreshold)
        assertTrue(decoded.touchAimSensorTransforms.isEmpty())
    }

    @Test
    fun profileApply_copiesSnapshotWithoutDependingOnSavedFile() {
        val control = control().copy(
            touchAimManualShootOnThreshold = 7f,
            touchAimManualShootOffThreshold = 6.5f
        )
        val profile = profile("00000000-0000-0000-0000-000000000001", "Bottom", 10)
        profile.applyTo(control)
        assertEquals(TouchAimMode.CALIBRATED_TWO_STATE, control.touchAimMode)
        assertEquals(profile.id, control.touchAimAppliedCalibrationId)
        assertEquals(0.55f, control.touchAimShootSensitivity)
        assertFalse(control.touchAimStickUsesTouchPosition)
        assertEquals(180f, control.touchAimStickFullDisplacementPx)
        assertEquals(5f, control.touchAimStickDeadzonePx)
        assertEquals("RT:1.0", control.touchAimShootPayload)
        assertEquals(7f, control.touchAimManualShootOnThreshold)
        assertEquals(6.5f, control.touchAimManualShootOffThreshold)
    }

    @Test
    fun unreliableOrInvalidProfile_cannotMutateControl() {
        val control = control()
        assertFalse(profile("00000000-0000-0000-0000-000000000001", "Bad", 10)
            .copy(quality = TouchAimCalibrationQuality.UNRELIABLE).applyTo(control))
        assertEquals(TouchAimMode.MANUAL_THREE_STAGE, control.touchAimMode)
        assertFalse(profile("00000000-0000-0000-0000-000000000002", "Equal", 10)
            .copy(shootOffThreshold = 1.2f).applyTo(control))
        assertEquals(TouchAimMode.MANUAL_THREE_STAGE, control.touchAimMode)
    }

    @Test
    fun unreliableBestGuess_requiresExplicitOverrideAndRemainsEditable() {
        val profile = profile("00000000-0000-0000-0000-000000000003", "Best guess", 10)
            .copy(quality = TouchAimCalibrationQuality.UNRELIABLE)
        val control = control()

        assertFalse(profile.applyTo(control))
        assertTrue(profile.applyTo(control, allowUnreliableBestGuess = true))
        assertEquals(TouchAimMode.CALIBRATED_TWO_STATE, control.touchAimMode)

        control.touchAimShootOnThreshold = 1.35f
        assertEquals(1.35f, control.touchAimShootOnThreshold)
    }

    @Test
    fun savedProfile_roundTripsAllRuntimeSettings() {
        val original = profile("00000000-0000-0000-0000-000000000001", "Bottom", 10)
            .copy(validationCompleted = true, validationUnexpectedTransitions = 2)
        val decoded = json.decodeFromString<TouchAimCalibrationProfile>(
            json.encodeToString(TouchAimCalibrationProfile.serializer(), original)
        )
        assertEquals(original, decoded)
    }

    @Test
    fun catalogSupportsSaveLoadRenameDuplicateDeleteAndMigration() {
        val first = profile("00000000-0000-0000-0000-000000000001", "Bottom", 10)
        var profiles = TouchAimCalibrationCatalog.upsert(emptyList(), first)
        assertEquals(first, profiles.single())

        profiles = requireNotNull(TouchAimCalibrationCatalog.rename(profiles, first.id, "Reclined", 20))
        assertEquals("Reclined", profiles.single().name)
        assertNull(TouchAimCalibrationCatalog.rename(profiles, first.id, "", 30))

        val copyId = "00000000-0000-0000-0000-000000000002"
        profiles = requireNotNull(
            TouchAimCalibrationCatalog.duplicate(profiles, first.id, copyId, "Reclined copy", 30)
        )
        assertEquals(2, profiles.size)
        assertEquals(30L, profiles.first { it.id == copyId }.createdAtEpochMs)

        profiles = TouchAimCalibrationCatalog.delete(profiles, first.id)
        assertEquals(listOf(copyId), profiles.map { it.id })
        assertNotNull(TouchAimCalibrationCatalog.migrated(profiles.single()))
        assertNull(
            TouchAimCalibrationCatalog.migrated(
                profiles.single().copy(schemaVersion = TouchAimCalibrationProfile.CURRENT_SCHEMA_VERSION + 1)
            )
        )
    }

    private fun control() = Control(
        id = "touch",
        type = ControlType.TOUCH_AIM,
        x = 0f,
        y = 0f,
        w = 500f,
        h = 320f,
        payload = "TOUCH_AIM"
    )

    private fun profile(id: String, name: String, time: Long) = TouchAimCalibrationProfile(
        id = id,
        name = name,
        createdAtEpochMs = time,
        updatedAtEpochMs = time,
        sensors = listOf(TouchAimSensorTransform(TouchAimSensor.SIZE, 0.02f, 0.01f)),
        smoothing = 0.3f,
        shootOnThreshold = 1.2f,
        shootOffThreshold = 0.9f,
        enterShootConfirmationMs = 90,
        returnToAimConfirmationMs = 120,
        quality = TouchAimCalibrationQuality.GOOD,
        estimatedFalseActivationRate = 0f,
        estimatedMissedActivationRate = 0f,
        movementReducedReliability = false,
        consistencyScore = 1f,
        validationCompleted = true,
        passSummaries = emptyList(),
        aimOutput = TouchAimOutput.RIGHT_STICK,
        aimSensitivity = 1f,
        stickUsesTouchPosition = false,
        stickFullDisplacementPx = 180f,
        stickDeadzonePx = 5f,
        shootSensitivity = 0.55f,
        aimPayload = "LT:1.0",
        shootPayload = "RT:1.0",
        keepAimPayloadWhileShooting = true,
        shootBehavior = TouchAimShootBehavior.HOLD_WHILE_ABOVE
    )
}
