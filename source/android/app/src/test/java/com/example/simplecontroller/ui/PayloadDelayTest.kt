package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ButtonAimPayloadPolicy
import com.example.simplecontroller.model.PayloadCommandTiming
import com.example.simplecontroller.model.PayloadModifiers
import org.junit.Assert.*
import org.junit.Test

class PayloadDelayTest {
    @Test fun delayIsPerCommand_unrelatedImmediateOutputStartsAtDown() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(D100),RT:1.0")
        assertEquals(listOf("0:RT:1.0"), f.events)
        f.clock.advance(99)
        assertEquals(1, f.events.size)
        f.clock.advance(1)
        assertEquals(listOf("0:RT:1.0", "100:LT:1.0"), f.events)
        f.executor.releaseNormally(lease)
        assertEquals(listOf("100:LT:0.0", "100:RT:0.0"), f.events.takeLast(2))
    }

    @Test fun equalDelays_startInParallelFromTheSameSourceTime() {
        val f = Fixture()
        f.activate("LT:1.0(D100),RT:1.0(D100)")
        f.clock.advance(100)
        assertEquals(listOf("100:LT:1.0", "100:RT:1.0"), f.events)
        f.executor.releaseAllLeases()
    }

    @Test fun earlyNormalExit_cancelsPendingDelayAndNeverCreatesHrdHold() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(D100)(HRD200)")
        f.clock.advance(50)
        f.executor.releaseNormally(lease)
        f.clock.advance(1000, runCanceled = true)
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun hardCancel_cancelsEveryPendingDelay() {
        val f = Fixture()
        f.activate("LT:1.0(D100)(HRD200),RT:1.0(D200)(R100)")
        f.clock.advance(50)
        f.executor.releaseAllLeases()
        f.clock.advance(1000, runCanceled = true)
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun finitePressDuration_beginsAtActualDelayedActivation() {
        val f = Fixture()
        val lease = f.activate("X360A(D100)", finitePress = 90)
        f.clock.advance(189)
        assertEquals(listOf("100:X360A_HOLD"), f.events)
        f.clock.advance(1)
        assertEquals("190:X360A_RELEASE", f.events.last())
        f.executor.releaseNormally(lease)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun authoredPDuration_beginsAtActualDelayedActivation() {
        val f = Fixture()
        val lease = f.activate("RT:1.0P0.1(D100)", keepAuthoredPulse = true)
        f.clock.advance(150)
        f.executor.releaseNormally(lease)
        f.clock.advance(49)
        assertEquals(listOf("100:RT:1.0"), f.events)
        f.clock.advance(1)
        assertEquals(listOf("100:RT:1.0", "200:RT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun delayAndRepeat_delayOnlyFirstPulseThenUseNormalCadence() {
        val f = Fixture()
        val lease = f.activate("RT:1.0(D100)(R100)")
        f.clock.advance(225)
        f.executor.releaseNormally(lease)
        f.clock.advance(1000, runCanceled = true)
        assertEquals(listOf("100:RT:1.0", "150:RT:0.0", "200:RT:1.0", "225:RT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun sorDelay_beginsAtReleaseAndRepeatedReleaseDoesNotRestartIt() {
        val f = Fixture()
        val lease = f.activate("RT:1.0(SOR)(D100)")
        f.clock.advance(50)
        f.executor.releaseNormally(lease)
        f.clock.advance(25)
        f.executor.releaseNormally(lease)
        f.clock.advance(74)
        assertTrue(f.events.isEmpty())
        f.clock.advance(1)
        assertEquals(listOf("150:RT:1.0"), f.events)
        f.clock.advance(90)
        assertEquals("240:RT:0.0", f.events.last())
    }

    @Test fun inheritedSorDelay_addsControlDelayAndCommandDelay() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(I),RT:1.0(D100)",
            defaultTiming = PayloadCommandTiming.SEND_ON_RELEASE, controlReleaseDelay = 50)
        f.clock.advance(20)
        f.executor.releaseNormally(lease)
        f.clock.advance(149)
        assertEquals(listOf("0:LT:1.0", "20:LT:0.0"), f.events)
        f.clock.advance(1)
        assertEquals("170:RT:1.0", f.events.last())
        f.clock.advance(90)
        assertEquals("260:RT:0.0", f.events.last())
    }

    @Test fun sorDelay_canSatisfyExistingSameOutputReleaseGap() {
        val f = Fixture()
        val lease = f.activate("RT:1.0(R100),RT:1.0(SOR)(D100)")
        f.clock.advance(25)
        f.executor.releaseNormally(lease)
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:RT:1.0", "25:RT:0.0", "125:RT:1.0", "215:RT:0.0"), f.events)
    }

    @Test fun hardCancelAfterSorRelease_cancelsItsPendingDelayedShot() {
        val f = Fixture()
        val lease = f.activate("RT:1.0(SOR)(D100)")
        f.executor.releaseNormally(lease)
        f.clock.advance(50)
        f.executor.releaseAllLeases()
        f.clock.advance(1000, runCanceled = true)
        assertTrue(f.events.isEmpty())
    }

    @Test fun turboTicks_doNotPostponeInitialDelayOrDoubleStartFirstPulse() {
        val f = Fixture()
        val lease = f.activate("RT:1.0(D100)", finitePress = 50, turbo = true)
        f.clock.advance(50)
        f.executor.repeatImmediatePress(lease, 50)
        f.clock.advance(50)
        f.executor.repeatImmediatePress(lease, 50)
        assertEquals(listOf("100:RT:1.0"), f.events)
        f.clock.advance(100)
        f.executor.repeatImmediatePress(lease, 50)
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.clock.advance(1000, runCanceled = true)
        assertEquals(listOf("100:RT:1.0", "150:RT:0.0", "200:RT:1.0", "210:RT:0.0"), f.events)
    }

    @Test fun delayedHrd_staysExcludedFromTurboAfterItsInitialDelay() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(D100)(HRD200)", finitePress = 50, turbo = true)
        f.clock.advance(200)
        f.executor.repeatImmediatePress(lease, 50)
        f.clock.advance(20)
        f.executor.releaseNormally(lease)
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("100:LT:1.0", "420:LT:0.0"), f.events)
    }

    @Test fun liveHrdReentry_transfersHoldWithoutGapOrAnotherDelay() {
        val f = Fixture()
        val payload = "LT:1.0(D100)(HRD200)"
        val first = f.activate(payload)
        f.clock.advance(150)
        f.executor.releaseNormally(first)
        f.clock.advance(50)
        val resumed = f.activate(payload, resume = first)
        f.clock.advance(150, runCanceled = true)
        assertEquals(listOf("100:LT:1.0"), f.events)
        f.executor.releaseNormally(resumed)
        f.clock.advance(200)
        assertEquals(listOf("100:LT:1.0", "550:LT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun liveTailTransfer_keepsOldSorIndependentAndRejectsOldReleaseCallback() {
        val f = Fixture()
        val payload = "LT:1.0(D100)(HRD200),RT:1.0(SOR)(D200)"
        val first = f.activate(payload)
        f.clock.advance(150)
        f.executor.releaseNormally(first)
        f.clock.advance(50)
        val resumed = f.activate(payload, resume = first)
        f.clock.advance(160, runCanceled = true)
        assertEquals(listOf("100:LT:1.0", "350:RT:1.0"), f.events)
        f.executor.releaseNormally(resumed)
        f.clock.advance(300, runCanceled = true)
        assertEquals(listOf("100:LT:1.0", "350:RT:1.0", "440:RT:0.0", "560:LT:0.0",
            "560:RT:1.0", "650:RT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun transferOnlyResumesLiveHrdTokens_otherCommandsGetTheirOwnFreshDelay() {
        val f = Fixture()
        val payload = "LT:1.0(D100)(HRD200),RT:1.0(I)(D50)"
        val first = f.activate(payload)
        f.clock.advance(150)
        f.executor.releaseNormally(first)
        f.clock.advance(50)
        val resumed = f.activate(payload, resume = first)
        f.clock.advance(25)
        f.executor.releaseNormally(resumed)
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("50:RT:1.0", "100:LT:1.0", "150:RT:0.0", "425:LT:0.0"), f.events)
    }

    @Test fun expiredTailReentry_startsFreshDelay() {
        val f = Fixture()
        val payload = "LT:1.0(D100)(HRD200)"
        val first = f.activate(payload)
        f.clock.advance(150)
        f.executor.releaseNormally(first)
        f.clock.advance(250)
        val resumed = f.activate(payload, resume = first)
        f.clock.advance(99)
        assertEquals(listOf("100:LT:1.0", "350:LT:0.0"), f.events)
        f.clock.advance(1)
        assertEquals("500:LT:1.0", f.events.last())
        f.executor.release(resumed)
    }

    @Test fun transferCannotStealAnotherOwnerRole() {
        val f = Fixture()
        val payload = "LT:1.0(D100)(HRD200)"
        val first = f.activate(payload)
        f.clock.advance(150)
        f.executor.releaseNormally(first)
        f.clock.advance(50)
        val otherOwner = f.activate(payload, resume = first, owner = ButtonAimPayloadOwner.ALTERNATE)
        f.clock.advance(50)
        f.executor.releaseNormally(otherOwner)
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("100:LT:1.0", "350:LT:0.0"), f.events)
    }

    @Test fun repeatOnTailReentry_keepsClaimAndResumesCadenceWithoutDelay() {
        val f = Fixture()
        val payload = "RT:1.0(D100)(R100)(HRD200)"
        val first = f.activate(payload)
        f.clock.advance(125)
        f.executor.releaseNormally(first)
        f.clock.advance(75, runCanceled = true)
        val resumed = f.activate(payload, resume = first)
        f.clock.advance(130, runCanceled = true)
        f.executor.releaseNormally(resumed)
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("100:RT:1.0", "250:RT:0.0", "300:RT:1.0", "530:RT:0.0"), f.events)
    }

    @Test fun repeatOffReentry_neverRevivesAnOffClaimAndUsesFreshDelay() {
        val f = Fixture()
        val payload = "RT:1.0(D100)(R100)(HRD200)"
        val first = f.activate(payload)
        f.clock.advance(175)
        f.executor.releaseNormally(first)
        f.clock.advance(25)
        val resumed = f.activate(payload, resume = first)
        f.clock.advance(99, runCanceled = true)
        assertEquals(listOf("100:RT:1.0", "150:RT:0.0"), f.events)
        f.clock.advance(1)
        assertEquals("300:RT:1.0", f.events.last())
        f.executor.release(resumed)
    }

    @Test fun actualUpWithPreservedFiniteSibling_doesNotRestartAlreadyRunningHrd() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(R200)(HRD200),RT:1.0(I)", finitePress = 90)
        f.clock.advance(30)
        f.executor.releaseNormally(lease, preserveFinitePress = true)
        f.clock.advance(20)
        f.executor.releaseNormally(lease)
        f.clock.advance(1000, runCanceled = true)
        assertEquals(listOf("0:LT:1.0", "0:RT:1.0", "50:RT:0.0", "230:LT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun canceledDelayedToken_cannotFireWhileSiblingKeepsTheParentAlive() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(D100)(HRD200),RT:1.0(SOR)(D300)")
        f.clock.advance(30)
        f.executor.releaseNormally(lease)
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("330:RT:1.0", "420:RT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun dZeroAndMaximum_areAcceptedAndDetectorIncludesMalformedAttempts() {
        val f = Fixture()
        val immediate = f.activate("X360A(d0)")
        assertEquals(listOf("0:X360A_HOLD"), f.events)
        f.executor.release(immediate)
        val maximum = f.activate("X360B(D600000)")
        f.clock.advance(599999)
        assertEquals("0:X360A_RELEASE", f.events.last())
        f.clock.advance(1)
        assertEquals("600000:X360B_HOLD", f.events.last())
        f.executor.release(maximum)
        assertTrue(PayloadModifiers.hasDelayModifiers("LT:1.0(dBad)"))
        assertTrue(PayloadModifiers.parse("LT:1.0(D0)").commands.single().hasActivationDelay)
        assertFalse(PayloadModifiers.hasDelayModifiers("LT:1.0(HRD200)(R100)"))
    }

    @Test fun malformedOrDuplicateDelay_rejectsWholePayloadBeforeOutput() {
        for (command in listOf("LT:1.0(D)", "LT:1.0(D-1)", "LT:1.0(D0.5)", "LT:1.0(D600001)",
            "LT:1.0(D9999999999999999999999999)", "LT:1.0(Dbad)", "LT:1.0(D0)(D100)",
            "LT:1.0(D100", "RELEASE_ALL(D100)", "RT:0.0(D100)")) {
            val f = Fixture()
            val payload = "X360A(I),$command"
            assertNotNull(ButtonAimPayloadPolicy.validationError(payload))
            assertTrue(f.executor.activate(ButtonAimPayloadOwner.BASE, payload) is ButtonAimPayloadActivationResult.Invalid)
            assertTrue("$command must emit nothing", f.events.isEmpty())
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
        fun activate(payload: String, finitePress: Long? = null, keepAuthoredPulse: Boolean = false,
                     turbo: Boolean = false, resume: ButtonAimPayloadLease? = null,
                     owner: ButtonAimPayloadOwner = ButtonAimPayloadOwner.BASE,
                     defaultTiming: PayloadCommandTiming = PayloadCommandTiming.IMMEDIATE,
                     controlReleaseDelay: Long = 0L) =
            (executor.activate(owner, payload, immediatePressDurationMs = finitePress,
                preserveAuthoredPulseOnRelease = keepAuthoredPulse, turboSession = turbo,
                resumeHoldReleaseFrom = resume, defaultTiming = defaultTiming, defaultReleaseDelayMs = controlReleaseDelay)
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
