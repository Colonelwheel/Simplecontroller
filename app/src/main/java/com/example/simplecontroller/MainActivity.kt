package com.example.simplecontroller

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.provider.Settings
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.doOnNextLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.simplecontroller.io.LayoutManager
import com.example.simplecontroller.io.ControllerProfileLoadResult
import com.example.simplecontroller.io.ImportedControllerProfile
import com.example.simplecontroller.io.ControllerProfilesBackupLoadResult
import com.example.simplecontroller.io.ProfileTransferDocument
import com.example.simplecontroller.io.StoredControllerProfileResult
import com.example.simplecontroller.io.clearStagedControllerProfileTransfer
import com.example.simplecontroller.io.controllerProfilesBackupFileName
import com.example.simplecontroller.io.controllerProfileTransferFileName
import com.example.simplecontroller.io.createControllerProfilesBackup
import com.example.simplecontroller.io.decodeProfileTransferDocument
import com.example.simplecontroller.io.deleteControllerProfile
import com.example.simplecontroller.io.deepCopyControls
import com.example.simplecontroller.io.encodeControllerProfilesBackup
import com.example.simplecontroller.io.importControllerProfilesBackup
import com.example.simplecontroller.io.listLayouts
import com.example.simplecontroller.io.readControllerProfileTransferText
import com.example.simplecontroller.io.readControllerProfile
import com.example.simplecontroller.io.readStagedControllerProfileTransfer
import com.example.simplecontroller.io.saveControllerProfile
import com.example.simplecontroller.io.sanitizeSuggestedProfileName
import com.example.simplecontroller.io.stageControllerProfileTransfer
import com.example.simplecontroller.io.stageProfileTransferDocument
import com.example.simplecontroller.io.stableLegacyBasePageId
import com.example.simplecontroller.io.uniqueImportedProfileName
import com.example.simplecontroller.io.validateImportedProfileName
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControllerPage
import com.example.simplecontroller.model.ControllerPageOption
import com.example.simplecontroller.model.ControllerPageSession
import com.example.simplecontroller.model.ControllerProfile
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.model.LayoutOrientation
import com.example.simplecontroller.model.OrientationPreferenceBackend
import com.example.simplecontroller.model.OrientationPreferenceStore
import com.example.simplecontroller.model.PageAction
import com.example.simplecontroller.model.TouchAimCalibrationProfile
import com.example.simplecontroller.model.hasPageName
import com.example.simplecontroller.model.hasUsablePageNavigation
import com.example.simplecontroller.model.capturePageGeometry
import com.example.simplecontroller.model.controlsForOrientation
import com.example.simplecontroller.model.geometryFor
import com.example.simplecontroller.model.isLocalPageAction
import com.example.simplecontroller.model.newPageId
import com.example.simplecontroller.model.page
import com.example.simplecontroller.model.pageName
import com.example.simplecontroller.model.referenceCount
import com.example.simplecontroller.model.remapControlIds
import com.example.simplecontroller.model.withGeometry
import com.example.simplecontroller.model.withOnlyControlIds
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.ui.ControlView
import com.example.simplecontroller.ui.GlobalSettings
import com.example.simplecontroller.ui.SwipeManager
import com.example.simplecontroller.ui.ThemeManager
import com.example.simplecontroller.ui.UIComponentBuilder
import com.example.simplecontroller.ui.ReleaseAllCoordinator
import com.example.simplecontroller.ui.TouchAimCalibrationWizard
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.simplecontroller.net.UdpClient
import android.widget.CheckBox
import android.view.ViewGroup
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity(), LayoutManager.LayoutCallback {

    private companion object {
        const val USB_TETHER_RETURN_PENDING = "usbTetherReturnPending"
        const val TETHER_SETTINGS_ACTION = "android.settings.TETHER_SETTINGS"
    }

    private val createProfileDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) {
            clearStagedControllerProfileTransfer(this)
        } else {
            writeAndVerifyProfileExport(uri)
        }
    }

    private val openProfileDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) readAndPromptForProfileImport(uri)
    }

    /* ---------- persisted "current layout" name ---------- */
    private val prefs by lazy { getSharedPreferences("layout", MODE_PRIVATE) }
    private var layoutName: String
        get() = prefs.getString("current", "default") ?: "default"
        set(v) = prefs.edit().putString("current", v).apply()

    /* ---------- network settings prefs ---------- */
    private val networkPrefs by lazy { getSharedPreferences("network", MODE_PRIVATE) }
    private val orientationPrefs by lazy { getSharedPreferences("orientation", MODE_PRIVATE) }
    private val orientationStore by lazy {
        OrientationPreferenceStore(object : OrientationPreferenceBackend {
            override fun read(key: String): String? = orientationPrefs.getString(key, null)

            override fun write(key: String, value: String) {
                orientationPrefs.edit().putString(key, value).apply()
            }
        })
    }

    /* ---------- helper classes ---------- */
    private lateinit var uiBuilder: UIComponentBuilder
    private lateinit var layoutManager: LayoutManager

    /* ---------- underlying data model ---------- */
    // Changed from lazy delegate to lateinit to avoid initialization order issues
    private lateinit var controls: MutableList<Control>
    private lateinit var controllerProfile: ControllerProfile
    private lateinit var pageSession: ControllerPageSession
    private var displayedPageId: String = ""
    private var editingPageId: String = ""
    private var profileAutosaveBlocked = false

    /* ---------- edit-only widgets we show/hide ---------- */
    private lateinit var btnSave: View
    private lateinit var btnLoad: View
    private lateinit var fabAdd: View
    private lateinit var btnEdit: Button
    private lateinit var btnPageManager: Button
    private lateinit var btnEditOrientation: Button
    private lateinit var btnPlayOrientation: Button

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
    private var activeTouchAimCalibration: TouchAimCalibrationWizard? = null
    private var touchAimCalibrationLaunchGeneration = 0
    private var activeLayoutOrientation = LayoutOrientation.PORTRAIT
    private var pendingOrientationPageId: String? = null
    private var orientationRenderPending = false
    
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
        requestedOrientation = requestedOrientationFor(orientationStore.readPlayOrientation())
        setContentView(R.layout.activity_main)

        // 1. Grab views
        canvas = findViewById(R.id.canvas)
        connectionStatusText = findViewById(R.id.connectionStatus)
        activeLayoutOrientation = layoutOrientationFrom(resources.configuration)

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

        // 4. Load the complete profile. Old top-level control arrays are wrapped in memory as Base.
        val storedProfile = readControllerProfile(this, layoutName)
        val loadedProfile = (storedProfile as? StoredControllerProfileResult.Loaded)?.result
        profileAutosaveBlocked = storedProfile is StoredControllerProfileResult.Error
        controllerProfile = loadedProfile?.profile ?: run {
            val baseId = stableLegacyBasePageId(layoutName)
            ControllerProfile(
                homePageId = baseId,
                pages = listOf(ControllerPage(baseId, "Base", emptyList()))
            )
        }
        pageSession = ControllerPageSession(
            controllerProfile.homePageId,
            controllerProfile.pages.map { it.id }
        )
        displayedPageId = controllerProfile.homePageId
        editingPageId = controllerProfile.homePageId
        controls = controllerProfile.page(controllerProfile.homePageId)
            ?.controlsForOrientation(activeLayoutOrientation, 0f, 0f)
            ?.let(::deepCopyControls)
            .orEmpty()
            .toMutableList()

        // 5. Helpers that all point at *that* list
        uiBuilder = UIComponentBuilder(this, canvas)
        layoutManager = LayoutManager(this, canvas, controls) { ctrl ->
            ControlView(
                this,
                ctrl,
                onPageAction = ::activateControllerPageAction,
                pageActionLabel = ::pageActionDisplayLabel
            )
        }.apply { setCallback(this@MainActivity) }

        // If this is a first run (or the file was missing) seed it with the default template
        if (loadedProfile == null ||
            (loadedProfile.migratedFromSinglePage && controls.isEmpty())
        ) {
            controls += layoutManager.defaultLayout()
            syncDisplayedPageIntoProfile()
        }

        // 6. Build the UI & observers
        setupUI()
        observeConnectionStatus()

        if (loadedProfile?.warnings?.isNotEmpty() == true) {
            Toast.makeText(
                this,
                loadedProfile.warnings.take(3).joinToString("\n"),
                Toast.LENGTH_LONG
            ).show()
        } else if (storedProfile is StoredControllerProfileResult.Error) {
            Toast.makeText(
                this,
                "This profile could not be read. Its original file was left unchanged; use Save as to recover safely.",
                Toast.LENGTH_LONG
            ).show()
        }

        // 7. Render current layout
        layoutManager.spawnControlViews()
        canvas.doOnNextLayout {
            renderPage(displayedPageId, safeToDetach = true)
        }

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

        // Returning from tether settings is handled by USB discovery in onResume().
        // Do not briefly start the saved manual connection first.
        val usbTetherReturnPending =
            networkPrefs.getBoolean(USB_TETHER_RETURN_PENDING, false)
        if (networkPrefs.getBoolean("autoReconnect", false) && !usbTetherReturnPending) {
            NetworkClient.start()
        }
    }

    override fun onPause() {
        super.onPause()
        cancelTouchAimCalibration("Calibration stopped because the app left the foreground.")
        syncDisplayedPageIntoProfile()
        releaseOutputs(showFeedback = false)
        if (displayedPageId != controllerProfile.homePageId) {
            pageSession.resetToHome()
            editingPageId = controllerProfile.homePageId
            renderPage(controllerProfile.homePageId, safeToDetach = true)
        } else pageSession.resetToHome()
        syncDisplayedPageIntoProfile()
        if (!profileAutosaveBlocked) {
            saveControllerProfile(this, layoutName, controllerProfile)
        }
    }

    override fun onResume() {
        super.onResume()
        if (networkPrefs.getBoolean(USB_TETHER_RETURN_PENDING, false)) {
            networkPrefs.edit().remove(USB_TETHER_RETURN_PENDING).apply()
            connectViaUsbTether()
        }
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
        if (activeTouchAimCalibration != null) return super.dispatchTouchEvent(ev)
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
    private lateinit var turboSpeedContainer: View
    private var turboSpeed = 16L // Default speed in milliseconds (≈60 Hz)

    private fun setupUI() {
        /* --- Edit toggle ------------------------------------------------ */
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        btnEdit = uiBuilder.addCornerButton(
            "Edit", Gravity.TOP or Gravity.END, dp(16), dp(16)
        ) {
            requestEditModeChange(!GlobalSettings.editMode)
        }

        /* --- Connection button ------------------------------------------ */
        btnConnect = uiBuilder.addCornerButton(
            "Connect",                  // Label
            Gravity.TOP or Gravity.END, // Gravity
            dp(16),                     // Horizontal margin
            dp(72)                      // Vertical margin
        ) {
            showConnectionSettingsDialog()
        }

        btnPageManager = uiBuilder.addCornerButton(
            "Editing page: Base ▾",
            Gravity.TOP or Gravity.END,
            dp(16),
            dp(128)
        ) {
            showControllerPageManager()
        }

        btnEditOrientation = uiBuilder.addCornerButton(
            "Edit in Landscape",
            Gravity.TOP or Gravity.END,
            dp(16),
            dp(184)
        ) {
            requestEditorOrientation(activeLayoutOrientation.opposite())
        }.apply {
            isAllCaps = false
            minHeight = dp(48)
        }

        btnPlayOrientation = uiBuilder.addCornerButton(
            "Play lock: Portrait",
            Gravity.TOP or Gravity.END,
            dp(16),
            dp(240)
        ) {
            val updated = orientationStore.readPlayOrientation().opposite()
            orientationStore.writePlayOrientation(updated)
            updateOrientationButtonLabels()
        }.apply {
            isAllCaps = false
            minHeight = dp(48)
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
            turboSpeedContainer.visibility = if (on) View.VISIBLE else View.GONE
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
            0,
            0,
            100,
        ) { value ->
            // Parse and apply the new turbo speed
            val newSpeed = value.toLongOrNull() ?: 16L
            turboSpeed = newSpeed.coerceIn(10L, 1000L) // Limit between 10ms and 1000ms

            // Update GlobalSettings with the new speed
            GlobalSettings.turboSpeed = turboSpeed

            // Update the field to show the validated value
            turboSpeedControl.first.setText(turboSpeed.toString())
        }
        turboSpeedContainer = turboSpeedControl.first.parent as View
        turboSpeedContainer.visibility = View.GONE
        switchTurbo.doOnNextLayout { turboSwitch ->
            val params = turboSpeedContainer.layoutParams as FrameLayout.LayoutParams
            params.leftMargin = turboSwitch.right + 8
            params.topMargin = turboSwitch.top
            turboSpeedContainer.layoutParams = params
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
        updateOrientationButtonLabels()
        updateEditUi(GlobalSettings.editMode)
    }

    /**
     * Observe connection status changes and update UI
     */
    private fun observeConnectionStatus() {
        lifecycleScope.launch {
            var previousStatus = NetworkClient.connectionStatus.value
            NetworkClient.connectionStatus.collectLatest { status ->
                if (previousStatus == NetworkClient.ConnectionStatus.CONNECTED &&
                    status != NetworkClient.ConnectionStatus.CONNECTED
                ) {
                    // Cancel armed/delayed Button Aim and Auto-tap state locally. In particular,
                    // a scheduled payload must not fire after reconnection.
                    releaseOutputs(showFeedback = false)
                    cancelTouchAimCalibration("Calibration stopped because the connection changed.")
                    resetToHomeAfterCleanup()
                }
                previousStatus = status
                updateConnectionStatusUI(status)
                updatePlayerRoleIndicator()
                if (status == NetworkClient.ConnectionStatus.CONNECTED) {
                    UdpClient.resendCameraFollowState()
                }
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
                NetworkClient.ConnectionStatus.CONNECTING -> {
                    // Release while the current socket is live, then return to Home.
                    releaseOutputs(showFeedback = false)
                    resetToHomeAfterCleanup()
                    cancelTouchAimCalibration("Calibration stopped before disconnecting.")
                    NetworkClient.close()
                }

                else -> showConnectionSettingsDialog()
            }
        }
    }

    private fun openUsbTetherSettings() {
        networkPrefs.edit().putBoolean(USB_TETHER_RETURN_PENDING, true).apply()

        val settingsIntents = listOf(
            Intent(TETHER_SETTINGS_ACTION),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        var launched = false
        for (intent in settingsIntents) {
            try {
                startActivity(intent)
                launched = true
                break
            } catch (_: ActivityNotFoundException) {
                // Try the broader wireless settings screen on devices without a tether shortcut.
            } catch (_: SecurityException) {
                // Some manufacturers expose the action but prevent third-party launching.
            }
        }

        if (!launched) {
            networkPrefs.edit().remove(USB_TETHER_RETURN_PENDING).apply()
            Toast.makeText(
                this,
                "Unable to open tether settings on this device.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun connectViaUsbTether() {
        Toast.makeText(this, "Searching for the PC receiver over USB…", Toast.LENGTH_SHORT).show()
        releaseOutputs(showFeedback = false)
        resetToHomeAfterCleanup()
        cancelTouchAimCalibration("Calibration stopped before changing connections.")
        UdpClient.close()

        NetworkClient.discoverUsbTetherReceiver { endpoint ->
            if (endpoint == null) return@discoverUsbTetherReceiver

            val autoReconnect = networkPrefs.getBoolean("autoReconnect", false)
            // Use the discovered endpoint only for this runtime connection. The saved
            // manual host, port, and ConsoleBridge choice remain untouched.
            UdpClient.setConsoleBridgeEnabled(false)
            NetworkClient.updateSettings(endpoint.host, endpoint.port, autoReconnect)
            UdpClient.initialize(endpoint.host, endpoint.port)
            NetworkClient.start()

            Toast.makeText(
                this,
                "USB receiver found at ${endpoint.host}:${endpoint.port}",
                Toast.LENGTH_SHORT
            ).show()
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
        val buttonUsbTetherMode =
            dialogView.findViewById<Button>(R.id.buttonUsbTetherMode)

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
                // Clear timed/armed button phases and their held payloads before changing endpoints.
                releaseOutputs(showFeedback = false)
                resetToHomeAfterCleanup()
                cancelTouchAimCalibration("Calibration stopped before changing connections.")
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
        buttonUsbTetherMode.setOnClickListener {
            dialog.dismiss()
            openUsbTetherSettings()
        }
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
            "Response Curve Stick",
            "TouchPad",
            "Touch Aim",
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
                    2 -> layoutManager.createControl(ControlType.CURVED_STICK)
                    3 -> layoutManager.createControl(ControlType.TOUCHPAD)
                    4 -> layoutManager.createControl(ControlType.TOUCH_AIM)
                    5 -> layoutManager.createControl(ControlType.RECENTER)
                    6 -> {} // This is just a divider item
                    7 -> createXboxControllerLayout()
                    8 -> createPlayer1XboxLayout()
                    9 -> createPlayer2XboxLayout()
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
        val editWidgets = listOf(
            btnSave,
            btnLoad,
            fabAdd,
            btnPageManager,
            btnEditOrientation,
            btnPlayOrientation
        )
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
        if (activeTouchAimCalibration != null) {
            cancelTouchAimCalibration("Calibration stopped because a control was removed.")
        }
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
        profileAutosaveBlocked = false
    }

    /**
     * Called when a layout is saved
     */
    override fun onLayoutSaved(layoutName: String) {
        this.layoutName = layoutName
        profileAutosaveBlocked = false
    }

    override fun controllerProfileForSave(): ControllerProfile {
        syncDisplayedPageIntoProfile()
        return controllerProfile
    }

    override fun activeLayoutName(): String = layoutName

    override fun onControllerProfileLoaded(
        layoutName: String,
        result: ControllerProfileLoadResult
    ): Boolean {
        releaseOutputs(showFeedback = false)
        profileAutosaveBlocked = false
        installProfile(layoutName, result.profile)
        if (result.warnings.isNotEmpty()) {
            Toast.makeText(
                this,
                result.warnings.take(3).joinToString("\n"),
                Toast.LENGTH_LONG
            ).show()
        }
        return true
    }

    override fun onNewControllerProfileRequested(): Boolean {
        releaseOutputs(showFeedback = false)
        profileAutosaveBlocked = false
        val baseId = newPageId()
        installProfile(
            "untitled",
            ControllerProfile(
                homePageId = baseId,
                pages = listOf(ControllerPage(baseId, "Base", emptyList()))
            )
        )
        Toast.makeText(this, "New blank profile created", Toast.LENGTH_SHORT).show()
        return true
    }

    override fun onExternalProfileImportRequested(): Boolean {
        runCatching {
            openProfileDocumentLauncher.launch(
                arrayOf("application/json", "text/json", "text/plain", "application/octet-stream")
            )
        }.onFailure {
            Toast.makeText(
                this,
                "Android's document picker could not be opened: ${friendlyProfileFileError(it)}",
                Toast.LENGTH_LONG
            ).show()
        }
        return true
    }

    override fun onExternalProfileExportRequested(
        profileName: String,
        profile: ControllerProfile
    ): Boolean {
        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) {
                stageControllerProfileTransfer(this@MainActivity, profileName, profile)
            }
            if (staged.isFailure) {
                withContext(Dispatchers.IO) {
                    clearStagedControllerProfileTransfer(this@MainActivity)
                }
                Toast.makeText(
                    this@MainActivity,
                    "Could not prepare the profile export: ${friendlyProfileFileError(staged.exceptionOrNull())}",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                runCatching {
                    createProfileDocumentLauncher.launch(
                        controllerProfileTransferFileName(profileName)
                    )
                }.onFailure {
                    withContext(Dispatchers.IO) {
                        clearStagedControllerProfileTransfer(this@MainActivity)
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "Android's document picker could not be opened: ${friendlyProfileFileError(it)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        return true
    }

    override fun onAllProfilesExportRequested(): Boolean {
        val activeName = layoutName
        val activeProfile = controllerProfileForSave()
        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) {
                createControllerProfilesBackup(
                    this@MainActivity,
                    activeName,
                    activeProfile
                ).mapCatching(::encodeControllerProfilesBackup)
                    .fold(
                        onSuccess = { stageProfileTransferDocument(this@MainActivity, it) },
                        onFailure = { Result.failure(it) }
                    )
            }
            if (staged.isFailure) {
                withContext(Dispatchers.IO) {
                    clearStagedControllerProfileTransfer(this@MainActivity)
                }
                Toast.makeText(
                    this@MainActivity,
                    "Could not prepare the all-profiles export: ${friendlyProfileFileError(staged.exceptionOrNull())}",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                runCatching {
                    createProfileDocumentLauncher.launch(controllerProfilesBackupFileName())
                }.onFailure {
                    withContext(Dispatchers.IO) {
                        clearStagedControllerProfileTransfer(this@MainActivity)
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "Android's document picker could not be opened: ${friendlyProfileFileError(it)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        return true
    }

    private fun writeAndVerifyProfileExport(uri: Uri) {
        lifecycleScope.launch {
            val attempt = withContext(Dispatchers.IO) {
                var writeCompleted = false
                val result = runCatching {
                    val stagedText = readStagedControllerProfileTransfer(this@MainActivity)
                        .getOrThrow()
                    val expected = decodeProfileTransferDocument(stagedText, "Exported Profile")
                    val output = contentResolver.openOutputStream(uri, "wt")
                        ?: error("The selected location could not be opened for writing.")
                    output.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                        writer.write(stagedText)
                    }
                    writeCompleted = true

                    val writtenText = contentResolver.openInputStream(uri)?.use {
                        readControllerProfileTransferText(it)
                    } ?: error("The exported file could not be reopened for verification.")
                    val verified = decodeProfileTransferDocument(
                        writtenText,
                        "Exported Profile"
                    )
                    require(verified == expected) {
                        "The exported profile data did not match the data prepared by SimpleController."
                    }
                    verified
                }
                clearStagedControllerProfileTransfer(this@MainActivity)
                writeCompleted to result
            }

            val verified = attempt.second.getOrNull()
            if (verified != null) {
                val message = when (verified) {
                    is ProfileTransferDocument.Single ->
                        "Export verified: ${verified.imported.suggestedName} " +
                            "(${verified.imported.result.profile.pages.size} pages)"
                    is ProfileTransferDocument.AllProfiles -> {
                        val backup = verified.loaded.backup
                        val reusableCount = backup.touchAimCalibrations.size +
                            backup.touchAimManualProfiles.size + backup.buttonAimProfiles.size +
                            backup.stickDirectionalProfiles.size
                        "All-profile export verified: ${backup.controllerProfiles.size} controller, " +
                            "$reusableCount reusable profiles"
                    }
                }
                Toast.makeText(
                    this@MainActivity,
                    message,
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val prefix = if (attempt.first) {
                    "The file was written, but verification failed"
                } else {
                    "Profile export failed"
                }
                Toast.makeText(
                    this@MainActivity,
                    "$prefix: ${friendlyProfileFileError(attempt.second.exceptionOrNull())}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun readAndPromptForProfileImport(uri: Uri) {
        lifecycleScope.launch {
            val document = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = profileDocumentDisplayName(uri)
                    val text = contentResolver.openInputStream(uri)?.use {
                        readControllerProfileTransferText(it)
                    } ?: error("The selected file could not be opened.")
                    displayName to decodeProfileTransferDocument(text, displayName)
                }
            }
            document.fold(
                onSuccess = { (displayName, document) ->
                    when (document) {
                        is ProfileTransferDocument.Single ->
                            promptForProfileImport(document.imported, displayName)
                        is ProfileTransferDocument.AllProfiles ->
                            promptForAllProfilesImport(document.loaded, displayName)
                    }
                },
                onFailure = {
                    Toast.makeText(
                        this@MainActivity,
                        "Profile import failed: ${friendlyProfileFileError(it)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun promptForProfileImport(
        imported: ImportedControllerProfile,
        sourceDisplayName: String
    ) {
        val input = EditText(this).apply {
            setText(uniqueImportedProfileName(imported.suggestedName, listLayouts(this@MainActivity)))
            selectAll()
        }
        val warnings = imported.result.warnings
        val dialog = AlertDialog.Builder(this)
            .setTitle("Import controller profile")
            .setMessage(
                buildString {
                    append("File: $sourceDisplayName\n")
                    append("Pages: ${imported.result.profile.pages.size}\n\n")
                    append("The imported profile will be saved as a new profile and loaded at its Home page. ")
                    append("Your current profile will not be overwritten.")
                    if (imported.result.migratedFromSinglePage) {
                        append("\n\nThis older one-page layout will be imported as a Base page.")
                    }
                    if (warnings.isNotEmpty()) {
                        append("\n\nProfile warning:\n")
                        append(warnings.take(3).joinToString("\n"))
                    }
                }
            )
            .setView(input)
            .setPositiveButton("Import and load", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val importButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            importButton.setOnClickListener {
                val requestedName = input.text.toString().trim()
                val problem = validateImportedProfileName(requestedName)
                if (problem != null) {
                    input.error = problem
                    return@setOnClickListener
                }
                if (listLayouts(this).any { it.equals(requestedName, ignoreCase = true) }) {
                    input.error = "That profile already exists. Choose a new name."
                    return@setOnClickListener
                }

                importButton.isEnabled = false
                lifecycleScope.launch {
                    val verified = withContext(Dispatchers.IO) {
                        if (listLayouts(this@MainActivity).any {
                                it.equals(requestedName, ignoreCase = true)
                            }
                        ) return@withContext null

                        val saved = saveControllerProfile(
                            this@MainActivity,
                            requestedName,
                            imported.result.profile
                        )
                        if (saved.isFailure) return@withContext null
                        val reloaded = (readControllerProfile(
                            this@MainActivity,
                            requestedName
                        ) as? StoredControllerProfileResult.Loaded)?.result
                        reloaded?.takeIf { it.profile == imported.result.profile } ?: run {
                            deleteControllerProfile(this@MainActivity, requestedName)
                            null
                        }
                    }
                    importButton.isEnabled = true
                    if (verified == null) {
                        Toast.makeText(
                            this@MainActivity,
                            "The imported profile could not be saved and verified; the current profile is unchanged.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        dialog.dismiss()
                        onControllerProfileLoaded(requestedName, verified)
                        Toast.makeText(
                            this@MainActivity,
                            "Imported \"$requestedName\" with ${verified.profile.pages.size} pages",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun promptForAllProfilesImport(
        loaded: ControllerProfilesBackupLoadResult,
        sourceDisplayName: String
    ) {
        val backup = loaded.backup
        val reusableCount = backup.touchAimCalibrations.size +
            backup.touchAimManualProfiles.size + backup.buttonAimProfiles.size +
            backup.stickDirectionalProfiles.size
        val dialog = AlertDialog.Builder(this)
            .setTitle("Import all profiles")
            .setMessage(
                buildString {
                    append("File: $sourceDisplayName\n")
                    append("Controller profiles: ${backup.controllerProfiles.size}\n")
                    append("Reusable profiles: $reusableCount\n\n")
                    append("This adds every profile in the backup. Existing profiles are kept, ")
                    append("and duplicate names receive an Imported suffix. ")
                    append("Your currently loaded profile stays unchanged.")
                    if (loaded.warnings.isNotEmpty()) {
                        append("\n\nProfile warning:\n")
                        append(loaded.warnings.take(3).joinToString("\n"))
                    }
                }
            )
            .setPositiveButton("Import all", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val importButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            importButton.setOnClickListener {
                importButton.isEnabled = false
                lifecycleScope.launch {
                    val imported = withContext(Dispatchers.IO) {
                        importControllerProfilesBackup(this@MainActivity, loaded)
                    }
                    importButton.isEnabled = true
                    imported.fold(
                        onSuccess = { result ->
                            dialog.dismiss()
                            val importedReusable = result.touchAimCalibrationCount +
                                result.touchAimManualProfileCount + result.buttonAimProfileCount +
                                result.stickDirectionalProfileCount
                            Toast.makeText(
                                this@MainActivity,
                                "Imported ${result.controllerProfileCount} controller and " +
                                    "$importedReusable reusable profiles",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        onFailure = {
                            Toast.makeText(
                                this@MainActivity,
                                "All-profile import failed; no existing profile was overwritten: " +
                                    friendlyProfileFileError(it),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }
        }
        dialog.show()
    }

    private fun profileDocumentDisplayName(uri: Uri): String {
        val displayName = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index < 0) null else cursor.getString(index)
                }
            }
        }.getOrNull()
        return sanitizeSuggestedProfileName(displayName.orEmpty())
    }

    private fun friendlyProfileFileError(error: Throwable?): String =
        error?.message?.takeIf(String::isNotBlank) ?: "unknown file error"

    /**
     * Clear all control views
     */
    override fun clearControlViews() {
        cancelTouchAimCalibration("Calibration stopped because the layout changed.")
        canvas.children.filter { it.tag == "control" }.toList()
            .forEach { canvas.removeView(it) }
    }

    fun controllerPageOptions(): List<ControllerPageOption> =
        controllerProfile.pages.map { ControllerPageOption(it.id, it.name) }

    private fun syncDisplayedPageIntoProfile() {
        if (orientationRenderPending || !::controllerProfile.isInitialized || displayedPageId.isBlank()) {
            return
        }
        val index = controllerProfile.pages.indexOfFirst { it.id == displayedPageId }
        if (index < 0) return
        val updated = controllerProfile.pages.toMutableList()
        val existing = updated[index]
        val copiedControls = deepCopyControls(controls)
        val controlIds = copiedControls.mapTo(mutableSetOf()) { it.id }
        val priorCurrentGeometry = existing.geometryFor(activeLayoutOrientation)
        val canvasWidth = canvas.width.toFloat().takeIf { it > 0f }
            ?: priorCurrentGeometry?.canvasWidth
            ?: 0f
        val canvasHeight = canvas.height.toFloat().takeIf { it > 0f }
            ?: priorCurrentGeometry?.canvasHeight
            ?: 0f
        var page = existing.copy(controls = copiedControls).withGeometry(
            activeLayoutOrientation,
            capturePageGeometry(copiedControls, canvasWidth, canvasHeight)
        )
        val otherOrientation = activeLayoutOrientation.opposite()
        page = page.withGeometry(
            otherOrientation,
            page.geometryFor(otherOrientation)?.withOnlyControlIds(controlIds)
        )
        updated[index] = page
        controllerProfile = controllerProfile.copy(pages = updated)
    }

    private fun clearControlViewsSafely() {
        cancelTouchAimCalibration("Calibration stopped because the page changed.")
        canvas.children.filterIsInstance<ControlView>().toList().forEach { view ->
            view.markSafeForSilentDetach()
            canvas.removeView(view)
        }
    }

    private fun renderPage(pageId: String, safeToDetach: Boolean) {
        val page = controllerProfile.page(pageId) ?: return
        if (safeToDetach) clearControlViewsSafely() else clearControlViews()
        controls.clear()
        controls.addAll(
            deepCopyControls(
                page.controlsForOrientation(
                    activeLayoutOrientation,
                    canvas.width.toFloat(),
                    canvas.height.toFloat()
                )
            )
        )
        displayedPageId = page.id
        layoutManager.spawnControlViews()
        updatePageSelectorLabel()
    }

    private fun installProfile(name: String, profile: ControllerProfile) {
        controllerProfile = profile
        pageSession = ControllerPageSession(profile.homePageId, profile.pages.map { it.id })
        displayedPageId = profile.homePageId
        editingPageId = profile.homePageId
        layoutName = name
        renderPage(profile.homePageId, safeToDetach = true)
    }

    private fun resetToHomeAfterCleanup() {
        if (!::controllerProfile.isInitialized) return
        syncDisplayedPageIntoProfile()
        pageSession.resetToHome()
        editingPageId = controllerProfile.homePageId
        if (displayedPageId != controllerProfile.homePageId) {
            renderPage(controllerProfile.homePageId, safeToDetach = true)
        } else {
            updatePageSelectorLabel()
        }
    }

    private fun activateControllerPageAction(action: PageAction, targetPageId: String) {
        if (GlobalSettings.editMode) return
        val result = pageSession.perform(
            action = action,
            targetPageId = targetPageId,
            beforeSwitch = {
                syncDisplayedPageIntoProfile()
                releaseOutputs(showFeedback = false)
            },
            showPage = { renderPage(it, safeToDetach = true) }
        )
        result.warning?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            return
        }
        if (result.changed) {
            val name = controllerProfile.pageName(result.toPageId).orEmpty()
            canvas.performHapticFeedback(
                android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
            Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pageActionDisplayLabel(control: Control): String? {
        if (!control.isLocalPageAction()) return null
        val target = controllerProfile.pageName(control.pageTargetId)
        return when (control.pageAction) {
            PageAction.GO_TO -> target?.let { "Go to $it" } ?: "⚠ Missing page"
            PageAction.TOGGLE -> target?.let { "Toggle $it" } ?: "⚠ Missing page"
            PageAction.RETURN -> "Previous page"
            PageAction.HOME -> "Home page"
            PageAction.NONE -> null
        }
    }

    private fun updatePageSelectorLabel() {
        if (!::btnPageManager.isInitialized) return
        val pageName = controllerProfile.pageName(editingPageId)
            ?: controllerProfile.pageName(displayedPageId)
            ?: "Missing"
        val home = if (editingPageId == controllerProfile.homePageId) " · Home" else ""
        btnPageManager.text = "Editing page: $pageName$home ▾"
    }

    private fun requestEditModeChange(edit: Boolean) {
        if (edit) {
            syncDisplayedPageIntoProfile()
            GlobalSettings.editMode = true
            releaseOutputs(showFeedback = false)
            pageSession.resetToHome()
            editingPageId = controllerProfile.homePageId
            renderPage(controllerProfile.homePageId, safeToDetach = true)
            btnEdit.text = "Done"
            updateEditUi(true)
            return
        }

        syncDisplayedPageIntoProfile()
        val validPageIds = controllerProfile.pages.mapTo(mutableSetOf()) { it.id }
        val unsafePages = controllerProfile.pages.filter {
            it.id != controllerProfile.homePageId && !it.hasUsablePageNavigation(validPageIds)
        }
        if (unsafePages.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Page navigation warning")
                .setMessage(
                    "These pages have no usable Return, Home, or valid Toggle button:\n\n" +
                        unsafePages.joinToString("\n") { "• ${it.name}" } +
                        "\n\nYou can still use Edit as an emergency route. Enter Play Mode anyway?"
                )
                .setPositiveButton("Enter Play Mode") { _, _ -> finishLeavingEditMode() }
                .setNegativeButton("Keep editing", null)
                .show()
        } else finishLeavingEditMode()
    }

    private fun finishLeavingEditMode() {
        syncDisplayedPageIntoProfile()
        releaseOutputs(showFeedback = false)
        cancelTouchAimCalibration("Calibration stopped because Play Mode started.")
        pageSession.resetToHome()
        editingPageId = controllerProfile.homePageId
        renderPage(controllerProfile.homePageId, safeToDetach = true)
        GlobalSettings.editMode = false
        btnEdit.text = "Edit"
        updateEditUi(false)
        switchToOrientation(
            orientation = orientationStore.readPlayOrientation(),
            pageId = controllerProfile.homePageId
        )
    }

    private fun requestEditorOrientation(orientation: LayoutOrientation) {
        if (!GlobalSettings.editMode || orientation == activeLayoutOrientation) return
        syncDisplayedPageIntoProfile()
        releaseOutputs(showFeedback = false)
        cancelTouchAimCalibration("Calibration stopped because the editor orientation changed.")
        switchToOrientation(orientation, editingPageId.ifBlank { displayedPageId })
    }

    private fun switchToOrientation(orientation: LayoutOrientation, pageId: String) {
        pendingOrientationPageId = pageId
        orientationRenderPending = true
        if (::btnEditOrientation.isInitialized) btnEditOrientation.isEnabled = false
        requestedOrientation = requestedOrientationFor(orientation)
        updateOrientationButtonLabels()

        // Android does not send another configuration callback when the requested orientation is
        // already active. Complete the render after layout in that case.
        if (layoutOrientationFrom(resources.configuration) == orientation) {
            activeLayoutOrientation = orientation
            pendingOrientationPageId = null
            orientationRenderPending = false
            renderPage(pageId, safeToDetach = true)
            if (::btnEditOrientation.isInitialized) btnEditOrientation.isEnabled = true
            updateOrientationButtonLabels()
            return
        }

        canvas.postDelayed({
            if (orientationRenderPending && pendingOrientationPageId == pageId &&
                layoutOrientationFrom(resources.configuration) != orientation
            ) {
                pendingOrientationPageId = null
                orientationRenderPending = false
                activeLayoutOrientation = layoutOrientationFrom(resources.configuration)
                if (::btnEditOrientation.isInitialized) btnEditOrientation.isEnabled = true
                updateOrientationButtonLabels()
                Toast.makeText(
                    this,
                    "Android did not allow the requested orientation on this display.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, 1500L)
    }

    private fun updateOrientationButtonLabels() {
        if (::btnEditOrientation.isInitialized) {
            btnEditOrientation.text = when (activeLayoutOrientation.opposite()) {
                LayoutOrientation.PORTRAIT -> "Edit in Portrait"
                LayoutOrientation.LANDSCAPE -> "Edit in Landscape"
            }
        }
        if (::btnPlayOrientation.isInitialized) {
            btnPlayOrientation.text = when (orientationStore.readPlayOrientation()) {
                LayoutOrientation.PORTRAIT -> "Play lock: Portrait"
                LayoutOrientation.LANDSCAPE -> "Play lock: Landscape"
            }
        }
    }

    private fun requestedOrientationFor(orientation: LayoutOrientation): Int = when (orientation) {
        LayoutOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        LayoutOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun layoutOrientationFrom(configuration: Configuration): LayoutOrientation =
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            LayoutOrientation.LANDSCAPE
        } else {
            LayoutOrientation.PORTRAIT
        }

    private fun showControllerPageManager() {
        if (!GlobalSettings.editMode) return
        syncDisplayedPageIntoProfile()
        var dialog: AlertDialog? = null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        content.addView(TextView(this).apply {
            text = "Select a page to edit"
            textSize = 18f
            setPadding(8, 8, 8, 8)
        })
        controllerProfile.pages.forEach { page ->
            content.addView(pageManagerButton(
                "${if (page.id == editingPageId) "✓ " else ""}${page.name}" +
                    if (page.id == controllerProfile.homePageId) "  (Home)" else ""
            ) {
                dialog?.dismiss()
                selectEditingPage(page.id)
            })
        }
        content.addView(pageManagerButton("Add blank page") {
            dialog?.dismiss()
            promptCreateBlankPage()
        })
        content.addView(pageManagerButton("Duplicate current page") {
            dialog?.dismiss()
            promptDuplicateCurrentPage()
        })
        content.addView(pageManagerButton("Import saved profile as page") {
            dialog?.dismiss()
            showImportProfilePicker()
        })
        content.addView(pageManagerButton("Rename current page") {
            dialog?.dismiss()
            promptRenameCurrentPage()
        })
        content.addView(pageManagerButton("Delete current page") {
            dialog?.dismiss()
            confirmDeleteCurrentPage()
        })
        content.addView(pageManagerButton("Set current page as Home") {
            dialog?.dismiss()
            setCurrentPageAsHome()
        }.apply { isEnabled = editingPageId != controllerProfile.homePageId })

        dialog = AlertDialog.Builder(this)
            .setTitle("Controller Pages")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }

    private fun pageManagerButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = (56 * resources.displayMetrics.density).toInt()
        setOnClickListener { action() }
    }

    private fun selectEditingPage(pageId: String) {
        if (!GlobalSettings.editMode || controllerProfile.page(pageId) == null) return
        syncDisplayedPageIntoProfile()
        editingPageId = pageId
        renderPage(pageId, safeToDetach = true)
    }

    private fun promptCreateBlankPage() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
        }
        val name = EditText(this).apply { hint = "Page name" }
        val copyNavigation = CheckBox(this).apply {
            text = "Copy page-navigation buttons from the current page"
            isChecked = true
        }
        container.addView(name)
        container.addView(copyNavigation)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add blank page")
            .setMessage("Copied navigation buttons keep their current positions; move them in Edit Mode if needed.")
            .setView(container)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val pageName = name.text.toString().trim()
                if (!validNewPageName(pageName)) return@setOnClickListener
                syncDisplayedPageIntoProfile()
                val id = newPageId()
                val source = controllerProfile.page(editingPageId) ?: return@setOnClickListener
                val page = if (copyNavigation.isChecked) {
                    detachedPageCopy(
                        source = source,
                        destinationPageId = id,
                        newName = pageName,
                        include = Control::isLocalPageAction,
                        redirectSelfTargets = false
                    )
                } else ControllerPage(id, pageName, emptyList())
                addNewPageWithWarning(page)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun promptDuplicateCurrentPage() {
        val source = controllerProfile.page(editingPageId) ?: return
        val input = EditText(this).apply { setText(uniquePageName("${source.name} Copy")) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Duplicate current page")
            .setMessage("The duplicate is an independent deep copy. Later edits will not affect the source page.")
            .setView(input)
            .setPositiveButton("Duplicate", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (!validNewPageName(name)) return@setOnClickListener
                syncDisplayedPageIntoProfile()
                val current = controllerProfile.page(editingPageId) ?: return@setOnClickListener
                val newId = newPageId()
                addNewPage(
                    detachedPageCopy(
                        source = current,
                        destinationPageId = newId,
                        newName = name,
                        redirectSelfTargets = true
                    )
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showImportProfilePicker() {
        val names = listLayouts(this).sorted()
        if (names.isEmpty()) {
            Toast.makeText(this, "No saved profiles are available to import.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Import from saved profile")
            .setItems(names.toTypedArray()) { _, index ->
                val sourceName = names[index]
                val stored = readControllerProfile(this, sourceName)
                val result = (stored as? StoredControllerProfileResult.Loaded)?.result
                if (result == null) {
                    val message = if (stored is StoredControllerProfileResult.NotFound) {
                        "'$sourceName' no longer exists."
                    } else "Could not read '$sourceName'; its file was not changed."
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                } else if (result.profile.pages.size == 1) {
                    promptImportPage(result.profile.pages.first(), result.warnings)
                } else {
                    val pages = result.profile.pages
                    AlertDialog.Builder(this)
                        .setTitle("Choose page from $sourceName")
                        .setItems(pages.map { it.name }.toTypedArray()) { _, pageIndex ->
                            promptImportPage(pages[pageIndex], result.warnings)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptImportPage(source: ControllerPage, warnings: List<String>) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
        }
        val name = EditText(this).apply { setText(uniquePageName(source.name)) }
        val copyNavigation = CheckBox(this).apply {
            text = "Also copy page-navigation buttons from the current page"
            isChecked = true
        }
        container.addView(name)
        container.addView(copyNavigation)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Import '${source.name}'")
            .setMessage(
                buildString {
                    append("The imported page is an independent copy and keeps no link to its source file. ")
                    append("Copied navigation buttons keep their positions and may overlap imported controls; review them in Edit Mode.")
                    if (warnings.isNotEmpty()) {
                        append("\n\nSource warning:\n")
                        append(warnings.take(3).joinToString("\n"))
                    }
                }
            )
            .setView(container)
            .setPositiveButton("Import", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val pageName = name.text.toString().trim()
                if (!validNewPageName(pageName)) return@setOnClickListener
                syncDisplayedPageIntoProfile()
                val newId = newPageId()
                var importedPage = detachedPageCopy(
                    source = source,
                    destinationPageId = newId,
                    newName = pageName,
                    redirectSelfTargets = true
                )
                if (copyNavigation.isChecked) {
                    val current = controllerProfile.page(editingPageId)
                        ?: return@setOnClickListener
                    val navigationPage = detachedPageCopy(
                        source = current,
                        destinationPageId = newId,
                        newName = pageName,
                        include = Control::isLocalPageAction,
                        redirectSelfTargets = false
                    )
                    val width = canvas.width.toFloat()
                    val height = canvas.height.toFloat()
                    val importedControls = importedPage.controlsForOrientation(
                        activeLayoutOrientation, width, height
                    )
                    val navigationControls = navigationPage.controlsForOrientation(
                        activeLayoutOrientation, width, height
                    )
                    val combined = importedControls + navigationControls
                    importedPage = importedPage.copy(controls = combined).withGeometry(
                        activeLayoutOrientation,
                        capturePageGeometry(combined, width, height)
                    )
                }
                addNewPageWithWarning(importedPage)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun addNewPageWithWarning(page: ControllerPage) {
        val validIds = controllerProfile.pages.mapTo(mutableSetOf()) { it.id }.apply { add(page.id) }
        if (page.hasUsablePageNavigation(validIds)) {
            addNewPage(page)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("No page-navigation button")
            .setMessage(
                "'${page.name}' has no usable Return, Home, or Toggle action. " +
                    "Edit remains an emergency route, but this page may be difficult to leave in Play Mode."
            )
            .setPositiveButton("Add anyway") { _, _ -> addNewPage(page) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addNewPage(page: ControllerPage) {
        syncDisplayedPageIntoProfile()
        controllerProfile = controllerProfile.copy(pages = controllerProfile.pages + page)
        pageSession.updateProfile(controllerProfile.homePageId, controllerProfile.pages.map { it.id })
        editingPageId = page.id
        renderPage(page.id, safeToDetach = true)
    }

    private fun detachedPageCopy(
        source: ControllerPage,
        destinationPageId: String,
        newName: String,
        include: (Control) -> Boolean = { true },
        redirectSelfTargets: Boolean
    ): ControllerPage {
        val selected = source.controls.filter(include)
        val detached = deepCopyControls(selected)
        val idMap = selected.indices.associate { index ->
            selected[index].id to "control_${newPageId()}"
        }
        val copiedControls = detached.map { control ->
            control.copy(
                id = idMap.getValue(control.id),
                pageTargetId = if (redirectSelfTargets && control.pageTargetId == source.id) {
                    destinationPageId
                } else control.pageTargetId
            )
        }
        val selectedIds = idMap.keys
        return ControllerPage(
            id = destinationPageId,
            name = newName,
            controls = copiedControls,
            portraitGeometry = source.portraitGeometry
                ?.withOnlyControlIds(selectedIds)
                ?.remapControlIds(idMap),
            landscapeGeometry = source.landscapeGeometry
                ?.withOnlyControlIds(selectedIds)
                ?.remapControlIds(idMap)
        )
    }

    private fun promptRenameCurrentPage() {
        val page = controllerProfile.page(editingPageId) ?: return
        val input = EditText(this).apply { setText(page.name) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename page")
            .setView(input)
            .setPositiveButton("Rename", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank() || controllerProfile.hasPageName(name, exceptPageId = page.id)) {
                    Toast.makeText(this, "Page names must be unique.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                syncDisplayedPageIntoProfile()
                controllerProfile = controllerProfile.copy(
                    pages = controllerProfile.pages.map {
                        if (it.id == page.id) it.copy(name = name) else it
                    }
                )
                updatePageSelectorLabel()
                canvas.children.filterIsInstance<ControlView>()
                    .forEach(ControlView::refreshPageActionLabel)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDeleteCurrentPage() {
        syncDisplayedPageIntoProfile()
        val page = controllerProfile.page(editingPageId) ?: return
        when {
            controllerProfile.pages.size == 1 -> {
                Toast.makeText(this, "The only page cannot be deleted.", Toast.LENGTH_LONG).show()
                return
            }
            page.id == controllerProfile.homePageId -> {
                Toast.makeText(this, "Set another page as Home before deleting this one.", Toast.LENGTH_LONG).show()
                return
            }
        }
        val references = controllerProfile.referenceCount(page.id, excludingPageId = page.id)
        AlertDialog.Builder(this)
            .setTitle("Delete '${page.name}'?")
            .setMessage(
                "$references control${if (references == 1) "" else "s"} reference this page. " +
                    "Those controls will be visibly marked as missing. This cannot be undone."
            )
            .setPositiveButton("Delete") { _, _ ->
                syncDisplayedPageIntoProfile()
                controllerProfile = controllerProfile.copy(
                    pages = controllerProfile.pages.filterNot { it.id == page.id }
                )
                pageSession.updateProfile(
                    controllerProfile.homePageId,
                    controllerProfile.pages.map { it.id }
                )
                editingPageId = controllerProfile.homePageId
                renderPage(controllerProfile.homePageId, safeToDetach = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setCurrentPageAsHome() {
        val page = controllerProfile.page(editingPageId) ?: return
        syncDisplayedPageIntoProfile()
        controllerProfile = controllerProfile.copy(homePageId = page.id)
        pageSession.updateProfile(page.id, controllerProfile.pages.map { it.id })
        Toast.makeText(this, "${page.name} is now Home", Toast.LENGTH_SHORT).show()
        updatePageSelectorLabel()
    }

    private fun validNewPageName(name: String): Boolean {
        val valid = name.isNotBlank() && !controllerProfile.hasPageName(name)
        if (!valid) Toast.makeText(this, "Page names must be nonblank and unique.", Toast.LENGTH_LONG).show()
        return valid
    }

    private fun uniquePageName(baseName: String): String {
        val base = baseName.trim().ifBlank { "Page" }
        if (!controllerProfile.hasPageName(base)) return base
        var suffix = 2
        while (controllerProfile.hasPageName("$base $suffix")) suffix++
        return "$base $suffix"
    }

    /** Public so an assignable RELEASE_ALL ControlView uses the same safety path. */
    fun activateReleaseAll(showFeedback: Boolean = true) {
        syncDisplayedPageIntoProfile()
        releaseOutputs(showFeedback)
    }

    private fun releaseOutputs(showFeedback: Boolean) {
        if (showFeedback) triggerReleaseAllHaptic()
        ReleaseAllCoordinator.releaseAll { status ->
            if (!showFeedback) return@releaseAll
            runOnUiThread {
                val message = when (status) {
                    ReleaseAllCoordinator.ReceiverStatus.PENDING ->
                        "Android outputs cleared; awaiting receiver confirmation"
                    ReleaseAllCoordinator.ReceiverStatus.CONFIRMED ->
                        "Receiver confirmed: all outputs released"
                    ReleaseAllCoordinator.ReceiverStatus.UNAVAILABLE ->
                        "Android outputs cleared — receiver confirmation unavailable"
                    ReleaseAllCoordinator.ReceiverStatus.CONSOLEBRIDGE_UNAVAILABLE ->
                        "Android outputs cleared — Pico release is not implemented yet"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun startTouchAimCalibration(
        controlId: String,
        existingProfile: TouchAimCalibrationProfile? = null
    ) {
        cancelTouchAimCalibration()
        val launchToken = ++touchAimCalibrationLaunchGeneration
        val view = canvas.children.filterIsInstance<ControlView>()
            .firstOrNull { it.model.id == controlId }
        if (view == null || view.model.type != ControlType.TOUCH_AIM) {
            Toast.makeText(this, "TouchAim control is unavailable.", Toast.LENGTH_LONG).show()
            return
        }
        val launch = launch@{
            if (launchToken != touchAimCalibrationLaunchGeneration || view.parent == null) return@launch
            val wizard = TouchAimCalibrationWizard(this, view, view.model, existingProfile) { applied ->
                activeTouchAimCalibration = null
                if (applied) {
                    syncDisplayedPageIntoProfile()
                    if (!profileAutosaveBlocked) {
                        saveControllerProfile(this, layoutName, controllerProfile)
                    }
                }
            }
            activeTouchAimCalibration = wizard
            wizard.show()
        }
        if (view.isLayoutRequested) view.doOnNextLayout { launch() } else view.post(launch)
    }

    fun cancelTouchAimCalibration(reason: String? = null) {
        touchAimCalibrationLaunchGeneration++
        val wizard = activeTouchAimCalibration ?: return
        activeTouchAimCalibration = null
        wizard.cancel(reason)
    }

    private fun triggerReleaseAllHaptic() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0L, 35L, 45L, 90L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    /**
     * Creates a standard Xbox controller layout
     */
    private fun createXboxControllerLayout() {
        // First clear existing controls
        controls.clear()
        clearControlViewsSafely()

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
        finalizeBuiltInPage()

        Toast.makeText(this, "Current page replaced with Xbox controls", Toast.LENGTH_SHORT).show()
    }

    /**
     * Creates a Player 1 Xbox controller layout
     */
    private fun createPlayer1XboxLayout() {
        // First clear existing controls
        controls.clear()
        clearControlViewsSafely()

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
        finalizeBuiltInPage()

        Toast.makeText(this, "Current page replaced with Player 1 controls", Toast.LENGTH_SHORT).show()
    }


    /**
     * Creates a Player 2 Xbox controller layout
     */
    private fun createPlayer2XboxLayout() {
        // First clear existing controls
        controls.clear()
        clearControlViewsSafely()

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
        finalizeBuiltInPage()

        Toast.makeText(this, "Current page replaced with Player 2 controls", Toast.LENGTH_SHORT).show()
    }

    private fun finalizeBuiltInPage() {
        syncDisplayedPageIntoProfile()
        updatePageSelectorLabel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cancelTouchAimCalibration("Calibration stopped because the screen configuration changed.")
        val newOrientation = layoutOrientationFrom(newConfig)
        if (newOrientation == activeLayoutOrientation && pendingOrientationPageId == null) return

        // Explicit editor/Play transitions release before requesting orientation. Keep this
        // defensive cleanup for any configuration transition Android delivers independently.
        releaseOutputs(showFeedback = false)
        val pageId = pendingOrientationPageId ?: displayedPageId
        orientationRenderPending = true
        canvas.doOnNextLayout {
            activeLayoutOrientation = newOrientation
            renderPage(pageId, safeToDetach = true)
            pendingOrientationPageId = null
            orientationRenderPending = false
            if (::btnEditOrientation.isInitialized) btnEditOrientation.isEnabled = true
            updateOrientationButtonLabels()
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        cancelTouchAimCalibration("Calibration stopped because the window size changed.")
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
