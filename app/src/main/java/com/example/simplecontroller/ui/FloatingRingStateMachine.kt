package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ButtonAimOutput
import com.example.simplecontroller.model.ButtonAimStickProfile
import com.example.simplecontroller.model.ButtonAimTuning
import com.example.simplecontroller.model.FloatingRingConfig
import com.example.simplecontroller.model.FloatingStartRole
import com.example.simplecontroller.model.RingAction
import com.example.simplecontroller.model.RingAimKnob
import com.example.simplecontroller.model.RingBoostBank
import com.example.simplecontroller.model.RingBoostSettings
import com.example.simplecontroller.model.RingBoostStage
import com.example.simplecontroller.model.RingFireMode
import com.example.simplecontroller.model.StickDirection
import kotlin.math.abs

/** The adapter owns shared phone output claims, transport ordering and the manual-stick arbiter. */
interface FloatingRingBackend {
    fun acquirePayload(slot: String, payload: String): Boolean
    fun releasePayload(slot: String)
    fun retainPayload(slot: String, retained: Boolean)
    fun payloadIsOwned(slot: String): Boolean = true
    fun payloadMasked(slot: String): Boolean = false
    /** Reserve an active/finite sender across its OFF gaps; those gaps are not takeover windows. */
    fun reservePayload(slot: String, payload: String, retained: Boolean = false, exclusive: Boolean = true): Boolean = true
    fun endPayload(slot: String) = releasePayload(slot)
    fun closeScope() {}
    fun beginAim(pointerId: Int, x: Float, y: Float, eventTime: Long, tuning: ButtonAimTuning): Boolean
    fun updateAim(pointerId: Int, x: Float, y: Float, eventTime: Long)
    fun suspendAim(pointerId: Int, x: Float, y: Float, eventTime: Long)
    fun resumeAim(pointerId: Int, x: Float, y: Float, eventTime: Long)
    fun completeAim(autoCenter: Boolean)
    fun finishAim()
    fun aimIsRetained(): Boolean = false
    fun updateAimSensitivity(value: Float)
    fun movement(x: Float, y: Float, retained: Boolean)
    fun releaseMovement()
    fun mouseSuppression(active: Boolean)
    fun payloadsOverlap(first: String, second: String): Boolean =
        first.uppercase().split(Regex("[,\\s]+" )).filter(String::isNotBlank).toSet()
            .intersect(second.uppercase().split(Regex("[,\\s]+" )).filter(String::isNotBlank).toSet()).isNotEmpty()
}

data class FloatingRingStatus(
    val pointerId: Int = -1,
    val role: FloatingStartRole? = null,
    val movementX: Float = 0f,
    val movementY: Float = 0f,
    val movementRetained: Boolean = false,
    val ads: Boolean = false,
    val firing: Boolean = false,
    val repeating: Boolean = false,
    val armed: Boolean = false,
    val pending: Boolean = false,
    val busy: Boolean = false,
    val suppressed: Boolean = false,
    val fine: Boolean = false,
    val boosts: Set<RingBoostStage> = emptySet(),
    val overridden: Set<String> = emptySet(),
    val blockedByOtherSource: Boolean = false,
    val heldBoosts: Set<RingBoostStage> = emptySet(),
    val repeatingBoosts: Set<RingBoostStage> = emptySet(),
    val latchedBoosts: Set<RingBoostStage> = emptySet(),
    val aimRetained: Boolean = false,
    val bankBoostLabels: List<String> = emptyList()
)

/**
 * Pointer, aim, payload claims, finite pulses, boost delays and return-fire delays are independent.
 * Pure Kotlin: the View passes every historical sample in time order and passes cancellation as
 * cancellation. No timer or pointer state belongs in the saved model.
 */
class FloatingRingStateMachine(
    val config: FloatingRingConfig,
    val geometry: FloatingRingGeometry,
    private val backend: FloatingRingBackend,
    private val scheduler: ButtonAimPayloadScheduler,
    private val now: () -> Long,
    private val onStateChanged: (FloatingRingStatus) -> Unit = {},
    private val onFinish: () -> Unit = {},
    private val finishIgnoresSwipes: Boolean = false
) {
    private class OutputAction(val slot: String) {
        var payload = ""
        var held = false
        var retained = false
        var repeating = false
        var busy = false
        var queued = false
        var queuedPayload = ""
        var queuedPressMs = 35L
        var queuedGapMs = 35L
        var queuedGesture = -1L
        var activeGesture = -1L
        var generation = 0L
        var task: ButtonAimScheduledTask? = null
        var pressMs = 35L
        var gapMs = 35L
    }
    private class BoostRuntime(
        val stage: RingBoostStage,
        val bank: RingBoostBank?,
        val settings: RingBoostSettings,
        val output: OutputAction
    ) {
        var inside = false
        var latched = false
        var direction: StickDirection? = null
        var pending = false
        var pendingPayload = ""
        var delayGeneration = 0L
        var delay: ButtonAimScheduledTask? = null
    }
    private val ads = OutputAction(ADS)
    private val fire = OutputAction(FIRE)
    // Runtime identity belongs to an action bank, not the latest pointer. Legacy profiles
    // deliberately keep their original shared identities, including retained-output behavior.
    private val boosts = (if (config.boostBanks?.enabled == true) RingBoostBank.entries.toList()
        else listOf<RingBoostBank?>(null)).flatMap { bank ->
        RingBoostStage.entries.map { stage ->
            val slot = if (stage == RingBoostStage.ONE) BOOST_ONE else BOOST_TWO
            BoostRuntime(stage, bank, config.boost(stage, bank),
                OutputAction(if (bank == null) slot else "${bank.name.lowercase()}:$slot"))
        }
    }
    private var pointerId = -1
    private var role: FloatingStartRole? = null
    private var gestureBank: RingBoostBank? = null
    private var knob: RingAimKnob? = null
    private var gestureGeneration = 0L
    private var returnGeneration = 0L
    private var returnTask: ButtonAimScheduledTask? = null
    private var returnPending = false
    private var returnAwaitingFirstAim = false
    private var earlyFireKnob: RingAimKnob? = null
    private var aimActive = false
    private var aimSuspended = false
    private var insideAim = false
    private var releaseCanceled = false
    private var armed = false
    private var fireEligible = false
    private var fireStopped = false
    private var adsStopped = false
    private var firedThisGesture = false
    private var fineGesture = false
    private var immediateFineGesture = false
    private val fineLatchSlots = mutableSetOf<String>()
    private var suppression = false
    private var finished = false
    private var finishEntryEligible = true
    private var processingLift = false
    private var directBoostOneUnlocked = false
    private var movementRetained = false
    private var movementVector = RingPoint(0f, 0f)
    private var lastX = 0f
    private var lastY = 0f
    private var logicalOffsetX = 0f
    private var logicalOffsetY = 0f
    private var movementOffsetX = 0f
    private var movementOffsetY = 0f
    private var movementRecentered = false
    private val visitedStages = mutableSetOf<RingBoostStage>()
    private val preexistingSlots = mutableSetOf<String>()
    private val preexistingRetained = mutableMapOf<String, Triple<String, Boolean, Long>>()
    private val overridden = mutableSetOf<String>()

    val status: FloatingRingStatus get() = FloatingRingStatus(
        pointerId, role, movementVector.x, movementVector.y, movementRetained,
        ads.held || boosts.any { it.latched && it.output.held && isAdsPayload(it.output.payload) },
        fire.held, fire.repeating, armed, returnPending || boosts.any { it.pending },
        outputs().any { it.busy || it.queued }, suppression, effectiveFine(),
        boosts.filter { it.inside || it.latched || it.output.retained || it.output.held || it.output.repeating || it.pending }
            .mapTo(mutableSetOf()) { it.stage },
        overridden.toSet(),
        ((fire.held || fire.repeating || fire.busy || returnPending) && backend.payloadMasked(FIRE)) ||
            boosts.any { boost ->
                val settings = boost.settings
                val affectsFire = config.knobs.any { backend.payloadsOverlap(payloadForSlot(boost.output.slot), it.fire.firePayload) }
                (boost.output.held || boost.output.repeating || boost.output.busy || boost.pending) &&
                    (settings.action != RingAction.HOLD || affectsFire) && backend.payloadMasked(boost.output.slot)
            },
        boosts.filter { it.output.held }.mapTo(mutableSetOf()) { it.stage },
        boosts.filter { it.output.repeating }.mapTo(mutableSetOf()) { it.stage },
        boosts.filter { it.latched && it.output.held }.mapTo(mutableSetOf()) { it.stage },
        backend.aimIsRetained(),
        boosts.filter { it.bank != null && (it.inside || it.latched || it.output.retained ||
            it.output.held || it.output.repeating || it.output.busy || it.pending) }.map { boost ->
            val bankName = if (boost.bank == RingBoostBank.AIM) "Aim" else "Movement"
            val stageName = if (boost.stage == RingBoostStage.ONE) "B1" else "B2"
            val action = when {
                boost.pending -> "pending"
                boost.output.repeating -> "repeating"
                boost.latched -> "latched"
                boost.output.retained -> "retained"
                boost.output.held -> "held"
                boost.output.busy -> "busy"
                else -> "active"
            }
            "$bankName $stageName $action"
        }
    )

    /** The UI refreshes this only when the receiver's conflict snapshot changes, never by polling. */
    fun refreshFeedback() = publish()

    fun payloadForSlot(slot: String): String = when (slot) {
        ADS -> ads.payload.ifBlank { knob?.fire?.adsPayload.orEmpty() }
        FIRE -> if (returnPending) firingSettings()?.firePayload.orEmpty()
            else fire.payload.ifBlank { firingSettings()?.firePayload.orEmpty() }
        else -> boosts.firstOrNull { it.output.slot == slot }?.let {
            it.pendingPayload.ifBlank { it.output.payload }
        }.orEmpty()
    }

    fun payloadsForSlot(slot: String): Set<String> = boosts.firstOrNull { it.output.slot == slot }?.let {
            setOf(it.pendingPayload, it.output.payload, it.output.queuedPayload).filterTo(mutableSetOf(), String::isNotBlank)
        } ?: setOf(payloadForSlot(slot)).filterTo(mutableSetOf(), String::isNotBlank)

    fun down(
        id: Int, x: Float, y: Float, time: Long,
        startingRole: FloatingStartRole,
        selectedKnobId: String = config.startKnobId,
        offsetX: Float = 0f, offsetY: Float = 0f,
        useConfiguredMovementStart: Boolean = false,
        checkFinish: Boolean = true
    ): Boolean {
        if (finished || pointerId != -1 || !config.isActive || config.validationError() != null) return false
        if (checkFinish && geometry.finish.contains(x, y)) { finish(); return true }
        val selectedBank = config.boostBankForRole(startingRole)
        val selectedKnob = config.knob(selectedKnobId)?.takeIf { it.enabled }
        val valid = when (startingRole) {
            FloatingStartRole.MOVEMENT -> config.movement.enabled
            FloatingStartRole.AIM -> config.aim.enabled && selectedKnob != null
            FloatingStartRole.BOOST_ONE -> config.boost(RingBoostStage.ONE, selectedBank).let { it.enabled && it.directTapEnabled }
            FloatingStartRole.BOOST_TWO -> config.boost(RingBoostStage.TWO, selectedBank).let { it.enabled && it.directTapEnabled }
        }
        if (!valid) return false
        // Fitting may put the birth pointer physically over Finish while the selected role is
        // logically under its grab. Only a later exit followed by entry counts as a Finish drag.
        finishEntryEligible = !geometry.finish.contains(x, y)
        gestureGeneration++
        pointerId = id
        role = startingRole
        gestureBank = selectedBank
        earlyFireKnob = null
        directBoostOneUnlocked = false
        logicalOffsetX = offsetX
        logicalOffsetY = offsetY
        movementOffsetX = 0f
        movementOffsetY = 0f
        movementRecentered = false
        lastX = x
        lastY = y
        insideAim = geometry.insideAim(x + offsetX, y + offsetY)
        visitedStages.clear()
        preexistingSlots.clear()
        preexistingRetained.clear()
        outputs().filter { it.held || it.repeating || it.busy || it.retained }
            .forEach {
                preexistingSlots.add(it.slot)
                preexistingRetained[it.slot] = Triple(it.payload, it.retained, it.generation)
            }
        overridden.clear()
        fireStopped = false
        adsStopped = false
        releaseCanceled = false
        armed = false
        fireEligible = false
        firedThisGesture = false
        fineGesture = false
        immediateFineGesture = false
        cancelReturn()
        when (startingRole) {
            FloatingStartRole.MOVEMENT -> {
                movementRetained = false
                if (useConfiguredMovementStart) {
                    movementVector = RingPoint(config.movement.initialX, config.movement.initialY)
                    sendMovement(movementVector, false)
                } else sendMovement(geometry.movementVector(x + offsetX, y + offsetY), false)
            }
            FloatingStartRole.AIM -> {
                if (!startAim(selectedKnob!!, x, y, time, directAddition = false)) { cancel(); return false }
            }
            FloatingStartRole.BOOST_ONE, FloatingStartRole.BOOST_TWO -> {
                val stage = if (startingRole == FloatingStartRole.BOOST_ONE) RingBoostStage.ONE else RingBoostStage.TWO
                val selected = gestureBoosts().first { it.stage == stage }
                selected.inside = true
                if (config.directBoostStartsAim) {
                    val destination = config.knob(config.directBoostAimKnobId)?.takeIf { it.enabled }
                    if (destination != null) {
                        immediateFineGesture = config.aim.fine.enabled
                        if (startAim(destination, x, y, time, directAddition = true) &&
                            !config.aim.continueOutside && !insideAim) {
                            backend.suspendAim(pointerId, x, y, time)
                            aimSuspended = true
                        }
                    }
                }
                enterBoost(selected, direction(x + offsetX, y + offsetY), time, directTouch = true)
            }
        }
        publish()
        return true
    }

    /** Every sample, including a final UP sample, is evaluated before gesture completion. */
    fun sample(id: Int, x: Float, y: Float, time: Long, finalLift: Boolean = false) {
        if (finished || pointerId == -1) return
        if (id != pointerId || !x.isFinite() || !y.isFinite()) { cancel(); return }
        val insideFinish = geometry.finish.contains(x, y)
        if (!finishIgnoresSwipes && insideFinish && finishEntryEligible) { finish(); return }
        if (!insideFinish) finishEntryEligible = true
        val previousLogicalX = lastX + logicalOffsetX
        val previousLogicalY = lastY + logicalOffsetY
        lastX = x
        lastY = y
        processingLift = finalLift
        val logicalX = x + logicalOffsetX
        val logicalY = y + logicalOffsetY
        val wasInsideAim = insideAim
        insideAim = geometry.insideAim(logicalX, logicalY)
        // In entry-start mode an Aim exit may also be the next qualifying Boost entry.
        // Cancel the old deadline before that entry can schedule its replacement.
        val returnSettings = knob?.fire?.returnFire
        val restartEntryOnAimExit = aimActive && wasInsideAim && !insideAim && returnSettings?.enabled == true &&
            returnSettings.startOnBoostEntry && returnSettings.cancelOnExit && !returnAwaitingFirstAim
        if (restartEntryOnAimExit) {
            cancelReturn()
            if (!fireEligible) armed = false
        }
        if (role == FloatingStartRole.MOVEMENT) {
            sendMovement(geometry.movementVector(logicalX + movementOffsetX, logicalY + movementOffsetY), false)
        }
        val direct = role == FloatingStartRole.BOOST_ONE || role == FloatingStartRole.BOOST_TWO
        if (direct && !aimActive && config.directBoostToAim && insideAim && !processingLift) {
            val destination = config.knob(config.directBoostAimKnobId)?.takeIf { it.enabled }
            if (destination != null) startAim(destination, x, y, time, directAddition = true)
        }
        if (participatesInBoosts()) evaluateBoosts(logicalX, logicalY, direct, time,
            previousLogicalX, previousLogicalY)
        if (restartEntryOnAimExit && !processingLift) {
            // Compact B1's exit hysteresis may overlap Aim. A new outward Aim crossing
            // requalifies that still-occupied band without replaying its boost payload.
            gestureBoosts().firstOrNull { it.inside && returnSettings?.qualifyingStages?.contains(it.stage) == true }
                ?.let { startBoostEntryFire(it.stage, time) }
        }
        if (aimActive) {
            if (wasInsideAim && !insideAim) leaveAim(x, y, time)
            if ((!wasInsideAim && insideAim) || (direct && insideAim && visitedStages.isNotEmpty())) {
                returnToAim(x, y, time)
            }
            if (config.aim.continueOutside || insideAim) {
                backend.updateAim(pointerId, x, y, time)
            }
        }
        processingLift = false
        publish()
    }

    fun up(id: Int, x: Float, y: Float, time: Long) {
        if (pointerId == -1 || finished) return
        sample(id, x, y, time, finalLift = true)
        if (pointerId == -1 || finished) return
        if (id != pointerId) { cancel(); return }
        val fireSettings = firingSettings()
        cancelReturn()
        boosts.forEach(::cancelBoostDelay)
        if ((aimActive || earlyFireKnob != null) && fireSettings?.mode == RingFireMode.RELEASE_SHOT && armed && !releaseCanceled && !fireStopped) {
            finitePulse(fire, fireSettings.firePayload, fireSettings.pulsePressMs, fireSettings.pulseGapMs)
        }
        armed = false
        if (role == FloatingStartRole.MOVEMENT) {
            movementRetained = !config.movement.autoCenter && !movementRecentered
            if (movementRetained) backend.movement(movementVector.x, movementVector.y, true)
            else { backend.releaseMovement(); movementVector = RingPoint(0f, 0f) }
        }
        if (aimActive) {
            backend.completeAim(config.aim.tuning.autoCenter)
            if (fireSettings != null) {
                retainOrRelease(ads, fireSettings.retainAdsOnLift)
            }
        }
        if ((aimActive || earlyFireKnob != null) && fireSettings != null &&
            fireSettings.mode != RingFireMode.TAP && fireSettings.mode != RingFireMode.RELEASE_SHOT) {
            retainOrRelease(fire, fireSettings.retainFireOnLift)
        }
        gestureBoosts().forEach { boost ->
            if (!boost.inside && !boost.latched && !boost.output.repeating && !boost.output.held) return@forEach
            val settings = boost.settings
            if (settings.action != RingAction.TAP) retainOrRelease(boost.output, settings.retainOnLift)
            boost.latched = boost.latched && boost.output.held
            if (!boost.latched) fineLatchSlots.remove(boost.output.slot)
            boost.inside = false
        }
        endPointer()
        publish()
    }

    /** Cancel only work acquired by this gesture, preserving legitimate older retained claims. */
    fun cancel() {
        if (pointerId == -1) return
        val canceledGesture = gestureGeneration
        gestureGeneration++
        cancelReturn()
        boosts.forEach { cancelBoostDelay(it); it.inside = false }
        armed = false
        if (aimActive) backend.finishAim()
        if (role == FloatingStartRole.MOVEMENT && !movementRetained) {
            backend.releaseMovement()
            movementVector = RingPoint(0f, 0f)
        }
        outputs().filter {
            val previous = preexistingRetained[it.slot]
            previous == null || previous.third != it.generation || it.activeGesture == canceledGesture
        }.forEach(::stopOutput)
        outputs().forEach { output ->
            val previous = preexistingRetained[output.slot]
            if (previous != null && previous.third == output.generation &&
                (output.held || output.repeating || output.busy)) {
                output.retained = previous.second
                if (output.queued && output.queuedGesture == canceledGesture) {
                    output.queued = false
                    output.queuedPayload = ""
                    output.queuedGesture = -1L
                }
                backend.retainPayload(output.slot, previous.second)
            }
        }
        boosts.forEach {
            it.latched = it.latched && it.output.held
            if (!it.latched) fineLatchSlots.remove(it.output.slot)
        }
        endPointer()
        publish()
    }

    /** A finishing gesture cannot return to aim, fire on lift, or re-summon the zone. */
    fun finish() {
        if (finished) return
        finished = true
        hardCleanup()
        onFinish()
    }

    fun hardCleanup() {
        gestureGeneration++
        cancelReturn()
        armed = false
        boosts.forEach { cancelBoostDelay(it); it.inside = false; it.latched = false }
        outputs().forEach(::stopOutput)
        backend.finishAim()
        backend.releaseMovement()
        movementVector = RingPoint(0f, 0f)
        movementRetained = false
        suppression = false
        backend.mouseSuppression(false)
        fineGesture = false
        fineLatchSlots.clear()
        endPointer()
        backend.closeScope()
        publish()
    }

    fun stopFiring() {
        fireStopped = true
        armed = false
        fireEligible = false
        cancelReturn()
        stopOutput(fire)
        val firingPayloads = config.knobs.map { it.fire.firePayload }.filter(String::isNotBlank)
        boosts.forEach { boost ->
            val payload = boost.pendingPayload.ifBlank { boost.output.payload }
            if (payload.isNotBlank() && firingPayloads.any { backend.payloadsOverlap(payload, it) }) {
                cancelBoostDelay(boost)
                stopOutput(boost.output)
                boost.latched = false
                if (fineLatchSlots.remove(boost.output.slot)) {
                    if (boost.bank == activeRuntimeBank()) fineGesture = false
                    backend.updateAimSensitivity(if (effectiveFine()) config.aim.fine.sensitivity else config.aim.tuning.sensitivity)
                }
            }
        }
        publish()
    }

    fun releaseAds() {
        adsStopped = true
        stopOutput(ads)
        val adsPayload = knob?.fire?.adsPayload.orEmpty()
        boosts.forEach { boost ->
            val payload = boost.pendingPayload.ifBlank { boost.output.payload }
            if (isAdsPayload(payload) ||
                (adsPayload.isNotBlank() && backend.payloadsOverlap(payload, adsPayload))) {
                cancelBoostDelay(boost)
                stopOutput(boost.output)
                boost.latched = false
                fineLatchSlots.remove(boost.output.slot)
            }
        }
        fineGesture = false
        immediateFineGesture = false
        backend.updateAimSensitivity(config.aim.tuning.sensitivity)
        publish()
    }

    fun endSuppression() {
        suppression = false
        backend.mouseSuppression(false)
        publish()
    }

    /** Recenter only this ring's movement lease; boost distance keeps its original grab. */
    fun recenterMovement() {
        movementRetained = false
        movementVector = RingPoint(0f, 0f)
        backend.releaseMovement()
        if (pointerId != -1 && role == FloatingStartRole.MOVEMENT) {
            movementOffsetX = geometry.center.x - (lastX + logicalOffsetX)
            movementOffsetY = geometry.center.y - (lastY + logicalOffsetY)
            movementRecentered = true
        }
        publish()
    }

    /** A new deliberate retained-stick owner won; never center the replacement owner's output. */
    fun movementReplaced() {
        movementRetained = false
        movementVector = RingPoint(0f, 0f)
        overridden.add("movement")
        publish()
    }

    fun outputReplaced(slot: String) {
        overridden.add(slot)
        if (slot == FIRE) { fireStopped = true; cancelReturn(); armed = false }
        outputs().firstOrNull { it.slot == slot }?.let(::stopOutput)
        boosts.firstOrNull { it.output.slot == slot }?.let {
            cancelBoostDelay(it)
            it.latched = false
            if (fineLatchSlots.remove(it.output.slot)) {
                if (it.bank == activeRuntimeBank()) fineGesture = false
                backend.updateAimSensitivity(if (effectiveFine()) config.aim.fine.sensitivity else config.aim.tuning.sensitivity)
            }
        }
        publish()
    }

    private fun startAim(selected: RingAimKnob, x: Float, y: Float, time: Long, directAddition: Boolean): Boolean {
        if (knob?.id != null && knob?.id != selected.id) {
            if (earlyFireKnob?.id != selected.id) stopOutput(fire)
            stopOutput(ads)
        }
        knob = selected
        val tuning = if (effectiveFine()) config.aim.tuning.copy(sensitivity = config.aim.fine.sensitivity)
            else config.aim.tuning
        if (!backend.beginAim(pointerId, x, y, time, tuning)) {
            overridden.add("aim")
            return false
        }
        aimActive = true
        earlyFireKnob = null
        aimSuspended = false
        if (directAddition) {
            // Entry adds only aim: establish a neutral grab without outside travel or a fresh DOWN.
            backend.suspendAim(pointerId, x, y, time)
            backend.resumeAim(pointerId, x, y, time)
        } else if (tuning.output == ButtonAimOutput.RIGHT_STICK && tuning.stickUsesTouchPosition) {
            backend.updateAim(pointerId, x, y, time)
        }
        if (config.aim.suppressCameraFollow && tuning.output in setOf(ButtonAimOutput.MOUSE, ButtonAimOutput.GAME_MOUSE)) {
            suppression = true
            backend.mouseSuppression(true)
        }
        if (!directAddition) {
            if (!adsStopped) hold(ads, selected.fire.adsPayload)
            if (!selected.fire.returnFire.enabled) activateFire()
        }
        return true
    }

    private fun leaveAim(x: Float, y: Float, time: Long) {
        val settings = knob?.fire ?: return
        if (!config.aim.continueOutside) {
            backend.suspendAim(pointerId, x, y, time)
            aimSuspended = true
        }
        if (settings.returnFire.enabled && settings.returnFire.cancelOnExit && !returnAwaitingFirstAim) {
            cancelReturn()
            if (!fireEligible) armed = false
        }
        if (!settings.continueAdsOutside) stopOutput(ads)
        if (!settings.continueFireOutside && settings.mode in setOf(RingFireMode.HOLD, RingFireMode.REPEAT)) {
            stopOutput(fire)
        }
        if (settings.mode == RingFireMode.RELEASE_SHOT && settings.cancelReleaseShotOnBoundaryExit) {
            releaseCanceled = true
            armed = false
        }
    }

    private fun returnToAim(x: Float, y: Float, time: Long) {
        val settings = knob?.fire ?: return
        if (aimSuspended) { backend.resumeAim(pointerId, x, y, time); aimSuspended = false }
        if (config.aim.fine.enabled && visitedStages.any { it in config.aim.fine.qualifyingStages }) {
            fineGesture = true
            gestureBoosts().filter { it.stage in visitedStages && it.stage in config.aim.fine.qualifyingStages && it.latched }
                .forEach { fineLatchSlots.add(it.output.slot) }
            backend.updateAimSensitivity(config.aim.fine.sensitivity)
        }
        if (releaseCanceled && settings.rearmReleaseShotOnBoundaryReturn) {
            releaseCanceled = false
            if (fireEligible) armed = settings.mode == RingFireMode.RELEASE_SHOT
        }
        if (!processingLift && !fireStopped && settings.mode != RingFireMode.NONE && settings.returnFire.enabled &&
            !settings.returnFire.startOnBoostEntry &&
            visitedStages.any { it in settings.returnFire.qualifyingStages } && !returnPending) {
            scheduleReturn(settings.returnFire.delayMs, time)
        } else if (!processingLift && settings.returnFire.enabled && fireEligible && !returnPending && !fireStopped) {
            activateFire()
        } else if (!processingLift && !settings.returnFire.enabled && !fireStopped) {
            if (settings.mode == RingFireMode.HOLD || settings.mode == RingFireMode.REPEAT) activateFire()
        }
        // Independent ADS configured for an ordinary aim gesture can resume; direct additions never invent it.
        if (!processingLift && role == FloatingStartRole.AIM && !settings.continueAdsOutside && !adsStopped) hold(ads, settings.adsPayload)
        returnAwaitingFirstAim = false
        visitedStages.clear()
    }

    private fun scheduleReturn(delayMs: Long, eventTime: Long, beforeAim: Boolean = false) {
        val generation = ++returnGeneration
        val liveGesture = gestureGeneration
        val livePointer = pointerId
        returnPending = true
        returnAwaitingFirstAim = beforeAim
        fireEligible = false
        armed = false
        firedThisGesture = false
        val run = {
            if (returnGeneration == generation && gestureGeneration == liveGesture &&
                pointerId == livePointer && pointerId != -1 && !finished && !fireStopped &&
                (aimActive || earlyFireKnob != null) &&
                (returnAwaitingFirstAim || insideAim || firingSettings()?.returnFire?.cancelOnExit == false)) {
                returnPending = false
                returnTask = null
                activateFire()
                publish()
            }
        }
        // Coalesced input deadlines start at the actual sample time. An overdue positive delay
        // is posted, never executed inline, so later history/UP/CANCEL can invalidate it first.
        if (delayMs == 0L) run() else returnTask = scheduler.schedule((eventTime + delayMs - now()).coerceAtLeast(0L), run)
    }

    private fun cancelReturn() {
        returnGeneration++
        returnTask?.cancel()
        returnTask = null
        returnPending = false
        returnAwaitingFirstAim = false
    }

    private fun firingSettings() = (earlyFireKnob ?: knob)?.fire

    private fun startBoostEntryFire(stage: RingBoostStage, eventTime: Long) {
        val selected = when (role) {
            FloatingStartRole.AIM -> knob
            FloatingStartRole.BOOST_ONE, FloatingStartRole.BOOST_TWO ->
                if (config.directBoostToAim || config.directBoostStartsAim)
                    config.knob(config.directBoostAimKnobId)?.takeIf { it.enabled } else null
            else -> null
        } ?: return
        val settings = selected.fire
        if (!settings.returnFire.enabled || !settings.returnFire.startOnBoostEntry ||
            stage !in settings.returnFire.qualifyingStages || settings.mode == RingFireMode.NONE ||
            returnPending || (fireEligible && returnAwaitingFirstAim) || fireStopped) return
        if (!aimActive) earlyFireKnob = selected
        scheduleReturn(settings.returnFire.delayMs, eventTime, beforeAim = true)
    }

    private fun activateFire() {
        val settings = firingSettings() ?: return
        if (fireStopped || pointerId == -1 || finished) return
        fireEligible = true
        if (!insideAim && !settings.continueFireOutside && settings.mode != RingFireMode.RELEASE_SHOT) return
        when (settings.mode) {
            RingFireMode.NONE -> Unit
            RingFireMode.HOLD -> hold(fire, settings.firePayload)
            RingFireMode.TAP -> if (!firedThisGesture) {
                firedThisGesture = true
                finitePulse(fire, settings.firePayload, settings.pulsePressMs, settings.pulseGapMs)
            }
            RingFireMode.RELEASE_SHOT -> if (!releaseCanceled) armed = true
            RingFireMode.REPEAT -> repeat(fire, settings.firePayload, settings.pulsePressMs, settings.pulseGapMs)
        }
    }

    private fun participatesInBoosts(): Boolean = when (role) {
        FloatingStartRole.MOVEMENT -> config.movement.participateInBoosts
        FloatingStartRole.AIM -> config.aim.participateInBoosts
        FloatingStartRole.BOOST_ONE, FloatingStartRole.BOOST_TWO -> true
        null -> false
    }

    private fun evaluateBoosts(
        x: Float, y: Float, direct: Boolean, eventTime: Long,
        previousX: Float, previousY: Float
    ) {
        val selectedDirect = if (role == FloatingStartRole.BOOST_ONE) RingBoostStage.ONE else RingBoostStage.TWO
        val distance = geometry.distance(x, y)
        val compactAim = role == FloatingStartRole.AIM && config.compactAimBoosts
        val adjacentMovement = role == FloatingStartRole.MOVEMENT && config.adjacentMovementBoosts
        val bankBoosts = gestureBoosts()
        val returnBoundary = geometry.directBand(RingBoostStage.TWO, gestureBank ?: config.directBoostBank()).innerRadius
        val returningToBoostOne = role == FloatingStartRole.BOOST_TWO && config.directBoostTwoToOne &&
            bankBoosts.first { it.stage == RingBoostStage.ONE }.settings.enabled && !directBoostOneUnlocked &&
            geometry.distance(previousX, previousY) >= returnBoundary && distance < returnBoundary
        if (returningToBoostOne) directBoostOneUnlocked = true
        fun isReturningStageOne(boost: BoostRuntime) =
            role == FloatingStartRole.BOOST_TWO && directBoostOneUnlocked && boost.stage == RingBoostStage.ONE
        // Exit the outer stage first. Entry is cumulative, Stage 1 then Stage 2.
        bankBoosts.asReversed().forEach { boost ->
            val returningStageOne = isReturningStageOne(boost)
            if (direct && boost.stage != selectedDirect && !returningStageOne) return@forEach
            val settings = boost.settings
            val stillInside = if (direct && !returningStageOne) geometry.directHit(boost.stage, x, y, gestureBank ?: config.directBoostBank())
                else settings.enabled && distance >= geometry.boostDistance(boost.stage, compactAim, adjacentMovement, gestureBank) -
                    if (boost.inside) geometry.boostHysteresis(boost.stage, compactAim, adjacentMovement, gestureBank) else 0f
            if (boost.inside && !stillInside) { boost.inside = false; exitBoost(boost) }
        }
        bankBoosts.forEach { boost ->
            val returningStageOne = isReturningStageOne(boost)
            if (direct && boost.stage != selectedDirect && !returningStageOne) return@forEach
            val settings = boost.settings
            val crossedStageOne = returningStageOne && returningToBoostOne
            val threshold = geometry.boostDistance(boost.stage, compactAim, adjacentMovement, gestureBank)
            val sharesInnerBoundary = threshold == geometry.innerRadius || threshold == geometry.movementRadius
            val enters = if (direct && !returningStageOne) geometry.directHit(boost.stage, x, y, gestureBank ?: config.directBoostBank())
                else settings.enabled && if ((compactAim || adjacentMovement || sharesInnerBoundary) && boost.stage == RingBoostStage.ONE)
                    distance > threshold else distance >= threshold
            // A fast inward sample can skip the entire lower band. Visit it once using the
            // incoming direction, then apply its normal exit/latch rules at the current point.
            val direction = if (crossedStageOne && !enters) direction(previousX, previousY, boost.direction)
                else direction(x, y, boost.direction)
            if (!boost.inside && (enters || crossedStageOne)) {
                boost.inside = true
                enterBoost(boost, direction, eventTime)
                if (distance < threshold - geometry.boostHysteresis(boost.stage, compactAim, adjacentMovement, gestureBank)) {
                    boost.inside = false
                    exitBoost(boost)
                }
            }
            else if (boost.inside && direction != boost.direction && !boost.pending && !boost.latched) {
                if (settings.action != RingAction.TAP) stopOutput(boost.output)
                enterBoost(boost, direction, eventTime)
            }
        }
    }

    private fun enterBoost(
        boost: BoostRuntime, direction: StickDirection, eventTime: Long, directTouch: Boolean = false
    ) {
        val settings = boost.settings
        visitedStages.add(boost.stage)
        boost.direction = direction
        if (processingLift) return
        startBoostEntryFire(boost.stage, eventTime)
        if (boost.pending || boost.latched || boost.output.retained || boost.output.slot in overridden) return
        val payload = settings.payloadFor(direction)
        if (payload.isBlank()) return
        if (fireStopped && config.knobs.any { backend.payloadsOverlap(payload, it.fire.firePayload) }) return
        if (adsStopped && (isAdsPayload(payload) ||
                config.knobs.any { it.fire.adsPayload.isNotBlank() && backend.payloadsOverlap(payload, it.fire.adsPayload) })) return
        // Only a fresh direct Boost 2 DOWN can bypass its own activation wait. Later
        // entries from this same pointer, qualified fire and pulse timing remain independent.
        val skipActivationDelay = directTouch && boost.stage == RingBoostStage.TWO && settings.skipDelayOnDirectTouch
        if (settings.activationDelayMs > 0L && !skipActivationDelay) {
            boost.pending = true
            boost.pendingPayload = payload
            val generation = ++boost.delayGeneration
            val liveGesture = gestureGeneration
            val livePointer = pointerId
            boost.delay = scheduler.schedule((eventTime + settings.activationDelayMs - now()).coerceAtLeast(0L)) {
                if (boost.delayGeneration != generation || gestureGeneration != liveGesture ||
                    pointerId != livePointer || pointerId == -1 || finished ||
                    (!boost.inside && settings.cancelDelayOnExit)) return@schedule
                boost.pending = false
                boost.delay = null
                activateBoost(boost, settings, payload)
                publish()
            }
        } else activateBoost(boost, settings, payload)
    }

    private fun activateBoost(boost: BoostRuntime, settings: RingBoostSettings, payload: String) {
        when (settings.action) {
            RingAction.TAP -> finitePulse(boost.output, payload, settings.pulsePressMs, settings.pulseGapMs)
            RingAction.HOLD -> {
                if (boost.inside || settings.latchOnExit) {
                    hold(boost.output, payload)
                    boost.latched = settings.latchOnExit && boost.output.held
                    if (boost.latched && (fineGesture || immediateFineGesture) && boost.stage in config.aim.fine.qualifyingStages) {
                        fineLatchSlots.add(boost.output.slot)
                    }
                }
            }
            RingAction.REPEAT -> if (boost.inside || settings.repeatAfterExit) {
                repeat(boost.output, payload, settings.pulsePressMs, settings.pulseGapMs)
            }
        }
    }

    private fun exitBoost(boost: BoostRuntime) {
        val settings = boost.settings
        if (settings.cancelDelayOnExit) cancelBoostDelay(boost)
        when (settings.action) {
            RingAction.HOLD -> if (!boost.latched && !boost.output.retained) stopOutput(boost.output)
            RingAction.REPEAT -> if (!settings.repeatAfterExit && !boost.output.retained) stopOutput(boost.output)
            RingAction.TAP -> Unit
        }
    }

    private fun cancelBoostDelay(boost: BoostRuntime) {
        boost.delayGeneration++
        boost.delay?.cancel()
        boost.delay = null
        boost.pending = false
        boost.pendingPayload = ""
    }

    private fun hold(output: OutputAction, payload: String) {
        if (payload.isBlank() || output.slot in overridden) return
        if (output.held && output.payload == payload && backend.payloadIsOwned(output.slot)) {
            output.retained = false
            backend.retainPayload(output.slot, false)
            return
        }
        stopOutput(output)
        if (backend.acquirePayload(output.slot, payload)) {
            output.payload = payload
            output.held = true
            output.activeGesture = gestureGeneration
        } else overridden.add(output.slot)
    }

    private fun finitePulse(output: OutputAction, payload: String, pressMs: Long, gapMs: Long) {
        if (payload.isBlank() || output.slot in overridden) return
        if (output.busy) {
            if (!output.queued) {
                output.queued = true
                output.queuedPayload = payload
                output.queuedPressMs = pressMs
                output.queuedGapMs = gapMs
                output.queuedGesture = gestureGeneration
            }
            return
        }
        if (!backend.reservePayload(output.slot, payload)) { overridden.add(output.slot); return }
        output.pressMs = pressMs
        output.gapMs = gapMs
        output.payload = payload
        output.busy = true
        output.activeGesture = gestureGeneration
        val generation = ++output.generation
        runPulse(output, generation)
    }

    private fun repeat(output: OutputAction, payload: String, pressMs: Long, gapMs: Long) {
        if (payload.isBlank() || output.slot in overridden) return
        if (output.repeating && output.payload == payload) return
        stopOutput(output)
        if (!backend.reservePayload(output.slot, payload)) { overridden.add(output.slot); return }
        output.payload = payload
        output.pressMs = pressMs
        output.gapMs = gapMs
        output.repeating = true
        output.busy = true
        output.activeGesture = gestureGeneration
        runPulse(output, ++output.generation)
    }

    private fun runPulse(output: OutputAction, generation: Long) {
        if (finished || output.generation != generation || output.slot in overridden) return
        if (!backend.acquirePayload(output.slot, output.payload)) {
            overridden.add(output.slot)
            stopOutput(output)
            publish()
            return
        }
        output.held = true
        if (output.retained) backend.retainPayload(output.slot, true)
        output.task = scheduler.schedule(output.pressMs.coerceAtLeast(1L)) {
            if (finished || output.generation != generation) return@schedule
            backend.releasePayload(output.slot)
            output.held = false
            output.task = scheduler.schedule(output.gapMs.coerceAtLeast(1L)) gap@{
                if (finished || output.generation != generation) return@gap
                output.task = null
                if (output.repeating || output.queued) {
                    if (!output.repeating && output.queued) {
                        val payload = output.queuedPayload
                        backend.endPayload(output.slot)
                        if (!backend.reservePayload(output.slot, payload)) {
                            overridden.add(output.slot)
                            stopOutput(output)
                            publish()
                            return@gap
                        }
                        output.payload = payload
                        output.pressMs = output.queuedPressMs
                        output.gapMs = output.queuedGapMs
                        output.activeGesture = output.queuedGesture
                    }
                    output.queued = false
                    output.queuedPayload = ""
                    output.queuedGesture = -1L
                    runPulse(output, generation)
                } else {
                    output.busy = false
                    backend.endPayload(output.slot)
                }
                publish()
            }
            publish()
        }
    }

    private fun stopOutput(output: OutputAction) {
        output.generation++
        output.task?.cancel()
        output.task = null
        backend.endPayload(output.slot)
        output.held = false
        output.retained = false
        output.repeating = false
        output.busy = false
        output.queued = false
        output.queuedPayload = ""
        output.queuedGesture = -1L
        output.activeGesture = -1L
    }

    private fun retainOrRelease(output: OutputAction, retain: Boolean) {
        if (retain && (output.held || output.repeating)) {
            output.retained = true
            backend.retainPayload(output.slot, true)
        } else stopOutput(output)
    }

    private fun sendMovement(vector: RingPoint, retained: Boolean) {
        val settings = config.movement
        val mapped = when (settings.profile) {
            ButtonAimStickProfile.LINEAR -> vector.x * settings.sensitivity to vector.y * settings.sensitivity
            ButtonAimStickProfile.RESPONSE_CURVE -> StickResponseCurve.apply(vector.x, settings.sensitivity) to
                StickResponseCurve.apply(vector.y, settings.sensitivity)
            ButtonAimStickProfile.RADIAL_RESPONSE_CURVE -> StickResponseCurve.applyRadial(vector.x, vector.y, settings.sensitivity)
        }
        movementVector = RingPoint(mapped.first.coerceIn(-1f, 1f), mapped.second.coerceIn(-1f, 1f))
        if (movementVector.x != 0f || movementVector.y != 0f) movementRecentered = false
        backend.movement(movementVector.x, movementVector.y, retained)
    }

    private fun direction(x: Float, y: Float, previous: StickDirection? = null): StickDirection {
        val dx = x - geometry.center.x
        val dy = y - geometry.center.y
        val proposed = if (abs(dx) >= abs(dy)) {
            if (dx >= 0f) StickDirection.RIGHT else StickDirection.LEFT
        } else if (dy >= 0f) StickDirection.DOWN else StickDirection.UP
        if (previous != null && previous != proposed) {
            // Signed projection allows reversal on the same axis while keeping the 12 dp
            // margin between neighboring directional sectors.
            val oldAxis = when (previous) {
                StickDirection.LEFT -> -dx
                StickDirection.RIGHT -> dx
                StickDirection.UP -> -dy
                StickDirection.DOWN -> dy
            }
            val newAxis = if (proposed == StickDirection.LEFT || proposed == StickDirection.RIGHT) abs(dx) else abs(dy)
            if (newAxis < oldAxis + 12f * geometry.density) return previous
        }
        return proposed
    }

    private fun effectiveFine(): Boolean = config.aim.fine.enabled && (fineGesture || immediateFineGesture ||
        (config.aim.fine.persistWhileAdsLatched && boosts.any {
            it.output.slot in fineLatchSlots && it.latched && it.output.held &&
                backend.payloadIsOwned(it.output.slot)
        }))

    private fun isAdsPayload(payload: String): Boolean = payload.uppercase().split(Regex("[,\\s]+" )).any {
        it.startsWith("LT:") || it == "MOUSE_RIGHT_DOWN"
    }

    private fun outputs() = listOf(ads, fire) + boosts.map { it.output }
    private fun activeRuntimeBank() = if (config.boostBanks?.enabled == true) gestureBank else null
    private fun gestureBoosts() = boosts.filter { it.bank == activeRuntimeBank() }
    private fun endPointer() {
        pointerId = -1
        role = null
        gestureBank = null
        earlyFireKnob = null
        directBoostOneUnlocked = false
        aimActive = false
        aimSuspended = false
        fineGesture = false
        immediateFineGesture = false
        visitedStages.clear()
        preexistingSlots.clear()
        preexistingRetained.clear()
        armed = false
        fireEligible = false
        finishEntryEligible = true
    }
    private fun publish() = onStateChanged(status)

    companion object {
        const val ADS = "ads"
        const val FIRE = "fire"
        const val BOOST_ONE = "boost1"
        const val BOOST_TWO = "boost2"
    }
}
