package com.example.simplecontroller.ui

import com.example.simplecontroller.model.PayloadModifiers
import kotlin.math.min

fun autoTapUnsupportedPayloadReason(payload: String): String? {
    val parsed = PayloadModifiers.parse(payload)
    parsed.error?.let { return it }
    val commands = parsed.commands.map { it.command.uppercase() }
    return when {
        commands.isEmpty() -> "Auto-tap needs a button payload."
        commands.any { it.startsWith("PAGE_") } ->
            "Page actions cannot use Toggle auto-tap."
        commands.any { it == "RELEASE_ALL" } -> "Release All cannot use Toggle auto-tap."
        commands.any { it == "SCROLL_MODE_TOGGLE" } ->
            "Scroll Mode Toggle cannot use Toggle auto-tap."
        commands.any {
            it == "CAMERA_FOLLOW" || it == "CAMERA_FOLLOW:1" || it == "CAMERA_FOLLOW:0"
        } -> "Camera Follow cannot use Toggle auto-tap."
        else -> null
    }
}

/**
 * Runs a cancellable finite-press loop for one ordinary Button control.
 *
 * The interval is measured from the start of one tap to the start of the next. Each press is
 * released before another begins, and canceled callbacks are guarded by a generation token so a
 * stopped loop cannot restart itself later.
 */
class ButtonAutoTapController(
    private val scheduler: ButtonAimPayloadScheduler,
    private val pressPayload: () -> Boolean,
    private val releasePayload: () -> Unit,
    private val onStateChanged: (Boolean) -> Unit = {},
    private val cancelPayload: () -> Unit = releasePayload
) {
    var isActive: Boolean = false
        private set

    private var generation = 0L
    private var pulseHeld = false
    private var releaseTask: ButtonAimScheduledTask? = null
    private var nextTapTask: ButtonAimScheduledTask? = null

    /** Returns the resulting active state. */
    fun toggle(intervalMs: Long): Boolean {
        if (isActive) {
            stop(cancelPulse = false)
        } else {
            start(intervalMs)
        }
        return isActive
    }

    fun stop(cancelPulse: Boolean = true) {
        generation++
        cancelScheduledTasks()
        releaseCurrentPulse(cancelPulse)
        if (isActive) {
            isActive = false
            onStateChanged(false)
        }
    }

    private fun start(requestedIntervalMs: Long) {
        val intervalMs = requestedIntervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        val currentGeneration = ++generation
        isActive = true
        onStateChanged(true)
        runTap(currentGeneration, intervalMs)
    }

    private fun runTap(currentGeneration: Long, intervalMs: Long) {
        if (!isCurrent(currentGeneration)) return
        releaseCurrentPulse()

        if (!pressPayload()) {
            stop()
            return
        }
        pulseHeld = true

        val pressDurationMs = min(MAX_PRESS_DURATION_MS, intervalMs / 2L)
            .coerceAtLeast(1L)
        releaseTask = scheduler.schedule(pressDurationMs) {
            if (!isCurrent(currentGeneration)) return@schedule
            releaseTask = null
            releaseCurrentPulse()
        }
        nextTapTask = scheduler.schedule(intervalMs) {
            if (!isCurrent(currentGeneration)) return@schedule
            nextTapTask = null
            releaseTask?.cancel()
            releaseTask = null
            releaseCurrentPulse()
            runTap(currentGeneration, intervalMs)
        }
    }

    private fun isCurrent(expectedGeneration: Long): Boolean =
        isActive && generation == expectedGeneration

    private fun cancelScheduledTasks() {
        releaseTask?.cancel()
        nextTapTask?.cancel()
        releaseTask = null
        nextTapTask = null
    }

    private fun releaseCurrentPulse(cancel: Boolean = false) {
        if (!pulseHeld) return
        pulseHeld = false
        if (cancel) cancelPayload() else releasePayload()
    }

    companion object {
        const val MIN_INTERVAL_MS = 16L
        private const val MAX_PRESS_DURATION_MS = 50L
    }
}
