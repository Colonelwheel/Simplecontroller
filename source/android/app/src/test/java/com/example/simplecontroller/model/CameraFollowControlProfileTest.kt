package com.example.simplecontroller.model

import com.example.simplecontroller.io.decodeControllerProfile
import com.example.simplecontroller.io.encodeControllerProfile
import com.example.simplecontroller.io.validateControllerProfileForSave
import org.junit.Assert.*
import org.junit.Test

class CameraFollowControlProfileTest {
    private fun control(type: ControlType = ControlType.BUTTON) = Control("a", type, 0f, 0f, 200f, 200f, "CAMERA_FOLLOW")
    private fun profile(control: Control) = ControllerProfile(homePageId = "base", pages = listOf(ControllerPage("base", "Base", listOf(control))))
    @Test fun legacyDefaults_keepExistingFormatAndBehavior() {
        val original = control()
        assertFalse(original.excludeFromCameraFollow)
        assertEquals(100f, original.cameraFollowStrengthPercent)
        assertEquals(5, profile(original).requiredFormatVersion())
    }
    @Test fun changedStrength_roundTripsFormatEight() {
        val original = control().copy(cameraFollowStrengthPercent = 125f)
        val saved = encodeControllerProfile(profile(original))
        val decoded = decodeControllerProfile(saved, "Strength").profile
        assertEquals(8, decoded.formatVersion)
        assertEquals(125f, decoded.pages.single().controls.single().cameraFollowStrengthPercent)
    }
    @Test fun exclusions_onEveryMovementControlRoundTrip() {
        for (type in listOf(ControlType.STICK, ControlType.CURVED_STICK, ControlType.RADIAL_CURVED_STICK, ControlType.TOUCH_AIM, ControlType.BUTTON)) {
            val original = control(type).copy(excludeFromCameraFollow = true)
            val decoded = decodeControllerProfile(encodeControllerProfile(profile(original)), "Excluded").profile
            assertEquals(8, decoded.formatVersion)
            assertTrue(decoded.pages.single().controls.single().excludeFromCameraFollow)
        }
    }
    @Test fun restoringDefaultStrength_andInclusionKeepsEarlierMouseFormats() {
        assertEquals(6, profile(control(ControlType.TOUCH_AIM).copy(touchAimGameMouse = true)).requiredFormatVersion())
        assertEquals(7, profile(control().copy(buttonAimOutput = ButtonAimOutput.GAME_MOUSE)).requiredFormatVersion())
    }
    @Test fun outOfRangeSavedStrength_isRejected() {
        assertNotNull(validateControllerProfileForSave(profile(control().copy(cameraFollowStrengthPercent = 201f))))
        assertNotNull(validateControllerProfileForSave(profile(control().copy(cameraFollowStrengthPercent = Float.NaN))))
    }
    @Test fun reusableButtonAimProfiles_preserveTargetParticipation() {
        val saved = control().captureButtonAimProfile("saved", "Aim", 1)!!
        for (excluded in listOf(false, true)) {
            val target = control().copy(excludeFromCameraFollow = excluded, cameraFollowStrengthPercent = 125f)
            assertTrue(saved.applyAimTuningTo(target))
            assertEquals(excluded, target.excludeFromCameraFollow)
            assertTrue(saved.applyCompleteTo(target))
            assertEquals(excluded, target.excludeFromCameraFollow)
            assertEquals(125f, target.cameraFollowStrengthPercent)
        }
    }
    @Test fun reusableManualAndDirectionalProfiles_preserveTargetParticipation() {
        val manual = control(ControlType.TOUCH_AIM).captureManualTouchAimProfile("manual", "Manual", 1)!!
        val directional = control(ControlType.STICK).copy(directionalMode = true).captureStickDirectionalProfile("stick", "Stick", 1)!!
        for (excluded in listOf(false, true)) {
            val touch = control(ControlType.TOUCH_AIM).copy(excludeFromCameraFollow = excluded)
            assertTrue(manual.applyTo(touch))
            assertEquals(excluded, touch.excludeFromCameraFollow)
            val stick = control(ControlType.STICK).copy(excludeFromCameraFollow = excluded)
            assertTrue(directional.applyCommandsTo(stick))
            assertTrue(directional.applyAndSwitchModeTo(stick))
            assertEquals(excluded, stick.excludeFromCameraFollow)
        }
    }
}
