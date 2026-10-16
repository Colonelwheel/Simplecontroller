package com.example.simplecontroller.net

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Assigns every outgoing controller packet a process-local sequence and generation.
 * A release invalidates already-queued sends before creating its ordered barrier packet.
 */
internal class OutputSafetyEpoch(
    val sessionId: String = UUID.randomUUID().toString().replace("-", "")
) {
    data class Ticket(
        val sessionId: String,
        val sequence: Long,
        val generation: Long
    )

    private val sequence = AtomicLong(0L)
    private val generation = AtomicLong(0L)

    fun nextTicket(): Ticket = Ticket(
        sessionId = sessionId,
        sequence = sequence.getAndIncrement(),
        generation = generation.get()
    )

    fun invalidateQueuedSends() {
        generation.incrementAndGet()
    }

    fun isCurrent(ticket: Ticket): Boolean = ticket.generation == generation.get()

    fun wrapOutput(ticket: Ticket, command: String): String =
        "OUTPUT:${ticket.sessionId}:${ticket.sequence}:$command"

    fun releaseAllCommand(ticket: Ticket): String =
        "RELEASE_ALL:${ticket.sessionId}:${ticket.sequence}"

    fun releaseAllAcknowledgement(ticket: Ticket): String =
        "RELEASE_ALL_ACK:${ticket.sessionId}:${ticket.sequence}"
}
