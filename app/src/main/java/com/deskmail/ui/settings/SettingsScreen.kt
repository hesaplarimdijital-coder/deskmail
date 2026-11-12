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
import com.deskmail.settings.AppSettings
import com.deskmail.settings.WebLayer
import desktopkit.components.KeyValueRow
import desktopkit.components.SegmentedChoice
import desktopkit.components.SettingRow
import desktopkit.components.SwitchRow
import desktopkit.screens.PageHeader
import desktopkit.settings.AppearanceSection
import desktopkit.settings.DeskPreferences
import desktopkit.settings.InputSection
import desktopkit.settings.SettingsSection
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme

/** Settings: the kit's own sections (theme and accent, density), then the app's. */
@Composable
fun SettingsScreen(prefs: DeskPreferences, settings: AppSettings) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = rememberShellContentPadding()) {
        item(key = "header") { PageHeader(stringResource(desktopkit.R.string.dk_settings)) }
        item(key = "appearance") { AppearanceSection(prefs) }
        item(key = "input") { InputSection(prefs) }
        item(key = "experiments") { ExperimentsSection(settings) }
        item(key = "about") { AboutSection() }
    }
}

/**
 * What the first pre-releases are to find out on the devices: does the glass blur a message
 * beneath it, does a message read well under the bars, does the darkening suit the mail.
 */
@Composable
private fun ExperimentsSection(settings: AppSettings) {
    SettingsSection(stringResource(R.string.settings_experiments)) {
        Text(stringResource(R.string.settings_experiments_note), style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary)
        SwitchRow(
            title = stringResource(R.string.experiment_glass),
            checked = settings.glass,
            onCheckedChange = settings::updateGlass,
            subtitle = stringResource(R.string.experiment_glass_note),
        )
        SwitchRow(
            title = stringResource(R.string.experiment_under_bars),
            checked = settings.readerUnderBars,
            onCheckedChange = settings::updateReaderUnderBars,
            subtitle = stringResource(R.string.experiment_under_bars_note),
        )
        SwitchRow(
            title = stringResource(R.string.experiment_darken),
            checked = settings.darkenMessages,
            onCheckedChange = settings::updateDarkenMessages,
            subtitle = stringResource(R.string.experiment_darken_note),
        )
        SettingRow(stringResource(R.string.experiment_web_layer), subtitle = stringResource(R.string.experiment_web_layer_note)) {
            SegmentedChoice(
                options = listOf(
                    WebLayer.Default to stringResource(R.string.web_layer_default),
                    WebLayer.Hardware to stringResource(R.string.web_layer_hardware),
                    WebLayer.Software to stringResource(R.string.web_layer_software),
                ),
                selected = settings.webLayer,
                onSelect = settings::updateWebLayer,
            )
        }
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
