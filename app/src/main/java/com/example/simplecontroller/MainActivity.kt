package com.example.simplecontroller

import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Switch
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import com.example.simplecontroller.io.LayoutManager
import com.example.simplecontroller.io.loadControls
import com.example.simplecontroller.io.saveControls
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.ui.ControlView
import com.example.simplecontroller.ui.GlobalSettings
import com.example.simplecontroller.ui.SwipeManager
import com.example.simplecontroller.ui.UIComponentBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Refactored MainActivity that uses extracted components.
 *
 * This version delegates specialized functionality to helper classes for:
 * - UI component creation and management
 * - Layout loading and saving
 * - Swipe gesture handling
 */
class MainActivity : AppCompatActivity(), LayoutManager.LayoutCallback {

    /* ---------- persisted "current layout" name ---------- */
    private val prefs by lazy { getSharedPreferences("layout", MODE_PRIVATE) }
    private var layoutName: String
        get() = prefs.getString("current", "default") ?: "default"
        set(v) = prefs.edit().putString("current", v).apply()

    /* ---------- underlying data model ---------- */
    private val controls: MutableList<Control> by lazy {
        loadControls(this, layoutName)?.toMutableList()
            ?: layoutManager.defaultLayout().toMutableList()
    }

    /* ---------- helper classes ---------- */
    private lateinit var uiBuilder: UIComponentBuilder
    private lateinit var layoutManager: LayoutManager

    /* ---------- edit-only widgets we show/hide ---------- */
    private lateinit var btnSave: View
    private lateinit var btnLoad: View
    private lateinit var fabAdd: View

    /* ---------- global switches ---------- */
    private lateinit var switchSnap: Switch
    private lateinit var switchHold: Switch
    private lateinit var switchTurbo: Switch
    private lateinit var switchSwipe: Switch

    /* ---------- main canvas ---------- */
    private lateinit var canvas: FrameLayout

    // =============== life-cycle ======================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        canvas = findViewById(R.id.canvas)

        // Initialize helpers
        uiBuilder = UIComponentBuilder(this, canvas)
        layoutManager = LayoutManager(this, canvas, controls) { control ->
            ControlView(this, control)
        }
        layoutManager.setCallback(this)

        // Create UI
        setupUI()

        // Load controls
        layoutManager.spawnControlViews()
    }

    override fun onStart() {
        super.onStart()
        NetworkClient.start()
    }

    override fun onPause() {
        super.onPause()
        saveControls(this, layoutName, controls)   // auto-persist
        NetworkClient.close()
    }

    // Override dispatchTouchEvent to handle swipe mode
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // When swipe mode is active and we're not in edit mode, handle with SwipeManager
        if (GlobalSettings.globalSwipe && !GlobalSettings.editMode) {
            // If the manager processes it, we're done
            if (SwipeManager.processTouchEvent(ev)) {
                return true
            }
        }
        // Otherwise, use normal dispatch
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Set up all UI components
     */
    private fun setupUI() {
        /* --- Edit toggle ------------------------------------------------ */
        uiBuilder.addCornerButton("Edit", Gravity.TOP or Gravity.END) { v ->
            GlobalSettings.editMode = !GlobalSettings.editMode
            (v as? android.widget.Button)?.text = if (GlobalSettings.editMode) "Done" else "Edit"
            updateEditUi(GlobalSettings.editMode)
        }

        /* --- Switches --------------------------------------------------- */
        switchSnap = uiBuilder.createSwitch("Snap", true) { on ->
            GlobalSettings.snapEnabled = on
            controls.filter { it.type != ControlType.BUTTON }
                .forEach { it.autoCenter = on }
        }

        switchHold = uiBuilder.createSwitch("Hold", false) { on ->
            GlobalSettings.globalHold = on
        }

        switchTurbo = uiBuilder.createSwitch("Turbo", false) { on ->
            GlobalSettings.globalTurbo = on
        }

        switchSwipe = uiBuilder.createSwitch("Swipe", false) { on ->
            GlobalSettings.globalSwipe = on
        }

        // Add all switches to canvas
        uiBuilder.addVerticalSwitches(
            listOf(switchSnap, switchHold, switchTurbo, switchSwipe),
            Gravity.TOP or Gravity.START,
            16, 16, 48
        )

        /* --- Save / Load ------------------------------------------------- */
        btnSave = uiBuilder.addCornerButton("Save", Gravity.BOTTOM or Gravity.START) {
            layoutManager.showSaveDialog()
        }

        btnLoad = uiBuilder.addCornerButton("Load", Gravity.BOTTOM or Gravity.END) {
            layoutManager.showLoadDialog()
        }

        /* --- Add-FAB ---------------------------------------------------- */
        fabAdd = uiBuilder.addFloatingActionButton(
            android.R.drawable.ic_input_add,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            0, 90
        ) {
            showAddPicker()
        }

        // Hide edit widgets by default
        updateEditUi(GlobalSettings.editMode)
    }

    /**
     * Show picker dialog to add a new control
     */
    private fun showAddPicker() {
        val types = arrayOf("Button", "Stick", "TouchPad", "Re-center Button")
        AlertDialog.Builder(this)
            .setTitle("Add control…")
            .setItems(types) { _, i ->
                val type = when (i) {
                    0 -> ControlType.BUTTON
                    1 -> ControlType.STICK
                    2 -> ControlType.TOUCHPAD
                    3 -> ControlType.RECENTER
                    else -> ControlType.BUTTON
                }
                layoutManager.createControl(type)
            }
            .show()
    }

    /**
     * Update UI components when edit mode changes
     */
    private fun updateEditUi(edit: Boolean) {
        val editWidgets = listOf(btnSave, btnLoad, fabAdd)
        uiBuilder.updateViewsVisibility(editWidgets, edit)

        // Global switches stay visible in both modes
        val globalSwitches = listOf(switchSnap, switchHold, switchTurbo, switchSwipe)
        uiBuilder.updateViewsVisibility(globalSwitches, true)
    }

    /**
     * Create a control from a model (used for duplication)
     */
    fun createControlFrom(src: Control) {
        layoutManager.createControlFrom(src)
    }

    /**
     * Remove a control from the layout
     */
    fun removeControl(c: Control) {
        layoutManager.removeControl(c)
    }

    // === LayoutManager.LayoutCallback implementation ===

    /**
     * Called when a layout is loaded
     */
    override fun onLayoutLoaded(layoutName: String) {
        this.layoutName = layoutName
    }

    /**
     * Called when a layout is saved
     */
    override fun onLayoutSaved(layoutName: String) {
        this.layoutName = layoutName
    }

    /**
     * Clear all control views
     */
    override fun clearControlViews() {
        canvas.children.filter { it.tag == "control" }.toList()
            .forEach { canvas.removeView(it) }
    }
}