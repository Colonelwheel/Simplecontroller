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
     * Create a new control of the specified type
     */
    fun createControl(type: ControlType) {
        val w = if (type == ControlType.BUTTON) 140f else 220f
        val id = "${type.name.lowercase()}_${System.currentTimeMillis()}"

        // Calculate center position
        val cw = (canvas.width.takeIf { it > 0 } ?: canvas.measuredWidth).coerceAtLeast(1)
        val ch = (canvas.height.takeIf { it > 0 } ?: canvas.measuredHeight).coerceAtLeast(1)
        val x0 = ((cw - w) / 2f).coerceAtLeast(80f)
        val y0 = ((ch - w) / 2f).coerceAtLeast(80f)

        // Create control model with default payload
        val payload = when(type) {
            ControlType.BUTTON -> "BUTTON_PRESSED"
            ControlType.STICK -> "STICK"
            ControlType.TOUCHPAD -> "TOUCHPAD"
            ControlType.RECENTER -> "RECENTER"
        }

        // Create control model
        val c = Control(
            id = id, type = type,
            x = x0, y = y0, w = w, h = w,
            payload = payload
        )
        controls.add(c)

        // Create and add view
        val view = controlCreator(c)
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
                    saveControls(context, name, controls)
                    toast("Saved as \"$name\"")
                    callback?.onLayoutSaved(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show the load layout dialog
     */
    fun showLoadDialog() {
        val names = listLayouts(context)
        if (names.isEmpty()) {
            toast("No saved layouts")
            return
        }

        AlertDialog.Builder(context)
            .setTitle("Load layout")
            .setItems(names.toTypedArray()) { _, i ->
                val sel = names[i]
                loadControls(context, sel)?.let { loadedControls ->
                    // Clear existing controls and views
                    callback?.clearControlViews()
                    controls.clear()

                    // Add loaded controls
                    controls.addAll(loadedControls)
                    spawnControlViews()

                    // Notify callback
                    callback?.onLayoutLoaded(sel)
                    toast("Loaded \"$sel\"")
                } ?: toast("Failed to load \"$sel\"")
            }
            .show()
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
     * Show a toast message
     */
    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}