package com.example.simplecontroller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Style
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.example.simplecontroller.MainActivity
import com.example.simplecontroller.R
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.net.UdpClient
import kotlin.math.abs
import kotlin.math.min
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener


/**
 * ControlView is the primary UI component for interactive controls.
 *
 * This is a fully refactored version that delegates specialized behavior to
 * helper classes for better code organization and maintainability.
 *
 * Each ControlView represents a single interactive control (button, stick, or touchpad)
 * that the user can interact with in both edit and play modes.
 */
class ControlView(
    context: Context,
    val model: Control
) : FrameLayout(context) {

    /*hold*/
    private val holdHandler = Handler(Looper.getMainLooper())
    
    /*mouse click lock delay*/
    private val mouseClickHandler = Handler(Looper.getMainLooper())

    private var wasJustUnlatched = false


    /* ───────── member variables ────────── */
    // For dragging in edit mode
    private var dX = 0f
    private var dY = 0f

    // State tracking
    var isLatched = false
        set(value) {
            val oldValue = field
            field = value

            // If we're turning off latched mode, release any held buttons
            if (oldValue && !value) {
                Log.d("DEBUG_PULSE", "isLatched setter: unlatching and calling releaseLatched()")
                uiHelper.releaseLatched()
                // Double-check that pulse state is reset
                uiHelper.allowPulseLoop = false
            }
        }
    private var leftHeld = false           // for one-finger drag or toggle

    // For touchpad tracking - improved touchpad handling
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchPreviousDx = 0f  // Store previous movement for smoothing
    private var touchPreviousDy = 0f  // Store previous movement for smoothing
    private var touchInitialized = false
    private var touchpadSensitivity = 1.0f

    // Touchpad smoothing factors
    private val touchSmoothingFactor = 0.5f  // 0.0 = no smoothing, 1.0 = maximum smoothing
    private val touchMinMovement = 0.05f     // Minimum movement threshold
    private val touchDeadzone = 0.02f        // Ignore tiny movements below this value

    // Timestamp for rate limiting
    private var lastTouchpadSendTime = 0L
    private val touchpadSendIntervalMs = 8   // Limit send rate to reduce network spam

    // Handler for UI updates
    private val uiHandler = Handler(Looper.getMainLooper())

    // Specialized handlers
    private val uiHelper: ControlViewHelper
    private val directionalHandler: DirectionalStickHandler
    private val continuousSender: ContinuousSender

    // Paint for drawing the control
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    // System vibrator for stronger haptic feedback
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    /**
     * Trigger strong vibration using system Vibrator service
     */
    private fun triggerStrongVibration(durationMs: Long = 50) {
        try {
            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                    vibrator.vibrate(effect)
                    Log.d("HAPTIC_DEBUG", "Strong vibration triggered (API >= 26): ${durationMs}ms")
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                    Log.d("HAPTIC_DEBUG", "Strong vibration triggered (API < 26): ${durationMs}ms")
                }
            } else {
                Log.d("HAPTIC_DEBUG", "Device has no vibrator")
            }
        } catch (e: Exception) {
            Log.e("HAPTIC_DEBUG", "Vibration failed: ${e.message}")
        }
    }

    /* ───────── init ───────────── */
    init {
        // Configure layout params
        layoutParams = MarginLayoutParams(model.w.toInt(), model.h.toInt()).apply {
            leftMargin = model.x.toInt()
            topMargin = model.y.toInt()
        }

        // Configure view properties
        setWillNotDraw(false)
        isClickable = true

        // Create helper instances
        uiHelper = ControlViewHelper(context, model, this) {
            (context as? MainActivity)?.removeControl(model)
        }

        directionalHandler = DirectionalStickHandler(model, uiHandler)
        continuousSender = ContinuousSender(model, uiHandler)

        // Register with SwipeManager
        SwipeManager.registerControl(this)

        // Initial UI updates
        uiHelper.updateLabel()
    }

    // Clean up when the view is removed
    override fun onDetachedFromWindow() {
        // Release mouse button if needed for touchpad
        if (model.type == ControlType.TOUCHPAD && leftHeld) {
            UdpClient.sendCommand("MOUSE_LEFT_UP")
            leftHeld = false
        }

        // Stop any ongoing actions
        stopRepeat()
        stopContinuousSending()
        stopDirectionalCommands()
        
        // Cancel any pending mouse click handlers
        mouseClickHandler.removeCallbacksAndMessages(null)

        // Unregister from SwipeManager
        SwipeManager.unregisterControl(this)

        super.onDetachedFromWindow()
    }

    /* ───────── drawing ────────── */
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        when (model.type) {
            ControlType.BUTTON -> {
                val cornerRadius = min(width, height) / 3f
                
                if (isLatched || isPressed) {
                    // Solid blue when pressed or latched
                    paint.style = Style.FILL
                    paint.color = ContextCompat.getColor(context, R.color.button_pressed_blue)
                    c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), cornerRadius, cornerRadius, paint)
                } else {
                    // Transparent with blue outline when not pressed
                    paint.style = Style.STROKE
                    paint.strokeWidth = 4f
                    paint.color = ContextCompat.getColor(context, R.color.button_blue)
                    c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), cornerRadius, cornerRadius, paint)
                }
            }
            ControlType.RECENTER -> {
                val radius = min(width, height) / 2f
                
                if (isLatched || isPressed) {
                    // Solid orange when pressed or latched
                    paint.style = Style.FILL
                    paint.color = ContextCompat.getColor(context, R.color.recenter_pressed_orange)
                    c.drawCircle(width / 2f, height / 2f, radius, paint)
                } else {
                    // Transparent with orange outline when not pressed
                    paint.style = Style.STROKE
                    paint.strokeWidth = 4f
                    paint.color = ContextCompat.getColor(context, R.color.recenter_orange)
                    c.drawCircle(width / 2f, height / 2f, radius, paint)
                }
            }
            ControlType.STICK -> {
                // Background of the stick area with theme colors
                paint.style = Style.FILL
                paint.color = ContextCompat.getColor(context, R.color.touchpad_blue)
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

                // The stick itself with theme colors
                paint.color = ContextCompat.getColor(context, R.color.stick_blue)
                c.drawCircle(width/2f, height/2f, min(width, height)/6f, paint)
            }
            ControlType.TOUCHPAD -> {
                // Semi-transparent touchpad area with theme colors
                paint.style = Style.FILL
                paint.color = ContextCompat.getColor(context, R.color.touchpad_blue)
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        }
    }

    private val gestureDetector = GestureDetector(context, object : SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (GlobalSettings.editMode) {
                showProps()
            }
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (GlobalSettings.editMode) {
            gestureDetector.onTouchEvent(e)  // ✅ long-press to show props
            return editDrag(e)               // ✅ still allows drag
        }

        // Normal game mode
        if (GlobalSettings.globalSwipe) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                // Avoid rapid repeat unless turbo is on — handled inside playTouch now
                playTouch(e)
                return true
            }
            return false
        } else {
            playTouch(e)
            return true
        }
    }

    /* ---------- edit mode: reposition controls ---------- */
    private fun editDrag(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dX = e.rawX - (layoutParams as MarginLayoutParams).leftMargin
                dY = e.rawY - (layoutParams as MarginLayoutParams).topMargin
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val lp = layoutParams as MarginLayoutParams
                lp.leftMargin = (e.rawX - dX).toInt()
                lp.topMargin = (e.rawY - dY).toInt()
                layoutParams = lp
                model.x = lp.leftMargin.toFloat()
                model.y = lp.topMargin.toFloat()
                return true
            }
            else -> return false
        }
    }

    /* ---------- play mode: interact ---------- */
    fun playTouch(e: MotionEvent) {
        when (model.type) {
            /* ----- BUTTON ----- */
            ControlType.BUTTON -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Trigger strong vibration for button press
                    triggerStrongVibration(30) // Short 30ms vibration

                    wasJustUnlatched = false
                    uiHelper.allowPulseLoop = true  // ✅ enable pulsing for this press
                    // Cancel any pending long press action from before
                    holdHandler.removeCallbacksAndMessages(null)

                    // DEBUG ↓ — reflect whether we fire now or skip due to hold toggle
                    Log.d("DEBUG_BTN", "DOWN  ${if (model.holdToggle) "skipping immediate fire" else "firing"} ${model.payload}")

                    // If the button is already latched, a tap should unlatch it immediately
                    if (isLatched) {
                        isLatched = false
                        isPressed = false
                        wasJustUnlatched = true  // ✅ track that we just unlatched
                        uiHelper.releaseLatched()
                        invalidate()
                        return
                    }

                    // Normal press path (not latched)
                    isPressed = true  // ✅ This tracks "I'm still pressing"
                    invalidate() // ✅ Trigger redraw immediately for visual feedback

                    if (GlobalSettings.globalTurbo) {
                        uiHelper.startRepeat()
                    } else {
                        // Handle global hold mode (instant latch) for non-toggle buttons
                        if (GlobalSettings.globalHold && !model.holdToggle) {
                            isLatched = true
                            invalidate()
                        }

                        // ✅ Non-hold buttons: fire immediately (single tap)
                        if (!model.holdToggle && !isLatched) {
                            uiHelper.firePayload()
                        }

                        // ✅ Hold-toggle buttons: DO NOT fire immediately; fire only when hold threshold is reached
                        if (model.holdToggle) {
                            holdHandler.postDelayed({
                                if (isPressed && !wasJustUnlatched) {
                                    isLatched = true
                                    // Trigger longer vibration for button latch
                                    triggerStrongVibration(80) // Longer 80ms vibration for latch
                                    invalidate()
                                    uiHelper.firePayload() // Fire once after latching (sends *_HOLD behavior while latched)
                                }
                            }, model.holdDurationMs)
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopRepeat()
                    isPressed = false  // ✅ Cancel any pending delayed latch
                    invalidate() // ✅ Trigger redraw to remove pressed visual state

                    // ✅ If hold-toggle is on and we never latched, cancel silently (no release)
                    if (model.holdToggle && !isLatched) {
                        holdHandler.removeCallbacksAndMessages(null)
                        // DEBUG ↓ — early release before latch; nothing sent
                        Log.d("DEBUG_BTN", "UP    (early release, holdToggle) ${model.payload}")
                        return
                    }

                    if (!isLatched &&
                        !model.holdToggle && // ✅ don't release for hold-toggle quick taps
                        !model.payload.contains("RT:1.0P", ignoreCase = true) &&
                        !model.payload.contains("LT:1.0P", ignoreCase = true)) {
                        uiHelper.releaseLatched()  // ✅ Only release if NOT latched, NOT hold-toggle, and not pulse-based
                    }

                    // DEBUG ↓  — fires when you lift your finger
                    Log.d("DEBUG_BTN", "UP    firing ${model.payload}")

                    // Handle latch toggling on short tap for unlatched buttons only
                    if (!isLatched && model.holdToggle) {
                        // Cancel pending hold toggle if finger is lifted before holdDurationMs
                        holdHandler.removeCallbacksAndMessages(null)
                    }
                }
            }

            /* ----- RE-CENTER BUTTON ----- */
            ControlType.RECENTER -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Trigger vibration for recenter button
                    triggerStrongVibration(40) // Medium vibration for recenter
                    
                    // Visual feedback
                    isLatched = true
                    isPressed = true
                    invalidate()

                    // Trigger re-centering of all sticks
                    SwipeManager.recenterAllSticks()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Visual feedback
                    isLatched = false
                    isPressed = false
                    invalidate()
                }
            }

            /* ----- STICK ----- */
            ControlType.STICK -> {
                // Stop continuous sending when touching the stick again
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    stopContinuousSending()
                    stopDirectionalCommands()
                }
                handleStickOrPad(e)
            }

            /* ----- TOUCHPAD (with one-finger drag) ----- */
            ControlType.TOUCHPAD -> {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // Stop continuous sending when touching the pad again
                        stopContinuousSending()

                        // Initialize touchpad tracking
                        lastTouchX = e.x
                        lastTouchY = e.y
                        touchPreviousDx = 0f
                        touchPreviousDy = 0f
                        touchInitialized = true // Set to true immediately - don't skip first move

                        // Don't use MOUSE_RESET as it causes position jumps
                        // NetworkClient.send("MOUSE_RESET")

                        // Handle mouse button states
                        if (model.toggleLeftClick) {
                            // Toggle mode - flip the leftHeld state
                            leftHeld = !leftHeld

                            // Send the appropriate mouse command based on new state
                            if (leftHeld) {
                                // Add 750ms delay before activating mouse down in click lock mode
                                mouseClickHandler.postDelayed({
                                    if (leftHeld) { // Check if still in locked state
                                        UdpClient.sendCommand("MOUSE_LEFT_DOWN")
                                        // Trigger vibration when mouse actually activates
                                        triggerStrongVibration(25) // Short vibration for mouse click
                                        Log.d("HAPTIC_DEBUG", "Mouse click lock activated after delay")
                                    }
                                }, 750) // 750ms delay
                            } else {
                                // Cancel any pending mouse down activation
                                mouseClickHandler.removeCallbacksAndMessages(null)
                                UdpClient.sendCommand("MOUSE_LEFT_UP")
                            }
                        } else if (model.holdLeftWhileTouch) {
                            // Standard hold mode
                            UdpClient.sendCommand("MOUSE_LEFT_DOWN")
                            leftHeld = true
                            // Trigger vibration for mouse click activation
                            triggerStrongVibration(25) // Short vibration for mouse click
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Calculate raw movement delta
                        val dx = (e.x - lastTouchX) * model.sensitivity
                        val dy = (e.y - lastTouchY) * model.sensitivity

                        // Update for next calculation
                        lastTouchX = e.x
                        lastTouchY = e.y

                        // Skip if movement is within the dead-zone
                        if (abs(dx) < touchDeadzone && abs(dy) < touchDeadzone) return

                        // ───── smoothing ───────────────────────────────
                        val smoothedDx = dx * (1 - touchSmoothingFactor) +
                                touchPreviousDx * touchSmoothingFactor
                        val smoothedDy = dy * (1 - touchSmoothingFactor) +
                                touchPreviousDy * touchSmoothingFactor
                        touchPreviousDx = smoothedDx
                        touchPreviousDy = smoothedDy

                        // ───── non-linear scaling ──────────────────────
                        val scaledDx = applyTouchpadScaling(smoothedDx)
                        val scaledDy = applyTouchpadScaling(smoothedDy)

                        // ───── rate-limit traffic ──────────────────────
                        val now = System.currentTimeMillis()
                        if (now - lastTouchpadSendTime < touchpadSendIntervalMs) return
                        lastTouchpadSendTime = now

                        // ───── send to server ──────────────────────────
                        if (GlobalSettings.scrollMode) {
                            // Scroll mode ON → use vertical delta only
                            // (flip sign here if you want “natural” scrolling)
                            UdpClient.sendScroll(-scaledDy)
                        } else {
                            // Normal touch-pad cursor movement
                            UdpClient.sendTouchpadDelta(scaledDx, scaledDy)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // Reset tracking
                        touchPreviousDx = 0f
                        touchPreviousDy = 0f

                        // Send a final zero movement to ensure mouse stops
                        UdpClient.sendTouchpadPosition(0f, 0f)

                        // Only release mouse button on up/cancel if using standard hold mode
                        if (!model.toggleLeftClick &&
                            leftHeld && model.holdLeftWhileTouch) {
                            UdpClient.sendCommand("MOUSE_LEFT_UP")
                            leftHeld = false
                        }
                    }
                }
            }
        }
    }

    /**
     * Apply non-linear scaling to touchpad movement for better control
     * - Boost small movements for precision
     * - Cap large movements to prevent jerky motion
     */
    private fun applyTouchpadScaling(value: Float): Float {
        val absValue = abs(value)
        val sign = if (value >= 0) 1f else -1f

        return when {
            // Tiny movements get precision boost
            absValue < 0.2f -> sign * (absValue * 1.5f)
            // Medium movements pass through mostly unchanged
            absValue < 0.6f -> sign * absValue
            // Large movements get slightly reduced
            else -> sign * (0.6f + (absValue - 0.6f) * 0.8f)
        }
    }

    /**
     * Handle stick or touchpad movement
     */
    private fun handleStickOrPad(e: MotionEvent) {
        if (e.actionMasked !in listOf(
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL)) return

        val cx = width/2f
        val cy = height/2f
        val nx = ((e.x - cx) / (width/2f)).coerceIn(-1f, 1f) * model.sensitivity
        val ny = ((e.y - cy) / (height/2f)).coerceIn(-1f, 1f) * model.sensitivity

        // Only snap if snapEnabled is true AND the control's autoCenter is true AND it's an UP/CANCEL event
        val isLift = e.actionMasked == MotionEvent.ACTION_UP ||
                e.actionMasked == MotionEvent.ACTION_CANCEL ||
                e.actionMasked == MotionEvent.ACTION_POINTER_UP    // ← new
        val shouldSnap = model.autoCenter && isLift                    // ← no global flag

        val (sx, sy) = if (shouldSnap) 0f to 0f else nx to ny

        // Store last position for continuous sending if needed
        continuousSender.setLastPosition(sx, sy)

        // Handle different stick modes
        if (model.type == ControlType.STICK) {
            when {
                model.directionalMode -> {
                    // Pure directional mode - only send button commands
                    directionalHandler.handleDirectionalStick(sx, sy, e.actionMasked)
                }
                model.stickPlusMode -> {
                    // Stick+ mode - send analog via UDP helper (correct tag + player prefix),
                    // then layer the directional keys on top
                    UdpClient.sendStickPosition(model.payload, sx, sy)
                    directionalHandler.handleStickPlusMode(sx, sy, e.actionMasked)
                }
                else -> {
                    // Regular analog stick/pad mode (use UDP stick helper so tags/prefixes are correct)
                    UdpClient.sendStickPosition(model.payload, sx, sy)
                }
            }
        } else {
            // Touchpad mode
            NetworkClient.send("${model.payload}:${"%.2f".format(sx)},${"%.2f".format(sy)}")
        }

        // If auto-center triggered, ensure continuous sending is stopped and send final 0,0
        if (shouldSnap) {
            stopContinuousSending()
            // Ensure a final 0,0 position is sent for auto-center sticks
            if (model.type == ControlType.STICK) {
                UdpClient.sendStickPosition(model.payload, 0f, 0f)
            }
        }

        // If this is an UP or CANCEL event and we shouldn't snap,
        // start continuous sending of the last position ONLY FOR STICKS in ANALOG mode
        if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) &&
            !shouldSnap &&
            model.type == ControlType.STICK &&  // Only for sticks, not touchpads
            !model.directionalMode &&  // Only for analog sticks, not directional ones
            !model.autoCenter) {

            // If stick position is near center, don't bother with continuous sending
            if (abs(sx) < 0.1f && abs(sy) < 0.1f) {
                stopContinuousSending()
                return
            }

            startContinuousSending()
        }
    }

    /* ───────── public methods for state control ────────── */

    /**
     * Start continuous sending of the current position
     */
    fun startContinuousSending() {
        continuousSender.startContinuousSending()
    }

    /**
     * Stop continuous sending
     */
    fun stopContinuousSending() {
        continuousSender.stopContinuousSending()
    }

    /**
     * Stop directional commands
     */
    fun stopDirectionalCommands() {
        directionalHandler.stopDirectionalCommands()
    }

    /**
     * Stop repeating (for turbo mode)
     */
    fun stopRepeat() {
        uiHelper.stopRepeat()
    }

    /**
     * Show properties dialog for this control
     * This method is needed by LayoutManager
     */
    fun showProps() {
        PropertySheetBuilder(context, model) {
            // After properties are updated:
            val lp = layoutParams as ViewGroup.MarginLayoutParams
            lp.width = model.w.toInt()
            lp.height = model.h.toInt()
            layoutParams = lp
            uiHelper.updateLabel()
            invalidate()

            // Stop any running senders when settings change
            stopContinuousSending()
            stopDirectionalCommands()
        }.showPropertySheet()
    }
}