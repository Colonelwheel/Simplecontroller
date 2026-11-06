package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ButtonAimPayloadPolicy
import com.example.simplecontroller.model.PayloadCommandTiming
import com.example.simplecontroller.model.PayloadModifiers
import org.junit.Assert.*
import org.junit.Test

class PayloadUpTest {
    @Test fun uReleasesAtAuthoredDuration_evenWhileSourceStaysHeld() {
        val f = Fixture()
        val lease = f.activate("X360A(U100)")
        f.clock.advance(99)
        assertEquals(listOf("0:X360A_HOLD"), f.events)
        f.clock.advance(1)
        assertEquals("100:X360A_RELEASE", f.events.last())
        f.clock.advance(500)
        f.executor.releaseNormally(lease)
        assertEquals(2, f.events.size)
        assertFalse(f.executor.isLeaseActive(lease))
    }

    @Test fun delayRemainsParallel_andSeparateACommandsHaveRealReleaseEdges() {
        val f = Fixture()
        val lease = f.activate("X360A(U100),X360A(D400)(U100),X360B(D200)(U100)")
        f.clock.advance(600)
        f.executor.releaseNormally(lease)
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE", "200:X360B_HOLD",
            "300:X360B_RELEASE", "400:X360A_HOLD", "500:X360A_RELEASE"), f.events)
    }

    @Test fun macroTapCompletesAllStepsAfterLift_andRetiresItsLease() {
        val f = Fixture()
        val lease = f.activate("X360A(U100),X360A(D500)(U100),X360B(D800)(U100)", macro = true)
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.executor.releaseNormally(lease)
        f.clock.advance(890)
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE", "500:X360A_HOLD",
            "600:X360A_RELEASE", "800:X360B_HOLD", "900:X360B_RELEASE"), f.events)
        assertFalse(f.executor.isLeaseActive(lease))
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun macroFinishesWhileFingerIsStillDown_withoutWaitingForLift() {
        val f = Fixture()
        val lease = f.activate("X360A(U100),X360B(D200)(U100)", macro = true)
        f.clock.advance(300)
        assertFalse(f.executor.isLeaseActive(lease))
        f.executor.releaseNormally(lease)
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE", "200:X360B_HOLD", "300:X360B_RELEASE"), f.events)
    }

    @Test fun simultaneousMacroButtons_haveMatchingPressAndReleaseTimes() {
        val f = Fixture()
        val lease = f.activate("X360A(U100),X360X(U100),X360A(D500)(U100),X360X(D500)(U100)," +
            "X360B(D800)(U100),X360X(D800)(U100)", macro = true)
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.clock.advance(890)
        assertEquals(listOf("0:X360A_HOLD", "0:X360X_HOLD", "100:X360A_RELEASE", "100:X360X_RELEASE",
            "500:X360A_HOLD", "500:X360X_HOLD", "600:X360A_RELEASE", "600:X360X_RELEASE",
            "800:X360B_HOLD", "800:X360X_HOLD", "900:X360B_RELEASE", "900:X360X_RELEASE"), f.events)
    }

    @Test fun macroBareCommandsUseInheritedFiniteDuration_andUOverridesIt() {
        val f = Fixture()
        val lease = f.activate("X360A,X360B(D200)(U100)", macro = true)
        f.executor.releaseNormally(lease)
        f.clock.advance(300)
        assertEquals(listOf("0:X360A_HOLD", "90:X360A_RELEASE", "200:X360B_HOLD", "300:X360B_RELEASE"), f.events)
        assertFalse(f.executor.isLeaseActive(lease))
    }

    @Test fun normalEarlyLiftCancelsPendingStepsAndEndsCurrentU() {
        val f = Fixture()
        val lease = f.activate("X360A(U100),X360B(D200)(U100)")
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.clock.advance(1000, runCanceled = true)
        assertEquals(listOf("0:X360A_HOLD", "10:X360A_RELEASE"), f.events)
    }

    @Test fun hardCancelAfterMacroLift_cancelsHeldAndPendingCommands() {
        val f = Fixture()
        val lease = f.activate("X360A(U100),X360B(D200)(U100)", macro = true)
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.clock.advance(10)
        f.executor.releaseAllLeases()
        f.clock.advance(1000, runCanceled = true)
        assertEquals(listOf("0:X360A_HOLD", "20:X360A_RELEASE"), f.events)
        assertFalse(f.executor.isLeaseActive(lease))
    }

    @Test fun canceledMacroCallbackCannotReleaseOrStartANewerLease() {
        val f = Fixture()
        val old = f.activate("X360A(U100),X360B(D200)(U100)", macro = true)
        f.clock.advance(20)
        f.executor.release(old)
        val current = f.activate("X360A")
        f.clock.advance(1000, runCanceled = true)
        assertEquals(listOf("0:X360A_HOLD", "20:X360A_RELEASE", "20:X360A_HOLD"), f.events)
        f.executor.release(current)
        assertEquals("1020:X360A_RELEASE", f.events.last())
    }

    @Test fun uOnlyReleasesItsOwnClaim_otherOwnerKeepsButtonHeld() {
        val f = Fixture()
        val held = f.activate("X360A")
        val timed = f.activate("X360A(U100)", macro = true)
        f.executor.releaseNormally(timed)
        f.clock.advance(100)
        assertEquals(listOf("0:X360A_HOLD"), f.events)
        assertFalse(f.executor.isLeaseActive(timed))
        f.executor.release(held)
        assertEquals("100:X360A_RELEASE", f.events.last())
    }

    @Test fun overlappingSameButtonCommandsKeepOneContinuousHold_untilBothDurationsEnd() {
        val f = Fixture()
        val lease = f.activate("X360A(U100),X360A(D50)(U100)", macro = true)
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.clock.advance(90)
        assertEquals(listOf("0:X360A_HOLD"), f.events)
        f.clock.advance(50)
        assertEquals(listOf("0:X360A_HOLD", "150:X360A_RELEASE"), f.events)
        assertFalse(f.executor.isLeaseActive(lease))
    }

    @Test fun completedMacroWithLongFingerHold_canStartAgainAfterLift() {
        val f = Fixture()
        val first = f.activate("X360A", macro = true)
        f.clock.advance(1000)
        assertFalse(f.executor.isLeaseActive(first))
        f.executor.releaseNormally(first)
        val second = f.activate("X360A", macro = true)
        f.executor.releaseNormally(second)
        f.clock.advance(90)
        assertEquals(listOf("0:X360A_HOLD", "90:X360A_RELEASE", "1000:X360A_HOLD", "1090:X360A_RELEASE"), f.events)
        assertFalse(f.executor.isLeaseActive(second))
    }

    @Test fun finiteTriggerExpiryRestoresOtherOwner_andKeyboardMouseStickRelease() {
        val f = Fixture()
        val held = f.activate("RT:0.4")
        val timed = f.activate("RT:1.0(U100),SPACE(U30),MOUSE_LEFT_DOWN(U40),LS:R50(U50)", macro = true)
        f.executor.releaseNormally(timed)
        f.clock.advance(100)
        assertEquals(listOf("0:RT:0.4", "0:RT:1.0", "0:KEY:SPACE:true", "0:MOUSE_LEFT_DOWN", "0:LS:0.5:0.0",
            "30:KEY:SPACE:false", "40:MOUSE_LEFT_UP", "50:LS:0.0:0.0", "100:RT:0.4"), f.events)
        f.executor.release(held)
        assertEquals("100:RT:0.0", f.events.last())
    }

    @Test fun normalHrdCanEndBeforeU_withoutWaitingForTheLaterTimer() {
        val f = Fixture()
        val lease = f.activate("X360A(U100)(HRD20)")
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.clock.advance(20)
        assertEquals(listOf("0:X360A_HOLD", "30:X360A_RELEASE"), f.events)
        assertFalse(f.executor.isLeaseActive(lease))
        f.clock.advance(1000, runCanceled = true)
        assertEquals(2, f.events.size)
    }

    @Test fun uCapsNormalHrdTail_andCompletedPulseIsNeverRevived() {
        val f = Fixture()
        val lease = f.activate("X360A(U100)(HRD200)")
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.clock.advance(90)
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE"), f.events)
        assertFalse(f.executor.isLeaseActive(lease))
        f.clock.advance(500, runCanceled = true)
        f.executor.releaseNormally(lease)
        assertEquals(2, f.events.size)
    }

    @Test fun macroUFinishesDespiteEarlierPhysicalUpAndHrdDeadline() {
        val f = Fixture()
        val lease = f.activate("X360A(U100)(HRD20),X360B(D200)(U100)(HRD20)", macro = true)
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.clock.advance(290)
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE", "200:X360B_HOLD", "300:X360B_RELEASE"), f.events)
        assertFalse(f.executor.isLeaseActive(lease))
    }

    @Test fun resumedHrdHoldKeepsItsOriginalUDeadline_withoutFreshDelayOrDuration() {
        val f = Fixture()
        val payload = "X360A(U100)(HRD200)"
        val first = f.activate(payload)
        f.clock.advance(20)
        f.executor.releaseNormally(first)
        f.clock.advance(30)
        val resumed = f.activate(payload, resume = first)
        f.clock.advance(49)
        assertEquals(listOf("0:X360A_HOLD"), f.events)
        f.clock.advance(1)
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE"), f.events)
        f.executor.releaseNormally(resumed)
        f.clock.advance(500, runCanceled = true)
        assertEquals(2, f.events.size)
        assertFalse(f.executor.isLeaseActive(first))
        assertFalse(f.executor.isLeaseActive(resumed))
    }

    @Test fun cancelingOldParentWithPendingSor_doesNotCancelTransferredUDeadline() {
        val f = Fixture()
        val payload = "X360A(U100)(HRD200),X360B(SOR)(D500)(U100)"
        val first = f.activate(payload)
        f.clock.advance(20)
        f.executor.releaseNormally(first)
        f.clock.advance(30)
        val resumed = f.activate(payload, resume = first)
        assertTrue(f.executor.isLeaseActive(first))
        f.clock.advance(10)
        f.executor.release(first)
        f.clock.advance(40, runCanceled = true)
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE"), f.events)
        f.executor.release(resumed)
        f.clock.advance(1000, runCanceled = true)
        assertEquals(2, f.events.size)
    }

    @Test fun cancelingResumedHoldInvalidatesTransferredDeadline_beforeNewerHoldStarts() {
        val f = Fixture()
        val payload = "X360A(U100)(HRD200)"
        val first = f.activate(payload)
        f.clock.advance(20)
        f.executor.releaseNormally(first)
        f.clock.advance(30)
        val resumed = f.activate(payload, resume = first)
        f.clock.advance(10)
        f.executor.release(resumed)
        val current = f.activate("X360A")
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:X360A_HOLD", "60:X360A_RELEASE", "60:X360A_HOLD"), f.events)
        f.executor.release(current)
        assertEquals("560:X360A_RELEASE", f.events.last())
    }

    @Test fun repeatedHrdReentryTransfersSameUDeadlineAcrossMultipleLeases() {
        val f = Fixture()
        val payload = "X360A(U100)(HRD200)"
        val first = f.activate(payload)
        f.clock.advance(20)
        f.executor.releaseNormally(first)
        f.clock.advance(30)
        val second = f.activate(payload, resume = first)
        f.clock.advance(10)
        f.executor.releaseNormally(second)
        f.clock.advance(20)
        val third = f.activate(payload, resume = second)
        f.clock.advance(20, runCanceled = true)
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE"), f.events)
        f.executor.releaseNormally(third)
        f.clock.advance(500, runCanceled = true)
        assertEquals(2, f.events.size)
    }

    @Test fun resumedRepeatKeepsCurrentUDeadline_andNewPulsesGetFreshDurations() {
        val f = Fixture()
        val payload = "X360A(R200)(U100)(HRD200)"
        val first = f.activate(payload)
        f.clock.advance(20)
        f.executor.releaseNormally(first)
        f.clock.advance(30)
        val resumed = f.activate(payload, resume = first)
        f.clock.advance(300, runCanceled = true)
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE", "250:X360A_HOLD", "350:X360A_RELEASE"), f.events)
        f.executor.releaseNormally(resumed)
        f.clock.advance(500, runCanceled = true)
        assertEquals(4, f.events.size)
        assertFalse(f.executor.isLeaseActive(resumed))
    }

    @Test fun macroSorRemainsArmedAfterImmediateStepsFinish_andFiresOnlyOnPhysicalUp() {
        val f = Fixture()
        val lease = f.activate("X360A(U100),X360B(SOR)(D200)(U50)", macro = true)
        f.clock.advance(300)
        assertTrue(f.executor.isLeaseActive(lease))
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE"), f.events)
        f.executor.releaseNormally(lease)
        f.executor.releaseNormally(lease)
        f.clock.advance(250)
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE", "500:X360B_HOLD", "550:X360B_RELEASE"), f.events)
        assertFalse(f.executor.isLeaseActive(lease))
    }

    @Test fun macroSorAndPendingImmediate_haveIndependentReleaseAndStartClocks() {
        val f = Fixture()
        val lease = f.activate("X360A(D500)(U100),X360B(SOR)(D200)(U50)", macro = true)
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.clock.advance(590)
        assertEquals(listOf("210:X360B_HOLD", "260:X360B_RELEASE", "500:X360A_HOLD", "600:X360A_RELEASE"), f.events)
        assertFalse(f.executor.isLeaseActive(lease))
    }

    @Test fun inheritedSorUsesU_andAddsExistingControlDelay() {
        val f = Fixture()
        val lease = f.activate("X360A(D200)(U100)", timing = PayloadCommandTiming.SEND_ON_RELEASE,
            releaseDelay = 50, macro = true)
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.clock.advance(350)
        assertEquals(listOf("260:X360A_HOLD", "360:X360A_RELEASE"), f.events)
    }

    @Test fun sorUIsAFiniteCap_evenWhenHrdIsAuthored() {
        val f = Fixture()
        val lease = f.activate("X360A(SOR)(U100)(HRD200)")
        f.executor.releaseNormally(lease)
        f.clock.advance(100)
        assertEquals(listOf("0:X360A_HOLD", "100:X360A_RELEASE"), f.events)
        assertFalse(f.executor.isLeaseActive(lease))
    }

    @Test fun rUsesUAsOnDuration_andNormalReleaseStillStopsRepetition() {
        val f = Fixture()
        val lease = f.activate("X360A(R100)(U20)")
        f.clock.advance(205)
        f.executor.releaseNormally(lease)
        f.clock.advance(1000, runCanceled = true)
        assertEquals(listOf("0:X360A_HOLD", "20:X360A_RELEASE", "100:X360A_HOLD", "120:X360A_RELEASE",
            "200:X360A_HOLD", "205:X360A_RELEASE"), f.events)
    }

    @Test fun repeatedUCapsHrdTailAfterSourceRelease() {
        val f = Fixture()
        val lease = f.activate("X360A(R100)(U20)(HRD200)")
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.clock.advance(10)
        assertEquals(listOf("0:X360A_HOLD", "20:X360A_RELEASE"), f.events)
        assertFalse(f.executor.isLeaseActive(lease))
        f.clock.advance(500, runCanceled = true)
        assertEquals(2, f.events.size)
    }

    @Test fun globalTurboDoesNotTruncateAuthoredU_orRestartItsInitialDelay() {
        val f = Fixture()
        val lease = f.activate("X360A(D100)(U100)", finite = 10, turbo = true)
        f.clock.advance(50)
        f.executor.repeatImmediatePress(lease, 10)
        f.clock.advance(50)
        f.executor.repeatImmediatePress(lease, 10)
        f.clock.advance(50)
        f.executor.repeatImmediatePress(lease, 10)
        f.clock.advance(50)
        assertEquals(listOf("100:X360A_HOLD", "200:X360A_RELEASE"), f.events)
        f.executor.repeatImmediatePress(lease, 10)
        f.clock.advance(100)
        f.executor.releaseNormally(lease)
        assertEquals(listOf("100:X360A_HOLD", "200:X360A_RELEASE", "200:X360A_HOLD", "300:X360A_RELEASE"), f.events)
    }

    @Test fun macroPDurationSurvivesEarlyLift_includingPendingDelay() {
        val f = Fixture()
        val lease = f.activate("RT:1.0P0.1(D200)", macro = true)
        f.executor.releaseNormally(lease)
        f.clock.advance(300)
        assertEquals(listOf("200:RT:1.0", "300:RT:0.0"), f.events)
        assertFalse(f.executor.isLeaseActive(lease))
    }

    @Test fun completionFlagNeverDetachesAnInfiniteHoldOrRepeat() {
        val f = Fixture()
        val lease = f.activate("X360A(R100),X360B", macro = true, finite = null)
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.clock.advance(1000, runCanceled = true)
        assertEquals(listOf("0:X360A_HOLD", "0:X360B_HOLD", "10:X360A_RELEASE", "10:X360B_RELEASE"), f.events)
        assertFalse(f.executor.isLeaseActive(lease))
    }

    @Test fun parserAcceptsCaseInsensitiveDurationBounds_andDetectsMalformedAttempts() {
        val parsed = PayloadModifiers.parse("X360A(u1),X360B(U600000)(D100)")
        assertNull(parsed.error)
        assertEquals(listOf(1L, 600000L), parsed.commands.map { it.pressDurationMs })
        assertTrue(PayloadModifiers.hasReleaseModifiers("X360A(uBad)"))
        assertTrue(PayloadModifiers.hasReleaseModifiers("X360A(U"))
        assertFalse(PayloadModifiers.hasReleaseModifiers("X360A(HRD200)(R100)(D10)"))
    }

    @Test fun invalidUAndConflictingDurationsFailBeforeAnyOutput() {
        for (bad in listOf("X360A(U)", "X360A(U0)", "X360A(U-1)", "X360A(U1.5)",
            "X360A(U600001)", "X360A(U999999999999999999999999)", "X360A(Ubad)",
            "X360A(U1)(U2)", "X360A(U100", "RT:1.0P0.1(U100)", "X360A(R100)(U100)",
            "X360A(R100)(U101)", "RELEASE_ALL(U100)", "RT:0.0(U100)", "X360A_RELEASE(U100)")) {
            val f = Fixture()
            val payload = "X360X(I),$bad"
            assertNotNull("$bad should fail", ButtonAimPayloadPolicy.validationError(payload))
            assertTrue(f.executor.activate(ButtonAimPayloadOwner.BASE, payload) is ButtonAimPayloadActivationResult.Invalid)
            assertTrue("$bad must be atomic", f.events.isEmpty())
        }
    }

    private class Fixture {
        val clock = Clock()
        val events = mutableListOf<String>()
        val executor = ButtonAimPayloadExecutor(object : ButtonAimPayloadTransport {
            override fun sendCommand(command: String) { events += "${clock.now}:$command" }
            override fun sendKey(key: String, pressed: Boolean) { events += "${clock.now}:KEY:$key:$pressed" }
            override fun sendStickMacro(stickName: String, x: Float, y: Float) { events += "${clock.now}:$stickName:$x:$y" }
            override fun onCameraFollow(command: String) { error("Unexpected edge action") }
            override fun onScrollToggle() { error("Unexpected edge action") }
            override fun onReleaseAll() { events += "${clock.now}:RELEASE_ALL" }
        }, clock)
        fun activate(payload: String, macro: Boolean = false, finite: Long? = if (macro) 90L else null,
                     turbo: Boolean = false, timing: PayloadCommandTiming = PayloadCommandTiming.IMMEDIATE,
                     releaseDelay: Long = 0L, resume: ButtonAimPayloadLease? = null) =
            (executor.activate(ButtonAimPayloadOwner.BASE, payload, immediatePressDurationMs = finite,
                completeOnRelease = macro, turboSession = turbo, defaultTiming = timing,
                defaultReleaseDelayMs = releaseDelay, resumeHoldReleaseFrom = resume)
                as ButtonAimPayloadActivationResult.Activated).lease
    }

    private class Clock : ButtonAimPayloadScheduler {
        var now = 0L
        private data class Task(val at: Long, val sequence: Int, val action: () -> Unit, var canceled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        private var nextSequence = 0
        override fun schedule(delayMs: Long, task: () -> Unit): ButtonAimScheduledTask {
            val scheduled = Task(now + delayMs, nextSequence++, task)
            tasks += scheduled
            return ButtonAimScheduledTask { scheduled.canceled = true }
        }
        fun advance(ms: Long, runCanceled: Boolean = false) {
            val target = now + ms
            while (true) {
                val next = tasks.filter { it.at <= target }.minWithOrNull(compareBy<Task>({ it.at }, { it.sequence })) ?: break
                tasks.remove(next)
                now = next.at
                if (runCanceled || !next.canceled) next.action()
            }
            now = target
        }
    }
}
