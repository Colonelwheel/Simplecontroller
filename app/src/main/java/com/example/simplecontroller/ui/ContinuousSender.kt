package com.example.simplecontroller.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.net.UdpClient
import kotlin.math.abs

/**
 * Handles continuous sending of control positions.
 * Used primarily for analog stick positions that need to be maintained even after
 * the user lifts their finger.
 */
class ContinuousSender(
    private val model: Control,
    private val uiHandler: Handler = Handler(Looper.getMainLooper())
) {
    private var continuousSender: Runnable? = null

    // Store last position values
    private var lastStickX = 0f
    private var lastStickY = 0f

    // Throttle sending frequency
    private val sendIntervalMs = 10L  // ~100 FPS (down from 16ms)
    private var lastSendTimeMs = 0L

    // Use UDP by default for faster transmission
    private val useUdp = true

    /**
     * Set the last position to be continuously sent
     */
    fun setLastPosition(x: Float, y: Float) {
        // Only update position if significantly different (reduces jitter)
        if (abs(lastStickX - x) > 0.01f || abs(lastStickY - y) > 0.01f) {
            lastStickX = x
            lastStickY = y

            // Direct send with throttling for immediate response
            val currentTimeMs = System.currentTimeMillis()
            if (currentTimeMs - lastSendTimeMs >= sendIntervalMs) {
                // Apply response curve for more precise control
                val curvedX = applyResponseCurve(x)
                val curvedY = applyResponseCurve(y)

                if (useUdp) {
                    if (model.type == ControlType.TOUCHPAD) {
                        // For touchpads, use the touchpad-specific sender
                        UdpClient.sendTouchpadPosition(curvedX, curvedY)
                    } else {
                        // For sticks, use the stick position sender with the model's payload
                        UdpClient.sendStickPosition(model.payload, curvedX, curvedY)
                    }
                } else {
                    // Fallback to TCP - format remains the same for both types
                    NetworkClient.send("${model.payload}:${"%.2f".format(curvedX)},${"%.2f".format(curvedY)}")
                }
                lastSendTimeMs = currentTimeMs
            }
        }
    }

    /*
   * Patch for ContinuousSender.kt – keeps stick value streaming when Auto‑center is OFF,
   * preserves old behaviour when Auto‑center is ON.
   */

    /** Start continuously sending the last stick position */
    fun startContinuousSending() {
        Log.d("DEBUG_CS", "autoCenter=${model.autoCenter}")

        // just cancel any earlier runnable – *don’t* zero the coords
        continuousSender?.let { uiHandler.removeCallbacks(it) }

        // Bail out early only when the stick is *meant* to snap back and is already near centre
        if (model.autoCenter &&
            abs(lastStickX) < 0.15f && abs(lastStickY) < 0.15f) {
            sendCenter()
            return
        }

        continuousSender = object : Runnable {
            private var decay = 1f                 // stays 1 → no fade when autoCenter == false
            override fun run() {
                val curX = if (model.autoCenter) lastStickX * decay else lastStickX
                val curY = if (model.autoCenter) lastStickY * decay else lastStickY

                val minMag = if (model.autoCenter) 0.15f else 0.01f
                if (abs(curX) > minMag || abs(curY) > minMag) {
                    val sendX = applyResponseCurve(curX)
                    val sendY = applyResponseCurve(curY)
                    UdpClient.sendStickPosition(model.payload, sendX, sendY)

                    if (model.autoCenter) decay *= 0.95f      // glide only in snap‑back mode
                    uiHandler.postDelayed(this, sendIntervalMs)
                } else if (model.autoCenter) {
                    // Only snap‑back sticks send a final 0,0 frame
                    sendCenter()
                }
            }
        }
        uiHandler.post(continuousSender!!)
    }

    /* helper: single 0,0 packet + stop */
    private fun sendCenter() {
        UdpClient.sendStickPosition(model.payload, 0f, 0f)
        continuousSender = null
    }

    /** Stop continuous sending */
    fun stopContinuousSending() {
        continuousSender?.let { uiHandler.removeCallbacks(it) }
        continuousSender = null

        // Only force‑centre sticks that are *supposed* to auto‑centre
        if (model.autoCenter) {
            sendCenter()   // unconditional – a harmless duplicate ‘0,0’ is fine
        }
        // Note: we no longer reset lastStickX/Y here – they’re kept for restart.
    }


    /**
     * Is continuous sending currently active?
     */
    fun isActive(): Boolean {
        return continuousSender != null
    }

    /**
     * Apply non-linear response curve for more precise control
     * This gives finer control near center, more speed at edges
     */
    private fun applyResponseCurve(value: Float): Float {
        // Square response curve with sign preservation
        return value * abs(value)
    }
}