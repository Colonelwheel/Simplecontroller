package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ButtonAimPayloadPolicy
import com.example.simplecontroller.model.PayloadCommandTiming
import com.example.simplecontroller.model.PayloadModifiers
import org.junit.Assert.*
import org.junit.Test

class PayloadModifiersTest {
    @Test fun immediateDelayAndReleaseShot_followIndependentDeadlines() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(I)(HRD200),RT:1.0(SOR)")
        assertEquals(listOf("0:LT:1.0"), f.events)
        f.clock.advance(400)
        f.executor.releaseNormally(lease)
        f.executor.releaseNormally(lease)
        assertEquals(listOf("0:LT:1.0", "400:RT:1.0"), f.events)
        f.clock.advance(89)
        assertEquals(2, f.events.size)
        f.clock.advance(1)
        assertEquals("490:RT:0.0", f.events.last())
        f.clock.advance(110)
        assertEquals("600:LT:0.0", f.events.last())
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun hardCancel_neverFiresSorAndBypassesHrd() {
        val f = Fixture()
        f.activate("LT:1.0(HRD200),RT:1.0(SOR)")
        f.executor.releaseAllLeases()
        f.clock.advance(1000, runCanceled = true)
        assertEquals(listOf("0:LT:1.0", "0:LT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun cancelDuringTail_staleCallbackCannotReleaseNewPress() {
        val f = Fixture()
        val first = f.activate("X360A(HRD200)")
        f.executor.releaseNormally(first)
        f.clock.advance(50)
        f.executor.release(first)
        val second = f.activate("X360A(I)")
        f.clock.advance(200, runCanceled = true)
        assertEquals(listOf("0:X360A_HOLD", "50:X360A_RELEASE", "50:X360A_HOLD"), f.events)
        f.executor.releaseNormally(second)
        assertEquals("250:X360A_RELEASE", f.events.last())
    }

    @Test fun rapidReentry_delayedOldLeasePreservesNewSharedOwner() {
        val f = Fixture()
        val first = f.activate("X360A(HRD200)")
        f.executor.releaseNormally(first)
        f.clock.advance(100)
        val second = f.activate("X360A(I)")
        f.clock.advance(100)
        assertEquals(listOf("0:X360A_HOLD"), f.events)
        f.executor.releaseNormally(second)
        assertEquals("200:X360A_RELEASE", f.events.last())
    }

    @Test fun thresholdExit_ignoresHrdButFiresSorOnce() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(HRD200),RT:1.0(SOR)")
        f.clock.advance(10)
        f.executor.releaseNormally(lease, applyHoldReleaseDelay = false)
        f.executor.releaseNormally(lease)
        f.clock.advance(90)
        assertEquals(listOf("0:LT:1.0", "10:LT:0.0", "10:RT:1.0", "100:RT:0.0"), f.events)
    }

    @Test fun finiteStagePress_preservesSorArmAfterImmediatePulseEnds() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(I)(HRD200),RT:1.0(SOR)", finitePress = 90)
        f.clock.advance(200)
        assertEquals(listOf("0:LT:1.0", "90:LT:0.0"), f.events)
        assertEquals(1, f.executor.activeLeaseCount())
        f.executor.releaseNormally(lease)
        f.clock.advance(90)
        assertEquals(listOf("0:LT:1.0", "90:LT:0.0", "200:RT:1.0", "290:RT:0.0"), f.events)
    }

    @Test fun finiteStageExit_preservesPulseThenActualUpAppliesHrdWithoutRepeatingSor() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(I)(HRD200),RT:1.0(SOR)", finitePress = 90)
        f.clock.advance(10)
        f.executor.releaseNormally(lease, applyHoldReleaseDelay = false, preserveFinitePress = true)
        assertEquals(listOf("0:LT:1.0", "10:RT:1.0"), f.events)
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.clock.advance(200)
        assertEquals(listOf("0:LT:1.0", "10:RT:1.0", "100:RT:0.0", "220:LT:0.0"), f.events)
    }

    @Test fun sorPulse_usesAuthoredPThenAddsHrd() {
        val f = Fixture()
        val lease = f.activate("RT:1.0P0.3(SOR)(HRD200)")
        assertTrue(f.events.isEmpty())
        f.executor.releaseNormally(lease)
        f.clock.advance(499)
        assertEquals(listOf("0:RT:1.0"), f.events)
        f.clock.advance(1)
        assertEquals("500:RT:0.0", f.events.last())
    }

    @Test fun canceledFiniteTimer_cannotCutShortHrdWhenCallbackStillArrives() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(I)(HRD200)", finitePress = 90)
        f.clock.advance(20)
        f.executor.releaseNormally(lease)
        f.clock.advance(100, runCanceled = true)
        assertEquals(listOf("0:LT:1.0"), f.events)
        f.clock.advance(100)
        assertEquals("220:LT:0.0", f.events.last())
    }

    @Test fun ordinaryAuthoredPulse_keepsItsDurationAfterEarlyLift() {
        val f = Fixture()
        val lease = f.activate("LT:1.0P0.3(I)", keepAuthoredPulse = true)
        f.clock.advance(80)
        f.executor.releaseNormally(lease)
        f.clock.advance(219)
        assertEquals(listOf("0:LT:1.0"), f.events)
        f.clock.advance(1)
        assertEquals("300:LT:0.0", f.events.last())
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun ordinaryAuthoredPulse_hrdUsesLiftDeadlineAndNeverRevivesExpiredPulse() {
        val early = Fixture()
        val first = early.activate("LT:1.0P0.3(I)(HRD200)", keepAuthoredPulse = true)
        early.clock.advance(80)
        early.executor.releaseNormally(first)
        early.clock.advance(200)
        assertEquals(listOf("0:LT:1.0", "280:LT:0.0"), early.events)

        val late = Fixture()
        val second = late.activate("LT:1.0P0.3(I)(HRD200)", keepAuthoredPulse = true)
        late.clock.advance(400)
        late.executor.releaseNormally(second)
        late.clock.advance(200)
        assertEquals(listOf("0:LT:1.0", "300:LT:0.0"), late.events)
    }

    @Test fun keyboardMouseAndStick_releaseOnlyTheirOwnModifiedClaims() {
        val f = Fixture()
        val lease = f.activate("SPACE(I)(HRD20),MOUSE_LEFT_DOWN(SOR),LS:R50(SOR)")
        f.clock.advance(10)
        f.executor.releaseNormally(lease)
        f.clock.advance(90)
        assertEquals(listOf("0:KEY:SPACE:true", "10:MOUSE_LEFT_DOWN", "10:LS:0.5:0.0",
            "30:KEY:SPACE:false", "100:MOUSE_LEFT_UP", "100:LS:0.0:0.0"), f.events)
    }

    @Test fun turboRepeatsOnlyImmediateCommands_sorWaitsForActualSourceRelease() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(I)(HRD200),RT:1.0(SOR)", finitePress = 50, turbo = true)
        f.clock.advance(100)
        f.executor.repeatImmediatePress(lease, 50)
        f.clock.advance(20)
        assertEquals(listOf("0:LT:1.0"), f.events)
        f.executor.releaseNormally(lease)
        f.clock.advance(200, runCanceled = true)
        assertEquals(listOf("0:LT:1.0", "120:RT:1.0",
            "210:RT:0.0", "320:LT:0.0"), f.events)
    }

    @Test fun turboHrdSteadyImmediateRtRepeats_releaseShotGetsDistinctGapAfterOnPhase() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(HRD200),RT:1.0(I),RT:1.0(SOR)", finitePress = 50, turbo = true)
        f.clock.advance(100)
        f.executor.repeatImmediatePress(lease, 50)
        f.clock.advance(20)
        f.executor.releaseNormally(lease)
        assertEquals(listOf("0:LT:1.0", "0:RT:1.0", "50:RT:0.0", "100:RT:1.0", "120:RT:0.0"), f.events)
        f.clock.advance(34, runCanceled = true)
        assertEquals("120:RT:0.0", f.events.last())
        f.clock.advance(1)
        assertEquals("155:RT:1.0", f.events.last())
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:LT:1.0", "0:RT:1.0", "50:RT:0.0", "100:RT:1.0", "120:RT:0.0",
            "155:RT:1.0", "245:RT:0.0", "320:LT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun turboReleaseDuringOffPhase_stillFiresOneSorAndNeverRestartsRepeat() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(HRD200),RT:1.0(I),RT:1.0(SOR)", finitePress = 50, turbo = true)
        f.clock.advance(80)
        f.executor.releaseNormally(lease)
        f.executor.repeatImmediatePress(lease, 50)
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:LT:1.0", "0:RT:1.0", "50:RT:0.0", "115:RT:1.0", "205:RT:0.0", "280:LT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun turboSorListedBeforeImmediate_stillReleasesImmediateBeforeReleaseShot() {
        val f = Fixture()
        val lease = f.activate("RT:1.0(SOR),LT:1.0(HRD200),RT:1.0(I)", finitePress = 50, turbo = true)
        f.clock.advance(20)
        f.executor.releaseNormally(lease)
        f.clock.advance(200, runCanceled = true)
        assertEquals(listOf("0:LT:1.0", "0:RT:1.0", "20:RT:0.0", "55:RT:1.0", "145:RT:0.0", "220:LT:0.0"), f.events)
    }

    @Test fun turboHrdZero_isStillExcludedFromTurboAndReleasesOnActualUp() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(HRD0),RT:1.0(I)", finitePress = 50, turbo = true)
        f.clock.advance(100)
        f.executor.repeatImmediatePress(lease, 50)
        f.clock.advance(100)
        assertEquals(listOf("0:LT:1.0", "0:RT:1.0", "50:RT:0.0", "100:RT:1.0", "150:RT:0.0"), f.events)
        f.executor.releaseNormally(lease)
        assertEquals("200:LT:0.0", f.events.last())
        assertTrue(PayloadModifiers.parse("LT:1.0(HRD0)").commands.single().hasHoldReleaseDelay)
        assertFalse(PayloadModifiers.parse("LT:1.0(I)").commands.single().hasHoldReleaseDelay)
    }

    @Test fun turboBoundarySuppressesOnlyReleaseShot_preservesSteadyHrdUntilDeadline() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(HRD200),RT:1.0(I),RT:1.0(SOR)", finitePress = 50, turbo = true)
        f.clock.advance(20)
        f.executor.releaseNormally(lease, suppressSendOnRelease = true)
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:LT:1.0", "0:RT:1.0", "20:RT:0.0", "220:LT:0.0"), f.events)
    }

    @Test fun turboHardCancelDuringReleaseShotGap_cancelsSorAndHrdWithoutLateCallbacks() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(HRD200),RT:1.0(I),RT:1.0(SOR)", finitePress = 50, turbo = true)
        f.clock.advance(20)
        f.executor.releaseNormally(lease)
        f.clock.advance(10)
        f.executor.releaseAllLeases()
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:LT:1.0", "0:RT:1.0", "20:RT:0.0", "30:LT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun boundaryCancellation_suppressesOnlySorWhileHonoringImmediateHrd() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(I)(HRD200),RT:1.0(SOR)")
        f.executor.releaseNormally(lease, suppressSendOnRelease = true)
        f.clock.advance(200)
        assertEquals(listOf("0:LT:1.0", "200:LT:0.0"), f.events)
    }

    @Test fun boundaryCancellationOnLatchedSource_doesNotStartHrdUntilActualUnlatch() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(I)(HRD200),RT:1.0(SOR)")
        f.executor.suppressSendOnRelease(lease)
        f.clock.advance(500)
        assertEquals(listOf("0:LT:1.0"), f.events)
        f.executor.releaseNormally(lease)
        f.clock.advance(200)
        assertEquals(listOf("0:LT:1.0", "700:LT:0.0"), f.events)
    }

    @Test fun explicitImmediate_overridesInheritedSorWhileUnmodifiedTokenKeepsDelay() {
        val f = Fixture()
        val lease = (f.executor.activate(ButtonAimPayloadOwner.BASE,
            "LT:1.0(I)(HRD200),RT:1.0", defaultTiming = PayloadCommandTiming.SEND_ON_RELEASE,
            defaultReleaseDelayMs = 50) as ButtonAimPayloadActivationResult.Activated).lease
        assertEquals(listOf("0:LT:1.0"), f.events)
        f.executor.releaseNormally(lease)
        f.clock.advance(50)
        assertEquals("50:RT:1.0", f.events.last())
        f.clock.advance(150)
        assertEquals(listOf("0:LT:1.0", "50:RT:1.0", "140:RT:0.0", "200:LT:0.0"), f.events)
    }

    @Test fun malformedAndConflictingSuffixes_failAtomically() {
        for (payload in listOf("LT:1.0(I)(SOR)", "X360A(HRD-1)", "SPACE(HRD1.5)",
            "RT:1.0(HRD)", "X360A(HRD600001)", "X360A(HRD1)(HRD2)", "X360A(WAIT)",
            "X360A(I", "LT:1.0(I),RT:1.0(HRDabc)", "RELEASE_ALL(SOR)", "LT:0.0(HRD200)")) {
            val f = Fixture()
            assertNotNull("$payload must fail validation", ButtonAimPayloadPolicy.validationError(payload))
            assertTrue(f.executor.activate(ButtonAimPayloadOwner.BASE, payload) is ButtonAimPayloadActivationResult.Invalid)
            assertTrue("$payload must emit nothing", f.events.isEmpty())
        }
    }

    @Test fun parser_preservesPlainCommandsAndRecognizesCaseInsensitiveModifiers() {
        val result = PayloadModifiers.parse("LT:1.0(i)(hrd200),KEY_DOWN:SPACE(sor),X360A")
        assertNull(result.error)
        assertEquals(listOf("LT:1.0", "KEY_DOWN:SPACE", "X360A"), result.commands.map { it.command })
        assertEquals(PayloadCommandTiming.IMMEDIATE, result.commands[0].timing)
        assertEquals(200L, result.commands[0].holdReleaseDelayMs)
        assertEquals(PayloadCommandTiming.SEND_ON_RELEASE, result.commands[1].timing)
        assertFalse(result.commands[2].hasModifiers)
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
        fun activate(payload: String, finitePress: Long? = null, keepAuthoredPulse: Boolean = false, turbo: Boolean = false) =
            (executor.activate(ButtonAimPayloadOwner.BASE, payload,
                immediatePressDurationMs = finitePress, preserveAuthoredPulseOnRelease = keepAuthoredPulse,
                turboSession = turbo)
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
