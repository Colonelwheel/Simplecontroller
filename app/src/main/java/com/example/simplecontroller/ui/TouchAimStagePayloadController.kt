package com.example.simplecontroller.ui

import com.example.simplecontroller.model.TouchStageAction
import com.example.simplecontroller.model.ButtonAimPayloadPolicy
import com.example.simplecontroller.model.PayloadModifiers

internal data class TouchAimPayloadStage(
    val level: TouchContactLevel,
    val payload: String,
    val action: TouchStageAction
)

internal data class TouchAimReleasedPayload(
    val payload: String,
    val lease: ButtonAimPayloadLease
)

/** A modifier opts the complete stage set into shared ownership, so every stage must be reversible. */
internal fun touchAimStagePayloadValidationError(stages: List<TouchAimPayloadStage>): String? =
    if (stages.any { PayloadModifiers.hasModifiers(it.payload) }) {
        stages.firstNotNullOfOrNull { ButtonAimPayloadPolicy.stateValidationError(it.payload) }
    } else null

/** Stage eligibility owns payloads; an ordinary stage exit or lift starts its release tail. */
internal class TouchAimStagePayloadController(
    private val executor: ButtonAimPayloadExecutor,
    private val onPayloadError: (String) -> Unit
) {
    private data class ActiveStage(val config: TouchAimPayloadStage, val lease: ButtonAimPayloadLease)
    private val stageLeases = linkedMapOf<TouchContactLevel, ActiveStage>()
    private val finiteLeases = linkedMapOf<Long, ButtonAimPayloadLease>()
    private val retiredHolds = mutableMapOf<TouchContactLevel, TouchAimReleasedPayload>()

    fun transition(
        stages: List<TouchAimPayloadStage>,
        next: TouchContactLevel,
        keepLowerHolds: Boolean,
        enteredPressLevels: Set<TouchContactLevel>
    ) {
        val eligible = stages.filter {
            if (keepLowerHolds) it.level.rank <= next.rank else it.level == next
        }
        val eligibleLevels = eligible.mapTo(mutableSetOf()) { it.level }
        // Acquire replacements before releasing departed stages. Shared outputs stay owned and
        // lower trigger values can be restored without an artificial release edge.
        eligible.filter { it.action == TouchStageAction.HOLD }.forEach { stage ->
            if (stage.level !in stageLeases) activate(stage)
        }
        stages.filter { it.action == TouchStageAction.PRESS && it.level in enteredPressLevels }
            .forEach(::activate)
        stageLeases.keys.filter { it !in eligibleLevels }.forEach { level ->
            val active = stageLeases.remove(level) ?: return@forEach
            executor.releaseNormally(
                active.lease,
                preserveFinitePress = active.config.action == TouchStageAction.PRESS
            )
            if (active.config.action == TouchStageAction.HOLD) {
                retiredHolds[level] = TouchAimReleasedPayload(active.config.payload, active.lease)
            }
        }
    }

    fun finish(cancelled: Boolean) {
        if (cancelled) {
            // A retired source can still own an HRD or release-shot timer after its stage left.
            executor.releaseAllLeases()
            retiredHolds.clear()
        } else {
            val leases = (stageLeases.values.map { it.lease } + finiteLeases.values)
                .distinctBy { it.id }.toTypedArray()
            executor.releaseNormallyTogether(*leases)
            stageLeases.forEach { (level, active) ->
                if (active.config.action == TouchStageAction.HOLD) {
                    retiredHolds[level] = TouchAimReleasedPayload(active.config.payload, active.lease)
                }
            }
        }
        stageLeases.clear()
        finiteLeases.clear()
    }

    private fun activate(stage: TouchAimPayloadStage) {
        if (stage.payload.isBlank()) return
        val press = stage.action == TouchStageAction.PRESS
        val resumeSource = if (press) null else retiredHolds.remove(stage.level)
            ?.takeIf { it.payload == stage.payload }?.lease
        when (val result = executor.activate(
            ButtonAimPayloadOwner.BASE, stage.payload, ButtonAimPulseMode.ONCE,
            immediatePressDurationMs = if (press) PRESS_DURATION_MS else null,
            resumeHoldReleaseFrom = resumeSource
        )) {
            is ButtonAimPayloadActivationResult.Activated -> result.lease?.let { lease ->
                stageLeases[stage.level] = ActiveStage(stage, lease)
                if (press) finiteLeases[lease.id] = lease
            }
            is ButtonAimPayloadActivationResult.Invalid -> onPayloadError(result.reason)
            ButtonAimPayloadActivationResult.ReleaseAll -> Unit
        }
    }

    companion object {
        private const val PRESS_DURATION_MS = 90L
    }
}
