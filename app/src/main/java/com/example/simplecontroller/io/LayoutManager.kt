package com.example.simplecontroller.io

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControllerProfile
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.ui.ControlView

/**
 * Manages control layouts including saving, loading, and creation.
 *
 * This class encapsulates all layout-related functionality that was previously
 * in MainActivity, including:
 * - Saving/loading layouts to/from storage
 * - Creating default layouts
 * - Managing layout dialogs
 * - Creating controls from models
 */
class LayoutManager(
    private val context: Context,
    private val canvas: FrameLayout,
    private val controls: MutableList<Control>,
    private val controlCreator: (Control) -> ControlView
) {
    // Interface for layout operations callbacks
    interface LayoutCallback {
        fun onLayoutLoaded(layoutName: String)
        fun onLayoutSaved(layoutName: String)
        fun clearControlViews()
        fun activeLayoutName(): String? = null
        fun controllerProfileForSave(): ControllerProfile? = null
        fun onControllerProfileLoaded(
            layoutName: String,
            result: ControllerProfileLoadResult
        ): Boolean = false
        fun onNewControllerProfileRequested(): Boolean = false
    }

    // Callback handler
    private var callback: LayoutCallback? = null

    /**
     * Set the callback for layout operations
     */
    fun setCallback(callback: LayoutCallback) {
        this.callback = callback
    }

    /**
     * Creates and returns a control view for the given control model
     */
    fun createControlView(control: Control): ControlView {
        return controlCreator(control).apply { tag = "control" }
    }

    /**
     * Create control views for all controls in the list
     */
    fun spawnControlViews() {
        controls.forEach { control ->
            canvas.addView(controlCreator(control).apply { tag = "control" })
        }
    }


    /**
     * Update the controls list
     */
    fun updateControls(newControls: MutableList<Control>) {
        controls.clear()
        controls.addAll(newControls)
    }


    /**
     * Create a new control of the specified type
     */
    fun createControl(type: ControlType) {
        val w = when (type) {
            ControlType.BUTTON -> 140f
            ControlType.TOUCH_AIM -> 500f
            else -> 220f
        }
        val h = if (type == ControlType.TOUCH_AIM) 320f else w
        val id = "${type.name.lowercase()}_${System.currentTimeMillis()}"

        // Calculate center position
        val cw = (canvas.width.takeIf { it > 0 } ?: canvas.measuredWidth).coerceAtLeast(1)
        val ch = (canvas.height.takeIf { it > 0 } ?: canvas.measuredHeight).coerceAtLeast(1)
        val x0 = ((cw - w) / 2f).coerceAtLeast(80f)
        val y0 = ((ch - h) / 2f).coerceAtLeast(80f)

        // Create control model with default payload
        val payload = when(type) {
            ControlType.BUTTON -> "X360"
            ControlType.STICK -> "STICK"
            ControlType.CURVED_STICK -> "STICK"
            ControlType.TOUCHPAD -> "TOUCHPAD"
            ControlType.TOUCH_AIM -> "TOUCH_AIM"
            ControlType.RECENTER -> "RECENTER"
        }

        // Create control model
        val c = Control(
            id = id, type = type,
            x = x0, y = y0, w = w, h = h,
            payload = payload
        )
        controls.add(c)

        // Create and add view
        val view = createControlView(c)   // already sets tag = "control"
        canvas.addView(view)

        // Show properties dialog
        view.post { view.showProps() }
    }

    /**
     * Show the save layout dialog
     */
    fun showSaveDialog() {
        val input = EditText(context).apply { hint = "layout name" }
        AlertDialog.Builder(context)
            .setTitle("Save layout as…")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val result = callback?.controllerProfileForSave()?.let {
                        saveControllerProfile(context, name, it)
                    } ?: saveControls(context, name, controls)
                    if (result.isSuccess) {
                        toast("Saved as \"$name\"")
                        callback?.onLayoutSaved(name)
                    } else {
                        toast("Failed to save \"$name\"")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show the load layout dialog
     */
    fun showLoadDialog() {
        val savedNames = listLayouts(context)
        
        // Add "New Layout" option at the top
        val displayNames = mutableListOf("New Layout")
        displayNames.addAll(savedNames)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Load layout")
            .setItems(displayNames.toTypedArray()) { _, i ->
                if (i == 0) {
                    // "New Layout" option - create blank layout
                    if (callback?.onNewControllerProfileRequested() != true) createNewLayout()
                } else {
                    // Regular saved layout
                    val sel = savedNames[i - 1]
                    when (val stored = readControllerProfile(context, sel)) {
                        is StoredControllerProfileResult.Loaded -> {
                            val result = stored.result
                            if (callback?.onControllerProfileLoaded(sel, result) == true) {
                                toast("Loaded \"$sel\"")
                            } else {
                                val profile = result.profile
                                val loadedControls = profile.pages
                                    .firstOrNull { it.id == profile.homePageId }?.controls
                                    ?: profile.pages.firstOrNull()?.controls.orEmpty()
                                callback?.clearControlViews()
                                controls.clear()
                                controls.addAll(loadedControls)
                                spawnControlViews()
                                callback?.onLayoutLoaded(sel)
                                toast("Loaded \"$sel\"")
                            }
                        }
                        StoredControllerProfileResult.NotFound -> toast("\"$sel\" no longer exists")
                        is StoredControllerProfileResult.Error ->
                            toast("Failed to load \"$sel\"; its file was not changed")
                    }
                }
            }
            .create()

        // Set up long press detection on list items (skip "New Layout" option)
        dialog.setOnShowListener {
            val listView = dialog.listView
            listView?.setOnItemLongClickListener { _, _, position, _ ->
                if (position > 0) { // Skip "New Layout" option
                    val layoutName = savedNames[position - 1]
                    showLayoutContextMenu(layoutName) {
                        // Refresh the dialog with updated names
                        dialog.dismiss()
                        showLoadDialog()
                    }
                    true
                } else {
                    false // Don't handle long press on "New Layout"
                }
            }
        }

        dialog.show()
    }

    /**
     * Create a default layout if none exists
     */
    fun defaultLayout(): List<Control> = listOf(
        Control(
            id = "btnA", type = ControlType.BUTTON,
            x = 300f, y = 900f, w = 140f, h = 140f,
            payload = "BUTTON_A_PRESSED"
        ),
        Control(
            id = "stickL", type = ControlType.STICK,
            x = 80f, y = 600f, w = 220f, h = 220f,
            payload = "STICK_L"
        )
    )

    /**
     * Add a new control from an existing model
     * Used when duplicating controls
     */
    fun createControlFrom(src: Control) {
        controls.add(src)
        canvas.addView(controlCreator(src).apply { tag = "control" })
    }

    /**
     * Remove a control
     */
    fun removeControl(c: Control) {
        controls.remove(c)
    }

    /**
     * Create a new blank layout
     */
    private fun createNewLayout() {
        // Clear existing controls and views
        callback?.clearControlViews()
        controls.clear()
        
        // Notify callback with a generic new layout name
        callback?.onLayoutLoaded("untitled")
        toast("New blank layout created")
    }

    /**
     * Show context menu for layout management
     */
    private fun showLayoutContextMenu(layoutName: String, onComplete: () -> Unit) {
        val options = arrayOf("Rename", "Duplicate", "Delete")
        
        AlertDialog.Builder(context)
            .setTitle("Manage \"$layoutName\"")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> renameLayout(layoutName, onComplete)
                    1 -> duplicateLayout(layoutName, onComplete)
                    2 -> deleteLayout(layoutName, onComplete)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Rename a layout
     */
    private fun renameLayout(oldName: String, onComplete: () -> Unit) {
        val input = EditText(context).apply { 
            setText(oldName)
            hint = "new layout name" 
        }
        
        AlertDialog.Builder(context)
            .setTitle("Rename layout")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && !newName.equals(oldName, ignoreCase = true)) {
                    // Load the layout data
                    val isActive = callback?.activeLayoutName()
                        ?.equals(oldName, ignoreCase = true) == true
                    val layoutData = if (isActive) {
                        callback?.controllerProfileForSave()
                    } else {
                        (readControllerProfile(context, oldName) as? StoredControllerProfileResult.Loaded)
                            ?.result?.profile
                    }
                    layoutData?.let {
                        // Save with new name
                        if (saveControllerProfile(context, newName, it).isSuccess) {
                            // Delete old file only after the replacement is safely written.
                            if (deleteControllerProfile(context, oldName)) {
                                if (isActive) callback?.onLayoutSaved(newName)
                                toast("Renamed \"$oldName\" to \"$newName\"")
                                onComplete()
                            } else toast("Saved \"$newName\", but could not delete \"$oldName\"")
                        } else toast("Failed to rename layout")
                    } ?: toast("Failed to rename layout")
                } else if (newName.equals(oldName, ignoreCase = true)) {
                    onComplete() // No change needed
                } else {
                    toast("Invalid name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Duplicate a layout
     */
    private fun duplicateLayout(layoutName: String, onComplete: () -> Unit) {
        val input = EditText(context).apply { 
            setText("${layoutName}_copy")
            hint = "duplicate name" 
        }
        
        AlertDialog.Builder(context)
            .setTitle("Duplicate layout")
            .setView(input)
            .setPositiveButton("Duplicate") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    // Load the layout data
                    val isActive = callback?.activeLayoutName()
                        ?.equals(layoutName, ignoreCase = true) == true
                    val layoutData = if (isActive) {
                        callback?.controllerProfileForSave()
                    } else {
                        (readControllerProfile(context, layoutName) as? StoredControllerProfileResult.Loaded)
                            ?.result?.profile
                    }
                    layoutData?.let {
                        // Save with new name
                        if (saveControllerProfile(context, newName, it).isSuccess) {
                            toast("Duplicated \"$layoutName\" as \"$newName\"")
                            onComplete()
                        } else toast("Failed to duplicate layout")
                    } ?: toast("Failed to duplicate layout")
                } else {
                    toast("Invalid name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Delete a layout
     */
    private fun deleteLayout(layoutName: String, onComplete: () -> Unit) {
        if (callback?.activeLayoutName()?.equals(layoutName, ignoreCase = true) == true) {
            toast("Load or save another profile before deleting the active one")
            return
        }
        AlertDialog.Builder(context)
            .setTitle("Delete layout")
            .setMessage("Are you sure you want to delete \"$layoutName\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (deleteControllerProfile(context, layoutName)) {
                    toast("Deleted \"$layoutName\"")
                    onComplete()
                } else {
                    toast("Failed to delete layout")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show a toast message
     */
    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
