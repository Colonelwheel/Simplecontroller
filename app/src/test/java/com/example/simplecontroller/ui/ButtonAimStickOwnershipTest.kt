package com.example.simplecontroller.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonAimStickOwnershipTest {
    @Test
    fun deliberateSwipe_requiresMovementBeyondTouchSlop() {
        val tracker = DeliberateSwipeTracker(8f)
        tracker.reset(100f, 200f)

        assertFalse(tracker.update(104f, 204f))
        assertFalse(tracker.update(108f, 200f))
        assertTrue(tracker.update(108.01f, 200f))
        assertTrue(tracker.update(100f, 200f))
    }

    @Test
    fun aimIsDeferred_whenAutoCenterIsOffOrStickAlreadyRetained() {
        assertFalse(shouldDeferButtonAimStick(autoCenter = true, hasRetainedOwner = false))
        assertTrue(shouldDeferButtonAimStick(autoCenter = false, hasRetainedOwner = false))
        assertTrue(shouldDeferButtonAimStick(autoCenter = true, hasRetainedOwner = true))
        assertTrue(shouldDeferButtonAimStick(autoCenter = false, hasRetainedOwner = true))
    }

    @Test
    fun retainedOwner_canTransferWithoutVacatingCanonicalStick() {
        val registry = ManualStickOwnershipRegistry()
        val oldOwner = Any()
        val newOwner = Any()
        val oldYield: (String) -> Unit = {}
        val newYield: (String) -> Unit = {}

        assertTrue(registry.acquire(
            "STICK_R",
            oldOwner,
            ManualStickOwnerState.ACTIVE,
            allowRetainedTakeover = false,
            onYielded = oldYield
        ).first.acquired)
        assertTrue(registry.updateState("STICK_R", oldOwner, ManualStickOwnerState.RETAINED))

        val (result, displaced) = registry.acquire(
            "STICK_R",
            newOwner,
            ManualStickOwnerState.ACTIVE,
            allowRetainedTakeover = true,
            onYielded = newYield
        )

        assertTrue(result.acquired)
        assertTrue(result.replacedRetainedOwner)
        assertSame(oldOwner, displaced?.owner)
        assertTrue(registry.owns("STICK_R", newOwner))
        assertFalse(registry.owns("STICK_R", oldOwner))
        assertTrue(registry.isOccupied("STICK_R"))
    }

    @Test
    fun activeAndDelayedOwners_cannotBeStolen() {
        for (state in listOf(ManualStickOwnerState.ACTIVE, ManualStickOwnerState.DELAY_LOCKED,
            ManualStickOwnerState.CENTERED_PENDING)) {
            val registry = ManualStickOwnershipRegistry()
            val owner = Any()
            val intruder = Any()
            registry.acquire("STICK_L", owner, state, false) {}

            val result = registry.acquire("STICK_L", intruder, ManualStickOwnerState.ACTIVE, true) {}

            assertFalse(result.first.acquired)
            assertTrue(registry.owns("STICK_L", owner))
        }
    }

    @Test
    fun leftAndRightStickOwnership_areIndependent() {
        val registry = ManualStickOwnershipRegistry()
        val leftOwner = Any()
        val rightOwner = Any()
        registry.acquire("STICK_L", leftOwner, ManualStickOwnerState.RETAINED, false) {}
        registry.acquire("STICK_R", rightOwner, ManualStickOwnerState.RETAINED, false) {}

        assertTrue(registry.release("STICK_L", leftOwner))
        assertFalse(registry.isOccupied("STICK_L"))
        assertTrue(registry.owns("STICK_R", rightOwner))
    }

    @Test
    fun invalidatedGeneration_rejectsStaleResendCallbacks() {
        val generation = CallbackGeneration()
        val oldCallback = generation.snapshot()
        assertTrue(generation.isCurrent(oldCallback))

        generation.invalidate()

        assertFalse(generation.isCurrent(oldCallback))
        assertTrue(generation.isCurrent(generation.snapshot()))
    }
}
