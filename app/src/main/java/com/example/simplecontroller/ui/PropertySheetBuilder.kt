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
import com.example.simplecontroller.model.ButtonAimMouseProfile
import com.example.simplecontroller.model.ButtonAimDpadMode
import com.example.simplecontroller.model.ButtonAimDpadOrigin
import com.example.simplecontroller.model.ButtonAimOutput
import com.example.simplecontroller.model.ButtonAimPayloadTiming
import com.example.simplecontroller.model.ButtonAimStickProfile
import com.example.simplecontroller.model.TouchAimOutput
import com.example.simplecontroller.model.TouchAimMode
import com.example.simplecontroller.model.TouchAimShootBehavior
import com.example.simplecontroller.model.TouchStageAction
import com.example.simplecontroller.model.PageAction
import com.example.simplecontroller.model.pageActionNeedsTarget
import com.example.simplecontroller.model.applyTo
import com.example.simplecontroller.model.ButtonAimProfile
import com.example.simplecontroller.model.StickDirectionalProfile
import com.example.simplecontroller.model.StickDirectionalProfileMode
import com.example.simplecontroller.model.TouchAimManualProfile
import com.example.simplecontroller.model.captureButtonAimProfile
import com.example.simplecontroller.model.captureStickDirectionalProfile
import com.example.simplecontroller.model.captureManualTouchAimProfile
import com.example.simplecontroller.model.isApplicable
import com.example.simplecontroller.io.ButtonAimProfileStore
import com.example.simplecontroller.io.StickDirectionalProfileStore
import com.example.simplecontroller.io.TouchAimManualProfileStore
import com.example.simplecontroller.io.TouchAimCalibrationStore
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.max
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
        val autoTapEnabled: CheckBox,
        val autoTapIntervalMs: EditText,
        val autoCenter: CheckBox,
        val holdDurationField: EditText,
        val swipeActivate: CheckBox,
        val holdLeftWhileTouch: CheckBox,
        val doubleTapClickLock: CheckBox,   // NEW
        val toggleLeftClick: CheckBox,
        val directionalMode: CheckBox,
        val stickPlusMode: CheckBox,
        val directionalContainer: LinearLayout,
        val payloadField: AutoCompleteTextView,
        val touchAim: TouchAimComponents?,
        val buttonAim: ButtonAimComponents?,
        val pageAction: PageActionComponents?,
        val stickProfiles: StickDirectionalProfileComponents?
    )

    private data class TouchAimComponents(
        val mode: Spinner,
        val currentCalibration: TextView,
        val calibrateButton: Button,
        val savedCalibrationsButton: Button,
        val rerunButton: Button,
        val saveManualProfileButton: Button,
        val manageManualProfilesButton: Button,
        val aimOutput: Spinner,
        val useSize: CheckBox,
        val useMajor: CheckBox,
        val useMinor: CheckBox,
        val sizeScale: EditText,
        val smoothing: EditText,
        val lowThreshold: EditText,
        val mediumThreshold: EditText,
        val highThreshold: EditText,
        val hysteresis: EditText,
        val lowPayload: EditText,
        val mediumPayload: EditText,
        val highPayload: EditText,
        val lowAction: Spinner,
        val mediumAction: Spinner,
        val highAction: Spinner,
        val keepLowerHolds: CheckBox,
        val stickFullSpeed: EditText,
        val invertY: CheckBox,
        val useResponseCurve: CheckBox,
        val stickUsesTouchPosition: CheckBox,
        val stickFullDisplacement: EditText,
        val stickDeadzone: EditText,
        val aimPayload: EditText,
        val shootPayload: EditText,
        val keepAimPayload: CheckBox,
        val shootSensitivity: EditText,
        val shootBehavior: Spinner,
        val shootOnThreshold: EditText,
        val shootOffThreshold: EditText,
        val manualShootOnThreshold: EditText,
        val manualShootOffThreshold: EditText,
        val twoStateSmoothing: EditText,
        val enterConfirmationMs: EditText,
        val returnConfirmationMs: EditText
    )

    private data class ButtonAimComponents(
        val enabled: CheckBox,
        val saveProfileButton: Button,
        val manageProfilesButton: Button,
        val aimOutput: Spinner,
        val payloadTiming: Spinner,
        val releaseDelayMs: EditText,
        val sensitivity: EditText,
        val invertY: CheckBox,
        val stickProfile: Spinner,
        val mouseProfile: Spinner,
        val stickFullDisplacement: EditText,
        val stickDeadzone: EditText,
        val stickUsesTouchPosition: CheckBox,
        val dpadMode: Spinner,
        val dpadOrigin: Spinner,
        val dpadActivationDistance: EditText,
        val haptics: CheckBox,
        val oneShotAlternateEnabled: CheckBox,
        val alternatePayload: AutoCompleteTextView,
        val alternatePayloadTiming: Spinner,
        val alternateResetHoldDurationMs: EditText,
        val alternateBaseUnlatchDelayMs: EditText,
        val alternateDisplayName: EditText,
        val alternateOutput: Spinner,
        val alternateSensitivity: EditText,
        val alternateInvertY: CheckBox,
        val alternateStickProfile: Spinner,
        val alternateMouseProfile: Spinner,
        val alternateStickFullDisplacement: EditText,
        val alternateStickDeadzone: EditText,
        val alternateStickUsesTouchPosition: CheckBox,
        val alternateDpadMode: Spinner,
        val alternateDpadOrigin: Spinner,
        val alternateDpadActivationDistance: EditText,
        val alternateHaptics: CheckBox
    )

    private data class PageActionComponents(
        val action: Spinner,
        val target: Spinner,
        val targetIds: List<String>,
        val targetContainer: LinearLayout
    )

    private data class StickDirectionalProfileComponents(
        val saveProfileButton: Button,
        val manageProfilesButton: Button
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

        components.touchAim?.let { fields ->
            fields.calibrateButton.setOnClickListener {
                saveProperties(components)
                alertDialog.dismiss()
                (context as? MainActivity)?.startTouchAimCalibration(model.id)
            }
            fields.rerunButton.setOnClickListener {
                val profile = model.touchAimAppliedCalibrationId.takeIf { it.isNotBlank() }
                    ?.let { TouchAimCalibrationStore.get(context, it) }
                if (profile == null) {
                    Toast.makeText(
                        context,
                        "The saved calibration is unavailable; this control's copied settings are retained.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                saveProperties(components)
                alertDialog.dismiss()
                (context as? MainActivity)?.startTouchAimCalibration(model.id, profile)
            }
            fields.savedCalibrationsButton.setOnClickListener {
                saveProperties(components)
                showSavedCalibrationList(alertDialog)
            }
            fields.saveManualProfileButton.setOnClickListener {
                saveProperties(components)
                promptSaveManualTouchAimProfile()
            }
            fields.manageManualProfilesButton.setOnClickListener {
                saveProperties(components)
                showManualTouchAimProfileList(alertDialog)
            }
        }
        components.buttonAim?.let { fields ->
            fields.saveProfileButton.setOnClickListener {
                saveProperties(components)
                promptSaveButtonAimProfile()
            }
            fields.manageProfilesButton.setOnClickListener {
                saveProperties(components)
                showButtonAimProfileList(alertDialog)
            }
        }
        components.stickProfiles?.let { fields ->
            fields.saveProfileButton.setOnClickListener {
                saveProperties(components)
                promptSaveStickDirectionalProfile()
            }
            fields.manageProfilesButton.setOnClickListener {
                saveProperties(components)
                showStickDirectionalProfileList(alertDialog)
            }
        }

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

        // Keep the primary Button behavior choice near the top for one-finger access.
        val pageActionComponents = if (model.type == ControlType.BUTTON) {
            addPageActionUI(container)
        } else null
        val earlyButtonPayload = if (model.type == ControlType.BUTTON) {
            addPayloadControl(container)
        } else null
        
        // Sensitivity (for stick/touchpad)
        val sensitivitySeek = if (
            model.type == ControlType.STICK ||
            model.type == ControlType.CURVED_STICK ||
            model.type == ControlType.TOUCHPAD ||
            model.type == ControlType.TOUCH_AIM
        ) {
            addSeekBarWithLabel(
                container, 
                if (model.type == ControlType.CURVED_STICK) {
                    "Curve sensitivity: ${(model.sensitivity * 100).roundToInt() / 100f}"
                } else {
                    "Sensitivity: ${(model.sensitivity * 100).roundToInt() / 100f}"
                },
                500,
                (model.sensitivity * 100).roundToInt(),
                {
                    if (model.type == ControlType.CURVED_STICK) {
                        "Curve sensitivity: ${it / 100f}"
                    } else {
                        "Sensitivity: ${it / 100f}"
                    }
                }
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

        val autoTapEnabled = addCheckBox(
            container,
            "Toggle auto-tap",
            model.autoTapEnabled,
            model.type == ControlType.BUTTON
        )
        val autoTapIntervalMs = addTextField(
            container,
            model.autoTapIntervalMs.toString(),
            "Auto-tap interval (ms, minimum 16)",
            InputType.TYPE_CLASS_NUMBER,
            model.type == ControlType.BUTTON && model.autoTapEnabled
        )

        val buttonAimComponents = if (model.type == ControlType.BUTTON) {
            addButtonAimUI(container) { checked ->
                if (checked) autoTapEnabled.isChecked = false
            }
        } else {
            null
        }

        if (model.type == ControlType.BUTTON) {
            autoTapEnabled.setOnCheckedChangeListener { _, checked ->
                autoTapIntervalMs.visibility = if (checked) View.VISIBLE else View.GONE
                if (checked) {
                    holdToggle.isChecked = false
                    buttonAimComponents?.enabled?.isChecked = false
                }
            }
            holdToggle.setOnCheckedChangeListener { _, checked ->
                if (checked) autoTapEnabled.isChecked = false
            }
            when {
                buttonAimComponents?.enabled?.isChecked == true ->
                    autoTapEnabled.isChecked = false
                autoTapEnabled.isChecked -> holdToggle.isChecked = false
            }

            val updatePageActionCompatibility = {
                val action = PageAction.entries[pageActionComponents?.action?.selectedItemPosition ?: 0]
                val localAction = action != PageAction.NONE
                pageActionComponents?.targetContainer?.visibility =
                    if (pageActionNeedsTarget(action)) View.VISIBLE else View.GONE
                holdToggle.isEnabled = !localAction
                autoTapEnabled.isEnabled = !localAction
                buttonAimComponents?.enabled?.isEnabled = !localAction
                holdDurationField.visibility = if (localAction) View.GONE else View.VISIBLE
                earlyButtonPayload?.visibility = if (localAction) View.GONE else View.VISIBLE
                autoTapIntervalMs.visibility = if (!localAction && autoTapEnabled.isChecked) {
                    View.VISIBLE
                } else View.GONE
                if (localAction) {
                    holdToggle.isChecked = false
                    autoTapEnabled.isChecked = false
                    buttonAimComponents?.enabled?.isChecked = false
                }
            }
            pageActionComponents?.action?.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) = updatePageActionCompatibility()
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            updatePageActionCompatibility()
        }
        
        // Stick/Touchpad controls
        val autoCenter = addCheckBox(
            container,
            "Auto-center",
            model.autoCenter,
            model.type == ControlType.STICK ||
                model.type == ControlType.CURVED_STICK ||
                model.type == ControlType.TOUCHPAD
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

        val touchAimComponents = if (model.type == ControlType.TOUCH_AIM) {
            addTouchAimUI(container)
        } else {
            null
        }
        
        // Directional mode (for sticks)
        val isStick = model.type == ControlType.STICK || model.type == ControlType.CURVED_STICK
        val stickProfileComponents = if (isStick) {
            addStickDirectionalProfileUI(container)
        } else null
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
        val payloadField = earlyButtonPayload ?: addPayloadControl(container)
        payloadField.visibility = if (
            model.type == ControlType.TOUCH_AIM ||
            (model.type == ControlType.BUTTON && model.pageAction != PageAction.NONE)
        ) View.GONE else View.VISIBLE
        
        return UIComponents(
            nameField, widthSeek, heightSeek, sensitivitySeek,
            holdToggle, autoTapEnabled, autoTapIntervalMs,
            autoCenter, holdDurationField, swipeActivate,
            holdLeftWhileTouch, doubleTapClickLock, toggleLeftClick, directionalMode, stickPlusMode,
            directionalContainer, payloadField, touchAimComponents, buttonAimComponents,
            pageActionComponents,
            stickProfileComponents
        )
    }

    private fun addPageActionUI(container: LinearLayout): PageActionComponents {
        addSectionTitle(container, "Local page action")
        container.addView(TextView(context).apply {
            text = "Page actions run once on Android and never send the ordinary payload. Hold, Turbo, Auto-Tap, Button Aim, and delayed actions are disabled."
        })
        val action = addChoice(
            container,
            "Button action",
            listOf("Ordinary payload", "Go to page", "Toggle page", "Return to previous page", "Go to Home page"),
            model.pageAction.ordinal.coerceIn(0, PageAction.entries.lastIndex)
        )

        val pageOptions = (context as? MainActivity)?.controllerPageOptions().orEmpty()
        val ids = pageOptions.map { it.id }.toMutableList()
        val names = pageOptions.map { it.name }.toMutableList()
        if (model.pageTargetId.isNotBlank() && model.pageTargetId !in ids) {
            ids += model.pageTargetId
            names += "⚠ Missing page"
        }
        if (ids.isEmpty()) {
            ids += ""
            names += "No pages available"
        }
        val targetContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "Target page" })
        }
        val target = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                names
            )
            setSelection(ids.indexOf(model.pageTargetId).takeIf { it >= 0 } ?: 0)
        }
        targetContainer.addView(target)
        container.addView(targetContainer)
        return PageActionComponents(action, target, ids, targetContainer)
    }

    private fun addStickDirectionalProfileUI(
        container: LinearLayout
    ): StickDirectionalProfileComponents {
        addSectionTitle(container, "Stick directional profiles")
        return StickDirectionalProfileComponents(
            saveProfileButton = largeActionButton(
                container,
                "Save changes as new stick profile"
            ),
            manageProfilesButton = largeActionButton(
                container,
                "Save changes, then manage stick profiles"
            )
        )
    }

    private fun addButtonAimUI(
        container: LinearLayout,
        onEnabledChanged: (Boolean) -> Unit = {}
    ): ButtonAimComponents {
        addSectionTitle(container, "Button Aim Surface")
        val enabled = addCheckBox(container, "Aim while pressed", model.buttonAimEnabled)
        val saveProfileButton = largeActionButton(
            container,
            "Save changes as new Button Aim profile"
        )
        val manageProfilesButton = largeActionButton(
            container,
            "Save changes, then manage Button Aim profiles"
        )
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (model.buttonAimEnabled) View.VISIBLE else View.GONE
            container.addView(this)
        }

        val aimOutput = addChoice(
            details,
            "Aim output",
            listOf("Mouse", "Right stick", "Left stick", "D-pad"),
            when (model.buttonAimOutput) {
                ButtonAimOutput.MOUSE -> 0
                ButtonAimOutput.RIGHT_STICK -> 1
                ButtonAimOutput.LEFT_STICK -> 2
                ButtonAimOutput.DPAD -> 3
            }
        )
        val payloadTiming = addChoice(
            details,
            "Payload timing",
            listOf("Immediate", "Send on release"),
            if (model.buttonAimPayloadTiming == ButtonAimPayloadTiming.IMMEDIATE) 0 else 1
        )
        details.addView(TextView(context).apply { text = "Delay after release (ms)" })
        val releaseDelayMs = addTextField(
            details,
            model.buttonAimReleaseDelayMs.toString(),
            "Delay after release (ms)",
            InputType.TYPE_CLASS_NUMBER
        )
        details.addView(TextView(context).apply { text = "Delay applies only to Send on release" })
        details.addView(createGap())
        val continuousAimOptions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            details.addView(this)
        }
        val sensitivity = addDecimalField(
            continuousAimOptions,
            "Aim sensitivity",
            model.buttonAimSensitivity
        )
        val invertY = addCheckBox(continuousAimOptions, "Invert Y", model.buttonAimInvertY)

        val mouseOptions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            continuousAimOptions.addView(this)
        }
        val mouseProfile = addChoice(
            mouseOptions,
            "Mouse movement profile",
            listOf("Linear Relative", "Smoothed / Nonlinear"),
            if (model.buttonAimMouseProfile == ButtonAimMouseProfile.LINEAR_RELATIVE) 0 else 1
        )

        val stickOptions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            continuousAimOptions.addView(this)
        }
        val stickProfile = addChoice(
            stickOptions,
            "Stick movement profile",
            listOf("Linear", "Response Curve"),
            if (model.buttonAimStickProfile == ButtonAimStickProfile.LINEAR) 0 else 1
        )
        val stickFullDisplacement = addDecimalField(
            stickOptions,
            "Full stick displacement (px)",
            model.buttonAimStickFullDisplacementPx
        )
        val stickDeadzone = addDecimalField(
            stickOptions,
            "Stick dead zone (px)",
            model.buttonAimStickDeadzonePx
        )
        val stickUsesTouchPosition = addCheckBox(
            stickOptions,
            "Use touch position as stick position",
            model.buttonAimStickUsesTouchPosition
        )
        stickOptions.addView(TextView(context).apply {
            text = "When enabled, touching a corner starts the stick in that corner."
        })

        val dpadOptions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            details.addView(this)
        }
        val dpadMode = addChoice(
            dpadOptions,
            "D-pad directions",
            listOf("4-way", "8-way"),
            if (model.buttonAimDpadMode == ButtonAimDpadMode.FOUR_WAY) 0 else 1
        )
        val dpadOrigin = addChoice(
            dpadOptions,
            "D-pad zero point",
            listOf("Initial touch", "Button center"),
            if (model.buttonAimDpadOrigin == ButtonAimDpadOrigin.CONTROL_CENTER) 0 else 1
        )
        dpadOptions.addView(TextView(context).apply {
            text = "Initial touch: the button center is neutral, so tapping a side immediately selects that direction.\n\nButton center: your first contact is neutral; slide from it to select a direction."
        })
        val dpadActivationDistance = addDecimalField(
            dpadOptions,
            "D-pad activation distance (px)",
            model.buttonAimDpadActivationDistancePx
        )
        val haptics = addCheckBox(details, "Button Aim haptics", model.buttonAimHaptics)

        addSectionTitle(details, "One-Shot Alternate")
        details.addView(TextView(context).apply {
            text = "Keep this surface on a configured alternate action until its reset hold is completed."
        })
        val oneShotAlternateEnabled = addCheckBox(
            details,
            "Use one-shot alternate",
            model.buttonAimOneShotAlternateEnabled
        )
        val alternateDetails = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (model.buttonAimOneShotAlternateEnabled) View.VISIBLE else View.GONE
            details.addView(this)
        }

        alternateDetails.addView(TextView(context).apply {
            text = "Alternate payload (supports the same commands and comma-separated combinations as a Button)"
        })
        val alternatePayload = addPayloadControl(
            alternateDetails,
            model.buttonAimAlternatePayload,
            "Alternate payload (comma-separated)"
        )
        alternateDetails.addView(createGap())

        alternateDetails.addView(TextView(context).apply {
            text = "Optional alternate display name (the payload is used when blank)"
        })
        val alternateDisplayName = addTextField(
            alternateDetails,
            model.buttonAimAlternateDisplayName,
            "Optional alternate display name"
        )

        val alternatePayloadTiming = addChoice(
            alternateDetails,
            "Alternate payload timing",
            listOf("Immediate", "Send on release"),
            if (model.buttonAimAlternatePayloadTiming == ButtonAimPayloadTiming.IMMEDIATE) 0 else 1
        )
        alternateDetails.addView(TextView(context).apply {
            text = "Send on release fires on intentional lift and does not inherit the base release delay."
        })
        alternateDetails.addView(createGap())

        alternateDetails.addView(TextView(context).apply {
            text = "Return-to-base hold duration (ms)"
        })
        val alternateResetHoldDurationMs = addTextField(
            alternateDetails,
            model.buttonAimAlternateResetHoldDurationMs.toString(),
            "Return-to-base hold duration (ms)",
            InputType.TYPE_CLASS_NUMBER
        )
        alternateDetails.addView(TextView(context).apply {
            text = "While in alternate mode, hold this long and release to return to the base action."
        })
        alternateDetails.addView(createGap())

        alternateDetails.addView(TextView(context).apply {
            text = "Base unlatch delay after alternate release (ms)"
        })
        val alternateBaseUnlatchDelayMs = addTextField(
            alternateDetails,
            model.buttonAimAlternateBaseUnlatchDelayMs.toString(),
            "Base unlatch delay (ms)",
            InputType.TYPE_CLASS_NUMBER
        )
        alternateDetails.addView(TextView(context).apply {
            text = "After a reset gesture releases the alternate, keep a latched Base payload held for this additional time."
        })
        alternateDetails.addView(createGap())

        addSectionTitle(alternateDetails, "Alternate Aim")
        alternateDetails.addView(TextView(context).apply {
            text = "These settings apply only while the one-shot alternate is armed or active."
        })
        val alternateOutput = addChoice(
            alternateDetails,
            "Alternate aim output",
            listOf("Mouse", "Right stick", "Left stick", "D-pad"),
            when (model.buttonAimAlternateOutput) {
                ButtonAimOutput.MOUSE -> 0
                ButtonAimOutput.RIGHT_STICK -> 1
                ButtonAimOutput.LEFT_STICK -> 2
                ButtonAimOutput.DPAD -> 3
            }
        )
        val alternateContinuousAimOptions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            alternateDetails.addView(this)
        }
        val alternateSensitivity = addDecimalField(
            alternateContinuousAimOptions,
            "Alternate aim sensitivity",
            model.buttonAimAlternateSensitivity
        )
        val alternateInvertY = addCheckBox(
            alternateContinuousAimOptions,
            "Alternate invert Y",
            model.buttonAimAlternateInvertY
        )

        val alternateMouseOptions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            alternateContinuousAimOptions.addView(this)
        }
        val alternateMouseProfile = addChoice(
            alternateMouseOptions,
            "Alternate mouse movement profile",
            listOf("Linear Relative", "Smoothed / Nonlinear"),
            if (model.buttonAimAlternateMouseProfile == ButtonAimMouseProfile.LINEAR_RELATIVE) 0 else 1
        )

        val alternateStickOptions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            alternateContinuousAimOptions.addView(this)
        }
        val alternateStickProfile = addChoice(
            alternateStickOptions,
            "Alternate stick movement profile",
            listOf("Linear", "Response Curve"),
            if (model.buttonAimAlternateStickProfile == ButtonAimStickProfile.LINEAR) 0 else 1
        )
        val alternateStickFullDisplacement = addDecimalField(
            alternateStickOptions,
            "Alternate full stick displacement (px)",
            model.buttonAimAlternateStickFullDisplacementPx
        )
        val alternateStickDeadzone = addDecimalField(
            alternateStickOptions,
            "Alternate stick dead zone (px)",
            model.buttonAimAlternateStickDeadzonePx
        )
        val alternateStickUsesTouchPosition = addCheckBox(
            alternateStickOptions,
            "Use touch position as alternate stick position",
            model.buttonAimAlternateStickUsesTouchPosition
        )
        alternateStickOptions.addView(TextView(context).apply {
            text = "When enabled, touching a corner starts the alternate stick in that corner."
        })
        val alternateDpadOptions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            alternateDetails.addView(this)
        }
        val alternateDpadMode = addChoice(
            alternateDpadOptions,
            "Alternate D-pad directions",
            listOf("4-way", "8-way"),
            if (model.buttonAimAlternateDpadMode == ButtonAimDpadMode.FOUR_WAY) 0 else 1
        )
        val alternateDpadOrigin = addChoice(
            alternateDpadOptions,
            "Alternate D-pad zero point",
            listOf("Initial touch", "Button center"),
            if (model.buttonAimAlternateDpadOrigin == ButtonAimDpadOrigin.CONTROL_CENTER) 0 else 1
        )
        alternateDpadOptions.addView(TextView(context).apply {
            text = "Initial touch: the button center is neutral, so tapping a side immediately selects that direction.\n\nButton center: your first contact is neutral; slide from it to select a direction."
        })
        val alternateDpadActivationDistance = addDecimalField(
            alternateDpadOptions,
            "Alternate D-pad activation distance (px)",
            model.buttonAimAlternateDpadActivationDistancePx
        )
        val alternateHaptics = addCheckBox(
            alternateDetails,
            "Alternate haptics",
            model.buttonAimAlternateHaptics
        )

        fun updateOutputVisibility() {
            val mouseSelected = aimOutput.selectedItemPosition == 0
            val stickSelected = aimOutput.selectedItemPosition == 1 ||
                aimOutput.selectedItemPosition == 2
            val dpadSelected = aimOutput.selectedItemPosition == 3
            continuousAimOptions.visibility = if (dpadSelected) View.GONE else View.VISIBLE
            mouseOptions.visibility = if (mouseSelected) View.VISIBLE else View.GONE
            stickOptions.visibility = if (stickSelected) View.VISIBLE else View.GONE
            dpadOptions.visibility = if (dpadSelected) View.VISIBLE else View.GONE
        }
        aimOutput.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateOutputVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        fun updateAlternateOutputVisibility() {
            val mouseSelected = alternateOutput.selectedItemPosition == 0
            val stickSelected = alternateOutput.selectedItemPosition == 1 ||
                alternateOutput.selectedItemPosition == 2
            val dpadSelected = alternateOutput.selectedItemPosition == 3
            alternateContinuousAimOptions.visibility =
                if (dpadSelected) View.GONE else View.VISIBLE
            alternateMouseOptions.visibility = if (mouseSelected) View.VISIBLE else View.GONE
            alternateStickOptions.visibility = if (stickSelected) View.VISIBLE else View.GONE
            alternateDpadOptions.visibility = if (dpadSelected) View.VISIBLE else View.GONE
        }
        alternateOutput.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateAlternateOutputVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        enabled.setOnCheckedChangeListener { _, checked ->
            details.visibility = if (checked) View.VISIBLE else View.GONE
            onEnabledChanged(checked)
        }
        oneShotAlternateEnabled.setOnCheckedChangeListener { _, checked ->
            alternateDetails.visibility = if (checked) View.VISIBLE else View.GONE
        }
        updateOutputVisibility()
        updateAlternateOutputVisibility()

        return ButtonAimComponents(
            enabled = enabled,
            saveProfileButton = saveProfileButton,
            manageProfilesButton = manageProfilesButton,
            aimOutput = aimOutput,
            payloadTiming = payloadTiming,
            releaseDelayMs = releaseDelayMs,
            sensitivity = sensitivity,
            invertY = invertY,
            stickProfile = stickProfile,
            mouseProfile = mouseProfile,
            stickFullDisplacement = stickFullDisplacement,
            stickDeadzone = stickDeadzone,
            stickUsesTouchPosition = stickUsesTouchPosition,
            dpadMode = dpadMode,
            dpadOrigin = dpadOrigin,
            dpadActivationDistance = dpadActivationDistance,
            haptics = haptics,
            oneShotAlternateEnabled = oneShotAlternateEnabled,
            alternatePayload = alternatePayload,
            alternatePayloadTiming = alternatePayloadTiming,
            alternateResetHoldDurationMs = alternateResetHoldDurationMs,
            alternateBaseUnlatchDelayMs = alternateBaseUnlatchDelayMs,
            alternateDisplayName = alternateDisplayName,
            alternateOutput = alternateOutput,
            alternateSensitivity = alternateSensitivity,
            alternateInvertY = alternateInvertY,
            alternateStickProfile = alternateStickProfile,
            alternateMouseProfile = alternateMouseProfile,
            alternateStickFullDisplacement = alternateStickFullDisplacement,
            alternateStickDeadzone = alternateStickDeadzone,
            alternateStickUsesTouchPosition = alternateStickUsesTouchPosition,
            alternateDpadMode = alternateDpadMode,
            alternateDpadOrigin = alternateDpadOrigin,
            alternateDpadActivationDistance = alternateDpadActivationDistance,
            alternateHaptics = alternateHaptics
        )
    }

    private fun addTouchAimUI(container: LinearLayout): TouchAimComponents {
        addSectionTitle(container, "TouchAim mode and calibration")
        val mode = addChoice(
            container,
            "Mode",
            listOf(
                "Manual three-stage",
                "Manual two-state: Aim / Shoot",
                "Calibrated two-state: Aim / Shoot"
            ),
            when (model.touchAimMode) {
                TouchAimMode.MANUAL_THREE_STAGE -> 0
                TouchAimMode.MANUAL_TWO_STATE -> 1
                TouchAimMode.CALIBRATED_TWO_STATE -> 2
            }
        )
        val storedAppliedProfile = model.touchAimAppliedCalibrationId.takeIf { it.isNotBlank() }
            ?.let { TouchAimCalibrationStore.get(context, it) }
        val currentCalibrationLabel = when {
            model.touchAimMode == TouchAimMode.MANUAL_THREE_STAGE -> "Manual three-stage settings"
            model.touchAimMode == TouchAimMode.MANUAL_TWO_STATE -> "Manual two-state settings"
            storedAppliedProfile != null && model.matchesAppliedCalibration(storedAppliedProfile) -> storedAppliedProfile.name
            storedAppliedProfile != null -> "Older or customized snapshot of ${storedAppliedProfile.name}"
            model.touchAimAppliedCalibrationId.isNotBlank() -> "Saved profile unavailable; copied calibration retained"
            else -> "Custom two-state settings"
        }
        val currentCalibration = TextView(context).apply {
            text = "Currently applied: $currentCalibrationLabel"
            textSize = 16f
            setTextColor(ThemeManager.getTextColor(context))
            setPadding(0, 12, 0, 8)
            container.addView(this)
        }
        val calibrateButton = largeActionButton(container, "Calibrate TouchAim")
        val savedCalibrationsButton = largeActionButton(
            container,
            "Save changes, then manage calibrations"
        )
        val rerunButton = largeActionButton(container, "Re-run current calibration").apply {
            isEnabled = model.touchAimMode == TouchAimMode.CALIBRATED_TWO_STATE &&
                model.touchAimAppliedCalibrationId.isNotBlank()
        }
        val manualProfileContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            container.addView(this)
        }
        addSectionTitle(manualProfileContainer, "Manual TouchAim profiles")
        val saveManualProfileButton = largeActionButton(
            manualProfileContainer,
            "Save changes as new manual profile"
        )
        val manageManualProfilesButton = largeActionButton(
            manualProfileContainer,
            "Save changes, then manage manual profiles"
        )

        addSectionTitle(container, "Aim")
        val aimOutput = addChoice(
            container,
            "Aim output",
            listOf("Mouse", "Right stick", "Left stick"),
            when (model.touchAimOutput) {
                TouchAimOutput.MOUSE -> 0
                TouchAimOutput.RIGHT_STICK -> 1
                TouchAimOutput.LEFT_STICK -> 2
            }
        )
        val invertY = addCheckBox(container, "Invert Y", model.touchInvertY)
        val stickAimContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            container.addView(this)
        }
        val useResponseCurve = addCheckBox(
            stickAimContainer,
            "Stick aiming style: Response curve (off = Linear)",
            model.touchUseResponseCurve
        )
        val centerStickContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            stickAimContainer.addView(this)
        }
        val stickFullSpeed = addDecimalField(
            centerStickContainer,
            "Stick full speed (px/sec)",
            model.touchStickFullSpeed
        )
        val stickUsesTouchPosition = addCheckBox(
            stickAimContainer,
            "Use touch position as stick position",
            model.touchAimStickUsesTouchPosition
        )
        stickAimContainer.addView(TextView(context).apply {
            text = "On: the TouchAim surface center is neutral. Off: wherever your finger first lands is neutral."
            setTextColor(ThemeManager.getTextColor(context))
        })
        val relativeStickContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            stickAimContainer.addView(this)
        }
        val stickFullDisplacement = addDecimalField(
            relativeStickContainer,
            "Full displacement (px)",
            model.touchAimStickFullDisplacementPx
        )
        val stickDeadzone = addDecimalField(
            relativeStickContainer,
            "Deadzone (px)",
            model.touchAimStickDeadzonePx
        )
        relativeStickContainer.addView(TextView(context).apply {
            text = "Full displacement is the finger travel needed for 100% stick output. Deadzone is movement ignored near the initial touch."
            setTextColor(ThemeManager.getTextColor(context))
        })
        fun updateStickAimVisibility() {
            val isStickOutput = aimOutput.selectedItemPosition != 0
            stickAimContainer.visibility = if (isStickOutput) View.VISIBLE else View.GONE
            centerStickContainer.visibility = if (isStickOutput &&
                stickUsesTouchPosition.isChecked
            ) View.VISIBLE else View.GONE
            relativeStickContainer.visibility = if (isStickOutput &&
                !stickUsesTouchPosition.isChecked
            ) View.VISIBLE else View.GONE
        }
        aimOutput.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) = updateStickAimVisibility()

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        stickUsesTouchPosition.setOnCheckedChangeListener { _, _ -> updateStickAimVisibility() }
        updateStickAimVisibility()

        val twoStateContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            container.addView(this)
        }
        addSectionTitle(twoStateContainer, "Two-state Aim / Shoot settings")
        twoStateContainer.addView(TextView(context).apply {
            text = "Aim uses the normal Sensitivity setting above. Shoot aim sensitivity applies only after SHOOT is confirmed; lower values provide finer control. Manual two-state uses the manual sensor score; calibrated mode uses the wizard's normalized score."
            setTextColor(ThemeManager.getTextColor(context))
        })
        val aimPayload = addCommandField(twoStateContainer, "Optional Aim-state payload", model.touchAimAimPayload)
        val shootPayload = addCommandField(twoStateContainer, "Shoot-state payload", model.touchAimShootPayload)
        val keepAimPayload = addCheckBox(
            twoStateContainer,
            "Keep Aim payload active while shooting",
            model.touchAimKeepAimPayloadWhileShooting
        )
        val shootSensitivity = addDecimalField(
            twoStateContainer,
            "Shoot aim sensitivity",
            model.touchAimShootSensitivity
        )
        val shootBehavior = addChoice(
            twoStateContainer,
            "Shoot activation",
            listOf(
                "Hold while above threshold",
                "Press when entering Shoot",
                "Press when returning to Aim"
            ),
            when (model.touchAimShootBehavior) {
                TouchAimShootBehavior.HOLD_WHILE_ABOVE -> 0
                TouchAimShootBehavior.PRESS_ON_ENTER -> 1
                TouchAimShootBehavior.PRESS_ON_RETURN_TO_AIM -> 2
            }
        )
        val calibratedThresholdContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            twoStateContainer.addView(this)
        }
        val shootOnThreshold = addDecimalField(
            calibratedThresholdContainer,
            "Calibrated Shoot ON threshold",
            model.touchAimShootOnThreshold
        )
        val shootOffThreshold = addDecimalField(
            calibratedThresholdContainer,
            "Calibrated Shoot OFF threshold",
            model.touchAimShootOffThreshold
        )
        val twoStateSmoothing = addDecimalField(
            twoStateContainer,
            "Two-state smoothing (0.05-1.0)",
            model.touchAimTwoStateSmoothing
        )
        val enterConfirmationMs = addTextField(
            twoStateContainer,
            model.touchAimEnterShootConfirmationMs.toString(),
            "Enter Shoot confirmation (ms)",
            InputType.TYPE_CLASS_NUMBER
        )
        val returnConfirmationMs = addTextField(
            twoStateContainer,
            model.touchAimReturnToAimConfirmationMs.toString(),
            "Return to Aim confirmation (ms)",
            InputType.TYPE_CLASS_NUMBER
        )
        twoStateContainer.addView(TextView(context).apply {
            text = "Hysteresis is the gap between Shoot ON and OFF: it prevents flickering between states. Enter/return confirmation is how long, in milliseconds, the score must remain past each threshold before the state changes."
            setTextColor(ThemeManager.getTextColor(context))
        })

        val manualSensorContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            container.addView(this)
        }
        addSectionTitle(manualSensorContainer, "Manual sensor score")
        manualSensorContainer.addView(TextView(context).apply {
            text = "Used by both manual modes. Size is multiplied first, then the checked sensor values are averaged. Manual two-state uses its ON/OFF values below; manual three-stage has its own smoothing and Low/Medium/High settings."
            setTextColor(ThemeManager.getTextColor(context))
        })
        val useSize = addCheckBox(manualSensorContainer, "Use Size", model.touchUseSize)
        val useMajor = addCheckBox(manualSensorContainer, "Use TouchMajor", model.touchUseMajor)
        val useMinor = addCheckBox(manualSensorContainer, "Use TouchMinor", model.touchUseMinor)
        val sizeScale = addDecimalField(manualSensorContainer, "Size multiplier", model.touchSizeScale)
        val initialManualShootOn = if (model.touchAimManualThresholdsInitialized) {
            model.touchAimManualShootOnThreshold
        } else {
            model.touchHighThreshold
        }
        val initialManualShootOff = if (model.touchAimManualThresholdsInitialized) {
            model.touchAimManualShootOffThreshold
        } else {
            (model.touchHighThreshold - model.touchHysteresis).coerceAtLeast(0f)
        }
        val manualTwoThresholdContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            manualSensorContainer.addView(this)
        }
        val manualShootOnThreshold = addDecimalField(
            manualTwoThresholdContainer,
            "Manual two-state Shoot ON threshold",
            initialManualShootOn
        )
        val manualShootOffThreshold = addDecimalField(
            manualTwoThresholdContainer,
            "Manual two-state Shoot OFF threshold",
            initialManualShootOff
        )
        val threeStageContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            container.addView(this)
        }
        addSectionTitle(threeStageContainer, "Manual three-stage settings")
        val smoothing = addDecimalField(threeStageContainer, "Score smoothing (0.05-1.0)", model.touchScoreSmoothing)

        addSectionTitle(threeStageContainer, "Thresholds")
        val lowThreshold = addDecimalField(threeStageContainer, "Low ON", model.touchLowThreshold)
        val mediumThreshold = addDecimalField(threeStageContainer, "Medium ON", model.touchMediumThreshold)
        val highThreshold = addDecimalField(threeStageContainer, "High ON", model.touchHighThreshold)
        val hysteresis = addDecimalField(threeStageContainer, "Hysteresis", model.touchHysteresis)
        val keepLowerHolds = addCheckBox(
            threeStageContainer,
            "Keep lower holds at higher levels",
            model.touchKeepLowerHolds
        )

        addSectionTitle(threeStageContainer, "Low level")
        val lowPayload = addCommandField(threeStageContainer, "Commands", model.touchLowPayload)
        val lowAction = addActionChoice(threeStageContainer, model.touchLowAction)

        addSectionTitle(threeStageContainer, "Medium level")
        val mediumPayload = addCommandField(threeStageContainer, "Commands", model.touchMediumPayload)
        val mediumAction = addActionChoice(threeStageContainer, model.touchMediumAction)

        addSectionTitle(threeStageContainer, "High level")
        val highPayload = addCommandField(threeStageContainer, "Commands", model.touchHighPayload)
        val highAction = addActionChoice(threeStageContainer, model.touchHighAction)

        fun updateModeVisibility(position: Int) {
            twoStateContainer.visibility = if (position == 0) View.GONE else View.VISIBLE
            manualSensorContainer.visibility = if (position == 2) View.GONE else View.VISIBLE
            manualTwoThresholdContainer.visibility = if (position == 1) View.VISIBLE else View.GONE
            calibratedThresholdContainer.visibility = if (position == 2) View.VISIBLE else View.GONE
            threeStageContainer.visibility = if (position == 0) View.VISIBLE else View.GONE
            manualProfileContainer.visibility = if (position == 2) View.GONE else View.VISIBLE
        }
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                updateModeVisibility(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateModeVisibility(mode.selectedItemPosition)

        return TouchAimComponents(
            mode = mode,
            currentCalibration = currentCalibration,
            calibrateButton = calibrateButton,
            savedCalibrationsButton = savedCalibrationsButton,
            rerunButton = rerunButton,
            saveManualProfileButton = saveManualProfileButton,
            manageManualProfilesButton = manageManualProfilesButton,
            aimOutput = aimOutput,
            useSize = useSize,
            useMajor = useMajor,
            useMinor = useMinor,
            sizeScale = sizeScale,
            smoothing = smoothing,
            lowThreshold = lowThreshold,
            mediumThreshold = mediumThreshold,
            highThreshold = highThreshold,
            hysteresis = hysteresis,
            lowPayload = lowPayload,
            mediumPayload = mediumPayload,
            highPayload = highPayload,
            lowAction = lowAction,
            mediumAction = mediumAction,
            highAction = highAction,
            keepLowerHolds = keepLowerHolds,
            stickFullSpeed = stickFullSpeed,
            invertY = invertY,
            useResponseCurve = useResponseCurve,
            stickUsesTouchPosition = stickUsesTouchPosition,
            stickFullDisplacement = stickFullDisplacement,
            stickDeadzone = stickDeadzone,
            aimPayload = aimPayload,
            shootPayload = shootPayload,
            keepAimPayload = keepAimPayload,
            shootSensitivity = shootSensitivity,
            shootBehavior = shootBehavior,
            shootOnThreshold = shootOnThreshold,
            shootOffThreshold = shootOffThreshold,
            manualShootOnThreshold = manualShootOnThreshold,
            manualShootOffThreshold = manualShootOffThreshold,
            twoStateSmoothing = twoStateSmoothing,
            enterConfirmationMs = enterConfirmationMs,
            returnConfirmationMs = returnConfirmationMs
        )
    }

    private fun largeActionButton(container: LinearLayout, label: String): Button = Button(context).apply {
        text = label
        textSize = 18f
        minHeight = (58 * context.resources.displayMetrics.density).roundToInt()
        isAllCaps = false
        container.addView(
            this,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        )
    }

    private fun promptSaveStickDirectionalProfile() {
        if (model.directionalMode == model.stickPlusMode) {
            Toast.makeText(
                context,
                "Choose Directional/WASD mode or Stick+ mode before saving a stick profile.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val modeLabel = if (model.directionalMode) "WASD" else "Stick+"
        val input = EditText(context).apply {
            hint = "Profile name"
            setText(model.name.ifBlank { "$modeLabel settings" })
            selectAll()
        }
        AlertDialog.Builder(context)
            .setTitle("Save $modeLabel profile")
            .setMessage(
                "Saves Up, Down, Left, and Right commands plus every Regular Boost and Super Boost " +
                    "command and threshold. It does not save the stick's size, position, LS/RS " +
                    "payload, sensitivity, auto-center, or Linear/Response Curve type."
            )
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val profile = model.captureStickDirectionalProfile(
                    UUID.randomUUID().toString(),
                    input.text.toString(),
                    System.currentTimeMillis()
                )
                if (profile == null) {
                    Toast.makeText(context, "These stick directional settings are invalid.", Toast.LENGTH_LONG).show()
                } else {
                    val result = StickDirectionalProfileStore.save(context, profile)
                    Toast.makeText(
                        context,
                        result.error ?: "Saved \"${result.profile?.name}\"",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStickDirectionalProfileList(propertyDialog: AlertDialog) {
        val profiles = StickDirectionalProfileStore.list(context)
        if (profiles.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("Stick directional profiles")
                .setMessage("No Stick+ or WASD profiles have been saved yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = profiles.map { "${it.name} — ${it.mode.displayName()}" }
        var listDialog: AlertDialog? = null
        listDialog = AlertDialog.Builder(context)
            .setTitle("Stick directional profiles")
            .setItems(labels.toTypedArray()) { _, index ->
                showStickDirectionalProfileActions(profiles[index], propertyDialog, listDialog)
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun showStickDirectionalProfileActions(
        profile: StickDirectionalProfile,
        propertyDialog: AlertDialog,
        listDialog: AlertDialog?
    ) {
        val modeLabel = profile.mode.displayName()
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        var actionDialog: AlertDialog? = null
        fun finishApply() {
            onPropertiesUpdated()
            actionDialog?.dismiss()
            listDialog?.dismiss()
            propertyDialog.dismiss()
            reopenPropertySheet()
        }
        largeActionButton(actions, "Apply commands only").setOnClickListener {
            if (profile.applyCommandsTo(model)) {
                finishApply()
            } else {
                Toast.makeText(context, profile.validationError() ?: "This profile cannot be applied.", Toast.LENGTH_LONG).show()
            }
        }
        largeActionButton(actions, "Apply and switch to $modeLabel").setOnClickListener {
            if (profile.applyAndSwitchModeTo(model)) {
                finishApply()
            } else {
                Toast.makeText(context, profile.validationError() ?: "This profile cannot be applied.", Toast.LENGTH_LONG).show()
            }
        }
        largeActionButton(actions, "Rename").setOnClickListener {
            val input = EditText(context).apply { setText(profile.name); selectAll() }
            AlertDialog.Builder(context)
                .setTitle("Rename stick profile")
                .setView(input)
                .setPositiveButton("Rename") { _, _ ->
                    val result = StickDirectionalProfileStore.rename(
                        context,
                        profile,
                        input.text.toString(),
                        System.currentTimeMillis()
                    )
                    Toast.makeText(context, result.error ?: "Profile renamed", Toast.LENGTH_LONG).show()
                    if (result.succeeded) {
                        actionDialog?.dismiss()
                        listDialog?.dismiss()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        largeActionButton(actions, "Delete").setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Delete stick profile?")
                .setMessage("Sticks that already copied these settings will not change.")
                .setPositiveButton("Delete") { _, _ ->
                    val deleted = StickDirectionalProfileStore.delete(context, profile.id)
                    Toast.makeText(context, if (deleted) "Profile deleted" else "Could not delete profile", Toast.LENGTH_LONG).show()
                    if (deleted) {
                        actionDialog?.dismiss()
                        listDialog?.dismiss()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        actionDialog = AlertDialog.Builder(context)
            .setTitle(profile.name)
            .setMessage(
                "Commands only replaces all directional, Regular Boost, and Super Boost commands " +
                    "and thresholds while keeping this stick's current mode.\n\n" +
                    "Apply and switch also selects the saved $modeLabel mode. Both choices preserve " +
                    "the target stick's geometry, LS/RS payload, sensitivity, auto-center, and stick type."
            )
            .setView(ScrollView(context).apply { addView(actions) })
            .setNegativeButton("Back", null)
            .show()
    }

    private fun StickDirectionalProfileMode.displayName(): String = when (this) {
        StickDirectionalProfileMode.WASD -> "Directional/WASD"
        StickDirectionalProfileMode.STICK_PLUS -> "Stick+"
    }

    private fun promptSaveManualTouchAimProfile() {
        if (model.touchAimMode == TouchAimMode.CALIBRATED_TWO_STATE) {
            Toast.makeText(context, "Choose a manual TouchAim mode before saving a manual profile.", Toast.LENGTH_LONG).show()
            return
        }
        val input = EditText(context).apply {
            hint = "Profile name"
            setText(
                model.name.ifBlank {
                    if (model.touchAimMode == TouchAimMode.MANUAL_TWO_STATE) {
                        "Manual two-state"
                    } else {
                        "Manual three-stage"
                    }
                }
            )
            selectAll()
        }
        AlertDialog.Builder(context)
            .setTitle("Save manual TouchAim profile")
            .setMessage("Saves this manual mode's sensors, thresholds, aiming, and state actions. It does not save the control's size, position, or name.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val profile = model.captureManualTouchAimProfile(
                    UUID.randomUUID().toString(),
                    input.text.toString(),
                    System.currentTimeMillis()
                )
                if (profile == null) {
                    Toast.makeText(context, "These manual settings are incomplete or invalid.", Toast.LENGTH_LONG).show()
                } else {
                    val result = TouchAimManualProfileStore.save(context, profile)
                    Toast.makeText(
                        context,
                        result.error ?: "Saved \"${result.profile?.name}\"",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualTouchAimProfileList(propertyDialog: AlertDialog) {
        val profiles = TouchAimManualProfileStore.list(context)
        if (profiles.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("Manual TouchAim profiles")
                .setMessage("No manual TouchAim profiles have been saved yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = profiles.map { profile ->
            val mode = if (profile.mode == TouchAimMode.MANUAL_TWO_STATE) "Two-state" else "Three-stage"
            "${profile.name} — $mode"
        }
        var listDialog: AlertDialog? = null
        listDialog = AlertDialog.Builder(context)
            .setTitle("Manual TouchAim profiles")
            .setItems(labels.toTypedArray()) { _, index ->
                showManualTouchAimProfileActions(profiles[index], propertyDialog, listDialog)
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun showManualTouchAimProfileActions(
        profile: TouchAimManualProfile,
        propertyDialog: AlertDialog,
        listDialog: AlertDialog?
    ) {
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        var actionDialog: AlertDialog? = null
        largeActionButton(actions, "Apply complete manual profile").setOnClickListener {
            if (profile.applyTo(model)) {
                onPropertiesUpdated()
                actionDialog?.dismiss()
                listDialog?.dismiss()
                propertyDialog.dismiss()
                reopenPropertySheet()
            } else {
                Toast.makeText(context, profile.validationError() ?: "This profile cannot be applied.", Toast.LENGTH_LONG).show()
            }
        }
        largeActionButton(actions, "Rename").setOnClickListener {
            val input = EditText(context).apply { setText(profile.name); selectAll() }
            AlertDialog.Builder(context)
                .setTitle("Rename manual profile")
                .setView(input)
                .setPositiveButton("Rename") { _, _ ->
                    val result = TouchAimManualProfileStore.rename(
                        context,
                        profile,
                        input.text.toString(),
                        System.currentTimeMillis()
                    )
                    Toast.makeText(context, result.error ?: "Profile renamed", Toast.LENGTH_LONG).show()
                    if (result.succeeded) {
                        actionDialog?.dismiss()
                        listDialog?.dismiss()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        largeActionButton(actions, "Delete").setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Delete manual profile?")
                .setMessage("Controls that already copied these settings will not change.")
                .setPositiveButton("Delete") { _, _ ->
                    val deleted = TouchAimManualProfileStore.delete(context, profile.id)
                    Toast.makeText(context, if (deleted) "Profile deleted" else "Could not delete profile", Toast.LENGTH_LONG).show()
                    if (deleted) {
                        actionDialog?.dismiss()
                        listDialog?.dismiss()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        actionDialog = AlertDialog.Builder(context)
            .setTitle(profile.name)
            .setMessage(
                "Applying switches this control to ${if (profile.mode == TouchAimMode.MANUAL_TWO_STATE) "Manual two-state" else "Manual three-stage"}. " +
                    "It replaces the aim settings, thresholds, payloads, and PRESS / HOLD / SHOOT behavior saved in this profile. " +
                    "The control's name, size, and position stay unchanged."
            )
            .setView(ScrollView(context).apply { addView(actions) })
            .setNegativeButton("Back", null)
            .show()
    }

    private fun promptSaveButtonAimProfile() {
        val input = EditText(context).apply {
            hint = "Profile name"
            setText(model.name.ifBlank { "Button Aim tuning" })
            selectAll()
        }
        AlertDialog.Builder(context)
            .setTitle("Save Button Aim profile")
            .setMessage(
                "Saves the complete Button Aim setup: Base and Alternate aiming feel, payloads, " +
                    "Hold behavior, timing, and one-shot settings. The button's name, size, position, " +
                    "and swipe setting are not included."
            )
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val profile = model.captureButtonAimProfile(
                    UUID.randomUUID().toString(),
                    input.text.toString(),
                    System.currentTimeMillis()
                )
                if (profile == null) {
                    Toast.makeText(context, "These Button Aim settings are invalid.", Toast.LENGTH_LONG).show()
                } else {
                    val result = ButtonAimProfileStore.save(context, profile)
                    Toast.makeText(
                        context,
                        result.error ?: "Saved \"${result.profile?.name}\"",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showButtonAimProfileList(propertyDialog: AlertDialog) {
        val profiles = ButtonAimProfileStore.list(context)
        if (profiles.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("Button Aim profiles")
                .setMessage("No Button Aim profiles have been saved yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        var listDialog: AlertDialog? = null
        listDialog = AlertDialog.Builder(context)
            .setTitle("Button Aim profiles")
            .setItems(profiles.map(ButtonAimProfile::name).toTypedArray()) { _, index ->
                showButtonAimProfileActions(profiles[index], propertyDialog, listDialog)
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun showButtonAimProfileActions(
        profile: ButtonAimProfile,
        propertyDialog: AlertDialog,
        listDialog: AlertDialog?
    ) {
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        var actionDialog: AlertDialog? = null
        val sizeDifference = max(
            kotlin.math.abs(model.w - profile.sourceControlWidthPx) / profile.sourceControlWidthPx,
            kotlin.math.abs(model.h - profile.sourceControlHeightPx) / profile.sourceControlHeightPx
        )
        largeActionButton(actions, "Apply aiming feel only").setOnClickListener {
            if (profile.applyAimTuningTo(model)) {
                onPropertiesUpdated()
                actionDialog?.dismiss()
                listDialog?.dismiss()
                propertyDialog.dismiss()
                reopenPropertySheet()
            } else {
                Toast.makeText(context, profile.validationError() ?: "This profile cannot be applied.", Toast.LENGTH_LONG).show()
            }
        }
        largeActionButton(actions, "Apply complete profile").setOnClickListener {
            if (profile.applyCompleteTo(model)) {
                onPropertiesUpdated()
                actionDialog?.dismiss()
                listDialog?.dismiss()
                propertyDialog.dismiss()
                reopenPropertySheet()
            } else {
                Toast.makeText(context, profile.validationError() ?: "This profile cannot be applied.", Toast.LENGTH_LONG).show()
            }
        }
        largeActionButton(actions, "Rename").setOnClickListener {
            val input = EditText(context).apply { setText(profile.name); selectAll() }
            AlertDialog.Builder(context)
                .setTitle("Rename Button Aim profile")
                .setView(input)
                .setPositiveButton("Rename") { _, _ ->
                    val result = ButtonAimProfileStore.rename(
                        context,
                        profile,
                        input.text.toString(),
                        System.currentTimeMillis()
                    )
                    Toast.makeText(context, result.error ?: "Profile renamed", Toast.LENGTH_LONG).show()
                    if (result.succeeded) {
                        actionDialog?.dismiss()
                        listDialog?.dismiss()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        largeActionButton(actions, "Delete").setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Delete Button Aim profile?")
                .setMessage("Buttons that already copied settings from this profile will not change.")
                .setPositiveButton("Delete") { _, _ ->
                    val deleted = ButtonAimProfileStore.delete(context, profile.id)
                    Toast.makeText(context, if (deleted) "Profile deleted" else "Could not delete profile", Toast.LENGTH_LONG).show()
                    if (deleted) {
                        actionDialog?.dismiss()
                        listDialog?.dismiss()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        val sizeWarning = if (sizeDifference > .25f) {
            "\n\nThis button is a substantially different size from the source. Touch-position stick mode may feel different."
        } else ""
        actionDialog = AlertDialog.Builder(context)
            .setTitle(profile.name)
            .setMessage(
                "Aiming feel only copies Base and Alternate output, sensitivity, curve, displacement, deadzone, origin, inversion, and haptics; this button's actions stay unchanged.\n\n" +
                    "Complete profile also replaces the Base payload, Hold behavior, payload timing, and all one-shot Alternate payload/timing settings.\n\n" +
                    "Either choice enables Button Aim and turns off Auto-Tap.$sizeWarning"
            )
            .setView(ScrollView(context).apply { addView(actions) })
            .setNegativeButton("Back", null)
            .show()
    }

    private fun showSavedCalibrationList(propertyDialog: AlertDialog) {
        val profiles = TouchAimCalibrationStore.list(context)
        if (profiles.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("Saved TouchAim calibrations")
                .setMessage("No calibrations have been saved yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = profiles.map { profile ->
            val current = if (model.touchAimMode == TouchAimMode.CALIBRATED_TWO_STATE &&
                profile.id == model.touchAimAppliedCalibrationId &&
                model.matchesAppliedCalibration(profile)) " — APPLIED" else ""
            "${profile.name} — ${profile.quality.name.lowercase().replaceFirstChar { it.uppercase() }}$current"
        }
        AlertDialog.Builder(context)
            .setTitle("Saved TouchAim calibrations")
            .setItems(labels.toTypedArray()) { _, index ->
                showCalibrationActions(profiles[index], propertyDialog)
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun showCalibrationActions(
        profile: com.example.simplecontroller.model.TouchAimCalibrationProfile,
        propertyDialog: AlertDialog
    ) {
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        fun action(label: String, block: () -> Unit) {
            largeActionButton(actions, label).setOnClickListener { block() }
        }
        var actionDialog: AlertDialog? = null
        val applyButton = largeActionButton(actions, "Apply to this TouchAim")
        val allowUnreliableBestGuess =
            profile.quality == com.example.simplecontroller.model.TouchAimCalibrationQuality.UNRELIABLE
        applyButton.isEnabled = profile.isApplicable(allowUnreliableBestGuess)
        applyButton.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Apply ${profile.name}?")
                .setMessage(
                    (when (profile.quality) {
                        com.example.simplecontroller.model.TouchAimCalibrationQuality.BORDERLINE ->
                            "Warning: this profile was rated Borderline. Validate it cautiously for accidental firing.\n\n"
                        com.example.simplecontroller.model.TouchAimCalibrationQuality.UNRELIABLE ->
                            "Warning: this is an experimental best guess rated Unreliable. Apply it only as a starting point, then edit the thresholds and smoothing cautiously.\n\n"
                        else -> ""
                    }) +
                        "Calibration only keeps this control's current aiming settings, sensitivities, payloads, and Shoot action. " +
                        "Apply all also replaces those settings, including stick origin, displacement, and deadzone, with the saved profile values."
                )
                .setPositiveButton("Calibration only") { _, _ ->
                    if (applyCalibrationDetectionOnly(profile)) finishCalibrationApply(actionDialog, propertyDialog)
                }
                .setNeutralButton("Apply all saved settings") { _, _ ->
                    if (profile.applyTo(model, allowUnreliableBestGuess)) {
                        finishCalibrationApply(actionDialog, propertyDialog)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        action("Re-run this calibration") {
            actionDialog?.dismiss()
            propertyDialog.dismiss()
            (context as? MainActivity)?.startTouchAimCalibration(model.id, profile)
        }
        action("Rename") {
            val input = EditText(context).apply { setText(profile.name); selectAll() }
            AlertDialog.Builder(context)
                .setTitle("Rename calibration")
                .setView(input)
                .setPositiveButton("Rename") { _, _ ->
                    val result = TouchAimCalibrationStore.rename(
                        context,
                        profile.id,
                        input.text.toString(),
                        System.currentTimeMillis()
                    )
                    Toast.makeText(context, result.error ?: "Calibration renamed", Toast.LENGTH_LONG).show()
                    if (result.succeeded && model.touchAimAppliedCalibrationId == profile.id) {
                        model.touchAimAppliedCalibrationName = result.profile?.name.orEmpty()
                        onPropertiesUpdated()
                    }
                    actionDialog?.dismiss()
                    propertyDialog.dismiss()
                    reopenPropertySheet()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        action("Duplicate") {
            val input = EditText(context).apply {
                setText("${profile.name} copy")
                selectAll()
            }
            AlertDialog.Builder(context)
                .setTitle("Duplicate calibration")
                .setView(input)
                .setPositiveButton("Duplicate") { _, _ ->
                    val result = TouchAimCalibrationStore.duplicate(
                        context,
                        profile.id,
                        input.text.toString(),
                        System.currentTimeMillis()
                    )
                    Toast.makeText(context, result.error ?: "Calibration duplicated", Toast.LENGTH_LONG).show()
                    actionDialog?.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        action("Delete") {
            AlertDialog.Builder(context)
                .setTitle("Delete calibration?")
                .setMessage("The TouchAim control keeps its copied settings. Only the reusable saved calibration is deleted.")
                .setPositiveButton("Delete") { _, _ ->
                    val deleted = TouchAimCalibrationStore.delete(context, profile.id)
                    if (deleted && model.touchAimAppliedCalibrationId == profile.id) {
                        model.touchAimAppliedCalibrationId = ""
                        model.touchAimAppliedCalibrationName = ""
                        onPropertiesUpdated()
                    }
                    Toast.makeText(
                        context,
                        if (deleted) "Calibration deleted; control settings retained" else "Could not delete calibration",
                        Toast.LENGTH_LONG
                    ).show()
                    actionDialog?.dismiss()
                    propertyDialog.dismiss()
                    reopenPropertySheet()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        actionDialog = AlertDialog.Builder(context)
            .setTitle(profile.name)
            .setMessage(
                "${profile.quality.name.lowercase().replaceFirstChar { it.uppercase() }} — " +
                    "false activations ${(profile.estimatedFalseActivationRate * 100).roundToInt()}%, " +
                    "missed ${(profile.estimatedMissedActivationRate * 100).roundToInt()}%"
            )
            .setView(ScrollView(context).apply { addView(actions) })
            .setNegativeButton("Back", null)
            .show()
    }

    private fun finishCalibrationApply(actionDialog: AlertDialog?, propertyDialog: AlertDialog) {
        onPropertiesUpdated()
        actionDialog?.dismiss()
        propertyDialog.dismiss()
        reopenPropertySheet()
    }

    private fun applyCalibrationDetectionOnly(
        profile: com.example.simplecontroller.model.TouchAimCalibrationProfile
    ): Boolean {
        val allowUnreliableBestGuess =
            profile.quality == com.example.simplecontroller.model.TouchAimCalibrationQuality.UNRELIABLE
        if (!profile.isApplicable(allowUnreliableBestGuess)) {
            Toast.makeText(context, "This calibration is incomplete or invalid and cannot be applied.", Toast.LENGTH_LONG).show()
            return false
        }
        model.touchAimMode = TouchAimMode.CALIBRATED_TWO_STATE
        model.touchAimAppliedCalibrationId = profile.id
        model.touchAimAppliedCalibrationName = profile.name
        model.touchAimSensorTransforms = profile.sensors
        model.touchAimTwoStateSmoothing = profile.smoothing
        model.touchAimShootOnThreshold = profile.shootOnThreshold
        model.touchAimShootOffThreshold = profile.shootOffThreshold
        model.touchAimEnterShootConfirmationMs = profile.enterShootConfirmationMs
        model.touchAimReturnToAimConfirmationMs = profile.returnToAimConfirmationMs
        return true
    }

    private fun reopenPropertySheet() {
        (context as? MainActivity)
            ?.findViewById<FrameLayout>(R.id.canvas)
            ?.children
            ?.filterIsInstance<ControlView>()
            ?.firstOrNull { it.model.id == model.id }
            ?.post { reopenPropertySheetView() }
    }

    private fun reopenPropertySheetView() {
        (context as? MainActivity)
            ?.findViewById<FrameLayout>(R.id.canvas)
            ?.children
            ?.filterIsInstance<ControlView>()
            ?.firstOrNull { it.model.id == model.id }
            ?.showProps()
    }

    private fun addChoice(
        container: LinearLayout,
        label: String,
        choices: List<String>,
        selection: Int
    ): Spinner {
        container.addView(TextView(context).apply { text = label })
        return Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, choices)
            setSelection(selection.coerceIn(choices.indices))
            container.addView(this)
            container.addView(createGap())
        }
    }

    private fun addActionChoice(container: LinearLayout, action: TouchStageAction): Spinner =
        addChoice(
            container,
            "Action",
            listOf("Press once", "Hold"),
            if (action == TouchStageAction.PRESS) 0 else 1
        )

    private fun addDecimalField(
        container: LinearLayout,
        label: String,
        value: Float
    ): EditText {
        container.addView(TextView(context).apply { text = label })
        return EditText(context).apply {
            setText("%.3f".format(value))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            container.addView(this)
            container.addView(createGap())
        }
    }

    private fun addCommandField(
        container: LinearLayout,
        label: String,
        value: String
    ): EditText {
        container.addView(TextView(context).apply { text = label })
        return EditText(context).apply {
            setText(value)
            hint = "Comma-separated commands"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            container.addView(this)
            container.addView(createGap())
        }
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
        val maxSize = if (
            model.type == ControlType.TOUCHPAD || model.type == ControlType.TOUCH_AIM
        ) 1200 else 600
        
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
    private fun addPayloadControl(
        container: LinearLayout,
        initialValue: String = model.payload,
        fieldHint: String = "payload (comma-sep)"
    ): AutoCompleteTextView {
        return AutoCompleteTextView(context).apply {
            hint = fieldHint
            setText(initialValue)
            inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS
            
            // Set up autocomplete suggestions
            val suggestions = arrayOf(
                "A_PRESSED", "B_PRESSED", "X_PRESSED", "Y_PRESSED",
                "START", "SELECT", "UP", "DOWN", "LEFT", "RIGHT",
                "X360A", "X360B", "X360X", "X360Y",
                "X360LB", "X360RB", "X360START", "X360BACK",
                "X360UP", "X360DOWN", "X360LEFT", "X360RIGHT",
                "X360LS", "X360RS",
                "CAMERA_FOLLOW", "RELEASE_ALL"
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
        if (model.type == ControlType.BUTTON &&
            model.name.isBlank() &&
            model.payload.startsWith("CAMERA_FOLLOW", ignoreCase = true)
        ) {
            model.name = "Camera Follow"
        }
        if (model.type == ControlType.BUTTON &&
            model.name.isBlank() &&
            model.payload.equals("RELEASE_ALL", ignoreCase = true)
        ) {
            model.name = "Release All"
        }
        
        // Button-specific properties
        if (model.type == ControlType.BUTTON) {
            model.holdToggle = components.holdToggle.isChecked
            model.holdDurationMs = components.holdDurationField.text.toString().toLongOrNull() ?: 400L
            val autoTapRequested = components.autoTapEnabled.isChecked
            val autoTapBlockedByAim = components.buttonAim?.enabled?.isChecked == true
            val autoTapPayloadError = autoTapUnsupportedPayloadReason(model.payload)
            model.autoTapEnabled = autoTapRequested && !autoTapBlockedByAim &&
                autoTapPayloadError == null
            model.autoTapIntervalMs = components.autoTapIntervalMs.text.toString()
                .toLongOrNull()
                ?.coerceAtLeast(ButtonAutoTapController.MIN_INTERVAL_MS)
                ?: model.autoTapIntervalMs
            if (autoTapRequested && (autoTapBlockedByAim || autoTapPayloadError != null)) {
                Toast.makeText(
                    context,
                    autoTapPayloadError
                        ?: "Toggle auto-tap is available only when Aim while pressed is off.",
                    Toast.LENGTH_LONG
                ).show()
            }
            model.swipeActivate = components.swipeActivate.isChecked
            components.buttonAim?.let(::saveButtonAimProperties)
            components.pageAction?.let { fields ->
                val action = PageAction.entries[
                    fields.action.selectedItemPosition.coerceIn(0, PageAction.entries.lastIndex)
                ]
                model.pageAction = action
                model.pageTargetId = if (pageActionNeedsTarget(action)) {
                    fields.targetIds.getOrNull(fields.target.selectedItemPosition).orEmpty()
                } else ""
                if (action != PageAction.NONE) {
                    model.holdToggle = false
                    model.autoTapEnabled = false
                    model.buttonAimEnabled = false
                    model.buttonAimOneShotAlternateEnabled = false
                    model.buttonAimPayloadTiming = ButtonAimPayloadTiming.IMMEDIATE
                }
            }
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

        if (model.type == ControlType.TOUCH_AIM) {
            components.touchAim?.let(::saveTouchAimProperties)
        }
        
        // Stick-specific directional mode properties
        if (model.type == ControlType.STICK || model.type == ControlType.CURVED_STICK) {
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

    private fun saveTouchAimProperties(fields: TouchAimComponents) {
        val requestedMode = when (fields.mode.selectedItemPosition) {
            1 -> TouchAimMode.MANUAL_TWO_STATE
            2 -> if (model.touchAimSensorTransforms.isNotEmpty()) {
                TouchAimMode.CALIBRATED_TWO_STATE
            } else {
                TouchAimMode.MANUAL_THREE_STAGE
            }
            else -> TouchAimMode.MANUAL_THREE_STAGE
        }
        if (fields.mode.selectedItemPosition == 2 && model.touchAimSensorTransforms.isEmpty()) {
            Toast.makeText(context, "No calibration is available, so the control stayed in Manual three-stage. Choose Manual two-state explicitly if that is what you want.", Toast.LENGTH_LONG).show()
        }
        model.touchAimOutput = when (fields.aimOutput.selectedItemPosition) {
            1 -> TouchAimOutput.RIGHT_STICK
            2 -> TouchAimOutput.LEFT_STICK
            else -> TouchAimOutput.MOUSE
        }
        model.touchUseSize = fields.useSize.isChecked
        model.touchUseMajor = fields.useMajor.isChecked
        model.touchUseMinor = fields.useMinor.isChecked
        model.touchSizeScale = fields.sizeScale.floatValue(model.touchSizeScale).coerceAtLeast(0f)
        model.touchScoreSmoothing = fields.smoothing.floatValue(model.touchScoreSmoothing)
            .coerceIn(0.05f, 1f)
        val hasEffectiveManualSensor = model.touchUseMajor || model.touchUseMinor ||
            (model.touchUseSize && model.touchSizeScale > 0f)
        model.touchAimMode = if (requestedMode == TouchAimMode.MANUAL_TWO_STATE &&
            !hasEffectiveManualSensor) {
            Toast.makeText(
                context,
                "Manual two-state needs Major, Minor, or Size with a multiplier above zero. The control stayed in Manual three-stage.",
                Toast.LENGTH_LONG
            ).show()
            TouchAimMode.MANUAL_THREE_STAGE
        } else {
            requestedMode
        }

        val low = fields.lowThreshold.floatValue(model.touchLowThreshold).coerceAtLeast(0f)
        val medium = max(fields.mediumThreshold.floatValue(model.touchMediumThreshold), low + 0.01f)
        val high = max(fields.highThreshold.floatValue(model.touchHighThreshold), medium + 0.01f)
        model.touchLowThreshold = low
        model.touchMediumThreshold = medium
        model.touchHighThreshold = high
        model.touchHysteresis = fields.hysteresis.floatValue(model.touchHysteresis).coerceAtLeast(0f)

        model.touchLowPayload = fields.lowPayload.text.toString().trim()
        model.touchMediumPayload = fields.mediumPayload.text.toString().trim()
        model.touchHighPayload = fields.highPayload.text.toString().trim()
        model.touchLowAction = fields.lowAction.selectedAction()
        model.touchMediumAction = fields.mediumAction.selectedAction()
        model.touchHighAction = fields.highAction.selectedAction()
        model.touchKeepLowerHolds = fields.keepLowerHolds.isChecked
        model.touchStickFullSpeed = fields.stickFullSpeed.floatValue(model.touchStickFullSpeed)
            .coerceAtLeast(1f)
        model.touchInvertY = fields.invertY.isChecked
        model.touchUseResponseCurve = fields.useResponseCurve.isChecked
        model.touchAimStickUsesTouchPosition = fields.stickUsesTouchPosition.isChecked
        val fullDisplacement = fields.stickFullDisplacement
            .floatValue(model.touchAimStickFullDisplacementPx)
            .coerceAtLeast(1f)
        model.touchAimStickFullDisplacementPx = fullDisplacement
        model.touchAimStickDeadzonePx = fields.stickDeadzone
            .floatValue(model.touchAimStickDeadzonePx)
            .coerceIn(0f, (fullDisplacement - 1f).coerceAtLeast(0f))

        model.touchAimAimPayload = fields.aimPayload.text.toString().trim()
        model.touchAimShootPayload = fields.shootPayload.text.toString().trim()
        model.touchAimKeepAimPayloadWhileShooting = fields.keepAimPayload.isChecked
        model.touchAimShootSensitivity = fields.shootSensitivity
            .floatValue(model.touchAimShootSensitivity)
            .coerceIn(0f, 5f)
        model.touchAimShootBehavior = when (fields.shootBehavior.selectedItemPosition) {
            1 -> TouchAimShootBehavior.PRESS_ON_ENTER
            2 -> TouchAimShootBehavior.PRESS_ON_RETURN_TO_AIM
            else -> TouchAimShootBehavior.HOLD_WHILE_ABOVE
        }
        val shootOn = fields.shootOnThreshold.floatValue(model.touchAimShootOnThreshold)
        val shootOff = fields.shootOffThreshold.floatValue(model.touchAimShootOffThreshold)
        model.touchAimShootOnThreshold = shootOn
        model.touchAimShootOffThreshold = shootOff.coerceAtMost(shootOn - 0.01f)
        if (model.touchAimManualThresholdsInitialized ||
            requestedMode == TouchAimMode.MANUAL_TWO_STATE) {
            val manualShootOn = fields.manualShootOnThreshold
                .floatValue(model.touchAimManualShootOnThreshold)
                .coerceAtLeast(0.01f)
            val manualShootOff = fields.manualShootOffThreshold
                .floatValue(model.touchAimManualShootOffThreshold)
                .coerceIn(0f, manualShootOn - 0.01f)
            model.touchAimManualShootOnThreshold = manualShootOn
            model.touchAimManualShootOffThreshold = manualShootOff
            model.touchAimManualThresholdsInitialized = true
        }
        model.touchAimTwoStateSmoothing = fields.twoStateSmoothing
            .floatValue(model.touchAimTwoStateSmoothing)
            .coerceIn(0.05f, 1f)
        model.touchAimEnterShootConfirmationMs = fields.enterConfirmationMs.text.toString()
            .toLongOrNull()?.coerceIn(32L, 2000L) ?: model.touchAimEnterShootConfirmationMs
        model.touchAimReturnToAimConfirmationMs = fields.returnConfirmationMs.text.toString()
            .toLongOrNull()?.coerceIn(32L, 2000L) ?: model.touchAimReturnToAimConfirmationMs

        val applied = model.touchAimAppliedCalibrationId.takeIf { it.isNotBlank() }
            ?.let { TouchAimCalibrationStore.get(context, it) }
        if (model.touchAimMode != TouchAimMode.CALIBRATED_TWO_STATE ||
            applied == null || !model.matchesAppliedCalibration(applied)
        ) {
            model.touchAimAppliedCalibrationId = ""
            model.touchAimAppliedCalibrationName = ""
        }
    }

    private fun Control.matchesAppliedCalibration(
        profile: com.example.simplecontroller.model.TouchAimCalibrationProfile
    ): Boolean = touchAimSensorTransforms == profile.sensors &&
        touchAimTwoStateSmoothing == profile.smoothing &&
        touchAimShootOnThreshold == profile.shootOnThreshold &&
        touchAimShootOffThreshold == profile.shootOffThreshold &&
        touchAimEnterShootConfirmationMs == profile.enterShootConfirmationMs &&
        touchAimReturnToAimConfirmationMs == profile.returnToAimConfirmationMs

    private fun saveButtonAimProperties(fields: ButtonAimComponents) {
        model.buttonAimEnabled = fields.enabled.isChecked
        model.buttonAimOutput = when (fields.aimOutput.selectedItemPosition) {
            1 -> ButtonAimOutput.RIGHT_STICK
            2 -> ButtonAimOutput.LEFT_STICK
            3 -> ButtonAimOutput.DPAD
            else -> ButtonAimOutput.MOUSE
        }
        model.buttonAimPayloadTiming = if (fields.payloadTiming.selectedItemPosition == 0) {
            ButtonAimPayloadTiming.IMMEDIATE
        } else {
            ButtonAimPayloadTiming.SEND_ON_RELEASE
        }
        model.buttonAimReleaseDelayMs = fields.releaseDelayMs.text.toString()
            .toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: model.buttonAimReleaseDelayMs
        model.buttonAimSensitivity = fields.sensitivity.floatValue(model.buttonAimSensitivity)
            .coerceAtLeast(0f)
        model.buttonAimInvertY = fields.invertY.isChecked
        model.buttonAimStickProfile = if (fields.stickProfile.selectedItemPosition == 0) {
            ButtonAimStickProfile.LINEAR
        } else {
            ButtonAimStickProfile.RESPONSE_CURVE
        }
        model.buttonAimMouseProfile = if (fields.mouseProfile.selectedItemPosition == 0) {
            ButtonAimMouseProfile.LINEAR_RELATIVE
        } else {
            ButtonAimMouseProfile.SMOOTHED_NONLINEAR
        }
        val fullDisplacement = fields.stickFullDisplacement
            .floatValue(model.buttonAimStickFullDisplacementPx)
            .coerceAtLeast(1f)
        model.buttonAimStickFullDisplacementPx = fullDisplacement
        model.buttonAimStickDeadzonePx = fields.stickDeadzone
            .floatValue(model.buttonAimStickDeadzonePx)
            .coerceIn(0f, (fullDisplacement - 1f).coerceAtLeast(0f))
        model.buttonAimStickUsesTouchPosition = fields.stickUsesTouchPosition.isChecked
        model.buttonAimDpadMode = if (fields.dpadMode.selectedItemPosition == 0) {
            ButtonAimDpadMode.FOUR_WAY
        } else {
            ButtonAimDpadMode.EIGHT_WAY
        }
        model.buttonAimDpadOrigin = if (fields.dpadOrigin.selectedItemPosition == 0) {
            // Labels are intentionally reversed at the user's request.
            ButtonAimDpadOrigin.CONTROL_CENTER
        } else {
            ButtonAimDpadOrigin.INITIAL_TOUCH
        }
        model.buttonAimDpadActivationDistancePx = fields.dpadActivationDistance
            .floatValue(model.buttonAimDpadActivationDistancePx)
            .coerceAtLeast(0f)
        model.buttonAimHaptics = fields.haptics.isChecked

        val alternatePayload = fields.alternatePayload.text.toString().trim()
        val requestedOneShot = fields.oneShotAlternateEnabled.isChecked
        model.buttonAimOneShotAlternateEnabled = requestedOneShot && alternatePayload.isNotEmpty()
        model.buttonAimAlternatePayload = alternatePayload
        if (requestedOneShot && alternatePayload.isEmpty()) {
            Toast.makeText(
                context,
                "One-shot alternate was left off because its payload is blank.",
                Toast.LENGTH_LONG
            ).show()
        }
        model.buttonAimAlternatePayloadTiming =
            if (fields.alternatePayloadTiming.selectedItemPosition == 0) {
                ButtonAimPayloadTiming.IMMEDIATE
            } else {
                ButtonAimPayloadTiming.SEND_ON_RELEASE
            }
        model.buttonAimAlternateResetHoldDurationMs = fields.alternateResetHoldDurationMs.text
            .toString()
            .toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: model.buttonAimAlternateResetHoldDurationMs
        model.buttonAimAlternateBaseUnlatchDelayMs = fields.alternateBaseUnlatchDelayMs.text
            .toString()
            .toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: model.buttonAimAlternateBaseUnlatchDelayMs
        model.buttonAimAlternateDisplayName = fields.alternateDisplayName.text.toString().trim()
        model.buttonAimAlternateOutput = when (fields.alternateOutput.selectedItemPosition) {
            1 -> ButtonAimOutput.RIGHT_STICK
            2 -> ButtonAimOutput.LEFT_STICK
            3 -> ButtonAimOutput.DPAD
            else -> ButtonAimOutput.MOUSE
        }
        model.buttonAimAlternateSensitivity = fields.alternateSensitivity
            .floatValue(model.buttonAimAlternateSensitivity)
            .coerceAtLeast(0f)
        model.buttonAimAlternateInvertY = fields.alternateInvertY.isChecked
        model.buttonAimAlternateStickProfile =
            if (fields.alternateStickProfile.selectedItemPosition == 0) {
                ButtonAimStickProfile.LINEAR
            } else {
                ButtonAimStickProfile.RESPONSE_CURVE
            }
        model.buttonAimAlternateMouseProfile =
            if (fields.alternateMouseProfile.selectedItemPosition == 0) {
                ButtonAimMouseProfile.LINEAR_RELATIVE
            } else {
                ButtonAimMouseProfile.SMOOTHED_NONLINEAR
            }
        val alternateFullDisplacement = fields.alternateStickFullDisplacement
            .floatValue(model.buttonAimAlternateStickFullDisplacementPx)
            .coerceAtLeast(1f)
        model.buttonAimAlternateStickFullDisplacementPx = alternateFullDisplacement
        model.buttonAimAlternateStickDeadzonePx = fields.alternateStickDeadzone
            .floatValue(model.buttonAimAlternateStickDeadzonePx)
            .coerceIn(0f, (alternateFullDisplacement - 1f).coerceAtLeast(0f))
        model.buttonAimAlternateStickUsesTouchPosition =
            fields.alternateStickUsesTouchPosition.isChecked
        model.buttonAimAlternateDpadMode =
            if (fields.alternateDpadMode.selectedItemPosition == 0) {
                ButtonAimDpadMode.FOUR_WAY
            } else {
                ButtonAimDpadMode.EIGHT_WAY
            }
        model.buttonAimAlternateDpadOrigin =
            if (fields.alternateDpadOrigin.selectedItemPosition == 0) {
                // Labels are intentionally reversed at the user's request.
                ButtonAimDpadOrigin.CONTROL_CENTER
            } else {
                ButtonAimDpadOrigin.INITIAL_TOUCH
            }
        model.buttonAimAlternateDpadActivationDistancePx =
            fields.alternateDpadActivationDistance
                .floatValue(model.buttonAimAlternateDpadActivationDistancePx)
                .coerceAtLeast(0f)
        model.buttonAimAlternateHaptics = fields.alternateHaptics.isChecked
    }

    private fun EditText.floatValue(fallback: Float): Float =
        text.toString().toFloatOrNull()?.takeIf(Float::isFinite) ?: fallback

    private fun Spinner.selectedAction(): TouchStageAction =
        if (selectedItemPosition == 0) TouchStageAction.PRESS else TouchStageAction.HOLD
    
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
