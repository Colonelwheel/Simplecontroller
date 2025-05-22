package com.example.simplecontroller.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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
import com.example.simplecontroller.network.PayloadSender
import com.example.simplecontroller.ui.GlobalSettings
import com.example.simplecontroller.ui.PropertySheetBuilder
import com.example.simplecontroller.ui.RefactoredControlView
import com.example.simplecontroller.ui.button.RepeatHandler

/**
 * Helper class for creating and managing control view UI elements and behavior.
 * 
 * This is a refactored version that delegates payload handling to dedicated classes.
 */
class ControlUIHelper(
    private val context: Context,
    private val model: Control,
    private val parentView: View,
    private val isLatchedCallback: () -> Boolean,
    private val onDeleteRequested: (Control) -> Unit
) {
    // UI Handler for callbacks
    private val uiHandler = Handler(Looper.getMainLooper())
    
    // Specialized handlers
    private val payloadSender = PayloadSender(model, isLatchedCallback)
    private val repeatHandler = RepeatHandler({ payloadSender.firePayload() }, uiHandler)
    
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
        (parentView as? ViewGroup)?.let {
            it.addView(label)
            it.addView(gear)
            it.addView(dup)
            it.addView(del)
        }
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
            if (parentView is ViewGroup) {
                val lp = parentView.layoutParams as ViewGroup.MarginLayoutParams
                lp.width = model.w.toInt()
                lp.height = model.h.toInt()
                parentView.layoutParams = lp
                updateLabel()
                parentView.invalidate()
            }
            
            // Stop any running senders when settings change
            if (parentView is RefactoredControlView) {
                parentView.stopContinuousSending()
                parentView.stopDirectionalCommands()
            }
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
                (parentView.parent as? ViewGroup)?.removeView(parentView)
                onDeleteRequested(model)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Send the control's payload
     */
    fun firePayload() {
        payloadSender.firePayload()
    }
    
    /**
     * Release any latched buttons
     */
    fun releaseLatched() {
        payloadSender.releaseLatched()
    }
    
    /**
     * Start repeating the payload (for turbo mode)
     */
    fun startRepeat() {
        repeatHandler.startRepeat()
    }
    
    /**
     * Stop repeating the payload
     */
    fun stopRepeat() {
        repeatHandler.stopRepeat()
    }
}