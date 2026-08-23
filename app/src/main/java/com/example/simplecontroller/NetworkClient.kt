package com.example.simplecontroller.net

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException

object NetworkClient {
    private const val TAG = "NetworkClient"
    private const val DEFAULT_HOST = "10.0.2.2"
    const val DEFAULT_RECEIVER_PORT = 42734
    private const val LEGACY_RECEIVER_PORT = 9001
    private const val DISCOVERY_REQUEST = "SIMPLE_CONTROLLER_DISCOVER"
    private const val DISCOVERY_RESPONSE_PREFIX = "SIMPLE_CONTROLLER_RECEIVER"
    private const val CONNECT_TIMEOUT_MS = 700
    private const val DISCOVERY_TIMEOUT_MS = 1200
    private const val HEARTBEAT_TIMEOUT_MS = 6500L

    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    enum class PlayerRole {
        PLAYER1,
        PLAYER2
    }

    private data class ReceiverEndpoint(
        val address: InetAddress,
        val port: Int,
        val discovered: Boolean
    )

    private var currentPlayerRole = PlayerRole.PLAYER1

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus = _connectionStatus.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage = _lastErrorMessage.asStateFlow()

    private var hostAddress = DEFAULT_HOST
    private var portNumber = DEFAULT_RECEIVER_PORT
    private var activePortNumber = DEFAULT_RECEIVER_PORT

    private var autoReconnect = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectDelayMs = 3000L
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { start() }

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatInterval = 2000L
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                sendHeartbeat()
                heartbeatHandler.postDelayed(this, heartbeatInterval)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var lastPacketReceivedAt = 0L

    private fun setupLowLatencySocket(socket: DatagramSocket) {
        try {
            socket.sendBufferSize = 1024
            socket.receiveBufferSize = 1024

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                socket.trafficClass = 0x10
            }

            Log.d(TAG, "Applied low-latency socket configuration")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply low-latency socket config: ${e.message}")
        }
    }

    fun updateSettings(host: String, port: Int, autoReconnectEnabled: Boolean) {
        hostAddress = host.trim().ifEmpty { DEFAULT_HOST }
        portNumber = if (port > 0) port else DEFAULT_RECEIVER_PORT
        activePortNumber = portNumber
        autoReconnect = autoReconnectEnabled

        if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
            close()
            start()
        }
    }

    fun setPlayerRole(role: PlayerRole) {
        currentPlayerRole = role
        Log.d(TAG, "Player role set to: $role")

        if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
            val roleId = if (role == PlayerRole.PLAYER1) "player1" else "player2"
            send("REGISTER:$roleId")
        }
    }

    fun getPlayerRole(): PlayerRole {
        return currentPlayerRole
    }

    fun start() {
        if (_connectionStatus.value == ConnectionStatus.CONNECTING) return

        _connectionStatus.value = ConnectionStatus.CONNECTING
        scope.launch {
            try {
                reconnectHandler.removeCallbacks(reconnectRunnable)
                heartbeatHandler.removeCallbacks(heartbeatRunnable)

                Log.d(TAG, "Connecting to $hostAddress:$portNumber via UDP")

                socket?.close()
                val newSocket = DatagramSocket()
                setupLowLatencySocket(newSocket)
                socket = newSocket

                val endpoint = resolveReceiverEndpoint(newSocket)
                    ?: throw IOException("No Simple Controller receiver found")

                serverAddress = endpoint.address
                activePortNumber = endpoint.port
                lastPacketReceivedAt = System.currentTimeMillis()

                UdpClient.updateSettings(endpoint.address.hostAddress ?: hostAddress, endpoint.port)

                _connectionStatus.value = ConnectionStatus.CONNECTED
                startListening()
                reconnectAttempts = 0
                val mode = if (endpoint.discovered) "discovered" else "saved"
                Log.d(TAG, "Connected to $mode receiver at ${endpoint.address.hostAddress}:${endpoint.port}")

                heartbeatHandler.post(heartbeatRunnable)

                val roleId = if (currentPlayerRole == PlayerRole.PLAYER1) "player1" else "player2"
                send("REGISTER:$roleId")
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                socket?.close()
                socket = null
                _connectionStatus.value = ConnectionStatus.ERROR
                _lastErrorMessage.value =
                    "Failed to connect. Start simple_controller_receiver, enable USB tethering or Wi-Fi, and check Windows Firewall."

                if (autoReconnect && reconnectAttempts < maxReconnectAttempts) {
                    scheduleReconnect()
                }
            }
        }
    }

    private suspend fun resolveReceiverEndpoint(sock: DatagramSocket): ReceiverEndpoint? =
        withContext(Dispatchers.IO) {
            directEndpointCandidates().firstOrNull { tryConnectEndpoint(sock, it) }
                ?: discoverReceiver(sock)?.takeIf { tryConnectEndpoint(sock, it) }
        }

    private fun directEndpointCandidates(): List<ReceiverEndpoint> {
        val endpoints = mutableListOf<ReceiverEndpoint>()
        val ports = listOf(portNumber, DEFAULT_RECEIVER_PORT, LEGACY_RECEIVER_PORT).distinct()

        try {
            val address = InetAddress.getByName(hostAddress)
            ports.forEach { port ->
                endpoints += ReceiverEndpoint(address, port, discovered = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Saved host is not usable for direct connect: $hostAddress", e)
        }

        return endpoints
    }

    private fun tryConnectEndpoint(sock: DatagramSocket, endpoint: ReceiverEndpoint): Boolean {
        val roleId = if (currentPlayerRole == PlayerRole.PLAYER1) "player1" else "player2"
        val message = "CONNECT:$roleId"

        return try {
            sendPacket(sock, message, endpoint.address, endpoint.port)
            receiveMatching(sock, CONNECT_TIMEOUT_MS) { response, packet ->
                packet.address == endpoint.address &&
                    (response == "CONNECTED:$roleId" || response.startsWith("CONNECTED:"))
            } != null
        } catch (e: Exception) {
            Log.d(TAG, "Receiver did not answer at ${endpoint.address.hostAddress}:${endpoint.port}: ${e.message}")
            false
        }
    }

    private fun discoverReceiver(sock: DatagramSocket): ReceiverEndpoint? {
        return try {
            sock.broadcast = true
            val payload = DISCOVERY_REQUEST.toByteArray()
            val ports = listOf(portNumber, DEFAULT_RECEIVER_PORT, LEGACY_RECEIVER_PORT).distinct()
            val addresses = discoveryBroadcastAddresses()

            for (address in addresses) {
                for (port in ports) {
                    try {
                        sock.send(DatagramPacket(payload, payload.size, address, port))
                    } catch (e: Exception) {
                        Log.d(TAG, "Discovery send failed to ${address.hostAddress}:$port: ${e.message}")
                    }
                }
            }

            receiveDiscoveryResponse(sock)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver discovery failed: ${e.message}", e)
            null
        }
    }

    private fun receiveDiscoveryResponse(sock: DatagramSocket): ReceiverEndpoint? {
        val result = receiveMatching(sock, DISCOVERY_TIMEOUT_MS) { response, _ ->
            response.startsWith(DISCOVERY_RESPONSE_PREFIX)
        } ?: return null

        val response = result.first
        val packet = result.second
        val discoveredPort = response.split(":").getOrNull(1)?.toIntOrNull() ?: packet.port

        Log.d(TAG, "Discovered receiver at ${packet.address.hostAddress}:$discoveredPort")
        return ReceiverEndpoint(packet.address, discoveredPort, discovered = true)
    }

    private fun discoveryBroadcastAddresses(): List<InetAddress> {
        val addresses = linkedSetOf(
            "255.255.255.255",
            "192.168.42.255",
            "192.168.43.255"
        )

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast is Inet4Address) {
                        addresses += broadcast.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not enumerate broadcast addresses: ${e.message}")
        }

        return addresses.mapNotNull { host ->
            runCatching { InetAddress.getByName(host) }.getOrNull()
        }
    }

    private fun receiveMatching(
        sock: DatagramSocket,
        timeoutMs: Int,
        isMatch: (String, DatagramPacket) -> Boolean
    ): Pair<String, DatagramPacket>? {
        val originalTimeout = sock.soTimeout
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(1024)

        try {
            while (true) {
                val remaining = (deadline - System.currentTimeMillis()).toInt()
                if (remaining <= 0) return null

                sock.soTimeout = remaining
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    sock.receive(packet)
                } catch (e: SocketTimeoutException) {
                    return null
                }

                val response = String(packet.data, packet.offset, packet.length).trim()
                if (isMatch(response, packet)) {
                    return response to packet
                }
            }
        } finally {
            sock.soTimeout = originalTimeout
        }
    }

    private fun sendPacket(sock: DatagramSocket, message: String, address: InetAddress, port: Int) {
        val buffer = message.toByteArray()
        sock.send(DatagramPacket(buffer, buffer.size, address, port))
    }

    private fun startListening() {
        scope.launch {
            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)

            while (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                try {
                    socket?.soTimeout = 5000
                    packet.length = buffer.size
                    socket?.receive(packet)
                    val received = String(packet.data, 0, packet.length)
                    lastPacketReceivedAt = System.currentTimeMillis()
                    handleServerMessage(received)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: SocketException) {
                    if (_connectionStatus.value == ConnectionStatus.DISCONNECTED) {
                        break
                    }
                    Log.e(TAG, "Socket error while listening", e)
                    handleDisconnect()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error receiving data", e)
                    if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                        _lastErrorMessage.value = "Connection error: ${e.message}"
                    }
                }
            }
        }
    }

    private fun handleServerMessage(message: String) {
        when {
            message.startsWith("PONG") -> Log.d(TAG, "Received heartbeat response")
            message.startsWith("REGISTERED:") -> Log.d(TAG, "Registered with receiver")
        }
    }

    private fun sendHeartbeat() {
        try {
            sendRaw("PING")

            val elapsed = System.currentTimeMillis() - lastPacketReceivedAt
            if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                Log.w(TAG, "Receiver heartbeat timed out after ${elapsed}ms")
                _lastErrorMessage.value = "Receiver connection lost"
                handleDisconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send heartbeat", e)
            handleDisconnect()
        }
    }

    fun close() {
        reconnectHandler.removeCallbacks(reconnectRunnable)
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        reconnectAttempts = 0
        closeConnection()
    }

    private fun closeConnection() {
        socket?.close()
        socket = null
        serverAddress = null
        activePortNumber = portNumber
        UdpClient.close()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        scope.coroutineContext.cancelChildren()
    }

    private fun handleDisconnect() {
        closeConnection()

        if (autoReconnect && reconnectAttempts < maxReconnectAttempts) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectAttempts++
        Log.d(TAG, "Scheduling reconnect attempt $reconnectAttempts/$maxReconnectAttempts")
        reconnectHandler.postDelayed(reconnectRunnable, reconnectDelayMs)
    }

    private fun sendRaw(message: String) {
        socket?.let { openSocket ->
            serverAddress?.let { address ->
                try {
                    sendPacket(openSocket, message, address, activePortNumber)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending data: ${e.message}")
                    throw e
                }
            }
        }
    }

    fun send(message: String) {
        Log.d(TAG, "Sending: $message")
        if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
            scope.launch {
                try {
                    val prefixedMessage = if (message.startsWith("REGISTER:") ||
                        message.startsWith("player1:") ||
                        message.startsWith("player2:")
                    ) {
                        message
                    } else {
                        val playerPrefix = if (currentPlayerRole == PlayerRole.PLAYER1) "player1:" else "player2:"
                        "$playerPrefix$message"
                    }

                    serverAddress?.let { address ->
                        val buffer = prefixedMessage.toByteArray()
                        val packet = DatagramPacket(buffer, buffer.size, address, activePortNumber)
                        socket?.send(packet)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send message: ${e.message}")
                    _lastErrorMessage.value = "Failed to send data: ${e.message}"

                    if (e is SocketException) {
                        handleDisconnect()
                    }
                }
            }
        } else {
            Log.w(TAG, "Cannot send when not connected")
        }
    }
}
