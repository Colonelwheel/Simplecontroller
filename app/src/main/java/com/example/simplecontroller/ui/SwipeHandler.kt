package com.example.simplecontroller.ui

import android.view.MotionEvent
import com.example.simplecontroller.model.ControlType

/**
 * Handles swipe interactions between controls.
 * This class manages finger movements across the screen when swipe mode is enabled.
 */
class SwipeHandler {

    // Short Cooldown variable

    private val lastFiredMap = mutableMapOf<String, Long>()
    private val swipeCooldownMs = 300L

    // Track the currently touched control for swipe handling
    private var activeTouch: MotionEvent? = null
    private var lastTouchedView: ControlView? = null

    // Whether swipe handling is currently enabled
    private var swipeEnabled = false
    
    // Whether edit mode is active (swipe handling is disabled in edit mode)
    private var editMode = false
    
    // Reference to all active control views
    private var allViews = mutableSetOf<ControlView>()
    
    /**
     * Process touch events for swipe mode
     * @param e The motion event
     * @return true if the event was handled, false otherwise
     */
    fun processTouchEvent(e: MotionEvent): Boolean {
        if (!swipeEnabled || editMode) return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeTouch = MotionEvent.obtain(e)
                // Find which control was initially touched (if any)
                lastTouchedView = allViews.firstOrNull {
                    val loc = IntArray(2)
                    it.getLocationOnScreen(loc)
                    val x = e.rawX - loc[0]
                    val y = e.rawY - loc[1]
                    x >= 0 && x < it.width && y >= 0 && y < it.height
                }

                // If we touched a view initially, let it handle the event
                if (lastTouchedView != null) {
                    val loc = IntArray(2)
                    lastTouchedView!!.getLocationOnScreen(loc)
                    val x = e.rawX - loc[0]
                    val y = e.rawY - loc[1]

                    val downEvent = MotionEvent.obtain(
                        e.downTime, e.eventTime, MotionEvent.ACTION_DOWN,
                        x, y, e.pressure, e.size, e.metaState, e.xPrecision,
                        e.yPrecision, e.deviceId, e.edgeFlags
                    )

                    lastTouchedView!!.playTouch(downEvent)
                    downEvent.recycle()
                }

                return lastTouchedView != null
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeTouch == null) return false

                for (view in allViews) {
                    if (view.model.type == ControlType.BUTTON && !view.model.swipeActivate) continue

                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    val x = e.rawX - loc[0]
                    val y = e.rawY - loc[1]

                    if (x >= 0 && x < view.width && y >= 0 && y < view.height) {
                        val now = System.currentTimeMillis()
                        val lastFired = lastFiredMap[view.model.id] ?: 0L

                        if (now - lastFired >= swipeCooldownMs) {
                            // Always send UP to the last touched view, even if it's the same
                            lastTouchedView?.let { prevView ->
                                val upEvent = MotionEvent.obtain(
                                    e.downTime, e.eventTime, MotionEvent.ACTION_UP,
                                    0f, 0f, e.pressure, e.size, e.metaState,
                                    e.xPrecision, e.yPrecision, e.deviceId, e.edgeFlags
                                )
                                prevView.playTouch(upEvent)
                                upEvent.recycle()
                            }

                            // Send a new ACTION_DOWN to the current view
                            val downEvent = MotionEvent.obtain(
                                e.downTime, e.eventTime, MotionEvent.ACTION_DOWN,
                                x, y, e.pressure, e.size, e.metaState,
                                e.xPrecision, e.yPrecision, e.deviceId, e.edgeFlags
                            )
                            view.playTouch(downEvent)
                            downEvent.recycle()

                            // Track cooldown
                            lastFiredMap[view.model.id] = now
                            lastTouchedView = view
                            return true
                        }
                    }
                }

                // Still over same touchpad? Forward continuous moves
                if (lastTouchedView?.model?.type == ControlType.TOUCHPAD) {
                    val loc = IntArray(2)
                    lastTouchedView!!.getLocationOnScreen(loc)
                    val x = e.rawX - loc[0]
                    val y = e.rawY - loc[1]

                    val moveEvent = MotionEvent.obtain(
                        e.downTime, e.eventTime, MotionEvent.ACTION_MOVE,
                        x, y, e.pressure, e.size, e.metaState,
                        e.xPrecision, e.yPrecision, e.deviceId, e.edgeFlags
                    )
                    lastTouchedView!!.playTouch(moveEvent)
                    moveEvent.recycle()
                    return true
                }

                return lastTouchedView != null
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Make sure to send UP event to last touched view to clean up any state
                lastTouchedView?.let { view ->
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    val x = e.rawX - loc[0]
                    val y = e.rawY - loc[1]

                    val upEvent = MotionEvent.obtain(
                        e.downTime, e.eventTime, MotionEvent.ACTION_UP,
                        x, y, e.pressure, e.size, e.metaState, e.xPrecision,
                        e.yPrecision, e.deviceId, e.edgeFlags
                    )
                    view.playTouch(upEvent)
                    upEvent.recycle()
                }

                activeTouch?.recycle()
                activeTouch = null
                lastTouchedView = null
                return false
            }
        }

        return false
    }
    
    /**
     * Register a view with the swipe handler
     */
    fun registerView(view: ControlView) {
        allViews.add(view)
    }
    
    /**
     * Unregister a view from the swipe handler
     */
    fun unregisterView(view: ControlView) {
        allViews.remove(view)
    }
    
    /**
     * Set swipe enabled state
     */
    fun setSwipeEnabled(enabled: Boolean) {
        if (swipeEnabled && !enabled) {
            // When turning swipe off, make sure any active controls are cleaned up
            lastTouchedView?.let { it.stopRepeat() }
            lastTouchedView = null
            activeTouch?.recycle()
            activeTouch = null
        }
        swipeEnabled = enabled
    }
    
    /**
     * Set edit mode state
     */
    fun setEditMode(enabled: Boolean) {
        editMode = enabled
    }
    
    /**
     * Get all registered views
     */
    fun getAllViews(): Set<ControlView> = allViews
}