package com.nseassist.data.local

import android.content.Context

enum class ThemeMode { SYSTEM, LIGHT, DARK }

class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ThemeMode =
        ThemeMode.values().firstOrNull { it.name == prefs.getString(KEY, null) } ?: ThemeMode.SYSTEM

    fun save(mode: ThemeMode) = prefs.edit().putString(KEY, mode.name).apply()

    companion object {
        private const val PREFS_NAME = "app_appearance"
        private const val KEY = "theme_mode"
    }
}
