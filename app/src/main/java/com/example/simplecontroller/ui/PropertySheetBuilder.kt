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
import androidx.core.content.ContextCompat
import androidx.core.view.children
import com.example.simplecontroller.MainActivity
import com.example.simplecontroller.R

/**
 * Builds property sheet dialogs for controls.
 *
 * This class encapsulates the dialog creation and property management for all control types.
 * It creates different UI elements based on the control type (Button, Stick, Touchpad) and
 * handles saving the updated properties back to the control model.
 */
class PropertySheetBuilder(
    private val context: Context,
    private val model: Control,
    private val onPropertiesUpdated: () -> Unit
) {
    // UI components that need to be accessible across methods
    private data class UIComponents(
        val nameField: EditText,
        val widthSeek: SeekBar,
        val heightSeek: SeekBar,
        val sensitivitySeek: SeekBar?,
        val holdToggle: CheckBox,
        val autoCenter: CheckBox,
        val holdDurationField: EditText,
        val swipeActivate: CheckBox,
        val holdLeftWhileTouch: CheckBox,
        val doubleTapClickLock: CheckBox,   // NEW
        val toggleLeftClick: CheckBox,
        val directionalMode: CheckBox,
        val stickPlusMode: CheckBox,
        val directionalContainer: LinearLayout,
        val payloadField: AutoCompleteTextView
    )

    /**
     * Shows the property sheet dialog with all appropriate UI elements for this control type.
     */
    fun showPropertySheet() {
        // Create scrollable dialog container
        val scrollView = createScrollView()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
            setBackgroundColor(ContextCompat.getColor(context, R.color.dark_surface))
        }
        scrollView.addView(container)

        // Build all UI components
        val components = buildUIComponents(container)

        // Add a horizontal row of buttons for Delete and Duplicate
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        // Duplicate button
        val btnDuplicate = Button(context).apply {
            text = "Duplicate"
            setTextColor(ContextCompat.getColor(context, R.color.primary_blue))
            setOnClickListener {
                val copy = model.copy(
                    id = "${model.id}_copy_${System.currentTimeMillis()}",
                    x = model.x + 40f,
                    y = model.y + 40f
                )
                (context as? MainActivity)?.createControlFrom(copy)
            }
        }

        // Delete button
        val btnDelete = Button(context).apply {
            text = "Delete"
            setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_light))
            setOnClickListener {
                (context as? MainActivity)?.removeControl(model)
            }
        }

        buttonRow.addView(btnDuplicate)
        buttonRow.addView(btnDelete)
        container.addView(buttonRow)

        // Create and show the dialog
        val alertDialog = AlertDialog.Builder(context)
            .setTitle("Properties")
            .setView(scrollView)
            .setPositiveButton("OK") { _, _ -> saveProperties(components) }
            .setNegativeButton("Cancel", null)
            .create()

        alertDialog.window?.setBackgroundDrawableResource(R.color.dark_surface)
        alertDialog.show()

        // Style dialog buttons
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            ContextCompat.getColor(context, R.color.primary_blue)
        )
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
            ContextCompat.getColor(context, R.color.primary_blue)
        )
    }

    /**
     * Apply dark theme styling to dialog components
     */
    private fun applyThemeStylingToComponents(components: UIComponents) {
        // Apply text colors
        components.nameField.setTextColor(ThemeManager.getTextColor(context))
        components.nameField.setHintTextColor(ContextCompat.getColor(context, R.color.dark_text_secondary))

        components.payloadField.setTextColor(ThemeManager.getTextColor(context))
        components.payloadField.setHintTextColor(ContextCompat.getColor(context, R.color.dark_text_secondary))

        // Handle optional fields
        components.sensitivitySeek?.progressTintList =
            ContextCompat.getColorStateList(context, R.color.primary_blue)
        components.sensitivitySeek?.thumbTintList =
            ContextCompat.getColorStateList(context, R.color.accent_blue)

        // Style checkboxes
        components.holdToggle.setTextColor(ThemeManager.getTextColor(context))
        components.holdToggle.buttonTintList =
            ContextCompat.getColorStateList(context, R.color.primary_blue)

        components.autoCenter.setTextColor(ThemeManager.getTextColor(context))
        components.autoCenter.buttonTintList =
            ContextCompat.getColorStateList(context, R.color.primary_blue)

        components.swipeActivate.setTextColor(ThemeManager.getTextColor(context))
        components.swipeActivate.buttonTintList =
            ContextCompat.getColorStateList(context, R.color.primary_blue)

        components.holdLeftWhileTouch.setTextColor(ThemeManager.getTextColor(context))
        components.holdLeftWhileTouch.buttonTintList =
            ContextCompat.getColorStateList(context, R.color.primary_blue)

        components.doubleTapClickLock.setTextColor(ThemeManager.getTextColor(context))
        components.doubleTapClickLock.buttonTintList =
            ContextCompat.getColorStateList(context, R.color.primary_blue)

        components.toggleLeftClick.setTextColor(ThemeManager.getTextColor(context))
        components.toggleLeftClick.buttonTintList =
            ContextCompat.getColorStateList(context, R.color.primary_blue)

        components.directionalMode.setTextColor(ThemeManager.getTextColor(context))
        components.directionalMode.buttonTintList =
            ContextCompat.getColorStateList(context, R.color.primary_blue)

        // Style duration field
        components.holdDurationField.setTextColor(ThemeManager.getTextColor(context))
        components.holdDurationField.setHintTextColor(
            ContextCompat.getColor(context, R.color.dark_text_secondary)
        )
    }

    /**
     * Creates a ScrollView for the dialog content
     */
    private fun createScrollView() = ScrollView(context).apply {
        val metrics = context.resources.displayMetrics
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (metrics.heightPixels * 0.7).toInt()
        )
    }
    
    /**
     * Helper for creating space between components
     */
    private fun createGap(height: Int = 8) = Space(context).apply { 
        minimumHeight = height 
    }
    
    /**
     * Builds all UI components for the property sheet
     */
    private fun buildUIComponents(container: LinearLayout): UIComponents {
        // Basic properties
        val nameField = addTextField(container, model.name, "Label")
        
        // Size controls
        val (widthSeek, heightSeek) = addSizeControls(container)
        
        // Sensitivity (for stick/touchpad)
        val sensitivitySeek = if (model.type != ControlType.BUTTON) {
            addSeekBarWithLabel(
                container, 
                "Sensitivity: ${(model.sensitivity * 100).roundToInt() / 100f}",
                500,
                (model.sensitivity * 100).roundToInt(),
                { "Sensitivity: ${it / 100f}" }
            )
        } else null
        
        // Button-specific controls
        val holdToggle = addCheckBox(
            container, 
            "Hold toggles", 
            model.holdToggle,
            model.type == ControlType.BUTTON
        )
        
        val holdDurationField = addTextField(
            container,
            model.holdDurationMs.toString(),
            "ms",
            InputType.TYPE_CLASS_NUMBER,
            model.type == ControlType.BUTTON
        )
        
        val swipeActivate = addCheckBox(
            container,
            "Enable swipe activation",
            model.swipeActivate,
            model.type == ControlType.BUTTON
        )
        
        // Stick/Touchpad controls
        val autoCenter = addCheckBox(
            container,
            "Auto-center",
            model.autoCenter,
            model.type != ControlType.BUTTON
        )
        
        // Touchpad-specific controls
        val isTouchpad = model.type == ControlType.TOUCHPAD
        val holdLeftWhileTouch = addCheckBox(
            container,
            "Hold left while finger is down",
            model.holdLeftWhileTouch,
            isTouchpad
        )
        
        val toggleLeftClick = addCheckBox(
            container,
            "Toggle left click mode (click-lock)",
            model.toggleLeftClick,
            isTouchpad
        )

        /* NEW: Double-tap click-lock (Unified Remote style) */
        val doubleTapClickLock = addCheckBox(
            container,
            "Double-tap click-lock (UR style)",
            model.doubleTapClickLock,
            isTouchpad
        )

// Make the three touchpad click modes mutually exclusive
        if (isTouchpad) {
            setupMutuallyExclusiveOptions(holdLeftWhileTouch, toggleLeftClick)
            setupMutuallyExclusiveOptions(holdLeftWhileTouch, doubleTapClickLock)
            setupMutuallyExclusiveOptions(toggleLeftClick, doubleTapClickLock)
        }
        
        // Directional mode (for sticks)
        val isStick = model.type == ControlType.STICK
        val directionalMode = addCheckBox(
            container,
            "Directional mode (WASD style)",
            model.directionalMode,
            isStick
        )
        
        // Stick+ mode (for sticks)
        val stickPlusMode = addCheckBox(
            container,
            "Stick+ mode (Analog + directional buttons)",
            model.stickPlusMode,
            isStick
        )
        
        // Container for directional settings
        val directionalContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isStick && (model.directionalMode || model.stickPlusMode)) View.VISIBLE else View.GONE
            tag = "directional_container"
        }
        container.addView(directionalContainer)
        
        // Setup visibility toggle for directional container
        if (isStick) {
            val updateDirectionalVisibility = {
                val shouldShow = directionalMode.isChecked || stickPlusMode.isChecked
                directionalContainer.visibility = if (shouldShow) View.VISIBLE else View.GONE
            }
            
            directionalMode.setOnCheckedChangeListener { _, _ -> updateDirectionalVisibility() }
            stickPlusMode.setOnCheckedChangeListener { _, _ -> updateDirectionalVisibility() }
            
            // Make directional mode and stick+ mode mutually exclusive
            setupMutuallyExclusiveOptions(directionalMode, stickPlusMode)
            
            // Add directional command fields
            addDirectionalCommandsUI(directionalContainer)
        }
        
        // Payload field
        val payloadField = addPayloadControl(container)
        
        return UIComponents(
            nameField, widthSeek, heightSeek, sensitivitySeek,
            holdToggle, autoCenter, holdDurationField, swipeActivate,
            holdLeftWhileTouch, doubleTapClickLock, toggleLeftClick, directionalMode, stickPlusMode,
            directionalContainer, payloadField
        )
    }
    
    /**
     * Add standard text field
     */
    private fun addTextField(
        container: LinearLayout,
        initialValue: String,
        hint: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        visible: Boolean = true
    ): EditText {
        return EditText(context).apply {
            setText(initialValue)
            this.hint = hint
            this.inputType = inputType
            visibility = if (visible) View.VISIBLE else View.GONE
            container.addView(this)
            container.addView(createGap())
        }
    }
    
    /**
     * Add standard checkbox
     */
    private fun addCheckBox(
        container: LinearLayout,
        text: String,
        isChecked: Boolean,
        visible: Boolean = true,
        tag: String? = null
    ): CheckBox {
        return CheckBox(context).apply {
            this.text = text
            this.isChecked = isChecked
            visibility = if (visible) View.VISIBLE else View.GONE
            if (tag != null) this.tag = tag
            container.addView(this)
            container.addView(createGap())
        }
    }
    
    /**
     * Setup mutually exclusive checkboxes
     */
    private fun setupMutuallyExclusiveOptions(option1: CheckBox, option2: CheckBox) {
        option1.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && option2.isChecked) {
                option2.isChecked = false
            }
        }
        
        option2.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && option1.isChecked) {
                option1.isChecked = false
            }
        }
    }
    
    /**
     * Add width and height sliders
     */
    private fun addSizeControls(container: LinearLayout): Pair<SeekBar, SeekBar> {
        // Determine max size based on control type - touchpads can be much larger
        val maxSize = if (model.type == ControlType.TOUCHPAD) 1200 else 600
        
        // Width control
        val widthText = TextView(context)
        val widthSeek = addSeekBarWithLabel(
            container, 
            "Width: ${model.w.roundToInt()} px",
            maxSize,
            model.w.roundToInt().coerceIn(40, maxSize),
            { "Width: $it px" },
            widthText
        )
        
        // Height control
        val heightText = TextView(context)
        val heightSeek = addSeekBarWithLabel(
            container, 
            "Height: ${model.h.roundToInt()} px",
            maxSize,
            model.h.roundToInt().coerceIn(40, maxSize),
            { "Height: $it px" },
            heightText
        )
        
        return Pair(widthSeek, heightSeek)
    }
    
    /**
     * Add a seekbar with a label that updates
     */
    private fun addSeekBarWithLabel(
        container: LinearLayout,
        initialLabelText: String,
        maxValue: Int,
        initialProgress: Int,
        labelUpdater: (Int) -> String,
        textView: TextView? = null
    ): SeekBar {
        // Create label if not provided
        val label = textView ?: TextView(context).apply {
            container.addView(this)
        }
        label.text = initialLabelText
        
        // Create seekbar
        val seekBar = SeekBar(context).apply {
            max = maxValue
            progress = initialProgress
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    label.text = labelUpdater(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        
        container.addView(seekBar)
        container.addView(createGap())
        
        return seekBar
    }
    
    /**
     * Add payload control with autocomplete
     */
    private fun addPayloadControl(container: LinearLayout): AutoCompleteTextView {
        return AutoCompleteTextView(context).apply {
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
            
            container.addView(this)
        }
    }
    
    /**
     * Add directional controls UI with threshold sliders and command fields
     */
    private fun addDirectionalCommandsUI(container: LinearLayout) {
        addSectionTitle(container, "Directional Commands")
        
        // Basic direction commands
        addLabeledTextField(container, "Up command:", model.upCommand, "W", "up_command")
        addLabeledTextField(container, "Down command:", model.downCommand, "S", "down_command")
        addLabeledTextField(container, "Left command:", model.leftCommand, "A", "left_command")
        addLabeledTextField(container, "Right command:", model.rightCommand, "D", "right_command")
        container.addView(createGap(16))
        
        // Thresholds
        addThresholdControl(
            container, 
            "Regular Boost threshold:", 
            model.boostThreshold, 
            "boost_threshold"
        )
        
        addThresholdControl(
            container, 
            "Super Boost threshold:", 
            model.superBoostThreshold, 
            "super_boost_threshold", 
            "boost_threshold"
        )
        
        // Regular boost commands
        addSectionTitle(container, "Regular Boost Commands")
        addLabeledTextField(container, "Up boost command:", model.upBoostCommand, "W,SHIFT", "up_boost")
        addLabeledTextField(container, "Down boost command:", model.downBoostCommand, "S,CTRL", "down_boost")
        addLabeledTextField(container, "Left boost command:", model.leftBoostCommand, "A,SHIFT", "left_boost")
        addLabeledTextField(container, "Right boost command:", model.rightBoostCommand, "D,SHIFT", "right_boost")
        container.addView(createGap(16))
        
        // Super boost commands
        addSectionTitle(container, "Super Boost Commands")
        addLabeledTextField(container, "Up super boost command:", model.upSuperBoostCommand, "W,SHIFT,SPACE", "up_super_boost")
        addLabeledTextField(container, "Down super boost command:", model.downSuperBoostCommand, "S,CTRL,SPACE", "down_super_boost")
        addLabeledTextField(container, "Left super boost command:", model.leftSuperBoostCommand, "A,SHIFT,SPACE", "left_super_boost")
        addLabeledTextField(container, "Right super boost command:", model.rightSuperBoostCommand, "D,SHIFT,SPACE", "right_super_boost")
    }
    
    /**
     * Add a section title
     */
    private fun addSectionTitle(container: LinearLayout, title: String) {
        container.addView(TextView(context).apply {
            text = title
            setPadding(0, 16, 0, 8)
            setTypeface(null, Typeface.BOLD)
        })
    }
    
    /**
     * Add a labeled text field
     */
    private fun addLabeledTextField(
        container: LinearLayout,
        labelText: String,
        value: String,
        hint: String,
        tag: String
    ) {
        container.addView(TextView(context).apply { text = labelText })
        container.addView(EditText(context).apply {
            setText(value)
            this.hint = hint
            this.tag = tag
        })
        container.addView(createGap())
    }
    
    /**
     * Add a threshold control with slider and direct input
     */
    private fun addThresholdControl(
        container: LinearLayout,
        labelText: String,
        currentValue: Float,
        tagPrefix: String,
        referenceThresholdTag: String? = null
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
                    
                    // Handle relationship with reference threshold if needed
                    handleThresholdRelationship(container, tagPrefix, referenceThresholdTag, value)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        
        // Set initial text value
        thresholdText.text = "%.2f".format(0.1f + (thresholdSeek.progress / 100f))
        
        // Add update handler for direct editing
        setupThresholdDirectEdit(thresholdEdit, thresholdSeek, container, tagPrefix, referenceThresholdTag)
        
        // Add all components to the row
        thresholdRow.addView(thresholdText)
        thresholdRow.addView(thresholdSeek)
        thresholdRow.addView(thresholdEdit)
        
        // Add the row to the container
        container.addView(thresholdRow)
        container.addView(createGap(16))
    }
    
    /**
     * Handle relationship between threshold controls
     */
    private fun handleThresholdRelationship(
        container: LinearLayout,
        tagPrefix: String,
        referenceThresholdTag: String?,
        value: Float
    ) {
        if (tagPrefix == "super_boost_threshold" && referenceThresholdTag != null) {
            val regularThreshold = container.findViewWithTag<SeekBar>(referenceThresholdTag)
            if (regularThreshold != null && value <= 0.1f + (regularThreshold.progress / 100f)) {
                regularThreshold.progress = ((value - 0.15f) * 100).toInt().coerceIn(0, 90)
            }
        }
    }
    
    /**
     * Setup direct editing for threshold controls
     */
    private fun setupThresholdDirectEdit(
        edit: EditText,
        seekBar: SeekBar,
        container: LinearLayout,
        tagPrefix: String,
        referenceThresholdTag: String?
    ) {
        edit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                try {
                    val value = edit.text.toString().toFloat()
                    val validValue = value.coerceIn(0.1f, 1.0f)
                    val progress = ((validValue - 0.1f) * 100).roundToInt().coerceIn(0, 90)
                    seekBar.progress = progress
                    
                    // Handle relationships between thresholds if needed
                    handleThresholdRelationship(container, tagPrefix, referenceThresholdTag, validValue)
                } catch (e: NumberFormatException) {
                    // Reset to current slider value if invalid input
                    edit.setText("%.2f".format(0.1f + (seekBar.progress) / 100f))
                }
            }
        }
    }
    
    /**
     * Save all properties from UI components to the model
     */
    private fun saveProperties(components: UIComponents) {
        // Save common properties
        model.name = components.nameField.text.toString()
        model.w = components.widthSeek.progress.toFloat().coerceAtLeast(40f)
        model.h = components.heightSeek.progress.toFloat().coerceAtLeast(40f)
        model.payload = components.payloadField.text.toString().trim()
        
        // Button-specific properties
        if (model.type == ControlType.BUTTON) {
            model.holdToggle = components.holdToggle.isChecked
            model.holdDurationMs = components.holdDurationField.text.toString().toLongOrNull() ?: 400L
            model.swipeActivate = components.swipeActivate.isChecked
        }
        
        // Stick/Touchpad properties
        if (model.type != ControlType.BUTTON) {
            model.autoCenter = components.autoCenter.isChecked
            components.sensitivitySeek?.let { model.sensitivity = it.progress / 100f }
        }
        
        // Touchpad-specific properties
        if (model.type == ControlType.TOUCHPAD) {
            model.holdLeftWhileTouch = components.holdLeftWhileTouch.isChecked
            model.toggleLeftClick = components.toggleLeftClick.isChecked
            model.doubleTapClickLock = components.doubleTapClickLock.isChecked   // NEW
        }
        
        // Stick-specific directional mode properties
        if (model.type == ControlType.STICK) {
            model.directionalMode = components.directionalMode.isChecked
            model.stickPlusMode = components.stickPlusMode.isChecked
            
            if (model.directionalMode || model.stickPlusMode) {
                updateDirectionalModeSettings(components.directionalContainer)
            }
        }
        
        // Notify that properties have been updated
        onPropertiesUpdated()

        // Ensure both width and height are updated on the actual view
        val parentView = (context as? MainActivity)
            ?.findViewById<FrameLayout>(R.id.canvas)
            ?.children
            ?.filterIsInstance<ControlView>()
            ?.find { it.model.id == model.id }

        parentView?.let {
            val lp = it.layoutParams as ViewGroup.MarginLayoutParams
            lp.width = model.w.toInt()
            lp.height = model.h.toInt()
            it.layoutParams = lp
            it.invalidate()
        }
    }
    
    /**
     * Update directional mode settings from UI components
     */
    private fun updateDirectionalModeSettings(directionalContainer: LinearLayout) {
        // Basic directional commands - allow empty strings
        readTextFieldIntoModel(directionalContainer, "up_command") { model.upCommand = it }
        readTextFieldIntoModel(directionalContainer, "down_command") { model.downCommand = it }
        readTextFieldIntoModel(directionalContainer, "left_command") { model.leftCommand = it }
        readTextFieldIntoModel(directionalContainer, "right_command") { model.rightCommand = it }
        
        // Thresholds
        readThresholdValue(directionalContainer, "boost_threshold", "boost_threshold_edit") { model.boostThreshold = it }
        readThresholdValue(directionalContainer, "super_boost_threshold", "super_boost_threshold_edit") { model.superBoostThreshold = it }
        
        // Boost commands - allow empty strings
        readTextFieldIntoModel(directionalContainer, "up_boost") { model.upBoostCommand = it }
        readTextFieldIntoModel(directionalContainer, "down_boost") { model.downBoostCommand = it }
        readTextFieldIntoModel(directionalContainer, "left_boost") { model.leftBoostCommand = it }
        readTextFieldIntoModel(directionalContainer, "right_boost") { model.rightBoostCommand = it }
        
        // Super boost commands - allow empty strings
        readTextFieldIntoModel(directionalContainer, "up_super_boost") { model.upSuperBoostCommand = it }
        readTextFieldIntoModel(directionalContainer, "down_super_boost") { model.downSuperBoostCommand = it }
        readTextFieldIntoModel(directionalContainer, "left_super_boost") { model.leftSuperBoostCommand = it }
        readTextFieldIntoModel(directionalContainer, "right_super_boost") { model.rightSuperBoostCommand = it }
    }
    
    /**
     * Helper to read text field value into model
     */
    private fun readTextFieldIntoModel(container: LinearLayout, tag: String, setter: (String) -> Unit) {
        container.findViewWithTag<EditText>(tag)?.let {
            setter(it.text.toString())
        }
    }
    
    /**
     * Helper to read threshold value from seekbar or edit field
     */
    private fun readThresholdValue(container: LinearLayout, seekBarTag: String, editTag: String, setter: (Float) -> Unit) {
        container.findViewWithTag<SeekBar>(seekBarTag)?.let {
            setter(0.1f + (it.progress / 100f))
        } ?: container.findViewWithTag<EditText>(editTag)?.let {
            try {
                setter(it.text.toString().toFloat().coerceIn(0.1f, 1.0f))
            } catch (e: NumberFormatException) {
                // Use default if parse fails
                setter(if (seekBarTag.contains("super")) 0.75f else 0.5f)
            }
        }
    }
    
    /**
     * Extension function to use string if not blank or return null
     */
    private fun String.takeIfNotBlank() = takeIf { it.isNotBlank() }
}