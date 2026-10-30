package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ButtonAimPayloadPolicy
import com.example.simplecontroller.model.PayloadCommandTiming
import com.example.simplecontroller.model.PayloadModifiers
import org.junit.Assert.*
import org.junit.Test

class PayloadRepeatTest {
    @Test fun repeat100_startsImmediatelyAndKeepsSourceAcrossOffGaps() {
        val f = Fixture()
        val lease = f.activate("X360A(R100)")
        f.clock.advance(260)
        assertEquals(listOf("0:X360A_HOLD", "50:X360A_RELEASE", "100:X360A_HOLD",
            "150:X360A_RELEASE", "200:X360A_HOLD", "250:X360A_RELEASE"), f.events)
        assertEquals(1, f.executor.activeLeaseCount())
        assertTrue(f.executor.leaseHasRepeat(lease))
        f.executor.releaseNormally(lease)
        f.clock.advance(1000, runCanceled = true)
        assertEquals(6, f.events.size)
        assertEquals(0, f.executor.activeLeaseCount())
        assertFalse(f.executor.leaseHasRepeat(lease))
    }

    @Test fun eachCommand_keepsItsOwnCadence() {
        val f = Fixture()
        val lease = f.activate("X360A(R100),X360B(R160)")
        f.clock.advance(235)
        f.executor.releaseNormally(lease)
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:X360A_HOLD", "50:X360A_RELEASE", "100:X360A_HOLD",
            "150:X360A_RELEASE", "200:X360A_HOLD", "235:X360A_RELEASE"), f.events.filter { "X360A" in it })
        assertEquals(listOf("0:X360B_HOLD", "50:X360B_RELEASE", "160:X360B_HOLD", "210:X360B_RELEASE"),
            f.events.filter { "X360B" in it })
    }

    @Test fun releaseDuringOn_stopsBothTimersAndReleasesImmediately() {
        val f = Fixture()
        val lease = f.activate("RT:1.0(R100)")
        f.clock.advance(25)
        f.executor.releaseNormally(lease)
        f.executor.releaseNormally(lease)
        f.clock.advance(1000, runCanceled = true)
        assertEquals(listOf("0:RT:1.0", "25:RT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun releaseDuringOff_neverRevivesACommand() {
        val f = Fixture()
        val lease = f.activate("RT:1.0(R100)(HRD200)")
        f.clock.advance(75)
        f.executor.releaseNormally(lease)
        f.clock.advance(1000, runCanceled = true)
        assertEquals(listOf("0:RT:1.0", "50:RT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun hrdDuringOn_stopsCadenceAndExtendsOnlyTheCurrentPress() {
        val f = Fixture()
        val lease = f.activate("RT:1.0(R100)(HRD200)")
        f.clock.advance(25)
        f.executor.releaseNormally(lease)
        f.clock.advance(199, runCanceled = true)
        assertEquals(listOf("0:RT:1.0"), f.events)
        f.clock.advance(1)
        assertEquals(listOf("0:RT:1.0", "225:RT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun hrdZero_releasesAtUpAndDoesNotDisableExplicitRepeat() {
        val f = Fixture()
        val lease = f.activate("RT:1.0(HRD0)(R100)")
        f.clock.advance(120)
        f.executor.releaseNormally(lease)
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:RT:1.0", "50:RT:0.0", "100:RT:1.0", "120:RT:0.0"), f.events)
    }

    @Test fun thresholdExit_ignoresHrdAndNeverPreservesRepeatingFinitePress() {
        val f = Fixture()
        val lease = f.activate("RT:1.0(R100)(HRD200)", finitePress = 90)
        f.clock.advance(25)
        f.executor.releaseNormally(lease, applyHoldReleaseDelay = false, preserveFinitePress = true)
        f.clock.advance(1000, runCanceled = true)
        assertEquals(listOf("0:RT:1.0", "25:RT:0.0"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun generatedFinitePress_expiresOtherCommandsButKeepsRepeatRunning() {
        val f = Fixture()
        val lease = f.activate("X360A(R100),X360B(I)", finitePress = 90)
        f.clock.advance(225)
        assertEquals(listOf("0:X360B_HOLD", "90:X360B_RELEASE"), f.events.filter { "X360B" in it })
        assertEquals(listOf("0:X360A_HOLD", "50:X360A_RELEASE", "100:X360A_HOLD",
            "150:X360A_RELEASE", "200:X360A_HOLD"), f.events.filter { "X360A" in it })
        f.executor.releaseNormally(lease)
        f.clock.advance(500, runCanceled = true)
        assertEquals("225:X360A_RELEASE", f.events.last())
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun globalTurbo_doesNotRestartOrTruncateExplicitRepeat() {
        val f = Fixture()
        val lease = f.activate("X360A(R100),LT:1.0(I)", finitePress = 10, turbo = true)
        f.clock.advance(30)
        f.executor.repeatImmediatePress(lease, 10)
        f.clock.advance(30)
        assertEquals(listOf("0:X360A_HOLD", "50:X360A_RELEASE"), f.events.filter { "X360A" in it })
        f.clock.advance(40)
        assertEquals("100:X360A_HOLD", f.events.last())
        f.executor.releaseNormally(lease)
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:LT:1.0", "10:LT:0.0", "30:LT:1.0", "40:LT:0.0"), f.events.filter { "LT:" in it })
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun explicitRepeatWithHrd_stillRepeatsUnderGlobalTurbo() {
        val f = Fixture()
        val lease = f.activate("LT:1.0(HRD200)(R100)", finitePress = 10, turbo = true)
        f.clock.advance(30)
        f.executor.repeatImmediatePress(lease, 10)
        f.clock.advance(90)
        f.executor.releaseNormally(lease)
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:LT:1.0", "50:LT:0.0", "100:LT:1.0", "320:LT:0.0"), f.events)
    }

    @Test fun authoredP_setsEachOnDurationAndStopsOnSourceRelease() {
        val f = Fixture()
        val lease = f.activate("RT:1.0P0.02(R100)", keepAuthoredPulse = true)
        f.clock.advance(205)
        f.executor.releaseNormally(lease)
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:RT:1.0", "20:RT:0.0", "100:RT:1.0", "120:RT:0.0",
            "200:RT:1.0", "205:RT:0.0"), f.events)
    }

    @Test fun minimumInterval_hasOneMillisecondOnAndOff() {
        val f = Fixture()
        val lease = f.activate("SPACE(R2)")
        f.clock.advance(4)
        f.executor.releaseNormally(lease)
        f.clock.advance(20, runCanceled = true)
        assertEquals(listOf("0:KEY:SPACE:true", "1:KEY:SPACE:false", "2:KEY:SPACE:true",
            "3:KEY:SPACE:false", "4:KEY:SPACE:true", "4:KEY:SPACE:false"), f.events)
    }

    @Test fun maximumInterval_isAcceptedWithoutChangingDefaultOnDuration() {
        val f = Fixture()
        val lease = f.activate("MOUSE_LEFT_DOWN(R600000)")
        f.clock.advance(600000)
        f.executor.releaseNormally(lease)
        assertEquals(listOf("0:MOUSE_LEFT_DOWN", "50:MOUSE_LEFT_UP", "600000:MOUSE_LEFT_DOWN", "600000:MOUSE_LEFT_UP"), f.events)
    }

    @Test fun hardCancelDuringOn_ignoresHrdAndDisablesAllCanceledCallbacks() {
        val f = Fixture()
        f.activate("X360A(R100)(HRD200)")
        f.clock.advance(25)
        f.executor.releaseAllLeases()
        f.clock.advance(1000, runCanceled = true)
        assertEquals(listOf("0:X360A_HOLD", "25:X360A_RELEASE"), f.events)
        assertEquals(0, f.executor.activeLeaseCount())
    }

    @Test fun oldCanceledCallbacks_cannotReleaseOrRestartANewerActivation() {
        val f = Fixture()
        val old = f.activate("X360A(R100)(HRD200)")
        f.clock.advance(25)
        f.executor.release(old)
        val current = f.activate("X360A(I)")
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:X360A_HOLD", "25:X360A_RELEASE", "25:X360A_HOLD"), f.events)
        f.executor.releaseNormally(current)
        assertEquals("525:X360A_RELEASE", f.events.last())
    }

    @Test fun overlappingBooleanOwner_masksPulsesWithoutLosingItsHold() {
        val f = Fixture()
        val base = f.activate("X360A")
        val repeat = f.activate("X360A(R100)")
        f.clock.advance(250)
        f.executor.releaseNormally(repeat)
        assertEquals(listOf("0:X360A_HOLD"), f.events)
        f.executor.release(base)
        assertEquals("250:X360A_RELEASE", f.events.last())
    }

    @Test fun directionalRepeat_doesNotForceAnotherLegitimateOwnerOff() {
        val f = Fixture()
        f.activate("X360A")
        val repeat = f.activate("X360A(R100)", owner = ButtonAimPayloadOwner.DIRECTION_ADDITION)
        f.clock.advance(250)
        f.executor.releaseNormally(repeat)
        assertEquals(listOf("0:X360A_HOLD"), f.events)
        f.executor.releaseAllLeases()
        assertEquals("250:X360A_RELEASE", f.events.last())
    }

    @Test fun triggerPulseExpiry_restoresOtherOwnerInsteadOfSendingZero() {
        val f = Fixture()
        val base = f.activate("RT:0.4")
        val repeat = f.activate("RT:1.0(R100)")
        f.clock.advance(125)
        f.executor.releaseNormally(repeat)
        assertEquals(listOf("0:RT:0.4", "0:RT:1.0", "50:RT:0.4", "100:RT:1.0", "125:RT:0.4"), f.events)
        f.executor.release(base)
        assertEquals("125:RT:0.0", f.events.last())
    }

    @Test fun sameKeySor_getsReleaseGapAfterRepeatStopsWithoutGlobalTurbo() {
        val f = Fixture()
        val lease = f.activate("RT:1.0(R100),RT:1.0(SOR)")
        f.clock.advance(25)
        f.executor.releaseNormally(lease)
        f.clock.advance(500, runCanceled = true)
        assertEquals(listOf("0:RT:1.0", "25:RT:0.0", "60:RT:1.0", "150:RT:0.0"), f.events)
    }

    @Test fun inheritedSor_requiresExplicitImmediateForRepeatBeforeAnyOutput() {
        val f = Fixture()
        val invalid = f.executor.activate(ButtonAimPayloadOwner.BASE, "LT:1.0(I),RT:1.0(R100)",
            defaultTiming = PayloadCommandTiming.SEND_ON_RELEASE)
        assertTrue(invalid is ButtonAimPayloadActivationResult.Invalid)
        assertTrue(f.events.isEmpty())
        val valid = f.executor.activate(ButtonAimPayloadOwner.BASE, "RT:1.0(I)(R100)",
            defaultTiming = PayloadCommandTiming.SEND_ON_RELEASE)
        assertTrue(valid is ButtonAimPayloadActivationResult.Activated)
        assertEquals(listOf("0:RT:1.0"), f.events)
        f.executor.releaseAllLeases()
    }

    @Test fun malformedConflictingAndOverlappingRepeatSettings_failAtomically() {
        for (command in listOf("X360A(R)", "X360A(R0)", "X360A(R1)", "X360A(R-1)",
            "X360A(R1.5)", "X360A(R600001)", "X360A(R99999999999999999999999999)",
            "X360A(Rbad)", "X360A(R100)(R200)", "RT:1.0(R100)(SOR)",
            "RT:1.0P0(R100)", "RT:1.0P0.1(R100)", "RT:1.0P0.3(R100)", "RT:0.0P0.02(R100)",
            "RELEASE_ALL(R100)", "RT:0.0(R100)", "X360A_RELEASE(R100)")) {
            val f = Fixture()
            val payload = "LT:1.0(I),$command"
            assertNotNull("$command must fail policy", ButtonAimPayloadPolicy.validationError(payload))
            assertTrue("$command must fail activation", f.executor.activate(ButtonAimPayloadOwner.BASE, payload) is ButtonAimPayloadActivationResult.Invalid)
            assertTrue("$command must emit nothing", f.events.isEmpty())
            assertEquals(0, f.executor.activeLeaseCount())
        }
    }

    @Test fun repeatParser_isCaseInsensitiveAndDetectsMalformedAttemptsForSchemaChecks() {
        val parsed = PayloadModifiers.parse("RT:1.0(i)(r100)(hrd200),X360A")
        assertNull(parsed.error)
        assertEquals(100L, parsed.commands[0].repeatIntervalMs)
        assertEquals(50L, PayloadModifiers.repeatPressDurationMs(parsed.commands[0]))
        assertNull(parsed.commands[1].repeatIntervalMs)
        assertTrue(PayloadModifiers.hasRepeatModifiers("RT:1.0(rBad)"))
        assertFalse(PayloadModifiers.hasRepeatModifiers("RT:1.0(HRD200),RT:1.0(SOR)"))
    }

    @Test fun keyboardNamesEndingInPAndDigits_arePreservedExactly() {
        val f = Fixture()
        val lease = f.activate("KP0(R100)")
        f.clock.advance(100)
        f.executor.releaseNormally(lease)
        assertEquals(listOf("0:KEY:KP0:true", "50:KEY:KP0:false", "100:KEY:KP0:true", "100:KEY:KP0:false"), f.events)
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
                     turbo: Boolean = false, owner: ButtonAimPayloadOwner = ButtonAimPayloadOwner.BASE) =
            (executor.activate(owner, payload, immediatePressDurationMs = finitePress,
                preserveAuthoredPulseOnRelease = keepAuthoredPulse, turboSession = turbo)
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
