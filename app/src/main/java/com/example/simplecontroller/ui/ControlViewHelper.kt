package com.example.simplecontroller.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import com.example.simplecontroller.MainActivity
import com.example.simplecontroller.io.LayoutManager
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.net.UdpClient
import kotlin.math.min


/**
 * Helper class for creating and managing ControlView UI elements and behavior.
 *
 * This class handles common functionality used by ControlView instances:
 * - Creating and managing UI controls (labels, buttons)
 * - Helper methods for payload handling
 * - Turbo/repeat functionality
 */
class ControlViewHelper(
    private val context: Context,
    private val model: Control,
    private val parentView: ControlView,
    private val onDeleteRequested: (Control) -> Unit
) {
    private var pulseRepeater: Runnable? = null
    var pulseAlreadyFired = false
    private var isPulseLoopActive = false
    // Handler specifically for pulse feature to ensure we're using the same instance
    private val pulseHandler = Handler(Looper.getMainLooper())

    // UI Handler for callbacks
    private val uiHandler = Handler(Looper.getMainLooper())

    var allowPulseLoop = true

    // For turbo/repeat functionality
    private var repeater: Runnable? = null

    // UI elements
    private val label = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.WHITE)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
        gravity = Gravity.CENTER
    }

    init {
        // Initialize UI elements
        parentView.addView(label)


    }

    /**
     * Update the label text based on the model name
     */
    fun updateLabel() {
        label.text = model.name
        label.visibility = if (model.name.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Show the property sheet for this control
     */
    private fun showProperties() {
        PropertySheetBuilder(context, model) {
            // After properties are updated:
            val lp = parentView.layoutParams as ViewGroup.MarginLayoutParams
            lp.width = model.w.toInt()
            lp.height = model.h.toInt()
            parentView.layoutParams = lp
            updateLabel()
            parentView.invalidate()

            // Stop any running senders when settings change
            parentView.stopContinuousSending()
            parentView.stopDirectionalCommands()
        }.showPropertySheet()
    }

    /**
     * Create a duplicate of this control
     */
    private fun duplicateSelf() {
        val copy = model.copy(
            id = "${model.id}_copy_${System.currentTimeMillis()}",
            x = model.x + 40f,
            y = model.y + 40f
        )
        (context as? MainActivity)?.createControlFrom(copy)
    }

    /**
     * Show delete confirmation dialog
     */
    private fun confirmDelete() {
        AlertDialog.Builder(context)
            .setMessage("Delete this control?")
            .setPositiveButton("Delete") { _, _ ->
                (parentView.parent as? FrameLayout)?.removeView(parentView)
                onDeleteRequested(model)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Send the control's payload
     */
    fun firePayload() {
        Log.d("DEBUG_PULSE", "firePayload() called, isLatched=${(parentView as? ControlView)?.isLatched}, payload='${model.payload}'")
        Log.d("ControlViewHelper", "=== PAYLOAD DEBUG START ===")
        Log.d("ControlViewHelper", "Control ID: ${model.id}")
        Log.d("ControlViewHelper", "Control Type: ${model.type}")
        Log.d("ControlViewHelper", "Raw Payload: '${model.payload}'")
        Log.d("ControlViewHelper", "Is Latched: ${(parentView as? ControlView)?.isLatched ?: false}")
        Log.d("ControlViewHelper", "Connection Status: ${NetworkClient.connectionStatus.value}")

        // If the button is latched, send _HOLD version to prevent auto-release
        if (parentView is ControlView && (parentView as ControlView).isLatched) {
            // For Xbox controller buttons, use the _HOLD suffix
            val commands = model.payload.split(',', ' ')
                .filter { it.isNotBlank() }
                .map { cmd ->
                    if (cmd.startsWith("X360") && !cmd.contains("_RELEASE")) {
                        "${cmd}_HOLD"
                    } else {
                        cmd
                    }
                }

            Log.d("ControlViewHelper", "Latched Mode - Commands to send: $commands")
            commands.forEach {
                Log.d("ControlViewHelper", "Sending latched command: '$it'")
                sendCommand(it.trim())
            }
        } else {
            // Regular version with auto-release for normal presses
            val commands = model.payload.split(',', ' ').filter { it.isNotBlank() }
            Log.d("ControlViewHelper", "Normal Mode - Commands to send: $commands")
            commands.forEach {
                Log.d("ControlViewHelper", "Sending normal command: '$it'")
                sendCommand(it.trim())
            }
        }
        Log.d("ControlViewHelper", "=== PAYLOAD DEBUG END ===")
    }

    /**
     * Send a command using the appropriate protocol based on command type
     */
    private fun sendCommand(command: String) {
        Log.d("ControlViewHelper", "sendCommand() called with: '$command'")

        // ───────────────── Scroll-mode toggle ─────────────────
        if (command == "SCROLL_MODE_TOGGLE") {
            GlobalSettings.scrollMode = !GlobalSettings.scrollMode
            Toast.makeText(
                context,
                if (GlobalSettings.scrollMode) "Scroll mode ON" else "Scroll mode OFF",
                Toast.LENGTH_SHORT
            ).show()
            return                                 // do not forward toggle to PC
        }

        // ───── Pulse support: LT:1.0P0.3 or RT:1.0P0.6 ─────
        val pulseMatch = Regex("""(LT|RT):([01](?:\.\d+)?)[Pp]([0-9.]+)""").matchEntire(command)
        Log.d("DEBUG_PULSE", "LOOP CHECK → latched=${(parentView as? ControlView)?.isLatched}, allowed=$allowPulseLoop, alreadyFired=$pulseAlreadyFired, active=$isPulseLoopActive")
        if (pulseMatch != null) {
            Log.d("DEBUG_PULSE", "Pulse match detected. isLatched=${(parentView as? ControlView)?.isLatched}")

            val trigger = pulseMatch.groupValues[1] // LT or RT
            val pressVal = pulseMatch.groupValues[2] // e.g. 1.0
            val holdTime = pulseMatch.groupValues[3].toFloatOrNull() ?: 0.2f // fallback to 0.2s

            val downCmd = "$trigger:$pressVal"
            val upCmd = "$trigger:0.0"

            val delayMs = (holdTime * 1000).toLong()
            
            // Stop any previous repeater using our dedicated handler
            pulseRepeater?.let { pulseHandler.removeCallbacks(it) }

            // 🔁 If latched, keep repeating
            if ((parentView as? ControlView)?.isLatched == true &&
                allowPulseLoop && !isPulseLoopActive && !pulseAlreadyFired) {

                pulseAlreadyFired = true   // ✅ SET IMMEDIATELY before posting the loop
                isPulseLoopActive = true   // ✅ Guard from further restarts

                pulseRepeater = object : Runnable {
                    override fun run() {
                        Log.d("ControlViewHelper", "Pulse (latched) sending: $downCmd → $upCmd")
                        UdpClient.sendCommand(downCmd)
                        pulseHandler.postDelayed({
                            UdpClient.sendCommand(upCmd)
                            Log.d("ControlViewHelper", "Pulse (latched) released: $upCmd")
                        }, delayMs / 2)

                        pulseHandler.postDelayed(this, delayMs)
                    }
                }
                pulseHandler.post(pulseRepeater!!)


            } else {
                // 🔂 Not latched → send once
                Log.d("ControlViewHelper", "Pulse Trigger: $downCmd → wait $holdTime → $upCmd")
                UdpClient.sendCommand(downCmd)
                pulseHandler.postDelayed({
                    UdpClient.sendCommand(upCmd)
                    Log.d("ControlViewHelper", "Pulse Trigger released: $upCmd")
                }, delayMs)
            }

            return // skip rest of sendCommand
        }
// ──────────────────────────────────────────────

        val isXboxCommand = command.startsWith("X360")
        val isMouseCommand = command.startsWith("MOUSE_")
        val isTouchpadCommand = command.startsWith("TOUCHPAD:")
        val isStickCommand = command.startsWith("STICK")
        val isTriggerCommand = command.startsWith("LT:") || command.startsWith("RT:")

        Log.d("ControlViewHelper", "Command type analysis:")
        Log.d("ControlViewHelper", "  - isXboxCommand: $isXboxCommand")
        Log.d("ControlViewHelper", "  - isMouseCommand: $isMouseCommand")
        Log.d("ControlViewHelper", "  - isTouchpadCommand: $isTouchpadCommand")
        Log.d("ControlViewHelper", "  - isStickCommand: $isStickCommand")
        Log.d("ControlViewHelper", "  - isTriggerCommand: $isTriggerCommand")

        if (isXboxCommand) {
            Log.d("ControlViewHelper", "Treating as Xbox command, using UdpClient.sendCommand")
            UdpClient.sendCommand(command)

        } else if (isTriggerCommand) {
            Log.d("ControlViewHelper", "Sending analog trigger command via UdpClient: $command")
            UdpClient.sendCommand(command)

        } else if (!isMouseCommand && !isTouchpadCommand && !isStickCommand) {
            Log.d(
                "ControlViewHelper",
                "Treating as keyboard command, using UdpClient.sendKeyCommand"
            )
            UdpClient.sendKeyCommand(command, true)

            if (!(parentView is ControlView && (parentView as ControlView).isLatched)) {
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d("ControlViewHelper", "Sending delayed key release for: '$command'")
                    UdpClient.sendKeyCommand(command, false)
                }, 100)
            }

        } else {
            Log.d("ControlViewHelper", "Treating as special command, using UdpClient.sendCommand")
            UdpClient.sendCommand(command)
        }
    }


    // Improved function to handle releasing held buttons
    fun releaseLatched() {
        Log.d("DEBUG_PULSE", "releaseLatched() CALLED")
        Log.d("ControlViewHelper", "releaseLatched() called for payload: '${model.payload}'")

        // Cancel any repeating pulse trigger using the same handler that created it
        pulseRepeater?.let {
            pulseHandler.removeCallbacks(it)
            pulseRepeater = null
            Log.d("DEBUG_PULSE", "pulseRepeater CANCELLED")
        }

        // 🔒 Absolute state reset - ensure all pulse-related state is completely reset
        isPulseLoopActive = false
        allowPulseLoop = false
        pulseAlreadyFired = false
        
        // Cancel any pending pulse tasks to be absolutely sure
        pulseHandler.removeCallbacksAndMessages(null)


        // Process all commands in the payload
        model.payload.split(',', ' ')
            .filter { it.isNotBlank() }
            .forEach { cmd ->
                Log.d("ControlViewHelper", "Processing release for command: '$cmd'")

                if (cmd.startsWith("X360")) {
                    // ── Xbox buttons ──────────────────────────────────────────
                    val releaseCmd = if (cmd.endsWith("_HOLD")) {
                        cmd.replace("_HOLD", "_RELEASE")
                    } else {
                        "${cmd}_RELEASE"
                    }
                    Log.d("ControlViewHelper",
                        "Sending Xbox release command via UdpClient: '$releaseCmd'")
                    UdpClient.sendCommand(releaseCmd.trim())

                } else if (cmd.startsWith("LT:") || cmd.startsWith("RT:")) {
                    // ── Analog triggers ───────────────────────────────────────
                    val releaseCmd = if (cmd.startsWith("LT:")) "LT:0.0" else "RT:0.0"
                    Log.d("ControlViewHelper",
                        "Sending analog trigger release: $releaseCmd")
                    UdpClient.sendCommand(releaseCmd)

                } else if (cmd.startsWith("MOUSE_RIGHT")) {
                    // ── NEW: right-mouse auto-release (matches suffix style) ──
                    val releaseCmd = when {
                        cmd.endsWith("_HOLD") -> cmd.replace("_HOLD", "_UP")
                        cmd.endsWith("_DOWN") -> cmd.replace("_DOWN", "_UP")
                        else                  -> "MOUSE_RIGHT_UP"
                    }
                    Log.d("ControlViewHelper",
                        "Sending right-mouse release via UdpClient: '$releaseCmd'")
                    UdpClient.sendCommand(releaseCmd.trim())

                } else if (cmd.startsWith("MOUSE_LEFT")) {
                    // ── NEW: left-mouse auto-release (matches suffix style) ──
                    val releaseCmd = when {
                        cmd.endsWith("_HOLD") -> cmd.replace("_HOLD", "_UP")
                        cmd.endsWith("_DOWN") -> cmd.replace("_DOWN", "_UP")
                        else                  -> "MOUSE_LEFT_UP"
                    }
                    Log.d("ControlViewHelper",
                        "Sending right-mouse release via UdpClient: '$releaseCmd'")
                    UdpClient.sendCommand(releaseCmd.trim())

                } else if (!cmd.startsWith("MOUSE_") &&
                    !cmd.startsWith("TOUCHPAD:") &&
                    !cmd.startsWith("STICK") &&
                    !cmd.startsWith("TRIGGER")) {
                    // ── Keyboard keys ─────────────────────────────────────────
                    Log.d("ControlViewHelper",
                        "Sending keyboard release command: '$cmd'")
                    UdpClient.sendKeyCommand(cmd.trim(), false)
                }
            }
    }

    /**
     * Start repeating the payload (for turbo mode)
     */
    fun startRepeat() {
        firePayload() // fire immediately
        repeater = object : Runnable {
            override fun run() {
                firePayload()
                uiHandler.postDelayed(this, GlobalSettings.turboSpeed) // Use the configurable speed
            }
        }
        uiHandler.postDelayed(repeater!!, GlobalSettings.turboSpeed)
    }

    /**
     * Stop repeating the payload
     */
    fun stopRepeat() {
        repeater?.let { uiHandler.removeCallbacks(it) }
        repeater = null
    }
}

/**
 * Global settings for all control views.
 *
 * This replaces the static variables in the ControlView companion object.
 */
object GlobalSettings {
    // Whether edit mode is currently active
    var editMode = false
        set(v) {
            field = v
            // Update all controls when edit mode changes
            SwipeManager.notifyEditModeChanged(v)
        }

    // Whether auto-center is enabled for sticks/pads
    var snapEnabled = false
        set(value) {
            field = value
            if (value) {
                // When enabling snap, stop any continuous sending
                SwipeManager.stopAllContinuousSending()
            }
        }

    // Whether all buttons should use hold/latch behavior
    var globalHold = false

    var scrollMode = false        // ← false = normal mouse, true = scroll

    // Whether all buttons should use turbo/rapid-fire
    var globalTurbo = false
        set(value) {
            if (field && !value) {
                // When turning turbo off, make sure all repeaters are stopped
                SwipeManager.stopAllRepeating()
            }
            field = value
        }

    // Speed of turbo/rapid-fire in milliseconds
    var turboSpeed = 16L // Default 16ms (≈60 Hz)



    // Whether swipe mode is active
    var globalSwipe = false
        set(value) {
            if (field && !value) {
                // When turning swipe off, cleanup active touches
                SwipeManager.cleanupActiveTouch()
            }
            field = value
            SwipeManager.setSwipeEnabled(value)
        }
}

/**
 * Central manager for swipe functionality to coordinate between all controls.
 */
object SwipeManager {
    // Reference to swipe handler that manages actual touch handling
    private val swipeHandler = SwipeHandler()

    // All registered control views
    private val allViews = mutableSetOf<ControlView>()

    /**
     * Register a control view with the manager
     */
    fun registerControl(view: ControlView) {
        allViews.add(view)
        swipeHandler.registerView(view)
    }

    /**
     * Unregister a control view
     */
    fun unregisterControl(view: ControlView) {
        allViews.remove(view)
        swipeHandler.unregisterView(view)
    }

    /**
     * Process a touch event through the swipe handler
     */
    fun processTouchEvent(e: android.view.MotionEvent): Boolean {
        return swipeHandler.processTouchEvent(e)
    }

    /**
     * Set whether swipe mode is enabled
     */
    fun setSwipeEnabled(enabled: Boolean) {
        swipeHandler.setSwipeEnabled(enabled)
    }

    /**
     * Notify all controls that edit mode has changed
     */
    fun notifyEditModeChanged(editMode: Boolean) {
        swipeHandler.setEditMode(editMode)
        allViews.forEach {
            if (editMode) {
                it.stopContinuousSending()
                it.stopDirectionalCommands()
            }
        }
    }

    /**
     * Stop all continuous sending when needed
     */
    fun stopAllContinuousSending() {
        allViews.forEach {
            it.stopContinuousSending()
            it.stopDirectionalCommands()
        }
    }

    /**
     * Stop all repeating (for turbo mode)
     */
    fun stopAllRepeating() {
        allViews.forEach { it.stopRepeat() }
    }

    /**
     * Clean up any active touch state when swipe is disabled
     */
    fun cleanupActiveTouch() {
        swipeHandler.setSwipeEnabled(false)
    }

    /**
     * Re-center all stick controls
     * This is triggered by the Re-center button
     */
    fun recenterAllSticks() {
        allViews.forEach { view ->
            if (view.model.type == ControlType.STICK) {
                // Stop any continuous sending or directional commands
                view.stopContinuousSending()
                view.stopDirectionalCommands()

                // Send zero values to center the stick
                if (view.model.directionalMode) {
                    // For directional sticks, we just stop any active commands
                } else {
                    // For analog sticks, send center position (0,0)
                    NetworkClient.send("${view.model.payload}:0.00,0.00")
                }
            }
        }
    }
}