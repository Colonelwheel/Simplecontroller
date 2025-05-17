package com.example.simplecontroller.ui

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.net.UdpClient
import kotlin.math.abs

/**
 * Handles directional stick behavior for controls.
 * Manages directional command sending based on stick position.
 */
class DirectionalStickHandler(
    private val model: Control,
    private val uiHandler: Handler = Handler(Looper.getMainLooper())
) {
    // Track which directional commands are being continuously sent
    private var continuousDirectional: Runnable? = null
    private var sendingUp = false
    private var sendingDown = false
    private var sendingLeft = false
    private var sendingRight = false
    private var sendingUpBoost = false
    private var sendingDownBoost = false
    private var sendingLeftBoost = false
    private var sendingRightBoost = false
    private var sendingUpSuperBoost = false
    private var sendingDownSuperBoost = false
    private var sendingLeftSuperBoost = false
    private var sendingRightSuperBoost = false

    // Store the last stick position for continuous sending
    private var lastStickX = 0f
    private var lastStickY = 0f
    
    // Store last sent raw position to avoid redundant sends
    private var lastSentRawX = 0f
    private var lastSentRawY = 0f
    private val positionThreshold = 0.03f  // Minimum movement threshold
    
    // Throttle raw position updates
    private var lastRawUpdateTimeMs = 0L
    private val rawUpdateThrottleMs = 12L // Throttle raw position updates

    // Use UDP for analog position updates (not directional commands)
    private var useUdp = true

    // Optimized continuous sending interval
    private val continuousSendIntervalMs = 45L // 45ms ~22Hz (reduced from 50ms for better responsiveness)

    /**
     * Handle directional stick inputs, sending button commands instead of analog values
     * Optimized for reduced network traffic and better responsiveness
     * @param x Normalized X position (-1 to 1)
     * @param y Normalized Y position (-1 to 1)
     * @param action The motion event action (e.g., ACTION_UP, ACTION_MOVE)
     */
    fun handleDirectionalStick(x: Float, y: Float, action: Int) {
        // Store last position
        lastStickX = x
        lastStickY = y

        // For move events with significant motion, also send raw position via UDP
        // But throttle updates to reduce network traffic
        if (action == MotionEvent.ACTION_MOVE && (abs(x) > 0.05f || abs(y) > 0.05f)) {
            val currentTimeMs = System.currentTimeMillis()
            
            // Only send if position changed significantly or enough time passed
            if ((abs(x - lastSentRawX) > positionThreshold || abs(y - lastSentRawY) > positionThreshold) &&
                currentTimeMs - lastRawUpdateTimeMs >= rawUpdateThrottleMs) {
                
                lastSentRawX = x
                lastSentRawY = y
                lastRawUpdateTimeMs = currentTimeMs
                
                UdpClient.sendStickPosition("DIR_${model.id}", x, y)
            }
        }

        // Track whether we've sent commands for each direction this frame
        var sentUp = false
        var sentDown = false
        var sentLeft = false
        var sentRight = false

        // Determine the main direction(s) to send
        val absX = abs(x)
        val absY = abs(y)

        // Check if we're close enough to center to not send any commands
        if (absX < 0.1f && absY < 0.1f) {
            // Near center - stop all continuous commands if this is a MOVE event
            if (action == MotionEvent.ACTION_MOVE) {
                stopDirectionalCommands()
            }
            return
        }

        // Helper function to send a command and update tracking
        fun sendCommand(command: String, update: () -> Unit) {
            command.split(',', ' ')
                .filter { it.isNotBlank() }
                .forEach {
                    if (useUdp) {
                        // Try UDP first for lower latency
                        try {
                            UdpClient.sendCommand(it.trim())
                        } catch (e: Exception) {
                            // Fall back to TCP if UDP fails
                            NetworkClient.send(it.trim())
                        }
                    } else {
                        NetworkClient.send(it.trim())
                    }
                }
            update()
        }

        // Send commands based on direction and intensity
        if (y < -0.1f) { // Up direction
            if (absY > model.superBoostThreshold) {
                sendCommand(model.upSuperBoostCommand) { sentUp = true }
            } else if (absY > model.boostThreshold) {
                sendCommand(model.upBoostCommand) { sentUp = true }
            } else {
                sendCommand(model.upCommand) { sentUp = true }
            }
        }

        if (y > 0.1f) { // Down direction
            if (absY > model.superBoostThreshold) {
                sendCommand(model.downSuperBoostCommand) { sentDown = true }
            } else if (absY > model.boostThreshold) {
                sendCommand(model.downBoostCommand) { sentDown = true }
            } else {
                sendCommand(model.downCommand) { sentDown = true }
            }
        }

        if (x < -0.1f) { // Left direction
            if (absX > model.superBoostThreshold) {
                sendCommand(model.leftSuperBoostCommand) { sentLeft = true }
            } else if (absX > model.boostThreshold) {
                sendCommand(model.leftBoostCommand) { sentLeft = true }
            } else {
                sendCommand(model.leftCommand) { sentLeft = true }
            }
        }

        if (x > 0.1f) { // Right direction
            if (absX > model.superBoostThreshold) {
                sendCommand(model.rightSuperBoostCommand) { sentRight = true }
            } else if (absX > model.boostThreshold) {
                sendCommand(model.rightBoostCommand) { sentRight = true }
            } else {
                sendCommand(model.rightCommand) { sentRight = true }
            }
        }

        // Store the directions we sent commands for so we can start continuous sending
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            // On release, start continuous sending for the last direction
            startDirectionalSending(sentUp, sentDown, sentLeft, sentRight)
        }
    }

    /**
     * Start continuous sending of directional commands
     */
    private fun startDirectionalSending(up: Boolean, down: Boolean, left: Boolean, right: Boolean) {
        // Stop any existing continuous sender
        stopDirectionalCommands()

        // Store the directions we're sending
        sendingUp = up
        sendingDown = down
        sendingLeft = left
        sendingRight = right

        // Determine if we're using boost commands
        val absX = abs(lastStickX)
        val absY = abs(lastStickY)

        // Normal boost level
        sendingUpBoost = up && absY > model.boostThreshold && absY <= model.superBoostThreshold
        sendingDownBoost = down && absY > model.boostThreshold && absY <= model.superBoostThreshold
        sendingLeftBoost = left && absX > model.boostThreshold && absX <= model.superBoostThreshold
        sendingRightBoost = right && absX > model.boostThreshold && absX <= model.superBoostThreshold

        // Super boost level
        sendingUpSuperBoost = up && absY > model.superBoostThreshold
        sendingDownSuperBoost = down && absY > model.superBoostThreshold
        sendingLeftSuperBoost = left && absX > model.superBoostThreshold
        sendingRightSuperBoost = right && absX > model.superBoostThreshold

        if (!model.autoCenter && (up || down || left || right)) {
            // Create a continuous sender for directional commands
            continuousDirectional = object : Runnable {
                override fun run() {
                    if (sendingUp) {
                        val command = if (sendingUpSuperBoost) model.upSuperBoostCommand
                        else if (sendingUpBoost) model.upBoostCommand
                        else model.upCommand
                        command.split(',', ' ')
                            .filter { it.isNotBlank() }
                            .forEach {
                                if (useUdp) {
                                    UdpClient.sendCommand(it.trim())
                                } else {
                                    NetworkClient.send(it.trim())
                                }
                            }
                    }

                    if (sendingDown) {
                        val command = if (sendingDownSuperBoost) model.downSuperBoostCommand
                        else if (sendingDownBoost) model.downBoostCommand
                        else model.downCommand
                        command.split(',', ' ')
                            .filter { it.isNotBlank() }
                            .forEach {
                                if (useUdp) {
                                    UdpClient.sendCommand(it.trim())
                                } else {
                                    NetworkClient.send(it.trim())
                                }
                            }
                    }

                    if (sendingLeft) {
                        val command = if (sendingLeftSuperBoost) model.leftSuperBoostCommand
                        else if (sendingLeftBoost) model.leftBoostCommand
                        else model.leftCommand
                        command.split(',', ' ')
                            .filter { it.isNotBlank() }
                            .forEach {
                                if (useUdp) {
                                    UdpClient.sendCommand(it.trim())
                                } else {
                                    NetworkClient.send(it.trim())
                                }
                            }
                    }

                    if (sendingRight) {
                        val command = if (sendingRightSuperBoost) model.rightSuperBoostCommand
                        else if (sendingRightBoost) model.rightBoostCommand
                        else model.rightCommand
                        command.split(',', ' ')
                            .filter { it.isNotBlank() }
                            .forEach {
                                if (useUdp) {
                                    UdpClient.sendCommand(it.trim())
                                } else {
                                    NetworkClient.send(it.trim())
                                }
                            }
                    }

                    // Use adaptive timing - if moving diagonally, slow down sending to avoid network congestion
                    val sendDelay = if (sendingUp && sendingRight || sendingUp && sendingLeft || 
                                      sendingDown && sendingRight || sendingDown && sendingLeft) {
                        continuousSendIntervalMs + 5L
                    } else {
                        continuousSendIntervalMs
                    }
                    uiHandler.postDelayed(this, sendDelay)
                }
            }

            // Start continuous sending
            uiHandler.postDelayed(continuousDirectional!!, continuousSendIntervalMs)
        }
    }

    /**
     * Stop any continuous directional commands being sent
     */
    fun stopDirectionalCommands() {
        continuousDirectional?.let { uiHandler.removeCallbacks(it) }
        continuousDirectional = null
        sendingUp = false
        sendingDown = false
        sendingLeft = false
        sendingRight = false
        sendingUpBoost = false
        sendingDownBoost = false
        sendingLeftBoost = false
        sendingRightBoost = false
        sendingUpSuperBoost = false
        sendingDownSuperBoost = false
        sendingLeftSuperBoost = false
        sendingRightSuperBoost = false

        // Also send a final zero position via UDP to ensure server knows we've stopped
        UdpClient.sendStickPosition("DIR_${model.id}", 0f, 0f)
    }

    /**
     * Set whether to use UDP for commands (default is true)
     */
    fun setUseUdp(enabled: Boolean) {
        useUdp = enabled
    }
}