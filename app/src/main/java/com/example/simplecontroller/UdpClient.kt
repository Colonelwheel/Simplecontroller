package com.example.simplecontroller.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Provides UDP communication for lower latency position updates.
 * Designed to complement NetworkClient for time-sensitive data.
 */
object UdpClient {
    // Using constants for tagging and configuration
    private const val TAG = "UdpClient"
    private const val DEFAULT_PORT = 9001

    // Socket and address information
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort = DEFAULT_PORT

    // Coroutine scope for background operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Player role from NetworkClient
    private val playerRole get() = NetworkClient.getPlayerRole()

    // Connection status
    private var isInitialized = false

    /**
     * Initialize the UDP client with server details
     */
    fun initialize(host: String, port: Int = DEFAULT_PORT) {
        scope.launch {
            try {
                Log.d(TAG, "Initializing UDP client for $host:$port")

                // Close existing socket if open
                close()

                // Create new socket
                serverAddress = InetAddress.getByName(host)
                serverPort = port
                socket = DatagramSocket()
                socket?.soTimeout = 1000  // 1 second timeout

                isInitialized = true
                Log.d(TAG, "UDP client initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize UDP client: ${e.message}", e)
                isInitialized = false
            }
        }
    }

    /**
     * Send position data via UDP for lowest latency
     */
    fun sendPosition(x: Float, y: Float) {
        if (!isInitialized || socket == null || serverAddress == null) {
            return
        }

        scope.launch {
            try {
                // Create message with player prefix for server routing
                val playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"
                val message = "${playerPrefix}POS:${"%.1f".format(x)},${"%.1f".format(y)}"
                val buffer = message.toByteArray()

                // Create and send packet
                val packet = DatagramPacket(buffer, buffer.size, serverAddress, serverPort)
                socket?.send(packet)
            } catch (e: Exception) {
                // Only log occasionally to avoid overwhelming logs
                if (Math.random() < 0.01) {
                    Log.e(TAG, "Error sending UDP position: ${e.message}")
                }
            }
        }
    }

    /**
     * Send a touchpad position update
     */
    fun sendTouchpadPosition(x: Float, y: Float) {
        sendPosition(x, y)
    }

    /**
     * Send a stick position update
     */
    fun sendStickPosition(stickName: String, x: Float, y: Float) {
        if (!isInitialized || socket == null || serverAddress == null) {
            return
        }

        scope.launch {
            try {
                // Create message with player prefix and stick identifier
                val playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"
                val message = "${playerPrefix}STICK_${stickName}:${"%.1f".format(x)},${"%.1f".format(y)}"
                val buffer = message.toByteArray()

                // Create and send packet
                val packet = DatagramPacket(buffer, buffer.size, serverAddress, serverPort)
                socket?.send(packet)
            } catch (e: Exception) {
                // Only log occasionally to avoid overwhelming logs
                if (Math.random() < 0.01) {
                    Log.e(TAG, "Error sending UDP stick position: ${e.message}")
                }
            }
        }
    }

    /**
     * Close the UDP socket
     */
    fun close() {
        socket?.close()
        socket = null
        isInitialized = false
    }

    /**
     * Update connection settings (should be called when NetworkClient settings change)
     */
    fun updateSettings(host: String, port: Int = DEFAULT_PORT) {
        // Only reinitialize if settings actually changed
        if (host != serverAddress?.hostAddress || port != serverPort) {
            initialize(host, port)
        }
    }
}