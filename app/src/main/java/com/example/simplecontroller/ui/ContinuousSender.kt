package com.example.simplecontroller.ui

import android.os.Handler
import android.os.Looper
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.net.UdpClient
import kotlin.math.abs

/**
 * Handles continuous sending of control positions.
 * Used primarily for analog stick positions that need to be maintained even after
 * the user lifts their finger.
 */
class ContinuousSender(
    private val model: Control,
    private val uiHandler: Handler = Handler(Looper.getMainLooper())
) {
    private var continuousSender: Runnable? = null

    // Store last position values
    private var lastStickX = 0f
    private var lastStickY = 0f

    // Throttle sending frequency
    private val sendIntervalMs = 16L  // ~60 FPS (down from 33ms)
    private var lastSendTimeMs = 0L
    
    // Flag to use UDP for positioning when available
    private var useUdp = GlobalSettings.useUdpForAll
    
    init {
        // Register with SwipeManager for UDP settings updates
        SwipeManager.registerContinuousSender(this)
    }

    /**
     * Set the last position to be continuously sent
     */
    fun setLastPosition(x: Float, y: Float) {
        // Only update position if significantly different (reduces jitter)
        if (abs(lastStickX - x) > 0.01f || abs(lastStickY - y) > 0.01f) {
            lastStickX = x
            lastStickY = y

            // Direct send with throttling for immediate response
            val currentTimeMs = System.currentTimeMillis()
            if (currentTimeMs - lastSendTimeMs >= sendIntervalMs) {
                sendPositionUpdate(x, y)
                lastSendTimeMs = currentTimeMs
            }
        }
    }

    /**
     * Start continuously sending the current position
     */
    fun startContinuousSending() {
        // Stop any existing continuous sender
        stopContinuousSending()

        // More aggressive deadzone to prevent drift
        if (abs(lastStickX) < 0.15f && abs(lastStickY) < 0.15f) {
            // Send a center position to ensure stick stops
            sendPositionUpdate(0f, 0f)
            return
        }

        // Create a continuous sender with decay
        continuousSender = object : Runnable {
            private var decayFactor = 1.0f

            override fun run() {
                // Apply decay factor for smooth stopping
                val currentX = lastStickX * decayFactor
                val currentY = lastStickY * decayFactor

                // Only send if values are significant
                if (abs(currentX) > 0.15f || abs(currentY) > 0.15f) {
                    sendPositionUpdate(currentX, currentY)

                    // Reduce intensity for next update (creates a smooth stopping effect)
                    decayFactor *= 0.95f
                    uiHandler.postDelayed(this, sendIntervalMs)
                } else {
                    // Stopped moving, send a final zero position to ensure we stop
                    sendPositionUpdate(0f, 0f)
                    continuousSender = null
                }
            }
        }

        // Start continuous sending
        uiHandler.postDelayed(continuousSender!!, sendIntervalMs)
    }

    /**
     * Stop continuous sending
     */
    fun stopContinuousSending() {
        continuousSender?.let { uiHandler.removeCallbacks(it) }
        continuousSender = null

        // Send a final zero position to ensure controls stop
        if (abs(lastStickX) > 0.01f || abs(lastStickY) > 0.01f) {
            sendPositionUpdate(0f, 0f)
            lastStickX = 0f
            lastStickY = 0f
        }
    }

    /**
     * Is continuous sending currently active?
     */
    fun isActive(): Boolean {
        return continuousSender != null
    }
    
    /**
     * Send position update using the best available method (UDP if available, TCP as fallback)
     */
    private fun sendPositionUpdate(x: Float, y: Float) {
        // Check if this is a stick control
        val isStick = model.payload.contains("STICK") || 
                      model.payload.contains("LS") || 
                      model.payload.contains("RS")
        
        // Try UDP first for better latency if it's enabled and initialized
        if (useUdp && UdpClient.isInitialized()) {
            if (isStick) {
                // Get stick name from payload
                val stickName = when {
                    model.payload.contains("STICK_L") -> "L"
                    model.payload.contains("LS") -> "L"
                    model.payload.contains("STICK_R") -> "R"
                    model.payload.contains("RS") -> "R"
                    else -> "L" // Default to left stick if not specified
                }
                UdpClient.sendStickPosition(stickName, x, y)
            } else {
                // For other position controls (like touchpad)
                UdpClient.sendPosition(x, y)
            }
        } else {
            // Fallback to TCP via NetworkClient
            NetworkClient.send("${model.payload}:${"%.2f".format(x)},${"%.2f".format(y)}")
        }
    }

    /**
     * Configure whether to use UDP for position updates
     */
    fun setUseUdp(enabled: Boolean) {
        useUdp = enabled
    }
    
    /**
     * Clean up resources when no longer needed
     */
    fun dispose() {
        SwipeManager.unregisterContinuousSender(this)
    }

    /**
     * Apply non-linear response curve for more precise control
     * This gives finer control near center, more speed at edges
     */
    private fun applyResponseCurve(value: Float): Float {
        return value * abs(value)  // Square response curve
    }
}