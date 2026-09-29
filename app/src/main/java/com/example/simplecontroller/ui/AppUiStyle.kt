package com.example.simplecontroller.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import androidx.core.content.ContextCompat
import com.example.simplecontroller.R

/** App chrome only. Never applied to user-created controller views. */
object AppUiStyle {
    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun surface(context: Context, fill: Int = R.color.ui_surface_raised, radius: Int = 14) =
        GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, fill))
            cornerRadius = dp(context, radius).toFloat()
            setStroke(dp(context, 1), ContextCompat.getColor(context, R.color.ui_outline))
        }

    fun ripple(context: Context, fill: Int = R.color.ui_surface_raised, radius: Int = 14) =
        RippleDrawable(
            ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ui_ripple)),
            surface(context, fill, radius),
            null
        )

    fun button(button: Button, primary: Boolean = false) {
        val context = button.context
        button.apply {
            alpha = 1f
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minHeight = dp(context, 48)
            minimumHeight = dp(context, 48)
            minWidth = dp(context, 88)
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(
                    ContextCompat.getColor(context, R.color.ui_text_muted),
                    ContextCompat.getColor(context, if (primary) R.color.ui_on_accent else R.color.ui_text)
                )
            ))
            backgroundTintList = null
            background = ripple(context, if (primary) R.color.ui_accent else R.color.ui_surface_raised)
            elevation = 0f
            stateListAnimator = null
        }
    }
}
