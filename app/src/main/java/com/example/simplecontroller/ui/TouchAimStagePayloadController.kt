package com.example.simplecontroller.ui

import com.example.simplecontroller.model.TouchStageAction
import com.example.simplecontroller.model.ButtonAimPayloadPolicy
import com.example.simplecontroller.model.PayloadModifiers

internal data class TouchAimPayloadStage(
    val level: TouchContactLevel,
    val payload: String,
    val action: TouchStageAction
)

/** A modifier opts the complete stage set into shared ownership, so every stage must be reversible. */
internal fun touchAimStagePayloadValidationError(stages: List<TouchAimPayloadStage>): String? =
    if (stages.any { PayloadModifiers.hasModifiers(it.payload) }) {
        stages.firstNotNullOfOrNull { ButtonAimPayloadPolicy.stateValidationError(it.payload) }
    } else null

/** Stage eligibility owns payloads; only a real contact lift is allowed to start HRD. */
internal class TouchAimStagePayloadController(
    private val executor: ButtonAimPayloadExecutor,
    private val onPayloadError: (String) -> Unit
) {
    private val stageLeases = linkedMapOf<TouchContactLevel, ButtonAimPayloadLease>()
    private val finiteLeases = linkedMapOf<Long, ButtonAimPayloadLease>()

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
            val lease = stageLeases.remove(level) ?: return@forEach
            val stage = stages.first { it.level == level }
            executor.releaseNormally(
                lease, applyHoldReleaseDelay = false,
                preserveFinitePress = stage.action == TouchStageAction.PRESS
            )
        }
    }

    fun finish(cancelled: Boolean) {
        val leases = (stageLeases.values + finiteLeases.values).distinctBy { it.id }.toTypedArray()
        if (cancelled) executor.releaseTogether(*leases)
        else executor.releaseNormallyTogether(*leases)
        stageLeases.clear()
        finiteLeases.clear()
    }

    private fun activate(stage: TouchAimPayloadStage) {
        if (stage.payload.isBlank()) return
        val press = stage.action == TouchStageAction.PRESS
        when (val result = executor.activate(
            ButtonAimPayloadOwner.BASE, stage.payload, ButtonAimPulseMode.ONCE,
            immediatePressDurationMs = if (press) PRESS_DURATION_MS else null
        )) {
            is ButtonAimPayloadActivationResult.Activated -> result.lease?.let { lease ->
                stageLeases[stage.level] = lease
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
