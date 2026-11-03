package com.example.simplecontroller.ui

/** Confirms one steady target stage without changing the legacy score/hysteresis calculation. */
internal class TouchAimStageConfirmation(
    private val confirmationMs: (TouchContactLevel) -> Long
) {
    var confirmedLevel: TouchContactLevel = TouchContactLevel.AIM_ONLY
        private set
    private var candidate: TouchContactLevel? = null
    private var candidateSinceMs = 0L

    fun onTarget(target: TouchContactLevel, timeMs: Long): TouchContactLevel? {
        if (target == confirmedLevel) {
            candidate = null
            return null
        }
        if (candidate != target) {
            candidate = target
            candidateSinceMs = timeMs
        }
        return onTime(timeMs)
    }

    fun nextDeadlineMs(): Long? = candidate?.let {
        candidateSinceMs + confirmationMs(it).coerceAtLeast(0L)
    }

    /** A stationary contact can finish confirmation without another MOVE sample. */
    fun onTime(timeMs: Long): TouchContactLevel? {
        val target = candidate ?: return null
        if (timeMs < nextDeadlineMs()!!) return null
        confirmedLevel = target
        candidate = null
        return target
    }

    fun reset() {
        confirmedLevel = TouchContactLevel.AIM_ONLY
        candidate = null
        candidateSinceMs = 0L
    }
}

/** A legacy jump fires crossed PRESS stages; with confirmation only the confirmed target fires. */
internal fun enteredTouchAimPressLevels(
    previous: TouchContactLevel,
    next: TouchContactLevel,
    hasStageConfirmation: Boolean
): Set<TouchContactLevel> {
    if (next.rank <= previous.rank) return emptySet()
    return if (hasStageConfirmation) setOf(next) else TouchContactLevel.entries.filterTo(linkedSetOf()) {
        it.rank in (previous.rank + 1)..next.rank
    }
}
