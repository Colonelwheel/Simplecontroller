package com.example.simplecontroller

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.example.simplecontroller.ui.ThemeManager
import com.example.simplecontroller.ui.UIComponentBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.example.simplecontroller.net.UdpClient
import android.widget.CheckBox
import android.view.ViewGroup

class MainActivity : AppCompatActivity(), LayoutManager.LayoutCallback {

    /* ---------- persisted "current layout" name ---------- */
    private val prefs by lazy { getSharedPreferences("layout", MODE_PRIVATE) }
    private var layoutName: String
        get() = prefs.getString("current", "default") ?: "default"
        set(v) = prefs.edit().putString("current", v).apply()

    /* ---------- network settings prefs ---------- */
    private val networkPrefs by lazy { getSharedPreferences("network", MODE_PRIVATE) }

    /* ---------- helper classes ---------- */
    private lateinit var uiBuilder: UIComponentBuilder
    private lateinit var layoutManager: LayoutManager

    /* ---------- underlying data model ---------- */
    // Changed from lazy delegate to lateinit to avoid initialization order issues
    private lateinit var controls: MutableList<Control>

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
    
    /* ---------- layout monitoring ---------- */
    private var lastCanvasHeight = 0
    private var isLayoutInitialized = false
    private var originalCanvasHeight = 0f
    private var originalCanvasWidth = 0f
    private var originalControlDimensions = mutableMapOf<String, OriginalDimensions>()
    
    data class OriginalDimensions(
        val x: Float,
        val y: Float, 
        val w: Float,
        val h: Float
    )

    // =============== life-cycle ======================================
    override fun onCreate(savedInstanceState: Bundle?) {
        // 0. Theme first
        ThemeManager.init(this)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Grab views
        canvas = findViewById(R.id.canvas)
        connectionStatusText = findViewById(R.id.connectionStatus)

        // 2. Apply theme colours
        canvas.setBackgroundColor(ContextCompat.getColor(this, R.color.dark_background))
        connectionStatusText.setTextColor(ContextCompat.getColor(this, R.color.dark_text_primary))

        // 3. Network / player-role state
        val savedPlayerRole = networkPrefs.getString(
            "playerRole",
            NetworkClient.PlayerRole.PLAYER1.name
        )
        val playerRole = runCatching {
            NetworkClient.PlayerRole.valueOf(savedPlayerRole ?: "PLAYER1")
        }.getOrDefault(NetworkClient.PlayerRole.PLAYER1)
        NetworkClient.setPlayerRole(playerRole)

        // 4. ----------  ONE  shared controls list  ----------
        controls = loadControls(this, layoutName)?.toMutableList() ?: mutableListOf()

        // 5. Helpers that all point at *that* list
        uiBuilder = UIComponentBuilder(this, canvas)
        layoutManager = LayoutManager(this, canvas, controls) { ctrl ->
            ControlView(this, ctrl)
        }.apply { setCallback(this@MainActivity) }

        // If this is a first run (or the file was missing) seed it with the default template
        if (controls.isEmpty()) controls += layoutManager.defaultLayout()

        // 6. Build the UI & observers
        setupUI()
        observeConnectionStatus()

        // 7. Render current layout
        layoutManager.spawnControlViews()

        // 8. Setup window insets handling for split screen
        setupWindowInsetsHandling()
        
        // 9. Misc startup
        loadNetworkSettings()
        updatePlayerRoleIndicator()
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_toggle_theme -> {
                ThemeManager.toggleDarkMode(this)
                recreate() // Recreate activity to apply theme changes
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    fun createControlFrom(src: Control) {
        layoutManager.createControlFrom(src)
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
                updatePlayerRoleIndicator()
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
            NetworkClient.ConnectionStatus.DISCONNECTED -> ContextCompat.getColor(
                this,
                R.color.dark_text_secondary
            )

            NetworkClient.ConnectionStatus.CONNECTING -> ContextCompat.getColor(
                this,
                R.color.primary_blue
            )

            NetworkClient.ConnectionStatus.CONNECTED -> ContextCompat.getColor(
                this,
                R.color.button_pressed_blue
            )

            NetworkClient.ConnectionStatus.ERROR -> ContextCompat.getColor(
                this,
                android.R.color.holo_red_dark
            )
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

        // Load player role
        val savedPlayerRole =
            networkPrefs.getString("playerRole", NetworkClient.PlayerRole.PLAYER1.name)
        val playerRole = try {
            NetworkClient.PlayerRole.valueOf(savedPlayerRole ?: "PLAYER1")
        } catch (e: Exception) {
            NetworkClient.PlayerRole.PLAYER1
        }

        // Set player role and update connection settings
        NetworkClient.setPlayerRole(playerRole)
        NetworkClient.updateSettings(host, port, autoReconnect)

        // Also initialize UdpClient with the same settings
        UdpClient.initialize(host, port)

        // Read saved CBv0 preference and apply
        val useCbv0 = networkPrefs.getBoolean("useCbv0", false)
        UdpClient.setConsoleBridgeEnabled(useCbv0)
    }

    /**
     * Show dialog to configure server connection settings
     */
    private fun showConnectionSettingsDialog() {
        val dialogView =
            LayoutInflater.from(this).inflate(R.layout.dialog_connection_settings, null)

        // Get references to dialog views
        val editHost = dialogView.findViewById<EditText>(R.id.editServerHost)
        val editPort = dialogView.findViewById<EditText>(R.id.editServerPort)
        val checkAutoReconnect = dialogView.findViewById<CheckBox>(R.id.checkboxAutoReconnect)

        // --- CBv0 toggle checkbox (added next to Auto Reconnect) ---
        val checkUseCbv0 = CheckBox(this).apply {
            text = "Use ConsoleBridge (CBv0)"
            isChecked = networkPrefs.getBoolean("useCbv0", false)
        }
        // Put it into the same container as Auto Reconnect
        (checkAutoReconnect.parent as? ViewGroup)?.addView(checkUseCbv0)

        // Add radio buttons for player selection
        val radioPlayer1 = dialogView.findViewById<RadioButton>(R.id.radioPlayer1)
        val radioPlayer2 = dialogView.findViewById<RadioButton>(R.id.radioPlayer2)

        // Set default selected player based on current setting
        if (NetworkClient.getPlayerRole() == NetworkClient.PlayerRole.PLAYER1) {
            radioPlayer1.isChecked = true
        } else {
            radioPlayer2.isChecked = true
        }

        // Fill in current values
        editHost.setText(networkPrefs.getString("serverHost", "10.0.2.2"))
        editPort.setText(networkPrefs.getInt("serverPort", 9001).toString())
        checkAutoReconnect.isChecked = networkPrefs.getBoolean("autoReconnect", false)

        // Apply theme to dialog elements
        editHost.setTextColor(ContextCompat.getColor(this, R.color.dark_text_primary))
        editHost.setHintTextColor(ContextCompat.getColor(this, R.color.dark_text_secondary))
        editPort.setTextColor(ContextCompat.getColor(this, R.color.dark_text_primary))
        editPort.setHintTextColor(ContextCompat.getColor(this, R.color.dark_text_secondary))
        checkAutoReconnect.setTextColor(ContextCompat.getColor(this, R.color.dark_text_primary))
        radioPlayer1.setTextColor(ContextCompat.getColor(this, R.color.dark_text_primary))
        radioPlayer2.setTextColor(ContextCompat.getColor(this, R.color.dark_text_primary))

        // Apply dark theme to dialog background
        dialogView.setBackgroundColor(ContextCompat.getColor(this, R.color.dark_surface))

        // Show the dialog with themed appearance
        val dialog = AlertDialog.Builder(this)
            .setTitle("Server Connection")
            .setView(dialogView)
            .setPositiveButton("Connect") { _, _ ->
                // --- Read fields ---
                val host = editHost.text.toString()
                val port = editPort.text.toString().toIntOrNull() ?: 9001
                val autoReconnect = checkAutoReconnect.isChecked
                val useCbv0Checked = checkUseCbv0.isChecked  // <-- the new checkbox

                // Player role from radio buttons
                val playerRole = if (radioPlayer1.isChecked)
                    NetworkClient.PlayerRole.PLAYER1
                else
                    NetworkClient.PlayerRole.PLAYER2

                // --- Save to preferences (adds useCbv0) ---
                networkPrefs.edit()
                    .putString("serverHost", host)
                    .putInt("serverPort", port)
                    .putBoolean("autoReconnect", autoReconnect)
                    .putString("playerRole", playerRole.name)
                    .putBoolean("useCbv0", useCbv0Checked)   // persist toggle
                    .apply()

                // --- Update client and connect (honor toggle) ---
                NetworkClient.setPlayerRole(playerRole)
                NetworkClient.updateSettings(host, port, autoReconnect)

                UdpClient.initialize(host, port)
                UdpClient.setConsoleBridgeEnabled(useCbv0Checked)

                NetworkClient.start()
                updatePlayerRoleIndicator()
            }
            .setNegativeButton("Cancel", null)
            .create()

        // Apply styling to dialog buttons
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            ContextCompat.getColor(this, R.color.primary_blue)
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
            ContextCompat.getColor(this, R.color.primary_blue)
        )
    }

    /**
     * Update the UI to indicate which player role is active
     */
    private fun updatePlayerRoleIndicator() {
        // Get current player role
        val playerRole = NetworkClient.getPlayerRole()

        // Update the connection status text to include player role
        val statusText = when (NetworkClient.connectionStatus.value) {
            NetworkClient.ConnectionStatus.DISCONNECTED -> "Disconnected"
            NetworkClient.ConnectionStatus.CONNECTING -> "Connecting..."
            NetworkClient.ConnectionStatus.CONNECTED -> "Connected"
            NetworkClient.ConnectionStatus.ERROR -> "Connection Error"
        }

        connectionStatusText.text = when (playerRole) {
            NetworkClient.PlayerRole.PLAYER1 -> "P1: $statusText"
            NetworkClient.PlayerRole.PLAYER2 -> "P2: $statusText"
        }

        // Optional: Update the background color of the status indicator
        val bgColor = when (playerRole) {
            NetworkClient.PlayerRole.PLAYER1 -> ContextCompat.getColor(this, R.color.player1_color)
            NetworkClient.PlayerRole.PLAYER2 -> ContextCompat.getColor(this, R.color.player2_color)
        }
        connectionStatusText.setBackgroundColor(bgColor)
    }

    /**
     * Show picker dialog to add a new control or create a template layout
     */
    private fun showAddPicker() {
        val options = arrayOf(
            "Button",
            "Stick",
            "TouchPad",
            "Re-center Button",
            "--- Layouts ---",
            "Xbox Controller Layout",
            "Player 1 Xbox Layout",
            "Player 2 Xbox Layout"
        )

        // Show dialog with dark theme
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add control or layout…")
            .setItems(options) { _, i ->
                when (i) {
                    0 -> layoutManager.createControl(ControlType.BUTTON)
                    1 -> layoutManager.createControl(ControlType.STICK)
                    2 -> layoutManager.createControl(ControlType.TOUCHPAD)
                    3 -> layoutManager.createControl(ControlType.RECENTER)
                    4 -> {} // This is just a divider item
                    5 -> createXboxControllerLayout()
                    6 -> createPlayer1XboxLayout()
                    7 -> createPlayer2XboxLayout()
                }
            }
            .create()

        dialog.window?.setBackgroundDrawableResource(R.color.dark_surface)
        dialog.show()

        // Apply text color to list items
        dialog.listView?.let { listView ->
            for (i in 0 until listView.count) {
                val v = listView.getChildAt(i)
                if (v is TextView) {
                    v.setTextColor(ContextCompat.getColor(this, R.color.dark_text_primary))
                }
            }
        }
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
     * Remove a control from the layout
     */
    fun removeControl(c: Control) {
        controls.remove(c)

        val viewToRemove = canvas.children.firstOrNull {
            it is ControlView && it.model.id == c.id
        }

        if (viewToRemove != null) {
            canvas.removeView(viewToRemove)
        }
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

    /**
     * Creates a standard Xbox controller layout
     */
    private fun createXboxControllerLayout() {
        // First clear existing controls
        controls.clear()
        clearControlViews()

        // Screen dimensions for positioning
        val screenWidth = canvas.width.toFloat()
        val screenHeight = canvas.height.toFloat()

        // Define standard sizes
        val buttonSize = 120f
        val stickSize = 200f
        val shoulderSize = 100f
        val dpadSize = 160f

        // Function to create and add a control
        fun addControl(
            id: String,
            type: ControlType,
            x: Float,
            y: Float,
            w: Float,
            h: Float,
            payload: String,
            name: String = ""
        ): Control {
            val control = Control(
                id = id,
                type = type,
                x = x,
                y = y,
                w = w,
                h = h,
                payload = payload,
                name = name
            )
            controls.add(control)
            canvas.addView(layoutManager.createControlView(control))
            return control
        }

        // Left stick (positioned on the left side)
        addControl(
            "left_stick",
            ControlType.STICK,
            x = screenWidth * 0.2f - stickSize / 2,
            y = screenHeight * 0.6f - stickSize / 2,
            w = stickSize,
            h = stickSize,
            payload = "STICK_L",
            name = "Left Stick"
        )

        // Right stick (positioned on the right side)
        addControl(
            "right_stick",
            ControlType.STICK,
            x = screenWidth * 0.75f - stickSize / 2,
            y = screenHeight * 0.6f - stickSize / 2,
            w = stickSize,
            h = stickSize,
            payload = "STICK_R",
            name = "Right Stick"
        )

        // Face buttons (ABXY) positioned on the right
        // A Button (bottom)
        addControl(
            "a_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - buttonSize / 2,
            y = screenHeight * 0.75f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360A",
            name = "A"
        )

        // B Button (right)
        addControl(
            "b_button",
            ControlType.BUTTON,
            x = screenWidth * 0.9f - buttonSize / 2,
            y = screenHeight * 0.65f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360B",
            name = "B"
        )

        // X Button (left)
        addControl(
            "x_button",
            ControlType.BUTTON,
            x = screenWidth * 0.7f - buttonSize / 2,
            y = screenHeight * 0.65f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360X",
            name = "X"
        )

        // Y Button (top)
        addControl(
            "y_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - buttonSize / 2,
            y = screenHeight * 0.55f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360Y",
            name = "Y"
        )

        // D-Pad (positioned on the left)
        // D-Pad Up
        addControl(
            "dpad_up",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 2,
            y = screenHeight * 0.35f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360UP",
            name = "↑"
        )

        // D-Pad Down
        addControl(
            "dpad_down",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 2,
            y = screenHeight * 0.5f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360DOWN",
            name = "↓"
        )

        // D-Pad Left
        addControl(
            "dpad_left",
            ControlType.BUTTON,
            x = screenWidth * 0.1f - buttonSize / 2,
            y = screenHeight * 0.425f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360LEFT",
            name = "←"
        )

        // D-Pad Right
        addControl(
            "dpad_right",
            ControlType.BUTTON,
            x = screenWidth * 0.3f - buttonSize / 2,
            y = screenHeight * 0.425f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360RIGHT",
            name = "→"
        )

        // Shoulder Buttons (positioned at the top)
        // Left Shoulder (LB)
        addControl(
            "lb_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - shoulderSize / 2,
            y = screenHeight * 0.15f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "X360LB",
            name = "LB"
        )

        // Right Shoulder (RB)
        addControl(
            "rb_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - shoulderSize / 2,
            y = screenHeight * 0.15f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "X360RB",
            name = "RB"
        )

        // Triggers (positioned at the top)
        // Left Trigger (LT) - using button for now, could make special trigger control later
        addControl(
            "lt_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - shoulderSize / 2,
            y = screenHeight * 0.05f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "LT:1.0", // Fully pressed trigger
            name = "LT"
        )

        // Right Trigger (RT)
        addControl(
            "rt_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - shoulderSize / 2,
            y = screenHeight * 0.05f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "RT:1.0",
            name = "RT"
        )

        // Center buttons (Start/Back/Guide)
        // Start button
        addControl(
            "start_button",
            ControlType.BUTTON,
            x = screenWidth * 0.6f - buttonSize / 2,
            y = screenHeight * 0.3f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize * 0.6f,
            payload = "X360START",
            name = "Start"
        )

        // Back button
        addControl(
            "back_button",
            ControlType.BUTTON,
            x = screenWidth * 0.4f - buttonSize / 2,
            y = screenHeight * 0.3f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize * 0.6f,
            payload = "X360BACK",
            name = "Back"
        )

        // Stick clickable buttons
        // Left stick button (press)
        addControl(
            "ls_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 3,
            y = screenHeight * 0.7f - buttonSize / 3,
            w = buttonSize * 0.6f,
            h = buttonSize * 0.6f,
            payload = "X360LS",
            name = "LS"
        )

        // Right stick button (press)
        addControl(
            "rs_button",
            ControlType.BUTTON,
            x = screenWidth * 0.75f - buttonSize / 3,
            y = screenHeight * 0.7f - buttonSize / 3,
            w = buttonSize * 0.6f,
            h = buttonSize * 0.6f,
            payload = "X360RS",
            name = "RS"
        )

        // Save the layout
        saveControls(this, "xbox_standard", controls)
        layoutName = "xbox_standard"

        Toast.makeText(this, "Xbox controller layout created!", Toast.LENGTH_SHORT).show()
    }

    /**
     * Creates a Player 1 Xbox controller layout
     */
    private fun createPlayer1XboxLayout() {
        // First clear existing controls
        controls.clear()
        clearControlViews()

        // Screen dimensions for positioning
        val screenWidth = canvas.width.toFloat()
        val screenHeight = canvas.height.toFloat()

        // Define standard sizes
        val buttonSize = 120f
        val stickSize = 200f
        val shoulderSize = 100f
        val dpadSize = 160f

        // Function to create and add a control
        fun addControl(
            id: String,
            type: ControlType,
            x: Float,
            y: Float,
            w: Float,
            h: Float,
            payload: String,
            name: String = ""
        ): Control {
            val control = Control(
                id = id,
                type = type,
                x = x,
                y = y,
                w = w,
                h = h,
                payload = payload,
                name = name
            )
            controls.add(control)
            canvas.addView(layoutManager.createControlView(control))
            return control
        }

        // Left stick (positioned on the left side)
        addControl(
            "p1_left_stick",
            ControlType.STICK,
            x = screenWidth * 0.2f - stickSize / 2,
            y = screenHeight * 0.6f - stickSize / 2,
            w = stickSize,
            h = stickSize,
            payload = "STICK_L",
            name = "P1 Left Stick"
        )

        // Right stick (positioned on the right side)
        addControl(
            "p1_right_stick",
            ControlType.STICK,
            x = screenWidth * 0.75f - stickSize / 2,
            y = screenHeight * 0.6f - stickSize / 2,
            w = stickSize,
            h = stickSize,
            payload = "STICK_R",
            name = "P1 Right Stick"
        )

        // Face buttons (ABXY) positioned on the right
        // A Button (bottom)
        addControl(
            "p1_a_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - buttonSize / 2,
            y = screenHeight * 0.75f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360A",
            name = "P1 A"
        )

        // B Button (right)
        addControl(
            "p1_b_button",
            ControlType.BUTTON,
            x = screenWidth * 0.9f - buttonSize / 2,
            y = screenHeight * 0.65f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360B",
            name = "P1 B"
        )

        // X Button (left)
        addControl(
            "p1_x_button",
            ControlType.BUTTON,
            x = screenWidth * 0.7f - buttonSize / 2,
            y = screenHeight * 0.65f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360X",
            name = "P1 X"
        )

        // Y Button (top)
        addControl(
            "p1_y_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - buttonSize / 2,
            y = screenHeight * 0.55f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360Y",
            name = "P1 Y"
        )

        // D-Pad (positioned on the left)
        // D-Pad Up
        addControl(
            "p1_dpad_up",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 2,
            y = screenHeight * 0.35f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360UP",
            name = "P1 ↑"
        )

        // D-Pad Down
        addControl(
            "p1_dpad_down",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 2,
            y = screenHeight * 0.5f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360DOWN",
            name = "P1 ↓"
        )

        // D-Pad Left
        addControl(
            "p1_dpad_left",
            ControlType.BUTTON,
            x = screenWidth * 0.1f - buttonSize / 2,
            y = screenHeight * 0.425f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360LEFT",
            name = "P1 ←"
        )

        // D-Pad Right
        addControl(
            "p1_dpad_right",
            ControlType.BUTTON,
            x = screenWidth * 0.3f - buttonSize / 2,
            y = screenHeight * 0.425f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360RIGHT",
            name = "P1 →"
        )

        // Shoulder Buttons (positioned at the top)
        // Left Shoulder (LB)
        addControl(
            "p1_lb_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - shoulderSize / 2,
            y = screenHeight * 0.15f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "X360LB",
            name = "P1 LB"
        )

        // Right Shoulder (RB)
        addControl(
            "p1_rb_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - shoulderSize / 2,
            y = screenHeight * 0.15f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "X360RB",
            name = "P1 RB"
        )

        // Triggers (positioned at the top)
        // Left Trigger (LT) - using button for now, could make special trigger control later
        addControl(
            "p1_lt_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - shoulderSize / 2,
            y = screenHeight * 0.05f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "LT:1.0", // Fully pressed trigger
            name = "P1 LT"
        )

        // Right Trigger (RT)
        addControl(
            "p1_rt_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - shoulderSize / 2,
            y = screenHeight * 0.05f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "RT:1.0",
            name = "P1 RT"
        )

        // Center buttons (Start/Back/Guide)
        // Start button
        addControl(
            "p1_start_button",
            ControlType.BUTTON,
            x = screenWidth * 0.6f - buttonSize / 2,
            y = screenHeight * 0.3f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize * 0.6f,
            payload = "X360START",
            name = "P1 Start"
        )

        // Back button
        addControl(
            "p1_back_button",
            ControlType.BUTTON,
            x = screenWidth * 0.4f - buttonSize / 2,
            y = screenHeight * 0.3f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize * 0.6f,
            payload = "X360BACK",
            name = "P1 Back"
        )

        // Stick clickable buttons
        // Left stick button (press)
        addControl(
            "p1_ls_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 3,
            y = screenHeight * 0.7f - buttonSize / 3,
            w = buttonSize * 0.6f,
            h = buttonSize * 0.6f,
            payload = "X360LS",
            name = "P1 LS"
        )

        // Right stick button (press)
        addControl(
            "p1_rs_button",
            ControlType.BUTTON,
            x = screenWidth * 0.75f - buttonSize / 3,
            y = screenHeight * 0.7f - buttonSize / 3,
            w = buttonSize * 0.6f,
            h = buttonSize * 0.6f,
            payload = "X360RS",
            name = "P1 RS"
        )

        // Save the layout
        saveControls(this, "player1_xbox", controls)
        layoutName = "player1_xbox"

        Toast.makeText(this, "Player 1 Xbox layout created!", Toast.LENGTH_SHORT).show()
    }


    /**
     * Creates a Player 2 Xbox controller layout
     */
    private fun createPlayer2XboxLayout() {
        // First clear existing controls
        controls.clear()
        clearControlViews()

        // Screen dimensions for positioning
        val screenWidth = canvas.width.toFloat()
        val screenHeight = canvas.height.toFloat()

        // Define standard sizes
        val buttonSize = 120f
        val stickSize = 200f
        val shoulderSize = 100f
        val dpadSize = 160f

        // Function to create and add a control
        fun addControl(
            id: String,
            type: ControlType,
            x: Float,
            y: Float,
            w: Float,
            h: Float,
            payload: String,
            name: String = ""
        ): Control {
            val control = Control(
                id = id,
                type = type,
                x = x,
                y = y,
                w = w,
                h = h,
                payload = payload,
                name = name
            )
            controls.add(control)
            canvas.addView(layoutManager.createControlView(control))
            return control
        }

        // Left stick (positioned on the left side)
        addControl(
            "p2_left_stick",
            ControlType.STICK,
            x = screenWidth * 0.2f - stickSize / 2,
            y = screenHeight * 0.6f - stickSize / 2,
            w = stickSize,
            h = stickSize,
            payload = "STICK_L",
            name = "P2 Left Stick"
        )

        // Right stick (positioned on the right side)
        addControl(
            "p2_right_stick",
            ControlType.STICK,
            x = screenWidth * 0.75f - stickSize / 2,
            y = screenHeight * 0.6f - stickSize / 2,
            w = stickSize,
            h = stickSize,
            payload = "STICK_R",
            name = "P2 Right Stick"
        )

        // Face buttons (ABXY) positioned on the right
        // A Button (bottom)
        addControl(
            "p2_a_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - buttonSize / 2,
            y = screenHeight * 0.75f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360A",
            name = "P2 A"
        )

        // B Button (right)
        addControl(
            "p2_b_button",
            ControlType.BUTTON,
            x = screenWidth * 0.9f - buttonSize / 2,
            y = screenHeight * 0.65f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360B",
            name = "P2 B"
        )

        // X Button (left)
        addControl(
            "p2_x_button",
            ControlType.BUTTON,
            x = screenWidth * 0.7f - buttonSize / 2,
            y = screenHeight * 0.65f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360X",
            name = "P2 X"
        )

        // Y Button (top)
        addControl(
            "p2_y_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - buttonSize / 2,
            y = screenHeight * 0.55f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360Y",
            name = "P2 Y"
        )

        // D-Pad (positioned on the left)
        // D-Pad Up
        addControl(
            "p2_dpad_up",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 2,
            y = screenHeight * 0.35f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360UP",
            name = "P2 ↑"
        )

        // D-Pad Down
        addControl(
            "p2_dpad_down",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 2,
            y = screenHeight * 0.5f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360DOWN",
            name = "P2 ↓"
        )

        // D-Pad Left
        addControl(
            "p2_dpad_left",
            ControlType.BUTTON,
            x = screenWidth * 0.1f - buttonSize / 2,
            y = screenHeight * 0.425f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360LEFT",
            name = "P2 ←"
        )

        // D-Pad Right
        addControl(
            "p2_dpad_right",
            ControlType.BUTTON,
            x = screenWidth * 0.3f - buttonSize / 2,
            y = screenHeight * 0.425f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360RIGHT",
            name = "P2 →"
        )

        // Shoulder Buttons (positioned at the top)
        // Left Shoulder (LB)
        addControl(
            "p2_lb_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - shoulderSize / 2,
            y = screenHeight * 0.15f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "X360LB",
            name = "P2 LB"
        )

        // Right Shoulder (RB)
        addControl(
            "p2_rb_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - shoulderSize / 2,
            y = screenHeight * 0.15f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "X360RB",
            name = "P2 RB"
        )

        // Triggers (positioned at the top)
        // Left Trigger (LT) - using button for now, could make special trigger control later
        addControl(
            "p2_lt_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - shoulderSize / 2,
            y = screenHeight * 0.05f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "LT:1.0", // Fully pressed trigger
            name = "P2 LT"
        )

        // Right Trigger (RT)
        addControl(
            "p2_rt_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - shoulderSize / 2,
            y = screenHeight * 0.05f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "RT:1.0",
            name = "P2 RT"
        )

        // Center buttons (Start/Back/Guide)
        // Start button
        addControl(
            "p2_start_button",
            ControlType.BUTTON,
            x = screenWidth * 0.6f - buttonSize / 2,
            y = screenHeight * 0.3f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize * 0.6f,
            payload = "X360START",
            name = "P2 Start"
        )

        // Back button
        addControl(
            "p2_back_button",
            ControlType.BUTTON,
            x = screenWidth * 0.4f - buttonSize / 2,
            y = screenHeight * 0.3f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize * 0.6f,
            payload = "X360BACK",
            name = "P2 Back"
        )

        // Stick clickable buttons
        // Left stick button (press)
        addControl(
            "p2_ls_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 3,
            y = screenHeight * 0.7f - buttonSize / 3,
            w = buttonSize * 0.6f,
            h = buttonSize * 0.6f,
            payload = "X360LS",
            name = "P2 LS"
        )

        // Right stick button (press)
        addControl(
            "p2_rs_button",
            ControlType.BUTTON,
            x = screenWidth * 0.75f - buttonSize / 3,
            y = screenHeight * 0.7f - buttonSize / 3,
            w = buttonSize * 0.6f,
            h = buttonSize * 0.6f,
            payload = "X360RS",
            name = "P2 RS"
        )

        // Save the layout
        saveControls(this, "player2_xbox", controls)
        layoutName = "player2_xbox"

        Toast.makeText(this, "Player 2 Xbox controller layout created!", Toast.LENGTH_SHORT).show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        // Do nothing - keep controls exactly as they are
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        
        // Do nothing - keep controls exactly as they are
    }

    private fun refreshLayout() {
        // Clear existing views first to prevent duplicates
        clearControlViews()
        
        // Re-spawn all control views with current canvas dimensions
        layoutManager.spawnControlViews()
    }

    private fun setupWindowInsetsHandling() {
        // Handle window insets for split screen mode
        ViewCompat.setOnApplyWindowInsetsListener(canvas) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            
            // Apply padding to ensure content is not clipped
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom + ime.bottom
            )
            
            insets
        }
        
        // Monitor canvas size changes - DISABLED to allow manual resizing
        // User can manually adjust controls after entering split screen mode
        canvas.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val currentHeight = canvas.height
                
                // Only store initial dimensions, don't auto-resize on changes
                if (!isLayoutInitialized && currentHeight > 0) {
                    lastCanvasHeight = currentHeight
                    originalCanvasHeight = currentHeight.toFloat()
                    originalCanvasWidth = canvas.width.toFloat()
                    isLayoutInitialized = true
                    
                    // Store original control dimensions for scaling
                    storeOriginalControlDimensions()
                }
                
                // Auto-resizing disabled - user can manually adjust controls
                // if (isLayoutInitialized && currentHeight > 0 && 
                //     kotlin.math.abs(currentHeight - lastCanvasHeight) > 50) {
                //     lastCanvasHeight = currentHeight
                //     canvas.post { refreshLayoutForNewDimensions() }
                // }
            }
        })
    }

    private fun refreshLayoutForNewDimensions() {
        // Scale and reposition controls to fit the new canvas dimensions
        val canvasWidth = canvas.width.toFloat()
        val canvasHeight = canvas.height.toFloat()
        
        if (canvasWidth <= 0 || canvasHeight <= 0 || originalCanvasHeight <= 0 || originalCanvasWidth <= 0) return
        
        // Calculate scaling factors
        val heightScale = canvasHeight / originalCanvasHeight
        val widthScale = canvasWidth / originalCanvasWidth
        
        // For split screen, we primarily want to scale based on height reduction,
        // but allow width to expand if needed. Only scale down on height, allow width to stay full.
        val scaleFactor = if (heightScale < 1.0f) {
            // In split screen (height reduced), use height scale but don't constrain width
            heightScale
        } else {
            // Full screen or width change only
            minOf(heightScale, widthScale, 1.0f)
        }
        
        // Apply scaling to all controls
        controls.forEach { control ->
            val originalDims = originalControlDimensions[control.id]
            if (originalDims != null) {
                if (heightScale < 1.0f) {
                    // In split screen mode: scale everything by height reduction,
                    // but allow horizontal positions to utilize the full width
                    control.x = originalDims.x * (canvasWidth / originalCanvasWidth)
                    control.y = originalDims.y * scaleFactor
                    control.w = originalDims.w * scaleFactor
                    control.h = originalDims.h * scaleFactor
                } else {
                    // Full screen mode: normal proportional scaling
                    control.x = originalDims.x * scaleFactor
                    control.y = originalDims.y * scaleFactor
                    control.w = originalDims.w * scaleFactor
                    control.h = originalDims.h * scaleFactor
                }
                
                // Ensure controls still fit within bounds after scaling
                control.x = control.x.coerceIn(0f, (canvasWidth - control.w).coerceAtLeast(0f))
                control.y = control.y.coerceIn(0f, (canvasHeight - control.h).coerceAtLeast(0f))
            }
        }
        
        // Refresh the layout
        refreshLayout()
    }

    private fun storeOriginalControlDimensions() {
        originalControlDimensions.clear()
        controls.forEach { control ->
            originalControlDimensions[control.id] = OriginalDimensions(
                x = control.x,
                y = control.y,
                w = control.w,
                h = control.h
            )
        }
    }
}