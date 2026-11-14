package com.deskmail.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.FileProvider
import com.deskmail.R
import com.deskmail.core.log.AppLog
import com.deskmail.core.provider.MicrosoftSignIn
import com.deskmail.deskMail
import com.deskmail.mail.AccountActions
import com.deskmail.mail.MailAccount
import com.deskmail.mail.MailComposer
import com.deskmail.mail.MailSource
import com.deskmail.mail.MessageTemplate
import com.deskmail.mail.SavedSearch
import com.deskmail.mail.SenderMarks
import com.deskmail.notify.NotifyGate
import com.deskmail.settings.AppSettings
import com.deskmail.settings.SettingsFile
import com.deskmail.ui.MailIcons
import com.deskmail.ui.MailScope
import com.deskmail.ui.compose.TemplateDialog
import com.deskmail.ui.shortcuts
import desktopkit.appResources
import desktopkit.components.DeskButton
import desktopkit.components.DeskDialog
import desktopkit.components.DeskIcons
import desktopkit.components.DeskTextField
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
import java.time.Instant

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
 * @param notifications the system's leave to show notifications, and the way to ask for it; null
 *   where there is nobody to ask.
 * @param onCheckChosen the user chose how often the mail is looked for in the background.
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
    notifications: NotifyGate? = null,
    onCheckChosen: () -> Unit = {},
    composer: MailComposer? = null,
) {
    val context = LocalContext.current
    val resources = appResources()
    // an account's page only while the account is there: removed (here or in another window), its page closes
    val page = ui.page?.takeIf { it !is SettingsPage.Account || mail.account(it.id) != null }
    // a saved search being changed
    var search by remember { mutableStateOf<SavedSearch?>(null) }
    search?.let { shown ->
        SavedSearchDialog(
            search = shown,
            onDismiss = { search = null },
            onSave = { name, query ->
                search = null
                scope.launch { mail.saveSearch(shown.id, name, query) }
            },
            onDelete = {
                search = null
                scope.launch { mail.removeSearch(shown.id) }
            },
        )
    }
    // a kept text being changed, or a new one (id 0) being written
    var template by remember { mutableStateOf<MessageTemplate?>(null) }
    val shownTemplate = template
    if (shownTemplate != null && composer != null) {
        val removed = stringResource(R.string.template_removed)
        TemplateDialog(
            title = stringResource(if (shownTemplate.id == 0L) R.string.template_new else R.string.template_edit_title),
            initialName = shownTemplate.name,
            initialBody = shownTemplate.body,
            onDismiss = { template = null },
            onSave = { name, body ->
                template = null
                scope.launch { composer.saveTemplate(shownTemplate.id, name, body) }
            },
            onDelete = if (shownTemplate.id == 0L) null else ({
                template = null
                scope.launch {
                    composer.removeTemplate(shownTemplate.id)
                    onNotify(removed)
                }
            }),
        )
    }
    // The settings to another device: written to a file the user picks a place for, and read from
    // one picked on the other device. The work is the screen's scope's: the screen may go meanwhile.
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SettingsFile.MIME_TYPE)) { uri ->
        if (uri != null && accounts != null) {
            scope.launch {
                val written = try {
                    val contents = SettingsFile.Contents(
                        accounts = accounts.portable(),
                        settings = settings.portable(),
                        look = SettingsFile.lookOf(prefs),
                        templates = composer?.templates.orEmpty(),
                        searches = mail.savedSearches,
                        senders = mail.senders,
                    )
                    val text = SettingsFile.write(contents, Instant.now())
                    withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) } } != null
                } catch (e: Exception) {
                    log.warn("settings", "the settings could not be written to a file", e)
                    false
                }
                if (written) log.info("settings", "the settings were written to a file")
                onNotify(resources.getString(if (written) R.string.transfer_exported else R.string.transfer_export_failed))
            }
        }
    }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && accounts != null) {
            scope.launch {
                val contents = try {
                    withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { SettingsFile.read(it) } }
                } catch (e: Exception) {
                    log.warn("settings", "a settings file could not be read", e)
                    null
                }
                if (contents == null) {
                    onNotify(resources.getString(R.string.transfer_not_a_file))
                    return@launch
                }
                val added = accounts.adopt(contents.accounts)
                if (settings.adopt(contents.settings)) onCheckChosen()
                contents.look?.let { SettingsFile.applyLook(prefs, it) }
                // the user's own lists: what this device has already is not kept twice
                if (composer != null) {
                    val kept = composer.templates.map { it.name to it.body }.toSet()
                    contents.templates.filter { (it.name to it.body) !in kept }.forEach { composer.saveTemplate(0, it.name, it.body) }
                }
                val searched = mail.savedSearches.map { it.query }.toSet()
                contents.searches.filter { it.query !in searched }.forEach { mail.saveSearch(0, it.name, it.query) }
                contents.senders.forEach { (address, marks) ->
                    val known = mail.sender(address)
                    mail.markSender(address, SenderMarks(vip = known.vip || marks.vip, images = known.images || marks.images))
                }
                log.info("settings", "a settings file was read: $added of its ${contents.accounts.size} accounts were added")
                onNotify(
                    if (added > 0) resources.getQuantityString(R.plurals.transfer_imported, added, added)
                    else resources.getString(R.string.transfer_imported_none),
                )
            }
        }
    }
    val fileName = stringResource(R.string.transfer_file_name)
    DetailTransition(
        detail = page,
        onBack = ui::close,
        list = {
            SettingsList(
                prefs = prefs,
                settings = settings,
                accounts = mail.accounts,
                canAdd = accounts != null,
                notifications = notifications,
                onCheckChosen = onCheckChosen,
                state = ui.list,
                onOpenLog = { ui.page = SettingsPage.Log },
                onOpenLicences = { ui.page = SettingsPage.Licences },
                onOpenAccount = { ui.page = SettingsPage.Account(it.id) },
                onAddAccount = ui::addAccount,
                templates = composer?.templates,
                onTemplate = { template = it },
                searches = mail.savedSearches,
                onSearch = { search = it },
                senders = mail.senders,
                onSender = { address, marks -> scope.launch { mail.markSender(address, marks) } },
                onExport = if (accounts == null) null else ({ exportTo.launch(fileName) }),
                onImport = if (accounts == null) null else ({ importFrom.launch(SettingsFile.OPEN_TYPES) }),
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
            SettingsPage.Licences -> LicencesScreen(onClose = ui::close)
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
    notifications: NotifyGate?,
    onCheckChosen: () -> Unit,
    state: LazyListState,
    onOpenLog: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenAccount: (MailAccount) -> Unit,
    onAddAccount: () -> Unit,
    templates: List<MessageTemplate>?,
    onTemplate: (MessageTemplate) -> Unit,
    searches: List<SavedSearch>,
    onSearch: (SavedSearch) -> Unit,
    senders: Map<String, SenderMarks>,
    onSender: (String, SenderMarks) -> Unit,
    onExport: (() -> Unit)?,
    onImport: (() -> Unit)?,
) {
    val keyboard = showShortcutHints()
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = rememberShellContentPadding()) {
        item(key = "header") { PageHeader(stringResource(desktopkit.R.string.dk_settings)) }
        item(key = "accounts") { AccountsSection(accounts, canAdd, onOpenAccount, onAddAccount) }
        item(key = "notifications") { NotificationsSection(settings, notifications, onCheckChosen) }
        item(key = "lists") { ListsSection(settings) }
        item(key = "writing") { WritingSection(settings) }
        if (templates != null) item(key = "templates") { TemplatesSection(templates, onTemplate) }
        item(key = "searches") { SavedSearchesSection(searches, onSearch) }
        item(key = "senders") { SendersSection(senders, onSender) }
        item(key = "appearance") { AppearanceSection(prefs) }
        item(key = "input") { InputSection(prefs) }
        // the list of shortcuts only where there is a keyboard to use them with
        if (keyboard) {
            item(key = "shortcuts") {
                ShortcutListSection(shortcuts, scopeLabel = { scope -> if (scope == MailScope) stringResource(R.string.shortcut_scope_mail) else null })
            }
        }
        if (onExport != null && onImport != null) item(key = "transfer") { TransferSection(onExport, onImport) }
        item(key = "experiments") { ExperimentsSection(settings) }
        item(key = "microsoft") { MicrosoftSection(settings) }
        item(key = "log") { LogSection(onOpenLog) }
        item(key = "about") { AboutSection(onOpenLicences) }
    }
}

/** The accounts with how each is doing, each row the way to its account's page, and "Add an account". */
@Composable
private fun AccountsSection(accounts: List<MailAccount>, canAdd: Boolean, onOpen: (MailAccount) -> Unit, onAdd: () -> Unit) {
    SettingsSection(stringResource(R.string.settings_accounts)) {
        if (accounts.isEmpty()) {
            Text(stringResource(R.string.settings_accounts_empty), style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary)
        }
        accounts.forEach { account ->
            // the whole row leads there, an arrow at its end (docs/KIT-ISTEKLERI.md, 17)
            SettingRow(account.name, subtitle = stringResource(R.string.account_summary, account.address, stateLabel(account.state)), onClick = { onOpen(account) })
        }
        if (canAdd) {
            DeskButton(stringResource(R.string.account_add), onAdd, Modifier.padding(top = DeskTheme.dimens.gapS), icon = DeskIcons.Plus, primary = accounts.isEmpty())
        }
    }
}

/**
 * New mail: whether the system lets the app tell of it, with the way to its settings (the
 * switch, and each account's sound); how the inboxes are looked at while no window is open (as
 * mail comes, every so often, or not at all); and, for "as it comes", whether the system leaves
 * the app alone in the background, with the way to that setting.
 */
@Composable
private fun NotificationsSection(settings: AppSettings, gate: NotifyGate?, onCheckChosen: () -> Unit) {
    SettingsSection(stringResource(R.string.settings_notifications)) {
        if (gate != null) {
            if (gate.allowed) {
                SettingRow(stringResource(R.string.settings_notify_on), subtitle = stringResource(R.string.settings_notify_on_note)) {
                    DeskButton(stringResource(R.string.settings_notify_system), gate.openSystemSettings)
                }
            } else {
                SettingRow(stringResource(R.string.settings_notify_off), subtitle = stringResource(R.string.settings_notify_off_note)) {
                    DeskButton(stringResource(R.string.settings_notify_allow), gate.ask, primary = true)
                }
            }
        }
        SettingRow(stringResource(R.string.settings_check), subtitle = stringResource(R.string.settings_check_note)) {
            SegmentedChoice(
                options = AppSettings.CHECK_CHOICES.map { minutes ->
                    minutes to when (minutes) {
                        AppSettings.CHECK_INSTANT -> stringResource(R.string.settings_check_instant)
                        0 -> stringResource(R.string.settings_check_manual)
                        else -> stringResource(R.string.settings_check_minutes, minutes)
                    }
                },
                selected = settings.checkMinutes,
                onSelect = { minutes ->
                    settings.updateCheckMinutes(minutes)
                    onCheckChosen()
                },
            )
        }
        // the line that waits for mail lives through the night only where the system leaves the app alone
        if (gate != null && settings.checkMinutes == AppSettings.CHECK_INSTANT) {
            if (gate.unrestricted) {
                SettingRow(stringResource(R.string.settings_battery_free), subtitle = stringResource(R.string.settings_battery_free_note)) {
                    DeskButton(stringResource(R.string.settings_battery_app), gate.openBatterySettings)
                }
            } else {
                SettingRow(stringResource(R.string.settings_battery_limited), subtitle = stringResource(R.string.settings_battery_limited_note)) {
                    DeskButton(stringResource(R.string.settings_battery_allow), gate.askBattery, primary = true)
                }
            }
        }
    }
}

/** The lists of mail: a conversation as one row, or every message on its own. */
@Composable
private fun ListsSection(settings: AppSettings) {
    SettingsSection(stringResource(R.string.settings_lists)) {
        SwitchRow(
            title = stringResource(R.string.settings_conversations),
            checked = settings.conversations,
            onCheckedChange = settings::updateConversations,
            subtitle = stringResource(R.string.settings_conversations_note),
        )
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

/** The kept texts to put into a message: each with the way to change it, and a new one. */
@Composable
private fun TemplatesSection(templates: List<MessageTemplate>, onOpen: (MessageTemplate) -> Unit) {
    SettingsSection(stringResource(R.string.settings_templates)) {
        Text(stringResource(R.string.settings_templates_note), style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary)
        templates.forEach { template ->
            SettingRow(template.name, subtitle = template.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()) {
                DeskButton(stringResource(R.string.template_edit), { onOpen(template) })
            }
        }
        DeskButton(
            stringResource(R.string.template_new),
            { onOpen(MessageTemplate(0, "", "")) },
            Modifier.padding(top = DeskTheme.dimens.gapS),
            icon = DeskIcons.Plus,
        )
    }
}

/** The searches kept as folders of their own: each with the way to change it, or to take it off the sidebar. */
@Composable
private fun SavedSearchesSection(searches: List<SavedSearch>, onOpen: (SavedSearch) -> Unit) {
    SettingsSection(stringResource(R.string.nav_saved_searches)) {
        if (searches.isEmpty()) {
            Text(stringResource(R.string.settings_searches_empty), style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary)
        }
        searches.forEach { search ->
            SettingRow(search.name, subtitle = search.query) {
                DeskButton(stringResource(R.string.template_edit), { onOpen(search) })
            }
        }
    }
}

/** A saved search's name and what it looks for, to be changed; or the search taken off the sidebar. */
@Composable
private fun SavedSearchDialog(search: SavedSearch, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(search.name) }
    var query by remember { mutableStateOf(search.query) }
    DeskDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_search_edit),
        actions = {
            DeskButton(stringResource(R.string.template_delete), onDelete)
            DeskButton(stringResource(R.string.template_save), { onSave(name.trim(), query.trim()) }, primary = true, enabled = name.isNotBlank() && query.isNotBlank())
        },
    ) {
        DeskTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = stringResource(R.string.search_save_name))
        DeskTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), label = stringResource(R.string.settings_search_query))
    }
}

/**
 * What was said of senders: the VIPs, whose mail has a folder of its own, and the senders whose
 * remote pictures are shown without being asked; each can be taken back.
 */
@Composable
private fun SendersSection(senders: Map<String, SenderMarks>, onChange: (String, SenderMarks) -> Unit) {
    SettingsSection(stringResource(R.string.settings_senders)) {
        Text(stringResource(R.string.settings_senders_note), style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary)
        senders.filterValues { it.vip }.forEach { (address, marks) ->
            SettingRow(address, subtitle = stringResource(R.string.settings_sender_vip)) {
                DeskButton(stringResource(R.string.settings_sender_unvip), { onChange(address, marks.copy(vip = false)) })
            }
        }
        senders.filterValues { it.images }.forEach { (address, marks) ->
            SettingRow(address, subtitle = stringResource(R.string.settings_sender_images)) {
                DeskButton(stringResource(R.string.settings_sender_ask), { onChange(address, marks.copy(images = false)) })
            }
        }
    }
}

/** The settings to another device: what goes into the file, and the two ways, out and in. */
@Composable
private fun TransferSection(onExport: () -> Unit, onImport: () -> Unit) {
    SettingsSection(stringResource(R.string.transfer_title)) {
        Text(stringResource(R.string.transfer_note), style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary)
        Row(Modifier.padding(top = DeskTheme.dimens.gapS), horizontalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS)) {
            DeskButton(stringResource(R.string.transfer_export), onExport, icon = MailIcons.Share)
            DeskButton(stringResource(R.string.transfer_import), onImport, icon = MailIcons.Import)
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
    }
}

/** Names the tests find the fields of the settings by. */
object SettingsTags {
    const val MICROSOFT_CLIENT_ID = "settings-microsoft-client-id"
}

/**
 * The registration at Microsoft that Outlook accounts sign in with: the app's own, or one of the
 * user's, for the day the app's is no longer taken ([AppSettings.microsoftClientId]).
 */
@Composable
private fun MicrosoftSection(settings: AppSettings) {
    val dimens = DeskTheme.dimens
    // what is typed; the setting's until then
    var typed by remember(settings.microsoftClientId) { mutableStateOf(settings.microsoftClientId) }
    val valid = typed.isBlank() || MicrosoftSignIn.isClientId(typed)
    val save = { if (valid) settings.updateMicrosoftClientId(typed) }
    SettingsSection(stringResource(R.string.settings_microsoft)) {
        Text(stringResource(R.string.settings_microsoft_note), style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary)
        DeskTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth().padding(top = dimens.gapM).testTag(SettingsTags.MICROSOFT_CLIENT_ID),
            label = stringResource(R.string.settings_microsoft_client_id),
            hint = stringResource(R.string.settings_microsoft_client_id_hint),
            error = !valid,
            supportingText = if (valid) null else stringResource(R.string.settings_microsoft_client_id_invalid),
            mono = true,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
            onImeAction = save,
        )
        DeskButton(
            stringResource(R.string.account_writing_save),
            save,
            Modifier.padding(top = dimens.gapM),
            enabled = valid && typed.trim() != settings.microsoftClientId,
        )
    }
}

/** The installed version (what to quote when something goes wrong), and the way to the licences of others' work in it. */
@Composable
private fun AboutSection(onOpenLicences: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }
    SettingsSection(stringResource(R.string.settings_about)) {
        KeyValueRow(stringResource(R.string.settings_version), version, selectable = true)
        Text(stringResource(R.string.settings_about_note), style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary)
        DeskButton(stringResource(R.string.licences_title), onOpenLicences, Modifier.padding(top = DeskTheme.dimens.gapS))
    }
}
