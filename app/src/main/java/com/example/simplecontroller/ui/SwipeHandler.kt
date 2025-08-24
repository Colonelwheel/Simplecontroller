package com.example.simplecontroller.ui

import android.view.MotionEvent
import com.example.simplecontroller.model.ControlType

// Time (ms) to ignore extra enter/exit events when swiping
private const val swipeCooldownMs = 120L

/**
 * Handles swipe interactions between controls.
 *
 * Key behaviors:
 *  1) When leaving a STICK/TOUCHPAD, it immediately starts its own continuous‑sending
 *     so the last direction/position is held while you slide onto a button (walk→jump).
 *  2) Sends a proper ACTION_UP to the previous control using *clamped* local coords,
 *     so directional sticks see the true vector and can apply their internal logic.
 *  3) Short per‑control cooldown prevents rapid re‑fires while gliding over a control.
 */
class SwipeHandler {

    /** Last time a control was fired (per‑control) */
    private val lastFiredMap = mutableMapOf<String, Long>()

    // Track the currently touched control for swipe handling
    private var activeTouch: MotionEvent? = null
    private var lastTouchedView: ControlView? = null

    // Whether swipe handling is currently enabled
    private var swipeEnabled = false

    // Whether edit mode is active (swipe disabled while editing)
    private var editMode = false

    // All registered control views (populated by SwipeManager)
    private val allViews = mutableSetOf<ControlView>()

    /**
     * Process touch events for swipe mode
     * @return *true* if the event was handled exclusively by the SwipeHandler
     */
    fun processTouchEvent(e: MotionEvent): Boolean {
        if (!swipeEnabled || editMode) return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeTouch = MotionEvent.obtain(e)

                // Identify which control we started on
                lastTouchedView = allViews.firstOrNull { it.hitTest(e) }

                // Forward the DOWN event (with local coordinates) to that control
                lastTouchedView?.forwardEvent(e, MotionEvent.ACTION_DOWN)
                return lastTouchedView != null
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeTouch == null) return false

                for (view in allViews) {
                    // Respect per‑button swipe‑activation toggle
                    if (view.model.type == ControlType.BUTTON && !view.model.swipeActivate) continue

                    if (view.hitTest(e)) {
                        val now = System.currentTimeMillis()
                        val lastFired = lastFiredMap[view.model.id] ?: 0L
                        if (now - lastFired < swipeCooldownMs) break

                        // 1) Wrap‑up previous control *before* switching
                        lastTouchedView?.let { prev ->
                            // a) Tell sticks/touch‑pads to take over their own sending now
                            if (prev.model.type == ControlType.STICK || prev.model.type == ControlType.TOUCHPAD) {
                                prev.startContinuousSending()
                            }

                            // b) Deliver a proper ACTION_UP using **clamped** coords so
                            //    directional sticks capture the current vector.
                            prev.forwardEvent(e, MotionEvent.ACTION_UP, clamp = true)
                        }

                        // 2) Activate the *new* control with an ACTION_DOWN
                        view.forwardEvent(e, MotionEvent.ACTION_DOWN)

                        // 3) Book‑keeping
                        lastTouchedView = view
                        lastFiredMap[view.model.id] = now
                        break
                    }
                }

                // Forward MOVE events *only* to an active touch‑pad to keep cursor fluid
                if (lastTouchedView?.model?.type == ControlType.TOUCHPAD) {
                    lastTouchedView?.forwardEvent(e, MotionEvent.ACTION_MOVE)
                }
                return lastTouchedView != null
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Forward terminal event to whichever control was last active
                lastTouchedView?.forwardEvent(e, MotionEvent.ACTION_UP, clamp = true)

                // Clean‑up
                activeTouch?.recycle(); activeTouch = null
                lastTouchedView = null
                return false
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
