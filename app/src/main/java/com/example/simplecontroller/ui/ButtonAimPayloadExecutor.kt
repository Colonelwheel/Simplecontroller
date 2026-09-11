package com.example.simplecontroller.ui

/** The two independently releasable payload roles owned by one Button Aim surface. */
enum class ButtonAimPayloadOwner {
    BASE,
    ALTERNATE
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
}

fun interface ButtonAimScheduledTask {
    fun cancel()
}

/** Injectable so ownership and stale-callback behavior are deterministic in JVM tests. */
fun interface ButtonAimPayloadScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): ButtonAimScheduledTask
}

/**
 * Executes base and alternate Button Aim payloads without reading or mutating Control.payload.
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

    private var nextLeaseId = 1L
    private val leases = linkedMapOf<Long, LeaseState>()
    private val effectiveOutputs = linkedMapOf<LogicalKey, DesiredOutput>()

    /** Returns null when activation is safe, otherwise a user-facing validation reason. */
    fun validationError(payload: String): String? {
        val rawTokens = tokenize(payload)
        if (rawTokens.isEmpty()) return "Payload is blank"
        return rawTokens.map(::parseToken)
            .filterIsInstance<ParsedToken.Invalid>()
            .firstOrNull()
            ?.reason
    }

    fun activate(
        owner: ButtonAimPayloadOwner,
        payload: String,
        pulseMode: ButtonAimPulseMode = ButtonAimPulseMode.ONCE
    ): ButtonAimPayloadActivationResult {
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
        val ids = leases.values
            .filter { it.lease.owner == owner }
            .mapTo(linkedSetOf()) { it.lease.id }
        releaseLeaseIds(ids)
    }

    fun releaseAllLeases() {
        releaseLeaseIds(leases.keys.toCollection(linkedSetOf()))
    }

    fun activeLeaseCount(owner: ButtonAimPayloadOwner? = null): Int =
        if (owner == null) leases.size else leases.values.count { it.lease.owner == owner }

    /** Useful to validate collisions before activation and in focused state-machine tests. */
    fun canonicalLogicalKey(command: String): String? = when (val token = parseToken(command.trim())) {
        is ParsedToken.Output -> token.desired.key.toString()
        is ParsedToken.ForceRelease -> token.desired.key.toString()
        else -> null
    }

    private fun replaceLeaseOutput(
        state: LeaseState,
        desired: DesiredOutput,
        pulseMode: ButtonAimPulseMode
    ) {
        cancelPulse(state, desired.key)
        state.outputs[desired.key] = desired
        reconcile(desired.key)

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
        ids.forEach { id ->
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
        val previous = effectiveOutputs.remove(desired.key)
        emitInactive(previous ?: desired)
    }

    private fun cancelPulse(state: LeaseState, key: LogicalKey) {
        state.pulses.remove(key)?.task?.cancel()
    }

    private fun reconcile(key: LogicalKey) {
        val previous = effectiveOutputs[key]
        val next = effectiveOutput(key)
        if (sameEffectiveOutput(previous, next)) return

        when {
            next == null && previous != null -> emitInactive(previous)
            next != null -> emitActive(next)
        }
        if (next == null) effectiveOutputs.remove(key) else effectiveOutputs[key] = next
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

    private fun clearWithoutSendingReleases() {
        leases.values.forEach { state ->
            state.pulses.values.forEach { it.task?.cancel() }
        }
        leases.clear()
        effectiveOutputs.clear()
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
        private val TOKEN_SEPARATOR = Regex("[,\\s]+")
        private val XBOX_REGEX = Regex("^X360([A-Z0-9]+?)(?:_(HOLD|RELEASE))?$")
        private val MOUSE_REGEX = Regex("^MOUSE_(LEFT|RIGHT|MIDDLE)(?:_(DOWN|UP))?$")
        private val TRIGGER_REGEX = Regex(
            "^(LT|RT):([01](?:\\.[0-9]+)?)(?:P([0-9]+(?:\\.[0-9]+)?))?$"
        )
        private val STICK_MACRO_REGEX = Regex("^(LS|RS):([A-Z]+)([0-9]{1,3})?$")
    }
}
