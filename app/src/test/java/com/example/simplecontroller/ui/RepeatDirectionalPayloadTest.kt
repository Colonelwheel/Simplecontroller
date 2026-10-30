package com.example.simplecontroller.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatDirectionalPayloadTest {
    @Test fun heldZoneRepeatsWithoutRestartingOnMoveAndStopsOnExit() {
        val f = Fixture()
        val held = mapOf(DirectionalCommandSlot.UP to "RT:1.0(R100),LT:1.0")
        f.controller.reconcile(held)
        f.clock.advance(75)
        f.controller.reconcile(held)
        f.clock.advance(45)
        f.controller.reconcile(emptyMap())
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:RT:1.0", "0:LT:1.0", "50:RT:0.0",
            "100:RT:1.0", "120:RT:0.0", "120:LT:0.0"), f.events)
    }

    @Test fun oldRepeatCannotRestartAfterZoneExitAndReentry() {
        val f = Fixture()
        val held = mapOf(DirectionalCommandSlot.UP to "RT:1.0(R100)")
        f.controller.reconcile(held)
        f.clock.advance(75)
        f.controller.reconcile(emptyMap())
        f.clock.advance(5)
        f.controller.reconcile(held)
        f.clock.advance(105, runCanceled = true)
        f.controller.clear()
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:RT:1.0", "50:RT:0.0", "80:RT:1.0",
            "130:RT:0.0", "180:RT:1.0", "185:RT:0.0"), f.events)
    }

    @Test fun otherDirectionOwnsSameOutputAndMasksRepeatWithoutBeingForcedOff() {
        val f = Fixture()
        f.controller.reconcile(mapOf(DirectionalCommandSlot.UP to "RT:1.0(R100)",
            DirectionalCommandSlot.RIGHT to "RT:1.0"))
        f.clock.advance(60)
        assertEquals(listOf("0:RT:1.0"), f.events)
        f.controller.reconcile(mapOf(DirectionalCommandSlot.UP to "RT:1.0(R100)"))
        f.clock.advance(50)
        f.controller.clear()
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:RT:1.0", "60:RT:0.0", "100:RT:1.0", "110:RT:0.0"), f.events)
    }

    private class Fixture {
        val clock = Clock()
        val events = mutableListOf<String>()
        val executor = ButtonAimPayloadExecutor(object : ButtonAimPayloadTransport {
            override fun sendCommand(command: String) { events += "${clock.now}:$command" }
            override fun sendKey(key: String, pressed: Boolean) { events += "${clock.now}:$key:$pressed" }
            override fun sendStickMacro(stickName: String, x: Float, y: Float) { events += "${clock.now}:$stickName:$x:$y" }
            override fun onCameraFollow(command: String) = error("Unexpected edge action")
            override fun onScrollToggle() = error("Unexpected edge action")
            override fun onReleaseAll() = error("Unexpected edge action")
        }, clock)
        val controller = DirectionalCommandStateController(executor)
    }

    private class Clock : ButtonAimPayloadScheduler {
        var now = 0L
        private data class Work(val at: Long, val sequence: Long, val task: () -> Unit, var canceled: Boolean = false)
        private val work = mutableListOf<Work>()
        private var nextSequence = 0L
        override fun schedule(delayMs: Long, task: () -> Unit): ButtonAimScheduledTask {
            val entry = Work(now + delayMs, nextSequence++, task)
            work += entry
            return ButtonAimScheduledTask { entry.canceled = true }
        }
        fun advance(ms: Long, runCanceled: Boolean = false) {
            val target = now + ms
            while (true) {
                val entry = work.filter { it.at <= target }
                    .minWithOrNull(compareBy<Work>({ it.at }, { it.sequence })) ?: break
                work.remove(entry)
                now = entry.at
                if (runCanceled || !entry.canceled) entry.task()
            }
            now = target
        }
    }
}
