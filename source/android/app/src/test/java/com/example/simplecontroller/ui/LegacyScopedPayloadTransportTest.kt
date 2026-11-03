package com.example.simplecontroller.ui

import com.example.simplecontroller.net.PhoneOutputCoordinator
import com.example.simplecontroller.net.PhoneOutputPacket
import com.example.simplecontroller.net.PhoneOutputScope
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

/** Synthetic transport checks: real UI dispatch, receiver hardware and game timing remain separate. */
class LegacyScopedPayloadTransportTest {
    private class Fixture {
        var sequence = 0L
        val packets = mutableListOf<PhoneOutputPacket>()
        val callbacks = mutableListOf<() -> Unit>()
        val coordinator = PhoneOutputCoordinator("process", { sequence++ }, packets::add)
        val scheduler = ButtonAimPayloadScheduler { _, task ->
            var canceled = false
            callbacks += { if (!canceled) task() }
            ButtonAimScheduledTask { canceled = true }
        }
        fun executor(scope: PhoneOutputScope) = ButtonAimPayloadExecutor(object : ButtonAimPayloadTransport {
            override fun sendCommand(command: String) { scope.sendCommand(command) }
            override fun sendKey(key: String, pressed: Boolean) { scope.sendKey(key, pressed) }
            override fun sendStickMacro(stickName: String, x: Float, y: Float) { scope.sendStickMacro(stickName, x, y) }
            override fun onForceRelease(command: String) { scope.sendLegacyForceRelease(command) }
            override fun onCameraFollow(command: String) = Unit
            override fun onScrollToggle() = Unit
            override fun onReleaseAll() = Unit
        }, scheduler)
        fun state(): String = URLDecoder.decode(packets.last { it.command.startsWith("PHONE_STATE") }
            .command.substringAfterLast(':'), "UTF-8")
    }

    @Test
    fun separateLegacyExecutors_releaseOnlyTheirOwnSharedAdsClaim() {
        val f = Fixture()
        val first = f.executor(f.coordinator.beginScope("button-one", "player1").child("button-action"))
        val second = f.executor(f.coordinator.beginScope("button-two", "player1").child("touch-state"))
        first.activate(ButtonAimPayloadOwner.BASE, "MOUSE_RIGHT")
        second.activate(ButtonAimPayloadOwner.BASE, "MOUSE_RIGHT_DOWN")
        first.releaseAllLeases()
        assertEquals("MOUSE_RIGHT_DOWN", f.state())
        second.releaseAllLeases()
        assertEquals("MOUSE_RIGHT_UP", f.state())
    }

    @Test
    fun authoredForceRelease_usesExplicitForceEventButNormalUpDoesNot() {
        val f = Fixture()
        val executor = f.executor(f.coordinator.beginScope("button", "player1").child("button-action"))
        val first = executor.activate(ButtonAimPayloadOwner.BASE, "RT:1") as ButtonAimPayloadActivationResult.Activated
        executor.release(first.lease)
        assertFalse(f.packets.any { URLDecoder.decode(it.command, "UTF-8").contains("PHONE_FORCE:") })
        executor.activate(ButtonAimPayloadOwner.BASE, "RT:1")
        executor.activate(ButtonAimPayloadOwner.ALTERNATE, "RT:0")
        assertTrue(f.packets.any { URLDecoder.decode(it.command, "UTF-8").contains("PHONE_FORCE:RT:0.0") })
    }

    @Test
    fun rootFinish_invalidatesExecutorResendsWithoutChangingAnotherOwner() {
        val f = Fixture()
        val root = f.coordinator.beginScope("floating-group", "player1")
        val executor = f.executor(root.child("auto-tap"))
        executor.activate(ButtonAimPayloadOwner.BASE, "RT:1P0.3", ButtonAimPulseMode.REPEAT)
        val other = f.coordinator.beginScope("external-ads", "player1")
        other.sendCommand("MOUSE_RIGHT_DOWN")
        root.finish()
        val count = f.packets.size
        f.callbacks.toList().forEach { it() }
        assertEquals(count, f.packets.size)
        assertTrue(other.isActive)
        other.finish()
        assertEquals("MOUSE_RIGHT_UP", f.state())
    }
}
