package com.example.simplecontroller.net

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.simplecontroller.CbProtocol
import com.example.simplecontroller.model.isPageTransportCommand

/**
 * Provides UDP communication for lower latency position updates.
 * Designed to complement NetworkClient for time-sensitive data.
 */
object UdpClient {

    enum class ReleaseDelivery {
        ACK_PENDING,
        RECEIVER_UNAVAILABLE,
        CONSOLEBRIDGE_UNAVAILABLE
    }

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

    // Every output gets a process-unique session/sequence. RELEASE_ALL advances the
    // generation locally and gives the receiver a barrier against older UDP packets.
    private val outputSafetyEpoch = OutputSafetyEpoch()

    private val cameraFollowStateLock = Any()
    @Volatile private var cameraFollowEnabled = false
    private val manualRightStickOwners = mutableSetOf<Any>()

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

                // Explicit state is safe to resend and restores the receiver after reconnects.
                resendCameraFollowState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize UDP client: ${e.message}", e)
                isInitialized = false
            }
        }
    }

    fun sendScroll(deltaY: Float) {
        val ticket = outputSafetyEpoch.nextTicket()
        val command = "SCROLL:${"%.2f".format(deltaY)}"
        if (!isInitialized || socket == null || serverAddress == null) {
            if (outputSafetyEpoch.isCurrent(ticket)) {
                NetworkClient.send(if (useCbv0) command else outputSafetyEpoch.wrapOutput(ticket, command))
            }
            return
        }

        scope.launch {
            if (!outputSafetyEpoch.isCurrent(ticket)) return@launch
            val prefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"
            val wireCommand = if (useCbv0) command else outputSafetyEpoch.wrapOutput(ticket, command)
            val msgStr = "$prefix$wireCommand"
            socket?.send(DatagramPacket(msgStr.toByteArray(), msgStr.length, serverAddress, serverPort))
        }
    }

    /**
     * Send a command via UDP
     */
    fun sendCommand(command: String) {
        if (isPageTransportCommand(command)) {
            Log.w(TAG, "Blocked Android-local page command from receiver transport")
            return
        }
        val ticket = outputSafetyEpoch.nextTicket()
        if (!isInitialized || socket == null || serverAddress == null) {
            if (outputSafetyEpoch.isCurrent(ticket)) {
                NetworkClient.send(if (useCbv0) command else outputSafetyEpoch.wrapOutput(ticket, command))
            }
            return
        }

        scope.launch {
            try {
                if (!outputSafetyEpoch.isCurrent(ticket)) return@launch
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
                // Preserve unsupported legacy ConsoleBridge tokens exactly. The Python
                // receiver gets the ordered envelope used by RELEASE_ALL's stale barrier.
                val wireCommand = if (useCbv0) command else outputSafetyEpoch.wrapOutput(ticket, command)
                val message = "$playerPrefix$wireCommand"
                val buffer = message.toByteArray()
                val packet = DatagramPacket(buffer, buffer.size, serverAddress, serverPort)
                socket?.send(packet)
            } catch (e: Exception) {
                if (Math.random() < 0.01) { // avoid log spam
                    Log.e(TAG, "Error sending UDP command: ${e.message}")
                }
                // Try TCP as last resort
                if (outputSafetyEpoch.isCurrent(ticket)) {
                    val fallback = if (useCbv0) command else outputSafetyEpoch.wrapOutput(ticket, command)
                    NetworkClient.send(fallback)
                }
            }
        }
    }

    /** Invalidate queued sends and stop Android's keyboard keep-alive before local cleanup. */
    fun prepareReleaseAll() {
        outputSafetyEpoch.invalidateQueuedSends()
        stopKeyStateSync()
        activeKeys.clear()
    }

    /**
     * Send one idempotent release barrier with bounded retries through NetworkClient so
     * its listening socket can receive the acknowledgement.
     */
    fun sendReleaseAll(onAcknowledged: (Boolean) -> Unit): ReleaseDelivery {
        if (useCbv0) return ReleaseDelivery.CONSOLEBRIDGE_UNAVAILABLE
        if (NetworkClient.connectionStatus.value != NetworkClient.ConnectionStatus.CONNECTED) {
            return ReleaseDelivery.RECEIVER_UNAVAILABLE
        }

        val ticket = outputSafetyEpoch.nextTicket()
        val command = outputSafetyEpoch.releaseAllCommand(ticket)
        val acknowledgement = outputSafetyEpoch.releaseAllAcknowledgement(ticket)
        NetworkClient.requestReleaseAll(command, acknowledgement, onAcknowledged)
        return ReleaseDelivery.ACK_PENDING
    }

    fun toggleCameraFollow(): Boolean {
        val (enabled, effectiveEnabled) = synchronized(cameraFollowStateLock) {
            cameraFollowEnabled = !cameraFollowEnabled
            cameraFollowEnabled to (cameraFollowEnabled && manualRightStickOwners.isEmpty())
        }
        sendCommand("CAMERA_FOLLOW:${if (effectiveEnabled) 1 else 0}")
        return enabled
    }

    fun setCameraFollowEnabled(enabled: Boolean) {
        val effectiveEnabled = synchronized(cameraFollowStateLock) {
            cameraFollowEnabled = enabled
            cameraFollowEnabled && manualRightStickOwners.isEmpty()
        }
        sendCommand("CAMERA_FOLLOW:${if (effectiveEnabled) 1 else 0}")
    }

    fun resendCameraFollowState() {
        val enabled = synchronized(cameraFollowStateLock) {
            cameraFollowEnabled && manualRightStickOwners.isEmpty()
        }
        sendCommand("CAMERA_FOLLOW:${if (enabled) 1 else 0}")
    }

    /**
     * Temporarily suppress receiver-generated Camera Follow for the full lifetime of a real
     * manual Right Stick gesture, including neutral/dead-zone positions.
     */
    fun setManualRightStickActive(owner: Any, active: Boolean) {
        val effectiveChange = synchronized(cameraFollowStateLock) {
            val wasEnabled = cameraFollowEnabled && manualRightStickOwners.isEmpty()
            if (active) manualRightStickOwners.add(owner) else manualRightStickOwners.remove(owner)
            val isEnabled = cameraFollowEnabled && manualRightStickOwners.isEmpty()
            if (wasEnabled == isEnabled) null else isEnabled
        }
        effectiveChange?.let { sendCommand("CAMERA_FOLLOW:${if (it) 1 else 0}") }
    }

    // -------------------------------------------------------------------
    //   TOUCHPAD (NEW ‑ relative Δ packets)
    // -------------------------------------------------------------------


    /**
     * Send a directional key command with reliable delivery
     * Use this for WASD or similar directional controls
     */
    fun sendKeyCommand(key: String, isPressed: Boolean) {
        if (isPageTransportCommand(key)) {
            Log.w(TAG, "Blocked Android-local page command from keyboard transport")
            return
        }
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
        val ticket = outputSafetyEpoch.nextTicket()
        val command = "POS:${"%.2f".format(x)},${"%.2f".format(y)}"
        if (!isInitialized || socket == null || serverAddress == null) {
            if (outputSafetyEpoch.isCurrent(ticket)) {
                NetworkClient.send(if (useCbv0) command else outputSafetyEpoch.wrapOutput(ticket, command))
            }
            return
        }

        scope.launch {
            try {
                if (!outputSafetyEpoch.isCurrent(ticket)) return@launch
                // Create message with player prefix for server routing
                val playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"
                val position = "POS:${"%.1f".format(x)},${"%.1f".format(y)}"
                val wireCommand = if (useCbv0) position else outputSafetyEpoch.wrapOutput(ticket, position)
                val message = "$playerPrefix$wireCommand"
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
        val ticket = outputSafetyEpoch.nextTicket()
        val command = "DELTA:${"%.3f".format(dx)},${"%.3f".format(dy)}"
        // Build the common player prefix for legacy path
        val playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"

        // If UDP not ready, fall back to TCP (legacy text)
        if (!isInitialized || socket == null || serverAddress == null) {
            if (outputSafetyEpoch.isCurrent(ticket)) {
                NetworkClient.send(if (useCbv0) command else outputSafetyEpoch.wrapOutput(ticket, command))
            }
            return
        }

        scope.launch {
            try {
                if (!outputSafetyEpoch.isCurrent(ticket)) return@launch
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
                val wireCommand = if (useCbv0) command else outputSafetyEpoch.wrapOutput(ticket, command)
                val msgStr = "$playerPrefix$wireCommand"
                val msg = msgStr.toByteArray()
                socket?.send(DatagramPacket(msg, msg.size, serverAddress, serverPort))
            } catch (e: Exception) {
                if (Math.random() < 0.01) Log.e(TAG, "UDP delta error: ${e.message}")
                // Last resort: TCP legacy
                if (outputSafetyEpoch.isCurrent(ticket)) {
                    val fallback = if (useCbv0) command else outputSafetyEpoch.wrapOutput(ticket, command)
                    NetworkClient.send(fallback)
                }
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
        sendOrderedStickPosition(stickNameRaw, x, y, isMacro = false)
    }

    /** Send an explicit LS/RS directional macro without disguising it as manual RS input. */
    fun sendStickMacroPosition(stickNameRaw: String, x: Float, y: Float) {
        // ConsoleBridge has one stick source and no PC-side Camera Follow arbitration.
        // Preserve its existing binary stick behavior exactly.
        if (useCbv0) {
            sendStickPosition(stickNameRaw, x, y)
            return
        }
        sendOrderedStickPosition(stickNameRaw, x, y, isMacro = true)
    }

    private fun sendOrderedStickPosition(
        stickNameRaw: String,
        x: Float,
        y: Float,
        isMacro: Boolean
    ) {
        val canon = normalizeStickName(stickNameRaw) // e.g., "STICK_L" or "STICK_R"
        val wireName = if (isMacro) {
            if (canon.contains("_R")) "STICK_MACRO_R" else "STICK_MACRO_L"
        } else {
            canon
        }
        val ticket = outputSafetyEpoch.nextTicket()
        val orderedCommand =
            "$wireName:${ticket.sessionId}:${ticket.sequence}:${"%.2f".format(x)},${"%.2f".format(y)}"
        val consoleBridgeFallback =
            "$canon:${"%.2f".format(x)},${"%.2f".format(y)}"

        // If UDP is not ready, retain ordering metadata for Python while preserving
        // ConsoleBridge's existing legacy fallback shape.
        if (!isInitialized || socket == null || serverAddress == null) {
            if (outputSafetyEpoch.isCurrent(ticket)) {
                NetworkClient.send(if (useCbv0) consoleBridgeFallback else orderedCommand)
            }
            return
        }

        scope.launch {
            try {
                if (!outputSafetyEpoch.isCurrent(ticket)) return@launch
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

                // Legacy text path (direct to :9001) — includes ordering metadata so the
                // receiver can discard older coordinates that arrive after newer ones.
                val playerPrefix = if (playerRole == NetworkClient.PlayerRole.PLAYER1) "player1:" else "player2:"
                val message = "$playerPrefix$orderedCommand"
                val buf = message.toByteArray()
                socket?.send(DatagramPacket(buf, buf.size, serverAddress, serverPort))
            } catch (e: Exception) {
                if (Math.random() < 0.01) {
                    Log.e(TAG, "Error sending UDP stick: ${e.message}")
                }
                // Last resort TCP (legacy text)
                if (outputSafetyEpoch.isCurrent(ticket)) {
                    NetworkClient.send(if (useCbv0) consoleBridgeFallback else orderedCommand)
                }
            }
        }
    }



    /**
     * Close the UDP socket
     */
    fun close() {
        outputSafetyEpoch.invalidateQueuedSends()
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
