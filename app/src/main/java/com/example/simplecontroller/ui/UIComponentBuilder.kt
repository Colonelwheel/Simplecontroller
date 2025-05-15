package com.example.simplecontroller.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Switch
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.example.simplecontroller.R
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
     * @param marginH Horizontal margin (optional)
     * @param marginV Vertical margin (optional)
     * @param onClick Click handler
     * @return The created button
     */
    fun addCornerButton(
        label: String,
        gravity: Int,
        marginH: Int = 16,
        marginV: Int = 16,
        onClick: (View) -> Unit
    ): Button {
        return Button(context).apply {
            text = label
            alpha = 0.7f
            // Add theme-specific styling
            setTextColor(ContextCompat.getColor(context, R.color.dark_text_primary))
            backgroundTintList = ContextCompat.getColorStateList(context, R.color.button_blue)
            setOnClickListener(onClick)
            addViewToCanvas(this, gravity, marginH, marginV)
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
        // Add theme-specific styling
        setTextColor(ContextCompat.getColor(context, R.color.dark_text_primary))
        thumbTintList = ContextCompat.getColorStateList(context, R.color.primary_blue)
        trackTintList = ContextCompat.getColorStateList(context, R.color.secondary_blue)
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
            // Add theme-specific styling
            backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary_blue)
            imageTintList = ContextCompat.getColorStateList(context, R.color.dark_text_primary)
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

    /**
     * Add an editable text field with apply button
     *
     * @param initialValue Initial value to display
     * @param hint Hint text to show
     * @param gravity Position gravity
     * @param marginH Horizontal margin
     * @param marginV Vertical margin
     * @param width Width in pixels
     * @param onApply Callback when value is applied
     * @return Pair of the EditText and the Button
     */
    fun addEditWithApplyButton(
        initialValue: String,
        hint: String,
        gravity: Int,
        marginH: Int,
        marginV: Int,
        width: Int = 100,
        onApply: (String) -> Unit
    ): Pair<EditText, ImageButton> {
        // Create container layout
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity
            ).apply {
                setMargins(marginH, marginV, marginH, marginV)
            }
        }

        // Create EditText
        val editText = EditText(context).apply {
            setText(initialValue)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            background = ContextCompat.getDrawable(context, android.R.drawable.edit_text)
            setPadding(8, 0, 8, 0)
            // Add theme-specific styling
            setTextColor(ContextCompat.getColor(context, R.color.dark_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.dark_text_secondary))
            backgroundTintList = ContextCompat.getColorStateList(context, R.color.dark_surface)
        }

        // Create apply button
        val applyButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_save)
            background = null
            alpha = 0.8f
            setPadding(4, 4, 4, 4)
            // Add theme-specific styling
            imageTintList = ContextCompat.getColorStateList(context, R.color.primary_blue)
            setOnClickListener {
                val value = editText.text.toString()
                if (value.isNotEmpty()) {
                    onApply(value)
                }
            }
        }

        // Add views to container
        container.addView(editText)
        container.addView(applyButton)

        // Add container to canvas
        canvas.addView(container)

        return Pair(editText, applyButton)
    }
}