package com.example.simplecontroller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.example.simplecontroller.MainActivity
import com.example.simplecontroller.R
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.net.NetworkClient
import kotlin.math.abs
import kotlin.math.min

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
                uiHelper.releaseLatched()
            }
        }
    private var leftHeld = false           // for one-finger drag or toggle

    // For touchpad relative movement tracking
    private var touchTrackingInitialized = false
    private var prevTouchX = 0f
    private var prevTouchY = 0f
    private var touchpadSensitivity = 5f   // Scale factor for touchpad movement

    // Handler for UI updates
    private val uiHandler = Handler(Looper.getMainLooper())

    // Specialized handlers
    private val uiHelper: ControlViewHelper
    private val directionalHandler: DirectionalStickHandler
    private val continuousSender: ContinuousSender

    // Paint for drawing the control
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

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
        updateOverlay()
        uiHelper.updateLabel()
    }

    // Clean up when the view is removed
    override fun onDetachedFromWindow() {
        // Release mouse button if needed for touchpad
        if (model.type == ControlType.TOUCHPAD && leftHeld) {
            NetworkClient.send("MOUSE_LEFT_UP")
            leftHeld = false
        }

        // Stop any ongoing actions
        stopRepeat()
        stopContinuousSending()
        stopDirectionalCommands()

        // Unregister from SwipeManager
        SwipeManager.unregisterControl(this)

        super.onDetachedFromWindow()
    }

    /* ───────── drawing ────────── */
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        when (model.type) {
            ControlType.BUTTON -> {
                // Use a brighter color if the button is latched (held) with theme colors
                paint.color = if (isLatched)
                    ContextCompat.getColor(context, R.color.button_pressed_blue)
                else
                    ContextCompat.getColor(context, R.color.button_blue)
                c.drawCircle(width / 2f, height / 2f, min(width, height) / 2f, paint)
            }
            ControlType.RECENTER -> {
                // Re-center button uses a distinctive orange color with theme colors
                paint.color = if (isLatched)
                    ContextCompat.getColor(context, R.color.recenter_pressed_orange)
                else
                    ContextCompat.getColor(context, R.color.recenter_orange)
                c.drawCircle(width / 2f, height / 2f, min(width, height) / 2f, paint)
            }
            ControlType.STICK -> {
                // Background of the stick area with theme colors
                paint.color = ContextCompat.getColor(context, R.color.touchpad_blue)
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

                // The stick itself with theme colors
                paint.color = ContextCompat.getColor(context, R.color.stick_blue)
                c.drawCircle(width/2f, height/2f, min(width, height)/6f, paint)
            }
            ControlType.TOUCHPAD -> {
                // Semi-transparent touchpad area with theme colors
                paint.color = ContextCompat.getColor(context, R.color.touchpad_blue)
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        }
    }

    /* ───────── touch handling ────────── */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (GlobalSettings.editMode) {
            return editDrag(e)
        } else {
            // If global swipe is active, let the SwipeManager handle this
            if (GlobalSettings.globalSwipe) {
                // Still handle direct touches on this view
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    playTouch(e)
                    return true
                }
                // But delegate swipe handling to SwipeManager
                return false
            } else {
                playTouch(e)
                return true
            }
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
                    // Cancel any pending long press action from before
                    holdHandler.removeCallbacksAndMessages(null)

                    // If the button is already latched, a tap should unlatch it immediately
                    if (isLatched) {
                        // We'll toggle the latch state on button down for already latched buttons
                        isLatched = false
                        isPressed = false
                        invalidate() // Redraw to show the unlatched state
                    } else {
                        // Button is not latched, normal behavior
                        if (GlobalSettings.globalTurbo) {
                            uiHelper.startRepeat()
                        } else {
                            // First check for global hold mode
                            if (GlobalSettings.globalHold && !model.holdToggle) {
                                // Set latched immediately before sending payload
                                isLatched = true
                                isPressed = true
                                invalidate() // Redraw to show the latched state
                            }

                            // Now fire payload (will use the updated isLatched state)
                            uiHelper.firePayload()

                            // Start long press detection for hold toggle
                            if (model.holdToggle) {
                                holdHandler.postDelayed({
                                    // This executes after holdDurationMs
                                    isLatched = true
                                    isPressed = true
                                    invalidate() // Redraw to show the latched state
                                    // We need to fire payload again after latching to ensure proper state
                                    uiHelper.firePayload()
                                }, model.holdDurationMs)
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopRepeat()

                    // Handle latch toggling on short tap for unlatched buttons only
                    // (We already handled unlatching on ACTION_DOWN if button was latched,
                    // and we handled global hold mode on ACTION_DOWN as well)
                    if (!isLatched && model.holdToggle) {
                        // Cancel pending hold toggle if finger is lifted before holdDurationMs
                        holdHandler.removeCallbacksAndMessages(null)
                    }
                }
            }

            /* ----- RE-CENTER BUTTON ----- */
            ControlType.RECENTER -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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

                        // Initialize tracking for relative movement
                        touchTrackingInitialized = false
                        prevTouchX = e.x
                        prevTouchY = e.y

                        if (model.toggleLeftClick) {
                            // Toggle mode - flip the leftHeld state
                            leftHeld = !leftHeld

                            // Send the appropriate mouse command based on new state
                            if (leftHeld) {
                                NetworkClient.send("MOUSE_LEFT_DOWN")
                            } else {
                                NetworkClient.send("MOUSE_LEFT_UP")
                            }
                        } else if (model.holdLeftWhileTouch) {
                            // Standard hold mode
                            NetworkClient.send("MOUSE_LEFT_DOWN")
                            leftHeld = true
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Handle touchpad movement using relative positioning
                        handleTouchpadMovement(e)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // Reset touchpad tracking
                        touchTrackingInitialized = false

                        // Only release mouse button on up/cancel if using standard hold mode
                        if (!model.toggleLeftClick &&
                            leftHeld && model.holdLeftWhileTouch) {
                            NetworkClient.send("MOUSE_LEFT_UP")
                            leftHeld = false
                        }
                    }
                }
            }
        }
    }

    /**
     * Handle touchpad movement using relative positioning
     * This prevents the cursor from jumping to absolute positions
     */
    private fun handleTouchpadMovement(e: MotionEvent) {
        if (!touchTrackingInitialized) {
            // Skip the first movement event to establish a baseline
            touchTrackingInitialized = true
            prevTouchX = e.x
            prevTouchY = e.y
            return
        }

        // Calculate the movement delta since last position
        val dx = (e.x - prevTouchX) * model.sensitivity * touchpadSensitivity
        val dy = (e.y - prevTouchY) * model.sensitivity * touchpadSensitivity

        // Skip if movement is too small
        if (abs(dx) < 0.1f && abs(dy) < 0.1f) {
            prevTouchX = e.x
            prevTouchY = e.y
            return
        }

        // Save current position for next calculation
        prevTouchX = e.x
        prevTouchY = e.y

        // Send relative movement to server
        // The server expects relative movement values in the range -1 to 1
        val normalizedDx = dx.coerceIn(-1f, 1f)
        val normalizedDy = dy.coerceIn(-1f, 1f)

        // Send through UdpClient for lower latency
        com.example.simplecontroller.net.UdpClient.sendTouchpadPosition(normalizedDx, normalizedDy)
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
        val shouldSnap = GlobalSettings.snapEnabled && model.autoCenter &&
                (e.actionMasked == MotionEvent.ACTION_UP ||
                        e.actionMasked == MotionEvent.ACTION_CANCEL)

        val (sx, sy) = if (shouldSnap) 0f to 0f else nx to ny

        // Store last position for continuous sending if needed
        continuousSender.setLastPosition(sx, sy)

        // Handle directional mode for sticks
        if (model.type == ControlType.STICK && model.directionalMode) {
            directionalHandler.handleDirectionalStick(sx, sy, e.actionMasked)
        } else {
            // Regular analog stick/pad mode
            NetworkClient.send("${model.payload}:${"%.2f".format(sx)},${"%.2f".format(sy)}")
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
     * Update overlay visibility
     */
    fun updateOverlay() {
        uiHelper.updateOverlay()
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