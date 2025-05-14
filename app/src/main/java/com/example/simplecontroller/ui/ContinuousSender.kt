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
    
    /**
     * Set the last position to be continuously sent
     */
    fun setLastPosition(x: Float, y: Float) {
        lastStickX = x
        lastStickY = y
    }
    
    /**
     * Start continuously sending the current position
     */
    fun startContinuousSending() {
        // Stop any existing continuous sender
        stopContinuousSending()
        
        // If position is near center, don't bother with continuous sending
        if (abs(lastStickX) < 0.1f && abs(lastStickY) < 0.1f) {
            return
        }

        // Create a new continuous sender
        continuousSender = object : Runnable {
            override fun run() {
                NetworkClient.send("${model.payload}:${"%.2f".format(lastStickX)},${"%.2f".format(lastStickY)}")
                uiHandler.postDelayed(this, 100L) // Send every 100ms
            }
        }

        // Start continuous sending
        uiHandler.postDelayed(continuousSender!!, 100L)
    }
    
    /**
     * Stop continuous sending
     */
    fun stopContinuousSending() {
        continuousSender?.let { uiHandler.removeCallbacks(it) }
        continuousSender = null
    }
    
    /**
     * Is continuous sending currently active?
     */
    fun isActive(): Boolean {
        return continuousSender != null
    }
}