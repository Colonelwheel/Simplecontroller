package com.example.simplecontroller.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.children
import com.example.simplecontroller.R
import kotlin.math.roundToInt

/** Presentation only: never replaces fields, parents, listeners, values, or visibility. */
internal class PropertySheetStyle(private val context: Context) {
    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private fun surface(fill: Int, outline: Int = R.color.ui_outline) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(color(fill))
        setStroke(dp(1), color(outline))
    }

    private fun textColors(normal: Int = R.color.ui_text) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(color(R.color.ui_text_muted), color(normal))
    )

    fun title(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(12))
        addView(TextView(context).apply {
            setText(R.string.property_sheet_title)
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.ui_text))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        addView(TextView(context).apply {
            setText(R.string.property_sheet_subtitle)
            textSize = 14f
            setTextColor(color(R.color.ui_text_muted))
            setPadding(0, dp(4), 0, 0)
        })
    }

    fun sectionTitle(container: LinearLayout, title: String) {
        container.addView(TextView(context).apply {
            text = title
            setTag(R.id.property_sheet_heading, true)
            ViewCompat.setAccessibilityHeading(this, true)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.ui_accent))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = surface(R.color.ui_surface_raised)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(16)
            bottomMargin = dp(10)
        })
    }

    fun styleContent(view: View) {
        when (view) {
            is EditText -> {
                view.setTextColor(textColors())
                view.setHintTextColor(color(R.color.ui_text_muted))
                view.textSize = 16f
                view.minHeight = dp(52)
                view.setPadding(dp(12), dp(12), dp(12), dp(12))
                view.backgroundTintList = null
                view.background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), surface(R.color.ui_surface_raised, R.color.ui_accent))
                    addState(intArrayOf(), surface(R.color.ui_surface_raised))
                }
            }
            is CompoundButton -> {
                view.setTextColor(textColors())
                view.textSize = 16f
                view.minHeight = dp(52)
                view.setPadding(dp(4), dp(8), dp(8), dp(8))
                view.buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(color(R.color.ui_outline), color(R.color.ui_accent), color(R.color.ui_text_muted))
                )
            }
            is Button -> button(view)
            is Spinner -> {
                view.minimumHeight = dp(52)
                // Tint the native background so its dropdown affordance remains intact.
                view.backgroundTintList = ColorStateList.valueOf(color(R.color.ui_accent))
            }
            is SeekBar -> {
                view.minimumHeight = dp(48)
                view.progressTintList = ColorStateList.valueOf(color(R.color.ui_accent))
                view.thumbTintList = ColorStateList.valueOf(color(R.color.ui_accent))
                view.progressBackgroundTintList = ColorStateList.valueOf(color(R.color.ui_outline))
            }
            is TextView -> if (view.getTag(R.id.property_sheet_heading) != true) {
                view.textSize = 14f
                view.setTextColor(textColors(R.color.ui_text_muted))
                view.setLineSpacing(dp(2).toFloat(), 1f)
                view.setPadding(view.paddingLeft, dp(6), view.paddingRight, dp(6))
            }
        }
        // Adapter-owned spinner children are styled by their item layout.
        if (view is ViewGroup && view !is Spinner) view.children.forEach(::styleContent)
    }

    fun button(button: Button, primary: Boolean = false, destructive: Boolean = false) {
        val foreground = when {
            primary -> R.color.ui_on_accent
            destructive -> R.color.ui_danger
            else -> R.color.ui_accent
        }
        val fill = if (primary) R.color.ui_accent else R.color.ui_surface_raised
        button.isAllCaps = false
        button.textSize = 16f
        button.minHeight = maxOf(button.minHeight, dp(52))
        button.setTextColor(textColors(foreground))
        button.setPadding(dp(14), dp(10), dp(14), dp(10))
        button.backgroundTintList = null
        button.background = RippleDrawable(
            ColorStateList.valueOf(color(R.color.ui_outline)),
            surface(fill, if (primary) R.color.ui_accent else R.color.ui_outline),
            null
        )
    }

    fun dialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(surface(R.color.ui_surface))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let {
            button(it, primary = true)
            (it.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.marginStart = dp(8)
                it.layoutParams = params
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { button(it) }
    }
}
