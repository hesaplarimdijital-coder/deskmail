package com.deskmail.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.deskmail.R
import com.deskmail.core.log.AppLog
import com.deskmail.deskMail
import com.deskmail.settings.AppSettings
import com.deskmail.settings.WebLayer
import com.deskmail.ui.MailIcons
import com.deskmail.ui.MailScope
import com.deskmail.ui.shortcuts
import desktopkit.appResources
import desktopkit.components.DeskButton
import desktopkit.components.KeyValueRow
import desktopkit.components.SegmentedChoice
import desktopkit.components.SettingRow
import desktopkit.components.SwitchRow
import desktopkit.input.showShortcutHints
import desktopkit.screens.PageHeader
import desktopkit.settings.AppearanceSection
import desktopkit.settings.DeskPreferences
import desktopkit.settings.InputSection
import desktopkit.settings.SettingsSection
import desktopkit.settings.ShortcutListSection
import desktopkit.shell.DetailTransition
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings: the kit's own sections (theme and accent, density, the shortcuts), then the app's.
 * The log opens over them, as a message opens over its list.
 *
 * @param onNotify shows a short notice (what was copied, what could not be shared).
 */
@Composable
fun SettingsScreen(prefs: DeskPreferences, settings: AppSettings, log: AppLog, onNotify: (String) -> Unit) {
    var logOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val resources = appResources()
    val scope = rememberCoroutineScope()
    DetailTransition(
        detail = if (logOpen) LogPage else null,
        onBack = { logOpen = false },
        list = { SettingsList(prefs, settings, onOpenLog = { logOpen = true }) },
        sharedElements = false,
    ) {
        LogScreen(
            log = log,
            onClose = { logOpen = false },
            onCopy = { lines ->
                // the end of the log: what fits a message in a chat
                val latest = lines.takeLast(COPIED_LINES)
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.log_share_title), latest.joinToString("\n")))
                onNotify(resources.getQuantityString(R.plurals.log_copied, latest.size, latest.size))
            },
            onShare = {
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) { context.deskMail.exportLog() }
                        shareLog(context, file, resources.getString(R.string.log_share_title))
                    } catch (e: Exception) {
                        log.warn("log", "the log could not be shared", e)
                        onNotify(resources.getString(R.string.log_share_failed))
                    }
                }
            },
            onClear = {
                context.deskMail.clearLog()
                onNotify(resources.getString(R.string.log_cleared))
            },
        )
    }
}

/** The log as the settings' detail page. */
private object LogPage

/** How many lines "Copy" takes from the end of the log. */
private const val COPIED_LINES = 400

/** Hands [file] to the app the user picks, with leave to read that one file. */
private fun shareLog(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // the grant travels with the clip data; Android no longer adds it by itself
    send.clipData = ClipData.newRawUri(title, uri)
    context.startActivity(Intent.createChooser(send, title))
}

@Composable
private fun SettingsList(prefs: DeskPreferences, settings: AppSettings, onOpenLog: () -> Unit) {
    val keyboard = showShortcutHints()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = rememberShellContentPadding()) {
        item(key = "header") { PageHeader(stringResource(desktopkit.R.string.dk_settings)) }
        item(key = "appearance") { AppearanceSection(prefs) }
        item(key = "input") { InputSection(prefs) }
        // the list of shortcuts only where there is a keyboard to use them with
        if (keyboard) {
            item(key = "shortcuts") {
                ShortcutListSection(shortcuts, scopeLabel = { scope -> if (scope == MailScope) stringResource(R.string.shortcut_scope_mail) else null })
            }
        }
        item(key = "experiments") { ExperimentsSection(settings) }
        item(key = "log") { LogSection(onOpenLog) }
        item(key = "about") { AboutSection() }
    }
}

/** The way to the log: what to open, and to share, when something breaks. */
@Composable
private fun LogSection(onOpen: () -> Unit) {
    SettingsSection(stringResource(R.string.settings_log)) {
        Text(stringResource(R.string.settings_log_note), style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary)
        DeskButton(stringResource(R.string.settings_log_open), onOpen, Modifier.padding(top = DeskTheme.dimens.gapS), icon = MailIcons.Log)
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
