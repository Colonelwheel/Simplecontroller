package com.example.simplecontroller.ui

import android.content.Context
import android.graphics.Canvas
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
import com.example.simplecontroller.net.UdpClient
import com.example.simplecontroller.ui.button.ButtonHandler
import com.example.simplecontroller.ui.stick.StickHandler
import com.example.simplecontroller.ui.touch.TouchpadHandler
import kotlin.math.min

/**
 * Refactored ControlView that uses specialized handlers for each control type.
 * 
 * This version delegates specific behaviors to dedicated handler classes:
 * - ButtonHandler for button controls
 * - StickHandler for stick controls
 * - TouchpadHandler for touchpad controls
 */
class RefactoredControlView(
    context: Context,
    val model: Control
) : FrameLayout(context) {

    // Handler for UI updates
    private val uiHandler = Handler(Looper.getMainLooper())
    
    // State tracking
    private var isLatched = false
        set(value) {
            val oldValue = field
            field = value
            
            // If we're turning off latched mode, release any held buttons
            if (oldValue && !value) {
                uiHelper.releaseLatched()
            }
        }
    
    // For dragging in edit mode
    private var dX = 0f
    private var dY = 0f
    
    // Specialized handlers
    private val uiHelper: ControlViewHelper
    private val directionalHandler: DirectionalStickHandler
    private val continuousSender: ContinuousSender
    
    // Type-specific handlers
    private val buttonHandler: ButtonHandler?
    private val stickHandler: StickHandler?
    private val touchpadHandler: TouchpadHandler?
    
    // Paint for drawing the control
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    
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
        
        // Create type-specific handlers
        buttonHandler = if (model.type == ControlType.BUTTON || model.type == ControlType.RECENTER) {
            ButtonHandler(
                model = model,
                firePayloadCallback = { uiHelper.firePayload() },
                startRepeatCallback = { uiHelper.startRepeat() },
                stopRepeatCallback = { uiHelper.stopRepeat() },
                latchStateCallback = { isLatched = it },
                setPressedCallback = { isPressed = it },
                invalidateCallback = { invalidate() }
            )
        } else null
        
        stickHandler = if (model.type == ControlType.STICK) {
            StickHandler(
                model = model,
                width = model.w.toInt(),
                height = model.h.toInt(),
                setPositionCallback = { x, y -> continuousSender.setLastPosition(x, y) },
                stopContinuousSendingCallback = { continuousSender.stopContinuousSending() },
                stopDirectionalCommandsCallback = { directionalHandler.stopDirectionalCommands() },
                startContinuousSendingCallback = { continuousSender.startContinuousSending() },
                handleDirectionalStickCallback = { x, y, action -> 
                    directionalHandler.handleDirectionalStick(x, y, action)
                }
            )
        } else null
        
        touchpadHandler = if (model.type == ControlType.TOUCHPAD) {
            TouchpadHandler(
                model = model,
                leftHeldCallback = { leftHeld -> /* Track left button state */ },
                uiHandler = uiHandler
            )
        } else null
        
        // Register with SwipeManager
        SwipeManager.registerControl(this)
        
        // Initial UI updates
        updateOverlay()
        uiHelper.updateLabel()
    }
    
    // Clean up when the view is removed
    override fun onDetachedFromWindow() {
        // Release mouse button if needed for touchpad
        if (model.type == ControlType.TOUCHPAD && touchpadHandler?.isLeftHeld() == true) {
            UdpClient.sendCommand("MOUSE_LEFT_UP")
        }
        
        // Stop any ongoing actions
        stopRepeat()
        stopContinuousSending()
        stopDirectionalCommands()
        
        // Clean up handlers
        touchpadHandler?.cleanup()
        
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
            ControlType.BUTTON -> {
                buttonHandler?.handleTouchEvent(e)
            }
            
            ControlType.RECENTER -> {
                if (buttonHandler?.handleRecenterButtonEvent(e) == true) {
                    // If button handler processed the event, also trigger recenter action
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        SwipeManager.recenterAllSticks()
                    }
                }
            }
            
            ControlType.STICK -> {
                stickHandler?.handleTouchEvent(e)
            }
            
            ControlType.TOUCHPAD -> {
                touchpadHandler?.handleTouchEvent(e)
            }
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