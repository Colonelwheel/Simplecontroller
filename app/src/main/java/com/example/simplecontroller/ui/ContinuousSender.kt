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
    
    // Optimized sending parameters
    private val sendIntervalMs = 8L  // ~125 FPS (reduced from 10ms)
    private var lastSendTimeMs = 0L
    private val positionThreshold = 0.015f  // Minimum change threshold before sending update
    
    // Use UDP by default for faster transmission
    private val useUdp = true
    
    // Cached format strings to avoid allocation
    private val formatString = "%.1f"
    
    // Store last sent position to avoid duplicate transmissions
    private var lastSentX = 0f
    private var lastSentY = 0f

    /**
     * Set the last position to be continuously sent
     * Optimized for less network traffic and overhead
     */
    fun setLastPosition(x: Float, y: Float) {
        // Only update position if change exceeds threshold (reduces jitter and network traffic)
        if (abs(lastStickX - x) > positionThreshold || abs(lastStickY - y) > positionThreshold) {
            lastStickX = x
            lastStickY = y

            // Direct send with throttling for immediate response
            val currentTimeMs = System.currentTimeMillis()
            if (currentTimeMs - lastSendTimeMs >= sendIntervalMs) {
                // Apply response curve for more precise control
                val curvedX = applyResponseCurve(x)
                val curvedY = applyResponseCurve(y)
                
                // Only send if the curved values are significantly different from last sent
                // This avoids network traffic for minor changes after curve application
                if (abs(curvedX - lastSentX) > positionThreshold || abs(curvedY - lastSentY) > positionThreshold) {
                    lastSentX = curvedX
                    lastSentY = curvedY
                    
                    // Use UDP when possible for lower latency
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
                        NetworkClient.send("${model.payload}:${formatString.format(curvedX)},${formatString.format(curvedY)}")
                    }
                }
                lastSendTimeMs = currentTimeMs
            }
        }
    }

    /**
     * Start continuously sending the current position
     * Optimized for smoother movement and less traffic
     */
    fun startContinuousSending() {
        // Stop any existing continuous sender
        stopContinuousSending()

        // More aggressive deadzone to prevent drift
        if (abs(lastStickX) < 0.15f && abs(lastStickY) < 0.15f) {
            // Send a center position to ensure stick stops
            // But only if we're not already at center (reduces unnecessary traffic)
            if (lastSentX != 0f || lastSentY != 0f) {
                lastSentX = 0f
                lastSentY = 0f
                if (useUdp) {
                    UdpClient.sendStickPosition(model.payload, 0f, 0f)
                } else {
                    NetworkClient.send("${model.payload}:0.0,0.0")
                }
            }
            return
        }

        // Create a continuous sender with optimized decay
        continuousSender = object : Runnable {
            private var decayFactor = 1.0f
            private var prevX = lastStickX
            private var prevY = lastStickY

            override fun run() {
                // Apply decay factor for smooth stopping
                val currentX = lastStickX * decayFactor
                val currentY = lastStickY * decayFactor

                // Only send if values are significant and different from previous
                if ((abs(currentX) > 0.15f || abs(currentY) > 0.15f) && 
                    (abs(currentX - prevX) > positionThreshold || abs(currentY - prevY) > positionThreshold)) {
                    
                    // Store current values for next comparison
                    prevX = currentX
                    prevY = currentY
                    
                    // Apply response curve for more precise control
                    val curvedX = applyResponseCurve(currentX)
                    val curvedY = applyResponseCurve(currentY)

                    // Only send if different from last sent values
                    if (abs(curvedX - lastSentX) > positionThreshold || abs(curvedY - lastSentY) > positionThreshold) {
                        lastSentX = curvedX
                        lastSentY = curvedY
                        
                        if (useUdp) {
                            // Use UDP for faster transmission
                            UdpClient.sendStickPosition(model.payload, curvedX, curvedY)
                        } else {
                            // Fallback to TCP
                            NetworkClient.send("${model.payload}:${formatString.format(curvedX)},${formatString.format(curvedY)}")
                        }
                    }

                    // Reduce intensity for next update (creates a smooth stopping effect)
                    // Slower decay for smoother motion
                    decayFactor *= 0.97f
                    uiHandler.postDelayed(this, sendIntervalMs)
                } else {
                    // Stopped moving, send a final zero position to ensure we stop
                    // But only if we're not already at center
                    if (lastSentX != 0f || lastSentY != 0f) {
                        lastSentX = 0f
                        lastSentY = 0f
                        if (useUdp) {
                            UdpClient.sendStickPosition(model.payload, 0f, 0f)
                        } else {
                            NetworkClient.send("${model.payload}:0.0,0.0")
                        }
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
     * Optimized for responsiveness while maintaining smoothness
     */
    private fun applyResponseCurve(value: Float): Float {
        // Improved response curve for smoother precision control
        // Uses cubic response for better precision at low movements
        val sign = if (value >= 0) 1f else -1f
        val absValue = abs(value)
        
        // Apply different curves based on input magnitude
        return when {
            absValue < 0.3f -> sign * 0.7f * (absValue * absValue * absValue) + sign * 0.3f * (absValue * absValue)
            else -> sign * absValue * absValue  // Square response for higher values
        }
    }
}