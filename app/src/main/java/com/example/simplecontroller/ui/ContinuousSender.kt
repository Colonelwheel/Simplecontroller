package com.example.simplecontroller.ui

import android.os.Handler
import android.os.Looper
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.net.NetworkClient
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
                NetworkClient.send("${model.payload}:${"%.2f".format(x)},${"%.2f".format(y)}")
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
            NetworkClient.send("${model.payload}:0.00,0.00")
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
                    NetworkClient.send("${model.payload}:${"%.2f".format(currentX)},${"%.2f".format(currentY)}")

                    // Reduce intensity for next update (creates a smooth stopping effect)
                    decayFactor *= 0.95f
                    uiHandler.postDelayed(this, sendIntervalMs)
                } else {
                    // Stopped moving, send a final zero position to ensure we stop
                    NetworkClient.send("${model.payload}:0.00,0.00")
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
            NetworkClient.send("${model.payload}:0.00,0.00")
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
     * Apply non-linear response curve for more precise control
     * This gives finer control near center, more speed at edges
     */
    private fun applyResponseCurve(value: Float): Float {
        return value * abs(value)  // Square response curve
    }
}