package com.example.simplecontroller.ui.touch

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.net.UdpClient
import kotlin.math.abs

/**
 * Handles touchpad-specific behavior.
 * 
 * This class encapsulates all touchpad-related functionality including:
 * - Touch event handling
 * - Delta movement calculation and smoothing
 * - Mouse button state management (clicks and drag)
 */
class TouchpadHandler(
    private val model: Control,
    private val leftHeldCallback: (Boolean) -> Unit,
    private val uiHandler: Handler = Handler(Looper.getMainLooper())
) {
    // For touchpad tracking
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchPreviousDx = 0f  // Store previous movement for smoothing
    private var touchPreviousDy = 0f  // Store previous movement for smoothing
    private var touchInitialized = false
    
    // Touchpad smoothing factors
    private val touchSmoothingFactor = 0.5f  // 0.0 = no smoothing, 1.0 = maximum smoothing
    private val touchMinMovement = 0.05f     // Minimum movement threshold
    private val touchDeadzone = 0.02f        // Ignore tiny movements below this value
    
    // Timestamp for rate limiting
    private var lastTouchpadSendTime = 0L
    private val touchpadSendIntervalMs = 8   // Limit send rate to reduce network spam
    
    // Tracks if left button is currently held
    private var leftHeld = false
    
    /**
     * Handle touchpad touch events
     */
    fun handleTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Initialize touchpad tracking
                lastTouchX = e.x
                lastTouchY = e.y
                touchPreviousDx = 0f
                touchPreviousDy = 0f
                touchInitialized = true // Set to true immediately - don't skip first move
                
                // Handle mouse button states
                if (model.toggleLeftClick) {
                    // Toggle mode - flip the leftHeld state
                    leftHeld = !leftHeld
                    leftHeldCallback(leftHeld)
                    
                    // Send the appropriate mouse command based on new state
                    if (leftHeld) {
                        UdpClient.sendCommand("MOUSE_LEFT_DOWN")
                    } else {
                        UdpClient.sendCommand("MOUSE_LEFT_UP")
                    }
                } else if (model.holdLeftWhileTouch) {
                    // Standard hold mode
                    UdpClient.sendCommand("MOUSE_LEFT_DOWN")
                    leftHeld = true
                    leftHeldCallback(true)
                }
                return true
            }
            
            MotionEvent.ACTION_MOVE -> {
                // Calculate raw movement delta
                val dx = (e.x - lastTouchX) * model.sensitivity
                val dy = (e.y - lastTouchY) * model.sensitivity
                
                // Update for next calculation
                lastTouchX = e.x
                lastTouchY = e.y
                
                // Skip if movement is within the deadzone
                if (abs(dx) < touchDeadzone && abs(dy) < touchDeadzone) {
                    return true
                }
                
                // Apply smoothing to reduce jitter
                // Blend previous movement with current movement
                val smoothedDx = dx * (1 - touchSmoothingFactor) +
                        touchPreviousDx * touchSmoothingFactor
                val smoothedDy = dy * (1 - touchSmoothingFactor) +
                        touchPreviousDy * touchSmoothingFactor
                
                // Store for next smoothing calculation
                touchPreviousDx = smoothedDx
                touchPreviousDy = smoothedDy
                
                // Apply non-linear scaling for better precision
                // Small movements get boosted, large movements capped
                val scaledDx = applyTouchpadScaling(smoothedDx)
                val scaledDy = applyTouchpadScaling(smoothedDy)
                
                // Rate limit sending to avoid overwhelming the server
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTouchpadSendTime < touchpadSendIntervalMs) {
                    return true
                }
                lastTouchpadSendTime = currentTime
                
                // Send via UDP for lower latency - use consistent protocol
                UdpClient.sendTouchpadDelta(scaledDx, scaledDy)
                return true
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Reset tracking
                touchPreviousDx = 0f
                touchPreviousDy = 0f
                
                // Send a final zero movement to ensure mouse stops
                UdpClient.sendTouchpadPosition(0f, 0f)
                
                // Only release mouse button on up/cancel if using standard hold mode
                if (!model.toggleLeftClick && leftHeld && model.holdLeftWhileTouch) {
                    UdpClient.sendCommand("MOUSE_LEFT_UP")
                    leftHeld = false
                    leftHeldCallback(false)
                }
                return true
            }
            
            else -> return false
        }
    }
    
    /**
     * Apply non-linear scaling to touchpad movement for better control
     * - Boost small movements for precision
     * - Cap large movements to prevent jerky motion
     */
    private fun applyTouchpadScaling(value: Float): Float {
        val absValue = abs(value)
        val sign = if (value >= 0) 1f else -1f
        
        return when {
            // Tiny movements get precision boost
            absValue < 0.2f -> sign * (absValue * 1.5f)
            // Medium movements pass through mostly unchanged
            absValue < 0.6f -> sign * absValue
            // Large movements get slightly reduced
            else -> sign * (0.6f + (absValue - 0.6f) * 0.8f)
        }
    }
    
    /**
     * Clean up resources and release any held buttons
     */
    fun cleanup() {
        if (leftHeld) {
            UdpClient.sendCommand("MOUSE_LEFT_UP")
            leftHeld = false
            leftHeldCallback(false)
        }
    }
    
    /**
     * Reset tracking state
     */
    fun reset() {
        touchPreviousDx = 0f
        touchPreviousDy = 0f
        touchInitialized = false
    }
    
    /**
     * Is the left mouse button currently held?
     */
    fun isLeftHeld(): Boolean {
        return leftHeld
    }
}