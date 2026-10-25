package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ButtonAimPayloadPolicy
import com.example.simplecontroller.model.PayloadCommand
import com.example.simplecontroller.model.PayloadCommandTiming
import com.example.simplecontroller.model.PayloadModifiers

/** Independently releasable roles on one button surface. */
enum class ButtonAimPayloadOwner {
    BASE,
    ALTERNATE,
    DIRECTION_BASE,
    DIRECTION_ADDITION
}

/** Pulse tokens run once for a momentary gesture or repeat while a latched lease is retained. */
enum class ButtonAimPulseMode {
    ONCE,
    REPEAT
}

class ButtonAimPayloadLease internal constructor(
    val id: Long,
    val owner: ButtonAimPayloadOwner
)

sealed class ButtonAimPayloadActivationResult {
    data class Activated(
        val lease: ButtonAimPayloadLease?,
        val outputCount: Int,
        val edgeActionCount: Int
    ) : ButtonAimPayloadActivationResult()

    data object ReleaseAll : ButtonAimPayloadActivationResult()

    data class Invalid(val reason: String) : ButtonAimPayloadActivationResult()
}

/** Low-level output operations. Production code can delegate these to UdpClient. */
interface ButtonAimPayloadTransport {
    fun sendCommand(command: String)
    fun sendKey(key: String, pressed: Boolean)
    fun sendStickMacro(stickName: String, x: Float, y: Float)
    fun onCameraFollow(command: String)
    fun onScrollToggle()
    fun onReleaseAll()
    /** Explicit authored UP differs from releasing only this executor's legitimate claim. */
    fun onForceRelease(command: String) {
        if (command.startsWith("KEY_UP:")) sendKey(command.substringAfter(':'), false)
        else sendCommand(command)
    }
}

fun interface ButtonAimScheduledTask {
    fun cancel()
}

/** Injectable so ownership and stale-callback behavior are deterministic in JVM tests. */
fun interface ButtonAimPayloadScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): ButtonAimScheduledTask
}

/**
 * Executes leased button payloads without reading or mutating Control.payload.
 *
 * Each activation gets a unique lease. Boolean outputs remain active while any lease owns them.
 * Alternate trigger and stick-macro values take priority over base values; releasing the alternate
 * therefore restores a still-owned base value. All release operations are idempotent.
 *
 * Instances are intended to be confined to the Android main thread. A scheduler callback must
 * return to that same thread (as HandlerButtonAimPayloadScheduler does).
 */
class ButtonAimPayloadExecutor(
    private val transport: ButtonAimPayloadTransport,
    private val scheduler: ButtonAimPayloadScheduler
) {
    private enum class OutputKind { XBOX, MOUSE, KEY, TRIGGER, STICK_MACRO }

    private data class LogicalKey(val kind: OutputKind, val id: String) {
        override fun toString(): String = "${kind.name}:$id"
    }

    private sealed class DesiredOutput(open val key: LogicalKey) {
        data class Xbox(
            val button: String
        ) : DesiredOutput(LogicalKey(OutputKind.XBOX, button))

        data class Mouse(
            val button: String
        ) : DesiredOutput(LogicalKey(OutputKind.MOUSE, button))

        data class Key(
            val keyName: String
        ) : DesiredOutput(LogicalKey(OutputKind.KEY, keyName.uppercase()))

        data class Trigger(
            val trigger: String,
            val value: Float,
            val pulseDurationMs: Long? = null
        ) : DesiredOutput(LogicalKey(OutputKind.TRIGGER, trigger))

        data class StickMacro(
            val stickName: String,
            val x: Float,
            val y: Float
        ) : DesiredOutput(LogicalKey(OutputKind.STICK_MACRO, stickName))
    }

    private sealed class ParsedToken {
        data class Output(val desired: DesiredOutput) : ParsedToken()
        data class CameraFollow(val command: String) : ParsedToken()
        data object ScrollToggle : ParsedToken()
        data class RawCommand(val command: String) : ParsedToken()
        data class ForceRelease(val desired: DesiredOutput) : ParsedToken()
        data object ReleaseAll : ParsedToken()
        data class Invalid(val reason: String) : ParsedToken()
    }

    private data class PulseRuntime(
        val desired: DesiredOutput.Trigger,
        val mode: ButtonAimPulseMode,
        var task: ButtonAimScheduledTask? = null
    )

    private data class LeaseState(
        val lease: ButtonAimPayloadLease,
        val outputs: LinkedHashMap<LogicalKey, DesiredOutput> = linkedMapOf(),
        val pulses: MutableMap<LogicalKey, PulseRuntime> = mutableMapOf()
    )

    private data class ReplayState(
        var queued: Int = 0,
        var outputIsReleased: Boolean = false,
        var task: ButtonAimScheduledTask? = null
    )

    private data class ModifiedTokenRuntime(
        val command: PayloadCommand,
        val timing: PayloadCommandTiming,
        var child: ButtonAimPayloadLease? = null,
        var pressGeneration: Long = 0L,
        var sorSuppressed: Boolean = false,
        var pressTask: ButtonAimScheduledTask? = null,
        var releaseTask: ButtonAimScheduledTask? = null
    )

    private data class ModifiedLeaseState(
        val lease: ButtonAimPayloadLease,
        val tokens: List<ModifiedTokenRuntime>,
        val defaultReleaseDelayMs: Long = 0L,
        val preserveAuthoredPulseOnRelease: Boolean = false,
        val turboSession: Boolean = false,
        var sourceReleased: Boolean = false
    )

    private var nextLeaseId = 1L
    private val leases = linkedMapOf<Long, LeaseState>()
    private val effectiveOutputs = linkedMapOf<LogicalKey, DesiredOutput>()
    private val replays = mutableMapOf<LogicalKey, ReplayState>()
    private val modifiedLeases = linkedMapOf<Long, ModifiedLeaseState>()

    /** Returns null when activation is safe, otherwise a user-facing validation reason. */
    fun validationError(payload: String): String? = ButtonAimPayloadPolicy.validationError(payload)

    /** TouchAim states must be fully reversible, so every token must own a releasable output. */
    fun statePayloadValidationError(payload: String): String? =
        ButtonAimPayloadPolicy.stateValidationError(payload)

    fun activate(
        owner: ButtonAimPayloadOwner,
        payload: String,
        pulseMode: ButtonAimPulseMode = ButtonAimPulseMode.ONCE,
        defaultTiming: PayloadCommandTiming = PayloadCommandTiming.IMMEDIATE,
        immediatePressDurationMs: Long? = null,
        defaultReleaseDelayMs: Long = 0L,
        preserveAuthoredPulseOnRelease: Boolean = false,
        turboSession: Boolean = false
    ): ButtonAimPayloadActivationResult {
        if (PayloadModifiers.hasModifiers(payload) || defaultTiming != PayloadCommandTiming.IMMEDIATE ||
            immediatePressDurationMs != null
        ) return activateModified(owner, payload, pulseMode, defaultTiming, immediatePressDurationMs,
            defaultReleaseDelayMs, preserveAuthoredPulseOnRelease, turboSession)
        val rawTokens = tokenize(payload)
        if (rawTokens.isEmpty()) {
            return ButtonAimPayloadActivationResult.Invalid("Payload is blank")
        }

        val parsed = rawTokens.map(::parseToken)
        if (parsed.any { it is ParsedToken.ReleaseAll }) {
            clearWithoutSendingReleases()
            transport.onReleaseAll()
            return ButtonAimPayloadActivationResult.ReleaseAll
        }
        parsed.filterIsInstance<ParsedToken.Invalid>().firstOrNull()?.let {
            return ButtonAimPayloadActivationResult.Invalid(it.reason)
        }

        val hasOutputs = parsed.any { it is ParsedToken.Output }
        val leaseState = if (hasOutputs) {
            val lease = ButtonAimPayloadLease(nextLeaseId++, owner)
            LeaseState(lease).also { leases[lease.id] = it }
        } else {
            null
        }

        var edgeActionCount = 0
        parsed.forEach { token ->
            when (token) {
                is ParsedToken.Output -> {
                    val state = checkNotNull(leaseState)
                    replaceLeaseOutput(state, token.desired, pulseMode)
                }

                is ParsedToken.CameraFollow -> {
                    transport.onCameraFollow(token.command)
                    edgeActionCount++
                }

                ParsedToken.ScrollToggle -> {
                    transport.onScrollToggle()
                    edgeActionCount++
                }

                is ParsedToken.RawCommand -> {
                    transport.sendCommand(token.command)
                    edgeActionCount++
                }

                is ParsedToken.ForceRelease -> {
                    forceRelease(token.desired)
                    edgeActionCount++
                }

                ParsedToken.ReleaseAll, is ParsedToken.Invalid -> Unit
            }
        }

        return ButtonAimPayloadActivationResult.Activated(
            lease = leaseState?.lease,
            outputCount = leaseState?.outputs?.size ?: 0,
            edgeActionCount = edgeActionCount
        )
    }

    private fun activateModified(
        owner: ButtonAimPayloadOwner,
        payload: String,
        pulseMode: ButtonAimPulseMode,
        defaultTiming: PayloadCommandTiming,
        immediatePressDurationMs: Long?,
        defaultReleaseDelayMs: Long,
        preserveAuthoredPulseOnRelease: Boolean,
        turboSession: Boolean
    ): ButtonAimPayloadActivationResult {
        validationError(payload)?.let { return ButtonAimPayloadActivationResult.Invalid(it) }
        val commands = PayloadModifiers.parse(payload).commands
        // Deferred/finite sessions must be reversible, even when their default is inherited.
        commands.firstOrNull { parseToken(it.command) !is ParsedToken.Output }?.let {
            return ButtonAimPayloadActivationResult.Invalid("Timed payload commands must be releasable: ${it.command}")
        }
        val parent = ModifiedLeaseState(
            ButtonAimPayloadLease(nextLeaseId++, owner),
            commands.map { ModifiedTokenRuntime(it, it.timing ?: defaultTiming) },
            defaultReleaseDelayMs.coerceAtLeast(0L), preserveAuthoredPulseOnRelease, turboSession
        )
        modifiedLeases[parent.lease.id] = parent
        parent.tokens.forEach { runtime ->
            if (runtime.timing == PayloadCommandTiming.IMMEDIATE) {
                startModifiedImmediate(parent, runtime, pulseMode, immediatePressDurationMs)
            }
        }
        return ButtonAimPayloadActivationResult.Activated(parent.lease, commands.size, 0)
    }

    private fun startModifiedImmediate(parent: ModifiedLeaseState, runtime: ModifiedTokenRuntime,
                                       pulseMode: ButtonAimPulseMode, pressDurationMs: Long?) {
        runtime.child = (activate(parent.lease.owner, runtime.command.command, pulseMode)
            as ButtonAimPayloadActivationResult.Activated).lease
        val authoredPulse = (parseToken(runtime.command.command) as? ParsedToken.Output)
            ?.desired.let { it as? DesiredOutput.Trigger }?.pulseDurationMs
        val finitePressMs = pressDurationMs.takeUnless { parent.turboSession && runtime.command.hasHoldReleaseDelay }
        if (finitePressMs != null || (authoredPulse != null && pulseMode == ButtonAimPulseMode.ONCE)) {
            val generation = ++runtime.pressGeneration
            runtime.pressTask = scheduler.schedule((authoredPulse ?: checkNotNull(finitePressMs)).coerceAtLeast(1L)) {
                if (modifiedLeases[parent.lease.id] !== parent || runtime.pressGeneration != generation) return@schedule
                runtime.pressTask = null
                release(runtime.child)
                runtime.child = null
                finishModifiedIfIdle(parent)
            }
        }
    }

    /** Turbo repeats immediate components without completing the physical source's SOR arm. */
    fun repeatImmediatePress(lease: ButtonAimPayloadLease?, pressDurationMs: Long) {
        val parent = modifiedLeases[lease?.id] ?: return
        if (parent.sourceReleased) return
        parent.tokens.filter { it.timing == PayloadCommandTiming.IMMEDIATE && !it.command.hasHoldReleaseDelay }.forEach { runtime ->
            runtime.pressTask?.cancel()
            runtime.pressGeneration++
            release(runtime.child)
            startModifiedImmediate(parent, runtime, ButtonAimPulseMode.ONCE, pressDurationMs)
        }
    }

    fun suppressSendOnRelease(lease: ButtonAimPayloadLease?) {
        modifiedLeases[lease?.id]?.tokens?.filter { it.timing == PayloadCommandTiming.SEND_ON_RELEASE }
            ?.forEach { it.sorSuppressed = true }
    }

    /** A normal source exit fires SOR once; cleanup must continue using [release]. */
    fun releaseNormally(lease: ButtonAimPayloadLease?, applyHoldReleaseDelay: Boolean = true, preserveFinitePress: Boolean = false,
                        suppressSendOnRelease: Boolean = false) {
        if (lease == null) return
        val state = modifiedLeases[lease.id]
        if (state == null) {
            release(lease)
            return
        }
        val alreadyReleased = state.sourceReleased
        if (alreadyReleased && !state.tokens.any { it.pressTask != null }) return
        state.sourceReleased = true
        // Complete immediate components first so a same-key SOR never inherits an old Turbo ON.
        state.tokens.sortedBy { it.timing == PayloadCommandTiming.SEND_ON_RELEASE }.forEach { runtime ->
            val delay = if (applyHoldReleaseDelay) runtime.command.holdReleaseDelayMs else 0L
            val authoredPulse = (parseToken(runtime.command.command) as? ParsedToken.Output)
                ?.desired.let { it as? DesiredOutput.Trigger }?.pulseDurationMs
            if (preserveFinitePress && runtime.timing == PayloadCommandTiming.IMMEDIATE && runtime.pressTask != null) {
                return@forEach
            }
            if (state.preserveAuthoredPulseOnRelease && authoredPulse != null && delay == 0L &&
                runtime.timing == PayloadCommandTiming.IMMEDIATE && runtime.pressTask != null
            ) return@forEach
            runtime.pressTask?.cancel()
            runtime.pressGeneration++
            runtime.pressTask = null
            if (runtime.timing == PayloadCommandTiming.SEND_ON_RELEASE) {
                if (alreadyReleased || suppressSendOnRelease || runtime.sorSuppressed) return@forEach
                val token = parseToken(runtime.command.command) as ParsedToken.Output
                val pulseMs = (token.desired as? DesiredOutput.Trigger)?.pulseDurationMs
                    ?: PayloadModifiers.RELEASE_PRESS_DURATION_MS
                val plainCommand = runtime.command.command.replace(Regex("(?i)P[0-9]+(?:\\.[0-9]+)?$"), "")
                val fire = {
                    runtime.child = (activate(state.lease.owner, plainCommand) as ButtonAimPayloadActivationResult.Activated).lease
                    scheduleModifiedRelease(state, runtime, pulseMs + delay)
                }
                val inheritedDelay = if (runtime.command.timing == null) state.defaultReleaseDelayMs else 0L
                val repeatsSameKey = state.turboSession && state.tokens.any { immediate ->
                    immediate.timing == PayloadCommandTiming.IMMEDIATE && !immediate.command.hasHoldReleaseDelay &&
                        (parseToken(immediate.command.command) as? ParsedToken.Output)?.desired?.key == token.desired.key
                }
                // Keep the release revision visible before the new press revision is published.
                val activationDelay = maxOf(inheritedDelay, if (repeatsSameKey) DIRECTION_REPLAY_GAP_MS else 0L)
                if (activationDelay > 0L) {
                    runtime.releaseTask = scheduler.schedule(activationDelay) {
                        if (modifiedLeases[state.lease.id] === state) fire()
                    }
                } else fire()
            } else if (runtime.child?.id in leases) {
                // HRD extends an existing live press; it never revives an already finished P pulse.
                leases[runtime.child?.id]?.let { child ->
                    child.pulses.keys.toList().forEach { cancelPulse(child, it) }
                }
                if (delay == 0L) {
                    release(runtime.child)
                    runtime.child = null
                } else scheduleModifiedRelease(state, runtime, delay)
            } else runtime.child = null
        }
        finishModifiedIfIdle(state)
    }

    fun releaseNormallyTogether(vararg payloadLeases: ButtonAimPayloadLease?, applyHoldReleaseDelay: Boolean = true) {
        // Keep the old atomic reconciliation for ordinary payloads.
        releaseTogether(*payloadLeases.filter { it?.id !in modifiedLeases }.toTypedArray())
        payloadLeases.filter { it?.id in modifiedLeases }.forEach { releaseNormally(it, applyHoldReleaseDelay) }
    }

    fun releaseOwnerNormally(owner: ButtonAimPayloadOwner, applyHoldReleaseDelay: Boolean = true) {
        val modified = modifiedLeases.values.filter { it.lease.owner == owner }.map { it.lease }
        val children = modifiedLeases.values.flatMap { it.tokens }.mapNotNull { it.child?.id }.toSet()
        releaseLeaseIds(leases.values.filter { it.lease.owner == owner && it.lease.id !in children }
            .mapTo(linkedSetOf()) { it.lease.id })
        modified.forEach { releaseNormally(it, applyHoldReleaseDelay) }
    }

    private fun scheduleModifiedRelease(state: ModifiedLeaseState, runtime: ModifiedTokenRuntime, delayMs: Long) {
        runtime.releaseTask = scheduler.schedule(delayMs.coerceAtLeast(1L)) {
            if (modifiedLeases[state.lease.id] !== state) return@schedule
            runtime.releaseTask = null
            release(runtime.child)
            runtime.child = null
            finishModifiedIfIdle(state)
        }
    }

    private fun finishModifiedIfIdle(state: ModifiedLeaseState) {
        if (state.sourceReleased && state.tokens.all { it.pressTask == null && it.releaseTask == null && it.child == null }) {
            modifiedLeases.remove(state.lease.id)
        }
    }

    fun release(lease: ButtonAimPayloadLease?) {
        if (lease == null) return
        releaseTogether(lease)
    }

    /** Removes all supplied leases before reconciling, avoiding transient base restoration. */
    fun releaseTogether(vararg payloadLeases: ButtonAimPayloadLease?) {
        val ids = payloadLeases.filterNotNull().mapTo(linkedSetOf()) { it.id }
        releaseLeaseIds(ids)
    }

    fun releaseOwner(owner: ButtonAimPayloadOwner) {
        val ids = (leases.values.map { it.lease } + modifiedLeases.values.map { it.lease })
            .filter { it.owner == owner }
            .mapTo(linkedSetOf()) { it.id }
        releaseLeaseIds(ids)
    }

    fun releaseAllLeases() {
        releaseLeaseIds((leases.keys + modifiedLeases.keys).toCollection(linkedSetOf()))
    }

    fun activeLeaseCount(owner: ButtonAimPayloadOwner? = null): Int {
        val children = modifiedLeases.values.flatMap { it.tokens }.mapNotNull { it.child?.id }.toSet()
        return (leases.values.map { it.lease }.filter { it.id !in children } + modifiedLeases.values.map { it.lease })
            .count { owner == null || it.owner == owner }
    }

    /** Useful to validate collisions before activation and in focused state-machine tests. */
    fun canonicalLogicalKey(command: String): String? {
        val plain = PayloadModifiers.parse(command).takeIf { it.error == null }?.commands?.singleOrNull()?.command ?: return null
        return when (val token = parseToken(plain)) {
            is ParsedToken.Output -> token.desired.key.toString()
            is ParsedToken.ForceRelease -> token.desired.key.toString()
            else -> null
        }
    }

    private fun replaceLeaseOutput(
        state: LeaseState,
        desired: DesiredOutput,
        pulseMode: ButtonAimPulseMode
    ) {
        val repeatedDirectionOutput = state.lease.owner == ButtonAimPayloadOwner.DIRECTION_ADDITION &&
            desired.key !in state.outputs && effectiveOutputs[desired.key] != null &&
            sameEffectiveOutput(effectiveOutputs[desired.key], desired)
        cancelPulse(state, desired.key)
        state.outputs[desired.key] = desired
        reconcile(desired.key)
        if (repeatedDirectionOutput) replayHeldOutput(desired.key)

        val pulse = desired as? DesiredOutput.Trigger ?: return
        if (pulse.pulseDurationMs == null) return

        val runtime = PulseRuntime(pulse, pulseMode)
        state.pulses[desired.key] = runtime
        if (pulseMode == ButtonAimPulseMode.REPEAT) {
            scheduleRepeatingPulseOff(state.lease.id, desired.key, runtime)
        } else {
            runtime.task = scheduler.schedule(pulse.pulseDurationMs) {
                val current = leases[state.lease.id] ?: return@schedule
                if (current.pulses[desired.key] !== runtime) return@schedule
                current.pulses.remove(desired.key)
                current.outputs.remove(desired.key)
                reconcile(desired.key)
                if (current.outputs.isEmpty() && current.pulses.isEmpty()) {
                    leases.remove(current.lease.id)
                }
            }
        }
    }

    private fun scheduleRepeatingPulseOff(
        leaseId: Long,
        key: LogicalKey,
        runtime: PulseRuntime
    ) {
        val cycleMs = runtime.desired.pulseDurationMs!!.coerceAtLeast(2L)
        val onDurationMs = (cycleMs / 2L).coerceAtLeast(1L)
        runtime.task = scheduler.schedule(onDurationMs) {
            val state = leases[leaseId] ?: return@schedule
            if (state.pulses[key] !== runtime) return@schedule
            state.outputs.remove(key)
            reconcile(key)
            scheduleRepeatingPulseOn(leaseId, key, runtime, cycleMs - onDurationMs)
        }
    }

    private fun scheduleRepeatingPulseOn(
        leaseId: Long,
        key: LogicalKey,
        runtime: PulseRuntime,
        delayMs: Long
    ) {
        runtime.task = scheduler.schedule(delayMs.coerceAtLeast(1L)) {
            val state = leases[leaseId] ?: return@schedule
            if (state.pulses[key] !== runtime) return@schedule
            state.outputs[key] = runtime.desired
            reconcile(key)
            scheduleRepeatingPulseOff(leaseId, key, runtime)
        }
    }

    private fun releaseLeaseIds(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val affectedKeys = linkedSetOf<LogicalKey>()
        val allIds = ids.toMutableSet()
        ids.forEach { id ->
            modifiedLeases.remove(id)?.tokens?.forEach { runtime ->
                runtime.pressTask?.cancel()
                runtime.pressGeneration++
                runtime.releaseTask?.cancel()
                runtime.child?.let { allIds += it.id }
            }
        }
        allIds.forEach { id ->
            val state = leases.remove(id) ?: return@forEach
            affectedKeys.addAll(state.outputs.keys)
            affectedKeys.addAll(state.pulses.keys)
            state.pulses.values.forEach { it.task?.cancel() }
            state.pulses.clear()
            state.outputs.clear()
        }
        affectedKeys.forEach(::reconcile)
    }

    private fun forceRelease(desired: DesiredOutput) {
        leases.values.forEach { state ->
            cancelPulse(state, desired.key)
            state.outputs.remove(desired.key)
        }
        val alreadyReleased = cancelReplay(desired.key)
        val previous = effectiveOutputs.remove(desired.key)
        if (!alreadyReleased) emitForcedInactive(previous ?: desired)
    }

    private fun cancelPulse(state: LeaseState, key: LogicalKey) {
        state.pulses.remove(key)?.task?.cancel()
    }

    private fun reconcile(key: LogicalKey) {
        val previous = effectiveOutputs[key]
        val next = effectiveOutput(key)
        if (replays[key]?.outputIsReleased == true) {
            if (next == null) cancelReplay(key)
            if (next == null) effectiveOutputs.remove(key) else effectiveOutputs[key] = next
            return
        }
        if (sameEffectiveOutput(previous, next)) return

        if (next == null) cancelReplay(key)

        when {
            next == null && previous != null -> emitInactive(previous)
            next != null -> emitActive(next)
        }
        if (next == null) effectiveOutputs.remove(key) else effectiveOutputs[key] = next
    }

    /** A new directional stage using an already held output needs a fresh physical edge. */
    private fun replayHeldOutput(key: LogicalKey) {
        val replay = replays.getOrPut(key) { ReplayState() }
        replay.queued++
        if (replay.task == null && !replay.outputIsReleased) startNextReplay(key, replay)
    }

    private fun startNextReplay(key: LogicalKey, replay: ReplayState) {
        val output = effectiveOutputs[key] ?: run {
            replays.remove(key)
            return
        }
        replay.queued--
        emitInactive(output)
        replay.outputIsReleased = true
        replay.task = scheduler.schedule(DIRECTION_REPLAY_GAP_MS) {
            replay.task = null
            val current = effectiveOutputs[key]
            if (current == null || replays[key] !== replay) return@schedule
            emitActive(current)
            replay.outputIsReleased = false
            if (replay.queued > 0) {
                replay.task = scheduler.schedule(DIRECTION_REPLAY_GAP_MS) {
                    replay.task = null
                    if (replays[key] === replay) startNextReplay(key, replay)
                }
            } else {
                replays.remove(key)
            }
        }
    }

    /** Returns whether the wire output was already released during a queued replay. */
    private fun cancelReplay(key: LogicalKey): Boolean {
        val replay = replays.remove(key) ?: return false
        replay.task?.cancel()
        return replay.outputIsReleased
    }

    private fun effectiveOutput(key: LogicalKey): DesiredOutput? {
        val candidates = leases.values.mapNotNull { state ->
            state.outputs[key]?.let { Triple(state.lease.owner, state.lease.id, it) }
        }
        if (candidates.isEmpty()) return null

        return when (key.kind) {
            OutputKind.XBOX, OutputKind.MOUSE, OutputKind.KEY -> candidates.first().third
            OutputKind.TRIGGER, OutputKind.STICK_MACRO -> candidates.maxWithOrNull(
                compareBy<Triple<ButtonAimPayloadOwner, Long, DesiredOutput>>(
                    { if (it.first == ButtonAimPayloadOwner.ALTERNATE) 1 else 0 },
                    { it.second }
                )
            )?.third
        }
    }

    private fun sameEffectiveOutput(a: DesiredOutput?, b: DesiredOutput?): Boolean = when {
        a == null || b == null -> a == b
        a is DesiredOutput.Xbox && b is DesiredOutput.Xbox -> true
        a is DesiredOutput.Mouse && b is DesiredOutput.Mouse -> true
        a is DesiredOutput.Key && b is DesiredOutput.Key -> true
        a is DesiredOutput.Trigger && b is DesiredOutput.Trigger -> a.value == b.value
        a is DesiredOutput.StickMacro && b is DesiredOutput.StickMacro ->
            a.x == b.x && a.y == b.y

        else -> false
    }

    private fun emitActive(output: DesiredOutput) {
        when (output) {
            is DesiredOutput.Xbox -> transport.sendCommand("X360${output.button}_HOLD")
            is DesiredOutput.Mouse -> transport.sendCommand("MOUSE_${output.button}_DOWN")
            is DesiredOutput.Key -> transport.sendKey(output.keyName, true)
            is DesiredOutput.Trigger ->
                transport.sendCommand("${output.trigger}:${wireFloat(output.value)}")

            is DesiredOutput.StickMacro ->
                transport.sendStickMacro(output.stickName, output.x, output.y)
        }
    }

    private fun emitInactive(output: DesiredOutput) {
        when (output) {
            is DesiredOutput.Xbox -> transport.sendCommand("X360${output.button}_RELEASE")
            is DesiredOutput.Mouse -> transport.sendCommand("MOUSE_${output.button}_UP")
            is DesiredOutput.Key -> transport.sendKey(output.keyName, false)
            is DesiredOutput.Trigger -> transport.sendCommand("${output.trigger}:0.0")
            is DesiredOutput.StickMacro -> transport.sendStickMacro(output.stickName, 0f, 0f)
        }
    }

    private fun emitForcedInactive(output: DesiredOutput) {
        when (output) {
            is DesiredOutput.Xbox -> transport.onForceRelease("X360${output.button}_RELEASE")
            is DesiredOutput.Mouse -> transport.onForceRelease("MOUSE_${output.button}_UP")
            is DesiredOutput.Key -> transport.onForceRelease("KEY_UP:${output.keyName}")
            is DesiredOutput.Trigger -> transport.onForceRelease("${output.trigger}:0.0")
            is DesiredOutput.StickMacro -> transport.sendStickMacro(output.stickName, 0f, 0f)
        }
    }

    private fun clearWithoutSendingReleases() {
        modifiedLeases.values.flatMap { it.tokens }.forEach {
            it.pressTask?.cancel()
            it.releaseTask?.cancel()
        }
        modifiedLeases.clear()
        leases.values.forEach { state ->
            state.pulses.values.forEach { it.task?.cancel() }
        }
        leases.clear()
        effectiveOutputs.clear()
        replays.values.forEach { it.task?.cancel() }
        replays.clear()
    }

    private fun parseToken(raw: String): ParsedToken {
        if (raw.isBlank()) return ParsedToken.Invalid("Payload contains a blank command")
        val command = raw.trim().uppercase()

        if (command == "RELEASE_ALL") return ParsedToken.ReleaseAll
        if (command == "SCROLL_MODE_TOGGLE") return ParsedToken.ScrollToggle
        if (command == "CAMERA_FOLLOW" || command == "CAMERA_FOLLOW:1" ||
            command == "CAMERA_FOLLOW:0"
        ) {
            return ParsedToken.CameraFollow(command)
        }

        STICK_MACRO_REGEX.matchEntire(command)?.let { match ->
            val stick = match.groupValues[1]
            val direction = match.groupValues[2]
            val amount = ((match.groupValues[3].toFloatOrNull() ?: 100f)
                .coerceIn(0f, 100f)) / 100f
            val (rawX, rawY) = directionVector(direction)
                ?: return ParsedToken.Invalid("Unknown stick direction: $raw")
            return ParsedToken.Output(
                DesiredOutput.StickMacro(stick, rawX * amount, rawY * amount)
            )
        }

        TRIGGER_REGEX.matchEntire(command)?.let { match ->
            val trigger = match.groupValues[1]
            val value = match.groupValues[2].toFloatOrNull()
                ?: return ParsedToken.Invalid("Invalid trigger value: $raw")
            if (value !in 0f..1f) return ParsedToken.Invalid("Trigger value must be 0.0-1.0: $raw")
            val pulseMs = match.groupValues[3].takeIf { it.isNotEmpty() }?.toFloatOrNull()?.let {
                (it * 1000f).toLong().coerceAtLeast(1L)
            }
            val output = DesiredOutput.Trigger(trigger, value, pulseMs)
            return if (value == 0f && pulseMs == null) {
                ParsedToken.ForceRelease(output)
            } else {
                ParsedToken.Output(output)
            }
        }
        if (command.startsWith("LT:") || command.startsWith("RT:")) {
            return ParsedToken.Invalid("Invalid trigger command: $raw")
        }

        XBOX_REGEX.matchEntire(command)?.let { match ->
            val output = DesiredOutput.Xbox(match.groupValues[1])
            return if (match.groupValues[2] == "RELEASE") {
                ParsedToken.ForceRelease(output)
            } else {
                ParsedToken.Output(output)
            }
        }
        if (command.startsWith("X360")) {
            return ParsedToken.Invalid("Invalid Xbox command: $raw")
        }

        MOUSE_REGEX.matchEntire(command)?.let { match ->
            val output = DesiredOutput.Mouse(match.groupValues[1])
            return if (match.groupValues[2] == "UP") {
                ParsedToken.ForceRelease(output)
            } else {
                ParsedToken.Output(output)
            }
        }
        if (command.startsWith("MOUSE_")) {
            return ParsedToken.RawCommand(raw.trim())
        }

        if (command.startsWith("TOUCHPAD:") || command.startsWith("STICK")) {
            return ParsedToken.RawCommand(raw.trim())
        }

        if (command.startsWith("KEY_DOWN:")) {
            val key = raw.substringAfter(':').trim()
            return if (key.isEmpty()) ParsedToken.Invalid("Keyboard key is blank: $raw")
            else ParsedToken.Output(DesiredOutput.Key(key))
        }
        if (command.startsWith("KEY_UP:")) {
            val key = raw.substringAfter(':').trim()
            return if (key.isEmpty()) ParsedToken.Invalid("Keyboard key is blank: $raw")
            else ParsedToken.ForceRelease(DesiredOutput.Key(key))
        }

        return ParsedToken.Output(DesiredOutput.Key(raw.trim()))
    }

    private fun directionVector(direction: String): Pair<Float, Float>? = when (direction) {
        "R", "RIGHT" -> 1f to 0f
        "L", "LEFT" -> -1f to 0f
        "U", "UP" -> 0f to -1f
        "D", "DOWN" -> 0f to 1f
        "UR" -> 1f to -1f
        "UL" -> -1f to -1f
        "BR" -> 1f to 1f
        "BL" -> -1f to 1f
        else -> null
    }

    private fun tokenize(payload: String): List<String> = payload
        .split(TOKEN_SEPARATOR)
        .map(String::trim)
        .filter(String::isNotEmpty)

    private fun wireFloat(value: Float): String = when (value) {
        0f -> "0.0"
        1f -> "1.0"
        else -> value.toString()
    }

    companion object {
        private const val DIRECTION_REPLAY_GAP_MS = 35L
        private val TOKEN_SEPARATOR = Regex("[,\\s]+")
        private val XBOX_REGEX = Regex("^X360([A-Z0-9]+?)(?:_(HOLD|RELEASE))?$")
        private val MOUSE_REGEX = Regex("^MOUSE_(LEFT|RIGHT|MIDDLE)(?:_(DOWN|UP))?$")
        private val TRIGGER_REGEX = Regex(
            "^(LT|RT):([01](?:\\.[0-9]+)?)(?:P([0-9]+(?:\\.[0-9]+)?))?$"
        )
        private val STICK_MACRO_REGEX = Regex("^(LS|RS):([A-Z]+)([0-9]{1,3})?$")
    }
}
