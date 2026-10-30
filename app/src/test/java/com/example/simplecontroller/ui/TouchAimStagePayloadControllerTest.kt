package com.example.simplecontroller.ui

import com.example.simplecontroller.model.TouchStageAction
import org.junit.Assert.*
import org.junit.Test

class TouchAimStagePayloadControllerTest {
    @Test fun modifiedStageSet_rejectsNonReleasableCommandsInOtherStages() {
        for (payload in listOf("CAMERA_FOLLOW", "RELEASE_ALL", "LT:0.0", "MOUSE_LEFT_UP")) {
            assertNotNull(touchAimStagePayloadValidationError(listOf(
                stage(TouchContactLevel.LOW, "LT:1.0(HRD200)"),
                stage(TouchContactLevel.HIGH, payload)
            )))
        }
        assertNull(touchAimStagePayloadValidationError(listOf(
            stage(TouchContactLevel.LOW, ""),
            stage(TouchContactLevel.MEDIUM, "LT:1.0(HRD200)"),
            stage(TouchContactLevel.HIGH, "RT:1.0")
        )))
    }

    @Test fun unmodifiedStageSet_keepsLegacyValidationBoundary() {
        assertNull(touchAimStagePayloadValidationError(listOf(
            stage(TouchContactLevel.LOW, "CAMERA_FOLLOW"),
            stage(TouchContactLevel.HIGH, "RELEASE_ALL")
        )))
    }

    @Test fun carriedHold_keepsSameLeaseAcrossHigherStage_andStartsDelayOnlyOnLift() {
        val f = Fixture()
        f.stages = listOf(
            stage(TouchContactLevel.MEDIUM, "LT:1.0(HRD200)"),
            stage(TouchContactLevel.HIGH, "RT:1.0")
        )
        f.transition(TouchContactLevel.MEDIUM)
        f.advance(100L)
        f.transition(TouchContactLevel.HIGH)
        f.advance(1000L)
        assertEquals(listOf("LT:1.0", "RT:1.0"), f.events)
        f.transition(TouchContactLevel.MEDIUM)
        assertEquals(listOf("LT:1.0", "RT:1.0", "RT:0.0"), f.events)
        f.controller.finish(cancelled = false)
        f.advance(199L)
        assertEquals("RT:0.0", f.events.last())
        f.advance(1L)
        assertEquals("LT:0.0", f.events.last())
    }

    @Test fun ineligibleHold_releasesOnStageChangeWithoutStartingHrd() {
        val f = Fixture()
        f.stages = listOf(
            stage(TouchContactLevel.MEDIUM, "LT:1.0(HRD200)"),
            stage(TouchContactLevel.HIGH, "RT:1.0")
        )
        f.transition(TouchContactLevel.MEDIUM, keepLowerHolds = false)
        f.transition(TouchContactLevel.HIGH, keepLowerHolds = false)
        assertEquals(listOf("LT:1.0", "RT:1.0", "LT:0.0"), f.events)
        f.advance(500L)
        assertEquals(3, f.events.size)
        f.controller.finish(cancelled = false)
        assertEquals("RT:0.0", f.events.last())
    }

    @Test fun sor_firesOnceOnStageExit_andLiftDoesNotFireItAgain() {
        val f = Fixture()
        f.stages = listOf(stage(TouchContactLevel.MEDIUM, "RT:1.0(SOR)"))
        f.transition(TouchContactLevel.MEDIUM, keepLowerHolds = false)
        assertTrue(f.events.isEmpty())
        f.transition(TouchContactLevel.HIGH, keepLowerHolds = false)
        assertEquals(listOf("RT:1.0"), f.events)
        f.controller.finish(cancelled = false)
        f.advance(89L)
        assertEquals(listOf("RT:1.0"), f.events)
        f.advance(1L)
        assertEquals(listOf("RT:1.0", "RT:0.0"), f.events)
    }

    @Test fun hardCancel_clearsHoldImmediately_andSuppressesArmedSor() {
        val f = Fixture()
        f.stages = listOf(stage(TouchContactLevel.MEDIUM, "LT:1.0(HRD200),RT:1.0(SOR)"))
        f.transition(TouchContactLevel.MEDIUM)
        f.controller.finish(cancelled = true)
        f.advance(1000L)
        assertEquals(listOf("LT:1.0", "LT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun press_keepsImmediateFinitePulse_andArmsSorUntilStageExit() {
        val f = Fixture()
        f.stages = listOf(stage(
            TouchContactLevel.MEDIUM, "LT:1.0(I)(HRD200),RT:1.0(SOR)", TouchStageAction.PRESS
        ))
        f.transition(TouchContactLevel.MEDIUM)
        assertEquals(listOf("LT:1.0"), f.events)
        f.advance(90L)
        assertEquals(listOf("LT:1.0", "LT:0.0"), f.events)
        f.advance(500L)
        assertEquals(2, f.events.size)
        f.controller.finish(cancelled = false)
        assertEquals("RT:1.0", f.events.last())
        f.advance(90L)
        assertEquals("RT:0.0", f.events.last())
        assertEquals(1, f.events.count { it == "LT:1.0" })
    }

    @Test fun pressStageExit_preservesPulseDuration_butLiftEndsLivePulseWithItsHrd() {
        val f = Fixture()
        f.stages = listOf(stage(
            TouchContactLevel.MEDIUM, "LT:1.0(I)(HRD200),RT:1.0(SOR)", TouchStageAction.PRESS
        ))
        f.transition(TouchContactLevel.MEDIUM, keepLowerHolds = false)
        f.advance(30L)
        f.transition(TouchContactLevel.HIGH, keepLowerHolds = false)
        assertEquals(listOf("LT:1.0", "RT:1.0"), f.events)
        f.advance(20L)
        f.controller.finish(cancelled = false)
        f.advance(69L)
        assertEquals(listOf("LT:1.0", "RT:1.0"), f.events)
        f.advance(1L)
        assertEquals("RT:0.0", f.events.last())
        f.advance(129L)
        assertEquals("RT:0.0", f.events.last())
        f.advance(1L)
        assertEquals("LT:0.0", f.events.last())
    }

    @Test fun retouch_oldDelayCannotReleaseNewContactHold() {
        val f = Fixture()
        f.stages = listOf(stage(TouchContactLevel.MEDIUM, "LT:1.0(HRD200)"))
        f.transition(TouchContactLevel.MEDIUM)
        f.controller.finish(cancelled = false)
        f.advance(100L)
        f.previous = TouchContactLevel.AIM_ONLY
        f.transition(TouchContactLevel.MEDIUM)
        f.advance(100L)
        assertEquals(listOf("LT:1.0"), f.events)
        f.controller.finish(cancelled = false)
        f.advance(200L)
        assertEquals(listOf("LT:1.0", "LT:0.0"), f.events)
    }

    @Test fun repeatCarryover_keepsCadenceAcrossHigherStage_andHrdStartsOnlyOnLift() {
        val f = Fixture()
        f.stages = listOf(
            stage(TouchContactLevel.MEDIUM, "LT:1.0(R200)(HRD200)"),
            stage(TouchContactLevel.HIGH, "RT:1.0")
        )
        f.transition(TouchContactLevel.MEDIUM)
        f.advance(100L)
        f.transition(TouchContactLevel.HIGH)
        assertEquals(listOf("LT:1.0", "LT:0.0", "RT:1.0"), f.events)
        f.advance(100L)
        assertEquals("LT:1.0", f.events.last())
        f.advance(50L)
        f.transition(TouchContactLevel.MEDIUM)
        assertEquals("RT:0.0", f.events.last())
        f.advance(150L)
        assertEquals(3, f.events.count { it == "LT:1.0" })
        assertEquals("LT:1.0", f.events.last())
        f.advance(10L)
        f.controller.finish(cancelled = false)
        f.advance(199L, runCancelled = true)
        assertEquals("LT:1.0", f.events.last())
        f.advance(1L)
        assertEquals("LT:0.0", f.events.last())
        f.advance(1000L, runCancelled = true)
        assertEquals(3, f.events.count { it == "LT:1.0" })
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun repeatLiftDuringOff_doesNotReviveOutputForHrd() {
        val f = Fixture()
        f.stages = listOf(stage(TouchContactLevel.MEDIUM, "LT:1.0(R200)(HRD200)"))
        f.transition(TouchContactLevel.MEDIUM)
        f.advance(75L)
        f.controller.finish(cancelled = false)
        f.advance(1000L, runCancelled = true)
        assertEquals(listOf("LT:1.0", "LT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun repeatOnPressStage_overridesOnlyItsOwnFinitePressDuration() {
        val f = Fixture()
        f.stages = listOf(stage(
            TouchContactLevel.MEDIUM, "LT:1.0(R200),RT:1.0", TouchStageAction.PRESS
        ))
        f.transition(TouchContactLevel.MEDIUM)
        f.advance(90L)
        assertEquals(listOf("LT:1.0", "RT:1.0", "LT:0.0", "RT:0.0"), f.events)
        f.advance(110L)
        assertEquals("LT:1.0", f.events.last())
        assertEquals(1, f.events.count { it == "RT:1.0" })
        f.controller.finish(cancelled = false)
        f.advance(1000L, runCancelled = true)
        assertEquals(2, f.events.count { it == "LT:1.0" })
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun repeatPressStageExit_stopsRepeatWhilePreservingOrdinaryFiniteCompanion() {
        val f = Fixture()
        f.stages = listOf(stage(
            TouchContactLevel.MEDIUM, "LT:1.0(R200)(HRD200),RT:1.0", TouchStageAction.PRESS
        ))
        f.transition(TouchContactLevel.MEDIUM, keepLowerHolds = false)
        f.advance(30L)
        f.transition(TouchContactLevel.HIGH, keepLowerHolds = false)
        assertEquals(listOf("LT:1.0", "RT:1.0", "LT:0.0"), f.events)
        f.advance(59L, runCancelled = true)
        assertEquals(3, f.events.size)
        f.advance(1L)
        assertEquals("RT:0.0", f.events.last())
        f.controller.finish(cancelled = false)
        f.advance(1000L, runCancelled = true)
        assertEquals(1, f.events.count { it == "LT:1.0" })
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun repeatPressReentry_canceledOldCallbacksCannotOverlapNewSource() {
        val f = Fixture()
        f.stages = listOf(stage(TouchContactLevel.MEDIUM, "LT:1.0(R200)", TouchStageAction.PRESS))
        f.transition(TouchContactLevel.MEDIUM, keepLowerHolds = false)
        f.advance(25L)
        // PRESS activates on upward crossings, so return below Medium before re-entering it.
        f.transition(TouchContactLevel.AIM_ONLY, keepLowerHolds = false)
        f.advance(25L)
        f.transition(TouchContactLevel.MEDIUM, keepLowerHolds = false)
        f.advance(150L, runCancelled = true)
        assertEquals(listOf("LT:1.0", "LT:0.0", "LT:1.0", "LT:0.0"), f.events)
        f.advance(50L, runCancelled = true)
        assertEquals("LT:1.0", f.events.last())
        f.controller.finish(cancelled = false)
        f.advance(1000L, runCancelled = true)
        assertEquals(3, f.events.count { it == "LT:1.0" })
        assertEquals("LT:0.0", f.events.last())
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun repeatPressDownwardReturn_doesNotChangeLegacyUpwardActivationRule() {
        val f = Fixture()
        f.stages = listOf(stage(TouchContactLevel.MEDIUM, "LT:1.0(R200)", TouchStageAction.PRESS))
        f.transition(TouchContactLevel.MEDIUM, keepLowerHolds = false)
        f.advance(25L)
        f.transition(TouchContactLevel.HIGH, keepLowerHolds = false)
        f.advance(25L)
        f.transition(TouchContactLevel.MEDIUM, keepLowerHolds = false)
        f.advance(1000L, runCancelled = true)
        assertEquals(listOf("LT:1.0", "LT:0.0"), f.events)
        f.controller.finish(cancelled = false)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun repeatHardCancel_stopsCurrentPressAndAllCallbacks_andSuppressesSor() {
        val f = Fixture()
        f.stages = listOf(stage(TouchContactLevel.MEDIUM, "LT:1.0(R200)(HRD200),RT:1.0(SOR)"))
        f.transition(TouchContactLevel.MEDIUM)
        f.advance(20L)
        f.controller.finish(cancelled = true)
        f.advance(1000L, runCancelled = true)
        assertEquals(listOf("LT:1.0", "LT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    private fun stage(
        level: TouchContactLevel,
        payload: String,
        action: TouchStageAction = TouchStageAction.HOLD
    ) = TouchAimPayloadStage(level, payload, action)

    private class Fixture {
        val events = mutableListOf<String>()
        private var nowMs = 0L
        private data class Task(val deadline: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        val executor = ButtonAimPayloadExecutor(object : ButtonAimPayloadTransport {
            override fun sendCommand(command: String) { events += command }
            override fun sendKey(key: String, pressed: Boolean) { events += "$key:$pressed" }
            override fun sendStickMacro(stickName: String, x: Float, y: Float) {
                events += "$stickName:$x,$y"
            }
            override fun onCameraFollow(command: String) = error("Unexpected edge action")
            override fun onScrollToggle() = error("Unexpected edge action")
            override fun onReleaseAll() = error("Unexpected edge action")
        }, ButtonAimPayloadScheduler { delayMs, action ->
            val task = Task(nowMs + delayMs, action)
            tasks += task
            ButtonAimScheduledTask { task.cancelled = true }
        })
        val controller = TouchAimStagePayloadController(executor) { error(it) }
        var stages = emptyList<TouchAimPayloadStage>()
        var previous = TouchContactLevel.AIM_ONLY

        fun transition(next: TouchContactLevel, keepLowerHolds: Boolean = true) {
            controller.transition(stages, next, keepLowerHolds, enteredTouchAimPressLevels(previous, next, false))
            previous = next
        }

        fun advance(durationMs: Long, runCancelled: Boolean = false) {
            val target = nowMs + durationMs
            while (true) {
                val task = tasks.filter { it.deadline <= target }.minByOrNull { it.deadline }
                    ?: break
                tasks.remove(task)
                nowMs = task.deadline
                if (!task.cancelled || runCancelled) task.action()
            }
            nowMs = target
        }
    }
}
