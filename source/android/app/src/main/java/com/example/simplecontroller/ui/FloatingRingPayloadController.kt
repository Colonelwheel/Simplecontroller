package com.example.simplecontroller.ui

import com.example.simplecontroller.model.PayloadModifiers
import com.example.simplecontroller.net.PhoneOutputCoordinator
import com.example.simplecontroller.net.PhoneOutputScope

/** Keeps deferred commands and HRD claims in their original ring slot until their timers finish. */
class FloatingRingPayloadController(
    private val root: PhoneOutputScope,
    private val fireKeys: Set<String>,
    private val scheduler: ButtonAimPayloadScheduler,
    private val onReplaced: (String) -> Unit,
    private val onChanged: () -> Unit = {}
) {
    private class Runtime(val slot: String) {
        lateinit var executor: ButtonAimPayloadExecutor
        var source: ButtonAimPayloadLease? = null
        var ending = false
    }
    private val runtimes = mutableMapOf<String, Runtime>()

    fun acquire(slot: String, payload: String): Boolean {
        val keys = PhoneOutputCoordinator.payloadLogicalKeys(payload, root.player) ?: return false
        val exclusiveKeys = when (slot) {
            FloatingRingStateMachine.ADS -> emptySet()
            FloatingRingStateMachine.FIRE -> keys
            else -> keys.intersect(fireKeys)
        }
        if (!PayloadModifiers.hasModifiers(payload) && slot !in runtimes) {
            return root.acquirePayload(slot, payload, exclusiveKeys = exclusiveKeys) { onReplaced(slot) }
        }
        if (!root.reservePayload(slot, payload, exclusiveKeys = exclusiveKeys) { replaced(slot) }) return false
        val runtime = runtimes.getOrPut(slot) { createRuntime(slot) }
        runtime.ending = false
        // A normal replacement may leave its old HRD tail; the executor owns each activation separately.
        runtime.executor.releaseNormally(runtime.source)
        val result = runtime.executor.activate(ButtonAimPayloadOwner.BASE, payload)
        if (result !is ButtonAimPayloadActivationResult.Activated) {
            end(slot)
            return false
        }
        runtime.source = result.lease
        return true
    }

    fun reserve(slot: String, payload: String, retained: Boolean, exclusive: Boolean): Boolean =
        root.reservePayload(slot, payload, retained, exclusive) { replaced(slot) }

    /** An OFF gap is a source release, while its reservation remains until the transaction ends. */
    fun release(slot: String) {
        val runtime = runtimes[slot]
        if (runtime == null) root.releasePayload(slot) else {
            runtime.executor.releaseNormally(runtime.source)
            runtime.source = null
        }
    }

    fun endNormally(slot: String) {
        val runtime = runtimes[slot]
        if (runtime == null) root.endPayload(slot) else {
            runtime.ending = true
            release(slot)
            finishIfIdle(runtime)
        }
    }

    /** Finish, cancellation, replacement and disconnect never execute a deferred SOR command. */
    fun end(slot: String) {
        runtimes.remove(slot)?.executor?.releaseAllLeases()
        root.endPayload(slot)
    }

    fun close() {
        runtimes.keys.toList().forEach(::end)
    }

    private fun replaced(slot: String) {
        end(slot)
        onReplaced(slot)
    }

    private fun createRuntime(slot: String): Runtime {
        val runtime = Runtime(slot)
        val scope = root.child("payload:$slot")
        runtime.executor = ButtonAimPayloadExecutor(object : ButtonAimPayloadTransport {
            override fun sendCommand(command: String) { scope.sendCommand(command) }
            override fun sendKey(key: String, pressed: Boolean) { scope.sendKey(key, pressed) }
            override fun sendStickMacro(stickName: String, x: Float, y: Float) { scope.sendStickMacro(stickName, x, y) }
            override fun onCameraFollow(command: String) { scope.sendCommand(command) }
            override fun onScrollToggle() {}
            override fun onReleaseAll() { close() }
        }, ButtonAimPayloadScheduler { delay, task ->
            scheduler.schedule(delay) {
                task()
                finishIfIdle(runtime)
                onChanged()
            }
        })
        return runtime
    }

    private fun finishIfIdle(runtime: Runtime) {
        if (runtimes[runtime.slot] === runtime && runtime.ending && runtime.executor.activeLeaseCount() == 0) {
            runtimes.remove(runtime.slot)
            root.endPayload(runtime.slot)
        }
    }
}
