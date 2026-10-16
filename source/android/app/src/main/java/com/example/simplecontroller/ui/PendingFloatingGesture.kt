package com.example.simplecontroller.ui

/**
 * Waits for connection readiness only while the original contact is still held.
 * The clock must be monotonic and the scheduler must enqueue, rather than invoke, its task.
 */
internal class PendingFloatingGesture<T : Any>(
    private val now: () -> Long,
    private val schedule: (delayMs: Long, task: () -> Unit) -> ButtonAimScheduledTask,
    private val timeoutMs: Long = 1500L,
    private val pollMs: Long = 16L,
    private val onReady: (T, Int, Float, Float, Long) -> Unit,
    private val onAbandoned: (timedOut: Boolean) -> Unit = {}
) {
    private class Contact<T>(
        val generation: Long,
        val data: T,
        val pointer: Int,
        var x: Float,
        var y: Float,
        val startedAt: Long,
        val isCurrent: () -> Boolean,
        val isReady: () -> Boolean,
        var task: ButtonAimScheduledTask? = null
    )

    private var generation = 0L
    private var contact: Contact<T>? = null

    init {
        require(timeoutMs > 0L) { "The pending gesture timeout must be positive." }
        require(pollMs > 0L) { "The pending gesture poll interval must be positive." }
    }

    val isPending: Boolean get() = contact != null

    fun begin(
        data: T,
        pointer: Int,
        x: Float,
        y: Float,
        isCurrent: () -> Boolean,
        isReady: () -> Boolean
    ) {
        cancel()
        val next = Contact(generation, data, pointer, x, y, now(), isCurrent, isReady)
        contact = next
        enqueue(next, 0L)
    }

    /** Other fingers cannot move the original contact or take over its pending activation. */
    fun update(pointer: Int, x: Float, y: Float) {
        val active = contact ?: return
        if (pointer != active.pointer) return
        if (!x.isFinite() || !y.isFinite()) {
            abandon(active, timedOut = false)
            return
        }
        active.x = x
        active.y = y
    }

    /** UP, CANCEL and explicit cleanup are silent and permanently invalidate queued callbacks. */
    fun cancel() {
        generation++
        val previous = contact
        contact = null
        val task = previous?.task
        previous?.task = null
        task?.cancel()
    }

    private fun active(candidate: Contact<T>): Boolean =
        contact === candidate && candidate.generation == generation

    private fun validPoint(candidate: Contact<T>): Boolean =
        candidate.pointer >= 0 && candidate.x.isFinite() && candidate.y.isFinite()

    private fun remaining(candidate: Contact<T>, time: Long): Long =
        timeoutMs - (time - candidate.startedAt).coerceAtLeast(0L)

    private fun enqueue(candidate: Contact<T>, delayMs: Long) {
        val task = schedule(delayMs) {
            if (!active(candidate)) return@schedule
            candidate.task = null
            poll(candidate)
        }
        if (active(candidate)) candidate.task = task else task.cancel()
    }

    private fun poll(candidate: Contact<T>) {
        if (!active(candidate)) return
        if (!validPoint(candidate)) {
            abandon(candidate, timedOut = false)
            return
        }
        val current = candidate.isCurrent()
        if (!active(candidate)) return
        if (!current) {
            abandon(candidate, timedOut = false)
            return
        }
        if (remaining(candidate, now()) <= 0L) {
            abandon(candidate, timedOut = true)
            return
        }

        val ready = candidate.isReady()
        if (!active(candidate)) return
        if (ready) {
            // Readiness preparation may synchronously replace the route or end this contact.
            val stillCurrent = candidate.isCurrent()
            if (!active(candidate)) return
            if (!stillCurrent || !validPoint(candidate)) {
                abandon(candidate, timedOut = false)
                return
            }
            val activationTime = now()
            if (remaining(candidate, activationTime) <= 0L) {
                abandon(candidate, timedOut = true)
                return
            }
            if (consume(candidate)) {
                onReady(candidate.data, candidate.pointer, candidate.x, candidate.y, activationTime)
            }
        } else {
            val left = remaining(candidate, now())
            if (left <= 0L) abandon(candidate, timedOut = true)
            else enqueue(candidate, minOf(pollMs, left))
        }
    }

    private fun consume(candidate: Contact<T>): Boolean {
        if (!active(candidate)) return false
        generation++
        contact = null
        val task = candidate.task
        candidate.task = null
        task?.cancel()
        return true
    }

    private fun abandon(candidate: Contact<T>, timedOut: Boolean) {
        if (consume(candidate)) onAbandoned(timedOut)
    }
}
