package com.example.simplecontroller.ui

import org.junit.Assert.*
import org.junit.Test

class PendingFloatingGestureTest {
    private data class Activation(val data: String, val pointer: Int, val x: Float, val y: Float, val time: Long)

    private class Clock {
        class Entry(val at: Long, val task: () -> Unit, var canceled: Boolean = false, var ran: Boolean = false)

        var time = 0L
        val entries = mutableListOf<Entry>()

        fun schedule(delayMs: Long, task: () -> Unit): ButtonAimScheduledTask {
            require(delayMs >= 0L)
            val entry = Entry(time + delayMs, task)
            entries += entry
            return ButtonAimScheduledTask { entry.canceled = true }
        }

        fun advanceTo(target: Long) {
            require(target >= time)
            while (true) {
                val next = entries.filter { !it.ran && !it.canceled && it.at <= target }.minByOrNull { it.at }
                    ?: break
                time = maxOf(time, next.at)
                next.ran = true
                next.task()
            }
            time = target
        }

        fun runCanceled() {
            entries.filter { !it.ran && it.canceled }.toList().forEach {
                it.ran = true
                it.task()
            }
        }
    }

    private class Fixture(timeoutMs: Long = 1500L) {
        val clock = Clock()
        val activations = mutableListOf<Activation>()
        val abandonments = mutableListOf<Boolean>()
        var current = true
        var ready = false
        var readyChecks = 0
        var afterReady: () -> Unit = {}
        val gesture: PendingFloatingGesture<String> = PendingFloatingGesture(
            now = { clock.time }, schedule = clock::schedule, timeoutMs = timeoutMs,
            onReady = { data, pointer, x, y, time ->
                assertFalse(gesturePending())
                activations += Activation(data, pointer, x, y, time)
                afterReady()
            },
            onAbandoned = { abandonments += it }
        )

        private fun gesturePending() = gesture.isPending

        fun begin(data: String = "ring", pointer: Int = 4, x: Float = 10f, y: Float = 20f) {
            gesture.begin(data, pointer, x, y, { current }, { readyChecks++; ready })
        }
    }

    @Test fun heldContact_readinessActivatesOnceAtLatestPointAndFreshTime() {
        val f = Fixture()
        f.clock.time = 100L
        f.begin()
        assertTrue(f.gesture.isPending)
        assertTrue(f.activations.isEmpty())
        assertEquals(0, f.readyChecks)
        f.clock.advanceTo(100L)
        f.gesture.update(4, 35f, 46f)
        f.ready = true
        f.clock.advanceTo(115L)
        assertTrue(f.activations.isEmpty())
        f.clock.advanceTo(116L)
        assertEquals(listOf(Activation("ring", 4, 35f, 46f, 116L)), f.activations)
        assertFalse(f.gesture.isPending)
        f.clock.advanceTo(2000L)
        assertEquals(1, f.activations.size)
        assertTrue(f.abandonments.isEmpty())
    }

    @Test fun alreadyReady_beginStillDefersActivationUntilScheduledPoll() {
        val f = Fixture()
        f.ready = true
        f.begin()
        assertTrue(f.activations.isEmpty())
        f.clock.advanceTo(0L)
        assertEquals(1, f.activations.size)
    }

    @Test fun liftBeforeAcknowledgement_canceledPollCannotProduceAQueuedTap() {
        val f = Fixture()
        f.begin()
        f.clock.advanceTo(0L)
        f.gesture.cancel()
        f.ready = true
        f.clock.runCanceled()
        f.clock.advanceTo(2000L)
        assertFalse(f.gesture.isPending)
        assertTrue(f.activations.isEmpty())
        assertTrue(f.abandonments.isEmpty())
    }

    @Test fun liftBeforeFirstPoll_neverChecksReadinessOrReplaysTheOldDown() {
        val f = Fixture()
        f.begin()
        f.gesture.cancel()
        f.ready = true
        f.clock.runCanceled()
        assertEquals(0, f.readyChecks)
        assertTrue(f.activations.isEmpty())
    }

    @Test fun changedRouteOrDisconnect_abandonsAndRecoveryNeverRevivesContact() {
        val f = Fixture()
        f.begin()
        f.clock.advanceTo(0L)
        f.current = false
        f.ready = true
        f.clock.advanceTo(16L)
        assertEquals(listOf(false), f.abandonments)
        assertEquals(1, f.readyChecks)
        f.current = true
        f.clock.advanceTo(5000L)
        assertFalse(f.gesture.isPending)
        assertTrue(f.activations.isEmpty())
        assertEquals(listOf(false), f.abandonments)
    }

    @Test fun deadline_isBoundedEvenWhenItFallsBetweenPollIntervals() {
        val f = Fixture(timeoutMs = 40L)
        f.begin()
        f.clock.advanceTo(39L)
        assertTrue(f.gesture.isPending)
        f.ready = true
        f.clock.advanceTo(40L)
        assertEquals(listOf(true), f.abandonments)
        assertTrue(f.activations.isEmpty())
        assertFalse(f.gesture.isPending)
        assertEquals(3, f.readyChecks)
    }

    @Test fun wrongPointer_cannotMoveOrTakeOverTrackedContact() {
        val f = Fixture()
        f.begin(pointer = 3, x = 11f, y = 12f)
        f.gesture.update(9, 999f, Float.NaN)
        f.ready = true
        f.clock.advanceTo(0L)
        assertEquals(listOf(Activation("ring", 3, 11f, 12f, 0L)), f.activations)
        assertTrue(f.abandonments.isEmpty())
    }

    @Test fun nonfiniteTrackedPoint_abandonsWithoutReadinessOrLaterActivation() {
        val f = Fixture()
        f.begin()
        f.gesture.update(4, Float.POSITIVE_INFINITY, 20f)
        f.ready = true
        f.clock.runCanceled()
        assertFalse(f.gesture.isPending)
        assertEquals(listOf(false), f.abandonments)
        assertEquals(0, f.readyChecks)
        assertTrue(f.activations.isEmpty())
    }

    @Test fun invalidInitialContact_isRejectedBeforeAnyReadinessPreparation() {
        listOf(-1 to 10f, 4 to Float.NaN).forEach { (pointer, x) ->
            val f = Fixture()
            f.ready = true
            f.begin(pointer = pointer, x = x)
            f.clock.advanceTo(0L)
            assertEquals(listOf(false), f.abandonments)
            assertEquals(0, f.readyChecks)
            assertTrue(f.activations.isEmpty())
        }
    }

    @Test fun replacement_oldCallbackCannotActivateOrClearTheNewContact() {
        val f = Fixture()
        f.begin("old")
        val old = f.clock.entries.single()
        f.begin("new", pointer = 7, x = 70f, y = 80f)
        assertTrue(old.canceled)
        f.ready = true
        old.task()
        assertTrue(f.gesture.isPending)
        assertTrue(f.activations.isEmpty())
        f.clock.advanceTo(0L)
        assertEquals(listOf(Activation("new", 7, 70f, 80f, 0L)), f.activations)
        assertTrue(f.abandonments.isEmpty())
    }

    @Test fun synchronousReadyCleanup_seesConsumedContactAndCannotDuplicateActivation() {
        val f = Fixture()
        f.ready = true
        f.afterReady = { f.gesture.cancel() }
        f.begin()
        val callback = f.clock.entries.single().task
        f.clock.advanceTo(0L)
        callback()
        f.clock.runCanceled()
        assertEquals(1, f.activations.size)
        assertFalse(f.gesture.isPending)
        assertTrue(f.abandonments.isEmpty())
    }

    @Test fun readinessPreparationThatCancelsContact_cannotActivateIt() {
        val clock = Clock()
        val activations = mutableListOf<String>()
        val abandonments = mutableListOf<Boolean>()
        lateinit var gesture: PendingFloatingGesture<String>
        gesture = PendingFloatingGesture(now = { clock.time }, schedule = clock::schedule,
            onReady = { data, _, _, _, _ -> activations += data },
            onAbandoned = { abandonments += it })
        gesture.begin("ring", 4, 1f, 2f, { true }, { gesture.cancel(); true })
        clock.advanceTo(0L)
        assertFalse(gesture.isPending)
        assertTrue(activations.isEmpty())
        assertTrue(abandonments.isEmpty())
    }

    @Test fun routeChangingDuringReadiness_isCheckedAgainBeforeActivation() {
        val clock = Clock()
        val activations = mutableListOf<String>()
        val abandonments = mutableListOf<Boolean>()
        var current = true
        val gesture = PendingFloatingGesture<String>(now = { clock.time }, schedule = clock::schedule,
            onReady = { data, _, _, _, _ -> activations += data },
            onAbandoned = { abandonments += it })
        gesture.begin("ring", 4, 1f, 2f, { current }, { current = false; true })
        clock.advanceTo(0L)
        assertEquals(listOf(false), abandonments)
        assertTrue(activations.isEmpty())
    }
}
