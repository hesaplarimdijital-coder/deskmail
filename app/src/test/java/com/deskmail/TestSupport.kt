package com.deskmail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import desktopkit.settings.DeskPreferences
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode

/**
 * The kit's preferences pinned for a test: the theme and the density do not follow the test
 * machine (which has no mouse, and no dark mode of its own).
 */
fun testPrefs(theme: ThemeMode = ThemeMode.Dark, density: DensityPreference = DensityPreference.Touch): DeskPreferences {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return DeskPreferences(context.getSharedPreferences("test-desktopkit", Context.MODE_PRIVATE)).apply {
        updateThemeMode(theme)
        updateDensity(density)
    }
}
