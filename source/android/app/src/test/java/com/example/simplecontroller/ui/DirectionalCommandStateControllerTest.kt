package com.example.simplecontroller.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionalCommandStateControllerTest {
    @Test
    fun unchangedBoostZone_holdsOnceAndReleasesOnExit() {
        val fixture = fixture()

        fixture.controller.reconcile(mapOf(DirectionalCommandSlot.UP to "X360A"))
        fixture.controller.reconcile(mapOf(DirectionalCommandSlot.UP to "X360A"))
        fixture.controller.reconcile(emptyMap())

        assertEquals(
            listOf("CMD:X360A_HOLD", "CMD:X360A_RELEASE"),
            fixture.transport.events
        )
    }

    @Test
    fun diagonalTransition_keepsBothAxesUntilEachAxisLeaves() {
        val fixture = fixture()

        fixture.controller.reconcile(mapOf(DirectionalCommandSlot.UP to "X360A"))
        fixture.controller.reconcile(
            mapOf(
                DirectionalCommandSlot.UP to "X360A",
                DirectionalCommandSlot.RIGHT to "X360B"
            )
        )
        fixture.controller.reconcile(mapOf(DirectionalCommandSlot.RIGHT to "X360B"))
        fixture.controller.reconcile(emptyMap())

        assertEquals(
            listOf(
                "CMD:X360A_HOLD",
                "CMD:X360B_HOLD",
                "CMD:X360A_RELEASE",
                "CMD:X360B_RELEASE"
            ),
            fixture.transport.events
        )
    }

    @Test
    fun sharedOutputAcrossAxes_releasesOnlyAfterLastOwnerLeaves() {
        val fixture = fixture()

        fixture.controller.reconcile(
            mapOf(
                DirectionalCommandSlot.UP to "X360A",
                DirectionalCommandSlot.RIGHT to "X360A"
            )
        )
        fixture.controller.reconcile(mapOf(DirectionalCommandSlot.RIGHT to "X360A"))
        fixture.controller.reconcile(emptyMap())

        assertEquals(
            listOf("CMD:X360A_HOLD", "CMD:X360A_RELEASE"),
            fixture.transport.events
        )
    }

    @Test
    fun boostTierTransition_activatesReplacementBeforeReleasingOldPayload() {
        val fixture = fixture()

        fixture.controller.reconcile(mapOf(DirectionalCommandSlot.UP to "W"))
        fixture.controller.reconcile(mapOf(DirectionalCommandSlot.UP to "X360A"))
        fixture.controller.reconcile(emptyMap())

        assertEquals(
            listOf(
                "KEY:W:true",
                "CMD:X360A_HOLD",
                "KEY:W:false",
                "CMD:X360A_RELEASE"
            ),
            fixture.transport.events
        )
    }

    private fun fixture(): Fixture {
        val transport = RecordingTransport()
        val executor = ButtonAimPayloadExecutor(transport, NoOpScheduler)
        return Fixture(DirectionalCommandStateController(executor), transport)
    }

    private data class Fixture(
        val controller: DirectionalCommandStateController,
        val transport: RecordingTransport
    )

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

    private object NoOpScheduler : ButtonAimPayloadScheduler {
        override fun schedule(delayMs: Long, action: () -> Unit): ButtonAimScheduledTask =
            ButtonAimScheduledTask {}
    }
}
