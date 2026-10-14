package com.example.simplecontroller.ui

import com.example.simplecontroller.model.*
import org.junit.Assert.*
import org.junit.Test

class FloatingRingStateMachineTest {
    private class Clock : ButtonAimPayloadScheduler {
        data class Work(val at: Long, val action: () -> Unit, var canceled: Boolean = false)
        var now = 0L
        val work = mutableListOf<Work>()
        override fun schedule(delayMs: Long, task: () -> Unit): ButtonAimScheduledTask {
            val item = Work(now + delayMs, task)
            work.add(item)
            return ButtonAimScheduledTask { item.canceled = true }
        }
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = work.filter { !it.canceled && it.at <= target }.minByOrNull { it.at } ?: break
                work.remove(next)
                now = next.at
                next.action()
            }
            now = target
        }
        fun runCanceled() { work.filter { it.canceled }.toList().forEach { it.action() } }
    }
    private class Backend : FloatingRingBackend {
        val held = mutableMapOf<String, String>()
        val events = mutableListOf<String>()
        val sensitivity = mutableListOf<Float>()
        val reservations = mutableSetOf<String>()
        val denied = mutableSetOf<String>()
        val masked = mutableSetOf<String>()
        var movement = RingPoint(0f, 0f)
        var aimBegins = 0
        var aimMoves = 0
        var paused = false
        var closes = 0
        var suppression = false
        var retainedAim = false
        private var stickAim = false
        override fun acquirePayload(slot: String, payload: String): Boolean {
            if (slot in denied) return false
            held[slot] = payload
            events.add("$slot:$payload")
            return true
        }
        override fun releasePayload(slot: String) { held.remove(slot); events.add("$slot:UP") }
        override fun retainPayload(slot: String, retained: Boolean) {}
        override fun payloadIsOwned(slot: String) = slot in held || slot in reservations
        override fun payloadMasked(slot: String) = slot in masked
        override fun reservePayload(slot: String, payload: String, retained: Boolean, exclusive: Boolean): Boolean {
            if (slot in denied) return false
            reservations.add(slot); return true
        }
        override fun endPayload(slot: String) { reservations.remove(slot); releasePayload(slot) }
        override fun beginAim(pointerId: Int, x: Float, y: Float, eventTime: Long, tuning: ButtonAimTuning): Boolean {
            retainedAim = false
            stickAim = tuning.output == ButtonAimOutput.RIGHT_STICK
            aimBegins++; sensitivity.add(tuning.sensitivity); return true
        }
        override fun updateAim(pointerId: Int, x: Float, y: Float, eventTime: Long) { aimMoves++ }
        override fun suspendAim(pointerId: Int, x: Float, y: Float, eventTime: Long) { paused = true }
        override fun resumeAim(pointerId: Int, x: Float, y: Float, eventTime: Long) { paused = false }
        override fun completeAim(autoCenter: Boolean) { retainedAim = stickAim && !autoCenter }
        override fun finishAim() { retainedAim = false }
        override fun aimIsRetained() = retainedAim
        override fun updateAimSensitivity(value: Float) { sensitivity.add(value) }
        override fun movement(x: Float, y: Float, retained: Boolean) { movement = RingPoint(x, y) }
        override fun releaseMovement() { movement = RingPoint(0f, 0f) }
        override fun mouseSuppression(active: Boolean) { suppression = active }
        override fun closeScope() { closes++ }
    }
    private class Fixture(val config: FloatingRingConfig) {
        val clock = Clock()
        val backend = Backend()
        val geometry = FloatingRingGeometry(config)
        var finishes = 0
        val published = mutableListOf<FloatingRingStatus>()
        val state = FloatingRingStateMachine(config, geometry, backend, clock, { clock.now },
            onStateChanged = { published.add(it) },
            onFinish = { finishes++ })
        val cx get() = geometry.center.x
        val cy get() = geometry.center.y
        fun down(role: FloatingStartRole = config.startRole, knobId: String = config.startKnobId) =
            state.down(7, cx, cy, clock.now, role, knobId)
        fun move(distance: Float) = state.sample(7, cx, cy - distance, clock.now)
        fun up(distance: Float = 0f) = state.up(7, cx, cy - distance, clock.now)
        fun firePresses() = backend.events.count { it == "fire:RT:1.0" }
    }
    private fun combat(
        mode: RingFireMode = RingFireMode.HOLD,
        returnDelay: Long = 300L,
        cancelOnExit: Boolean = true,
        boundaryCancel: Boolean = false,
        rearm: Boolean = false
    ) = FloatingRingConfig(
        startRole = FloatingStartRole.AIM,
        aim = RingAimSettings(continueOutside = false, participateInBoosts = true),
        knobs = listOf(RingAimKnob(fire = RingFireSettings(mode = mode,
            cancelReleaseShotOnBoundaryExit = boundaryCancel,
            rearmReleaseShotOnBoundaryReturn = rearm,
            returnFire = RingReturnFireSettings(enabled = true, delayMs = returnDelay, cancelOnExit = cancelOnExit)))),
        boost1 = RingBoostSettings(enabled = true, payload = "LT:1.0", latchOnExit = true, retainOnLift = true),
        boost2 = RingBoostSettings(enabled = true, distanceDp = 160f,
            directInnerDistanceDp = 160f, directOuterDistanceDp = 184f, payload = "X360A")
    )

    @Test fun pureAim_hasNoAutomaticAdsOrFire() {
        val f = Fixture(FloatingRingConfig(startRole = FloatingStartRole.AIM))
        assertTrue(f.down()); f.move(10f); f.up()
        assertEquals(1, f.backend.aimBegins)
        assertEquals(0, f.firePresses())
        assertTrue(f.backend.events.none { it == "ads:" || it.startsWith("ads:LT") })
    }

    @Test fun movement_isContinuousFromCenterAndCapped() {
        val f = Fixture(FloatingRingConfig())
        f.down(); f.move(22f)
        assertEquals(-.25f, f.backend.movement.y, .00001f)
        f.move(44f); assertEquals(-.5f, f.backend.movement.y, .00001f)
        f.move(200f); assertEquals(-1f, f.backend.movement.y, .00001f)
        f.move(0f); assertEquals(0f, f.backend.movement.y, 0f)
        assertEquals(0, f.backend.aimBegins)
    }

    @Test fun edgeAdjustedFirstMovement_preservesConfiguredVector() {
        val f = Fixture(FloatingRingConfig(movement = RingMovementSettings(initialY = -.5f)))
        val x = 5f; val y = 70f
        val anchor = f.geometry.startAnchor(FloatingStartRole.MOVEMENT)
        f.state.down(7, x, y, 0L, FloatingStartRole.MOVEMENT,
            offsetX = anchor.x - x, offsetY = anchor.y - y, useConfiguredMovementStart = true)
        assertEquals(-.5f, f.backend.movement.y, 0f)
        f.state.sample(7, x, y, 1L)
        assertEquals(-.5f, f.backend.movement.y, 0f)
    }

    @Test fun cumulativeDrag_stageTwoKeepsStageOneAndExitsInReverse() {
        val f = Fixture(combat())
        f.down(); f.move(170f)
        assertEquals("LT:1.0", f.backend.held["boost1"])
        assertEquals("X360A", f.backend.held["boost2"])
        f.move(125f)
        assertFalse("boost2" in f.backend.held)
        assertEquals("LT:1.0", f.backend.held["boost1"])
        f.move(0f); assertEquals("LT:1.0", f.backend.held["boost1"])
    }

    @Test fun returnFire_stationaryDeadlineStartsHoldWhileAimResumesImmediately() {
        val f = Fixture(combat())
        f.down(); assertEquals(0, f.firePresses())
        f.move(125f); assertTrue(f.backend.paused)
        f.move(0f); assertFalse(f.backend.paused)
        val aimMoves = f.backend.aimMoves
        f.clock.advance(299); assertEquals(0, f.firePresses())
        f.clock.advance(1); assertEquals(1, f.firePresses())
        assertEquals(aimMoves, f.backend.aimMoves)
        assertEquals("LT:1.0", f.backend.held["boost1"])
    }

    @Test fun earlyLift_cancelsEveryReturnFireModeWithoutFiringLater() {
        listOf(RingFireMode.HOLD, RingFireMode.TAP, RingFireMode.REPEAT, RingFireMode.RELEASE_SHOT).forEach {
            val f = Fixture(combat(it))
            f.down(); f.move(125f); f.move(0f); f.clock.advance(100); f.up()
            f.clock.advance(1000); f.clock.runCanceled()
            assertEquals("mode=$it", 0, f.firePresses())
            assertFalse(f.state.status.armed)
        }
    }

    @Test fun exitCancellation_requalifiedReturnStartsFullNewDelay() {
        val f = Fixture(combat())
        f.down(); f.move(125f); f.move(0f); f.clock.advance(200)
        f.move(60f); assertFalse(f.state.status.pending)
        f.move(125f); f.move(0f)
        f.clock.advance(299); assertEquals(0, f.firePresses())
        f.clock.advance(1); assertEquals(1, f.firePresses())
    }

    @Test fun exitCancellationOff_preservesDeadlineWithoutRestartAndAimStaysPausedOutside() {
        val f = Fixture(combat(cancelOnExit = false))
        f.down(); f.move(125f); f.move(0f); f.clock.advance(200)
        f.move(60f); assertTrue(f.state.status.pending); assertTrue(f.backend.paused)
        f.clock.advance(100); assertEquals(1, f.firePresses())
        assertTrue(f.backend.paused)
        f.move(0f); assertEquals(1, f.firePresses()); assertFalse(f.backend.paused)
    }

    @Test fun releaseShot_delayArmsThenOnlyValidLiftSendsFiniteShot() {
        val f = Fixture(combat(RingFireMode.RELEASE_SHOT))
        f.down(); f.move(125f); f.move(0f); f.clock.advance(300)
        assertTrue(f.state.status.armed); assertEquals(0, f.firePresses())
        f.up(); assertEquals(1, f.firePresses())
        f.clock.advance(35); assertFalse("fire" in f.backend.held)
    }

    @Test fun releaseShot_boundaryCanceledArmIsNotRestoredByOutsideDeadline() {
        val f = Fixture(combat(RingFireMode.RELEASE_SHOT, cancelOnExit = false, boundaryCancel = true))
        f.down(); f.move(125f); f.move(0f); f.move(60f); f.clock.advance(300)
        assertFalse(f.state.status.armed)
        f.move(0f); f.up(); assertEquals(0, f.firePresses())
    }

    @Test fun stopFiring_cancelsReturnTimerWithoutReleasingBoostAds() {
        val f = Fixture(combat())
        f.down(); f.move(125f); f.move(0f); f.state.stopFiring()
        f.clock.advance(1000); f.clock.runCanceled()
        assertEquals(0, f.firePresses()); assertEquals("LT:1.0", f.backend.held["boost1"])
        f.move(125f); f.move(0f); f.clock.advance(1000)
        assertEquals(0, f.firePresses())
    }

    @Test fun directBoostTwo_isCommandOnlyAndNeverInventsStageOne() {
        val cfg = combat().copy(startRole = FloatingStartRole.BOOST_TWO,
            boost2 = combat().boost2.copy(directTapEnabled = true))
        val f = Fixture(cfg)
        f.down(FloatingStartRole.BOOST_TWO)
        assertEquals(0, f.backend.aimBegins)
        assertEquals("X360A", f.backend.held["boost2"])
        assertFalse("boost1" in f.backend.held)
        f.move(0f); assertEquals(0, f.backend.aimBegins)
    }

    @Test fun directBoostToAim_addsAimSamePointerWithoutAdsOrLowerStage() {
        val base = combat()
        val cfg = base.copy(startRole = FloatingStartRole.BOOST_TWO, directBoostToAim = true,
            boost2 = base.boost2.copy(directTapEnabled = true))
        val f = Fixture(cfg)
        f.state.down(7, f.cx, f.cy - 170f, 0L, FloatingStartRole.BOOST_TWO)
        f.move(0f)
        assertEquals(1, f.backend.aimBegins)
        assertFalse("boost1" in f.backend.held)
        assertFalse("ads" in f.backend.held)
        assertEquals(7, f.state.status.pointerId)
    }

    @Test fun boostTwoDelayOffExitCancel_freezesPayloadAndExpiresInsideAfterReturn() {
        val base = combat()
        val cfg = base.copy(boost2 = base.boost2.copy(payload = "RT:1.0", activationDelayMs = 300,
            cancelDelayOnExit = false, latchOnExit = true),
            knobs = base.knobs.map { it.copy(fire = it.fire.copy(mode = RingFireMode.NONE,
                returnFire = RingReturnFireSettings())) })
        val f = Fixture(cfg)
        f.down(); f.move(170f); f.move(0f)
        f.clock.advance(299); assertFalse("boost2" in f.backend.held)
        f.clock.advance(1); assertEquals("RT:1.0", f.backend.held["boost2"])
        assertEquals("LT:1.0", f.backend.held["boost1"])
    }

    @Test fun boostTwoEarlyLift_cancelsPendingActionInsteadOfExecutingWaitImmediately() {
        val base = combat()
        val f = Fixture(base.copy(boost2 = base.boost2.copy(activationDelayMs = 300,
            cancelDelayOnExit = false, latchOnExit = true)))
        f.down(); f.move(170f); f.move(0f); f.clock.advance(100); f.up()
        f.clock.advance(1000); f.clock.runCanceled()
        assertTrue(f.backend.events.none { it == "boost2:X360A" })
    }

    @Test fun repeat_usesIndependentPressAndGapWithoutMoveRetriggers() {
        val cfg = FloatingRingConfig(startRole = FloatingStartRole.AIM,
            knobs = listOf(RingAimKnob(fire = RingFireSettings(mode = RingFireMode.REPEAT,
                pulsePressMs = 20, pulseGapMs = 80))))
        val f = Fixture(cfg)
        f.down(); repeat(10) { f.move(2f) }; assertEquals(1, f.firePresses())
        f.clock.advance(20); assertFalse("fire" in f.backend.held)
        f.clock.advance(79); assertEquals(1, f.firePresses())
        f.clock.advance(1); assertEquals(2, f.firePresses())
        f.up(); f.clock.advance(1000); assertEquals(2, f.firePresses())
    }

    @Test fun releaseShots_haveOnePendingShotAndRealReleaseGap() {
        val cfg = FloatingRingConfig(startRole = FloatingStartRole.AIM,
            knobs = listOf(RingAimKnob(fire = RingFireSettings(mode = RingFireMode.RELEASE_SHOT,
                pulsePressMs = 20, pulseGapMs = 80))))
        val f = Fixture(cfg)
        repeat(4) { f.down(); f.up() }
        assertEquals(1, f.firePresses())
        f.clock.advance(99); assertEquals(1, f.firePresses())
        f.clock.advance(1); assertEquals(2, f.firePresses())
        f.clock.advance(1000); assertEquals(2, f.firePresses())
    }

    @Test fun fineAim_onQualifiedReturnPersistsWhileOwnedAdsLatchSurvivesLift() {
        val base = combat()
        val f = Fixture(base.copy(aim = base.aim.copy(fine = RingFineAimSettings(enabled = true))))
        f.down(); f.move(125f); f.move(0f)
        assertEquals(.4f, f.backend.sensitivity.last(), 0f)
        f.up(); f.down()
        assertEquals(.4f, f.backend.sensitivity.last(), 0f)
        f.state.releaseAds(); assertEquals(1f, f.backend.sensitivity.last(), 0f)
        assertFalse("boost1" in f.backend.held)
    }

    @Test fun cancel_preservesOlderAdsLatchButReleasesNewGestureOutput() {
        val f = Fixture(combat())
        f.down(); f.move(125f); f.up(125f)
        assertEquals("LT:1.0", f.backend.held["boost1"])
        f.down(); f.move(170f); f.state.cancel()
        assertEquals("LT:1.0", f.backend.held["boost1"])
        assertFalse("boost2" in f.backend.held)
        assertEquals(0, f.firePresses())
    }

    @Test fun finishHistoricalSample_isIrreversibleAndInvalidatesCanceledCallbacks() {
        val f = Fixture(combat(RingFireMode.RELEASE_SHOT))
        f.down(); f.move(125f); f.move(0f)
        f.state.sample(7, f.geometry.finish.left + 1f, 1f, 1L)
        f.move(0f); f.up(); f.clock.advance(1000); f.clock.runCanceled()
        assertEquals(1, f.finishes); assertEquals(0, f.firePresses())
        assertTrue(f.backend.held.isEmpty()); assertTrue(f.backend.closes > 0)
        assertFalse(f.down())
    }

    @Test fun pointerLoss_cancelsRatherThanTransferringRoleOrFiring() {
        val f = Fixture(combat(RingFireMode.RELEASE_SHOT, returnDelay = 0))
        f.down(); f.move(125f); f.move(0f)
        f.state.sample(99, f.cx, f.cy, 1L)
        f.up(); assertEquals(0, f.firePresses()); assertEquals(-1, f.state.status.pointerId)
    }

    @Test fun boundaryRearmingAfterValidDelay_requiresReturnAndThenValidLift() {
        val f = Fixture(combat(RingFireMode.RELEASE_SHOT, cancelOnExit = false,
            boundaryCancel = true, rearm = true))
        f.down(); f.move(125f); f.move(0f); f.move(60f); f.clock.advance(300)
        assertFalse(f.state.status.armed)
        f.move(0f); assertTrue(f.state.status.armed)
        f.up(); assertEquals(1, f.firePresses())
    }

    @Test fun stationaryMouseSuppression_survivesLiftAndEndsOnlyScopedFinish() {
        val base = FloatingRingConfig(startRole = FloatingStartRole.AIM)
        val f = Fixture(base.copy(aim = base.aim.copy(suppressCameraFollow = true)))
        f.down(); assertTrue(f.backend.suppression)
        f.up(); assertTrue(f.backend.suppression)
        f.state.finish(); assertFalse(f.backend.suppression)
    }

    @Test fun movementReplacement_updatesIndicatorWithoutCenteringNewOwner() {
        val f = Fixture(FloatingRingConfig(movement = RingMovementSettings(autoCenter = false)))
        f.down(); f.move(44f); f.up(44f)
        assertTrue(f.state.status.movementRetained)
        val sent = f.backend.movement
        f.state.movementReplaced()
        assertFalse(f.state.status.movementRetained)
        assertEquals(0f, f.state.status.movementY, 0f)
        assertEquals(sent, f.backend.movement)
    }

    @Test fun disabledLayers_keepGeometryButDoNotRunOutput() {
        val config = FloatingRingConfig(movement = RingMovementSettings(enabled = false),
            aim = RingAimSettings(enabled = false), startRole = FloatingStartRole.MOVEMENT)
        val f = Fixture(config)
        assertFalse(f.down()); f.clock.advance(1000)
        assertEquals(88f, f.geometry.movementRadius, 0f)
        assertEquals(0, f.backend.aimBegins); assertTrue(f.backend.held.isEmpty())
    }

    @Test fun historicalReturn_deadlineUsesSampleTimeRatherThanDeliveryTime() {
        val f = Fixture(combat())
        f.down()
        f.clock.now = 200L
        f.state.sample(7, f.cx, f.cy - 125f, 50L)
        f.state.sample(7, f.cx, f.cy, 100L)
        f.clock.advance(199); assertEquals(0, f.firePresses())
        f.clock.advance(1); assertEquals(1, f.firePresses())
    }

    @Test fun overdueHistoricalReturn_isPostedAndFinalLiftCancelsBeforeExecution() {
        val f = Fixture(combat())
        f.down()
        f.clock.now = 1000L
        f.state.sample(7, f.cx, f.cy - 125f, 50L)
        f.state.sample(7, f.cx, f.cy, 100L)
        assertEquals(0, f.firePresses())
        f.state.up(7, f.cx, f.cy, 200L)
        f.clock.advance(1); f.clock.runCanceled()
        assertEquals(0, f.firePresses())
    }

    @Test fun historicalBoostTwoEntry_preservesCapturedSampleDeadline() {
        val base = combat()
        val f = Fixture(base.copy(boost2 = base.boost2.copy(activationDelayMs = 300,
            cancelDelayOnExit = false, latchOnExit = true)))
        f.down()
        f.clock.now = 200L
        f.state.sample(7, f.cx, f.cy - 170f, 100L)
        f.state.sample(7, f.cx, f.cy, 150L)
        f.clock.advance(199); assertFalse("boost2" in f.backend.held)
        f.clock.advance(1); assertEquals("X360A", f.backend.held["boost2"])
    }

    @Test fun repeatOffGap_keepsFiniteOwnershipReservationUntilStopped() {
        val cfg = FloatingRingConfig(startRole = FloatingStartRole.AIM,
            knobs = listOf(RingAimKnob(fire = RingFireSettings(mode = RingFireMode.REPEAT,
                pulsePressMs = 20, pulseGapMs = 80))))
        val f = Fixture(cfg)
        f.down(); f.clock.advance(20)
        assertFalse("fire" in f.backend.held)
        assertTrue("fire" in f.backend.reservations)
        f.state.stopFiring(); assertFalse("fire" in f.backend.reservations)
    }

    @Test fun directDelayedFire_stopFiringBeforeAimCancelsItsPendingAction() {
        val cfg = FloatingRingConfig(startRole = FloatingStartRole.BOOST_TWO,
            boost2 = RingBoostSettings(enabled = true, distanceDp = 160f,
                directInnerDistanceDp = 160f, directOuterDistanceDp = 184f,
                directTapEnabled = true, payload = "RT:1.0", activationDelayMs = 300,
                cancelDelayOnExit = false, latchOnExit = true))
        val f = Fixture(cfg)
        f.down(FloatingStartRole.BOOST_TWO); f.state.stopFiring()
        f.clock.advance(1000); f.clock.runCanceled()
        assertTrue(f.backend.events.none { it == "boost2:RT:1.0" })
    }

    @Test fun deniedFireOwnership_reportsOverriddenAndPreservesOtherClaims() {
        val f = Fixture(combat())
        f.backend.denied.add("fire")
        f.down(); f.move(125f); f.move(0f); f.clock.advance(300)
        assertTrue("fire" in f.state.status.overridden)
        assertEquals(0, f.firePresses())
        assertEquals("LT:1.0", f.backend.held["boost1"])
    }

    @Test fun switchingKnobMode_cancelsRetainedRepeaterWithoutResurrection() {
        val cfg = FloatingRingConfig(startRole = FloatingStartRole.AIM, startKnobId = "one", directBoostAimKnobId = "one",
            knobs = listOf(
                RingAimKnob(id = "one", x = -.4f, targetDiameterDp = 24f,
                    fire = RingFireSettings(mode = RingFireMode.REPEAT, retainFireOnLift = true)),
                RingAimKnob(id = "two", x = .4f, targetDiameterDp = 24f)))
        val f = Fixture(cfg)
        assertNull(cfg.validationError())
        assertTrue(f.down()); f.up(); assertTrue(f.state.status.repeating)
        assertTrue(f.down(knobId = "two")); assertFalse(f.state.status.repeating)
        f.clock.advance(1000); f.clock.runCanceled(); assertEquals(1, f.firePresses())
    }

    @Test fun maskedRepeatFeedback_refreshesOnChangedSourceSnapshotWithoutPolling() {
        val cfg = FloatingRingConfig(startRole = FloatingStartRole.AIM,
            knobs = listOf(RingAimKnob(fire = RingFireSettings(mode = RingFireMode.REPEAT))))
        val f = Fixture(cfg)
        f.backend.masked.add("fire"); f.down()
        assertTrue(f.published.last().blockedByOtherSource)
        val calls = f.published.size
        f.backend.masked.clear(); f.state.refreshFeedback()
        assertEquals(calls + 1, f.published.size)
        assertFalse(f.published.last().blockedByOtherSource)
    }

    @Test fun sharedAdsAlone_isNotReportedAsMaskedFire() {
        val cfg = FloatingRingConfig(startRole = FloatingStartRole.AIM,
            knobs = listOf(RingAimKnob(fire = RingFireSettings(adsPayload = "LT:1.0"))))
        val f = Fixture(cfg)
        f.backend.masked.add("ads"); f.down()
        assertFalse(f.state.status.blockedByOtherSource)
    }

    @Test fun returnObservedOnlyAtFinalUp_doesNotStartZeroDelayFireOrArmNewShot() {
        listOf(RingFireMode.HOLD, RingFireMode.TAP, RingFireMode.REPEAT, RingFireMode.RELEASE_SHOT).forEach {
            val f = Fixture(combat(it, returnDelay = 0L))
            f.down(); f.move(125f)
            f.up()
            f.clock.advance(1000)
            assertEquals("mode=$it", 0, f.firePresses())
            assertFalse(f.state.status.armed)
        }
    }

    @Test fun changingBoostTapDirection_preservesFinitePulseAndCapturesOneQueuedPayload() {
        val cfg = FloatingRingConfig(startRole = FloatingStartRole.AIM,
            aim = RingAimSettings(participateInBoosts = true),
            boost1 = RingBoostSettings(enabled = true, action = RingAction.TAP,
                pulsePressMs = 20, pulseGapMs = 80,
                directionalPayloads = DirectionalCommands(up = "X360A", right = "X360B")))
        val f = Fixture(cfg)
        f.down(); f.move(125f)
        f.state.sample(7, f.cx + 125f, f.cy, 1L)
        assertEquals("X360A", f.backend.held["boost1"])
        f.move(125f) // A third requested tap cannot replace the pending B shot or add a queue.
        f.clock.advance(99); assertEquals(1, f.backend.events.count { it == "boost1:X360A" })
        f.clock.advance(1); assertEquals("X360B", f.backend.held["boost1"])
        f.clock.advance(1000)
        assertEquals(1, f.backend.events.count { it == "boost1:X360A" })
        assertEquals(1, f.backend.events.count { it == "boost1:X360B" })
    }

    @Test fun endedFineAdsClaim_doesNotMakeLaterBoostEntryFineBeforeItsNewReturn() {
        val base = combat()
        val cfg = base.copy(aim = base.aim.copy(fine = RingFineAimSettings(enabled = true)),
            boost1 = base.boost1.copy(retainOnLift = false))
        val f = Fixture(cfg)
        f.down(); f.move(125f); f.move(0f); assertTrue(f.state.status.fine)
        f.up(); f.down(); assertFalse(f.state.status.fine)
        f.move(125f); assertFalse(f.state.status.fine)
        f.move(0f); assertTrue(f.state.status.fine)
    }

    @Test fun clampedBirthPointerOverFinish_stationaryMoveAndLiftDoNotFinish() {
        val f = Fixture(FloatingRingConfig(startRole = FloatingStartRole.AIM))
        val x = f.geometry.finish.right - 2f
        val y = f.geometry.finish.top + 2f
        val anchor = f.geometry.startAnchor(FloatingStartRole.AIM)
        assertTrue(f.geometry.finish.contains(x, y))
        assertTrue(f.state.down(7, x, y, 0L, FloatingStartRole.AIM,
            offsetX = anchor.x - x, offsetY = anchor.y - y, checkFinish = false))
        f.state.sample(7, x, y, 1L)
        f.state.stopFiring()
        f.state.sample(7, x, y, 2L)
        assertEquals(0, f.finishes)
        assertEquals(7, f.state.status.pointerId)
        f.state.up(7, x, y, 3L)
        assertEquals(0, f.finishes)
        assertEquals(0, f.backend.closes)
        assertEquals(1, f.backend.aimBegins)
    }

    @Test fun clampedBirthPointerOverFinish_exitThenHistoricalReentryFinishesOnce() {
        val f = Fixture(FloatingRingConfig(startRole = FloatingStartRole.AIM))
        val x = f.geometry.finish.right - 2f
        val y = f.geometry.finish.top + 2f
        val anchor = f.geometry.startAnchor(FloatingStartRole.AIM)
        assertTrue(f.state.down(7, x, y, 0L, FloatingStartRole.AIM,
            offsetX = anchor.x - x, offsetY = anchor.y - y, checkFinish = false))
        f.state.sample(7, x, y, 1L) // A stationary historical sample is still the birth overlap.
        assertEquals(0, f.finishes)
        f.state.sample(7, f.cx, f.cy, 2L) // First leave the actual rendered Finish rectangle.
        f.state.sample(7, x, y, 3L) // A later coalesced entry is now intentional Finish.
        f.state.sample(7, f.cx, f.cy, 4L)
        f.state.up(7, f.cx, f.cy, 5L)
        assertEquals(1, f.finishes)
        assertEquals(-1, f.state.status.pointerId)
        assertTrue(f.backend.closes > 0)
    }

    @Test fun freshDownOnExistingFinish_stillFinishesImmediately() {
        val f = Fixture(FloatingRingConfig(startRole = FloatingStartRole.AIM))
        assertTrue(f.state.down(7, f.geometry.finish.right - 2f, f.geometry.finish.top + 2f,
            0L, FloatingStartRole.AIM))
        assertEquals(1, f.finishes)
        assertEquals(0, f.backend.aimBegins)
    }

    @Test fun retainedRightStickFeedback_refreshClearsAfterExternalOwnerTakeover() {
        val cfg = FloatingRingConfig(startRole = FloatingStartRole.AIM,
            aim = RingAimSettings(tuning = defaultRingAimTuning().copy(
                output = ButtonAimOutput.RIGHT_STICK, autoCenter = false)))
        val f = Fixture(cfg)
        assertTrue(f.down()); f.move(20f); f.up()
        assertTrue(f.published.last().aimRetained)
        assertFalse(f.published.last().movementRetained)
        val calls = f.published.size
        f.backend.retainedAim = false // The adapter receives retained-RS loss from the arbiter.
        f.state.refreshFeedback()
        assertEquals(calls + 1, f.published.size)
        assertFalse(f.published.last().aimRetained)
        assertEquals(0, f.backend.closes)
    }
}
