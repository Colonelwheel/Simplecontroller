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
import com.example.simplecontroller.CbProtocol

/**
 * Provides UDP communication for lower latency position updates.
 * Designed to complement NetworkClient for time-sensitive data.
 */
object UdpClient {

    // ===== ConsoleBridge (CBv0) toggle =====
    @Volatile private var useCbv0: Boolean = false
    private const val CB_PORT = 9010

    /** Enable/disable CBv0 binary sending at runtime. */
    fun setConsoleBridgeEnabled(enabled: Boolean) {
        useCbv0 = enabled
    }

    /** Decide which UDP port to target based on protocol. */
    private fun targetPort(): Int = if (useCbv0) CB_PORT else serverPort

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

    fun sendScroll(deltaY: Float) {
        if (!isInitialized || socket == null || serverAddress == null) {
            NetworkClient.send("SCROLL:${"%.2f".format(deltaY)}")
            return
        }

        scope.launch {
            val prefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"
            val msgStr = "${prefix}SCROLL:${"%.2f".format(deltaY)}"
            socket?.send(DatagramPacket(msgStr.toByteArray(), msgStr.length, serverAddress, serverPort))
        }
    }

    /**
     * Send a command via UDP
     */
    fun sendCommand(command: String) {
        if (!isInitialized || socket == null || serverAddress == null) {
            // Fallback to TCP legacy path if UDP isn't ready
            NetworkClient.send(command)
            return
        }

        scope.launch {
            try {
                val playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"

                if (useCbv0) {
                    // Try CBv0 first (frame goes to :9010; player prefix added by the gateway)
                    val frame = CbProtocol.encode(command)
                    if (frame != null) {
                        val packet = DatagramPacket(frame, frame.size, serverAddress, targetPort())
                        socket?.send(packet)
                        return@launch
                    }
                    // If this command isn't supported by CBv0 yet, fall through to legacy text.
                }

                // Legacy text path (direct to :9001) — includes player prefix
                val message = "${playerPrefix}${command}"
                val buffer = message.toByteArray()
                val packet = DatagramPacket(buffer, buffer.size, serverAddress, serverPort)
                socket?.send(packet)
            } catch (e: Exception) {
                if (Math.random() < 0.01) { // avoid log spam
                    Log.e(TAG, "Error sending UDP command: ${e.message}")
                }
                // Try TCP as last resort
                NetworkClient.send(command)
            }
        }
    }

    // -------------------------------------------------------------------
    //   TOUCHPAD (NEW ‑ relative Δ packets)
    // -------------------------------------------------------------------


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
     * Primary method used by the touchpad: send *deltas* (dx, dy).
     * When CBv0 is enabled, we encode to TYPE_MOUSE_DELTA and send to :9010.
     * Otherwise, we send the legacy text "playerX:DELTA:x,y" to :9001.
     */
    fun sendTouchpadDelta(dx: Float, dy: Float) {
        // Build the common player prefix for legacy path
        val playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"

        // If UDP not ready, fall back to TCP (legacy text)
        if (!isInitialized || socket == null || serverAddress == null) {
            NetworkClient.send("${playerPrefix}DELTA:${"%.2f".format(dx)},${"%.2f".format(dy)}")
            return
        }

        scope.launch {
            try {
                if (useCbv0) {
                    // Encode as CBv0 (TYPE_MOUSE_DELTA) and send to gateway port (:9010)
                    val frame = CbProtocol.encode("DELTA:${"%.3f".format(dx)},${"%.3f".format(dy)}")
                    if (frame != null) {
                        val packet = DatagramPacket(frame, frame.size, serverAddress, targetPort())
                        socket?.send(packet)
                        return@launch
                    }
                    // If for some reason encoding isn't supported, fall through to legacy
                }

                // Legacy text path (direct to :9001) — includes player prefix
                val msgStr = "${playerPrefix}DELTA:${"%.3f".format(dx)},${"%.3f".format(dy)}"
                val msg = msgStr.toByteArray()
                socket?.send(DatagramPacket(msg, msg.size, serverAddress, serverPort))
            } catch (e: Exception) {
                if (Math.random() < 0.01) Log.e(TAG, "UDP delta error: ${e.message}")
                // Last resort: TCP legacy
                NetworkClient.send("${playerPrefix}DELTA:${"%.2f".format(dx)},${"%.2f".format(dy)}")
            }
        }
    }

    // Back-compat shim (some callers still use the old name)
    fun sendTouchpadPosition(x: Float, y: Float) = sendTouchpadDelta(x, y)

    private fun normalizeStickName(raw: String): String {
        val s = raw.trim()
        val u = s.uppercase()

        // Common aliases → canonical
        if (u == "STICK" || u == "L" || u == "LS" || u == "LEFT")  return "STICK_L"
        if (u == "R" || u == "RS" || u == "RIGHT")                 return "STICK_R"

        // Already valid prefixes → keep
        if (u.startsWith("STICK_") || u == "STICK_L" || u == "STICK_R" || u.startsWith("DIR_")) return u

        // If someone passed a view id like "stick_169..." (or any non‑canonical token), fall back safely
        if (u.startsWith("STICK") || u.startsWith("STK") || u.startsWith("STK_") || u.startsWith("STICK-") || u.startsWith("STICK_")) {
            return "STICK_L"
        }
        if (u.startsWith("STICK") || u.startsWith("STICK_")) return "STICK_L"
        if (u.startsWith("STICK")) return "STICK_L"
        if (u.startsWith("STICK", ignoreCase = true)) return "STICK_L"

        // Default: prefix unknown tokens as a generic stick (left by default)
        return if (u == s) "STICK_L" else "STICK_L"
    }


    /**
     * Send a stick position update
     */
    fun sendStickPosition(stickNameRaw: String, x: Float, y: Float) {
        val canon = normalizeStickName(stickNameRaw) // e.g., "STICK_L" or "STICK_R"

        // If UDP not ready, fall back to TCP (legacy text, canonical name)
        if (!isInitialized || socket == null || serverAddress == null) {
            NetworkClient.send("$canon:${"%.2f".format(x)},${"%.2f".format(y)}")
            return
        }

        scope.launch {
            try {
                if (useCbv0) {
                    // Map canon → CBv0-friendly side ("LS"/"RS")
                    val isRight = canon.contains("_R")
                    val side = if (isRight) "RS" else "LS"

                    // Build a CBv0-encodable legacy string, then encode → frame
                    val cbCompat = "$side:${"%.2f".format(x)},${"%.2f".format(y)}"
                    val frame = CbProtocol.encode(cbCompat)
                    if (frame != null) {
                        val pkt = DatagramPacket(frame, frame.size, serverAddress, targetPort())
                        socket?.send(pkt)
                        return@launch
                    }
                    // If for any reason encoding returns null, fall through to legacy path below.
                }

                // Legacy text path (direct to :9001) — includes player prefix and canonical stick name
                val playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"
                val message = "${playerPrefix}${canon}:${"%.2f".format(x)},${"%.2f".format(y)}"
                val buf = message.toByteArray()
                socket?.send(DatagramPacket(buf, buf.size, serverAddress, serverPort))
            } catch (e: Exception) {
                if (Math.random() < 0.01) {
                    Log.e(TAG, "Error sending UDP stick: ${e.message}")
                }
                // Last resort TCP (legacy text)
                NetworkClient.send("$canon:${"%.2f".format(x)},${"%.2f".format(y)}")
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