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
    private class Fixture(val config: FloatingRingConfig, finishIgnoresSwipes: Boolean = false) {
        val clock = Clock()
        val backend = Backend()
        val geometry = FloatingRingGeometry(config)
        var finishes = 0
        val published = mutableListOf<FloatingRingStatus>()
        val state = FloatingRingStateMachine(config, geometry, backend, clock, { clock.now },
            onStateChanged = { published.add(it) },
            onFinish = { finishes++ }, finishIgnoresSwipes = finishIgnoresSwipes)
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

    private fun entryFire(mode: RingFireMode = RingFireMode.HOLD, outside: Boolean = true): FloatingRingConfig {
        val base = combat(mode)
        return base.copy(knobs = base.knobs.map { it.copy(fire = it.fire.copy(
            continueFireOutside = outside, returnFire = it.fire.returnFire.copy(startOnBoostEntry = true))) })
    }

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

    @Test fun compactAimBoosts_borrowMovementBandAndRestoreMovementAfterLift() {
        val f = Fixture(combat().copy(compactAimBoosts = true))
        f.down(); f.move(47f)
        assertTrue(f.backend.held.isEmpty())
        f.move(48f)
        assertTrue("The Aim boundary itself is still Aim", f.backend.held.isEmpty())
        f.move(49f)
        assertEquals("LT:1.0", f.backend.held["boost1"])
        assertFalse("boost2" in f.backend.held)
        f.move(89f)
        assertEquals("X360A", f.backend.held["boost2"])
        assertEquals(RingPoint(0f, 0f), f.backend.movement)
        assertEquals(FloatingStartRole.AIM, f.state.status.role)
        f.up(89f)
        assertNull(f.state.status.role)
        f.down(FloatingStartRole.MOVEMENT); f.move(44f)
        assertEquals(-.5f, f.backend.movement.y, .00001f)
        f.move(89f)
        assertFalse("boost2" in f.backend.held)
        f.move(161f)
        assertEquals("X360A", f.backend.held["boost2"])
    }

    @Test fun compactAimBoosts_preserveParticipationSwitchAndDirectBoostRules() {
        val base = combat().copy(compactAimBoosts = true)
        val disabled = Fixture(base.copy(aim = base.aim.copy(participateInBoosts = false)))
        disabled.down(); disabled.move(170f)
        assertTrue(disabled.backend.held.isEmpty())
        val direct = Fixture(base.copy(startRole = FloatingStartRole.BOOST_TWO,
            boost2 = base.boost2.copy(directTapEnabled = true)))
        direct.down(); direct.move(89f)
        assertFalse("boost1" in direct.backend.held)
        assertFalse("boost2" in direct.backend.held)
        assertEquals(0, direct.backend.aimBegins)
    }

    @Test fun compactAimBoosts_largeSavedHysteresisCanStillExitAndCancelKeepsOlderLatch() {
        val base = combat().copy(compactAimBoosts = true)
        val f = Fixture(base.copy(boost1 = base.boost1.copy(hysteresisDp = 110f),
            boost2 = base.boost2.copy(hysteresisDp = 150f)))
        f.down(); f.move(89f); f.move(0f)
        assertFalse("boost2" in f.backend.held)
        assertEquals("LT:1.0", f.backend.held["boost1"])
        f.up(); f.down(); f.move(89f); f.state.cancel()
        assertEquals("LT:1.0", f.backend.held["boost1"])
        assertFalse("boost2" in f.backend.held)
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

    @Test fun entryFire_countsFromBoostAndDoesNotRestartAcrossStagesOrAimReturn() {
        val base = entryFire()
        val f = Fixture(base.copy(knobs = base.knobs.map { it.copy(fire = it.fire.copy(
            returnFire = it.fire.returnFire.copy(qualifyingStages = RingBoostStage.entries.toSet()))) }))
        f.down(); f.move(125f)
        f.clock.advance(100); f.move(170f)
        f.clock.advance(100); f.move(0f)
        f.clock.advance(99); assertEquals(0, f.firePresses())
        f.clock.advance(1); assertEquals(1, f.firePresses())
        f.move(0f); f.clock.advance(500)
        assertEquals(1, f.firePresses())
    }

    @Test fun entryFire_canExpireBeforeAimWithoutStartingFineAimOrResumingAim() {
        val base = entryFire()
        val f = Fixture(base.copy(aim = base.aim.copy(fine = RingFineAimSettings(enabled = true))))
        f.down(); f.move(125f)
        f.clock.advance(300)
        assertEquals(1, f.firePresses())
        assertTrue(f.backend.paused)
        assertFalse(f.state.status.fine)
        assertFalse(f.state.status.pending)
        f.move(0f)
        assertEquals(1, f.firePresses())
        assertFalse(f.backend.paused)
        assertTrue(f.state.status.fine)
    }

    @Test fun entryFire_outsideDisabledRemembersDeadlineUntilAimWithoutAnotherDelay() {
        val f = Fixture(entryFire(outside = false))
        f.down(); f.move(125f); f.clock.advance(300)
        assertEquals(0, f.firePresses())
        assertFalse(f.state.status.pending)
        f.move(0f)
        assertEquals(1, f.firePresses())
    }

    @Test fun entryFire_liftCancelFinishAndStopInvalidateEveryUnexpiredMode() {
        listOf(RingFireMode.HOLD, RingFireMode.TAP, RingFireMode.REPEAT, RingFireMode.RELEASE_SHOT).forEach { mode ->
            listOf("lift", "cancel", "finish", "stop").forEach { action ->
                val f = Fixture(entryFire(mode))
                f.down(); f.move(125f); f.clock.advance(100)
                when (action) {
                    "lift" -> f.up(125f)
                    "cancel" -> f.state.cancel()
                    "finish" -> f.state.finish()
                    else -> f.state.stopFiring()
                }
                f.clock.advance(1000); f.clock.runCanceled()
                assertEquals("mode=$mode action=$action", 0, f.firePresses())
                assertFalse(f.state.status.armed)
            }
        }
    }

    @Test fun entryFire_cancelOnExitAfterReturnRestartsAtNextBoostEntry() {
        listOf(0f, 47f).forEach { returnDistance ->
            val f = Fixture(entryFire().copy(compactAimBoosts = true))
            f.down(); f.move(49f); f.clock.advance(100); f.move(returnDistance)
            f.clock.advance(50); f.move(49f)
            assertTrue("return=$returnDistance", f.state.status.pending)
            f.clock.advance(299); assertEquals(0, f.firePresses())
            f.clock.advance(1); assertEquals(1, f.firePresses())
        }
    }

    @Test fun entryFire_overdueHistoricalEntryCanBeCanceledBeforeItsPostedDeadline() {
        val f = Fixture(entryFire())
        f.down(); f.clock.now = 500L
        f.state.sample(7, f.cx, f.cy - 125f, 100L)
        assertEquals(0, f.firePresses())
        f.state.up(7, f.cx, f.cy, 500L)
        f.clock.advance(0); f.clock.runCanceled()
        assertEquals(0, f.firePresses())
    }

    @Test fun entryFire_directAimUsesDestinationBeforeAimWithoutInventingAdsAndCleansUpOnLift() {
        listOf(false, true).forEach { retain ->
            val base = entryFire()
            val f = Fixture(base.copy(startRole = FloatingStartRole.BOOST_ONE, directBoostToAim = true,
                aim = base.aim.copy(suppressCameraFollow = true),
                boost1 = base.boost1.copy(directTapEnabled = true),
                knobs = base.knobs.map { it.copy(fire = it.fire.copy(adsPayload = "X360LB", retainFireOnLift = retain)) }))
            val anchor = f.geometry.startAnchor(FloatingStartRole.BOOST_ONE)
            assertTrue(f.state.down(7, anchor.x, anchor.y, 0L, FloatingStartRole.BOOST_ONE))
            f.clock.advance(300)
            assertEquals(1, f.firePresses())
            assertEquals(0, f.backend.aimBegins)
            assertFalse(f.backend.suppression)
            assertFalse("ads" in f.backend.held)
            f.state.up(7, anchor.x, anchor.y, f.clock.now)
            assertEquals("retain=$retain", retain, "fire" in f.backend.held)
            f.state.finish()
            assertFalse("fire" in f.backend.held)
        }
    }

    @Test fun entryFire_directReturnKeepsItsOutputWhenThePreviousKnobWasDifferent() {
        val base = entryFire()
        val destination = base.knobs.single().copy(id = "destination")
        val f = Fixture(base.copy(knobs = base.knobs + destination, directBoostToAim = true,
            directBoostAimKnobId = destination.id, boost1 = base.boost1.copy(directTapEnabled = true)))
        f.down(); f.up()
        val anchor = f.geometry.startAnchor(FloatingStartRole.BOOST_ONE)
        f.state.down(7, anchor.x, anchor.y, 0L, FloatingStartRole.BOOST_ONE)
        f.clock.advance(300)
        assertEquals(1, f.firePresses())
        f.move(0f)
        assertEquals(1, f.firePresses())
        assertEquals("RT:1.0", f.backend.held["fire"])
        assertEquals(2, f.backend.aimBegins)
    }

    @Test fun entryFire_pendingDirectFeedbackUsesItsDestinationBinding() {
        val base = entryFire()
        val destination = base.knobs.single().copy(id = "destination",
            fire = base.knobs.single().fire.copy(firePayload = "X360Y"))
        val f = Fixture(base.copy(knobs = base.knobs + destination, directBoostToAim = true,
            directBoostAimKnobId = destination.id, boost1 = base.boost1.copy(directTapEnabled = true)))
        f.down(); f.move(125f); f.clock.advance(300); f.up()
        assertEquals(1, f.firePresses())
        val anchor = f.geometry.startAnchor(FloatingStartRole.BOOST_ONE)
        f.state.down(7, anchor.x, anchor.y, f.clock.now, FloatingStartRole.BOOST_ONE)
        assertTrue(f.state.status.pending)
        assertEquals("X360Y", f.state.payloadForSlot(FloatingRingStateMachine.FIRE))
        f.clock.advance(300)
        assertEquals("X360Y", f.backend.held["fire"])
    }

    @Test fun entryFire_directTwoToOneStartsOnlyAtQualifierAndKeepsFirstDeadline() {
        listOf(setOf(RingBoostStage.ONE), RingBoostStage.entries.toSet()).forEach { qualifiers ->
            val base = entryFire()
            val f = Fixture(base.copy(startRole = FloatingStartRole.BOOST_TWO, compactAimBoosts = true,
                directBoostToAim = true, directBoostTwoToOne = true,
                boost2 = base.boost2.copy(directTapEnabled = true),
                knobs = base.knobs.map { it.copy(fire = it.fire.copy(
                    returnFire = it.fire.returnFire.copy(qualifyingStages = qualifiers))) }))
            val anchor = f.geometry.startAnchor(FloatingStartRole.BOOST_TWO)
            f.state.down(7, anchor.x, anchor.y, 0L, FloatingStartRole.BOOST_TWO)
            assertEquals(RingBoostStage.TWO in qualifiers, f.state.status.pending)
            f.clock.advance(100); f.move(140f)
            assertTrue(f.state.status.pending)
            f.clock.advance(100); f.move(0f)
            val remaining = if (RingBoostStage.TWO in qualifiers) 100L else 200L
            f.clock.advance(remaining - 1); assertEquals(0, f.firePresses())
            f.clock.advance(1); assertEquals(1, f.firePresses())
        }
    }

    @Test fun entryFire_directRepeatLiftOutsideEndsOutputAndCanceledCallbacks() {
        val base = entryFire(RingFireMode.REPEAT)
        val f = Fixture(base.copy(startRole = FloatingStartRole.BOOST_ONE, directBoostToAim = true,
            boost1 = base.boost1.copy(directTapEnabled = true)))
        val anchor = f.geometry.startAnchor(FloatingStartRole.BOOST_ONE)
        f.state.down(7, anchor.x, anchor.y, 0L, FloatingStartRole.BOOST_ONE)
        f.clock.advance(300)
        assertTrue(f.state.status.repeating)
        f.state.up(7, anchor.x, anchor.y, f.clock.now)
        val presses = f.firePresses()
        f.clock.advance(1000); f.clock.runCanceled()
        assertFalse(f.state.status.repeating)
        assertFalse("fire" in f.backend.held)
        assertEquals(presses, f.firePresses())
        assertEquals(0, f.backend.aimBegins)
    }

    @Test fun entryFire_boundaryCanceledReleaseShotWaitsForEligibleReturn() {
        val base = entryFire(RingFireMode.RELEASE_SHOT)
        val f = Fixture(base.copy(knobs = base.knobs.map { it.copy(fire = it.fire.copy(
            cancelReleaseShotOnBoundaryExit = true, rearmReleaseShotOnBoundaryReturn = true)) }))
        f.down(); f.move(125f); f.clock.advance(300)
        assertFalse(f.state.status.armed)
        assertEquals(0, f.firePresses())
        f.move(0f); assertTrue(f.state.status.armed)
        f.up(); assertEquals(1, f.firePresses())
    }

    @Test fun entryFire_neverUsesStaleAimSettingsForMovementOrCommandOnlyDirectBoost() {
        listOf(FloatingStartRole.MOVEMENT, FloatingStartRole.BOOST_ONE).forEach { role ->
            val base = entryFire()
            val f = Fixture(base.copy(boost1 = base.boost1.copy(directTapEnabled = true)))
            f.down(); f.up()
            f.down(role); f.move(125f); f.clock.advance(1000)
            assertEquals("role=$role", 0, f.firePresses())
            assertFalse(f.state.status.pending)
        }
    }

    @Test fun entryFire_releaseShotArmsBeforeAimButOnlyEligibleLiftFires() {
        val f = Fixture(entryFire(RingFireMode.RELEASE_SHOT))
        f.down(); f.move(125f); f.clock.advance(300)
        assertTrue(f.state.status.armed)
        assertEquals(0, f.firePresses())
        f.up(125f)
        assertEquals(1, f.firePresses())
    }

    @Test fun entryFire_completedTapCanStartAnotherQualifiedCycleAfterAimReturn() {
        val f = Fixture(entryFire(RingFireMode.TAP))
        f.down(); f.move(125f); f.clock.advance(300)
        assertEquals(1, f.firePresses())
        f.clock.advance(100); f.move(0f); f.move(125f)
        f.clock.advance(299); assertEquals(1, f.firePresses())
        f.clock.advance(1); assertEquals(2, f.firePresses())
    }

    @Test fun adjacentMovementBoosts_beginOutsideMovementAndKeepSavedBoostBandWidth() {
        val f = Fixture(combat().copy(adjacentMovementBoosts = true))
        f.down(FloatingStartRole.MOVEMENT); f.move(88f)
        assertFalse("boost1" in f.backend.held)
        assertEquals(-1f, f.backend.movement.y, 0f)
        f.move(89f)
        assertEquals("LT:1.0", f.backend.held["boost1"])
        f.move(127f); assertFalse("boost2" in f.backend.held)
        f.move(128f); assertEquals("X360A", f.backend.held["boost2"])
        assertEquals(0, f.backend.aimBegins)
    }

    @Test fun compactAimBoosts_edgeFitUsesLogicalBoundaryAndLargeNonlatchedHysteresisExits() {
        val base = combat().copy(compactAimBoosts = true)
        val f = Fixture(base.copy(boost1 = base.boost1.copy(latchOnExit = false, retainOnLift = false, hysteresisDp = 110f)))
        val x = 5f; val y = 70f
        f.state.down(7, x, y, 0L, FloatingStartRole.AIM,
            offsetX = f.cx - x, offsetY = f.cy - y, checkFinish = false)
        f.state.sample(7, x + 48f, y, 1L)
        assertFalse("boost1" in f.backend.held)
        f.state.sample(7, x + 48.1f, y, 2L)
        assertEquals("LT:1.0", f.backend.held["boost1"])
        f.state.sample(7, x, y, 3L)
        assertFalse("boost1" in f.backend.held)
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

    private fun directReturnConfig() = combat().let { base -> base.copy(
        startRole = FloatingStartRole.BOOST_TWO,
        directBoostTwoToOne = true,
        boost2 = base.boost2.copy(directTapEnabled = true)
    ) }

    @Test fun directTwoReturn_entersLowerStageOnlyOnInwardCrossingWithoutItsDirectTap() {
        val f = Fixture(directReturnConfig())
        assertFalse(f.config.boost1.directTapEnabled)
        assertTrue(f.state.down(7, f.cx, f.cy - 170f, 0L, FloatingStartRole.BOOST_TWO))
        assertFalse("boost1" in f.backend.held)
        f.move(180f); f.move(165f)
        assertFalse("boost1" in f.backend.held)
        f.move(159f)
        assertEquals("LT:1.0", f.backend.held["boost1"])
        assertFalse("boost2" in f.backend.held)
        f.move(140f); f.move(125f)
        assertEquals(1, f.backend.events.count { it == "boost1:LT:1.0" })
    }

    @Test fun directTwoReturn_respectsLowerHoldHysteresisAndLatchSettings() {
        listOf(false, true).forEach { latch ->
            val cfg = directReturnConfig().let { it.copy(boost1 = it.boost1.copy(latchOnExit = latch)) }
            val f = Fixture(cfg)
            f.state.down(7, f.cx, f.cy - 170f, 0L, FloatingStartRole.BOOST_TWO)
            f.move(119f)
            assertEquals("LT:1.0", f.backend.held["boost1"])
            f.move(117f)
            assertEquals(latch, "boost1" in f.backend.held)
            f.move(0f)
            assertEquals(latch, "boost1" in f.backend.held)
        }
    }

    @Test fun directTwoReturn_fastInnerSwipeUsesIncomingDirectionAndQualifiesFineAim() {
        val cfg = directReturnConfig().let { it.copy(
            directBoostToAim = true,
            aim = it.aim.copy(fine = RingFineAimSettings(enabled = true)),
            boost1 = it.boost1.copy(payload = "", directionalPayloads = DirectionalCommands(up = "LT:1.0"))
        ) }
        val f = Fixture(cfg)
        f.state.down(7, f.cx, f.cy - 170f, 0L, FloatingStartRole.BOOST_TWO)
        f.move(0f)
        assertEquals("LT:1.0", f.backend.held["boost1"])
        assertTrue(f.state.status.fine)
        assertEquals(.4f, f.backend.sensitivity.last(), 0f)
        assertEquals(1, f.backend.aimBegins)
        assertEquals(1, f.backend.events.count { it == "boost1:LT:1.0" })
    }

    @Test fun directTwoReturn_preservesDelayedRtAcrossInnerReturnWhenExitCancellationIsOff() {
        val cfg = directReturnConfig().let { it.copy(
            directBoostToAim = true,
            aim = it.aim.copy(fine = RingFineAimSettings(enabled = true)),
            knobs = listOf(RingAimKnob()),
            boost2 = it.boost2.copy(payload = "RT:1.0", activationDelayMs = 300L,
                cancelDelayOnExit = false, latchOnExit = true)
        ) }
        val f = Fixture(cfg)
        f.state.down(7, f.cx, f.cy - 170f, 0L, FloatingStartRole.BOOST_TWO)
        f.clock.advance(100); f.move(125f); f.move(0f)
        assertTrue(f.state.status.fine)
        assertEquals("LT:1.0", f.backend.held["boost1"])
        f.clock.advance(199); assertFalse("boost2" in f.backend.held)
        f.clock.advance(1); assertEquals("RT:1.0", f.backend.held["boost2"])
        f.state.finish()
        assertTrue(f.backend.held.isEmpty())
    }

    @Test fun directTwoReturn_disabledLowerStageNeverActivates() {
        val cfg = directReturnConfig().let { it.copy(boost1 = it.boost1.copy(enabled = false)) }
        val f = Fixture(cfg)
        f.state.down(7, f.cx, f.cy - 170f, 0L, FloatingStartRole.BOOST_TWO)
        f.move(125f); f.move(0f)
        assertTrue(f.backend.events.none { it == "boost1:LT:1.0" })
    }

    @Test fun directTwoReturn_liftCancelAndFinishCannotStartLowerAction() {
        listOf("lift", "cancel", "finish").forEach { end ->
            val f = Fixture(directReturnConfig())
            f.state.down(7, f.cx, f.cy - 170f, 0L, FloatingStartRole.BOOST_TWO)
            when (end) {
                "lift" -> f.up()
                "cancel" -> f.state.cancel()
                else -> f.state.finish()
            }
            f.clock.advance(1000); f.clock.runCanceled()
            assertTrue(end, f.backend.events.none { it == "boost1:LT:1.0" })
        }
    }

    @Test fun directTwoReturn_lowerThresholdCanReenterAfterReturnAndResetsForNextGesture() {
        val cfg = directReturnConfig().let { it.copy(boost1 = it.boost1.copy(latchOnExit = false, retainOnLift = false)) }
        val f = Fixture(cfg)
        f.state.down(7, f.cx, f.cy - 170f, 0L, FloatingStartRole.BOOST_TWO)
        f.move(125f); f.move(0f); f.move(125f)
        assertEquals(2, f.backend.events.count { it == "boost1:LT:1.0" })
        f.up(); assertFalse("boost1" in f.backend.held)
        f.state.down(7, f.cx, f.cy - 170f, 0L, FloatingStartRole.BOOST_TWO)
        f.move(180f)
        assertFalse("boost1" in f.backend.held)
        assertEquals(2, f.backend.events.count { it == "boost1:LT:1.0" })
    }

    @Test fun directTwoReturn_tapAndRepeatUseExistingTimingAndExitRules() {
        listOf(RingAction.TAP, RingAction.REPEAT).forEach { action ->
            val cfg = directReturnConfig().let { it.copy(boost1 = it.boost1.copy(
                action = action, latchOnExit = false, pulsePressMs = 20, pulseGapMs = 80,
                repeatAfterExit = false, retainOnLift = false)) }
            val f = Fixture(cfg)
            f.state.down(7, f.cx, f.cy - 170f, 0L, FloatingStartRole.BOOST_TWO)
            f.move(125f)
            assertEquals("LT:1.0", f.backend.held["boost1"])
            f.clock.advance(20); assertFalse("boost1" in f.backend.held)
            f.clock.advance(80)
            assertEquals(if (action == RingAction.REPEAT) 2 else 1,
                f.backend.events.count { it == "boost1:LT:1.0" })
            f.move(0f); f.clock.advance(1000)
            assertFalse("boost1" in f.backend.held)
            assertEquals(if (action == RingAction.REPEAT) 2 else 1,
                f.backend.events.count { it == "boost1:LT:1.0" })
        }
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

    private fun directionalBoost(action: RingAction = RingAction.HOLD) = FloatingRingConfig(
        boost1 = RingBoostSettings(enabled = true, action = action,
            directionalPayloads = DirectionalCommands(
                up = "X360A", down = "X360B", left = "X360X", right = "X360Y"))
    )

    @Test fun verticalBoostReversal_changesBothWaysWithinOneGesture() {
        val f = Fixture(directionalBoost())
        assertTrue(f.down())
        f.move(125f); assertEquals("X360A", f.backend.held["boost1"])
        f.move(-125f); assertEquals("X360B", f.backend.held["boost1"])
        f.move(125f); assertEquals("X360A", f.backend.held["boost1"])
    }

    @Test fun horizontalBoostReversal_changesBothWaysWithinOneGesture() {
        val f = Fixture(directionalBoost())
        assertTrue(f.down())
        f.state.sample(7, f.cx - 125f, f.cy, f.clock.now)
        assertEquals("X360X", f.backend.held["boost1"])
        f.state.sample(7, f.cx + 125f, f.cy, f.clock.now)
        assertEquals("X360Y", f.backend.held["boost1"])
        f.state.sample(7, f.cx - 125f, f.cy, f.clock.now)
        assertEquals("X360X", f.backend.held["boost1"])
    }

    @Test fun verticalBoostReversal_changesBothWaysAcrossLifts() {
        val f = Fixture(directionalBoost())
        assertTrue(f.down()); f.move(125f); f.up(125f)
        assertFalse("boost1" in f.backend.held)
        assertTrue(f.down()); f.move(-125f)
        assertEquals("X360B", f.backend.held["boost1"])
        f.up(-125f)
        assertFalse("boost1" in f.backend.held)
        assertTrue(f.down()); f.move(125f)
        assertEquals("X360A", f.backend.held["boost1"])
    }

    @Test fun horizontalBoostReversal_changesBothWaysAcrossLifts() {
        val f = Fixture(directionalBoost())
        assertTrue(f.down())
        f.state.sample(7, f.cx - 125f, f.cy, f.clock.now)
        f.state.up(7, f.cx - 125f, f.cy, f.clock.now)
        assertFalse("boost1" in f.backend.held)
        assertTrue(f.down())
        f.state.sample(7, f.cx + 125f, f.cy, f.clock.now)
        assertEquals("X360Y", f.backend.held["boost1"])
        f.state.up(7, f.cx + 125f, f.cy, f.clock.now)
        assertFalse("boost1" in f.backend.held)
        assertTrue(f.down())
        f.state.sample(7, f.cx - 125f, f.cy, f.clock.now)
        assertEquals("X360X", f.backend.held["boost1"])
    }

    @Test fun oppositeBlankTapDirection_doesNotQueueAnotherPriorDirectionPulse() {
        val f = Fixture(FloatingRingConfig(boost1 = RingBoostSettings(enabled = true,
            action = RingAction.TAP, pulsePressMs = 20, pulseGapMs = 80,
            directionalPayloads = DirectionalCommands(up = "X360A"))))
        assertTrue(f.down()); f.move(125f)
        assertEquals("X360A", f.backend.held["boost1"])
        f.move(0f); f.move(-125f)
        // The original Tap completes; a blank Down binding must not queue another Up Tap.
        f.clock.advance(19); assertEquals("X360A", f.backend.held["boost1"])
        f.clock.advance(1); assertFalse("boost1" in f.backend.held)
        f.clock.advance(1000)
        assertEquals(1, f.backend.events.count { it == "boost1:X360A" })
        assertFalse("boost1" in f.backend.held)
    }

    @Test fun oppositeBlankTapDirection_afterLiftDoesNotReusePreviousPayload() {
        val f = Fixture(FloatingRingConfig(boost1 = RingBoostSettings(enabled = true,
            action = RingAction.TAP, directionalPayloads = DirectionalCommands(up = "X360A"))))
        assertTrue(f.down()); f.move(125f); f.up(125f)
        f.clock.advance(1000)
        assertTrue(f.down()); f.move(-125f); f.up(-125f)
        f.clock.advance(1000)
        assertEquals(1, f.backend.events.count { it == "boost1:X360A" })
        assertFalse("boost1" in f.backend.held)
    }

    @Test fun neighboringBoostDirections_keepTwelveDpHysteresisInBothDirections() {
        val f = Fixture(directionalBoost())
        assertTrue(f.down())
        f.state.sample(7, f.cx + 125f, f.cy, f.clock.now)
        assertEquals("X360Y", f.backend.held["boost1"])
        f.state.sample(7, f.cx + 100f, f.cy - 111f, f.clock.now)
        assertEquals("X360Y", f.backend.held["boost1"])
        f.state.sample(7, f.cx + 100f, f.cy - 112f, f.clock.now)
        assertEquals("X360A", f.backend.held["boost1"])
        f.state.sample(7, f.cx + 123f, f.cy - 112f, f.clock.now)
        assertEquals("X360A", f.backend.held["boost1"])
        f.state.sample(7, f.cx + 124f, f.cy - 112f, f.clock.now)
        assertEquals("X360Y", f.backend.held["boost1"])
    }

    @Test fun oppositeMovement_preservesDeliberateLatchedAndRetainedBoostPayloads() {
        listOf(false, true).forEach { latch ->
            val base = directionalBoost()
            val f = Fixture(base.copy(boost1 = base.boost1.copy(latchOnExit = latch, retainOnLift = true)))
            assertTrue(f.down()); f.move(125f)
            if (latch) {
                f.move(-125f)
                assertEquals("X360A", f.backend.held["boost1"])
            }
            f.up(if (latch) -125f else 125f)
            assertTrue(f.down()); f.move(-125f)
            assertEquals("latch=$latch", "X360A", f.backend.held["boost1"])
            assertTrue(f.backend.events.none { it == "boost1:X360B" })
        }
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

    @Test fun finishIgnoresSwipes_allCapturedRolesKeepHistoryAndLiftAsOrdinaryInput() {
        FloatingStartRole.entries.forEach { role ->
            val base = combat(returnDelay = 0L)
            val config = base.copy(startRole = role,
                boost1 = base.boost1.copy(directTapEnabled = true),
                boost2 = base.boost2.copy(directTapEnabled = true))
            val f = Fixture(config, finishIgnoresSwipes = true)
            assertTrue("role=$role", f.down())
            val x = f.geometry.finish.right - 2f
            val y = f.geometry.finish.top + 2f
            f.state.sample(7, x, y, 1L) // The View also forwards historical samples here.
            assertEquals("role=$role", 7, f.state.status.pointerId)
            f.state.up(7, x, y, 2L)
            assertEquals("role=$role", 0, f.finishes)
            assertEquals("role=$role", -1, f.state.status.pointerId)
            assertEquals("role=$role", 0, f.backend.closes)
        }
    }

    @Test fun finishIgnoresSwipes_preservesEligibleReleaseShotAndFreshFinish() {
        val f = Fixture(combat(RingFireMode.RELEASE_SHOT, returnDelay = 0L), finishIgnoresSwipes = true)
        f.down(); f.move(125f); f.move(0f)
        assertTrue(f.state.status.armed)
        val x = f.geometry.finish.right - 2f
        val y = f.geometry.finish.top + 2f
        f.state.up(7, x, y, 1L)
        assertEquals(1, f.firePresses())
        assertEquals(0, f.finishes)
        assertTrue(f.state.down(8, x, y, 2L, FloatingStartRole.AIM))
        assertEquals(1, f.finishes)
        f.clock.advance(1000L); f.clock.runCanceled()
        assertEquals(1, f.firePresses())
        assertTrue(f.backend.held.isEmpty())
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
