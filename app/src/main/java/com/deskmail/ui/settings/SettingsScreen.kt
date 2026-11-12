package com.deskmail.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.deskmail.R
import com.deskmail.core.log.AppLog
import com.deskmail.deskMail
import com.deskmail.mail.AccountActions
import com.deskmail.mail.MailAccount
import com.deskmail.mail.MailSource
import com.deskmail.settings.AppSettings
import com.deskmail.settings.WebLayer
import com.deskmail.ui.MailIcons
import com.deskmail.ui.MailScope
import com.deskmail.ui.shortcuts
import desktopkit.appResources
import desktopkit.components.DeskButton
import desktopkit.components.DeskIcons
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings: the accounts, writing, the kit's own sections (theme and accent, density, the
 * shortcuts), then the rest of the app's. The log and the account pages open over them, as a
 * message opens over its list.
 *
 * @param mail where the accounts are listed from.
 * @param accounts adds, reconnects and removes accounts; null where there is nothing to do so with.
 * @param ui which page is open and what was typed; the window keeps it.
 * @param scope where the servers are asked; it outlives this screen.
 * @param onNotify shows a short notice (what was copied, what could not be shared).
 * @param onAccountAdded an account was added, by its address: its mail is about to arrive.
 */
@Composable
fun SettingsScreen(
    prefs: DeskPreferences,
    settings: AppSettings,
    log: AppLog,
    mail: MailSource,
    accounts: AccountActions?,
    ui: SettingsUiState,
    scope: CoroutineScope,
    onNotify: (String) -> Unit,
    onAccountAdded: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = appResources()
    // an account's page only while the account is there: removed (here or in another window), its page closes
    val page = ui.page?.takeIf { it !is SettingsPage.Account || mail.account(it.id) != null }
    DetailTransition(
        detail = page,
        onBack = ui::close,
        list = {
            SettingsList(
                prefs = prefs,
                settings = settings,
                accounts = mail.accounts,
                canAdd = accounts != null,
                state = ui.list,
                onOpenLog = { ui.page = SettingsPage.Log },
                onOpenAccount = { ui.page = SettingsPage.Account(it.id) },
                onAddAccount = ui::addAccount,
            )
        },
        sharedElements = false,
    ) { open ->
        when (open) {
            SettingsPage.Log -> LogScreen(
                log = log,
                onClose = ui::close,
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
            SettingsPage.AddAccount -> if (accounts != null) {
                AccountSetupPage(ui.setup, accounts, scope, onClose = ui::close, onAdded = onAccountAdded)
            }
            is SettingsPage.Account -> {
                val account = mail.account(open.id)
                if (account != null && accounts != null) {
                    AccountPage(
                        account = account,
                        actions = accounts,
                        scope = scope,
                        onClose = ui::close,
                        onSync = { mail.refresh() },
                        onNotify = onNotify,
                        onRemoved = { name ->
                            ui.close()
                            onNotify(resources.getString(R.string.account_removed, name))
                        },
                    )
                }
            }
        }
    }
}

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
private fun SettingsList(
    prefs: DeskPreferences,
    settings: AppSettings,
    accounts: List<MailAccount>,
    canAdd: Boolean,
    state: LazyListState,
    onOpenLog: () -> Unit,
    onOpenAccount: (MailAccount) -> Unit,
    onAddAccount: () -> Unit,
) {
    val keyboard = showShortcutHints()
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = rememberShellContentPadding()) {
        item(key = "header") { PageHeader(stringResource(desktopkit.R.string.dk_settings)) }
        item(key = "accounts") { AccountsSection(accounts, canAdd, onOpenAccount, onAddAccount) }
        item(key = "writing") { WritingSection(settings) }
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

/** The accounts with how each is doing, the way to each one's page, and "Add an account". */
@Composable
private fun AccountsSection(accounts: List<MailAccount>, canAdd: Boolean, onOpen: (MailAccount) -> Unit, onAdd: () -> Unit) {
    SettingsSection(stringResource(R.string.settings_accounts)) {
        if (accounts.isEmpty()) {
            Text(stringResource(R.string.settings_accounts_empty), style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary)
        }
        accounts.forEach { account ->
            SettingRow(account.name, subtitle = stringResource(R.string.account_summary, account.address, stateLabel(account.state))) {
                DeskButton(stringResource(R.string.account_manage), { onOpen(account) })
            }
        }
        if (canAdd) {
            DeskButton(stringResource(R.string.account_add), onAdd, Modifier.padding(top = DeskTheme.dimens.gapS), icon = DeskIcons.Plus, primary = accounts.isEmpty())
        }
    }
}

/** Writing: how long a message waits after "Send", which is the time it can be taken back in. */
@Composable
private fun WritingSection(settings: AppSettings) {
    SettingsSection(stringResource(R.string.settings_writing)) {
        SettingRow(stringResource(R.string.settings_undo_send), subtitle = stringResource(R.string.settings_undo_send_note)) {
            SegmentedChoice(
                options = AppSettings.UNDO_SEND_CHOICES.map { seconds -> seconds to stringResource(R.string.settings_undo_send_seconds, seconds) },
                selected = settings.undoSendSeconds,
                onSelect = settings::updateUndoSendSeconds,
            )
        }
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
 * What the first pre-releases are to find out on the devices: does the glass over a message leave
 * the message alone, does a message read well under the bars, does the darkening suit the mail.
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
            title = stringResource(R.string.experiment_glass_over_messages),
            checked = settings.glassOverMessages,
            onCheckedChange = settings::updateGlassOverMessages,
            subtitle = stringResource(R.string.experiment_glass_over_messages_note),
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
                    WebLayer.None to stringResource(R.string.web_layer_none),
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
