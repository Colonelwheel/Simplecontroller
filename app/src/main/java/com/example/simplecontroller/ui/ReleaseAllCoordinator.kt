package com.example.simplecontroller.ui

import com.example.simplecontroller.net.UdpClient

/** One Android-side entry point for the Release Everything safety action. */
object ReleaseAllCoordinator {
    enum class ReceiverStatus {
        PENDING,
        CONFIRMED,
        UNAVAILABLE,
        CONSOLEBRIDGE_UNAVAILABLE
    }

    fun releaseAll(onReceiverStatus: (ReceiverStatus) -> Unit = {}): ReceiverStatus {
        // Invalidate network work before any view cleanup can race an old queued send.
        UdpClient.prepareReleaseAll()
        SwipeManager.releaseEverythingLocally()

        val status = when (UdpClient.sendReleaseAll { acknowledged ->
            onReceiverStatus(
                if (acknowledged) ReceiverStatus.CONFIRMED else ReceiverStatus.UNAVAILABLE
            )
        }) {
            UdpClient.ReleaseDelivery.ACK_PENDING -> ReceiverStatus.PENDING
            UdpClient.ReleaseDelivery.RECEIVER_UNAVAILABLE -> ReceiverStatus.UNAVAILABLE
            UdpClient.ReleaseDelivery.CONSOLEBRIDGE_UNAVAILABLE ->
                ReceiverStatus.CONSOLEBRIDGE_UNAVAILABLE
        }
        onReceiverStatus(status)
        return status
    }
}
