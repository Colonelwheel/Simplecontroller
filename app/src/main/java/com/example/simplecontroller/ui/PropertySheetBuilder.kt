package com.example.simplecontroller.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import kotlin.math.roundToInt

/**
 * Builds property sheet dialogs for controls.
 *
 * This class encapsulates the dialog creation and property management for all control types.
 * It creates different UI elements based on the control type (Button, Stick, Touchpad) and
 * handles saving the updated properties back to the control model.
 *
 * Features:
 * - Builds scrollable dialog with appropriate fields for each control type
 * - Manages control sizing, sensitivity and payload
 * - Handles specialized settings like hold toggle, auto-center, etc.
 * - Creates complex directional control settings for sticks
 */
class PropertySheetBuilder(
    private val context: Context,
    private val model: Control,
    private val onPropertiesUpdated: () -> Unit
) {
    /**
     * Show the property sheet dialog with all appropriate UI elements for this control type.
     * This creates a scrollable dialog containing all editable properties for the control.
     */
    fun showPropertySheet() {
        // Create a ScrollView to make long property sheets scrollable
        val scrollView = ScrollView(context).apply {
            // Set max height to 70% of screen height to ensure dialog isn't too tall
            val metrics = context.resources.displayMetrics
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (metrics.heightPixels * 0.7).toInt()
            )
        }

        // Main dialog container
        val dlg = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }

        // Add the LinearLayout to the ScrollView
        scrollView.addView(dlg)

        // Helper for creating space between components
        val createGap = { h: Int ->
            Space(context).apply { minimumHeight = h }
        }

        // ---- Common properties for all control types ----

        // Name/label field
        val etName = EditText(context).apply {
            hint = "Label"
            setText(model.name)
        }
        dlg.addView(etName)
        dlg.addView(createGap(8))

        // Width & height sliders
        addSizeControls(dlg, createGap)

        // Sensitivity slider (for sticks and touchpads)
        val sensSeek = addSensitivityControl(dlg, createGap)

        // Hold toggle checkbox (for buttons)
        val chkHold = addHoldToggleControl(dlg, createGap)

        // Auto-center checkbox (for sticks and touchpads)
        val chkAuto = addAutoCenterControl(dlg, createGap)

        // Touchpad-specific controls
        val chkDrag = addTouchpadControls(dlg, createGap)

        // Button-specific controls for swipe activation
        val chkSwipe = addSwipeActivationControl(dlg, createGap)

        // Directional mode checkbox and container (for sticks)
        val directionalData = addDirectionalModeControls(dlg, createGap)

        // Payload field with autocomplete
        val etPayload = addPayloadControl(dlg)

        // Create and show the dialog
        AlertDialog.Builder(context).setTitle("Properties").setView(scrollView)
            .setPositiveButton("OK") { _, _ ->
                // Save all the common properties
                model.name = etName.text.toString()
                model.w = directionalData.wSeek.progress.toFloat().coerceAtLeast(40f)
                model.h = directionalData.hSeek.progress.toFloat().coerceAtLeast(40f)
                model.payload = etPayload.text.toString().trim()

                // Button-specific properties
                if (model.type == ControlType.BUTTON) {
                    model.holdToggle = chkHold.isChecked
                    model.holdDurationMs = directionalData.etMs.text.toString().toLongOrNull() ?: 400L
                    model.swipeActivate = chkSwipe.isChecked
                }

                // Stick/Touchpad properties
                if (model.type != ControlType.BUTTON) {
                    model.autoCenter = chkAuto.isChecked
                    sensSeek?.let { model.sensitivity = it.progress / 100f }
                }

                // Touchpad-specific properties
                if (model.type == ControlType.TOUCHPAD) {
                    model.holdLeftWhileTouch = chkDrag.isChecked
                    model.toggleLeftClick = directionalData.chkToggleClick.isChecked
                }

                // Stick-specific directional mode properties
                if (model.type == ControlType.STICK) {
                    model.directionalMode = directionalData.chkDirectional.isChecked

                    if (model.directionalMode) {
                        updateDirectionalModeSettings(directionalData.directionalContainer)
                    }
                }

                // Notify that properties have been updated
                onPropertiesUpdated()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Add width and height sliders to the property sheet
     */
    private fun addSizeControls(dlg: LinearLayout, gapCreator: (Int) -> Space): Pair<SeekBar, SeekBar> {
        val wText = TextView(context)
        val wSeek = SeekBar(context).apply {
            max = 600
            progress = model.w.roundToInt().coerceIn(40, 600)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    wText.text = "Width: $p px"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val hText = TextView(context)
        val hSeek = SeekBar(context).apply {
            max = 600
            progress = model.h.roundToInt().coerceIn(40, 600)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    hText.text = "Height: $p px"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        // Set initial text values
        wText.text = "Width: ${wSeek.progress} px"
        hText.text = "Height: ${hSeek.progress} px"

        // Add to container
        dlg.addView(wText)
        dlg.addView(wSeek)
        dlg.addView(hText)
        dlg.addView(hSeek)
        dlg.addView(gapCreator(8))

        return Pair(wSeek, hSeek)
    }

    /**
     * Add sensitivity slider for stick/touchpad controls
     */
    private fun addSensitivityControl(dlg: LinearLayout, gapCreator: (Int) -> Space): SeekBar? {
        // Only add sensitivity for non-button controls
        if (model.type == ControlType.BUTTON) return null

        val sText = TextView(context)
        val sensSeek = SeekBar(context).apply {
            max = 500
            progress = (model.sensitivity * 100).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    sText.text = "Sensitivity: ${p / 100f}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        sText.text = "Sensitivity: ${sensSeek.progress / 100f}"

        dlg.addView(sText)
        dlg.addView(sensSeek)
        dlg.addView(gapCreator(8))

        return sensSeek
    }

    /**
     * Add hold toggle control for buttons
     */
    private fun addHoldToggleControl(dlg: LinearLayout, gapCreator: (Int) -> Space): CheckBox {
        val chkHold = CheckBox(context).apply {
            text = "Hold toggles"
            isChecked = model.holdToggle
            visibility = if (model.type == ControlType.BUTTON) View.VISIBLE else View.GONE
        }

        val etMs = EditText(context).apply {
            hint = "ms"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(model.holdDurationMs.toString())
            visibility = chkHold.visibility
        }

        dlg.addView(chkHold)
        dlg.addView(etMs)
        dlg.addView(gapCreator(8))

        return chkHold
    }

    /**
     * Add auto-center control for sticks/touchpads
     */
    private fun addAutoCenterControl(dlg: LinearLayout, gapCreator: (Int) -> Space): CheckBox {
        val chkAuto = CheckBox(context).apply {
            text = "Auto-center"
            isChecked = model.autoCenter
            visibility = if (model.type != ControlType.BUTTON) View.VISIBLE else View.GONE
        }

        dlg.addView(chkAuto)
        dlg.addView(gapCreator(8))

        return chkAuto
    }

    /**
     * Add touchpad-specific controls
     */
    private fun addTouchpadControls(dlg: LinearLayout, gapCreator: (Int) -> Space): CheckBox {
        // Only show for touchpad controls
        val isTouchpad = model.type == ControlType.TOUCHPAD
        var visibility = if (isTouchpad) View.VISIBLE else View.GONE

        // Hold left mouse button while touching
        val chkDrag = CheckBox(context).apply {
            text = "Hold left while finger is down"
            isChecked = model.holdLeftWhileTouch
            visibility = visibility
        }
        dlg.addView(chkDrag)
        dlg.addView(gapCreator(8))

        // Toggle left click mode
        val chkToggleClick = CheckBox(context).apply {
            text = "Toggle left click mode (click-lock)"
            isChecked = model.toggleLeftClick
            visibility = visibility
        }
        dlg.addView(chkToggleClick)
        dlg.addView(gapCreator(8))

        // Make options mutually exclusive
        if (isTouchpad) {
            chkDrag.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && chkToggleClick.isChecked) {
                    chkToggleClick.isChecked = false
                }
            }

            chkToggleClick.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && chkDrag.isChecked) {
                    chkDrag.isChecked = false
                }
            }
        }

        return chkDrag
    }

    /**
     * Add swipe activation control for buttons
     */
    private fun addSwipeActivationControl(dlg: LinearLayout, gapCreator: (Int) -> Space): CheckBox {
        val chkSwipe = CheckBox(context).apply {
            text = "Enable swipe activation"
            isChecked = model.swipeActivate
            visibility = if (model.type == ControlType.BUTTON) View.VISIBLE else View.GONE
        }

        dlg.addView(chkSwipe)
        dlg.addView(gapCreator(8))

        return chkSwipe
    }

    /**
     * Add directional mode controls for sticks
     */
    private data class DirectionalControlData(
        val chkDirectional: CheckBox,
        val directionalContainer: LinearLayout,
        val wSeek: SeekBar,
        val hSeek: SeekBar,
        val etMs: EditText,
        val chkToggleClick: CheckBox
    )

    private fun addDirectionalModeControls(dlg: LinearLayout, gapCreator: (Int) -> Space): DirectionalControlData {
        // Find existing controls or create new ones - using vars instead of vals
        var sizeControls = dlg.findViewWithTag<SeekBar>("width_seek") ?: SeekBar(context)
        var heightSeek = dlg.findViewWithTag<SeekBar>("height_seek") ?: SeekBar(context)

        // Hold duration input (for button hold toggle) - using var instead of val
        var etMs = dlg.findViewWithTag<EditText>("hold_duration") ?: EditText(context).apply {
            setText(model.holdDurationMs.toString())
        }

        // Toggle click checkbox (for touchpad toggle mode)
        val chkToggleClick = dlg.findViewWithTag<CheckBox>("toggle_click") ?: CheckBox(context).apply {
            isChecked = model.toggleLeftClick
        }

        // Only add directional mode for sticks
        val isStick = model.type == ControlType.STICK

        // Directional mode checkbox
        val chkDirectional = CheckBox(context).apply {
            text = "Directional mode (WASD style)"
            isChecked = model.directionalMode
            visibility = if (isStick) View.VISIBLE else View.GONE
            tag = "directional_mode"
        }
        dlg.addView(chkDirectional)

        // Container for directional mode settings
        val directionalContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isStick && model.directionalMode) View.VISIBLE else View.GONE
            tag = "directional_container"
        }
        dlg.addView(directionalContainer)

        // Only add detailed controls if this is a stick
        if (isStick) {
            // Update visibility when directional mode is toggled
            chkDirectional.setOnCheckedChangeListener { _, isChecked ->
                directionalContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            }

            // Add directional commands fields
            addDirectionalCommandsUI(directionalContainer, gapCreator)
        }

        return DirectionalControlData(
            chkDirectional,
            directionalContainer,
            sizeControls,
            heightSeek,
            etMs,
            chkToggleClick
        )
    }

    /**
     * Add payload control with autocomplete
     */
    private fun addPayloadControl(dlg: LinearLayout): AutoCompleteTextView {
        val etPayload = AutoCompleteTextView(context).apply {
            hint = "payload (comma-sep)"
            setText(model.payload)
            inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS

            // Set up autocomplete suggestions
            val suggestions = arrayOf(
                "A_PRESSED", "B_PRESSED", "X_PRESSED", "Y_PRESSED",
                "START", "SELECT", "UP", "DOWN", "LEFT", "RIGHT"
            )
            setAdapter(
                ArrayAdapter(
                    context,
                    android.R.layout.simple_dropdown_item_1line,
                    suggestions
                )
            )
        }

        dlg.addView(etPayload)
        return etPayload
    }

    /**
     * Add directional commands UI elements to the container
     * Creates fields for all the directional commands and thresholds
     */
    private fun addDirectionalCommandsUI(
        directionalContainer: LinearLayout,
        gapCreator: (Int) -> Space
    ) {
        // Title
        directionalContainer.addView(TextView(context).apply {
            text = "Directional Commands"
            setPadding(0, 16, 0, 8)
            setTypeface(null, Typeface.BOLD)
        })

        // Basic direction commands
        addDirectionalCommandField(directionalContainer, "Up command:", model.upCommand, "W", "up_command", gapCreator)
        addDirectionalCommandField(directionalContainer, "Down command:", model.downCommand, "S", "down_command", gapCreator)
        addDirectionalCommandField(directionalContainer, "Left command:", model.leftCommand, "A", "left_command", gapCreator)
        addDirectionalCommandField(directionalContainer, "Right command:", model.rightCommand, "D", "right_command", gapCreator)
        directionalContainer.addView(gapCreator(16))

        // Boost threshold slider and input
        addThresholdControl(
            directionalContainer,
            "Regular Boost threshold:",
            model.boostThreshold,
            "boost_threshold",
            null,
            gapCreator
        )

        // Super boost threshold slider and input
        addThresholdControl(
            directionalContainer,
            "Super Boost threshold:",
            model.superBoostThreshold,
            "super_boost_threshold",
            "boost_threshold", // Reference to regular threshold for ensuring super > regular
            gapCreator
        )

        // Regular boost commands
        directionalContainer.addView(TextView(context).apply {
            text = "Regular Boost Commands"
            setPadding(0, 8, 0, 8)
            setTypeface(null, Typeface.BOLD)
        })

        addDirectionalCommandField(directionalContainer, "Up boost command:", model.upBoostCommand, "W,SHIFT", "up_boost", gapCreator)
        addDirectionalCommandField(directionalContainer, "Down boost command:", model.downBoostCommand, "S,CTRL", "down_boost", gapCreator)
        addDirectionalCommandField(directionalContainer, "Left boost command:", model.leftBoostCommand, "A,SHIFT", "left_boost", gapCreator)
        addDirectionalCommandField(directionalContainer, "Right boost command:", model.rightBoostCommand, "D,SHIFT", "right_boost", gapCreator)
        directionalContainer.addView(gapCreator(16))

        // Super boost commands
        directionalContainer.addView(TextView(context).apply {
            text = "Super Boost Commands"
            setPadding(0, 8, 0, 8)
            setTypeface(null, Typeface.BOLD)
        })

        addDirectionalCommandField(directionalContainer, "Up super boost command:", model.upSuperBoostCommand, "W,SHIFT,SPACE", "up_super_boost", gapCreator)
        addDirectionalCommandField(directionalContainer, "Down super boost command:", model.downSuperBoostCommand, "S,CTRL,SPACE", "down_super_boost", gapCreator)
        addDirectionalCommandField(directionalContainer, "Left super boost command:", model.leftSuperBoostCommand, "A,SHIFT,SPACE", "left_super_boost", gapCreator)
        addDirectionalCommandField(directionalContainer, "Right super boost command:", model.rightSuperBoostCommand, "D,SHIFT,SPACE", "right_super_boost", gapCreator)
    }

    /**
     * Create a threshold control with slider and direct input
     *
     * @param container The container to add controls to
     * @param labelText The label for this threshold
     * @param currentValue The current threshold value
     * @param tagPrefix Tag prefix to use for the controls
     * @param referenceThresholdTag Optional tag of another threshold to keep in relation with
     * @param gapCreator Function to create spacing between elements
     */
    private fun addThresholdControl(
        container: LinearLayout,
        labelText: String,
        currentValue: Float,
        tagPrefix: String,
        referenceThresholdTag: String?,
        gapCreator: (Int) -> Space
    ) {
        container.addView(TextView(context).apply { text = labelText })

        // Row to hold the slider and input
        val thresholdRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Text showing current value
        val thresholdText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = 8
            }
            tag = "${tagPrefix}_text"
        }

        // Direct edit field
        val thresholdEdit = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                width = 100
                gravity = Gravity.CENTER_VERTICAL
            }

            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.2f".format(currentValue))
            tag = "${tagPrefix}_edit"
        }

        // Create the slider
        val thresholdSeek = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,  // 0 width with weight means "take remaining space"
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { weight = 1f }

            max = 90  // 0.1 to 1.0 in steps of 0.01
            progress = ((currentValue - 0.1f) * 100).roundToInt().coerceIn(0, 90)
            tag = tagPrefix

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    val value = 0.1f + (p / 100f)
                    thresholdText.text = "%.2f".format(value)
                    thresholdEdit.setText("%.2f".format(value))

                    // If this is the super threshold, ensure it's higher than regular
                    if (tagPrefix == "super_boost_threshold" && referenceThresholdTag != null) {
                        val regularThreshold = container.findViewWithTag<SeekBar>(referenceThresholdTag)
                        if (regularThreshold != null && value <= 0.1f + (regularThreshold.progress / 100f)) {
                            regularThreshold.progress = ((value - 0.15f) * 100).toInt().coerceIn(0, 90)
                        }
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        // Set initial text value
        thresholdText.text = "%.2f".format(0.1f + (thresholdSeek.progress / 100f))

        // Add update handler for direct editing
        thresholdEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                try {
                    val value = thresholdEdit.text.toString().toFloat()
                    val validValue = value.coerceIn(0.1f, 1.0f)
                    val progress = ((validValue - 0.1f) * 100).roundToInt().coerceIn(0, 90)
                    thresholdSeek.progress = progress

                    // Handle relationships between thresholds if needed
                    if (tagPrefix == "super_boost_threshold" && referenceThresholdTag != null) {
                        val regularThreshold = container.findViewWithTag<SeekBar>(referenceThresholdTag)
                        if (regularThreshold != null) {
                            // Make sure regular threshold is lower
                            val regularValue = 0.1f + (regularThreshold.progress / 100f)
                            if (validValue <= regularValue) {
                                regularThreshold.progress = ((validValue - 0.15f) * 100).toInt().coerceIn(0, 90)
                            }
                        }
                    }
                } catch (e: NumberFormatException) {
                    // Reset to current slider value if invalid input
                    thresholdEdit.setText("%.2f".format(0.1f + (thresholdSeek.progress) / 100f))
                }
            }
        }

        // Add all components to the row
        thresholdRow.addView(thresholdText)
        thresholdRow.addView(thresholdSeek)
        thresholdRow.addView(thresholdEdit)

        // Add the row to the container
        container.addView(thresholdRow)
        container.addView(gapCreator(16))
    }

    /**
     * Helper function to add a directional command field
     */
    private fun addDirectionalCommandField(
        container: LinearLayout,
        labelText: String,
        currentValue: String,
        hintText: String,
        tagValue: String,
        gapCreator: (Int) -> Space
    ) {
        container.addView(TextView(context).apply { text = labelText })
        val editText = EditText(context).apply {
            hint = hintText
            setText(currentValue)
            tag = tagValue
        }
        container.addView(editText)
        container.addView(gapCreator(8))
    }

    /**
     * Update directional mode settings from UI components
     * Reads values from all the UI components and updates the control model
     */
    private fun updateDirectionalModeSettings(directionalContainer: LinearLayout) {
        // Basic directional commands
        directionalContainer.findViewWithTag<EditText>("up_command")?.let {
            model.upCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "W"
        }
        directionalContainer.findViewWithTag<EditText>("down_command")?.let {
            model.downCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "S"
        }
        directionalContainer.findViewWithTag<EditText>("left_command")?.let {
            model.leftCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "A"
        }
        directionalContainer.findViewWithTag<EditText>("right_command")?.let {
            model.rightCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "D"
        }

        // Thresholds
        directionalContainer.findViewWithTag<SeekBar>("boost_threshold")?.let {
            model.boostThreshold = 0.1f + (it.progress / 100f)
        } ?: directionalContainer.findViewWithTag<EditText>("boost_threshold_edit")?.let {
            try {
                model.boostThreshold = it.text.toString().toFloat().coerceIn(0.1f, 1.0f)
            } catch (e: NumberFormatException) {
                // Use default if parse fails
                model.boostThreshold = 0.5f
            }
        }

        directionalContainer.findViewWithTag<SeekBar>("super_boost_threshold")?.let {
            model.superBoostThreshold = 0.1f + (it.progress / 100f)
        } ?: directionalContainer.findViewWithTag<EditText>("super_boost_threshold_edit")?.let {
            try {
                model.superBoostThreshold = it.text.toString().toFloat().coerceIn(0.1f, 1.0f)
            } catch (e: NumberFormatException) {
                // Use default if parse fails
                model.superBoostThreshold = 0.75f
            }
        }

        // Boost commands
        directionalContainer.findViewWithTag<EditText>("up_boost")?.let {
            model.upBoostCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "W,SHIFT"
        }
        directionalContainer.findViewWithTag<EditText>("down_boost")?.let {
            model.downBoostCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "S,CTRL"
        }
        directionalContainer.findViewWithTag<EditText>("left_boost")?.let {
            model.leftBoostCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "A,SHIFT"
        }
        directionalContainer.findViewWithTag<EditText>("right_boost")?.let {
            model.rightBoostCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "D,SHIFT"
        }

        // Super boost commands
        directionalContainer.findViewWithTag<EditText>("up_super_boost")?.let {
            model.upSuperBoostCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "W,SHIFT,SPACE"
        }
        directionalContainer.findViewWithTag<EditText>("down_super_boost")?.let {
            model.downSuperBoostCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "S,CTRL,SPACE"
        }
        directionalContainer.findViewWithTag<EditText>("left_super_boost")?.let {
            model.leftSuperBoostCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "A,SHIFT,SPACE"
        }
        directionalContainer.findViewWithTag<EditText>("right_super_boost")?.let {
            model.rightSuperBoostCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "D,SHIFT,SPACE"
        }
    }
}