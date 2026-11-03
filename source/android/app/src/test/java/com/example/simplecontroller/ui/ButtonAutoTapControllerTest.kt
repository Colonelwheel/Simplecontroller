package com.example.simplecontroller.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonAutoTapControllerTest {
    @Test
    fun statefulTogglePayloads_areRejectedBeforeARepeatCanStart() {
        assertEquals(
            "Release All cannot use Toggle auto-tap.",
            autoTapUnsupportedPayloadReason("X360A, RELEASE_ALL")
        )
        assertEquals(
            "Camera Follow cannot use Toggle auto-tap.",
            autoTapUnsupportedPayloadReason("camera_follow")
        )
        assertEquals(
            "Scroll Mode Toggle cannot use Toggle auto-tap.",
            autoTapUnsupportedPayloadReason("SCROLL_MODE_TOGGLE")
        )
        assertEquals(null, autoTapUnsupportedPayloadReason("X360A, RT:1.0"))
    }

    @Test
    fun firstToggle_startsImmediatelyAndRepeatsAtConfiguredInterval() {
        val fixture = Fixture()

        assertTrue(fixture.controller.toggle(120L))
        assertEquals(listOf("state:true", "press"), fixture.events)
        assertEquals(listOf(50L, 120L), fixture.scheduler.pendingDelays())

        fixture.scheduler.runNext(50L)
        assertEquals("release", fixture.events.last())

        fixture.scheduler.runNext(120L)
        assertEquals(listOf("press", "release", "press"), fixture.events.takeLast(3))
        assertTrue(fixture.controller.isActive)
    }

    @Test
    fun secondToggle_releasesImmediatelyAndCanceledCallbacksCannotRestart() {
        val fixture = Fixture()
        fixture.controller.toggle(100L)

        assertFalse(fixture.controller.toggle(100L))
        assertEquals(listOf("state:true", "press", "release", "state:false"), fixture.events)

        fixture.scheduler.runAllIncludingCanceled()
        assertEquals(listOf("state:true", "press", "release", "state:false"), fixture.events)
    }

    @Test
    fun stopBetweenPulses_doesNotSendAnExtraRelease() {
        val fixture = Fixture()
        fixture.controller.toggle(100L)
        fixture.scheduler.runNext(50L)

        fixture.controller.stop()

        assertEquals(1, fixture.events.count { it == "release" })
        assertFalse(fixture.controller.isActive)
    }

    @Test
    fun tooSmallInterval_isClampedAndStillHasAnOffBoundary() {
        val fixture = Fixture()

        fixture.controller.toggle(1L)

        assertEquals(listOf(8L, ButtonAutoTapController.MIN_INTERVAL_MS), fixture.scheduler.pendingDelays())
    }

    @Test
    fun failedPress_stopsWithoutSchedulingAnotherTap() {
        val fixture = Fixture(pressSucceeds = false)

        assertFalse(fixture.controller.toggle(100L))

        assertEquals(listOf("state:true", "press", "state:false"), fixture.events)
        assertTrue(fixture.scheduler.pendingDelays().isEmpty())
    }

    private class Fixture(pressSucceeds: Boolean = true) {
        val events = mutableListOf<String>()
        val scheduler = FakeScheduler()
        val controller = ButtonAutoTapController(
            scheduler = scheduler,
            pressPayload = {
                events += "press"
                pressSucceeds
            },
            releasePayload = { events += "release" },
            onStateChanged = { events += "state:$it" }
        )
    }

    private class FakeScheduler : ButtonAimPayloadScheduler {
        private data class Entry(
            val delayMs: Long,
            val action: () -> Unit,
            var canceled: Boolean = false
        )

        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMs: Long, task: () -> Unit): ButtonAimScheduledTask {
            val entry = Entry(delayMs, task)
            entries += entry
            return ButtonAimScheduledTask { entry.canceled = true }
        }

        fun pendingDelays(): List<Long> = entries.filterNot { it.canceled }.map { it.delayMs }

        fun runNext(delayMs: Long) {
            val entry = entries.first { !it.canceled && it.delayMs == delayMs }
            entries.remove(entry)
            entry.action()
        }

        fun runAllIncludingCanceled() {
            val snapshot = entries.toList()
            entries.clear()
            snapshot.forEach { it.action() }
        }
    }
}
