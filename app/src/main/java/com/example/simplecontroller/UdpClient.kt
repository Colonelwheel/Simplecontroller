package com.example.simplecontroller.net

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    // UI thread handler for key state management
    private val mainHandler = Handler(Looper.getMainLooper())

    // Player role from NetworkClient
    private val playerRole get() = NetworkClient.getPlayerRole()

    // Connection status
    private var isInitialized = false

    // Key state tracking for reliable directional commands
    private val activeKeys = ConcurrentHashMap<String, Boolean>()
    private var keyResendRunnable: Runnable? = null
    private val keyResendInterval = 100L // milliseconds
    private var isKeySyncActive = false

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

            Log.d(TAG, "Applied low-latency socket configuration")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply low-latency socket config: ${e.message}")
        }
    }

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

                // Apply low-latency optimizations
                socket?.let { setupLowLatencySocket(it) }

                socket?.soTimeout = 1000  // 1 second timeout

                isInitialized = true
                Log.d(TAG, "UDP client initialized successfully")

                // Start key state sync when initialized
                startKeyStateSync()
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

    // -------------------------------------------------------------------
    //   TOUCHPAD (NEW ‑ relative Δ packets)
    // -------------------------------------------------------------------
    /**
     * Primary method used by the revamped touchpad: send *deltas* instead of
     * absolute positions to eliminate drift & bounding‑box issues.
     */
    fun sendTouchpadDelta(dx: Float, dy: Float) {
        // ----- build the common player prefix -----
        val playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1)
            "player1:" else "player2:"

        // ----- if UDP not ready, fall back to TCP -----
        if (!isInitialized || socket == null || serverAddress == null) {
            NetworkClient.send("${playerPrefix}DELTA:${"%.2f".format(dx)},${"%.2f".format(dy)}")
            return
        }

        // ----- normal UDP path -----
        scope.launch {
            try {
                val msgStr = "${playerPrefix}DELTA:${"%.3f".format(dx)},${"%.3f".format(dy)}"
                val msg = msgStr.toByteArray()
                socket?.send(DatagramPacket(msg, msg.size, serverAddress, serverPort))
            } catch (e: Exception) {
                if (Math.random() < 0.01) Log.e(TAG, "UDP delta error: ${e.message}")
            }
        }
    }

    /**
     * Send a directional key command with reliable delivery
     * Use this for WASD or similar directional controls
     */
    fun sendKeyCommand(key: String, isPressed: Boolean) {
        if (isPressed) {
            // Add to active keys
            activeKeys[key] = true

            // Send immediately (normal way)
            val command = "KEY_DOWN:$key"
            sendCommand(command)

            // Ensure key state sync is running
            ensureKeyStateSyncActive()
        } else {
            // Remove from active keys
            activeKeys.remove(key)

            // Send key up command
            val command = "KEY_UP:$key"
            sendCommand(command)
        }
    }

    /**
     * Ensures the key state sync mechanism is active if needed
     */
    private fun ensureKeyStateSyncActive() {
        if (!isKeySyncActive && activeKeys.isNotEmpty()) {
            startKeyStateSync()
        }
    }

    /**
     * Start periodic sync of active keys to ensure they stay pressed
     */
    private fun startKeyStateSync() {
        // Clear any existing runnable
        stopKeyStateSync()

        // Mark as active
        isKeySyncActive = true

        // Create new key sync runnable
        keyResendRunnable = object : Runnable {
            override fun run() {
                // Only continue if we have active keys and are initialized
                if (activeKeys.isNotEmpty() && isInitialized) {
                    // Resend all active keys
                    for (key in activeKeys.keys) {
                        val syncCommand = "KEY_SYNC:$key"
                        sendCommand(syncCommand)
                    }

                    // Schedule next sync
                    mainHandler.postDelayed(this, keyResendInterval)
                } else {
                    // No active keys, can stop syncing
                    isKeySyncActive = false
                }
            }
        }

        // Start the sync cycle
        keyResendRunnable?.let { mainHandler.post(it) }

        Log.d(TAG, "Key state sync started")
    }

    /**
     * Stop key state sync mechanism
     */
    private fun stopKeyStateSync() {
        keyResendRunnable?.let { mainHandler.removeCallbacks(it) }
        keyResendRunnable = null
        isKeySyncActive = false
    }

    /**
     * Send position data via UDP for lowest latency
     */
    fun sendPosition(x: Float, y: Float) {
        if (!isInitialized || socket == null || serverAddress == null) {
            // If not initialized, try to use NetworkClient directly
            NetworkClient.send("POS:${"%.2f".format(x)},${"%.2f".format(y)}")
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
        if (!isInitialized || socket == null || serverAddress == null) {
            // Fall back to TCP for touchpad
            NetworkClient.send("TOUCHPAD:${"%.2f".format(x)},${"%.2f".format(y)}")
            return
        }

        scope.launch {
            try {
                // Create message with player prefix with CORRECT TOUCHPAD format
                val playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"
                // Use just "TOUCHPAD" instead of "STICK_TOUCHPAD"
                val message = "${playerPrefix}TOUCHPAD:${"%.2f".format(x)},${"%.2f".format(y)}"
                val buffer = message.toByteArray()

                // Create and send packet
                val packet = DatagramPacket(buffer, buffer.size, serverAddress, serverPort)
                socket?.send(packet)
            } catch (e: Exception) {
                // Fall back to TCP on error
                NetworkClient.send("TOUCHPAD:${"%.2f".format(x)},${"%.2f".format(y)}")
            }
        }
    }

    /**
     * Send a stick position update
     */
    fun sendStickPosition(stickName: String, x: Float, y: Float) {
        if (!isInitialized || socket == null || serverAddress == null) {
            // If not initialized, try to use NetworkClient directly
            NetworkClient.send("${stickName}:${"%.2f".format(x)},${"%.2f".format(y)}")
            return
        }

        scope.launch {
            try {
                // Create message with player prefix and proper identifier
                val playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"

                // Check if stickName already includes STICK_ prefix
                val stickPrefix = if (stickName.startsWith("STICK_") || stickName.startsWith("DIR_")) "" else "STICK_"
                val message = "${playerPrefix}${stickPrefix}${stickName}:${"%.1f".format(x)},${"%.1f".format(y)}"

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
        // Stop key state sync
        stopKeyStateSync()

        // Clear active keys
        activeKeys.clear()

        // Close socket
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