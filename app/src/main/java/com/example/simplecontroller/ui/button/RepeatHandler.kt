package com.example.simplecontroller.ui.button

import android.os.Handler
import android.os.Looper
import com.example.simplecontroller.ui.GlobalSettings

/**
 * Handles repeated firing of payloads for turbo mode.
 * 
 * This class encapsulates the logic for repeatedly executing 
 * an action at a configurable interval.
 */
class RepeatHandler(
    private val fireCallback: () -> Unit,
    private val uiHandler: Handler = Handler(Looper.getMainLooper())
) {
    // Repeater for turbo mode
    private var repeater: Runnable? = null
    
    /**
     * Start repeating the action at the configured interval
     */
    fun startRepeat() {
        // Fire immediately first
        fireCallback()
        
        // Then set up the repeater
        repeater = object : Runnable {
            override fun run() {
                fireCallback()
                uiHandler.postDelayed(this, GlobalSettings.turboSpeed) // Use the configurable speed
            }
        }
        uiHandler.postDelayed(repeater!!, GlobalSettings.turboSpeed)
    }
    
    /**
     * Stop repeating
     */
    fun stopRepeat() {
        repeater?.let { uiHandler.removeCallbacks(it) }
        repeater = null
    }
    
    /**
     * Is repeating currently active?
     */
    fun isRepeating(): Boolean {
        return repeater != null
    }
}