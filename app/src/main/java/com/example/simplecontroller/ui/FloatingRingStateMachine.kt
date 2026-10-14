package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ButtonAimOutput
import com.example.simplecontroller.model.ButtonAimStickProfile
import com.example.simplecontroller.model.ButtonAimTuning
import com.example.simplecontroller.model.FloatingRingConfig
import com.example.simplecontroller.model.FloatingStartRole
import com.example.simplecontroller.model.RingAction
import com.example.simplecontroller.model.RingAimKnob
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
    val aimRetained: Boolean = false
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
    private val onFinish: () -> Unit = {}
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
    private class BoostRuntime(val stage: RingBoostStage, val output: OutputAction) {
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
    private val boosts = listOf(
        BoostRuntime(RingBoostStage.ONE, OutputAction(BOOST_ONE)),
        BoostRuntime(RingBoostStage.TWO, OutputAction(BOOST_TWO))
    )
    private var pointerId = -1
    private var role: FloatingStartRole? = null
    private var knob: RingAimKnob? = null
    private var gestureGeneration = 0L
    private var returnGeneration = 0L
    private var returnTask: ButtonAimScheduledTask? = null
    private var returnPending = false
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
    private val fineLatchStages = mutableSetOf<RingBoostStage>()
    private var suppression = false
    private var finished = false
    private var finishEntryEligible = true
    private var processingLift = false
    private var movementRetained = false
    private var movementVector = RingPoint(0f, 0f)
    private var lastX = 0f
    private var lastY = 0f
    private var logicalOffsetX = 0f
    private var logicalOffsetY = 0f
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
                val settings = config.boost(boost.stage)
                val affectsFire = config.knobs.any { backend.payloadsOverlap(payloadForSlot(boost.output.slot), it.fire.firePayload) }
                (boost.output.held || boost.output.repeating || boost.output.busy || boost.pending) &&
                    (settings.action != RingAction.HOLD || affectsFire) && backend.payloadMasked(boost.output.slot)
            },
        boosts.filter { it.output.held }.mapTo(mutableSetOf()) { it.stage },
        boosts.filter { it.output.repeating }.mapTo(mutableSetOf()) { it.stage },
        boosts.filter { it.latched && it.output.held }.mapTo(mutableSetOf()) { it.stage },
        backend.aimIsRetained()
    )

    /** The UI refreshes this only when the receiver's conflict snapshot changes, never by polling. */
    fun refreshFeedback() = publish()

    fun payloadForSlot(slot: String): String = when (slot) {
        ADS -> ads.payload.ifBlank { knob?.fire?.adsPayload.orEmpty() }
        FIRE -> fire.payload.ifBlank { knob?.fire?.firePayload.orEmpty() }
        BOOST_ONE, BOOST_TWO -> boosts.first { it.output.slot == slot }.let {
            it.pendingPayload.ifBlank { it.output.payload }
        }
        else -> ""
    }

    fun payloadsForSlot(slot: String): Set<String> = if (slot == BOOST_ONE || slot == BOOST_TWO) {
        boosts.first { it.output.slot == slot }.let {
            setOf(it.pendingPayload, it.output.payload, it.output.queuedPayload).filterTo(mutableSetOf(), String::isNotBlank)
        }
    } else setOf(payloadForSlot(slot)).filterTo(mutableSetOf(), String::isNotBlank)

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
        val selectedKnob = config.knob(selectedKnobId)?.takeIf { it.enabled }
        val valid = when (startingRole) {
            FloatingStartRole.MOVEMENT -> config.movement.enabled
            FloatingStartRole.AIM -> config.aim.enabled && selectedKnob != null
            FloatingStartRole.BOOST_ONE -> config.boost1.enabled && config.boost1.directTapEnabled
            FloatingStartRole.BOOST_TWO -> config.boost2.enabled && config.boost2.directTapEnabled
        }
        if (!valid) return false
        // Fitting may put the birth pointer physically over Finish while the selected role is
        // logically under its grab. Only a later exit followed by entry counts as a Finish drag.
        finishEntryEligible = !geometry.finish.contains(x, y)
        gestureGeneration++
        pointerId = id
        role = startingRole
        logicalOffsetX = offsetX
        logicalOffsetY = offsetY
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
                val selected = if (startingRole == FloatingStartRole.BOOST_ONE) boosts[0] else boosts[1]
                selected.inside = true
                enterBoost(selected, direction(x + offsetX, y + offsetY), time)
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
        if (insideFinish && finishEntryEligible) { finish(); return }
        if (!insideFinish) finishEntryEligible = true
        lastX = x
        lastY = y
        processingLift = finalLift
        val logicalX = x + logicalOffsetX
        val logicalY = y + logicalOffsetY
        val wasInsideAim = insideAim
        insideAim = geometry.insideAim(logicalX, logicalY)
        if (role == FloatingStartRole.MOVEMENT) {
            sendMovement(geometry.movementVector(logicalX, logicalY), false)
        }
        val direct = role == FloatingStartRole.BOOST_ONE || role == FloatingStartRole.BOOST_TWO
        if (direct && !aimActive && config.directBoostToAim && insideAim && !processingLift) {
            val destination = config.knob(config.directBoostAimKnobId)?.takeIf { it.enabled }
            if (destination != null) startAim(destination, x, y, time, directAddition = true)
        }
        if (participatesInBoosts()) evaluateBoosts(logicalX, logicalY, direct, time)
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
        val fireSettings = knob?.fire
        cancelReturn()
        boosts.forEach(::cancelBoostDelay)
        if (aimActive && fireSettings?.mode == RingFireMode.RELEASE_SHOT && armed && !releaseCanceled && !fireStopped) {
            finitePulse(fire, fireSettings.firePayload, fireSettings.pulsePressMs, fireSettings.pulseGapMs)
        }
        armed = false
        if (role == FloatingStartRole.MOVEMENT) {
            movementRetained = !config.movement.autoCenter
            if (movementRetained) backend.movement(movementVector.x, movementVector.y, true)
            else { backend.releaseMovement(); movementVector = RingPoint(0f, 0f) }
        }
        if (aimActive) {
            backend.completeAim(config.aim.tuning.autoCenter)
            if (fireSettings != null) {
                retainOrRelease(ads, fireSettings.retainAdsOnLift)
                if (fireSettings.mode != RingFireMode.TAP && fireSettings.mode != RingFireMode.RELEASE_SHOT) {
                    retainOrRelease(fire, fireSettings.retainFireOnLift)
                }
            }
        }
        boosts.forEach { boost ->
            if (!boost.inside && !boost.latched && !boost.output.repeating && !boost.output.held) return@forEach
            val settings = config.boost(boost.stage)
            if (settings.action != RingAction.TAP) retainOrRelease(boost.output, settings.retainOnLift)
            boost.latched = boost.latched && boost.output.held
            if (!boost.latched) fineLatchStages.remove(boost.stage)
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
            if (!it.latched) fineLatchStages.remove(it.stage)
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
        fineLatchStages.clear()
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
                if (fineLatchStages.remove(boost.stage)) {
                    fineGesture = false
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
                fineLatchStages.remove(boost.stage)
            }
        }
        fineGesture = false
        backend.updateAimSensitivity(config.aim.tuning.sensitivity)
        publish()
    }

    fun endSuppression() {
        suppression = false
        backend.mouseSuppression(false)
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
            if (fineLatchStages.remove(it.stage)) {
                fineGesture = false
                backend.updateAimSensitivity(if (effectiveFine()) config.aim.fine.sensitivity else config.aim.tuning.sensitivity)
            }
        }
        publish()
    }

    private fun startAim(selected: RingAimKnob, x: Float, y: Float, time: Long, directAddition: Boolean): Boolean {
        if (knob?.id != null && knob?.id != selected.id) { stopOutput(fire); stopOutput(ads) }
        knob = selected
        val tuning = if (effectiveFine()) config.aim.tuning.copy(sensitivity = config.aim.fine.sensitivity)
            else config.aim.tuning
        if (!backend.beginAim(pointerId, x, y, time, tuning)) {
            overridden.add("aim")
            return false
        }
        aimActive = true
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
        if (settings.returnFire.enabled && settings.returnFire.cancelOnExit) {
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
            boosts.filter { it.stage in visitedStages && it.stage in config.aim.fine.qualifyingStages && it.latched }
                .forEach { fineLatchStages.add(it.stage) }
            backend.updateAimSensitivity(config.aim.fine.sensitivity)
        }
        if (releaseCanceled && settings.rearmReleaseShotOnBoundaryReturn) {
            releaseCanceled = false
            if (fireEligible) armed = settings.mode == RingFireMode.RELEASE_SHOT
        }
        if (!processingLift && !fireStopped && settings.mode != RingFireMode.NONE && settings.returnFire.enabled &&
            visitedStages.any { it in settings.returnFire.qualifyingStages } && !returnPending) {
            scheduleReturn(settings.returnFire.delayMs, time)
        } else if (!processingLift && settings.returnFire.enabled && fireEligible && !returnPending && !fireStopped) {
            activateFire()
        } else if (!processingLift && !settings.returnFire.enabled && !fireStopped) {
            if (settings.mode == RingFireMode.HOLD || settings.mode == RingFireMode.REPEAT) activateFire()
        }
        // Independent ADS configured for an ordinary aim gesture can resume; direct additions never invent it.
        if (!processingLift && role == FloatingStartRole.AIM && !settings.continueAdsOutside && !adsStopped) hold(ads, settings.adsPayload)
        visitedStages.clear()
    }

    private fun scheduleReturn(delayMs: Long, eventTime: Long) {
        val generation = ++returnGeneration
        val liveGesture = gestureGeneration
        val livePointer = pointerId
        returnPending = true
        fireEligible = false
        armed = false
        firedThisGesture = false
        val run = {
            if (returnGeneration == generation && gestureGeneration == liveGesture &&
                pointerId == livePointer && pointerId != -1 && !finished && !fireStopped &&
                aimActive && (insideAim || knob?.fire?.returnFire?.cancelOnExit == false)) {
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
    }

    private fun activateFire() {
        val settings = knob?.fire ?: return
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

    private fun evaluateBoosts(x: Float, y: Float, direct: Boolean, eventTime: Long) {
        val selectedDirect = if (role == FloatingStartRole.BOOST_ONE) RingBoostStage.ONE else RingBoostStage.TWO
        val distance = geometry.distance(x, y)
        // Exit the outer stage first. Entry is cumulative, Stage 1 then Stage 2.
        boosts.asReversed().forEach { boost ->
            if (direct && boost.stage != selectedDirect) return@forEach
            val settings = config.boost(boost.stage)
            val stillInside = if (direct) geometry.directHit(boost.stage, x, y)
                else settings.enabled && distance >= (settings.distanceDp -
                    if (boost.inside) settings.hysteresisDp else 0f) * geometry.density
            if (boost.inside && !stillInside) { boost.inside = false; exitBoost(boost) }
        }
        boosts.forEach { boost ->
            if (direct && boost.stage != selectedDirect) return@forEach
            val settings = config.boost(boost.stage)
            val enters = if (direct) geometry.directHit(boost.stage, x, y)
                else settings.enabled && distance >= settings.distanceDp * geometry.density
            val direction = direction(x, y, boost.direction)
            if (!boost.inside && enters) { boost.inside = true; enterBoost(boost, direction, eventTime) }
            else if (boost.inside && direction != boost.direction && !boost.pending && !boost.latched) {
                if (settings.action != RingAction.TAP) stopOutput(boost.output)
                enterBoost(boost, direction, eventTime)
            }
        }
    }

    private fun enterBoost(boost: BoostRuntime, direction: StickDirection, eventTime: Long) {
        val settings = config.boost(boost.stage)
        visitedStages.add(boost.stage)
        boost.direction = direction
        if (processingLift) return
        if (boost.pending || boost.latched || boost.output.retained || boost.output.slot in overridden) return
        val payload = settings.payloadFor(direction)
        if (payload.isBlank()) return
        if (fireStopped && config.knobs.any { backend.payloadsOverlap(payload, it.fire.firePayload) }) return
        if (adsStopped && (isAdsPayload(payload) ||
                config.knobs.any { it.fire.adsPayload.isNotBlank() && backend.payloadsOverlap(payload, it.fire.adsPayload) })) return
        if (settings.activationDelayMs > 0L) {
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
                    if (boost.latched && fineGesture && boost.stage in config.aim.fine.qualifyingStages) {
                        fineLatchStages.add(boost.stage)
                    }
                }
            }
            RingAction.REPEAT -> if (boost.inside || settings.repeatAfterExit) {
                repeat(boost.output, payload, settings.pulsePressMs, settings.pulseGapMs)
            }
        }
    }

    private fun exitBoost(boost: BoostRuntime) {
        val settings = config.boost(boost.stage)
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
        backend.movement(movementVector.x, movementVector.y, retained)
    }

    private fun direction(x: Float, y: Float, previous: StickDirection? = null): StickDirection {
        val dx = x - geometry.center.x
        val dy = y - geometry.center.y
        val proposed = if (abs(dx) >= abs(dy)) {
            if (dx >= 0f) StickDirection.RIGHT else StickDirection.LEFT
        } else if (dy >= 0f) StickDirection.DOWN else StickDirection.UP
        if (previous != null && previous != proposed) {
            val oldAxis = if (previous == StickDirection.LEFT || previous == StickDirection.RIGHT) abs(dx) else abs(dy)
            val newAxis = if (proposed == StickDirection.LEFT || proposed == StickDirection.RIGHT) abs(dx) else abs(dy)
            if (newAxis < oldAxis + 12f * geometry.density) return previous
        }
        return proposed
    }

    private fun effectiveFine(): Boolean = config.aim.fine.enabled && (fineGesture ||
        (config.aim.fine.persistWhileAdsLatched && boosts.any {
            it.stage in fineLatchStages && it.latched && it.output.held &&
                backend.payloadIsOwned(it.output.slot)
        }))

    private fun isAdsPayload(payload: String): Boolean = payload.uppercase().split(Regex("[,\\s]+" )).any {
        it.startsWith("LT:") || it == "MOUSE_RIGHT_DOWN"
    }

    private fun outputs() = listOf(ads, fire) + boosts.map { it.output }
    private fun endPointer() {
        pointerId = -1
        role = null
        aimActive = false
        aimSuspended = false
        fineGesture = false
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
