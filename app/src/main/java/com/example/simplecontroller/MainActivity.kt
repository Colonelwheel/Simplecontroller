package com.example.simplecontroller

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import com.example.simplecontroller.io.*
import com.example.simplecontroller.model.*
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.ui.ControlView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    /* ---------- persisted “current layout” name ---------- */
    private val prefs     by lazy { getSharedPreferences("layout", MODE_PRIVATE) }
    private var layoutName: String
        get() = prefs.getString("current", "default") ?: "default"
        set(v) = prefs.edit().putString("current", v).apply()

    /* ---------- underlying data model ---------- */
    private val controls: MutableList<Control> by lazy {
        loadControls(this, layoutName)?.toMutableList()
            ?: defaultLayout().toMutableList()
    }

    /* ---------- edit-only widgets we show/hide ---------- */
    private lateinit var btnSave:    View
    private lateinit var btnLoad:    View
    private lateinit var switchSnap: View
    private lateinit var fabAdd:     View       // NEW ( ➕ )

    /* ---------- main canvas ---------- */
    private lateinit var canvas: FrameLayout

    // =============== life-cycle ======================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        canvas = findViewById(R.id.canvas)

        spawnControlViews()

        /* --- Edit toggle ------------------------------------------------ */
        addCornerButton("Edit", Gravity.TOP or Gravity.END) { v ->
            ControlView.editMode = !ControlView.editMode
            (v as Button).text   = if (ControlView.editMode) "Done" else "Edit"
            updateEditUi(ControlView.editMode)
        }

        /* --- Snap switch ------------------------------------------------- */
        switchSnap = Switch(this).apply {
            text = "Snap"; alpha = 0.7f; isChecked = true
            setOnCheckedChangeListener { _, on ->
                controls.filter { it.type != ControlType.BUTTON }
                    .forEach { it.autoCenter = on }
            }
        }
        addViewToCanvas(switchSnap, Gravity.TOP or Gravity.START, 16, 16)

        /* --- Save / Load ------------------------------------------------- */
        btnSave = addCornerButton("Save", Gravity.BOTTOM or Gravity.START) { saveDialog() }
        btnLoad = addCornerButton("Load", Gravity.BOTTOM or Gravity.END)  { loadDialog() }

        /* --- NEW ➕  Add-FAB -------------------------------------------- */
        fabAdd = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            alpha = 0.85f
            setOnClickListener { showAddPicker() }
        }
        addViewToCanvas(fabAdd, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 90)

        updateEditUi(ControlView.editMode)   // hide edit widgets by default
    }

    override fun onStart() { super.onStart(); NetworkClient.start() }

    override fun onPause() {
        super.onPause()
        saveControls(this, layoutName, controls)   // auto-persist
        NetworkClient.close()
    }

    // =============== helpers ===========================================
    private fun spawnControlViews() =
        controls.forEach { canvas.addView(ControlView(this, it).apply { tag = "control" }) }

    private fun clearControlViews() =
        canvas.children.filter { it.tag == "control" }.toList()
            .forEach { canvas.removeView(it) }

    /* corner helper ---------------------------------------------------- */
    private fun addCornerButton(label: String, gravity: Int, onClick: (View) -> Unit) =
        Button(this).apply {
            text = label; alpha = 0.7f; setOnClickListener(onClick)
            addViewToCanvas(this, gravity, 16, 16)
        }

    /* generic add-to-canvas helper ------------------------------------ */
    private fun addViewToCanvas(v: View, gravity: Int, marginH: Int, marginV: Int) {
        canvas.addView(
            v, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity
            ).apply { setMargins(marginH, marginV, marginH, marginV) }
        )
    }

    /* -------- show / hide widgets depending on Edit-mode ------------- */
    private fun updateEditUi(edit: Boolean) {
        val vis = if (edit) View.VISIBLE else View.GONE
        btnSave.visibility    = vis
        btnLoad.visibility    = vis
        switchSnap.visibility = vis
        fabAdd.visibility     = vis
    }

    // =============== “Add Control” flow ===============================
    private fun showAddPicker() {
        val types = arrayOf("Button", "Stick", "TouchPad")
        AlertDialog.Builder(this)
            .setTitle("Add control…")
            .setItems(types) { _, i ->
                val type = when (i) {
                    0 -> ControlType.BUTTON
                    1 -> ControlType.STICK
                    else -> ControlType.TOUCHPAD
                }
                createControl(type)
            }
            .show()
    }

    /** actually instantiate & drop one control on screen */
    fun createControl(type: ControlType) {
        val w  = if (type == ControlType.BUTTON) 140f else 220f
        val id = "${type.name.lowercase()}_${System.currentTimeMillis()}"

        /* robust centre-of-screen position (fallback → 80 px) */
        val cw = (canvas.width.takeIf { it > 0 } ?: canvas.measuredWidth).coerceAtLeast(1)
        val ch = (canvas.height.takeIf { it > 0 } ?: canvas.measuredHeight).coerceAtLeast(1)
        val x0 = ((cw - w) / 2f).coerceAtLeast(80f)
        val y0 = ((ch - w) / 2f).coerceAtLeast(80f)

        val c = Control(
            id = id, type = type,
            x = x0, y = y0, w = w, h = w,
            payload = type.defaultPayload()
        )
        controls += c

        val view = ControlView(this, c)
        canvas.addView(view)

        /* open its property sheet right away */
        view.post { view.showProps() }
    }

    // =============== dialogs (save / load) ============================
    private fun saveDialog() {
        val input = EditText(this).apply { hint = "layout name" }
        AlertDialog.Builder(this)
            .setTitle("Save layout as…")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    saveControls(this, name, controls); layoutName = name
                    toast("Saved as \"$name\"")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadDialog() {
        val names = listLayouts(this)
        if (names.isEmpty()) { toast("No saved layouts"); return }
        AlertDialog.Builder(this)
            .setTitle("Load layout")
            .setItems(names.toTypedArray()) { _, i ->
                val sel = names[i]
                loadControls(this, sel)?.let {
                    clearControlViews(); controls.clear(); controls += it
                    spawnControlViews(); layoutName = sel
                    toast("Loaded \"$sel\"")
                } ?: toast("Failed to load \"$sel\"")
            }
            .show()
    }

    /* tiny convenience ------------------------------------------------ */
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /* ---------- initial hard-coded layout (unchanged) ---------------- */
    private fun defaultLayout() = listOf(
        Control(
            id = "btnA", type = ControlType.BUTTON,
            x = 300f, y = 900f, w = 140f, h = 140f,
            payload = "BUTTON_A_PRESSED"
        ),
        Control(
            id = "stickL", type = ControlType.STICK,
            x = 80f, y = 600f, w = 220f, h = 220f,
            payload = "STICK_L"
        )
    )
}
