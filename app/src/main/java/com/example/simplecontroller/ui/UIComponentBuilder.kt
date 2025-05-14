package com.example.simplecontroller.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Switch
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Builds and manages UI components for the main activity.
 * 
 * This class centralizes creation of common UI elements like buttons, switches,
 * and handles their placement within the main canvas.
 */
class UIComponentBuilder(
    private val context: Context,
    private val canvas: FrameLayout
) {
    /**
     * Add a button to a corner of the canvas
     * 
     * @param label Button text
     * @param gravity Position (e.g., Gravity.TOP or Gravity.END)
     * @param onClick Click handler
     * @return The created button
     */
    fun addCornerButton(label: String, gravity: Int, onClick: (View) -> Unit): Button {
        return Button(context).apply {
            text = label
            alpha = 0.7f
            setOnClickListener(onClick)
            addViewToCanvas(this, gravity, 16, 16)
        }
    }
    
    /**
     * Create a switch with specified properties
     * 
     * @param label Switch text
     * @param initialState Initial checked state
     * @param onChange Change handler
     * @return The created switch
     */
    fun createSwitch(
        label: String,
        initialState: Boolean,
        onChange: (Boolean) -> Unit
    ): Switch = Switch(context).apply {
        text = label
        alpha = 0.7f
        isChecked = initialState
        setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
    }
    
    /**
     * Add a floating action button to the canvas
     * 
     * @param iconResource Icon resource ID
     * @param gravity Position gravity
     * @param marginH Horizontal margin
     * @param marginV Vertical margin
     * @param onClick Click handler
     * @return The created FAB
     */
    fun addFloatingActionButton(
        iconResource: Int,
        gravity: Int,
        marginH: Int = 0,
        marginV: Int = 0,
        onClick: () -> Unit
    ): FloatingActionButton {
        return FloatingActionButton(context).apply {
            setImageResource(iconResource)
            alpha = 0.85f
            setOnClickListener { onClick() }
            addViewToCanvas(this, gravity, marginH, marginV)
        }
    }

    /**
     * Helper to add a view to the canvas with specific layout parameters
     * 
     * @param view The view to add
     * @param gravity Position gravity
     * @param marginH Horizontal margin
     * @param marginV Vertical margin
     */
    fun addViewToCanvas(view: View, gravity: Int, marginH: Int, marginV: Int) {
        canvas.addView(
            view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity
            ).apply { setMargins(marginH, marginV, marginH, marginV) }
        )
    }
    
    /**
     * Add multiple switches vertically aligned
     * 
     * @param switches List of switches to add
     * @param baseGravity Base gravity for positioning
     * @param startMarginH Horizontal margin
     * @param startMarginV Initial vertical margin
     * @param spacing Vertical spacing between switches
     */
    fun addVerticalSwitches(
        switches: List<Switch>,
        baseGravity: Int = Gravity.TOP or Gravity.START,
        startMarginH: Int = 16,
        startMarginV: Int = 16,
        spacing: Int = 48
    ) {
        switches.forEachIndexed { index, switch ->
            addViewToCanvas(switch, baseGravity, startMarginH, startMarginV + (index * spacing))
        }
    }
    
    /**
     * Update the visibility of a set of views
     * 
     * @param views List of views to update
     * @param visible Whether the views should be visible
     */
    fun updateViewsVisibility(views: List<View>, visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        views.forEach { it.visibility = visibility }
    }
}