package com.example.simplecontroller.ui

import com.example.simplecontroller.model.*
import com.example.simplecontroller.net.orderedStickCommand
import org.junit.Assert.*
import org.junit.Test

class CameraFollowAimConfigTest {
    private fun control(type: ControlType) = Control("a", type, 0f, 0f, 200f, 200f, "STICK_L")
    @Test fun buttonBaseAndAlternate_captureExclusionWithoutChangingOutputs() {
        val source = control(ControlType.BUTTON).copy(excludeFromCameraFollow = true,
            buttonAimOutput = ButtonAimOutput.LEFT_STICK, buttonAimAlternateOutput = ButtonAimOutput.RIGHT_STICK)
        assertTrue(source.buttonAimConfig().excludeFromCameraFollow)
        assertTrue(source.buttonAimAlternateConfig().excludeFromCameraFollow)
        assertEquals(TouchAimOutput.LEFT_STICK, source.buttonAimConfig().output)
        assertEquals(TouchAimOutput.RIGHT_STICK, source.buttonAimAlternateConfig().output)
    }
    @Test fun capturedTouchConfig_retainsParticipationWhenModelChanges() {
        val source = control(ControlType.TOUCH_AIM).copy(excludeFromCameraFollow = true, touchAimOutput = TouchAimOutput.LEFT_STICK)
        val gesture = source.touchAimConfig()
        source.excludeFromCameraFollow = false
        assertTrue(gesture.excludeFromCameraFollow)
        assertFalse(source.touchAimConfig().excludeFromCameraFollow)
    }
    @Test fun retainedVector_keepsCapturedExclusionAndCanonicalOwnership() {
        val source = control(ControlType.BUTTON).copy(excludeFromCameraFollow = true, buttonAimOutput = ButtonAimOutput.LEFT_STICK)
        val gesture = source.buttonAimConfig()
        val registry = ManualStickOwnershipRegistry()
        val owner = Any()
        registry.acquire("STICK_L", owner, ManualStickOwnerState.ACTIVE, false) {}
        registry.updateState("STICK_L", owner, ManualStickOwnerState.RETAINED)
        assertTrue(registry.hasRetainedOwner("STICK_L"))
        assertEquals("STICK_L_NO_FOLLOW:session:5:0.50,0.00", orderedStickCommand("STICK_L", false,
            gesture.excludeFromCameraFollow, "session", 5, .5f, 0f))
        val nextOwner = Any()
        val transition = registry.acquire("STICK_L", nextOwner, ManualStickOwnerState.ACTIVE, true) {}
        assertTrue(transition.first.replacedRetainedOwner)
        assertFalse(registry.owns("STICK_L", owner))
        assertEquals("STICK_L:session:6:0.75,0.00", orderedStickCommand("STICK_L", false,
            false, "session", 6, .75f, 0f))
    }
}
