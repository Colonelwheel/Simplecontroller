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
    
    // Performance optimization settings
    private const val POSITION_PRECISION = 1 // Decimal places for position values
    private const val MESSAGE_THROTTLE_MS = 8L // Minimum time between position messages
    
    // Socket and address information
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort = DEFAULT_PORT
    
    // Throttle tracking
    private var lastSendTimeMs = 0L
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Player role from NetworkClient
    private val playerRole get() = NetworkClient.getPlayerRole()
    
    // Connection status
    private var isInitialized = false
    
    // Pre-computed player prefix to avoid string concatenation
    private var playerPrefix = "player1:"

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
                
                // Set buffer sizes for better performance
                socket?.receiveBufferSize = 8192
                socket?.sendBufferSize = 8192
                
                // Pre-compute player prefix
                playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"

                isInitialized = true
                Log.d(TAG, "UDP client initialized successfully with optimized settings")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize UDP client: ${e.message}", e)
                isInitialized = false
            }
        }
    }


    /**
     * Send a command via UDP
     */
    fun sendCommand(command: String) {
        if (!isInitialized || socket == null || serverAddress == null) {
            // If not initialized, fall back to NetworkClient
            NetworkClient.send(command)
            return
        }

        scope.launch {
            try {
                // Create message with player prefix for server routing
                val playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"
                val message = "${playerPrefix}${command}"
                val buffer = message.toByteArray()

                // Create and send packet
                val packet = DatagramPacket(buffer, buffer.size, serverAddress, serverPort)
                socket?.send(packet)
            } catch (e: Exception) {
                // Only log occasionally to avoid overwhelming logs
                if (Math.random() < 0.01) {
                    Log.e(TAG, "Error sending UDP command: ${e.message}")
                }
                // Fall back to TCP if UDP fails
                NetworkClient.send(command)
            }
        }
    }
    /**
     * Send position data via UDP for lowest latency
     * Optimized for reduced packet frequency and size
     */
    fun sendPosition(x: Float, y: Float) {
        if (!isInitialized || socket == null || serverAddress == null) {
            // If not initialized, try to use NetworkClient directly
            NetworkClient.send("POS:${"%.${POSITION_PRECISION}f".format(x)},${"%.${POSITION_PRECISION}f".format(y)}")
            return
        }
        
        // Throttle update frequency
        val currentTimeMs = System.currentTimeMillis()
        if (currentTimeMs - lastSendTimeMs < MESSAGE_THROTTLE_MS) {
            return  // Skip this update to reduce network traffic
        }
        lastSendTimeMs = currentTimeMs

        scope.launch {
            try {
                // Create message with cached player prefix
                // Use compact format with lower precision
                val message = "${playerPrefix}POS:${"%.${POSITION_PRECISION}f".format(x)},${"%.${POSITION_PRECISION}f".format(y)}"
                val buffer = message.toByteArray()

                // Create and send packet
                val packet = DatagramPacket(buffer, buffer.size, serverAddress, serverPort)
                socket?.send(packet)
            } catch (e: Exception) {
                // Only log occasionally to avoid overwhelming logs
                if (Math.random() < 0.001) { // Further reduced logging
                    Log.e(TAG, "Error sending UDP position: ${e.message}")
                }
            }
        }
    }
    }

    /**
     * Send a touchpad position update
     * Optimized for reduced packet frequency and size
     */
    fun sendTouchpadPosition(x: Float, y: Float) {
        if (!isInitialized || socket == null || serverAddress == null) {
            // Fall back to TCP for touchpad
            NetworkClient.send("TOUCHPAD:${"%.${POSITION_PRECISION}f".format(x)},${"%.${POSITION_PRECISION}f".format(y)}")
            return
        }
        
        // Throttle update frequency
        val currentTimeMs = System.currentTimeMillis()
        if (currentTimeMs - lastSendTimeMs < MESSAGE_THROTTLE_MS) {
            return  // Skip this update to reduce network traffic
        }
        lastSendTimeMs = currentTimeMs

        scope.launch {
            try {
                // Use compact format with cached player prefix
                val message = "${playerPrefix}TOUCHPAD:${"%.${POSITION_PRECISION}f".format(x)},${"%.${POSITION_PRECISION}f".format(y)}"
                val buffer = message.toByteArray()

                // Create and send packet
                val packet = DatagramPacket(buffer, buffer.size, serverAddress, serverPort)
                socket?.send(packet)
            } catch (e: Exception) {
                // Fall back to TCP on error, but only occasionally to reduce overhead
                if (Math.random() < 0.1) { // Only fall back 10% of the time
                    NetworkClient.send("TOUCHPAD:${"%.${POSITION_PRECISION}f".format(x)},${"%.${POSITION_PRECISION}f".format(y)}")
                }
            }
        }
    }
    }

    /**
     * Send a stick position update
     * Optimized for reduced packet frequency and size
     */
    fun sendStickPosition(stickName: String, x: Float, y: Float) {
        if (!isInitialized || socket == null || serverAddress == null) {
            // If not initialized, try to use NetworkClient directly
            NetworkClient.send("STICK_${stickName}:${"%.${POSITION_PRECISION}f".format(x)},${"%.${POSITION_PRECISION}f".format(y)}")
            return
        }
        
        // Throttle update frequency
        val currentTimeMs = System.currentTimeMillis()
        if (currentTimeMs - lastSendTimeMs < MESSAGE_THROTTLE_MS) {
            return  // Skip this update to reduce network traffic
        }
        lastSendTimeMs = currentTimeMs

        scope.launch {
            try {
                // Create message with cached player prefix and stick identifier
                // Keep format compact
                val message = "${playerPrefix}STICK_${stickName}:${"%.${POSITION_PRECISION}f".format(x)},${"%.${POSITION_PRECISION}f".format(y)}"
                val buffer = message.toByteArray()

                // Create and send packet
                val packet = DatagramPacket(buffer, buffer.size, serverAddress, serverPort)
                socket?.send(packet)
            } catch (e: Exception) {
                // Only log occasionally to avoid overwhelming logs
                if (Math.random() < 0.001) { // Further reduced logging
                    Log.e(TAG, "Error sending UDP stick position: ${e.message}")
                }
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
        } else {
            // Update player prefix in case role changed
            playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"
        }
    }
}