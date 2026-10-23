package com.yundac.apkcloner

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.yundac.apkcloner.ui.theme.ThemeMode
import com.yundac.apkcloner.ui.theme.ThemePreferences

class ApkClonerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Apply saved night mode on startup
        val currentTheme = ThemePreferences.getThemeMode(this)
        ThemePreferences.applyNightMode(currentTheme)

        // Apply Material You dynamic color harmonisation across activities
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
