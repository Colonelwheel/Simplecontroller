package com.example.simplecontroller

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), LayoutManager.LayoutCallback {

    /* ---------- persisted "current layout" name ---------- */
    private val prefs by lazy { getSharedPreferences("layout", MODE_PRIVATE) }
    private var layoutName: String
        get() = prefs.getString("current", "default") ?: "default"
        set(v) = prefs.edit().putString("current", v).apply()

    /* ---------- network settings prefs ---------- */
    private val networkPrefs by lazy { getSharedPreferences("network", MODE_PRIVATE) }

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

    /* ---------- connection UI ---------- */
    private lateinit var connectionStatusText: TextView
    private lateinit var btnConnect: Button

    /* ---------- main canvas ---------- */
    private lateinit var canvas: FrameLayout

    // =============== life-cycle ======================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        canvas = findViewById(R.id.canvas)
        connectionStatusText = findViewById(R.id.connectionStatus)

        // Initialize helpers
        uiBuilder = UIComponentBuilder(this, canvas)
        layoutManager = LayoutManager(this, canvas, controls) { control ->
            ControlView(this, control)
        }
        layoutManager.setCallback(this)

        // Create UI
        setupUI()

        // Setup connection status observer
        observeConnectionStatus()

        // Load controls
        layoutManager.spawnControlViews()

        // Load network settings
        loadNetworkSettings()
    }

    override fun onStart() {
        super.onStart()

        // Only auto-connect if auto-reconnect is enabled
        if (networkPrefs.getBoolean("autoReconnect", false)) {
            NetworkClient.start()
        }
    }

    override fun onPause() {
        super.onPause()
        saveControls(this, layoutName, controls)   // auto-persist
    }

    override fun onStop() {
        super.onStop()

        // Only disconnect if auto-reconnect is disabled
        if (!networkPrefs.getBoolean("autoReconnect", false)) {
            NetworkClient.close()
        }
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

    /* ---------- member variables for turbo speed control ---------- */
    private lateinit var turboSpeedControl: Pair<EditText, ImageButton>
    private var turboSpeed = 16L // Default speed in milliseconds (≈60 Hz)

    private fun setupUI() {
        /* --- Edit toggle ------------------------------------------------ */
        uiBuilder.addCornerButton("Edit", Gravity.TOP or Gravity.END) { v ->
            GlobalSettings.editMode = !GlobalSettings.editMode
            (v as? android.widget.Button)?.text = if (GlobalSettings.editMode) "Done" else "Edit"
            updateEditUi(GlobalSettings.editMode)
        }

        /* --- Connection button ------------------------------------------ */
        btnConnect = uiBuilder.addCornerButton(
            "Connect",                  // Label
            Gravity.TOP or Gravity.END, // Gravity
            16,                         // Horizontal margin
            64                          // Vertical margin
        ) {
            showConnectionSettingsDialog()
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

            // Show/hide the turbo speed control based on the switch state
            if (on) {
                turboSpeedControl.first.visibility = View.VISIBLE
                turboSpeedControl.second.visibility = View.VISIBLE
            } else {
                turboSpeedControl.first.visibility = View.GONE
                turboSpeedControl.second.visibility = View.GONE
            }
        }

        switchSwipe = uiBuilder.createSwitch("Swipe", false) { on ->
            GlobalSettings.globalSwipe = on
        }

        // Add all switches to canvas
        uiBuilder.addVerticalSwitches(
            listOf(switchSnap, switchHold, switchTurbo, switchSwipe),
            Gravity.TOP or Gravity.START,  // TOP instead of CENTER_VERTICAL
            16, 16, 48
        )

        // Add the turbo speed control (initially hidden)
        turboSpeedControl = uiBuilder.addEditWithApplyButton(
            turboSpeed.toString(),
            "ms",
            Gravity.TOP or Gravity.START,
            120,
            116,
            100,
        ) { value ->
            // Parse and apply the new turbo speed
            val newSpeed = value.toLongOrNull() ?: 16L
            turboSpeed = newSpeed.coerceIn(10L, 1000L) // Limit between 10ms and 1000ms

            // Update GlobalSettings with the new speed
            GlobalSettings.turboSpeed = turboSpeed

            // Update the field to show the validated value
            turboSpeedControl.first.setText(turboSpeed.toString())

            // Hide the controls after applying
            turboSpeedControl.first.visibility = View.GONE
            turboSpeedControl.second.visibility = View.GONE
        }

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
     * Observe connection status changes and update UI
     */
    private fun observeConnectionStatus() {
        lifecycleScope.launch {
            NetworkClient.connectionStatus.collectLatest { status ->
                updateConnectionStatusUI(status)
            }
        }

        // Also observe error messages
        lifecycleScope.launch {
            NetworkClient.lastErrorMessage.collectLatest { errorMsg ->
                errorMsg?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Update connection status UI elements
     */
    private fun updateConnectionStatusUI(status: NetworkClient.ConnectionStatus) {
        connectionStatusText.text = when (status) {
            NetworkClient.ConnectionStatus.DISCONNECTED -> "Disconnected"
            NetworkClient.ConnectionStatus.CONNECTING -> "Connecting..."
            NetworkClient.ConnectionStatus.CONNECTED -> "Connected"
            NetworkClient.ConnectionStatus.ERROR -> "Connection Error"
        }

        val color = when (status) {
            NetworkClient.ConnectionStatus.DISCONNECTED -> ContextCompat.getColor(this, android.R.color.darker_gray)
            NetworkClient.ConnectionStatus.CONNECTING -> ContextCompat.getColor(this, R.color.purple_500)
            NetworkClient.ConnectionStatus.CONNECTED -> ContextCompat.getColor(this, android.R.color.holo_green_dark)
            NetworkClient.ConnectionStatus.ERROR -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
        }

        connectionStatusText.setTextColor(color)

        // Update connect button text based on connection status
        btnConnect.text = when (status) {
            NetworkClient.ConnectionStatus.CONNECTED -> "Disconnect"
            NetworkClient.ConnectionStatus.CONNECTING -> "Cancel"
            else -> "Connect"
        }

        // Update connect button click behavior
        btnConnect.setOnClickListener {
            when (status) {
                NetworkClient.ConnectionStatus.CONNECTED,
                NetworkClient.ConnectionStatus.CONNECTING -> NetworkClient.close()
                else -> showConnectionSettingsDialog()
            }
        }
    }

    /**
     * Load network settings from SharedPreferences
     */
    private fun loadNetworkSettings() {
        val host = networkPrefs.getString("serverHost", "10.0.2.2") ?: "10.0.2.2"
        val port = networkPrefs.getInt("serverPort", 9001)
        val autoReconnect = networkPrefs.getBoolean("autoReconnect", false)

        NetworkClient.updateSettings(host, port, autoReconnect)
    }

    /**
     * Show dialog to configure server connection settings
     */
    private fun showConnectionSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_connection_settings, null)

        // Get references to dialog views
        val editHost = dialogView.findViewById<EditText>(R.id.editServerHost)
        val editPort = dialogView.findViewById<EditText>(R.id.editServerPort)
        val checkAutoReconnect = dialogView.findViewById<CheckBox>(R.id.checkboxAutoReconnect)

        // Fill in current values
        editHost.setText(networkPrefs.getString("serverHost", "10.0.2.2"))
        editPort.setText(networkPrefs.getInt("serverPort", 9001).toString())
        checkAutoReconnect.isChecked = networkPrefs.getBoolean("autoReconnect", false)

        // Show the dialog
        AlertDialog.Builder(this)
            .setTitle("Server Connection")
            .setView(dialogView)
            .setPositiveButton("Connect") { _, _ ->
                // Save settings
                val host = editHost.text.toString()
                val port = editPort.text.toString().toIntOrNull() ?: 9001
                val autoReconnect = checkAutoReconnect.isChecked

                networkPrefs.edit()
                    .putString("serverHost", host)
                    .putInt("serverPort", port)
                    .putBoolean("autoReconnect", autoReconnect)
                    .apply()

                // Update client and connect
                NetworkClient.updateSettings(host, port, autoReconnect)
                NetworkClient.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

        // Connection UI always visible
        connectionStatusText.visibility = View.VISIBLE
        btnConnect.visibility = View.VISIBLE
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