package com.example.simplecontroller.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.net.UdpClient

/**
 * Responsible for sending control payloads to the server.
 * 
 * This class handles:
 * - Parsing payload strings into individual commands
 * - Routing commands to the appropriate protocol (UDP/TCP)
 * - Adding HOLD/RELEASE suffixes for latched buttons
 * - Handling auto-release for non-latched buttons
 */
class PayloadSender(
    private val model: Control,
    private val isLatchedCallback: () -> Boolean
) {
    /**
     * Send the control's payload
     */
    fun firePayload() {
        Log.d("PayloadSender", "=== PAYLOAD DEBUG START ===")
        Log.d("PayloadSender", "Control ID: ${model.id}")
        Log.d("PayloadSender", "Control Type: ${model.type}")
        Log.d("PayloadSender", "Raw Payload: '${model.payload}'")
        Log.d("PayloadSender", "Is Latched: ${isLatchedCallback()}")
        Log.d("PayloadSender", "Connection Status: ${NetworkClient.connectionStatus.value}")
        
        // If the button is latched, send _HOLD version to prevent auto-release
        if (isLatchedCallback()) {
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
            
            Log.d("PayloadSender", "Latched Mode - Commands to send: $commands")
            commands.forEach {
                Log.d("PayloadSender", "Sending latched command: '$it'")
                sendCommand(it.trim())
            }
        } else {
            // Regular version with auto-release for normal presses
            val commands = model.payload.split(',', ' ').filter { it.isNotBlank() }
            Log.d("PayloadSender", "Normal Mode - Commands to send: $commands")
            commands.forEach {
                Log.d("PayloadSender", "Sending normal command: '$it'")
                sendCommand(it.trim())
            }
        }
        Log.d("PayloadSender", "=== PAYLOAD DEBUG END ===")
    }
    
    /**
     * Send a command using the appropriate protocol based on command type
     */
    private fun sendCommand(command: String) {
        Log.d("PayloadSender", "sendCommand() called with: '$command'")
        
        // Check if this is a keyboard key (not an Xbox button or special command)
        val isXboxCommand = command.startsWith("X360")
        val isMouseCommand = command.startsWith("MOUSE_")
        val isTouchpadCommand = command.startsWith("TOUCHPAD:")
        val isStickCommand = command.startsWith("STICK")
        val isTriggerCommand = command.startsWith("TRIGGER") || command.startsWith("LT:") || command.startsWith("RT:")
        
        Log.d("PayloadSender", "Command type analysis:")
        Log.d("PayloadSender", "  - isXboxCommand: $isXboxCommand")
        Log.d("PayloadSender", "  - isMouseCommand: $isMouseCommand")
        Log.d("PayloadSender", "  - isTouchpadCommand: $isTouchpadCommand")
        Log.d("PayloadSender", "  - isStickCommand: $isStickCommand")
        Log.d("PayloadSender", "  - isTriggerCommand: $isTriggerCommand")
        
        if (isXboxCommand) {
            Log.d("PayloadSender", "Treating as Xbox command, using UdpClient.sendCommand")
            // Xbox commands now use UdpClient for consistency with other working commands
            UdpClient.sendCommand(command)
        } else if (!isMouseCommand && !isTouchpadCommand && !isStickCommand && !isTriggerCommand) {
            Log.d("PayloadSender", "Treating as keyboard command, using UdpClient.sendKeyCommand")
            // This looks like a keyboard key - use the KEY_DOWN protocol
            UdpClient.sendKeyCommand(command, true)
            
            // For non-latched buttons, schedule release after a short delay
            if (!isLatchedCallback()) {
                val handler = Handler(Looper.getMainLooper())
                handler.postDelayed({
                    Log.d("PayloadSender", "Sending delayed key release for: '$command'")
                    UdpClient.sendKeyCommand(command, false)
                }, 100) // 100ms delay to match server auto-release
            }
        } else {
            Log.d("PayloadSender", "Treating as special command, using NetworkClient.send")
            // For mouse, touchpad, stick, and trigger commands, use NetworkClient
            NetworkClient.send(command)
        }
    }
    
    /**
     * Handle releasing held buttons
     */
    fun releaseLatched() {
        Log.d("PayloadSender", "releaseLatched() called for payload: '${model.payload}'")
        // Process all commands in the payload
        model.payload.split(',', ' ')
            .filter { it.isNotBlank() }
            .forEach { cmd ->
                Log.d("PayloadSender", "Processing release for command: '$cmd'")
                if (cmd.startsWith("X360")) {
                    // For Xbox buttons, send release commands via UdpClient
                    val releaseCmd = if (cmd.endsWith("_HOLD")) {
                        cmd.replace("_HOLD", "_RELEASE")
                    } else {
                        "${cmd}_RELEASE"
                    }
                    Log.d("PayloadSender", "Sending Xbox release command via UdpClient: '$releaseCmd'")
                    UdpClient.sendCommand(releaseCmd.trim())
                } else if (!cmd.startsWith("MOUSE_") &&
                    !cmd.startsWith("TOUCHPAD:") &&
                    !cmd.startsWith("STICK") &&
                    !cmd.startsWith("TRIGGER") &&
                    !cmd.startsWith("LT:") &&
                    !cmd.startsWith("RT:")) {
                    
                    // For keyboard keys, use KEY_UP protocol
                    Log.d("PayloadSender", "Sending keyboard release command: '$cmd'")
                    UdpClient.sendKeyCommand(cmd.trim(), false)
                }
            }
    }
}