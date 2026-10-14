package com.example.simplecontroller.net

import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class PhoneOutputCoordinatorTest {
    private class Fixture {
        var sequence = 0L
        val packets = mutableListOf<PhoneOutputPacket>()
        val tasks = mutableListOf<() -> Unit>()
        val coordinator = PhoneOutputCoordinator("process", { sequence++ }, packets::add) { _, task -> tasks.add(task) }
        fun scope(id: String) = coordinator.beginScope(id, "player1")
        fun lastState(): String = URLDecoder.decode(packets.last { it.command.startsWith("PHONE_STATE") }.command.substringAfterLast(':'), "UTF-8")
    }

    @Test fun twoAdsOwners_finishOnePreservesTheOthersOutput() {
        val f = Fixture(); val first = f.scope("first"); val second = f.scope("second")
        first.sendCommand("MOUSE_RIGHT_DOWN"); second.sendCommand("MOUSE_RIGHT_DOWN")
        first.finish()
        assertEquals("MOUSE_RIGHT_DOWN", f.lastState())
        assertTrue(second.isActive)
        second.finish()
        assertEquals("MOUSE_RIGHT_UP", f.lastState())
    }

    @Test fun triggerClaims_mergeMaximumAndReleaseOnlyTheirOwner() {
        val f = Fixture(); val first = f.scope("first"); val second = f.scope("second")
        first.sendCommand("RT:0.4"); second.sendCommand("RT:0.8")
        assertEquals("RT:0.8", f.lastState())
        second.sendCommand("RT:0")
        assertEquals("RT:0.4", f.lastState())
    }

    @Test fun oldQueuedStateGuard_becomesInvalidAfterScopedRelease() {
        val f = Fixture(); val first = f.scope("first")
        first.sendCommand("RT:1")
        val old = f.packets.last()
        assertTrue(old.isCurrent())
        first.finish()
        assertFalse(old.isCurrent())
        assertEquals("RT:0.0", f.lastState())
    }

    @Test fun finishBlocksMouseAndPendingPulseButOtherScopeStillSends() {
        val f = Fixture(); val first = f.scope("first"); val second = f.scope("second")
        first.sendMouseDelta(1f,2f); val oldDelta = f.packets.last()
        first.sendCommand("X360A"); first.finish()
        val count = f.packets.size
        f.tasks.forEach { it() }
        assertEquals(count, f.packets.size)
        assertFalse(oldDelta.isCurrent())
        assertFalse(first.sendMouseDelta(3f,4f))
        assertTrue(second.sendMouseDelta(5f,6f))
    }

    @Test fun retainedFireReplacement_revokesOldOwnerAndCannotResume() {
        val f = Fixture(); val first = f.scope("first"); val second = f.scope("second")
        var replaced = 0
        assertTrue(first.acquirePayload("fire","RT:1",retained=true,exclusive=true) { replaced++ })
        assertTrue(second.acquirePayload("fire","RT:0.8",exclusive=true))
        assertEquals(1,replaced)
        assertFalse(first.payloadIsOwned("fire"))
        second.endPayload("fire")
        assertEquals("RT:0.0",f.lastState())
        assertFalse(first.payloadIsOwned("fire"))
    }

    @Test fun finiteReservation_survivesOffGapUntilTransactionEnds() {
        val f = Fixture(); val first = f.scope("first"); val second = f.scope("second")
        assertTrue(first.reservePayload("repeat","RT:1"))
        assertTrue(first.acquirePayload("repeat","RT:1",exclusive=true))
        first.releasePayload("repeat")
        assertTrue(first.payloadIsOwned("repeat"))
        assertFalse(second.acquirePayload("fire","RT:1",exclusive=true))
        first.endPayload("repeat")
        assertTrue(second.acquirePayload("fire","RT:1",exclusive=true))
    }

    @Test fun activeManualStickCannotBeStolenAndNeutralIsAnOwnedState() {
        val f = Fixture(); val first = f.scope("first"); val second = f.scope("second")
        assertTrue(first.sendStick("LS",0f,0f))
        assertEquals("STICK_L:0.0,0.0",f.lastState())
        assertFalse(second.sendStick("LS",1f,0f))
        first.setRetained(true)
        assertTrue(second.sendStick("LS",0.5f,0f))
        assertEquals("STICK_L:0.5,0.0",f.lastState())
    }

    @Test fun rootFinishClosesItsChildrenAndReportsFailedAcknowledgement() {
        val f = Fixture(); val root=f.scope("root"); val child=root.child("ads")
        child.sendCommand("LT:1")
        val start=f.packets.size
        var result:Boolean?=null
        root.finish { result=it }
        assertFalse(root.isActive); assertFalse(child.isActive)
        val cleanup=f.packets.drop(start)
        cleanup.forEachIndexed { index, packet -> packet.onConfirmed(index != 0) }
        assertEquals(false,result)
    }

    @Test fun newScopeGenerationWorksWhileOldHandleStaysEnded() {
        val f=Fixture(); val first=f.scope("ring"); first.finish()
        val next=f.scope("ring")
        assertTrue(next.generation > first.generation)
        assertFalse(first.sendCommand("RT:1"))
        assertTrue(next.sendCommand("RT:1"))
    }

    @Test fun badPayloadIsRejectedBeforeAnyClaimAndKnownKeyboardAliasesCombine() {
        val f=Fixture(); val first=f.scope("first"); val second=f.scope("second")
        assertFalse(first.acquirePayload("bad","LT:1 RT:nan"))
        assertFalse(first.payloadIsOwned("bad"))
        first.sendKey("control",true); second.sendKey("LCTRL",true)
        first.sendKey("control",false)
        assertEquals("KEY_DOWN:LCTRL",f.lastState())
    }

    @Test fun ordinaryRepeatedTapRequestsReplayOnlyWithoutForeignPhoneHold() {
        val f=Fixture(); val first=f.scope("first"); val second=f.scope("second")
        first.sendCommand("X360A"); first.sendCommand("X360A")
        assertTrue(f.packets.any { URLDecoder.decode(it.command,"UTF-8").contains("PHONE_REPLAY:player1|XBOX|A") })
        second.sendCommand("X360A_HOLD")
        val before=f.packets.count { it.command.startsWith("PHONE_EVENT") }
        first.sendCommand("X360A")
        assertEquals(before,f.packets.count { it.command.startsWith("PHONE_EVENT") })
    }

    @Test fun ringPayloadSupportsBareKeysAndMacrosButRejectsEmbeddedPulseTiming() {
        val f=Fixture(); val first=f.scope("first")
        assertTrue(first.acquirePayload("boost","W,SPACE,CTRL,LS:R50"))
        assertEquals(setOf("GLOBAL|KEY|W","GLOBAL|KEY|SPACE","GLOBAL|KEY|LCTRL","player1|MACRO|L"),
            PhoneOutputCoordinator.payloadLogicalKeys("W,SPACE,CTRL,LS:R50"))
        assertFalse(first.reservePayload("bad","RT:1P0.3"))
        assertNull(PhoneOutputCoordinator.payloadLogicalKeys("RT:1P0.3"))
    }

    @Test fun endedRootCannotCreateNewActiveChildOrAcquireThroughStaleCallback() {
        val f=Fixture(); val root=f.scope("ring"); root.finish()
        val count=f.packets.size
        assertFalse(root.child("stale").isActive)
        assertFalse(root.acquirePayload("stale","RT:1"))
        assertFalse(root.reservePayload("stale","RT:1"))
        assertEquals(count,f.packets.size)
    }

    @Test fun capabilityAckWaitsForLegacyPressAndRetainedWorkToBecomeIdle() {
        val gate=PhoneProtocolActivationGate(); val owner=Any()
        gate.setLegacyBusy(owner,true); gate.receiverConfirmed(true)
        assertTrue(gate.receiverSupported)
        assertFalse(gate.tryActivate(true)); assertFalse(gate.active)
        // A lift that leaves Hold/finite work running still reports this owner busy.
        gate.setLegacyBusy(owner,true)
        assertFalse(gate.tryActivate(true))
        gate.setLegacyBusy(owner,false)
        assertTrue(gate.tryActivate(true)); assertTrue(gate.pending); assertFalse(gate.active)
        assertTrue(gate.activationConfirmed(true)); assertTrue(gate.active)
        gate.setLegacyBusy(owner,true)
        assertTrue(gate.active)
        gate.invalidate()
        assertFalse(gate.active); assertFalse(gate.receiverSupported)
        gate.setLegacyBusy(owner,false)
        assertFalse(gate.tryActivate(true))
        gate.receiverConfirmed(true)
        assertFalse(gate.tryActivate(false))
        assertTrue(gate.tryActivate(true))
        assertTrue(gate.pending); assertFalse(gate.active)
        assertFalse(gate.activationConfirmed(false)); assertFalse(gate.active)
        assertFalse(gate.pending)
        assertTrue(gate.tryActivate(true))
        gate.setLegacyBusy(owner,true)
        assertFalse(gate.pending)
        assertFalse(gate.activationConfirmed(true))
        assertFalse(gate.active)
    }

    @Test fun conflictFeedbackIsPerOutputOrderedAndNeverAcceptsOtherProcessOrOldStatus() {
        val tracker=PhoneOutputConflictTracker("process")
        assertTrue(tracker.accept("PHONE_ACK_V1:process:1:MASKED:player1%7CTRIGGER%7CRT"))
        assertEquals(true,tracker.snapshot()["player1|TRIGGER|RT"])
        assertTrue(tracker.accept("PHONE_ACK_V1:process:3:CLEAR:player1%7CTRIGGER%7CRT"))
        assertFalse(tracker.accept("PHONE_ACK_V1:process:2:MASKED:player1%7CTRIGGER%7CRT"))
        assertEquals(false,tracker.snapshot()["player1|TRIGGER|RT"])
        assertFalse(tracker.accept("PHONE_ACK_V1:other:4:MASKED:player1%7CTRIGGER%7CRT"))
        assertTrue(tracker.accept("PHONE_ACK_V1:process:0:MASKED:GLOBAL%7CMOUSE%7CRIGHT"))
        assertEquals(true,tracker.snapshot()["GLOBAL|MOUSE|RIGHT"])
    }

    @Test fun compoundHoldSharesAdsWhileFireRemainsExclusiveAndAtomic() {
        val f=Fixture(); val first=f.scope("first"); val second=f.scope("second"); val third=f.scope("third")
        assertTrue(first.acquirePayload("ads","LT:1",exclusiveKeys=emptySet()))
        assertTrue(second.acquirePayload("boost","LT:1,RT:1",exclusiveKeys=setOf("player1|TRIGGER|RT")))
        assertFalse(third.acquirePayload("blocked","LT:0.5,RT:1",exclusiveKeys=setOf("player1|TRIGGER|RT")))
        assertFalse(third.payloadIsOwned("blocked"))
        first.finish()
        assertTrue(second.payloadIsOwned("boost"))
        assertEquals("LT:1.0",URLDecoder.decode(f.packets.last { it.command.startsWith("PHONE_STATE") && it.command.contains("GLOBAL").not() && URLDecoder.decode(it.command,"UTF-8").contains("player1|TRIGGER|LT") }.command.substringAfterLast(':'),"UTF-8"))
    }

    @Test fun releasedClaimBeforeFinishStillRequiresTerminalStateAckAlongsideEnd() {
        val f=Fixture(); val root=f.scope("ring")
        root.acquirePayload("fire","RT:1")
        root.releasePayload("fire") // This ordinary OFF datagram may have been lost.
        val before=f.packets.size
        var first:Boolean?=null; var duplicate:Boolean?=null
        root.finish { first=it }
        root.finish { duplicate=it }
        val cleanup=f.packets.drop(before)
        val terminal=cleanup.single { it.command.startsWith("PHONE_STATE") }
        assertEquals("RT:0.0",URLDecoder.decode(terminal.command.substringAfterLast(':'),"UTF-8"))
        cleanup.filter { it !== terminal }.forEach { it.onConfirmed(true) }
        assertNull(first); assertNull(duplicate)
        terminal.onConfirmed(false) // Bounded retries exhausted without acknowledgement.
        assertEquals(false,first); assertEquals(false,duplicate)
        var cached:Boolean?=null
        root.finish { cached=it }
        assertEquals(false,cached)
        assertEquals(before+cleanup.size,f.packets.size)
    }

    @Test fun touchedKeyFinishReconcilesAnotherOwnersAggregateAndCachesConfirmation() {
        val f=Fixture(); val first=f.scope("first"); val second=f.scope("second")
        first.sendCommand("RT:1"); second.sendCommand("RT:0.8")
        first.releaseAllClaims()
        val before=f.packets.size
        var result:Boolean?=null
        first.finish { result=it }
        assertEquals("RT:0.8",f.lastState())
        f.packets.drop(before).forEach { it.onConfirmed(true) }
        assertEquals(true,result)
        var cached:Boolean?=null
        first.finish { cached=it }
        assertEquals(true,cached)
    }

    @Test fun reservationOnlySlotIsIncludedInTerminalAcknowledgedReconciliation() {
        val f=Fixture(); val root=f.scope("ring")
        assertTrue(root.reservePayload("shot","RT:1"))
        val before=f.packets.size
        root.finish()
        assertTrue(f.packets.drop(before).any { it.command.startsWith("PHONE_STATE") })
        assertEquals("RT:0.0",f.lastState())
    }
}
