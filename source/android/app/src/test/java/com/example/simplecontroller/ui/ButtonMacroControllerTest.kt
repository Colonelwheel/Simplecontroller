package com.example.simplecontroller.ui

import org.junit.Assert.*
import org.junit.Test

class ButtonMacroControllerTest {
    @Test fun ctrlAltTabIsOneSimultaneousFiniteChordFromATap() {
        val f = Fixture()
        f.macro.press("CTRL,ALT,TAB")
        f.clock.advance(10)
        f.macro.release()
        f.clock.advance(80)
        assertEquals(listOf("0:KEY:CTRL:true", "0:KEY:ALT:true", "0:KEY:TAB:true",
            "90:KEY:CTRL:false", "90:KEY:ALT:false", "90:KEY:TAB:false"), f.events)
        assertFalse(f.macro.isActive)
    }

    @Test fun tapCompletesAThenAThenBWithRealReleaseGaps() {
        val f = Fixture()
        assertNull(f.macro.press("X360A(U100),X360A(D500)(U100),X360B(D800)(U100)"))
        f.clock.advance(20)
        f.macro.release()
        f.clock.advance(880)
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE", "500:X360A_HOLD",
            "600:X360A_RELEASE", "800:X360B_HOLD", "900:X360B_RELEASE"), f.events)
        assertFalse(f.macro.isActive)
    }

    @Test fun matchingDelaysHoldTwoButtonsTogetherAndReleaseBoth() {
        val f = Fixture()
        f.macro.press("X360A(U100),X360X(U100),X360B(D300)(U100),X360Y(D300)(U100)")
        f.clock.advance(10)
        f.macro.release()
        f.clock.advance(390)
        assertEquals(listOf("0:X360A_HOLD", "0:X360X_HOLD", "100:X360A_RELEASE",
            "100:X360X_RELEASE", "300:X360B_HOLD", "300:X360Y_HOLD",
            "400:X360B_RELEASE", "400:X360Y_RELEASE"), f.events)
        assertFalse(f.macro.isActive)
    }

    @Test fun undecoratedAndDelayedCommandsHaveFiniteDefaultPresses() {
        val f = Fixture()
        f.macro.press("X360A,X360B(D400)")
        f.clock.advance(5)
        f.macro.release()
        f.clock.advance(485)
        assertEquals(listOf("0:X360A_HOLD", "90:X360A_RELEASE", "400:X360B_HOLD",
            "490:X360B_RELEASE"), f.events)
        assertFalse(f.macro.isActive)
    }

    @Test fun extraTapDoesNotDuplicateOrRestartRunningMacro() {
        val f = Fixture()
        f.macro.press("X360A(D400)(U100)")
        f.macro.release()
        f.clock.advance(200)
        assertNull(f.macro.press("X360A(D400)(U100)"))
        f.macro.release()
        f.clock.advance(500)
        assertEquals(listOf("400:X360A_HOLD", "500:X360A_RELEASE"), f.events)
        assertFalse(f.macro.isActive)
    }

    @Test fun aCompletedMacroCanBeTappedAgain() {
        val f = Fixture()
        f.macro.press("X360A(U100)")
        f.macro.release()
        f.clock.advance(200)
        assertFalse(f.macro.isActive)
        f.macro.press("X360A(U100)")
        f.macro.release()
        f.clock.advance(100)
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE", "200:X360A_HOLD",
            "300:X360A_RELEASE"), f.events)
    }

    @Test fun holdingFingerDoesNotRepeatOrLeaveOutputsDown() {
        val f = Fixture()
        f.macro.press("X360A(U100),X360B(D200)(U100)")
        f.clock.advance(1000)
        assertFalse(f.macro.isActive)
        f.macro.release()
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE", "200:X360B_HOLD",
            "300:X360B_RELEASE"), f.events)
    }

    @Test fun sorWaitsForFingerUpEvenAfterImmediateStepsFinish() {
        val f = Fixture()
        f.macro.press("X360A(U20),X360B(SOR)(D50)(U100)")
        f.clock.advance(500)
        assertTrue(f.macro.isActive)
        assertEquals(listOf("0:X360A_HOLD", "20:X360A_RELEASE"), f.events)
        f.macro.release()
        f.clock.advance(150)
        assertEquals(listOf("550:X360B_HOLD", "650:X360B_RELEASE"), f.events.takeLast(2))
        assertFalse(f.macro.isActive)
    }

    @Test fun cancelStopsActiveAndPendingCommandsAndCannotFireSor() {
        val f = Fixture()
        f.macro.press("X360A(U100),X360B(D400)(U100),X360Y(SOR)(U100)")
        f.clock.advance(20)
        f.macro.cancel()
        f.macro.release()
        f.clock.advance(1000, runCanceled = true)
        assertEquals(listOf("0:X360A_HOLD", "20:X360A_RELEASE"), f.events)
        assertFalse(f.macro.isActive)
    }

    @Test fun canceledCallbacksCannotInterruptTheNextMacro() {
        val f = Fixture()
        f.macro.press("X360A(D100)(U100)")
        f.macro.release()
        f.clock.advance(50)
        f.macro.cancel()
        f.macro.press("X360A(U300)")
        f.macro.release()
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("50:X360A_HOLD", "350:X360A_RELEASE"), f.events)
    }

    @Test fun authoredTriggerPulseRemainsFiniteAfterTap() {
        val f = Fixture()
        f.macro.press("RT:1.0P0.3(D100)")
        f.clock.advance(10)
        f.macro.release()
        f.clock.advance(390)
        assertEquals(listOf("100:RT:1.0", "400:RT:0.0"), f.events)
        assertFalse(f.macro.isActive)
    }

    @Test fun unboundedRepeatsAndOneWayActionsAreRejectedBeforeAnythingStarts() {
        for (payload in listOf("", "X360A(R100)", "X360A(R100)(U20)", "X360A,RELEASE_ALL",
            "CAMERA_FOLLOW", "SCROLL_MODE_TOGGLE", "X360A_RELEASE", "RT:0.0", "PAGE_HOME")) {
            val f = Fixture()
            assertNotNull(payload, f.macro.press(payload))
            assertTrue(payload, f.events.isEmpty())
            assertFalse(payload, f.macro.isActive)
        }
    }

    private class Fixture {
        val clock = Clock()
        val events = mutableListOf<String>()
        val executor = ButtonAimPayloadExecutor(object : ButtonAimPayloadTransport {
            override fun sendCommand(command: String) { events += "${clock.now}:$command" }
            override fun sendKey(key: String, pressed: Boolean) { events += "${clock.now}:KEY:$key:$pressed" }
            override fun sendStickMacro(stickName: String, x: Float, y: Float) { events += "${clock.now}:$stickName:$x:$y" }
            override fun onCameraFollow(command: String) { error("Unexpected state toggle") }
            override fun onScrollToggle() { error("Unexpected scroll toggle") }
            override fun onReleaseAll() { error("Unexpected Release All") }
        }, clock)
        val macro = ButtonMacroController(executor)
    }

    private class Clock : ButtonAimPayloadScheduler {
        var now = 0L
        private data class Task(val time: Long, val order: Int, val action: () -> Unit, var canceled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        private var sequence = 0
        override fun schedule(delayMs: Long, task: () -> Unit): ButtonAimScheduledTask {
            val scheduled = Task(now + delayMs, sequence++, task)
            tasks += scheduled
            return ButtonAimScheduledTask { scheduled.canceled = true }
        }
        fun advance(ms: Long, runCanceled: Boolean = false) {
            val until = now + ms
            while (true) {
                val next = tasks.filter { it.time <= until }
                    .minWithOrNull(compareBy<Task>({ it.time }, { it.order })) ?: break
                tasks.remove(next)
                now = next.time
                if (runCanceled || !next.canceled) next.action()
            }
            now = until
        }
    }
}
