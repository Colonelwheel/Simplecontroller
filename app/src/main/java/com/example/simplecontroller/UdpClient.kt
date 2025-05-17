package com.example.simplecontroller.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Connection status with flow for observability
    private val _connectionStatus = MutableStateFlow(false)
    val connectionStatus = _connectionStatus.asStateFlow()

    // Packet statistics for debugging
    private var packetsSent = 0
    private var packetsDropped = 0

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

                _connectionStatus.value = true
                Log.d(TAG, "UDP client initialized successfully")
                
                // Send test packet to verify connection
                sendTestPacket()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize UDP client: ${e.message}", e)
                _connectionStatus.value = false
            }
        }
    }

    /**
     * Send a test packet to verify UDP connectivity
     */
    private fun sendTestPacket() {
        scope.launch {
            try {
                val playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"
                val message = "${playerPrefix}UDP_TEST"
                val buffer = message.toByteArray()
                
                if (socket != null && serverAddress != null) {
                    val packet = DatagramPacket(buffer, buffer.size, serverAddress, serverPort)
                    socket?.send(packet)
                    Log.d(TAG, "Sent UDP test packet")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send UDP test packet: ${e.message}")
            }
        }
    }

    /**
     * Send position data via UDP for lowest latency
     */
    fun sendPosition(x: Float, y: Float) {
        if (!isInitialized()) {
            packetsDropped++
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
                packetsSent++
                
                // Log every 100th packet to avoid log spamming
                if (packetsSent % 100 == 0) {
                    Log.d(TAG, "UDP stats: sent=$packetsSent, dropped=$packetsDropped")
                }
            } catch (e: Exception) {
                // Only log occasionally to avoid overwhelming logs
                if (Math.random() < 0.01) {
                    Log.e(TAG, "Error sending UDP position: ${e.message}")
                }
                packetsDropped++
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
        if (!isInitialized()) {
            packetsDropped++
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
                packetsSent++
            } catch (e: Exception) {
                // Only log occasionally to avoid overwhelming logs
                if (Math.random() < 0.01) {
                    Log.e(TAG, "Error sending UDP stick position: ${e.message}")
                }
                packetsDropped++
            }
        }
    }

    /**
     * Send button press via UDP for lowest latency
     */
    fun sendButtonPress(buttonName: String) {
        if (!isInitialized()) {
            return
        }

        scope.launch {
            try {
                // Create message with player prefix
                val playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"
                val message = "${playerPrefix}${buttonName}"
                val buffer = message.toByteArray()

                // Create and send packet
                val packet = DatagramPacket(buffer, buffer.size, serverAddress, serverPort)
                socket?.send(packet)
                Log.d(TAG, "Sent UDP button press: $buttonName")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending UDP button press: ${e.message}")
            }
        }
    }

    /**
     * Close the UDP socket
     */
    fun close() {
        socket?.close()
        socket = null
        _connectionStatus.value = false
        Log.d(TAG, "UDP client closed")
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
    
    /**
     * Check if UDP client is initialized and ready
     */
    fun isInitialized(): Boolean {
        return socket != null && serverAddress != null && _connectionStatus.value
    }
    
    /**
     * Get usage statistics
     */
    fun getStats(): String {
        return "UDP: sent=$packetsSent, dropped=$packetsDropped"
    }
    
    /**
     * Reset packet statistics
     */
    fun resetStats() {
        packetsSent = 0
        packetsDropped = 0
    }
}