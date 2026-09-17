package com.example.simplecontroller.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlSettingsProfilesTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun legacyButtonAimTuning_withoutDpadFields_usesSafeDefaults() {
        val legacy = """
            {
              "output":"RIGHT_STICK",
              "sensitivity":1.0,
              "invertY":false,
              "stickProfile":"LINEAR",
              "mouseProfile":"SMOOTHED_NONLINEAR",
              "stickFullDisplacementPx":220.0,
              "stickDeadzonePx":8.0,
              "stickUsesTouchPosition":false,
              "haptics":true
            }
        """.trimIndent()

        val tuning = json.decodeFromString(ButtonAimTuning.serializer(), legacy)

        assertEquals(ButtonAimOutput.RIGHT_STICK, tuning.output)
        assertEquals(ButtonAimDpadMode.EIGHT_WAY, tuning.dpadMode)
        assertEquals(ButtonAimDpadOrigin.CONTROL_CENTER, tuning.dpadOrigin)
        assertEquals(8f, tuning.dpadActivationDistancePx)
    }

    @Test
    fun manualThreeStage_captureRoundTripApply_copiesManualSnapshotOnly() {
        val source = touchAim().copy(
            touchAimMode = TouchAimMode.MANUAL_THREE_STAGE,
            touchAimOutput = TouchAimOutput.RIGHT_STICK,
            sensitivity = .72f,
            touchUseSize = false,
            touchUseMajor = true,
            touchUseMinor = false,
            touchSizeScale = 80f,
            touchScoreSmoothing = .4f,
            touchLowThreshold = 3f,
            touchMediumThreshold = 5f,
            touchHighThreshold = 7f,
            touchHysteresis = .4f,
            touchLowPayload = "X360A",
            touchMediumPayload = "LT:1.0",
            touchHighPayload = "RT:1.0",
            touchLowAction = TouchStageAction.PRESS,
            touchMediumAction = TouchStageAction.HOLD,
            touchHighAction = TouchStageAction.PRESS,
            touchKeepLowerHolds = false,
            touchStickFullSpeed = 750f,
            touchInvertY = true,
            touchUseResponseCurve = true,
            touchAimStickUsesTouchPosition = false,
            touchAimStickFullDisplacementPx = 165f,
            touchAimStickDeadzonePx = 3f
        )
        val captured = requireNotNull(source.captureManualTouchAimProfile(ID1, "Three", 10L))
        val profile = json.decodeFromString<TouchAimManualProfile>(
            json.encodeToString(TouchAimManualProfile.serializer(), captured)
        )
        val target = touchAim().copy(
            id = "target",
            x = 91f,
            y = 92f,
            w = 600f,
            h = 400f,
            payload = "TOUCH_AIM_KEEP",
            touchAimAppliedCalibrationId = "calibration",
            touchAimAppliedCalibrationName = "Old",
            touchAimSensorTransforms = listOf(TouchAimSensorTransform(TouchAimSensor.SIZE, 1f, 1f))
        )

        assertTrue(profile.applyTo(target))
        assertEquals(TouchAimMode.MANUAL_THREE_STAGE, target.touchAimMode)
        assertEquals(3f, target.touchLowThreshold)
        assertEquals(5f, target.touchMediumThreshold)
        assertEquals(7f, target.touchHighThreshold)
        assertEquals("RT:1.0", target.touchHighPayload)
        assertEquals(TouchStageAction.PRESS, target.touchHighAction)
        assertEquals(TouchAimOutput.RIGHT_STICK, target.touchAimOutput)
        assertEquals(.72f, target.sensitivity)
        assertTrue(target.touchUseResponseCurve)
        assertFalse(target.touchAimStickUsesTouchPosition)
        assertEquals(165f, target.touchAimStickFullDisplacementPx)
        assertEquals(3f, target.touchAimStickDeadzonePx)
        assertEquals("target", target.id)
        assertEquals(91f, target.x)
        assertEquals(600f, target.w)
        assertEquals("TOUCH_AIM_KEEP", target.payload)
        assertEquals("", target.touchAimAppliedCalibrationId)
        assertTrue(target.touchAimSensorTransforms.isEmpty())
    }

    @Test
    fun manualTwoState_captureApply_resolvesAndInitializesManualThresholds() {
        val source = touchAim().copy(
            touchAimMode = TouchAimMode.MANUAL_TWO_STATE,
            touchUseSize = true,
            touchUseMajor = false,
            touchUseMinor = false,
            touchSizeScale = 100f,
            touchAimManualThresholdsInitialized = false,
            touchHighThreshold = 6f,
            touchHysteresis = .5f,
            touchAimTwoStateSmoothing = .25f,
            touchAimEnterShootConfirmationMs = 80L,
            touchAimReturnToAimConfirmationMs = 110L,
            touchAimAimPayload = "LT:1.0",
            touchAimShootPayload = "RT:1.0",
            touchAimShootSensitivity = .45f,
            touchAimShootBehavior = TouchAimShootBehavior.PRESS_ON_RETURN_TO_AIM
        )
        val captured = requireNotNull(source.captureManualTouchAimProfile(ID1, "Two", 10L))
        val profile = json.decodeFromString<TouchAimManualProfile>(
            json.encodeToString(TouchAimManualProfile.serializer(), captured)
        )
        assertEquals(captured, profile)
        assertEquals(6f, profile.twoState?.shootOnThreshold)
        assertEquals(5.5f, profile.twoState?.shootOffThreshold)
        val target = touchAim()

        assertTrue(profile.applyTo(target))
        assertEquals(TouchAimMode.MANUAL_TWO_STATE, target.touchAimMode)
        assertTrue(target.touchAimManualThresholdsInitialized)
        assertEquals(6f, target.touchAimManualShootOnThreshold)
        assertEquals(5.5f, target.touchAimManualShootOffThreshold)
        assertEquals(.45f, target.touchAimShootSensitivity)
        assertEquals(TouchAimShootBehavior.PRESS_ON_RETURN_TO_AIM, target.touchAimShootBehavior)
    }

    @Test
    fun invalidManualProfile_isAtomicNoOp() {
        val valid = requireNotNull(touchAim().captureManualTouchAimProfile(ID1, "Valid", 10L))
        val invalid = valid.copy(aimSensitivity = Float.NaN)
        val target = touchAim().copy(name = "unchanged")
        val before = target.copy()

        assertFalse(invalid.applyTo(target))
        assertEquals(before, target)
    }

    @Test
    fun manualTwoStateProfile_rejectsIrreversibleStatePayload() {
        val valid = requireNotNull(
            touchAim().copy(
                touchAimMode = TouchAimMode.MANUAL_TWO_STATE,
                touchAimAimPayload = "LT:1.0",
                touchAimShootPayload = "RT:1.0"
            ).captureManualTouchAimProfile(ID1, "Valid", 10L)
        )
        val invalid = valid.copy(
            twoState = requireNotNull(valid.twoState).copy(shootPayload = "RELEASE_ALL")
        )
        val target = touchAim()
        val before = target.copy()

        assertNotNull(invalid.validationError())
        assertFalse(invalid.applyTo(target))
        assertEquals(before, target)
    }

    @Test
    fun buttonAimProfile_aimingOnlyRoundTripsBothTuningsAndPreservesActions() {
        val source = button().copy(
            payload = "LT:1.0",
            holdToggle = false,
            holdDurationMs = 640L,
            buttonAimPayloadTiming = ButtonAimPayloadTiming.IMMEDIATE,
            buttonAimReleaseDelayMs = 35L,
            buttonAimOutput = ButtonAimOutput.RIGHT_STICK,
            buttonAimSensitivity = .65f,
            buttonAimInvertY = true,
            buttonAimStickProfile = ButtonAimStickProfile.RESPONSE_CURVE,
            buttonAimMouseProfile = ButtonAimMouseProfile.LINEAR_RELATIVE,
            buttonAimStickFullDisplacementPx = 170f,
            buttonAimStickDeadzonePx = 4f,
            buttonAimStickUsesTouchPosition = true,
            buttonAimDpadMode = ButtonAimDpadMode.FOUR_WAY,
            buttonAimDpadOrigin = ButtonAimDpadOrigin.INITIAL_TOUCH,
            buttonAimDpadActivationDistancePx = 14f,
            buttonAimHaptics = false,
            buttonAimAlternateOutput = ButtonAimOutput.LEFT_STICK,
            buttonAimAlternateSensitivity = .4f,
            buttonAimAlternateInvertY = true,
            buttonAimAlternateStickProfile = ButtonAimStickProfile.RESPONSE_CURVE,
            buttonAimAlternateMouseProfile = ButtonAimMouseProfile.LINEAR_RELATIVE,
            buttonAimAlternateStickFullDisplacementPx = 140f,
            buttonAimAlternateStickDeadzonePx = 3f,
            buttonAimAlternateStickUsesTouchPosition = true,
            buttonAimAlternateDpadMode = ButtonAimDpadMode.FOUR_WAY,
            buttonAimAlternateDpadOrigin = ButtonAimDpadOrigin.INITIAL_TOUCH,
            buttonAimAlternateDpadActivationDistancePx = 17f,
            buttonAimAlternateHaptics = false,
            buttonAimOneShotAlternateEnabled = true,
            buttonAimAlternatePayload = "RT:1.0",
            buttonAimAlternatePayloadTiming = ButtonAimPayloadTiming.SEND_ON_RELEASE,
            buttonAimAlternateResetHoldDurationMs = 1750L,
            buttonAimAlternateBaseUnlatchDelayMs = 90L,
            buttonAimAlternateDisplayName = "Shoot"
        )
        val captured = requireNotNull(source.captureButtonAimProfile(ID2, "Precision", 20L))
        val profile = json.decodeFromString<ButtonAimProfile>(
            json.encodeToString(ButtonAimProfile.serializer(), captured)
        )
        assertEquals(captured, profile)
        val target = button().copy(
            payload = "RT:1.0",
            holdToggle = true,
            autoTapEnabled = true,
            buttonAimPayloadTiming = ButtonAimPayloadTiming.SEND_ON_RELEASE,
            buttonAimOneShotAlternateEnabled = true,
            buttonAimAlternatePayload = "X360A"
        )

        assertTrue(profile.applyAimTuningTo(target))
        assertTrue(target.buttonAimEnabled)
        assertFalse(target.autoTapEnabled)
        assertEquals(ButtonAimOutput.RIGHT_STICK, target.buttonAimOutput)
        assertEquals(ButtonAimStickProfile.RESPONSE_CURVE, target.buttonAimStickProfile)
        assertEquals(170f, target.buttonAimStickFullDisplacementPx)
        assertEquals(4f, target.buttonAimStickDeadzonePx)
        assertEquals(ButtonAimDpadMode.FOUR_WAY, target.buttonAimDpadMode)
        assertEquals(ButtonAimDpadOrigin.INITIAL_TOUCH, target.buttonAimDpadOrigin)
        assertEquals(14f, target.buttonAimDpadActivationDistancePx)
        assertEquals(.4f, target.buttonAimAlternateSensitivity)
        assertEquals("RT:1.0", target.payload)
        assertTrue(target.holdToggle)
        assertEquals(ButtonAimPayloadTiming.SEND_ON_RELEASE, target.buttonAimPayloadTiming)
        assertTrue(target.buttonAimOneShotAlternateEnabled)
        assertEquals("X360A", target.buttonAimAlternatePayload)
    }

    @Test
    fun buttonAimProfile_completeApplyRestoresActionsButPreservesIdentityAndGeometry() {
        val source = button().copy(
            payload = "LT:1.0",
            holdToggle = true,
            holdDurationMs = 680L,
            buttonAimPayloadTiming = ButtonAimPayloadTiming.SEND_ON_RELEASE,
            buttonAimReleaseDelayMs = 45L,
            buttonAimOneShotAlternateEnabled = true,
            buttonAimAlternatePayload = "RT:1.0",
            buttonAimAlternatePayloadTiming = ButtonAimPayloadTiming.IMMEDIATE,
            buttonAimAlternateResetHoldDurationMs = 1600L,
            buttonAimAlternateBaseUnlatchDelayMs = 75L,
            buttonAimAlternateDisplayName = "Shoot",
            buttonAimSensitivity = .55f
        )
        val profile = requireNotNull(source.captureButtonAimProfile(ID2, "Complete", 20L))
        val target = button().copy(
            id = "target",
            name = "ADS island",
            x = 90f,
            y = 80f,
            w = 240f,
            h = 180f,
            payload = "X360A",
            swipeActivate = false,
            autoTapEnabled = true,
            autoTapIntervalMs = 44L
        )

        assertTrue(profile.applyCompleteTo(target))
        assertEquals("LT:1.0", target.payload)
        assertTrue(target.holdToggle)
        assertEquals(680L, target.holdDurationMs)
        assertEquals(ButtonAimPayloadTiming.SEND_ON_RELEASE, target.buttonAimPayloadTiming)
        assertEquals(45L, target.buttonAimReleaseDelayMs)
        assertTrue(target.buttonAimOneShotAlternateEnabled)
        assertEquals("RT:1.0", target.buttonAimAlternatePayload)
        assertEquals("Shoot", target.buttonAimAlternateDisplayName)
        assertEquals(.55f, target.buttonAimSensitivity)
        assertTrue(target.buttonAimEnabled)
        assertFalse(target.autoTapEnabled)
        assertEquals("target", target.id)
        assertEquals("ADS island", target.name)
        assertEquals(90f, target.x)
        assertEquals(240f, target.w)
        assertFalse(target.swipeActivate)
        assertEquals(44L, target.autoTapIntervalMs)
    }

    @Test
    fun buttonAimProfile_allowsAimOnlySurfaceButRequiresBaseActionForOneShot() {
        val aimOnly = button().copy(payload = "")
        assertNotNull(aimOnly.captureButtonAimProfile(ID2, "Aim only", 20L))

        val incompleteOneShot = aimOnly.copy(
            buttonAimOneShotAlternateEnabled = true,
            buttonAimAlternatePayload = "RT:1.0"
        )
        assertEquals(null, incompleteOneShot.captureButtonAimProfile(ID2, "Incomplete", 20L))
    }

    @Test
    fun invalidButtonProfileAndWrongTarget_areNoOps() {
        val valid = requireNotNull(button().captureButtonAimProfile(ID2, "Aim", 20L))
        val invalid = valid.copy(base = valid.base.copy(stickDeadzonePx = 300f))
        val target = button()
        val before = target.copy()

        assertFalse(invalid.applyAimTuningTo(target))
        assertEquals(before, target)
        assertFalse(valid.applyAimTuningTo(touchAim()))
        assertFalse(valid.applyCompleteTo(touchAim()))
    }

    private fun touchAim() = Control(
        id = "touch",
        type = ControlType.TOUCH_AIM,
        x = 1f,
        y = 2f,
        w = 500f,
        h = 320f,
        payload = "TOUCH_AIM"
    )

    private fun button() = Control(
        id = "button",
        type = ControlType.BUTTON,
        x = 3f,
        y = 4f,
        w = 140f,
        h = 140f,
        payload = "X360A"
    )

    private companion object {
        const val ID1 = "00000000-0000-0000-0000-000000000101"
        const val ID2 = "00000000-0000-0000-0000-000000000102"
    }
}
