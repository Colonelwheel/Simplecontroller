package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ButtonAimPayloadPolicy
import com.example.simplecontroller.model.PayloadModifiers

/** A finite, single-run Button payload whose timed steps survive normal finger-up. */
class ButtonMacroController(private val executor: ButtonAimPayloadExecutor) {
    private var lease: ButtonAimPayloadLease? = null

    val isActive: Boolean get() = executor.isLeaseActive(lease)

    /** Extra taps while running are ignored; a completed macro can be started again. */
    fun press(payload: String): String? {
        if (isActive) return null
        validationError(payload)?.let { return it }
        return when (val result = executor.activate(
            ButtonAimPayloadOwner.BASE,
            payload,
            immediatePressDurationMs = DEFAULT_PRESS_MS,
            completeOnRelease = true
        )) {
            is ButtonAimPayloadActivationResult.Activated -> {
                lease = result.lease
                null
            }
            is ButtonAimPayloadActivationResult.Invalid -> result.reason
            ButtonAimPayloadActivationResult.ReleaseAll -> "Release All cannot use Macro Mode."
        }
    }

    fun release() {
        executor.releaseNormally(lease)
    }

    /** Lifecycle cleanup, Cancel and Release All never complete a macro in the background. */
    fun cancel() {
        executor.releaseAllLeases()
        lease = null
    }

    companion object {
        const val DEFAULT_PRESS_MS = 90L

        fun validationError(payload: String): String? {
            val parsed = PayloadModifiers.parse(payload)
            parsed.error?.let { return it }
            if (parsed.commands.isEmpty()) return "Macro Mode needs a payload."
            ButtonAimPayloadPolicy.stateValidationError(payload)?.let { return it }
            if (parsed.commands.any { it.repeatIntervalMs != null }) {
                return "Macro Mode runs once. Use D and U for timed presses, or turn off Macro Mode to use R."
            }
            return null
        }
    }
}
