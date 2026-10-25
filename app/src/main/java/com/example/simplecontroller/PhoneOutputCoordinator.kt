package com.example.simplecontroller.net

import com.example.simplecontroller.model.PayloadModifiers

import java.net.URLEncoder
import java.net.URLDecoder

/** Receiver feedback is ordered independently for each physical output. */
internal class PhoneOutputConflictTracker(private val processSession: String) {
    private val revisions = mutableMapOf<String, Long>()
    private val conflicts = mutableMapOf<String, Boolean>()
    @Synchronized fun accept(message: String): Boolean {
        val parts = message.split(':')
        if (parts.size != 5 || parts[0] != "PHONE_ACK_V1" || parts[1] != processSession ||
            parts[3] !in setOf("MASKED", "CLEAR")) return false
        val sequence = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return false
        val key = runCatching { URLDecoder.decode(parts[4], "UTF-8") }.getOrNull()?.takeIf {
            it.length <= 300 && it.split('|').size == 3
        } ?: return false
        if (sequence <= (revisions[key] ?: -1L)) return false
        revisions[key] = sequence
        conflicts[key] = parts[3] == "MASKED"
        return true
    }
    @Synchronized fun snapshot(): Map<String, Boolean> = conflicts.toMap()
    @Synchronized fun clear() { revisions.clear(); conflicts.clear() }
}

/** A capability ACK cannot change the protocol of already-running legacy output. */
internal class PhoneProtocolActivationGate {
    private val busyOwners = mutableSetOf<Any>()
    @Volatile var receiverSupported = false
        private set
    @Volatile var active = false
        private set
    @Volatile var pending = false
        private set
    @Synchronized fun receiverConfirmed(confirmed: Boolean) { receiverSupported = confirmed }
    @Synchronized fun setLegacyBusy(owner: Any, busy: Boolean) {
        if (busy) busyOwners.add(owner) else busyOwners.remove(owner)
        if (busy && pending) pending = false
    }
    @Synchronized fun tryActivate(connectionEligible: Boolean): Boolean {
        if (active || pending || !receiverSupported || !connectionEligible || busyOwners.isNotEmpty()) return false
        pending = true
        return true
    }
    @Synchronized fun activationConfirmed(confirmed: Boolean): Boolean {
        val activate = pending && confirmed && receiverSupported && busyOwners.isEmpty()
        pending = false
        active = activate
        return activate
    }
    @Synchronized fun invalidate() { active = false; pending = false; receiverSupported = false }
}

/** Small, additive Windows protocol. Legacy and CBv0 messages are never rewritten by this codec. */
object PhoneOutputProtocol {
    const val CAPABILITY = "PHONE_OUTPUT_V1"
    fun field(value: String): String = URLEncoder.encode(value, "UTF-8")
    fun capability(session: String, nonce: String) = "PHONE_CAPS_V1:$session:$nonce"
    fun capabilityAck(session: String, nonce: String) = "PHONE_CAPS_V1_ACK:$session:$nonce:$CAPABILITY"
    fun activation(session: String, seq: Long) = "PHONE_ACTIVATE_V1:$session:$seq"
    fun state(session: String, seq: Long, key: String, command: String) =
        "PHONE_STATE_V1:$session:$seq:${field(key)}:${field(command)}"
    fun event(session: String, seq: Long, scope: String, generation: Long, event: Long, command: String) =
        "PHONE_EVENT_V1:$session:$seq:${field(scope)}:$generation:$event:${field(command)}"
    fun end(session: String, seq: Long, scope: String, generation: Long) =
        "PHONE_END_V1:$session:$seq:${field(scope)}:$generation"
    fun acknowledgement(session: String, seq: Long) = "PHONE_ACK_V1:$session:$seq"
}

data class PhoneOutputPacket(
    val command: String,
    val acknowledgement: String,
    val player: String,
    val isCurrent: () -> Boolean,
    val onConfirmed: (Boolean) -> Unit = {},
    val cleanup: Boolean = false
)

internal data class PhoneValue(
    val key: String, val command: String, val release: String,
    val amount: Float = 1f, val vector: Boolean = false
)

/** Phone-only ownership; receiver face/voice ownership is deliberately outside this registry. */
class PhoneOutputCoordinator(
    private val processSession: String,
    private val nextSequence: () -> Long,
    private val emit: (PhoneOutputPacket) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit = { _, _ -> }
) {
    private data class Claim(val scope: PhoneOutputScope, val value: PhoneValue, val order: Long,
                             var retained: Boolean, val exclusive: Boolean, var onReplaced: () -> Unit)
    private data class Reservation(val scope: PhoneOutputScope, var retained: Boolean,
                                   val exclusive: Boolean, var onReplaced: () -> Unit)
    private val claims = linkedMapOf<String, LinkedHashMap<PhoneOutputScope, Claim>>()
    private val reservations = linkedMapOf<String, LinkedHashMap<PhoneOutputScope, Reservation>>()
    private val revisions = mutableMapOf<String, Long>()
    private val generations = mutableMapOf<String, Long>()
    private val currentScopes = mutableMapOf<String, PhoneOutputScope>()
    private var order = 0L
    var cleanupListener: (Boolean) -> Unit = {}

    @Synchronized fun invalidateAll() {
        currentScopes.values.forEach { it.allScopes().forEach { owner ->
            owner.markEnded()
            owner.completeCleanup(false)
        } }
        claims.clear()
        reservations.clear()
        revisions.clear()
    }

    @Synchronized fun sendState(key: String, command: String, player: String) {
        val seq = nextSequence()
        revisions[key] = seq
        emit(PhoneOutputPacket(PhoneOutputProtocol.state(processSession, seq, key, command),
            PhoneOutputProtocol.acknowledgement(processSession, seq), player,
            { synchronized(this) { revisions[key] == seq } }))
    }

    @Synchronized
    fun beginScope(stableId: String, player: String): PhoneOutputScope {
        currentScopes[stableId]?.takeIf { it.isActive }?.finish()
        val generation = (generations[stableId] ?: -1L) + 1L
        generations[stableId] = generation
        return PhoneOutputScope(this, stableId, generation, player).also { currentScopes[stableId] = it }
    }

    @Synchronized
    internal fun put(scope: PhoneOutputScope, value: PhoneValue, retained: Boolean? = null,
                     exclusive: Boolean = false, onReplaced: (() -> Unit)? = null): Boolean {
        if (!scope.isActive) return false
        val owners = claims.getOrPut(value.key) { linkedMapOf() }
        val foreign = owners.values.filter { it.scope !== scope }
        val locks = reservations[value.key].orEmpty().values.filter { it.scope !== scope }
        if (foreign.any { (exclusive || it.exclusive || value.vector) && !it.retained } ||
            locks.any { (exclusive || it.exclusive || value.vector) && !it.retained }) return false
        val replaced = if (exclusive || value.vector) foreign.filter { it.retained } else emptyList()
        val replacedLocks = if (exclusive || value.vector) locks.filter { it.retained } else emptyList()
        replaced.forEach { owners.remove(it.scope) }
        replacedLocks.forEach { reservations[value.key]?.remove(it.scope) }
        owners[scope] = Claim(scope, value, ++order, retained ?: scope.retained, exclusive,
            onReplaced ?: scope.replacementCallback)
        scope.touched[value.key] = value
        (replaced.map { it.scope to it.onReplaced } + replacedLocks.map { it.scope to it.onReplaced })
            .distinctBy { it.first }.forEach { it.second() }
        publish(value.key, scope.player)
        return true
    }

    @Synchronized
    internal fun remove(scope: PhoneOutputScope, key: String) {
        val owners = claims[key] ?: return
        val old = owners.remove(scope) ?: return
        publish(key, scope.player, old.value.release)
    }

    @Synchronized
    internal fun release(scope: PhoneOutputScope, includeReservations: Boolean = true) {
        claims.keys.toList().forEach { remove(scope, it) }
        if (includeReservations) reservations.values.forEach { it.remove(scope) }
    }

    @Synchronized
    internal fun retain(scope: PhoneOutputScope, retained: Boolean) {
        claims.values.forEach { it[scope]?.let { claim -> claim.retained = retained; claim.onReplaced = scope.replacementCallback } }
        reservations.values.forEach { it[scope]?.let { lock -> lock.retained = retained; lock.onReplaced = scope.replacementCallback } }
    }

    @Synchronized
    internal fun owns(scope: PhoneOutputScope): Boolean = scope.isActive &&
        (claims.values.any { scope in it } || reservations.values.any { scope in it })

    @Synchronized
    internal fun canAcquire(scope: PhoneOutputScope, values: List<PhoneValue>, exclusive: Boolean,
                            exclusiveKeys: Set<String>? = null): Boolean =
        scope.isActive && values.all { value ->
            val keyExclusive = exclusiveKeys?.contains(value.key) ?: exclusive
            claims[value.key].orEmpty().values.none {
            it.scope !== scope && (keyExclusive || it.exclusive || value.vector) && !it.retained
        } && reservations[value.key].orEmpty().values.none {
            it.scope !== scope && (keyExclusive || it.exclusive || value.vector) && !it.retained
        } }

    @Synchronized
    internal fun reserve(scope: PhoneOutputScope, values: List<PhoneValue>, retained: Boolean,
                         exclusive: Boolean, onReplaced: () -> Unit, exclusiveKeys: Set<String>? = null): Boolean {
        if (!canAcquire(scope, values, exclusive, exclusiveKeys)) return false
        values.forEach { value ->
            val keyExclusive = exclusiveKeys?.contains(value.key) ?: exclusive
            val previous = claims[value.key].orEmpty().values.filter { it.scope !== scope && it.retained && keyExclusive }
            val oldLocks = reservations[value.key].orEmpty().values.filter { it.scope !== scope && it.retained && keyExclusive }
            previous.forEach { claims[value.key]?.remove(it.scope) }
            oldLocks.forEach { reservations[value.key]?.remove(it.scope) }
            reservations.getOrPut(value.key) { linkedMapOf() }[scope] = Reservation(scope, retained, keyExclusive, onReplaced)
            scope.touched[value.key] = value
            (previous.map { it.scope to it.onReplaced } + oldLocks.map { it.scope to it.onReplaced })
                .distinctBy { it.first }.forEach { it.second() }
            if (previous.isNotEmpty()) publish(value.key, scope.player, value.release)
        }
        return true
    }

    @Synchronized
    internal fun event(scope: PhoneOutputScope, command: String): Boolean {
        if (!scope.isActive || command.toByteArray(Charsets.UTF_8).size > 300) return false
        val seq = nextSequence()
        val event = scope.nextEvent()
        val wire = PhoneOutputProtocol.event(processSession, seq, scope.id, scope.generation, event, command)
        if (wire.toByteArray(Charsets.UTF_8).size > 1000) return false
        emit(PhoneOutputPacket(wire, PhoneOutputProtocol.acknowledgement(processSession, seq), scope.player,
            { scope.isActive }))
        return true
    }

    @Synchronized
    internal fun replay(scope: PhoneOutputScope, value: PhoneValue): Boolean {
        if (claims[value.key].orEmpty().keys.any { it !== scope }) return false
        return event(scope, "PHONE_REPLAY:${value.key}")
    }

    @Synchronized
    internal fun finish(scope: PhoneOutputScope, onConfirmed: (Boolean) -> Unit) {
        if (!scope.isActive) { scope.observeCleanup(onConfirmed); return }
        scope.observeCleanup(onConfirmed)
        val descendants = scope.allScopes()
        val cleanupVersions = descendants.associateWith { it.beginCleanup() }
        val affected = linkedMapOf<String, Pair<String, String>>()
        descendants.forEach { owner -> owner.touched.forEach { (key,value) ->
            affected[key] = owner.player to value.release
        } }
        descendants.forEach { it.markEnded() }
        descendants.forEach { owner -> claims.values.forEach { it.remove(owner) } }
        descendants.forEach { owner -> reservations.values.forEach { it.remove(owner) } }
        var remaining = affected.size + descendants.size
        var confirmed = true
        val acknowledge: (Boolean) -> Unit = { okay -> synchronized(this) {
            confirmed = confirmed && okay
            remaining--
            if (remaining == 0) {
                if (scope.isCleanupCurrent(cleanupVersions.getValue(scope))) cleanupListener(confirmed)
                descendants.forEach { it.completeCleanup(confirmed,cleanupVersions.getValue(it)) }
            }
        } }
        affected.forEach { (key, route) -> publish(key, route.first, route.second, acknowledge, cleanup=true) }
        descendants.forEach { owner ->
            val seq = nextSequence()
            emit(PhoneOutputPacket(PhoneOutputProtocol.end(processSession, seq, owner.id, owner.generation),
                PhoneOutputProtocol.acknowledgement(processSession, seq), owner.player, { true }, acknowledge, cleanup=true))
        }
    }

    private fun effective(key: String): PhoneValue? {
        val values = claims[key].orEmpty().values
        if (values.isEmpty()) return null
        return if (values.first().value.vector) values.maxBy { it.order }.value
               else values.maxWith(compareBy<Claim>({ it.value.amount }, { it.order })).value
    }

    private fun publish(key: String, player: String, release: String? = null,
                        onConfirmed: (Boolean) -> Unit = {}, cleanup: Boolean = false) {
        val value = effective(key)
        val command = value?.command ?: release ?: return
        val seq = nextSequence()
        revisions[key] = seq
        emit(PhoneOutputPacket(PhoneOutputProtocol.state(processSession, seq, key, command),
            PhoneOutputProtocol.acknowledgement(processSession, seq), player,
            { synchronized(this) { revisions[key] == seq } }, onConfirmed, cleanup))
    }

    internal fun pulse(scope: PhoneOutputScope, value: PhoneValue, duration: Long = 100L): Boolean {
        val wasOwned = synchronized(this) { claims[value.key]?.containsKey(scope) == true }
        if (!put(scope, value)) return false
        if (wasOwned) replay(scope, value)
        val pulse = scope.nextPulse(value.key)
        schedule(duration) { if (scope.isActive && scope.pulseIsCurrent(value.key, pulse)) remove(scope, value.key) }
        return true
    }

    companion object {
        internal fun parsePayloadToken(token: String, player: String): Pair<PhoneValue, Boolean>? =
            parse(token, player) ?: token.trim().takeIf {
                it.matches(Regex("[A-Za-z0-9_]+")) && !it.startsWith("WAIT_", true) &&
                    !it.startsWith("MOUSE_", true) && !it.startsWith("X360", true) &&
                    it != "RELEASE_ALL" && it != "CAMERA_FOLLOW"
            }?.let { parse("KEY_DOWN:$it", player) }

        fun logicalKeyForPayloadToken(token: String, player: String = "player1"): String? {
            val parsed = PayloadModifiers.parse(token)
            if (parsed.error != null || parsed.commands.size != 1) return null
            return parsePayloadToken(parsed.commands.single().command, player)?.first?.key
        }

        fun payloadLogicalKeys(payload: String, player: String = "player1"): Set<String>? {
            val parsed = PayloadModifiers.parse(payload)
            if (parsed.error != null) return null
            return parsed.commands.map { parsePayloadToken(it.command, player)?.first?.key ?: return null }.toSet()
        }

        internal fun parse(command: String, player: String): Pair<PhoneValue, Boolean>? {
            val raw = command.trim()
            val upper = raw.uppercase()
            Regex("^X360([A-Z0-9]+?)(?:_(HOLD|RELEASE))?$").matchEntire(upper)?.let {
                val button = when (it.groupValues[1]) { "LSC", "LSTICK" -> "LS"; "RSC", "RSTICK" -> "RS"; else -> it.groupValues[1] }
                if (button !in setOf("A","B","X","Y","LB","RB","BACK","START","UP","DOWN","LEFT","RIGHT","LS","RS")) return null
                return PhoneValue("$player|XBOX|$button", "X360${button}_HOLD", "X360${button}_RELEASE") to (it.groupValues[2] != "RELEASE")
            }
            Regex("^MOUSE_(LEFT|RIGHT|MIDDLE)(?:_(DOWN|UP))?$").matchEntire(upper)?.let {
                val button = it.groupValues[1]
                return PhoneValue("GLOBAL|MOUSE|$button", "MOUSE_${button}_DOWN", "MOUSE_${button}_UP") to (it.groupValues[2] != "UP")
            }
            Regex("^(LT|RT):([0-9]+(?:\\.[0-9]+)?)$").matchEntire(upper)?.let {
                val value = it.groupValues[2].toFloatOrNull() ?: return null
                if (!value.isFinite() || value !in 0f..1f) return null
                val trigger = it.groupValues[1]
                return PhoneValue("$player|TRIGGER|$trigger", "$trigger:$value", "$trigger:0.0", value) to (value > 0f)
            }
            Regex("^(LS|RS):([A-Z]+)([0-9]{1,3})?$").matchEntire(upper)?.let {
                val direction = when (it.groupValues[2]) {
                    "R", "RIGHT" -> 1f to 0f; "L", "LEFT" -> -1f to 0f
                    "U", "UP" -> 0f to -1f; "D", "DOWN" -> 0f to 1f
                    "UR" -> 1f to -1f; "UL" -> -1f to -1f
                    "BR" -> 1f to 1f; "BL" -> -1f to 1f; else -> return null
                }
                val amount = (it.groupValues[3].toFloatOrNull() ?: 100f).coerceIn(0f,100f) / 100f
                val side = if (it.groupValues[1] == "RS") "R" else "L"
                return PhoneValue("$player|MACRO|$side", "STICK_MACRO_$side:${direction.first * amount},${direction.second * amount}", "STICK_MACRO_$side:0.0,0.0", vector=true) to (amount > 0f)
            }
            if (upper.startsWith("KEY_DOWN:") || upper.startsWith("KEY_UP:")) {
                val key = raw.substringAfter(':').trim()
                if (key.isEmpty() || key.length > 80 || key.startsWith("WAIT_", true) || player != "player1") return null
                return PhoneValue("GLOBAL|KEY|${canonicalKey(key)}", "KEY_DOWN:$key", "KEY_UP:$key") to upper.startsWith("KEY_DOWN:")
            }
            return null
        }

        private fun canonicalKey(raw: String): String {
            val key = raw.uppercase()
            return when (key) {
                "SPACEBAR", "SPACE BAR", " " -> "SPACE"
                "ESCAPE" -> "ESC"
                "CONTROL", "LEFT CTRL", "LEFT CONTROL", "CTRL", "LCTRL" -> "LCTRL"
                "RIGHT CTRL", "RIGHT CONTROL" -> "RCTRL"
                "SHIFT", "LEFT SHIFT", "LSHIFT" -> "LSHIFT"
                "RIGHT SHIFT" -> "RSHIFT"
                "ALT", "LEFT ALT", "LALT" -> "LALT"
                "RIGHT ALT" -> "RALT"
                "WIN", "WINDOWS", "LEFT WINDOWS", "LWIN" -> "LWIN"
                "RIGHT WINDOWS" -> "RWIN"
                "PAGE UP" -> "PAGEUP"
                "PAGE DOWN" -> "PAGEDOWN"
                "CAPS LOCK" -> "CAPSLOCK"
                else -> key
            }
        }
    }
}

/** One runtime owner. Children isolate independent Base/ADS/fire/boost/executor contributions. */
class PhoneOutputScope internal constructor(
    private val coordinator: PhoneOutputCoordinator,
    val id: String,
    val generation: Long,
    val player: String
) {
    @Volatile var isActive: Boolean = true
        private set
    private val children = linkedMapOf<String, PhoneOutputScope>()
    private val pulses = mutableMapOf<String, Long>()
    internal val touched = linkedMapOf<String, PhoneValue>()
    private var cleanupResult: Boolean? = null
    private var cleanupVersion = 0L
    private val cleanupCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var eventSequence = 0L
    internal var retained = false
    internal var replacementCallback: () -> Unit = {}

    @Synchronized fun child(slot: String): PhoneOutputScope {
        if (!isActive) return children[slot] ?: PhoneOutputScope(coordinator,
            "$id/${PhoneOutputProtocol.field(slot)}", generation, player).also {
                it.markEnded(); it.completeCleanup(true); children[slot] = it
            }
        return children[slot]?.takeIf { it.isActive }
            ?: coordinator.beginScope("$id/${PhoneOutputProtocol.field(slot)}", player).also {
                it.setRetained(retained, replacementCallback)
                children[slot] = it
            }
    }
    internal fun nextEvent(): Long = eventSequence++
    @Synchronized internal fun nextPulse(key: String): Long = ((pulses[key] ?: 0L) + 1L).also { pulses[key] = it }
    @Synchronized internal fun pulseIsCurrent(key: String, generation: Long) = pulses[key] == generation
    internal fun markEnded() { isActive = false; pulses.clear() }
    @Synchronized internal fun observeCleanup(callback: (Boolean) -> Unit) {
        val result = cleanupResult
        if (result == null) cleanupCallbacks.add(callback) else callback(result)
    }
    @Synchronized internal fun beginCleanup(): Long {
        cleanupResult = null
        return ++cleanupVersion
    }
    @Synchronized internal fun isCleanupCurrent(version: Long) = version == cleanupVersion
    @Synchronized internal fun completeCleanup(confirmed: Boolean, version: Long? = null) {
        if (version != null && version != cleanupVersion) return
        if (version == null) cleanupVersion++
        cleanupResult = confirmed
        val callbacks = cleanupCallbacks.toList()
        cleanupCallbacks.clear()
        callbacks.forEach { it(confirmed) }
    }
    internal fun allScopes(): List<PhoneOutputScope> = listOf(this) + children.values.flatMap { it.allScopes() }

    fun sendCommand(command: String): Boolean {
        if (!isActive || PayloadModifiers.hasModifiers(command)) return false
        val parsed = PhoneOutputCoordinator.parse(command, player)
        if (parsed != null) {
            val (value, pressed) = parsed
            if (!pressed) { coordinator.remove(this, value.key); return true }
            if (command.trim().uppercase().startsWith("X360") && !command.contains('_')) return coordinator.pulse(this, value)
            return coordinator.put(this, value)
        }
        return coordinator.event(this, command)
    }
    fun sendLegacyForceRelease(command: String): Boolean {
        val parsed = PhoneOutputCoordinator.parse(command, player) ?: return coordinator.event(this, command)
        if (!isActive) return false
        touched[parsed.first.key] = parsed.first
        coordinator.remove(this, parsed.first.key)
        return coordinator.event(this, "PHONE_FORCE:$command")
    }
    fun sendKey(key: String, pressed: Boolean) = sendCommand("KEY_${if (pressed) "DOWN" else "UP"}:$key")
    fun sendMouseDelta(dx: Float, dy: Float, gameMouse: Boolean = false): Boolean =
        dx.isFinite() && dy.isFinite() && coordinator.event(this, "${if (gameMouse) "GAME_DELTA" else "DELTA"}:$dx,$dy")
    fun sendScroll(delta: Float): Boolean = delta.isFinite() && coordinator.event(this, "SCROLL:$delta")
    fun sendStick(name: String, x: Float, y: Float, exclude: Boolean = false): Boolean = stick(name,x,y,false,exclude)
    fun sendStickMacro(name: String, x: Float, y: Float, exclude: Boolean = false): Boolean = stick(name,x,y,true,exclude)
    private fun stick(name: String, x: Float, y: Float, macro: Boolean, exclude: Boolean): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val side = if (name.uppercase().let { it == "RS" || it == "R" || it.contains("_R") }) "R" else "L"
        val channel = if (macro) "MACRO" else "MANUAL"
        val tag = "STICK_${if (macro) "MACRO_" else ""}$side${if (exclude && side == "L") "_NO_FOLLOW" else ""}"
        val value = PhoneValue("$player|$channel|$side", "$tag:${x.coerceIn(-1f,1f)},${y.coerceIn(-1f,1f)}", "$tag:0.0,0.0", vector=true)
        if (macro && x == 0f && y == 0f) { coordinator.remove(this, value.key); return true }
        return coordinator.put(this, value)
    }
    fun acquirePayload(slot: String, payload: String, retained: Boolean = false, exclusive: Boolean = false,
                       exclusiveKeys: Set<String>? = null,
                       onReplaced: () -> Unit = {}): Boolean {
        if (!isActive) return false
        val owner = child("payload:$slot")
        val tokens = payload.split(Regex("[,\\s]+" )).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        val values = tokens.map { PhoneOutputCoordinator.parsePayloadToken(it, player)?.takeIf { p -> p.second }?.first ?: return false }
        if (!coordinator.canAcquire(owner, values, exclusive, exclusiveKeys)) return false
        values.forEach { coordinator.put(owner, it, retained, exclusiveKeys?.contains(it.key) ?: exclusive, onReplaced) }
        return true
    }
    fun reservePayload(slot: String, payload: String, retained: Boolean = false, exclusive: Boolean = true,
                       exclusiveKeys: Set<String>? = null,
                       onReplaced: () -> Unit = {}): Boolean {
        if (!isActive) return false
        val owner = child("payload:$slot")
        val parsed = PayloadModifiers.parse(payload)
        if (parsed.error != null) return false
        val values = parsed.commands.map { PhoneOutputCoordinator.parsePayloadToken(it.command, player)?.takeIf { p -> p.second }?.first ?: return false }
        return coordinator.reserve(owner, values, retained, exclusive, onReplaced, exclusiveKeys)
    }
    fun releasePayload(slot: String) { children["payload:$slot"]?.let { coordinator.release(it, false) } }
    fun endPayload(slot: String) { children["payload:$slot"]?.releaseAllClaims() }
    fun retainPayload(slot: String, retained: Boolean) { children["payload:$slot"]?.setRetained(retained) }
    fun payloadIsOwned(slot: String): Boolean = children["payload:$slot"]?.let { coordinator.owns(it) } == true
    fun setRetained(retained: Boolean, onReplaced: (() -> Unit)? = null) {
        this.retained = retained
        if (onReplaced != null) replacementCallback = onReplaced
        coordinator.retain(this, retained)
        children.values.forEach { it.setRetained(retained, onReplaced) }
    }
    fun releaseAllClaims() { coordinator.release(this); children.values.forEach { it.releaseAllClaims() } }
    fun finish(onConfirmed: (Boolean) -> Unit = {}) { coordinator.finish(this, onConfirmed) }
}
