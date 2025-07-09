package com.example.simplecontroller.net

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException


object NetworkClient {
    // Connection status enum
    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    // Define player roles
    enum class PlayerRole {
        PLAYER1,
        PLAYER2
    }

    // Current player selection
    private var currentPlayerRole = PlayerRole.PLAYER1

    // Expose connection status as state flow for UI updates
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus = _connectionStatus.asStateFlow()

    // Add last error message
    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage = _lastErrorMessage.asStateFlow()

    // Connection settings with default values
    private var hostAddress = "10.0.2.2"
    private var portNumber = 9001

    // Auto-reconnect settings
    private var autoReconnect = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectDelayMs = 3000L
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { start() }

    // Heartbeat for connection verification
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatInterval = 2000L // 2 seconds
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

    /**
     * Configure socket for minimal latency
     */
    private fun setupLowLatencySocket(socket: DatagramSocket) {
        try {
            // Set minimal buffer sizes
            socket.sendBufferSize = 1024
            socket.receiveBufferSize = 1024

            // Set traffic class for low latency if on newer Android versions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                socket.trafficClass = 0x10  // IPTOS_LOWDELAY
            }

            Log.d("NetworkClient", "Applied low-latency socket configuration")
        } catch (e: Exception) {
            Log.e("NetworkClient", "Failed to apply low-latency socket config: ${e.message}")
        }
    }

    /** Update connection settings */
    fun updateSettings(host: String, port: Int, autoReconnectEnabled: Boolean) {
        this.hostAddress = host
        this.portNumber = port
        this.autoReconnect = autoReconnectEnabled

        // If already connected, disconnect and reconnect with new settings
        if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
            close()
            start()
        }
    }

    /** Set the player role for this client */
    fun setPlayerRole(role: PlayerRole) {
        currentPlayerRole = role
        Log.d("NetworkClient", "Player role set to: $role")

        // If already connected, send player registration
        if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
            val roleId = if (role == PlayerRole.PLAYER1) "player1" else "player2"
            send("REGISTER:$roleId")
        }
    }

    /** Get the current player role */
    fun getPlayerRole(): PlayerRole {
        return currentPlayerRole
    }

    /** Start connection to server */
    fun start() {
        if (_connectionStatus.value == ConnectionStatus.CONNECTING) return

        _connectionStatus.value = ConnectionStatus.CONNECTING
        scope.launch {
            try {
                // Cancel any pending reconnect attempts
                reconnectHandler.removeCallbacks(reconnectRunnable)

                Log.d("NetworkClient", "Connecting to $hostAddress:$portNumber via UDP")

                // Close existing socket if any
                socket?.close()

                // Create a new UDP socket
                socket = DatagramSocket()

                // Apply low-latency optimizations
                socket?.let { setupLowLatencySocket(it) }

                serverAddress = InetAddress.getByName(hostAddress)

                // Send an initial connection message to establish communication
                val connectMessage = "CONNECT:${if (currentPlayerRole == PlayerRole.PLAYER1) "player1" else "player2"}"
                sendRaw(connectMessage)

                // Start listening for responses in a separate coroutine
                startListening()

                _connectionStatus.value = ConnectionStatus.CONNECTED
                reconnectAttempts = 0
                Log.d("NetworkClient", "Connected successfully via UDP")

                // Start heartbeat
                heartbeatHandler.post(heartbeatRunnable)

                // Register player role with server
                val roleId = if (currentPlayerRole == PlayerRole.PLAYER1) "player1" else "player2"
                send("REGISTER:$roleId")
            } catch (e: Exception) {
                Log.e("NetworkClient", "Connection error", e)
                _connectionStatus.value = ConnectionStatus.ERROR
                _lastErrorMessage.value = "Failed to connect: ${e.message}"

                if (autoReconnect && reconnectAttempts < maxReconnectAttempts) {
                    scheduleReconnect()
                }
            }
        }
    }

    /** Start listening for server responses */
    private fun startListening() {
        scope.launch {
            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)

            while (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                try {
                    socket?.soTimeout = 5000               // 5-second timeout
                    socket?.receive(packet)                // blocks
                    val received = String(packet.data, 0, packet.length)

                    // Handle received message (e.g., PONG responses, etc.)
                    handleServerMessage(received)

                } catch (e: SocketTimeoutException) {
                    // No packet within 5 s → harmless for an idle controller
                    continue

                } catch (e: SocketException) {
                    if (_connectionStatus.value == ConnectionStatus.DISCONNECTED) {
                        // Socket was intentionally closed; exit the loop
                        break
                    }
                    Log.e("NetworkClient", "Socket error while listening", e)
                    handleDisconnect()
                    break

                } catch (e: Exception) {
                    Log.e("NetworkClient", "Error receiving data", e)
                    if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                        _lastErrorMessage.value = "Connection error: ${e.message}"
                    }
                }
            }
        }
    }


    /** Handle messages from the server */
    private fun handleServerMessage(message: String) {
        when {
            message.startsWith("PONG") -> {
                // Heartbeat response, connection is still alive
                Log.d("NetworkClient", "Received heartbeat response")
            }
            // Add other message types as needed
        }
    }

    /** Send a heartbeat to verify connection */
    private fun sendHeartbeat() {
        try {
            sendRaw("PING")
        } catch (e: Exception) {
            Log.e("NetworkClient", "Failed to send heartbeat", e)
            handleDisconnect()
        }
    }

    /** Close the connection */
    fun close() {
        reconnectHandler.removeCallbacks(reconnectRunnable)
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        reconnectAttempts = 0
        closeConnection()
    }

    /** Internal method to close socket */
    private fun closeConnection() {
        socket?.close()
        socket = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        scope.coroutineContext.cancelChildren()
    }

    /** Handle disconnection */
    private fun handleDisconnect() {
        closeConnection()

        if (autoReconnect && reconnectAttempts < maxReconnectAttempts) {
            scheduleReconnect()
        }
    }

    /** Schedule a reconnection attempt */
    private fun scheduleReconnect() {
        reconnectAttempts++
        Log.d("NetworkClient", "Scheduling reconnect attempt $reconnectAttempts/$maxReconnectAttempts")
        reconnectHandler.postDelayed(reconnectRunnable, reconnectDelayMs)
    }

    /** Send raw data directly without player prefix */
    private fun sendRaw(message: String) {
        socket?.let { socket ->
            serverAddress?.let { address ->
                try {
                    val buffer = message.toByteArray()
                    val packet = DatagramPacket(buffer, buffer.size, address, portNumber)
                    socket.send(packet)
                } catch (e: Exception) {
                    Log.e("NetworkClient", "Error sending data: ${e.message}")
                    throw e
                }
            }
        }
    }

    /**
     * Send a command with the current player prefix.
     * Non‑blocking, thread‑safe.
     */
    fun send(message: String) {
        android.util.Log.d("NetworkClient", "Sending: $message")
        if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
            scope.launch {
                try {
                    // Prefix message with player ID if it doesn't already have one
                    val prefixedMessage = if (message.startsWith("REGISTER:") ||
                        message.startsWith("player1:") ||
                        message.startsWith("player2:")) {
                        message
                    } else {
                        val playerPrefix = if (currentPlayerRole == PlayerRole.PLAYER1) "player1:" else "player2:"
                        "$playerPrefix$message"
                    }

                    // Send the message via UDP
                    serverAddress?.let { address ->
                        val buffer = prefixedMessage.toByteArray()
                        val packet = DatagramPacket(buffer, buffer.size, address, portNumber)
                        socket?.send(packet)
                    }
                } catch (e: Exception) {
                    Log.e("NetworkClient", "Failed to send message: ${e.message}")
                    _lastErrorMessage.value = "Failed to send data: ${e.message}"

                    // Check if this is a connection error
                    if (e is SocketException) {
                        handleDisconnect()
                    }
                }
            }
        } else {
            Log.w("NetworkClient", "Cannot send when not connected")
        }
    }
}