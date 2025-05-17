package com.example.simplecontroller.ui

import android.os.Handler
import android.os.Looper
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
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
    private val sendIntervalMs = 10L  // ~100 FPS (down from 16ms)
    private var lastSendTimeMs = 0L

    // Use UDP by default for faster transmission
    private val useUdp = true

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
                // Apply response curve for more precise control
                val curvedX = applyResponseCurve(x)
                val curvedY = applyResponseCurve(y)

                if (useUdp) {
                    if (model.type == ControlType.TOUCHPAD) {
                        // For touchpads, use the touchpad-specific sender
                        UdpClient.sendTouchpadPosition(curvedX, curvedY)
                    } else {
                        // For sticks, use the stick position sender with the model's payload
                        UdpClient.sendStickPosition(model.payload, curvedX, curvedY)
                    }
                } else {
                    // Fallback to TCP - format remains the same for both types
                    NetworkClient.send("${model.payload}:${"%.2f".format(curvedX)},${"%.2f".format(curvedY)}")
                }
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
            if (useUdp) {
                UdpClient.sendStickPosition(model.payload, 0f, 0f)
            } else {
                NetworkClient.send("${model.payload}:0.00,0.00")
            }
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
                    // Apply response curve for more precise control
                    val curvedX = applyResponseCurve(currentX)
                    val curvedY = applyResponseCurve(currentY)

                    if (useUdp) {
                        // Use UDP for faster transmission
                        UdpClient.sendStickPosition(model.payload, curvedX, curvedY)
                    } else {
                        // Fallback to TCP
                        NetworkClient.send("${model.payload}:${"%.2f".format(curvedX)},${"%.2f".format(curvedY)}")
                    }

                    // Reduce intensity for next update (creates a smooth stopping effect)
                    decayFactor *= 0.95f
                    uiHandler.postDelayed(this, sendIntervalMs)
                } else {
                    // Stopped moving, send a final zero position to ensure we stop
                    if (useUdp) {
                        UdpClient.sendStickPosition(model.payload, 0f, 0f)
                    } else {
                        NetworkClient.send("${model.payload}:0.00,0.00")
                    }
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
            if (useUdp) {
                if (model.type == ControlType.TOUCHPAD) {
                    // For touchpads, use touchpad-specific sender
                    UdpClient.sendTouchpadPosition(0f, 0f)
                } else {
                    // For sticks, use stick-specific sender
                    UdpClient.sendStickPosition(model.payload, 0f, 0f)
                }

                // Also send via TCP for reliability
                NetworkClient.send("${model.payload}:0.00,0.00")
            } else {
                NetworkClient.send("${model.payload}:0.00,0.00")
            }
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
        // Square response curve with sign preservation
        return value * abs(value)
    }
}