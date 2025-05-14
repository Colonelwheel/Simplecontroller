package com.example.simplecontroller.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import com.example.simplecontroller.MainActivity
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.net.NetworkClient

/**
 * Helper class for creating and managing ControlView UI elements and behavior.
 * 
 * This class handles common functionality used by ControlView instances:
 * - Creating and managing UI controls (labels, buttons)
 * - Helper methods for payload handling
 * - Turbo/repeat functionality
 */
class ControlViewHelper(
    private val context: Context,
    private val model: Control,
    private val parentView: ControlView,
    private val onDeleteRequested: (Control) -> Unit
) {
    // UI Handler for callbacks
    private val uiHandler = Handler(Looper.getMainLooper())
    
    // For turbo/repeat functionality
    private var repeater: Runnable? = null
    
    // UI elements
    private val label = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.WHITE)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
        gravity = Gravity.CENTER
    }
    
    private val gear: ImageButton
    private val dup: ImageButton
    private val del: ImageButton
    
    init {
        // Initialize UI elements
        gear = makeIcon(
            android.R.drawable.ic_menu_manage,
            Gravity.TOP or Gravity.END
        ) { showProperties() }
        
        dup = makeIcon(
            android.R.drawable.ic_menu_add,
            Gravity.TOP or Gravity.START
        ) { if (GlobalSettings.editMode) duplicateSelf() }
        
        del = makeIcon(
            android.R.drawable.ic_menu_delete,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ) { if (GlobalSettings.editMode) confirmDelete() }
        
        // Add to parent view
        parentView.addView(label)
        parentView.addView(gear)
        parentView.addView(dup)
        parentView.addView(del)
    }

    /**
     * Create an icon button with specified appearance and click handler
     */
    private fun makeIcon(resId: Int, g: Int, onClick: () -> Unit) =
        ImageButton(context).apply {
            setImageResource(resId)
            background = null
            alpha = .8f
            setPadding(8)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                g
            )
            setOnClickListener { onClick() }
        }
    
    /**
     * Update the label text based on the model name
     */
    fun updateLabel() {
        label.text = model.name
        label.visibility = if (model.name.isNotEmpty()) View.VISIBLE else View.GONE
    }
    
    /**
     * Update overlay visibility based on edit mode
     */
    fun updateOverlay() {
        val vis = if (GlobalSettings.editMode) View.VISIBLE else View.GONE
        gear.visibility = vis
        dup.visibility = vis
        del.visibility = vis
    }
    
    /**
     * Show the property sheet for this control
     */
    private fun showProperties() {
        PropertySheetBuilder(context, model) {
            // After properties are updated:
            val lp = parentView.layoutParams as ViewGroup.MarginLayoutParams
            lp.width = model.w.toInt()
            lp.height = model.h.toInt()
            parentView.layoutParams = lp
            updateLabel()
            parentView.invalidate()
            
            // Stop any running senders when settings change
            parentView.stopContinuousSending()
            parentView.stopDirectionalCommands()
        }.showPropertySheet()
    }
    
    /**
     * Create a duplicate of this control
     */
    private fun duplicateSelf() {
        val copy = model.copy(
            id = "${model.id}_copy_${System.currentTimeMillis()}",
            x = model.x + 40f,
            y = model.y + 40f
        )
        (context as? MainActivity)?.createControlFrom(copy)
    }
    
    /**
     * Show delete confirmation dialog
     */
    private fun confirmDelete() {
        AlertDialog.Builder(context)
            .setMessage("Delete this control?")
            .setPositiveButton("Delete") { _, _ ->
                (parentView.parent as? FrameLayout)?.removeView(parentView)
                onDeleteRequested(model)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Send the control's payload 
     */
    fun firePayload() {
        model.payload.split(',', ' ')
            .filter { it.isNotBlank() }
            .forEach { NetworkClient.send(it.trim()) }
    }
    
    /**
     * Start repeating the payload (for turbo mode)
     */
    fun startRepeat() {
        firePayload() // fire immediately
        repeater = object : Runnable {
            override fun run() {
                firePayload()
                uiHandler.postDelayed(this, 16L) // ≈60 Hz
            }
        }
        uiHandler.postDelayed(repeater!!, 16L)
    }
    
    /**
     * Stop repeating the payload
     */
    fun stopRepeat() {
        repeater?.let { uiHandler.removeCallbacks(it) }
        repeater = null
    }
}

/**
 * Global settings for all control views.
 * 
 * This replaces the static variables in the ControlView companion object.
 */
object GlobalSettings {
    // Whether edit mode is currently active
    var editMode = false
        set(v) {
            field = v
            // Update all controls when edit mode changes
            SwipeManager.notifyEditModeChanged(v)
        }
    
    // Whether auto-center is enabled for sticks/pads
    var snapEnabled = true
        set(value) {
            field = value
            if (value) {
                // When enabling snap, stop any continuous sending
                SwipeManager.stopAllContinuousSending()
            }
        }
    
    // Whether all buttons should use hold/latch behavior
    var globalHold = false
    
    // Whether all buttons should use turbo/rapid-fire
    var globalTurbo = false
        set(value) {
            if (field && !value) {
                // When turning turbo off, make sure all repeaters are stopped
                SwipeManager.stopAllRepeating()
            }
            field = value
        }
    
    // Whether swipe mode is active
    var globalSwipe = false
        set(value) {
            if (field && !value) {
                // When turning swipe off, cleanup active touches
                SwipeManager.cleanupActiveTouch()
            }
            field = value
            SwipeManager.setSwipeEnabled(value)
        }
}

/**
 * Central manager for swipe functionality to coordinate between all controls.
 */
object SwipeManager {
    // Reference to swipe handler that manages actual touch handling
    private val swipeHandler = SwipeHandler()
    
    // All registered control views
    private val allViews = mutableSetOf<ControlView>()
    
    /**
     * Register a control view with the manager
     */
    fun registerControl(view: ControlView) {
        allViews.add(view)
        swipeHandler.registerView(view)
    }
    
    /**
     * Unregister a control view
     */
    fun unregisterControl(view: ControlView) {
        allViews.remove(view)
        swipeHandler.unregisterView(view)
    }
    
    /**
     * Process a touch event through the swipe handler
     */
    fun processTouchEvent(e: android.view.MotionEvent): Boolean {
        return swipeHandler.processTouchEvent(e)
    }
    
    /**
     * Set whether swipe mode is enabled
     */
    fun setSwipeEnabled(enabled: Boolean) {
        swipeHandler.setSwipeEnabled(enabled)
    }
    
    /**
     * Notify all controls that edit mode has changed
     */
    fun notifyEditModeChanged(editMode: Boolean) {
        swipeHandler.setEditMode(editMode)
        allViews.forEach {
            it.updateOverlay()
            if (editMode) {
                it.stopContinuousSending()
                it.stopDirectionalCommands()
            }
        }
    }
    
    /**
     * Stop all continuous sending when needed
     */
    fun stopAllContinuousSending() {
        allViews.forEach {
            it.stopContinuousSending()
            it.stopDirectionalCommands()
        }
    }
    
    /**
     * Stop all repeating (for turbo mode)
     */
    fun stopAllRepeating() {
        allViews.forEach { it.stopRepeat() }
    }
    
    /**
     * Clean up any active touch state when swipe is disabled
     */
    fun cleanupActiveTouch() {
        swipeHandler.setSwipeEnabled(false)
    }
    
    /**
     * Re-center all stick controls
     * This is triggered by the Re-center button
     */
    fun recenterAllSticks() {
        allViews.forEach { view ->
            if (view.model.type == ControlType.STICK) {
                // Stop any continuous sending or directional commands
                view.stopContinuousSending()
                view.stopDirectionalCommands()
                
                // Send zero values to center the stick
                if (view.model.directionalMode) {
                    // For directional sticks, we just stop any active commands
                } else {
                    // For analog sticks, send center position (0,0)
                    NetworkClient.send("${view.model.payload}:0.00,0.00")
                }
            }
        }
    }
}