package com.example.simplecontroller.ui

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import com.example.simplecontroller.MainActivity
import com.example.simplecontroller.model.*
import com.example.simplecontroller.net.NetworkClient
import kotlin.math.*

class ControlView(
    context: Context,
    private val model: Control
) : FrameLayout(context) {

    /* ───────── member variables ────────── */
    private var dX = 0f
    private var dY = 0f
    private var isLatched = false          // for Hold / globalHold
    private var leftHeld  = false          // for one-finger drag or toggle
    private var repeater  : Runnable? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    // For continuous stick position sending
    private var lastStickX = 0f
    private var lastStickY = 0f
    private var continuousSender: Runnable? = null

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

    /* ───────── companion ──────── */
    companion object {
        /* Edit-mode toggle */
        var editMode = false
            set(v) {
                field = v
                if (v) {
                    // Stop continuous sending for all controls when entering edit mode
                    _all.forEach {
                        it.updateOverlay()
                        it.stopContinuousSending()
                        it.stopDirectionalCommands()
                    }
                } else {
                    _all.forEach { it.updateOverlay() }
                }
            }

        /* ★ Global feature toggles (wired to MainActivity switches) ★ */
        var snapEnabled  = true    // "Snap" → auto-centre sticks / pads
            set(value) {
                field = value
                if (value) {
                    // When enabling snap, stop any continuous sending
                    _all.forEach {
                        it.stopContinuousSending()
                        it.stopDirectionalCommands()
                    }
                }
            }

        var globalHold   = false   // latch every button

        var globalTurbo  = false   // rapid-fire every button
            set(value) {
                if (field && !value) {
                    // When turning turbo off, make sure all repeaters are stopped
                    _all.forEach { it.stopRepeat() }
                }
                field = value
            }

        var globalSwipe  = false   // MainActivity intercepts swipe
            set(value) {
                if (field && !value) {
                    // When turning swipe off, make sure any active controls are cleaned up
                    lastTouchedView?.stopRepeat()
                    lastTouchedView = null
                    activeTouch?.recycle()
                    activeTouch = null
                }
                field = value
            }

        /* expose live set for hit-testing */
        internal val allViews: Set<ControlView> get() = _all
        private val _all = mutableSetOf<ControlView>()

        // Track the currently touched control for swipe handling
        private var activeTouch: MotionEvent? = null
        private var lastTouchedView: ControlView? = null

        // Process touch events for swipe mode
        fun processTouchEvent(e: MotionEvent): Boolean {
            if (!globalSwipe || editMode) return false

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeTouch = MotionEvent.obtain(e)
                    // Find which control was initially touched (if any)
                    lastTouchedView = _all.firstOrNull {
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

                    // Special handling for the touchpad - forward all move events to it
                    if (lastTouchedView?.model?.type == ControlType.TOUCHPAD) {
                        val loc = IntArray(2)
                        lastTouchedView!!.getLocationOnScreen(loc)
                        val x = e.rawX - loc[0]
                        val y = e.rawY - loc[1]

                        // Only forward the event if the point is within the touchpad
                        if (x >= 0 && x < lastTouchedView!!.width &&
                            y >= 0 && y < lastTouchedView!!.height) {
                            val moveEvent = MotionEvent.obtain(
                                e.downTime, e.eventTime, MotionEvent.ACTION_MOVE,
                                x, y, e.pressure, e.size, e.metaState, e.xPrecision,
                                e.yPrecision, e.deviceId, e.edgeFlags
                            )

                            lastTouchedView!!.playTouch(moveEvent)
                            moveEvent.recycle()
                            return true
                        }
                    }

                    // Find all controls under current touch point for swiping
                    for (view in _all) {
                        if (view == lastTouchedView) continue // Skip the initially touched view
                        if (view.model.type == ControlType.BUTTON && !view.model.swipeActivate) continue // Skip buttons with swipe activation disabled

                        val loc = IntArray(2)
                        view.getLocationOnScreen(loc)
                        val x = e.rawX - loc[0]
                        val y = e.rawY - loc[1]

                        if (x >= 0 && x < view.width && y >= 0 && y < view.height) {
                            // If we're moving to a new view, make sure the last one gets an UP event
                            lastTouchedView?.let { prevView ->
                                if (prevView != view) {
                                    val upEvent = MotionEvent.obtain(
                                        e.downTime, e.eventTime, MotionEvent.ACTION_UP,
                                        0f, 0f, e.pressure, e.size, e.metaState, e.xPrecision,
                                        e.yPrecision, e.deviceId, e.edgeFlags
                                    )
                                    prevView.playTouch(upEvent)
                                    upEvent.recycle()
                                }
                            }

                            // Create a synthetic DOWN event for this control
                            val downEvent = MotionEvent.obtain(
                                e.downTime, e.eventTime, MotionEvent.ACTION_DOWN,
                                x, y, e.pressure, e.size, e.metaState, e.xPrecision,
                                e.yPrecision, e.deviceId, e.edgeFlags
                            )

                            // Forward the event to the control
                            view.playTouch(downEvent)
                            downEvent.recycle()

                            // Update the last touched view
                            lastTouchedView = view
                            return true
                        }
                    }

                    // If we're still over the initial touchpad, keep forwarding events
                    if (lastTouchedView?.model?.type == ControlType.TOUCHPAD) {
                        val loc = IntArray(2)
                        lastTouchedView!!.getLocationOnScreen(loc)

                        // Even if outside the bounds, convert to relative coordinates for continuous tracking
                        val x = e.rawX - loc[0]
                        val y = e.rawY - loc[1]

                        val moveEvent = MotionEvent.obtain(
                            e.downTime, e.eventTime, MotionEvent.ACTION_MOVE,
                            x, y, e.pressure, e.size, e.metaState, e.xPrecision,
                            e.yPrecision, e.deviceId, e.edgeFlags
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
    }

    /* ───────── visuals ────────── */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.WHITE)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
        gravity = Gravity.CENTER
    }
    private fun makeIcon(resId: Int, g: Int, onClick: () -> Unit) =
        ImageButton(context).apply {
            setImageResource(resId)
            background = null
            alpha = .8f
            setPadding(8)
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                g
            )
            setOnClickListener { onClick() }
        }
    private val gear = makeIcon(
        android.R.drawable.ic_menu_manage,
        Gravity.TOP or Gravity.END
    ) { showProps() }
    private val dup = makeIcon(
        android.R.drawable.ic_menu_add,
        Gravity.TOP or Gravity.START
    ) { if (editMode) duplicateSelf() }
    private val del = makeIcon(
        android.R.drawable.ic_menu_delete,
        Gravity.TOP or Gravity.CENTER_HORIZONTAL
    ) { if (editMode) confirmDelete() }

    /* ───────── init ───────────── */
    init {
        layoutParams = MarginLayoutParams(model.w.toInt(), model.h.toInt()).apply {
            leftMargin = model.x.toInt(); topMargin = model.y.toInt()
        }
        setWillNotDraw(false); isClickable = true
        addView(label); addView(gear); addView(dup); addView(del)
        _all += this
        updateOverlay(); updateLabel()
    }

    // Make sure we clean up when the view is removed
    override fun onDetachedFromWindow() {
        // If we have a locked left mouse button, release it
        if (model.type == ControlType.TOUCHPAD && leftHeld) {
            NetworkClient.send("MOUSE_LEFT_UP")
            leftHeld = false
        }

        stopRepeat()
        stopContinuousSending()
        stopDirectionalCommands()
        _all -= this
        super.onDetachedFromWindow()
    }

    /* ───────── drawing ────────── */
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        when (model.type) {
            ControlType.BUTTON -> {
                // Use a brighter color if the button is latched (held)
                paint.color = if (isLatched) 0xFF4CAF50.toInt() else 0xFF2196F3.toInt()
                c.drawCircle(width / 2f, height / 2f, min(width, height) / 2f, paint)
            }
            ControlType.STICK -> {
                paint.color = 0x552196F3; c.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
                paint.color = 0xFF2196F3.toInt()
                c.drawCircle(width/2f, height/2f, min(width,height)/6f, paint)
            }
            ControlType.TOUCHPAD -> {
                paint.color = 0x332196F3; c.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editMode) {
            return editDrag(e)
        } else {
            // If global swipe is active, let the companion handler manage this
            if (globalSwipe) {
                // Still handle direct touches on this view
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    playTouch(e)
                    return true
                }
                // But delegate swipe handling to companion
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
                dX = e.rawX - lp().leftMargin
                dY = e.rawY - lp().topMargin
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val lp = lp()
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
                    if (globalTurbo) startRepeat() else firePayload()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopRepeat()
                    if (globalHold || model.holdToggle) {
                        isLatched = !isLatched
                        isPressed = isLatched
                        invalidate() // Redraw to show the latched state with the new color
                    }
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
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        handleStickOrPad(e)

                        // Only release mouse button on up/cancel if using standard hold mode
                        if (!model.toggleLeftClick &&
                            (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) &&
                            leftHeld && model.holdLeftWhileTouch) {
                            NetworkClient.send("MOUSE_LEFT_UP")
                            leftHeld = false
                        }
                    }
                }
            }
        }
    }

    /* ---------- helpers ---------- */
    private fun firePayload() {
        model.payload.split(',', ' ')
            .filter { it.isNotBlank() }
            .forEach { NetworkClient.send(it.trim()) }
    }

    private fun startRepeat() {
        firePayload()                       // fire immediately
        repeater = object : Runnable {
            override fun run() {
                firePayload()
                uiHandler.postDelayed(this, 16L)   // ≈60 Hz
            }
        }
        uiHandler.postDelayed(repeater!!, 16L)
    }

    private fun stopRepeat() {
        repeater?.let { uiHandler.removeCallbacks(it) }
        repeater = null
    }

    private fun handleStickOrPad(e: MotionEvent) {
        if (e.actionMasked !in listOf(
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL)) return

        val cx = width/2f; val cy = height/2f
        val nx = ((e.x - cx) / (width/2f)).coerceIn(-1f,1f) * model.sensitivity
        val ny = ((e.y - cy) / (height/2f)).coerceIn(-1f,1f) * model.sensitivity

        // Only snap if snapEnabled is true AND the control's autoCenter is true AND it's an UP/CANCEL event
        val shouldSnap = snapEnabled && model.autoCenter &&
                (e.actionMasked == MotionEvent.ACTION_UP ||
                        e.actionMasked == MotionEvent.ACTION_CANCEL)

        val (sx, sy) = if (shouldSnap) 0f to 0f else nx to ny

        // Store last position for continuous sending if needed
        lastStickX = sx
        lastStickY = sy

        // Handle directional mode for sticks
        if (model.type == ControlType.STICK && model.directionalMode) {
            handleDirectionalStick(sx, sy, e.actionMasked)
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

    // Handle directional stick inputs, sending button commands instead of analog values
    private fun handleDirectionalStick(x: Float, y: Float, action: Int) {
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
                .forEach { NetworkClient.send(it.trim()) }
            update()
        }

        // Send commands based on direction and intensity
        if (y < -0.1f) { // Up direction
            if (absY > model.boostThreshold) {
                sendCommand(model.upBoostCommand) { sentUp = true }
            } else {
                sendCommand(model.upCommand) { sentUp = true }
            }
        }

        if (y > 0.1f) { // Down direction
            if (absY > model.boostThreshold) {
                sendCommand(model.downBoostCommand) { sentDown = true }
            } else {
                sendCommand(model.downCommand) { sentDown = true }
            }
        }

        if (x < -0.1f) { // Left direction
            if (absX > model.boostThreshold) {
                sendCommand(model.leftBoostCommand) { sentLeft = true }
            } else {
                sendCommand(model.leftCommand) { sentLeft = true }
            }
        }

        if (x > 0.1f) { // Right direction
            if (absX > model.boostThreshold) {
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

    private fun startContinuousSending() {
        // Stop any existing continuous sender
        stopContinuousSending()

        // Create a new continuous sender
        continuousSender = object : Runnable {
            override fun run() {
                NetworkClient.send("${model.payload}:${"%.2f".format(lastStickX)},${"%.2f".format(lastStickY)}")
                uiHandler.postDelayed(this, 100L) // Send every 100ms
            }
        }

        // Start continuous sending
        uiHandler.postDelayed(continuousSender!!, 100L)
    }

    private fun stopContinuousSending() {
        continuousSender?.let { uiHandler.removeCallbacks(it) }
        continuousSender = null
    }

    private fun stopDirectionalCommands() {
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
    }

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
        sendingUpBoost = up && absY > model.boostThreshold
        sendingDownBoost = down && absY > model.boostThreshold
        sendingLeftBoost = left && absX > model.boostThreshold
        sendingRightBoost = right && absX > model.boostThreshold

        if (!model.autoCenter && (up || down || left || right)) {
            // Create a continuous sender for directional commands
            continuousDirectional = object : Runnable {
                override fun run() {
                    if (sendingUp) {
                        val command = if (sendingUpBoost) model.upBoostCommand else model.upCommand
                        command.split(',', ' ')
                            .filter { it.isNotBlank() }
                            .forEach { NetworkClient.send(it.trim()) }
                    }

                    if (sendingDown) {
                        val command = if (sendingDownBoost) model.downBoostCommand else model.downCommand
                        command.split(',', ' ')
                            .filter { it.isNotBlank() }
                            .forEach { NetworkClient.send(it.trim()) }
                    }

                    if (sendingLeft) {
                        val command = if (sendingLeftBoost) model.leftBoostCommand else model.leftCommand
                        command.split(',', ' ')
                            .filter { it.isNotBlank() }
                            .forEach { NetworkClient.send(it.trim()) }
                    }

                    if (sendingRight) {
                        val command = if (sendingRightBoost) model.rightBoostCommand else model.rightCommand
                        command.split(',', ' ')
                            .filter { it.isNotBlank() }
                            .forEach { NetworkClient.send(it.trim()) }
                    }

                    uiHandler.postDelayed(this, 100L) // Send every 100ms
                }
            }

            // Start continuous sending
            uiHandler.postDelayed(continuousDirectional!!, 100L)
        }
    }

    /* ───────── quick-actions ───── */
    private fun duplicateSelf() {
        val copy = model.copy(
            id = "${model.id}_copy_${System.currentTimeMillis()}",
            x  = model.x + 40f,
            y  = model.y + 40f
        )
        (context as? MainActivity)
            ?.createControlFrom(copy)
    }
    private fun confirmDelete() {
        AlertDialog.Builder(context)
            .setMessage("Delete this control?")
            .setPositiveButton("Delete") { _, _ ->
                (parent as? FrameLayout)?.removeView(this)
                (context as? MainActivity)
                    ?.removeControl(model)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* ───────── overlay / label ─── */
    private fun updateOverlay() {
        val vis = if (editMode) View.VISIBLE else View.GONE
        gear.visibility = vis; dup.visibility = vis; del.visibility = vis
    }
    private fun updateLabel() {
        label.text = model.name
        label.visibility = if (model.name.isNotEmpty()) View.VISIBLE else View.GONE
    }
    private fun lp() = layoutParams as MarginLayoutParams

    /* ───────── property sheet (unchanged except drag option) ───────── */
    fun showProps() {
        // Create a ScrollView to make long property sheets scrollable
        val scrollView = ScrollView(context).apply {
            // Set max height to 70% of screen height to ensure dialog isn't too tall
            val metrics = context.resources.displayMetrics
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (metrics.heightPixels * 0.7).toInt()
            )
        }

        val dlg = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32,24,32,8)
        }

        // Add the LinearLayout to the ScrollView
        scrollView.addView(dlg)

        fun gap(h:Int=8) = Space(context).apply { minimumHeight = h }

        val etName = EditText(context).apply { hint = "Label"; setText(model.name) }
        dlg.addView(etName); dlg.addView(gap())

        /* width & height sliders */
        val wText = TextView(context)
        val wSeek = SeekBar(context).apply {
            max = 600; progress = model.w.roundToInt().coerceIn(40,600)
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){ wText.text = "Width: $p px" }
                override fun onStartTrackingTouch(s:SeekBar?){}; override fun onStopTrackingTouch(s:SeekBar?){} })
        }
        val hText = TextView(context)
        val hSeek = SeekBar(context).apply {
            max = 600; progress = model.h.roundToInt().coerceIn(40,600)
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){ hText.text = "Height: $p px" }
                override fun onStartTrackingTouch(s:SeekBar?){}; override fun onStopTrackingTouch(s:SeekBar?){} })
        }
        wText.text = "Width: ${wSeek.progress} px"; hText.text = "Height: ${hSeek.progress} px"
        dlg.addView(wText); dlg.addView(wSeek); dlg.addView(hText); dlg.addView(hSeek); dlg.addView(gap())

        /* sensitivity */
        var sensSeek:SeekBar? = null
        if (model.type != ControlType.BUTTON) {
            val sText = TextView(context)
            sensSeek = SeekBar(context).apply {
                max = 500; progress = (model.sensitivity * 100).roundToInt()
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){ sText.text = "Sensitivity: ${p/100f}" }
                    override fun onStartTrackingTouch(s:SeekBar?){}; override fun onStopTrackingTouch(s:SeekBar?){} })
            }
            sText.text = "Sensitivity: ${sensSeek.progress/100f}"
            dlg.addView(sText); dlg.addView(sensSeek); dlg.addView(gap())
        }

        /* hold toggle */
        val chkHold = CheckBox(context).apply {
            text = "Hold toggles"; isChecked = model.holdToggle
            visibility = if (model.type == ControlType.BUTTON) View.VISIBLE else View.GONE
        }
        val etMs = EditText(context).apply {
            hint = "ms"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(model.holdDurationMs.toString()); visibility = chkHold.visibility
        }
        dlg.addView(chkHold); dlg.addView(etMs); dlg.addView(gap())

        /* auto-center */
        val chkAuto = CheckBox(context).apply {
            text = "Auto-center"; isChecked = model.autoCenter
            visibility = if (model.type != ControlType.BUTTON) View.VISIBLE else View.GONE
        }
        dlg.addView(chkAuto); dlg.addView(gap())

        /* one-finger drag option (TOUCHPAD only) */
        val chkDrag = CheckBox(context).apply {
            text = "Hold left while finger is down"
            isChecked = model.holdLeftWhileTouch
            visibility = if (model.type == ControlType.TOUCHPAD) View.VISIBLE else View.GONE
        }
        dlg.addView(chkDrag); dlg.addView(gap())

        /* toggle click mode (TOUCHPAD only) */
        val chkToggleClick = CheckBox(context).apply {
            text = "Toggle left click mode (click-lock)"
            isChecked = model.toggleLeftClick
            visibility = if (model.type == ControlType.TOUCHPAD) View.VISIBLE else View.GONE
        }
        dlg.addView(chkToggleClick); dlg.addView(gap())

        /* Make two touchpad options mutually exclusive */
        chkDrag.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && chkToggleClick.isChecked) {
                chkToggleClick.isChecked = false
            }
        }

        chkToggleClick.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && chkDrag.isChecked) {
                chkDrag.isChecked = false
            }
        }

        /* swipe activation option (for buttons) */
        val chkSwipe = CheckBox(context).apply {
            text = "Enable swipe activation"
            isChecked = model.swipeActivate
            visibility = if (model.type == ControlType.BUTTON) View.VISIBLE else View.GONE
        }
        dlg.addView(chkSwipe); dlg.addView(gap())

        /* directional mode (STICKS only) */
        val chkDirectional = CheckBox(context).apply {
            text = "Directional mode (WASD style)"
            isChecked = model.directionalMode
            visibility = if (model.type == ControlType.STICK) View.VISIBLE else View.GONE
        }
        dlg.addView(chkDirectional)

        /* directional settings container */
        val directionalContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (model.type == ControlType.STICK && model.directionalMode) View.VISIBLE else View.GONE
        }
        dlg.addView(directionalContainer)

        /* update visibility when directional mode is toggled */
        chkDirectional.setOnCheckedChangeListener { _, isChecked ->
            directionalContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        /* Directional commands section */
        if (model.type == ControlType.STICK) {
            // Title
            directionalContainer.addView(TextView(context).apply {
                text = "Directional Commands"
                setPadding(0, 16, 0, 8)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })

            // Up command
            directionalContainer.addView(TextView(context).apply { text = "Up command:" })
            val etUp = EditText(context).apply {
                hint = "W"
                setText(model.upCommand)
                tag = "up_command"
            }
            directionalContainer.addView(etUp)
            directionalContainer.addView(gap())

            // Down command
            directionalContainer.addView(TextView(context).apply { text = "Down command:" })
            val etDown = EditText(context).apply {
                hint = "S"
                setText(model.downCommand)
                tag = "down_command"
            }
            directionalContainer.addView(etDown)
            directionalContainer.addView(gap())

            // Left command
            directionalContainer.addView(TextView(context).apply { text = "Left command:" })
            val etLeft = EditText(context).apply {
                hint = "A"
                setText(model.leftCommand)
                tag = "left_command"
            }
            directionalContainer.addView(etLeft)
            directionalContainer.addView(gap())

            // Right command
            directionalContainer.addView(TextView(context).apply { text = "Right command:" })
            val etRight = EditText(context).apply {
                hint = "D"
                setText(model.rightCommand)
                tag = "right_command"
            }
            directionalContainer.addView(etRight)
            directionalContainer.addView(gap(16))

            // Boost threshold
            directionalContainer.addView(TextView(context).apply { text = "Boost threshold:" })
            val thresholdText = TextView(context)
            val thresholdSeek = SeekBar(context).apply {
                max = 90  // 0.1 to 1.0 in steps of 0.01
                progress = ((model.boostThreshold - 0.1f) * 100).roundToInt().coerceIn(0, 90)
                tag = "boost_threshold"
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){
                        val value = 0.1f + (p / 100f)
                        thresholdText.text = "%.2f".format(value)
                    }
                    override fun onStartTrackingTouch(s:SeekBar?){}
                    override fun onStopTrackingTouch(s:SeekBar?){}
                })
            }
            thresholdText.text = "%.2f".format(0.1f + (thresholdSeek.progress / 100f))
            directionalContainer.addView(thresholdText)
            directionalContainer.addView(thresholdSeek)
            directionalContainer.addView(gap(16))

            // Boost commands section
            directionalContainer.addView(TextView(context).apply {
                text = "Boost Commands (when pushed beyond threshold)"
                setPadding(0, 8, 0, 8)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })

            // Up boost command
            directionalContainer.addView(TextView(context).apply { text = "Up boost command:" })
            val etUpBoost = EditText(context).apply {
                hint = "W,SHIFT"
                setText(model.upBoostCommand)
                tag = "up_boost"
            }
            directionalContainer.addView(etUpBoost)
            directionalContainer.addView(gap())

            // Down boost command
            directionalContainer.addView(TextView(context).apply { text = "Down boost command:" })
            val etDownBoost = EditText(context).apply {
                hint = "S,CTRL"
                setText(model.downBoostCommand)
                tag = "down_boost"
            }
            directionalContainer.addView(etDownBoost)
            directionalContainer.addView(gap())

            // Left boost command
            directionalContainer.addView(TextView(context).apply { text = "Left boost command:" })
            val etLeftBoost = EditText(context).apply {
                hint = "A,SHIFT"
                setText(model.leftBoostCommand)
                tag = "left_boost"
            }
            directionalContainer.addView(etLeftBoost)
            directionalContainer.addView(gap())

            // Right boost command
            directionalContainer.addView(TextView(context).apply { text = "Right boost command:" })
            val etRightBoost = EditText(context).apply {
                hint = "D,SHIFT"
                setText(model.rightBoostCommand)
                tag = "right_boost"
            }
            directionalContainer.addView(etRightBoost)
            directionalContainer.addView(gap())
        }

        /* payload */
        val etPayload = AutoCompleteTextView(context).apply {
            hint = "payload (comma-sep)"
            setText(model.payload)
            inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            val suggestions = arrayOf(
                "A_PRESSED","B_PRESSED","X_PRESSED","Y_PRESSED",
                "START","SELECT","UP","DOWN","LEFT","RIGHT"
            )
            setAdapter(
                ArrayAdapter(
                    context,
                    android.R.layout.simple_dropdown_item_1line,
                    suggestions
                )
            )
        }
        dlg.addView(etPayload)

        // Use the ScrollView as the dialog content
        AlertDialog.Builder(context).setTitle("Properties").setView(scrollView)
            .setPositiveButton("OK") { _, _ ->
                model.name = etName.text.toString()
                model.w = wSeek.progress.toFloat().coerceAtLeast(40f)
                model.h = hSeek.progress.toFloat().coerceAtLeast(40f)
                model.payload = etPayload.text.toString().trim()
                model.holdToggle = chkHold.isChecked
                model.holdDurationMs = etMs.text.toString().toLongOrNull() ?: 400L
                model.autoCenter = chkAuto.isChecked
                model.holdLeftWhileTouch = chkDrag.isChecked
                model.toggleLeftClick = chkToggleClick.isChecked
                model.swipeActivate = chkSwipe.isChecked
                sensSeek?.let { model.sensitivity = it.progress/100f }

                // Save directional mode settings if applicable
                if (model.type == ControlType.STICK) {
                    model.directionalMode = chkDirectional.isChecked

                    if (model.directionalMode) {
                        directionalContainer.findViewWithTag<EditText>("up_command")?.let {
                            model.upCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "W"
                        }
                        directionalContainer.findViewWithTag<EditText>("down_command")?.let {
                            model.downCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "S"
                        }
                        directionalContainer.findViewWithTag<EditText>("left_command")?.let {
                            model.leftCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "A"
                        }
                        directionalContainer.findViewWithTag<EditText>("right_command")?.let {
                            model.rightCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "D"
                        }
                        directionalContainer.findViewWithTag<SeekBar>("boost_threshold")?.let {
                            model.boostThreshold = 0.1f + (it.progress / 100f)
                        }
                        directionalContainer.findViewWithTag<EditText>("up_boost")?.let {
                            model.upBoostCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "W,SHIFT"
                        }
                        directionalContainer.findViewWithTag<EditText>("down_boost")?.let {
                            model.downBoostCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "S,CTRL"
                        }
                        directionalContainer.findViewWithTag<EditText>("left_boost")?.let {
                            model.leftBoostCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "A,SHIFT"
                        }
                        directionalContainer.findViewWithTag<EditText>("right_boost")?.let {
                            model.rightBoostCommand = it.text.toString().takeIf { it.isNotBlank() } ?: "D,SHIFT"
                        }
                    }
                }

                val lp = lp(); lp.width = model.w.toInt(); lp.height = model.h.toInt()
                layoutParams = lp; updateLabel(); invalidate()

                // Stop any running senders when settings change
                stopContinuousSending()
                stopDirectionalCommands()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}