package com.example.simplecontroller.ui

import com.example.simplecontroller.model.*
import com.example.simplecontroller.net.PhoneOutputCoordinator
import com.example.simplecontroller.net.PhoneOutputPacket
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class FloatingRingPayloadControllerTest {
    private class Clock : ButtonAimPayloadScheduler {
        data class Work(val time: Long, val action: () -> Unit, var canceled: Boolean = false)
        var now = 0L
        private val work = mutableListOf<Work>()
        override fun schedule(delayMs: Long, task: () -> Unit): ButtonAimScheduledTask {
            val item = Work(now + delayMs, task)
            work += item
            return ButtonAimScheduledTask { item.canceled = true }
        }
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = work.filter { !it.canceled && it.time <= target }.minByOrNull { it.time } ?: break
                work.remove(next)
                now = next.time
                next.action()
            }
            now = target
        }
    }
    private class Fixture(config: FloatingRingConfig = FloatingRingConfig()) {
        val clock = Clock()
        var sequence = 0L
        val packets = mutableListOf<PhoneOutputPacket>()
        val coordinator = PhoneOutputCoordinator("process", { sequence++ }, packets::add)
        val root = coordinator.beginScope("ring", "player1")
        val controller = FloatingRingPayloadController(root, setOf("player1|TRIGGER|RT"), clock, {})
        val backend = object : FloatingRingBackend {
            override fun acquirePayload(slot: String, payload: String) = controller.acquire(slot, payload)
            override fun releasePayload(slot: String) = controller.release(slot)
            override fun endPayload(slot: String) = controller.end(slot)
            override fun endPayloadNormally(slot: String) = controller.endNormally(slot)
            override fun reservePayload(slot: String, payload: String, retained: Boolean, exclusive: Boolean) =
                controller.reserve(slot, payload, retained, exclusive)
            override fun retainPayload(slot: String, retained: Boolean) = root.retainPayload(slot, retained)
            override fun payloadIsOwned(slot: String) = root.payloadIsOwned(slot)
            override fun beginAim(pointerId: Int, x: Float, y: Float, eventTime: Long, tuning: ButtonAimTuning) = true
            override fun updateAim(pointerId: Int, x: Float, y: Float, eventTime: Long) {}
            override fun suspendAim(pointerId: Int, x: Float, y: Float, eventTime: Long) {}
            override fun resumeAim(pointerId: Int, x: Float, y: Float, eventTime: Long) {}
            override fun completeAim(autoCenter: Boolean) {}
            override fun finishAim() {}
            override fun updateAimSensitivity(value: Float) {}
            override fun movement(x: Float, y: Float, retained: Boolean) {}
            override fun releaseMovement() {}
            override fun mouseSuppression(active: Boolean) {}
            override fun closeScope() { controller.close(); root.finish() }
        }
        val geometry = FloatingRingGeometry(config)
        val state = FloatingRingStateMachine(config, geometry, backend, clock, { clock.now })
        fun stateCommands() = packets.filter { it.command.startsWith("PHONE_STATE") }.map {
            URLDecoder.decode(it.command.substringAfterLast(':'), "UTF-8")
        }
        fun lastTrigger(name: String) = stateCommands().lastOrNull { it.startsWith("$name:") }
        fun down(role: FloatingStartRole = FloatingStartRole.AIM) =
            state.down(7, geometry.center.x, geometry.center.y, clock.now, role)
        fun up() = state.up(7, geometry.center.x, geometry.center.y, clock.now)
    }

    @Test fun normalLift_delaysImmediateOutputAndPulsesSorWithReservationUntilFinished() {
        val f = Fixture()
        assertTrue(f.controller.acquire("fire", "LT:1.0(I)(HRD200),RT:1.0(SOR)"))
        assertEquals("LT:1.0", f.lastTrigger("LT"))
        assertNull(f.lastTrigger("RT"))
        f.controller.endNormally("fire")
        assertEquals("RT:1.0", f.lastTrigger("RT"))
        assertTrue(f.root.payloadIsOwned("fire"))
        f.clock.advance(90)
        assertEquals("RT:0.0", f.lastTrigger("RT"))
        assertEquals("LT:1.0", f.lastTrigger("LT"))
        f.clock.advance(110)
        assertEquals("LT:0.0", f.lastTrigger("LT"))
        assertFalse(f.root.payloadIsOwned("fire"))
        assertTrue(f.stateCommands().none { '(' in it || ')' in it })
    }

    @Test fun hardFinish_bypassesHrdAndNeverSendsSor() {
        val f = Fixture()
        assertTrue(f.controller.acquire("fire", "LT:1.0(I)(HRD200),RT:1.0(SOR)"))
        f.controller.end("fire")
        f.clock.advance(1000)
        assertEquals("LT:0.0", f.lastTrigger("LT"))
        assertTrue(f.stateCommands().none { it == "RT:1.0" })
        assertFalse(f.root.payloadIsOwned("fire"))
    }

    @Test fun quickNewPress_oldHrdTimerCannotReleaseNewerActivation() {
        val f = Fixture()
        val payload = "LT:1.0(HRD200)"
        f.controller.acquire("ads", payload)
        f.controller.endNormally("ads")
        f.clock.advance(50)
        f.controller.acquire("ads", payload)
        f.clock.advance(150)
        assertEquals("LT:1.0", f.lastTrigger("LT"))
        assertTrue(f.root.payloadIsOwned("ads"))
        f.controller.endNormally("ads")
        f.clock.advance(200)
        assertEquals("LT:0.0", f.lastTrigger("LT"))
        assertFalse(f.root.payloadIsOwned("ads"))
    }

    @Test fun armedSor_reservesFireAgainstAnotherRingUntilItsReleasePulseCompletes() {
        val f = Fixture()
        val other = f.coordinator.beginScope("other", "player1")
        assertTrue(f.controller.acquire("fire", "RT:1.0(SOR)"))
        assertFalse(other.acquirePayload("fire", "RT:1.0", exclusive = true))
        f.controller.endNormally("fire")
        assertFalse(other.acquirePayload("fire", "RT:1.0", exclusive = true))
        f.clock.advance(90)
        assertTrue(other.acquirePayload("fire", "RT:1.0", exclusive = true))
    }

    @Test fun ringState_normalLiftUsesModifierReleaseButFinishAndCancelDoNot() {
        fun config() = FloatingRingConfig(startRole = FloatingStartRole.AIM,
            knobs = listOf(RingAimKnob(fire = RingFireSettings(mode = RingFireMode.HOLD,
                adsPayload = "LT:1.0(HRD200)", firePayload = "RT:1.0(SOR)"))))
        val normal = Fixture(config())
        assertTrue(normal.down())
        normal.up()
        assertEquals("RT:1.0", normal.lastTrigger("RT"))
        assertEquals("LT:1.0", normal.lastTrigger("LT"))
        normal.clock.advance(200)
        assertFalse(normal.root.payloadIsOwned("ads"))
        for (finish in listOf(false, true)) {
            val hard = Fixture(config())
            assertTrue(hard.down())
            if (finish) hard.state.finish() else hard.state.cancel()
            hard.clock.advance(1000)
            assertEquals("LT:0.0", hard.lastTrigger("LT"))
            assertTrue(hard.stateCommands().none { it == "RT:1.0" })
        }
    }

    @Test fun boostSourceExit_startsHrdWhilePointerRemainsDown() {
        val config = FloatingRingConfig(boost1 = RingBoostSettings(enabled = true,
            payload = "LT:1.0(HRD200)", action = RingAction.HOLD))
        val f = Fixture(config)
        assertTrue(f.down(FloatingStartRole.MOVEMENT))
        f.state.sample(7, f.geometry.center.x, f.geometry.center.y - 125f, 0)
        assertEquals("LT:1.0", f.lastTrigger("LT"))
        f.state.sample(7, f.geometry.center.x, f.geometry.center.y, 0)
        assertEquals("LT:1.0", f.lastTrigger("LT"))
        f.clock.advance(199)
        assertEquals("LT:1.0", f.lastTrigger("LT"))
        f.clock.advance(1)
        assertEquals("LT:0.0", f.lastTrigger("LT"))
    }
}
