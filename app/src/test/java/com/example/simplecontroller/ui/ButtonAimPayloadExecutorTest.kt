package com.example.simplecontroller.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonAimPayloadExecutorTest {
    @Test
    fun touchAimStatePayloadPolicy_rejectsIrreversibleActions() {
        val fixture = Fixture()
        assertNull(fixture.executor.statePayloadValidationError("X360A,RT:1.0,LS:R50"))
        assertTrue(fixture.executor.statePayloadValidationError("CAMERA_FOLLOW:1") != null)
        assertTrue(fixture.executor.statePayloadValidationError("SCROLL_MODE_TOGGLE") != null)
        assertTrue(fixture.executor.statePayloadValidationError("RELEASE_ALL") != null)
        assertTrue(fixture.executor.statePayloadValidationError("MOUSE_RESET") != null)
    }

    @Test
    fun booleanOverlap_releasesOnlyAfterLastLease() {
        val fixture = Fixture()
        val base = fixture.activate(ButtonAimPayloadOwner.BASE, "X360A").lease
        val alternate = fixture.activate(ButtonAimPayloadOwner.ALTERNATE, "X360A").lease

        assertEquals(listOf("CMD:X360A_HOLD"), fixture.transport.events)
        fixture.executor.release(alternate)
        assertEquals(listOf("CMD:X360A_HOLD"), fixture.transport.events)
        fixture.executor.release(base)
        assertEquals(
            listOf("CMD:X360A_HOLD", "CMD:X360A_RELEASE"),
            fixture.transport.events
        )
    }

    @Test
    fun triggerAndStickAlternatePriority_restoreBaseOnCancel() {
        val fixture = Fixture()
        fixture.activate(ButtonAimPayloadOwner.BASE, "LT:0.4,LS:R50")
        val alternate = fixture.activate(ButtonAimPayloadOwner.ALTERNATE, "LT:1.0,LS:U100").lease

        fixture.executor.release(alternate)

        assertEquals(
            listOf(
                "CMD:LT:0.4",
                "STICK:LS:0.5:0.0",
                "CMD:LT:1.0",
                "STICK:LS:0.0:-1.0",
                "CMD:LT:0.4",
                "STICK:LS:0.5:0.0"
            ),
            fixture.transport.events
        )
    }

    @Test
    fun partialMultiCommandOverlap_keepsSharedBaseOutputs() {
        val fixture = Fixture()
        val base = fixture.activate(ButtonAimPayloadOwner.BASE, "X360A,MOUSE_LEFT_DOWN,SPACE").lease
        val alternate = fixture.activate(
            ButtonAimPayloadOwner.ALTERNATE,
            "X360A,MOUSE_RIGHT_DOWN,KEY_DOWN:SPACE"
        ).lease

        fixture.executor.release(alternate)
        assertEquals(
            listOf(
                "CMD:X360A_HOLD",
                "CMD:MOUSE_LEFT_DOWN",
                "KEY:SPACE:true",
                "CMD:MOUSE_RIGHT_DOWN",
                "CMD:MOUSE_RIGHT_UP"
            ),
            fixture.transport.events
        )

        fixture.executor.release(base)
        assertTrue(fixture.transport.events.contains("CMD:X360A_RELEASE"))
        assertTrue(fixture.transport.events.contains("CMD:MOUSE_LEFT_UP"))
        assertTrue(fixture.transport.events.contains("KEY:SPACE:false"))
    }

    @Test
    fun cameraFollowAndScroll_areInjectedEdgeActions() {
        val fixture = Fixture()
        val result = fixture.activate(
            ButtonAimPayloadOwner.ALTERNATE,
            "CAMERA_FOLLOW:1,SCROLL_MODE_TOGGLE"
        )

        assertNull(result.lease)
        assertEquals(2, result.edgeActionCount)
        assertEquals(
            listOf("CAMERA:CAMERA_FOLLOW:1", "SCROLL"),
            fixture.transport.events
        )
    }

    @Test
    fun legacySpecialNamespaces_areForwardedAsRawCommands() {
        val fixture = Fixture()

        val result = fixture.activate(
            ButtonAimPayloadOwner.ALTERNATE,
            "MOUSE_RESET,TOUCHPAD:CUSTOM,STICK_CUSTOM"
        )

        assertNull(result.lease)
        assertEquals(3, result.edgeActionCount)
        assertEquals(
            listOf("CMD:MOUSE_RESET", "CMD:TOUCHPAD:CUSTOM", "CMD:STICK_CUSTOM"),
            fixture.transport.events
        )
    }

    @Test
    fun releaseAllWinsMixedPayloadAndInvalidatesPulseCallback() {
        val fixture = Fixture()
        fixture.activate(ButtonAimPayloadOwner.BASE, "RT:1.0P0.3")
        fixture.transport.events.clear()

        val result = fixture.executor.activate(
            ButtonAimPayloadOwner.ALTERNATE,
            "X360B,RELEASE_ALL,MOUSE_LEFT_DOWN"
        )
        fixture.scheduler.runAllIncludingCanceled()

        assertTrue(result is ButtonAimPayloadActivationResult.ReleaseAll)
        assertEquals(listOf("RELEASE_ALL"), fixture.transport.events)
        assertEquals(0, fixture.executor.activeLeaseCount())
    }

    @Test
    fun blankAndMalformedReservedPayloads_areAtomicInvalidResults() {
        val fixture = Fixture()

        assertTrue(
            fixture.executor.activate(ButtonAimPayloadOwner.BASE, "  ") is
                ButtonAimPayloadActivationResult.Invalid
        )
        assertTrue(
            fixture.executor.activate(ButtonAimPayloadOwner.BASE, "X360A,LT:nope") is
                ButtonAimPayloadActivationResult.Invalid
        )
        assertTrue(fixture.transport.events.isEmpty())
        assertEquals(0, fixture.executor.activeLeaseCount())
    }

    @Test
    fun canceledPulseTimer_cannotReleaseReacquiredTrigger() {
        val fixture = Fixture()
        val pulse = fixture.activate(ButtonAimPayloadOwner.BASE, "RT:1.0P0.3").lease
        fixture.executor.release(pulse)
        fixture.activate(ButtonAimPayloadOwner.ALTERNATE, "RT:0.8")
        fixture.scheduler.runAllIncludingCanceled()

        assertEquals("CMD:RT:0.8", fixture.transport.events.last())
    }

    @Test
    fun releaseTogether_doesNotRestoreBaseBeforeNeutral() {
        val fixture = Fixture()
        val base = fixture.activate(ButtonAimPayloadOwner.BASE, "LT:0.4").lease
        val alternate = fixture.activate(ButtonAimPayloadOwner.ALTERNATE, "LT:1.0").lease
        fixture.transport.events.clear()

        fixture.executor.releaseTogether(alternate, base)

        assertEquals(listOf("CMD:LT:0.0"), fixture.transport.events)
    }

    @Test
    fun releaseAllLeases_isIdempotentAndDoesNotRestoreBase() {
        val fixture = Fixture()
        fixture.activate(ButtonAimPayloadOwner.BASE, "LT:0.4,X360A")
        fixture.activate(ButtonAimPayloadOwner.ALTERNATE, "LT:1.0,X360A")
        fixture.transport.events.clear()

        fixture.executor.releaseAllLeases()
        fixture.executor.releaseAllLeases()

        assertEquals(listOf("CMD:LT:0.0", "CMD:X360A_RELEASE"), fixture.transport.events)
    }

    @Test
    fun staleRepeatingPulse_cannotReactivateAfterReleaseAllLeases() {
        val fixture = Fixture()
        fixture.executor.activate(
            ButtonAimPayloadOwner.BASE,
            "RT:1.0P0.3",
            ButtonAimPulseMode.REPEAT
        )
        fixture.transport.events.clear()

        fixture.executor.releaseAllLeases()
        fixture.scheduler.runAllIncludingCanceled()

        assertEquals(listOf("CMD:RT:0.0"), fixture.transport.events)
    }

    private class Fixture {
        val transport = RecordingTransport()
        val scheduler = FakeScheduler()
        val executor = ButtonAimPayloadExecutor(transport, scheduler)

        fun activate(
            owner: ButtonAimPayloadOwner,
            payload: String
        ): ButtonAimPayloadActivationResult.Activated =
            executor.activate(owner, payload) as ButtonAimPayloadActivationResult.Activated
    }

    private class RecordingTransport : ButtonAimPayloadTransport {
        val events = mutableListOf<String>()

        override fun sendCommand(command: String) {
            events += "CMD:$command"
        }

        override fun sendKey(key: String, pressed: Boolean) {
            events += "KEY:$key:$pressed"
        }

        override fun sendStickMacro(stickName: String, x: Float, y: Float) {
            events += "STICK:$stickName:$x:$y"
        }

        override fun onCameraFollow(command: String) {
            events += "CAMERA:$command"
        }

        override fun onScrollToggle() {
            events += "SCROLL"
        }

        override fun onReleaseAll() {
            events += "RELEASE_ALL"
        }
    }

    private class FakeScheduler : ButtonAimPayloadScheduler {
        private data class Entry(val action: () -> Unit, var canceled: Boolean = false)

        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMs: Long, task: () -> Unit): ButtonAimScheduledTask {
            val entry = Entry(task)
            entries += entry
            return ButtonAimScheduledTask { entry.canceled = true }
        }

        fun runAllIncludingCanceled() {
            val snapshot = entries.toList()
            entries.clear()
            snapshot.forEach { it.action() }
        }
    }
}
