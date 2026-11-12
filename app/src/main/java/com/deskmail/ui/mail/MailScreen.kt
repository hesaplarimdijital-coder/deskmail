package com.deskmail.ui.mail

import android.text.format.Formatter
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deskmail.R
import com.deskmail.demo.DemoMail
import com.deskmail.mail.MailAttachment
import com.deskmail.mail.MailMessage
import com.deskmail.mail.MailView
import com.deskmail.ui.MailIcons
import com.deskmail.ui.folderPathLabel
import desktopkit.appResources
import desktopkit.components.DeskListRow
import desktopkit.components.HorizontalSplitPane
import desktopkit.components.InspectorPane
import desktopkit.components.InspectorTab
import desktopkit.components.InspectorToggleButton
import desktopkit.components.KeyValueRow
import desktopkit.components.WorkstationLayout
import desktopkit.pluralString
import desktopkit.screens.MessageScreen
import desktopkit.screens.PageHeader
import desktopkit.settings.DeskPreferences
import desktopkit.shell.CompactTopBar
import desktopkit.shell.DetailTransition
import desktopkit.shell.LocalNavKind
import desktopkit.shell.NavKind
import desktopkit.shell.TopBarAction
import desktopkit.shell.WidthClass
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme

/**
 * A view over the mail (the unified inbox, a folder) in the layout its window has room for:
 *
 * - a phone, and a tablet held upright: the list, and the message sliding over it (list → message);
 * - beside a rail, once the two fit: the list and the reader side by side;
 * - a large window (the sidebar; DeX): the table, the reader and the inspector that opens and closes.
 *
 * Which message is open, the filters and the scroll position are kept by [ui], so resizing the
 * window from one layout into another keeps them.
 *
 * @param title the view's name, for the header of a large window.
 * @param onBack set on a phone for a folder opened from the Folders list: the top bar gets a back arrow.
 * @param onCompose "New message"; the phone shows it as the accent icon of the top bar.
 * @param onNotYet a command that this pre-release does not have yet, by its name.
 * @param readerUnderBars on a phone the open message scrolls beneath the bars (an experiment's switch).
 */
@Composable
fun MailScreen(
    route: String,
    view: MailView,
    title: String,
    mail: DemoMail,
    ui: MailUiState,
    prefs: DeskPreferences,
    onBack: (() -> Unit)?,
    onCompose: () -> Unit,
    onNotYet: (String) -> Unit,
    readerUnderBars: Boolean,
    body: @Composable (MailMessage, Modifier) -> Unit,
) {
    val navKind = LocalNavKind.current
    val filters = ui.filters(route)
    val all = mail.messages(view)
    val sort = ui.sort
    // a table is sorted by its header; the rows of the touch layouts are always newest first
    val messages = remember(all, filters, sort, navKind) {
        val filtered = filterMessages(all, filters)
        if (navKind == NavKind.Sidebar) sortMessages(filtered, sort) else filtered
    }
    // from the store, so a message stays open when a filter takes it off the list
    val opened = ui.openedId(route)?.let { mail.message(it) }
    val open: (MailMessage) -> Unit = { message -> openMessage(mail, ui, route, message) }
    // what the keyboard steps through (J, K); the rows' list starts with the chips
    val leadingItems = if (navKind == NavKind.Sidebar) 0 else 1
    SideEffect { ui.setShown(route, messages.map { it.id }, leadingItems) }
    val composeLabel = stringResource(R.string.action_new_message)
    val showImages = stringResource(R.string.reader_remote_show)
    val openAttachment = stringResource(R.string.action_open_attachment)
    // What the phone's bars cover. Asked for here, in the screen's own composition, and handed
    // down: asked for from inside the BoxWithConstraints below, the shell does not notice and pads
    // the screen as well, so everything stands a bar's height too low (docs/KIT-ISTEKLERI.md, 12).
    val bars = rememberShellContentPadding()

    val chips: @Composable () -> Unit = {
        MailFilterChips(
            selected = filters,
            onToggle = { ui.toggleFilter(route, it) },
            onClear = { ui.clearFilters(route) },
            modifier = Modifier.padding(vertical = DeskTheme.dimens.gapS),
        )
    }
    val empty: @Composable () -> Unit = {
        MessageScreen(
            title = stringResource(if (filters.isEmpty()) R.string.list_empty_title else R.string.list_empty_filtered),
            icon = MailIcons.Mail,
            modifier = Modifier.padding(top = 48.dp),
        )
    }
    val reader: @Composable (MailMessage, (() -> Unit)?, @Composable () -> Unit) -> Unit = { message, onClose, trailing ->
        MessageReader(
            message = message,
            onToggleStar = { mail.setStarred(message.id, !message.starred) },
            onToggleUnread = { mail.setUnread(message.id, !message.unread) },
            onClose = onClose,
            onShowRemoteImages = { onNotYet(showImages) },
            onAttachment = { _: MailAttachment -> onNotYet(openAttachment) },
            barsPadding = bars,
            body = body,
            modifier = Modifier.testTag(MailTags.READER),
            underBars = readerUnderBars,
            trailingActions = { trailing() },
        )
    }

    when (navKind) {
        NavKind.Sidebar -> {
            // DeX and large windows: table | reader | inspector
            val inspector = ui.inspector
            WorkstationLayout(
                state = inspector,
                main = {
                    HorizontalSplitPane(
                        state = ui.listSplit,
                        start = {
                            Column(Modifier.fillMaxSize()) {
                                PageHeader(title, subtitle = pluralString(R.plurals.message_count, all.size))
                                chips()
                                if (messages.isEmpty()) {
                                    empty()
                                } else {
                                    MessageTable(
                                        messages = messages,
                                        openedId = opened?.id,
                                        state = ui.listState(route),
                                        sort = sort,
                                        onSortChange = { ui.sort = it },
                                        onOpen = open,
                                        prefs = prefs,
                                        modifier = Modifier.testTag(MailTags.LIST),
                                    )
                                }
                            }
                        },
                        end = {
                            if (opened == null) {
                                NoMessageOpen()
                            } else {
                                reader(opened, null) { InspectorToggleButton(inspector) }
                            }
                        },
                        minStart = 320.dp,
                        minEnd = 360.dp,
                        dividerLabel = stringResource(R.string.list_resize),
                    )
                },
                inspector = { MessageInspector(opened, mail, inspector.selectedTab, { inspector.selectedTab = it }, inspector::toggle) },
                minMain = 680.dp,
                showFrom = WidthClass.Large,
            )
        }
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val rows: @Composable () -> Unit = {
                // a phone's top bar: the view's name, back for a folder opened from the Folders list, "New message"
                CompactTopBar(onBack = onBack, actions = listOf(TopBarAction(MailIcons.Compose, composeLabel, onCompose, primary = true)))
                MessageRows(
                    messages = messages,
                    openedId = opened?.id,
                    state = ui.listState(route),
                    onOpen = open,
                    contentPadding = bars,
                    modifier = Modifier.testTag(MailTags.LIST),
                    header = chips,
                    empty = empty,
                )
            }
            if (maxWidth >= TWO_PANES_FROM) {
                // beside a rail with room for both: list | reader
                HorizontalSplitPane(
                    state = ui.listSplit,
                    start = rows,
                    end = { if (opened == null) NoMessageOpen() else reader(opened, null) {} },
                    minStart = 320.dp,
                    minEnd = 360.dp,
                    dividerLabel = stringResource(R.string.list_resize),
                )
            } else {
                // a phone, a tablet held upright: the message slides over the list
                DetailTransition(
                    detail = opened,
                    onBack = { ui.close(route) },
                    detailKey = { it.id },
                    list = rows,
                    // nothing flies between a row and the message
                    sharedElements = false,
                ) { message ->
                    reader(message, { ui.close(route) }) {}
                }
            }
        }
    }
}

/** Opens [message] in [route]'s reader. A message that is opened is read. */
fun openMessage(mail: DemoMail, ui: MailUiState, route: String, message: MailMessage) {
    ui.open(route, message)
    mail.setUnread(message.id, false)
}

/** From this width of the content on, the list and the reader stand side by side. */
private val TWO_PANES_FROM = 880.dp

/** Names the tests find the screen's two parts by. */
object MailTags {
    const val LIST = "mail-list"
    const val READER = "mail-reader"
}

/** The inspector of a large window: the open message's details, and its attachments. */
@Composable
private fun MessageInspector(message: MailMessage?, mail: DemoMail, selected: Int, onSelect: (Int) -> Unit, onCollapse: () -> Unit) {
    val resources = appResources()
    val context = LocalContext.current
    val dates = rememberDateLabels()
    val dimens = DeskTheme.dimens
    val tabs = listOf(
        InspectorTab("details", stringResource(R.string.inspector_details)),
        InspectorTab("attachments", stringResource(R.string.inspector_attachments), MailIcons.Attachment),
    )
    InspectorPane(tabs = tabs, selected = selected.coerceIn(0, tabs.lastIndex), onSelect = onSelect, onCollapse = onCollapse) { tab ->
        when {
            message == null -> NoMessageOpen()
            tab.id == "details" -> Column(Modifier.fillMaxSize().padding(horizontal = dimens.gapL, vertical = dimens.gapS)) {
                val folder = mail.folder(message.folderId)
                KeyValueRow(stringResource(R.string.detail_from), message.fromName + "\n" + message.fromAddress, selectable = true)
                KeyValueRow(stringResource(R.string.detail_to), message.to, selectable = true)
                KeyValueRow(stringResource(R.string.detail_date), dates.full(message.sentAt))
                KeyValueRow(stringResource(R.string.detail_account), mail.account(message.accountId)?.name.orEmpty())
                if (folder != null) KeyValueRow(stringResource(R.string.detail_folder), folderPathLabel(resources, folder))
            }
            message.attachments.isEmpty() -> MessageScreen(title = stringResource(R.string.inspector_no_attachments), icon = MailIcons.Attachment)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(message.attachments, key = { it.name }) { attachment ->
                    DeskListRow(
                        title = attachment.name,
                        leading = MailIcons.Attachment,
                        trailing = Formatter.formatShortFileSize(context, attachment.bytes),
                    )
                }
            }
        }
    }
}
