package com.example.simplecontroller.ui.stick

import android.view.MotionEvent
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.ui.GlobalSettings
import kotlin.math.abs

/**
 * Handles stick-specific behavior.
 * 
 * This class encapsulates all stick-related functionality including:
 * - Position calculation
 * - Auto-centering
 * - Regular analog mode vs directional mode
 */
class StickHandler(
    private val model: Control,
    private val width: Int,
    private val height: Int,
    private val setPositionCallback: (Float, Float) -> Unit,
    private val stopContinuousSendingCallback: () -> Unit,
    private val stopDirectionalCommandsCallback: () -> Unit,
    private val startContinuousSendingCallback: () -> Unit,
    private val handleDirectionalStickCallback: (Float, Float, Int) -> Unit
) {
    /**
     * Handle stick touch events
     */
    fun handleTouchEvent(e: MotionEvent): Boolean {
        // Stop continuous sending when touching the stick again
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            stopContinuousSendingCallback()
            stopDirectionalCommandsCallback()
            return true
        }
        
        if (e.actionMasked !in listOf(
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL)) {
            return false
        }
        
        // Calculate normalized position
        val cx = width/2f
        val cy = height/2f
        val nx = ((e.x - cx) / (width/2f)).coerceIn(-1f, 1f) * model.sensitivity
        val ny = ((e.y - cy) / (height/2f)).coerceIn(-1f, 1f) * model.sensitivity
        
        // Only snap if snapEnabled is true AND the control's autoCenter is true AND it's an UP/CANCEL event
        val shouldSnap = GlobalSettings.snapEnabled && model.autoCenter &&
                (e.actionMasked == MotionEvent.ACTION_UP ||
                        e.actionMasked == MotionEvent.ACTION_CANCEL)
        
        val (sx, sy) = if (shouldSnap) 0f to 0f else nx to ny
        
        // Store last position
        setPositionCallback(sx, sy)
        
        // Handle directional mode for sticks
        if (model.type == ControlType.STICK && model.directionalMode) {
            handleDirectionalStickCallback(sx, sy, e.actionMasked)
        } else {
            // Regular analog stick/pad mode
            NetworkClient.send("${model.payload}:${"%.2f".format(sx)},${"%.2f".format(sy)}")
        }
        
        // If this is an UP or CANCEL event and we shouldn't snap,
        // start continuous sending of the last position ONLY FOR STICKS in ANALOG mode
        if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) &&
            !shouldSnap &&
            model.type == ControlType.STICK &&  // Only for sticks, not touchpads
            !model.directionalMode &&  // Only for analog sticks, not directional ones
            !model.autoCenter) {
            
            // If stick position is near center, don't bother with continuous sending
            if (abs(sx) < 0.1f && abs(sy) < 0.1f) {
                stopContinuousSendingCallback()
                return true
            }
            
            startContinuousSendingCallback()
        }
        
        return true
    }
}