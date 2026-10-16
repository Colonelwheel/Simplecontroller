package com.example.simplecontroller.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handler callbacks must never perform socket I/O on the Android main thread. */
internal fun sendUdpHeartbeat(
    scope: CoroutineScope,
    socket: DatagramSocket,
    address: InetAddress,
    port: Int,
    isCurrent: () -> Boolean,
    onFailure: (Exception) -> Unit
) = scope.launch(Dispatchers.IO) {
    if (!isCurrent()) return@launch
    try {
        // Keep the unprefixed PING understood by both Windows and ConsoleBridge.
        val bytes = "PING".toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, address, port))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // A queued failure on an old socket must not disconnect its replacement.
        if (isCurrent()) onFailure(error)
    }
}
