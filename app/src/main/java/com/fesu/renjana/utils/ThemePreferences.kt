package com.fesu.renjana.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.fesu.renjana.ui.theme.AccentBlue

object ThemePreferences {
    private const val PREFS_NAME = "renjana_prefs"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_ACCENT_COLOR = "accent_color"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isDarkMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DARK_MODE, false)
    }

    fun setDarkMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DARK_MODE, enabled).apply()
    }

    fun getAccentColor(context: Context): Color {
        val argb = getPrefs(context).getInt(KEY_ACCENT_COLOR, AccentBlue.toArgb())
        return Color(argb)
    }

    fun setAccentColor(context: Context, color: Color) {
        getPrefs(context).edit().putInt(KEY_ACCENT_COLOR, color.toArgb()).apply()
    }
}
