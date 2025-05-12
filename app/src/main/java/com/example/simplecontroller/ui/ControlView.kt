package com.example.simplecontroller.ui

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import com.example.simplecontroller.model.*
import com.example.simplecontroller.net.NetworkClient
import kotlin.math.*

class ControlView(
    context: Context,
    private val model: Control
) : FrameLayout(context) {

    /* ───────── companion ──────── */
    companion object {
        /* Edit-mode toggle */
        var editMode = false
            set(v) { field = v; _all.forEach { it.updateOverlay() } }

        /* ★ Global feature toggles (wired to MainActivity switches) ★ */
        var snapEnabled  = true    // "Snap" → auto-centre sticks / pads
        var globalHold   = false   // latch every button
        var globalTurbo  = false   // rapid-fire every button
        var globalSwipe  = false   // MainActivity intercepts swipe

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
                    return lastTouchedView != null
                }

                MotionEvent.ACTION_MOVE -> {
                    if (activeTouch == null) return false

                    // Find all controls under current touch point
                    for (view in _all) {
                        if (view == lastTouchedView) continue // Skip the initially touched view
                        if (view.model.type == ControlType.BUTTON && !view.model.swipeActivate) continue // Skip buttons with swipe activation disabled

                        val loc = IntArray(2)
                        view.getLocationOnScreen(loc)
                        val x = e.rawX - loc[0]
                        val y = e.rawY - loc[1]

                        if (x >= 0 && x < view.width && y >= 0 && y < view.height) {
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
                    return lastTouchedView != null
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
    override fun onDetachedFromWindow() { _all -= this; super.onDetachedFromWindow() }

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

    /* ───────── edit-drag / play-touch ───────── */
    private var dX = 0f; private var dY = 0f
    private var isLatched = false          // for Hold / globalHold
    private var leftHeld  = false          // for one-finger drag
    private var repeater  : Runnable? = null
    private val uiHandler = Handler(Looper.getMainLooper())

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
            ControlType.STICK -> handleStickOrPad(e)

            /* ----- TOUCHPAD (with one-finger drag) ----- */
            ControlType.TOUCHPAD -> {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (model.holdLeftWhileTouch) {
                            NetworkClient.send("MOUSE_LEFT_DOWN")   // adjust to protocol
                            leftHeld = true
                        }
                    }
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        handleStickOrPad(e)
                        if ((e.actionMasked == MotionEvent.ACTION_UP ||
                                    e.actionMasked == MotionEvent.ACTION_CANCEL) && leftHeld) {
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
        val (sx, sy) =
            if (snapEnabled && e.actionMasked != MotionEvent.ACTION_MOVE) 0f to 0f else nx to ny
        NetworkClient.send("${model.payload}:${"%.2f".format(sx)},${"%.2f".format(sy)}")
    }

    /* ───────── quick-actions ───── */
    private fun duplicateSelf() {
        val copy = model.copy(
            id = "${model.id}_copy_${System.currentTimeMillis()}",
            x  = model.x + 40f,
            y  = model.y + 40f
        )
        (context as? com.example.simplecontroller.MainActivity)
            ?.createControlFrom(copy)
    }
    private fun confirmDelete() {
        AlertDialog.Builder(context)
            .setMessage("Delete this control?")
            .setPositiveButton("Delete") { _, _ ->
                (parent as? FrameLayout)?.removeView(this)
                (context as? com.example.simplecontroller.MainActivity)
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
        val dlg = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32,24,32,8)
        }
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

        /* swipe activation option (for buttons) */
        val chkSwipe = CheckBox(context).apply {
            text = "Enable swipe activation"
            isChecked = model.swipeActivate
            visibility = if (model.type == ControlType.BUTTON) View.VISIBLE else View.GONE
        }
        dlg.addView(chkSwipe); dlg.addView(gap())

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

        AlertDialog.Builder(context).setTitle("Properties").setView(dlg)
            .setPositiveButton("OK") { _, _ ->
                model.name = etName.text.toString()
                model.w = wSeek.progress.toFloat().coerceAtLeast(40f)
                model.h = hSeek.progress.toFloat().coerceAtLeast(40f)
                model.payload = etPayload.text.toString().trim()
                model.holdToggle = chkHold.isChecked
                model.holdDurationMs = etMs.text.toString().toLongOrNull() ?: 400L
                model.autoCenter = chkAuto.isChecked
                model.holdLeftWhileTouch = chkDrag.isChecked
                model.swipeActivate = chkSwipe.isChecked
                sensSeek?.let { model.sensitivity = it.progress/100f }

                val lp = lp(); lp.width = model.w.toInt(); lp.height = model.h.toInt()
                layoutParams = lp; updateLabel(); invalidate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}