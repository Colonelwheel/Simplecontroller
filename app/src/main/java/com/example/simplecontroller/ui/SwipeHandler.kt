package com.example.simplecontroller.ui

import android.view.MotionEvent
import com.example.simplecontroller.model.ControlType

// Time (ms) to ignore extra enter/exit events when swiping
private const val swipeCooldownMs = 120L

/**
 * Handles swipe interactions between controls.
 *
 * Key points:
 *  - MOVE events go to the *current* control (sticks now receive motion!)
 *  - We only switch controls when the finger actually leaves the current one
 *  - On switch: old control gets clamped UP (and sticks/pads start their self-sender),
 *    new control gets DOWN + an immediate MOVE at the current coords
 */
class SwipeHandler {

    /** Last time a control was entered (for cooldown) */
    private val lastFiredMap = mutableMapOf<String, Long>()

    // Active gesture state
    private var activeTouch: MotionEvent? = null
    private var lastTouchedView: ControlView? = null

    // Feature flags
    private var swipeEnabled = false
    private var editMode = false

    // Registered controls
    private val allViews = mutableSetOf<ControlView>()

    /**
     * Process touch events for swipe mode
     * @return *true* if handled
     */
    fun processTouchEvent(e: MotionEvent): Boolean {
        if (!swipeEnabled || editMode) return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeTouch = MotionEvent.obtain(e)

                // Find the starting control
                val start = allViews.firstOrNull { it.hitTest(e) }
                lastTouchedView = start

                // Send DOWN to it (with local coords)
                start?.forwardEvent(e, MotionEvent.ACTION_DOWN)

                return start != null
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeTouch == null) return false

                val current = lastTouchedView
                // 1) If we’re still over the same control, just send MOVE to it
                if (current != null && current.hitTest(e)) {
                    current.forwardEvent(e, MotionEvent.ACTION_MOVE)
                    return true
                }

                // 2) Else: finger moved off the old control; see if it entered a new one
                val entered = allViews.firstOrNull { it.hitTest(e) }
                if (entered != null) {
                    val now = System.currentTimeMillis()
                    val last = lastFiredMap[entered.model.id] ?: 0L
                    if (now - last >= swipeCooldownMs) {
                        // Wrap up previous control before switching
                        lastTouchedView?.let { prev ->
                            // Sticks / Touchpads take over their own sending while we swipe away
                            if (prev.model.type == ControlType.STICK || prev.model.type == ControlType.TOUCHPAD) {
                                prev.startContinuousSending()
                            }
                            // Give it a clamped UP so directional sticks capture the last vector
                            prev.forwardEvent(e, MotionEvent.ACTION_UP, clamp = true)
                        }

                        // Activate the new control with DOWN…
                        entered.forwardEvent(e, MotionEvent.ACTION_DOWN)
                        // …and immediately feed it a MOVE so sticks have a position right away
                        entered.forwardEvent(e, MotionEvent.ACTION_MOVE)

                        lastTouchedView = entered
                        lastFiredMap[entered.model.id] = now
                        return true
                    } else {
                        // Cooldown: don’t thrash; do nothing this frame
                        return true
                    }
                }

                // 3) We’re not over *any* control now; keep sending MOVE to sticks/touchpads
                //    only if that’s useful to your UX. Safer to do nothing here:
                return lastTouchedView != null
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Finalize last active control with a clamped UP
                lastTouchedView?.forwardEvent(e, MotionEvent.ACTION_UP, clamp = true)

                // Clean up
                activeTouch?.recycle(); activeTouch = null
                lastTouchedView = null
                return true
            }
        }
        return false
    }

    /* --------------------------------------------------------------------- */
    /*  Public helpers                                                        */
    /* --------------------------------------------------------------------- */
    fun registerView(view: ControlView)   = allViews.add(view)
    fun unregisterView(view: ControlView) = allViews.remove(view)

    fun setSwipeEnabled(enabled: Boolean) {
        if (swipeEnabled && !enabled) {
            // Force‑release any control that might still be active
            lastTouchedView?.stopRepeat()
            lastTouchedView = null
            activeTouch?.recycle(); activeTouch = null
        }
        swipeEnabled = enabled
    }

    fun setEditMode(enabled: Boolean) { editMode = enabled }

    fun getAllViews(): Set<ControlView> = allViews

    /* --------------------------------------------------------------------- */
    /*  Private extension helpers                                             */
    /* --------------------------------------------------------------------- */

    /** Hit‑test the MotionEvent against this view (screenspace) */
    private fun ControlView.hitTest(e: MotionEvent): Boolean {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val x = e.rawX - loc[0]
        val y = e.rawY - loc[1]
        return x >= 0 && x < width && y >= 0 && y < height
    }

    /**
     * Forward a cloned MotionEvent with action translated to *newAction*.
     * If *clamp* is true, x/y are clamped into the view bounds, otherwise raw.
     */
    private fun ControlView.forwardEvent(src: MotionEvent, newAction: Int, clamp: Boolean = false) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        var x = src.rawX - loc[0]
        var y = src.rawY - loc[1]
        if (clamp) {
            x = x.coerceIn(0f, width.toFloat())
            y = y.coerceIn(0f, height.toFloat())
        }
        val evt = MotionEvent.obtain(
            src.downTime,
            src.eventTime,
            newAction,
            x,
            y,
            src.pressure,
            src.size,
            src.metaState,
            src.xPrecision,
            src.yPrecision,
            src.deviceId,
            src.edgeFlags
        )
        playTouch(evt)
        evt.recycle()
    }
}
