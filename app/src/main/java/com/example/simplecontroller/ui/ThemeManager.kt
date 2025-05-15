package com.example.simplecontroller.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.example.simplecontroller.R

/**
 * Manages theme colors and settings for the app.
 * Provides colors for controls and UI elements and handles dark mode preferences.
 */
object ThemeManager {
    // Color constants for control elements in dark mode theme
    val BUTTON_COLOR get() = R.color.button_blue
    val BUTTON_PRESSED_COLOR get() = R.color.button_pressed_blue
    val STICK_COLOR get() = R.color.stick_blue
    val STICK_PRESSED_COLOR get() = R.color.stick_pressed_blue
    val TOUCHPAD_COLOR get() = R.color.touchpad_blue
    val RECENTER_COLOR get() = R.color.recenter_orange
    val RECENTER_PRESSED_COLOR get() = R.color.recenter_pressed_orange

    // Preference constants
    private const val THEME_PREFS = "theme_preferences"
    private const val DARK_MODE_ENABLED = "dark_mode_enabled"

    // Initialize with dark mode by default
    private var isDarkModeEnabled = true

    /**
     * Initialize theme settings from SharedPreferences
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
        isDarkModeEnabled = prefs.getBoolean(DARK_MODE_ENABLED, true)
        applyTheme()
    }

    /**
     * Apply the current theme setting to the app
     */
    private fun applyTheme() {
        val mode = if (isDarkModeEnabled) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /**
     * Toggle between dark and light mode
     */
    fun toggleDarkMode(context: Context) {
        isDarkModeEnabled = !isDarkModeEnabled

        // Save preference
        context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DARK_MODE_ENABLED, isDarkModeEnabled)
            .apply()

        // Apply theme
        applyTheme()
    }

    /**
     * Check if dark mode is currently enabled
     */
    fun isDarkModeEnabled(): Boolean {
        return isDarkModeEnabled
    }

    /**
     * Get color from resources with theme awareness
     */
    fun getColor(context: Context, colorResId: Int): Int {
        return ContextCompat.getColor(context, colorResId)
    }

    /**
     * Get text color based on theme
     */
    fun getTextColor(context: Context): Int {
        return ContextCompat.getColor(context, R.color.dark_text_primary)
    }

    /**
     * Get text shadow color
     */
    fun getTextShadowColor(): Int {
        return Color.BLACK
    }
}