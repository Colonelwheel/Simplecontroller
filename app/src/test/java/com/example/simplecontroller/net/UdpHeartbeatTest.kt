package com.example.simplecontroller.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class UdpHeartbeatTest {
    @Test fun callerDispatcherIsNotUsedForHeartbeatAndWirePayloadStaysUnprefixed() = runBlocking {
        var worker: Thread? = null
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { callerDispatcher ->
            withContext(callerDispatcher) {
                val caller = Thread.currentThread()
                DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
                    receiver.soTimeout = 1000
                    DatagramSocket().use { sender ->
                        sendUdpHeartbeat(this, sender,
                            InetAddress.getLoopbackAddress(), receiver.localPort,
                            { worker = Thread.currentThread(); true }, { throw AssertionError(it) }).join()
                        val packet = DatagramPacket(ByteArray(64), 64)
                        receiver.receive(packet)
                        assertEquals("PING", String(packet.data, 0, packet.length, Charsets.UTF_8))
                        assertNotSame(caller, worker)
                    }
                }
            }
        }
    }

    @Test fun obsoleteRouteSendsNothing() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 150
            DatagramSocket().use { sender ->
                sendUdpHeartbeat(this, sender, InetAddress.getLoopbackAddress(), receiver.localPort,
                    { false }, { fail("An obsolete route must be ignored") }).join()
                try {
                    receiver.receive(DatagramPacket(ByteArray(64), 64))
                    fail("An obsolete heartbeat was sent")
                } catch (_: SocketTimeoutException) { }
            }
        }
    }

    @Test fun closedCurrentSocketReportsFailureButReplacedSocketDoesNot() = runBlocking {
        val sender = DatagramSocket().also { it.close() }
        var failures = 0
        sendUdpHeartbeat(this, sender, InetAddress.getLoopbackAddress(), 1,
            { true }, { failures++ }).join()
        assertEquals(1, failures)
        var checks = 0
        sendUdpHeartbeat(this, sender, InetAddress.getLoopbackAddress(), 1,
            { ++checks == 1 }, { failures++ }).join()
        assertEquals(2, checks)
        assertEquals(1, failures)
    }
}
