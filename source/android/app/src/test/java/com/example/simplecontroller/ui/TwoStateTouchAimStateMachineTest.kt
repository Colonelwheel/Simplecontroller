package com.example.simplecontroller.ui

import com.example.simplecontroller.model.TouchAimShootBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoStateTouchAimStateMachineTest {
    @Test
    fun upwardAndDownwardTransitions_requireConfirmationAndHysteresis() {
        val machine = machine()
        machine.onContactStarted(0)
        assertTrue(machine.onScore(1.1f, 10).isEmpty())
        assertEquals(TwoStateTouchAimState.AIM, machine.state)
        assertEquals(listOf(TwoStateTouchAimAction.HOLD_SHOOT_PAYLOAD), machine.onScore(1.1f, 110))
        assertEquals(TwoStateTouchAimState.SHOOT, machine.state)

        assertTrue(machine.onScore(0.8f, 120).isEmpty())
        assertEquals(TwoStateTouchAimState.SHOOT, machine.state)
        assertTrue(machine.onScore(0.6f, 130).isEmpty())
        assertEquals(listOf(TwoStateTouchAimAction.RELEASE_SHOOT_PAYLOAD), machine.onScore(0.6f, 250))
        assertEquals(TwoStateTouchAimState.AIM, machine.state)
    }

    @Test
    fun holdWhileAboveThreshold_holdsThenReleases() {
        val machine = machine(enterMs = 0, returnMs = 0)
        assertEquals(listOf(TwoStateTouchAimAction.HOLD_AIM_PAYLOAD), machine.onContactStarted(0))
        assertEquals(listOf(TwoStateTouchAimAction.HOLD_SHOOT_PAYLOAD), machine.onScore(1.2f, 1))
        assertEquals(listOf(TwoStateTouchAimAction.RELEASE_SHOOT_PAYLOAD), machine.onScore(0.5f, 2))
    }

    @Test
    fun pressOnEnter_firesOnlyOnceForConfirmedEntry() {
        val machine = machine(TouchAimShootBehavior.PRESS_ON_ENTER, enterMs = 0, returnMs = 0)
        machine.onContactStarted(0)
        assertEquals(listOf(TwoStateTouchAimAction.PRESS_SHOOT_PAYLOAD), machine.onScore(1.2f, 1))
        assertTrue(machine.onScore(1.3f, 2).isEmpty())
        machine.onScore(0.5f, 3)
        assertEquals(listOf(TwoStateTouchAimAction.PRESS_SHOOT_PAYLOAD), machine.onScore(1.2f, 4))
    }

    @Test
    fun pressOnReturn_firesOncePerCompleteCycle() {
        val machine = machine(TouchAimShootBehavior.PRESS_ON_RETURN_TO_AIM, enterMs = 0, returnMs = 0)
        machine.onContactStarted(0)
        assertTrue(machine.onScore(1.2f, 1).isEmpty())
        assertEquals(listOf(TwoStateTouchAimAction.PRESS_SHOOT_PAYLOAD), machine.onScore(0.5f, 2))
        assertTrue(machine.onScore(0.4f, 3).isEmpty())
        assertTrue(machine.onScore(0.9f, 4).isEmpty())
        assertTrue(machine.onScore(1.2f, 5).isEmpty())
        assertEquals(listOf(TwoStateTouchAimAction.PRESS_SHOOT_PAYLOAD), machine.onScore(0.5f, 6))
        assertTrue(machine.onScore(0.4f, 7).isEmpty())
    }

    @Test
    fun stationaryScore_confirmsAtExposedDeadline() {
        val machine = machine(enterMs = 100, returnMs = 120)
        machine.onContactStarted(0)
        machine.onScore(1.2f, 10)
        assertEquals(110L, machine.nextDeadlineMs())
        assertEquals(listOf(TwoStateTouchAimAction.HOLD_SHOOT_PAYLOAD), machine.onTime(110))
        machine.onScore(0.5f, 150)
        assertEquals(270L, machine.nextDeadlineMs())
        assertEquals(listOf(TwoStateTouchAimAction.RELEASE_SHOOT_PAYLOAD), machine.onTime(270))
    }

    @Test
    fun pressOnReturn_fullLiftNeverFires() {
        val machine = machine(TouchAimShootBehavior.PRESS_ON_RETURN_TO_AIM, enterMs = 0, returnMs = 100)
        machine.onContactStarted(0)
        machine.onScore(1.2f, 1)
        machine.onScore(0.5f, 20)
        val actions = machine.terminate(TwoStateTouchAimTermination.FINGER_LIFT)
        assertFalse(actions.contains(TwoStateTouchAimAction.PRESS_SHOOT_PAYLOAD))
    }

    @Test
    fun everySafetyTermination_cancelsArmedReturnWithoutPress() {
        TwoStateTouchAimTermination.entries.forEach { reason ->
            val machine = machine(TouchAimShootBehavior.PRESS_ON_RETURN_TO_AIM, enterMs = 0, returnMs = 100)
            machine.onContactStarted(0)
            machine.onScore(1.2f, 1)
            machine.onScore(0.5f, 20)
            assertFalse(reason.name, machine.terminate(reason).contains(TwoStateTouchAimAction.PRESS_SHOOT_PAYLOAD))
            assertFalse(machine.contactActive)
        }
    }

    @Test
    fun thresholdChatter_doesNotProduceRepeatedShots() {
        val machine = machine(TouchAimShootBehavior.PRESS_ON_ENTER, enterMs = 100, returnMs = 100)
        machine.onContactStarted(0)
        assertTrue(machine.onScore(1.1f, 10).isEmpty())
        assertTrue(machine.onScore(0.9f, 50).isEmpty())
        assertTrue(machine.onScore(1.1f, 80).isEmpty())
        assertTrue(machine.onScore(0.95f, 120).isEmpty())
        assertEquals(TwoStateTouchAimState.AIM, machine.state)
    }

    @Test
    fun aimPayloadCanPauseDuringShootAndReturnAfterConfirmedRelaxation() {
        val machine = machine(
            behavior = TouchAimShootBehavior.HOLD_WHILE_ABOVE,
            enterMs = 0,
            returnMs = 0,
            keepAim = false
        )
        machine.onContactStarted(0)
        assertEquals(
            listOf(
                TwoStateTouchAimAction.RELEASE_AIM_PAYLOAD,
                TwoStateTouchAimAction.HOLD_SHOOT_PAYLOAD
            ),
            machine.onScore(1.2f, 1)
        )
        assertEquals(
            listOf(
                TwoStateTouchAimAction.RELEASE_SHOOT_PAYLOAD,
                TwoStateTouchAimAction.HOLD_AIM_PAYLOAD
            ),
            machine.onScore(0.5f, 2)
        )
    }

    private fun machine(
        behavior: TouchAimShootBehavior = TouchAimShootBehavior.HOLD_WHILE_ABOVE,
        enterMs: Long = 100,
        returnMs: Long = 100,
        keepAim: Boolean = true
    ) = TwoStateTouchAimStateMachine(
        TwoStateTouchAimConfig(
            shootOnThreshold = 1f,
            shootOffThreshold = 0.7f,
            smoothing = 1f,
            enterShootConfirmationMs = enterMs,
            returnToAimConfirmationMs = returnMs,
            shootBehavior = behavior,
            hasAimPayload = true,
            keepAimPayloadWhileShooting = keepAim,
            hasShootPayload = true
        )
    )
}
