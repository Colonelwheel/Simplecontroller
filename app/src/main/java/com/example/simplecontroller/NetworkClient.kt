package com.example.simplecontroller.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketException

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

    private val channel = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

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

                Log.d("NetworkClient", "Connecting to $hostAddress:$portNumber")
                socket = Socket(hostAddress, portNumber).also {
                    writer = BufferedWriter(OutputStreamWriter(it.getOutputStream()))
                }

                _connectionStatus.value = ConnectionStatus.CONNECTED
                reconnectAttempts = 0
                Log.d("NetworkClient", "Connected successfully")

                // Register player role with server
                val roleId = if (currentPlayerRole == PlayerRole.PLAYER1) "player1" else "player2"
                send("REGISTER:$roleId")

                for (msg in channel) {
                    try {
                        writer?.apply {
                            write(msg)
                            newLine()
                            flush()
                        }
                    } catch (e: SocketException) {
                        Log.e("NetworkClient", "Socket error while sending", e)
                        _lastErrorMessage.value = "Error sending data: ${e.message}"
                        handleDisconnect()
                        break
                    } catch (e: Exception) {
                        Log.e("NetworkClient", "Error sending data", e)
                        _lastErrorMessage.value = "Error sending data: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                Log.e("NetworkClient", "Connection error", e)
                _connectionStatus.value = ConnectionStatus.ERROR
                _lastErrorMessage.value = "Failed to connect: ${e.message}"

                if (autoReconnect && reconnectAttempts < maxReconnectAttempts) {
                    scheduleReconnect()
                }
            } finally {
                if (_connectionStatus.value != ConnectionStatus.CONNECTING) {
                    closeConnection()
                }
            }
        }
    }


    /** Close the connection */
    fun close() {
        reconnectHandler.removeCallbacks(reconnectRunnable)
        reconnectAttempts = 0
        closeConnection()
    }

    /** Internal method to close socket and writer */
    private fun closeConnection() {
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null
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

    /**
     * Send a command with the current player prefix.
     * Non‑blocking, thread‑safe.
     */
    fun send(message: String) {
        if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
            // Prefix message with player ID if it doesn't already have one
            val prefixedMessage = if (message.startsWith("REGISTER:") ||
                message.startsWith("player1:") ||
                message.startsWith("player2:")) {
                message
            } else {
                val playerPrefix = if (currentPlayerRole == PlayerRole.PLAYER1) "player1:" else "player2:"
                "$playerPrefix$message"
            }

            channel.trySend(prefixedMessage)
        } else {
            Log.w("NetworkClient", "Cannot send when not connected")
        }
    }
}