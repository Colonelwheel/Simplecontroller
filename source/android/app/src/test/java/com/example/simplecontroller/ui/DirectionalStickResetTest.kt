package com.example.simplecontroller.ui

import android.view.MotionEvent
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.net.PhoneOutputCoordinator
import com.example.simplecontroller.net.PhoneOutputPacket
import com.example.simplecontroller.net.PhoneOutputScope
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class DirectionalStickResetTest {
    @Test fun noCenterReset_doesNotAcquireZeroOrReplaceRetainedRingAim() {
        val f = Fixture()
        val aim = f.coordinator.beginScope("ring", "player1").child("aim")
        var replaced = 0
        aim.setRetained(true) { replaced++ }
        assertTrue(aim.sendStick("RS", .4f, .6f))
        val retainedPacket = f.packets.last()
        val packetCount = f.packets.size

        f.handler.stopDirectionalCommands(centerOutput = false)

        assertEquals(0, f.vectorScopeRequests)
        assertEquals(0, replaced)
        assertEquals(packetCount, f.packets.size)
        assertTrue(retainedPacket.isCurrent())
        assertTrue(aim.sendStick("RS", .5f, .1f))
    }

    @Test fun noCenterReset_releasesActualDirectionalLeaseWithoutTouchingRingAim() {
        val f = Fixture()
        val aim = f.coordinator.beginScope("ring", "player1").child("aim")
        var replaced = 0
        aim.setRetained(true) { replaced++ }
        assertTrue(aim.sendStick("RS", .4f, .6f))
        val retainedPacket = f.packets.last()
        f.handler.handleStickPlusMode(0f, -.25f, MotionEvent.ACTION_DOWN)
        assertTrue(f.handler.hasRuntimeState())
        assertEquals(listOf("X360A_HOLD"), f.commands)

        f.handler.stopDirectionalCommands(centerOutput = false)

        assertEquals(listOf("X360A_HOLD", "X360A_RELEASE"), f.commands)
        assertFalse(f.handler.hasRuntimeState())
        assertEquals(0, f.executor.activeLeaseCount())
        assertEquals(0, f.vectorScopeRequests)
        assertEquals(0, replaced)
        assertTrue(retainedPacket.isCurrent())
        assertTrue(aim.sendStick("RS", .5f, .1f))
    }

    @Test fun ordinaryDefaultReset_keepsExistingFinalZeroBehavior() {
        val f = Fixture()

        f.handler.stopDirectionalCommands()

        assertEquals(1, f.vectorScopeRequests)
        val packet = f.packets.last { it.command.startsWith("PHONE_STATE") }
        assertEquals("STICK_R:0.0,0.0", URLDecoder.decode(packet.command.substringAfterLast(':'), "UTF-8"))
    }

    private class Fixture {
        private var sequence = 0L
        val packets = mutableListOf<PhoneOutputPacket>()
        val commands = mutableListOf<String>()
        val coordinator = PhoneOutputCoordinator("process", { sequence++ }, packets::add)
        private val ordinary = coordinator.beginScope("ordinary", "player1")
        private val directional = ordinary.child("directional-stick")
        var vectorScopeRequests = 0
        val executor = ButtonAimPayloadExecutor(object : ButtonAimPayloadTransport {
            override fun sendCommand(command: String) { commands += command; directional.sendCommand(command) }
            override fun sendKey(key: String, pressed: Boolean) { directional.sendKey(key, pressed) }
            override fun sendStickMacro(stickName: String, x: Float, y: Float) {
                directional.sendStickMacro(stickName, x, y)
            }
            override fun onCameraFollow(command: String) = error("Unexpected Camera Follow action")
            override fun onScrollToggle() = error("Unexpected scroll action")
            override fun onReleaseAll() = error("Unexpected global release")
        }, ButtonAimPayloadScheduler { _, _ -> ButtonAimScheduledTask { } })
        val handler = DirectionalStickHandler(
            Control("STICK_R", ControlType.STICK, 0f, 0f, 100f, 100f, "STICK_R",
                directionalMode = true, upCommand = "X360A"),
            uiHandler = null,
            payloadExecutor = executor,
            scopeProvider = ::vectorScope
        )
        private fun vectorScope(): PhoneOutputScope {
            vectorScopeRequests++
            return ordinary.child("stick")
        }
    }
}
