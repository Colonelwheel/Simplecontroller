package com.example.simplecontroller.ui

internal enum class DirectionalCommandSlot {
    UP,
    DOWN,
    LEFT,
    RIGHT
}

/**
 * Keeps directional-zone payloads active until their owning zone is no longer selected.
 *
 * New payloads are activated before old payloads are released so moving between axes or
 * boost tiers cannot briefly release an output that the new zone still owns.
 */
internal class DirectionalCommandStateController(
    private val payloadExecutor: ButtonAimPayloadExecutor,
    private val onInvalidPayload: (String) -> Unit = {}
) {
    private data class ActivePayload(
        val payload: String,
        val lease: ButtonAimPayloadLease?
    )

    private val activePayloads = linkedMapOf<DirectionalCommandSlot, ActivePayload>()

    fun reconcile(desiredPayloads: Map<DirectionalCommandSlot, String>) {
        val normalizedDesired = desiredPayloads
            .mapValues { (_, payload) -> payload.trim() }
            .filterValues { it.isNotEmpty() }
        val changedSlots = DirectionalCommandSlot.entries.filter { slot ->
            activePayloads[slot]?.payload.orEmpty() != normalizedDesired[slot].orEmpty()
        }
        if (changedSlots.isEmpty()) return

        val nextPayloads = activePayloads.toMutableMap()

        // Acquire all replacement outputs first. ButtonAimPayloadExecutor reconciles shared
        // logical outputs, so this preserves a hold when two directions use the same command.
        for (slot in changedSlots) {
            val desired = normalizedDesired[slot].orEmpty()
            if (desired.isEmpty()) {
                nextPayloads.remove(slot)
                continue
            }

            when (val result = payloadExecutor.activate(ButtonAimPayloadOwner.BASE, desired)) {
                is ButtonAimPayloadActivationResult.Activated -> {
                    nextPayloads[slot] = ActivePayload(desired, result.lease)
                }

                is ButtonAimPayloadActivationResult.Invalid -> {
                    // Remember the invalid value so a MOVE stream does not retry it repeatedly.
                    nextPayloads[slot] = ActivePayload(desired, null)
                    onInvalidPayload(result.reason)
                }

                ButtonAimPayloadActivationResult.ReleaseAll -> {
                    // Release All invalidates every lease inside the executor. Remember this
                    // zone only to avoid repeatedly firing RELEASE_ALL while it stays selected.
                    activePayloads.clear()
                    activePayloads[slot] = ActivePayload(desired, null)
                    return
                }
            }
        }

        val replacedLeases = changedSlots.mapNotNull { activePayloads[it]?.lease }
        payloadExecutor.releaseTogether(*replacedLeases.toTypedArray())

        activePayloads.clear()
        activePayloads.putAll(nextPayloads)
    }

    fun clear() {
        val leases = activePayloads.values.mapNotNull { it.lease }
        payloadExecutor.releaseTogether(*leases.toTypedArray())
        activePayloads.clear()
    }
}
