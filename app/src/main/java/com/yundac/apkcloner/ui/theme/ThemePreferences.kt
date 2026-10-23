package com.yundac.apkcloner.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yundac.apkcloner.R

enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    AMOLED("amoled");

    companion object {
        fun fromKey(key: String?): ThemeMode = values().firstOrNull { it.key == key } ?: SYSTEM
    }
}

object ThemePreferences {
    private const val PREFS_NAME = "apk_cloner_theme"
    private const val KEY_THEME = "theme_mode"

    fun getThemeMode(context: Context): ThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ThemeMode.fromKey(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.key))
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, mode.key)
            .apply()
        applyNightMode(mode)
    }

    fun applyNightMode(mode: ThemeMode) {
        val nightMode = when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.AMOLED -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    /**
     * Must be called in Activity.onCreate() BEFORE setContentView()
     * to apply AMOLED style override if active.
     */
    fun applyActivityTheme(activity: Activity) {
        val mode = getThemeMode(activity)
        if (mode == ThemeMode.AMOLED) {
            val isNight = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (isNight) {
                activity.setTheme(R.style.Theme_ApkCloner_Amoled)
            }
        }
    }

    fun showThemeDialog(activity: Activity, onThemeChanged: () -> Unit = {}) {
        val current = getThemeMode(activity)
        val modes = ThemeMode.values()
        val items = arrayOf(
            activity.getString(R.string.theme_system),
            activity.getString(R.string.theme_light),
            activity.getString(R.string.theme_dark),
            activity.getString(R.string.theme_amoled),
        )
        val selectedIndex = modes.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.theme_dialog_title)
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                val newMode = modes[which]
                if (newMode != current) {
                    setThemeMode(activity, newMode)
                    onThemeChanged()
                    activity.recreate()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
