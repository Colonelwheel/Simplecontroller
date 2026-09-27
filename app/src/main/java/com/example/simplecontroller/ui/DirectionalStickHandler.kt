package com.example.simplecontroller.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.net.UdpClient
import kotlin.math.abs

/**
 * Handles directional stick behavior for controls.
 * Manages directional command sending based on stick position.
 */
class DirectionalStickHandler(
    private val model: Control,
    private val uiHandler: Handler = Handler(Looper.getMainLooper()),
    payloadExecutor: ButtonAimPayloadExecutor
) {
    private val commandState = DirectionalCommandStateController(payloadExecutor) { reason ->
        Log.w("DirectionalStick", "Ignored invalid held payload: $reason")
    }
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

    // Use UDP for analog position updates (not directional commands)
    private var useUdp = true

    // Faster continuous sending interval
    private val continuousSendIntervalMs = 50L // 50ms ~20Hz (was 100ms)

    /**
     * Handle Stick+ mode - sends directional button commands alongside analog stick output
     * @param x Normalized X position (-1 to 1)
     * @param y Normalized Y position (-1 to 1)
     * @param action The motion event action (e.g., ACTION_UP, ACTION_MOVE)
     */
    fun handleStickPlusMode(x: Float, y: Float, action: Int) {
        // Store last position
        lastStickX = x
        lastStickY = y

        if (action == MotionEvent.ACTION_CANCEL) {
            commandState.clear()
            return
        }
        commandState.reconcile(desiredPayloads(x, y))
    }

    /**
     * Handle directional stick inputs, sending button commands instead of analog values
     * @param x Normalized X position (-1 to 1)
     * @param y Normalized Y position (-1 to 1)
     * @param action The motion event action (e.g., ACTION_UP, ACTION_MOVE)
     */
    fun handleDirectionalStick(
        x: Float,
        y: Float,
        action: Int,
        analogX: Float = x,
        analogY: Float = y
    ) {
        // Store last position
        lastStickX = x
        lastStickY = y

        // For move events with significant motion, also send the selected analog position via UDP
        // This helps with smoother transitions between directional zones
        if (action == MotionEvent.ACTION_MOVE && (abs(x) > 0.05f || abs(y) > 0.05f)) {
            // Only send this for actual STICK type controls, not buttons
            if (model.type == ControlType.STICK || model.type == ControlType.CURVED_STICK) {
                UdpClient.sendStickPosition(model.payload, analogX, analogY)
            }
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            commandState.clear()
            return
        }
        commandState.reconcile(desiredPayloads(x, y))
    }

    private fun desiredPayloads(x: Float, y: Float): Map<DirectionalCommandSlot, String> {
        val desired = linkedMapOf<DirectionalCommandSlot, String>()
        val absX = abs(x)
        val absY = abs(y)

        if (y < -DIRECTION_DEAD_ZONE) {
            desired[DirectionalCommandSlot.UP] = selectPayload(
                absY,
                model.upCommand,
                model.upBoostCommand,
                model.upSuperBoostCommand
            )
        } else if (y > DIRECTION_DEAD_ZONE) {
            desired[DirectionalCommandSlot.DOWN] = selectPayload(
                absY,
                model.downCommand,
                model.downBoostCommand,
                model.downSuperBoostCommand
            )
        }

        if (x < -DIRECTION_DEAD_ZONE) {
            desired[DirectionalCommandSlot.LEFT] = selectPayload(
                absX,
                model.leftCommand,
                model.leftBoostCommand,
                model.leftSuperBoostCommand
            )
        } else if (x > DIRECTION_DEAD_ZONE) {
            desired[DirectionalCommandSlot.RIGHT] = selectPayload(
                absX,
                model.rightCommand,
                model.rightBoostCommand,
                model.rightSuperBoostCommand
            )
        }

        return desired
    }

    private fun selectPayload(
        intensity: Float,
        normal: String,
        boost: String,
        superBoost: String
    ): String = when {
        intensity > model.superBoostThreshold -> superBoost
        intensity > model.boostThreshold -> boost
        else -> normal
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
                        val intensity = abs(lastStickY)
                        command.split(',', ' ')
                            .filter { it.isNotBlank() }
                            .forEach { cmd ->
                                val trimmedCmd = cmd.trim()
                                // Skip sending if the command is just a number (likely a mistake)
                                if (trimmedCmd.matches(Regex("^\\d+\\.?\\d*$"))) {
                                    return@forEach
                                }
                                
                                val finalCmd = if (trimmedCmd.matches(Regex("X360[LR]T|PS[LR]2|TRIGGER_.*"))) {
                                    "$trimmedCmd:${"%.2f".format(intensity)}"
                                } else {
                                    trimmedCmd
                                }
                                
                                if (useUdp) {
                                    UdpClient.sendCommand(finalCmd)
                                } else {
                                    UdpClient.sendCommand(finalCmd)
                                }
                            }
                    }

                    if (sendingDown) {
                        val command = if (sendingDownSuperBoost) model.downSuperBoostCommand
                        else if (sendingDownBoost) model.downBoostCommand
                        else model.downCommand
                        val intensity = abs(lastStickY)
                        command.split(',', ' ')
                            .filter { it.isNotBlank() }
                            .forEach { cmd ->
                                val trimmedCmd = cmd.trim()
                                // Skip sending if the command is just a number (likely a mistake)
                                if (trimmedCmd.matches(Regex("^\\d+\\.?\\d*$"))) {
                                    return@forEach
                                }
                                
                                val finalCmd = if (trimmedCmd.matches(Regex("X360[LR]T|PS[LR]2|TRIGGER_.*"))) {
                                    "$trimmedCmd:${"%.2f".format(intensity)}"
                                } else {
                                    trimmedCmd
                                }
                                
                                if (useUdp) {
                                    UdpClient.sendCommand(finalCmd)
                                } else {
                                    UdpClient.sendCommand(finalCmd)
                                }
                            }
                    }

                    if (sendingLeft) {
                        val command = if (sendingLeftSuperBoost) model.leftSuperBoostCommand
                        else if (sendingLeftBoost) model.leftBoostCommand
                        else model.leftCommand
                        val intensity = abs(lastStickX)
                        command.split(',', ' ')
                            .filter { it.isNotBlank() }
                            .forEach { cmd ->
                                val trimmedCmd = cmd.trim()
                                // Skip sending if the command is just a number (likely a mistake)
                                if (trimmedCmd.matches(Regex("^\\d+\\.?\\d*$"))) {
                                    return@forEach
                                }
                                
                                val finalCmd = if (trimmedCmd.matches(Regex("X360[LR]T|PS[LR]2|TRIGGER_.*"))) {
                                    "$trimmedCmd:${"%.2f".format(intensity)}"
                                } else {
                                    trimmedCmd
                                }
                                
                                if (useUdp) {
                                    UdpClient.sendCommand(finalCmd)
                                } else {
                                    UdpClient.sendCommand(finalCmd)
                                }
                            }
                    }

                    if (sendingRight) {
                        val command = if (sendingRightSuperBoost) model.rightSuperBoostCommand
                        else if (sendingRightBoost) model.rightBoostCommand
                        else model.rightCommand
                        val intensity = abs(lastStickX)
                        command.split(',', ' ')
                            .filter { it.isNotBlank() }
                            .forEach { cmd ->
                                val trimmedCmd = cmd.trim()
                                // Skip sending if the command is just a number (likely a mistake)
                                if (trimmedCmd.matches(Regex("^\\d+\\.?\\d*$"))) {
                                    return@forEach
                                }
                                
                                val finalCmd = if (trimmedCmd.matches(Regex("X360[LR]T|PS[LR]2|TRIGGER_.*"))) {
                                    "$trimmedCmd:${"%.2f".format(intensity)}"
                                } else {
                                    trimmedCmd
                                }
                                
                                if (useUdp) {
                                    UdpClient.sendCommand(finalCmd)
                                } else {
                                    UdpClient.sendCommand(finalCmd)
                                }
                            }
                    }

                    uiHandler.postDelayed(this, continuousSendIntervalMs) // Send at faster rate
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
        commandState.clear()
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

        // Only send stop command for actual stick controls
        if (model.type == ControlType.STICK || model.type == ControlType.CURVED_STICK) {
            // Also send a final zero position via UDP to ensure server knows we've stopped
            UdpClient.sendStickPosition(model.id, 0f, 0f)
        }
    }

    // Note: We're keeping this method even though it's unused
    // It may be useful in the future or for testing
    /**
     * Set whether to use UDP for commands
     */
    fun setUseUdp(enabled: Boolean) {
        useUdp = enabled
    }

    private companion object {
        const val DIRECTION_DEAD_ZONE = 0.1f
    }
}
