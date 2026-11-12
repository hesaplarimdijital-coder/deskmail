package com.deskmail.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.deskmail.R
import desktopkit.components.KeyValueRow
import desktopkit.screens.PageHeader
import desktopkit.settings.AppearanceSection
import desktopkit.settings.DeskPreferences
import desktopkit.settings.InputSection
import desktopkit.settings.SettingsSection
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme

/** Settings: the kit's own sections (theme and accent, density), then the app's. */
@Composable
fun SettingsScreen(prefs: DeskPreferences) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = rememberShellContentPadding()) {
        item(key = "header") { PageHeader(stringResource(desktopkit.R.string.dk_settings)) }
        item(key = "appearance") { AppearanceSection(prefs) }
        item(key = "input") { InputSection(prefs) }
        item(key = "about") { AboutSection() }
    }
}

/** The installed version: what to quote when something goes wrong. */
@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val version = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }
    SettingsSection(stringResource(R.string.settings_about)) {
        KeyValueRow(stringResource(R.string.settings_version), version, selectable = true)
        Text(stringResource(R.string.settings_about_note), style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary)
    }
}
