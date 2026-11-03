package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ButtonAimDpadMode
import com.example.simplecontroller.model.ButtonAimDpadOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonAimDpadSessionTest {
    @Test
    fun controlCenterOrigin_tapRightImmediatelyHoldsRight() {
        val fixture = Fixture()
        fixture.begin(origin = ButtonAimDpadOrigin.CONTROL_CENTER)

        assertTrue(fixture.session.update(7, 90f, 50f))
        assertEquals(
            listOf(ButtonAimDpadDirection.RIGHT),
            fixture.session.currentDirections()
        )
        assertEquals(listOf("X360RIGHT_HOLD"), fixture.transport.commands)

        fixture.session.finish()
        assertEquals(
            listOf("X360RIGHT_HOLD", "X360RIGHT_RELEASE"),
            fixture.transport.commands
        )
    }

    @Test
    fun initialTouchOrigin_startsNeutralThenUsesMovement() {
        val fixture = Fixture()
        fixture.begin(origin = ButtonAimDpadOrigin.INITIAL_TOUCH, x = 80f, y = 50f)

        assertTrue(fixture.session.update(7, 80f, 50f))
        assertTrue(fixture.transport.commands.isEmpty())

        assertTrue(fixture.session.update(7, 80f, 25f))
        assertEquals(listOf("X360UP_HOLD"), fixture.transport.commands)
    }

    @Test
    fun fourWayAndEightWay_resolveTheSameDiagonalDifferently() {
        val fourWay = Fixture()
        fourWay.begin(
            mode = ButtonAimDpadMode.FOUR_WAY,
            origin = ButtonAimDpadOrigin.CONTROL_CENTER
        )
        fourWay.session.update(7, 80f, 20f)
        assertEquals(listOf(ButtonAimDpadDirection.RIGHT), fourWay.session.currentDirections())

        val eightWay = Fixture()
        eightWay.begin(
            mode = ButtonAimDpadMode.EIGHT_WAY,
            origin = ButtonAimDpadOrigin.CONTROL_CENTER
        )
        eightWay.session.update(7, 80f, 20f)
        assertEquals(
            listOf(ButtonAimDpadDirection.UP, ButtonAimDpadDirection.RIGHT),
            eightWay.session.currentDirections()
        )
    }

    @Test
    fun enteringNeutralZone_releasesDirection() {
        val fixture = Fixture()
        fixture.begin(origin = ButtonAimDpadOrigin.CONTROL_CENTER)
        fixture.session.update(7, 90f, 50f)

        fixture.session.update(7, 55f, 50f)

        assertTrue(fixture.session.currentDirections().isEmpty())
        assertEquals(
            listOf("X360RIGHT_HOLD", "X360RIGHT_RELEASE"),
            fixture.transport.commands
        )
    }

    @Test
    fun changingDirection_releasesOldDirectionBeforeHoldingNewDirection() {
        val fixture = Fixture()
        fixture.begin(origin = ButtonAimDpadOrigin.CONTROL_CENTER)
        fixture.session.update(7, 90f, 50f)

        fixture.session.update(7, 50f, 10f)

        assertEquals(
            listOf("X360RIGHT_HOLD", "X360RIGHT_RELEASE", "X360UP_HOLD"),
            fixture.transport.commands
        )
    }

    @Test
    fun directionLease_doesNotReleaseAccompanyingButtonPayload() {
        val fixture = Fixture()
        val buttonLease = (fixture.executor.activate(
            ButtonAimPayloadOwner.BASE,
            "X360B"
        ) as ButtonAimPayloadActivationResult.Activated).lease
        fixture.begin(origin = ButtonAimDpadOrigin.CONTROL_CENTER)
        fixture.session.update(7, 90f, 50f)

        fixture.session.finish()

        assertEquals(
            listOf("X360B_HOLD", "X360RIGHT_HOLD", "X360RIGHT_RELEASE"),
            fixture.transport.commands
        )
        fixture.executor.release(buttonLease)
        assertEquals("X360B_RELEASE", fixture.transport.commands.last())
    }

    @Test
    fun wrongPointerCannotChangeOrReleaseOwnedDirection() {
        val fixture = Fixture()
        fixture.begin(origin = ButtonAimDpadOrigin.CONTROL_CENTER)
        fixture.session.update(7, 90f, 50f)

        assertFalse(fixture.session.update(8, 50f, 10f))
        assertEquals(listOf(ButtonAimDpadDirection.RIGHT), fixture.session.currentDirections())
        assertEquals(listOf("X360RIGHT_HOLD"), fixture.transport.commands)
    }

    private class Fixture {
        val transport = RecordingTransport()
        val executor = ButtonAimPayloadExecutor(transport) { _, _ -> ButtonAimScheduledTask {} }
        val session = ButtonAimDpadSession(executor) { 100f to 100f }

        fun begin(
            mode: ButtonAimDpadMode = ButtonAimDpadMode.EIGHT_WAY,
            origin: ButtonAimDpadOrigin,
            x: Float = 50f,
            y: Float = 50f
        ) {
            session.begin(
                pointerId = 7,
                x = x,
                y = y,
                newConfig = ButtonAimDpadConfig(
                    mode = mode,
                    origin = origin,
                    activationDistancePx = 10f,
                    owner = ButtonAimPayloadOwner.BASE
                )
            )
        }
    }

    private class RecordingTransport : ButtonAimPayloadTransport {
        val commands = mutableListOf<String>()

        override fun sendCommand(command: String) {
            commands += command
        }

        override fun sendKey(key: String, pressed: Boolean) = Unit
        override fun sendStickMacro(stickName: String, x: Float, y: Float) = Unit
        override fun onCameraFollow(command: String) = Unit
        override fun onScrollToggle() = Unit
        override fun onReleaseAll() = Unit
    }
}
