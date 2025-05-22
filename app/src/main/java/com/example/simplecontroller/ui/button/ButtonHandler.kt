package com.example.simplecontroller.ui.button

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.ui.GlobalSettings

/**
 * Handles button-specific behavior.
 * 
 * This class encapsulates all button-related functionality including:
 * - Button press/release handling
 * - Latch (hold) state management
 * - Long-press detection
 * - Turbo (repeat) mode
 */
class ButtonHandler(
    private val model: Control,
    private val firePayloadCallback: () -> Unit,
    private val startRepeatCallback: () -> Unit,
    private val stopRepeatCallback: () -> Unit,
    private val latchStateCallback: (Boolean) -> Unit,
    private val setPressedCallback: (Boolean) -> Unit,
    private val invalidateCallback: () -> Unit
) {
    // Handler for long-press detection
    private val holdHandler = Handler(Looper.getMainLooper())
    
    // Current state
    private var isLatched = false
    
    /**
     * Handle button touch events
     */
    fun handleTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Cancel any pending long press action from before
                holdHandler.removeCallbacksAndMessages(null)
                
                // DEBUG log
                Log.d("DEBUG_BTN", "DOWN  firing ${model.payload}")
                
                // If the button is already latched, a tap should unlatch it immediately
                if (isLatched) {
                    // We'll toggle the latch state on button down for already latched buttons
                    setLatchState(false)
                    setPressedCallback(false)
                    invalidateCallback() // Redraw to show the unlatched state
                } else {
                    // Button is not latched, normal behavior
                    if (GlobalSettings.globalTurbo) {
                        startRepeatCallback()
                    } else {
                        // First check for global hold mode
                        if (GlobalSettings.globalHold && !model.holdToggle) {
                            // Set latched immediately before sending payload
                            setLatchState(true)
                            setPressedCallback(true)
                            invalidateCallback() // Redraw to show the latched state
                        }
                        
                        // Now fire payload (will use the updated isLatched state)
                        firePayloadCallback()
                        
                        // Start long press detection for hold toggle
                        if (model.holdToggle) {
                            holdHandler.postDelayed({
                                // This executes after holdDurationMs
                                setLatchState(true)
                                setPressedCallback(true)
                                invalidateCallback() // Redraw to show the latched state
                                // We need to fire payload again after latching to ensure proper state
                                firePayloadCallback()
                            }, model.holdDurationMs)
                        }
                    }
                }
                return true
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopRepeatCallback()
                
                // DEBUG log
                Log.d("DEBUG_BTN", "UP    firing ${model.payload}")
                
                // Handle latch toggling on short tap for unlatched buttons only
                // (We already handled unlatching on ACTION_DOWN if button was latched,
                // and we handled global hold mode on ACTION_DOWN as well)
                if (!isLatched && model.holdToggle) {
                    // Cancel pending hold toggle if finger is lifted before holdDurationMs
                    holdHandler.removeCallbacksAndMessages(null)
                }
                return true
            }
            
            else -> return false
        }
    }
    
    /**
     * Update the latched state
     */
    fun setLatchState(latched: Boolean) {
        if (isLatched != latched) {
            isLatched = latched
            latchStateCallback(latched)
        }
    }
    
    /**
     * Get the current latched state
     */
    fun isLatched(): Boolean {
        return isLatched
    }
    
    /**
     * Handle a re-center button press
     */
    fun handleRecenterButtonEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Visual feedback
                setLatchState(true)
                setPressedCallback(true)
                invalidateCallback()
                return true
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Visual feedback
                setLatchState(false)
                setPressedCallback(false)
                invalidateCallback()
                return true
            }
            
            else -> return false
        }
    }
    
    /**
     * Clean up resources and cancel any pending callbacks
     */
    fun cleanup() {
        holdHandler.removeCallbacksAndMessages(null)
    }
}