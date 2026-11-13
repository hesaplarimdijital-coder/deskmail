package com.deskmail.ui.mail

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deskmail.R
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.text.SearchQuery
import com.deskmail.mail.AccountState
import com.deskmail.mail.DraftKind
import com.deskmail.mail.MailAccount
import com.deskmail.mail.MailContent
import com.deskmail.mail.MailMessage
import com.deskmail.mail.MailSource
import com.deskmail.mail.MailView
import com.deskmail.ui.MailIcons
import com.deskmail.ui.folderPathLabel
import desktopkit.appResources
import desktopkit.components.BannerTone
import desktopkit.components.DeskDragPayload
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.DeskListRow
import desktopkit.components.DeskProgressBar
import desktopkit.components.HorizontalSplitPane
import desktopkit.components.InspectorPane
import desktopkit.components.InspectorTab
import desktopkit.components.InspectorToggleButton
import desktopkit.components.KeyValueRow
import desktopkit.components.MenuEntry
import desktopkit.components.SelectionBar
import desktopkit.components.SkeletonList
import desktopkit.components.StatusBanner
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
import desktopkit.shell.currentWidthClass
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme
import desktopkit.theme.InputMode

/**
 * A view over the mail (the unified inbox, a folder) in the layout its window has room for:
 *
 * - a phone, and a tablet held upright: the list, and the message sliding over it (list → message);
 * - beside a rail, once the two fit: the list and the reader side by side;
 * - a large window (the sidebar; DeX): the list, the reader and the inspector that opens and closes.
 *
 * Beside the reader the list has a share of the room, which the divider between them changes. In a
 * large window it is a table when a mouse is in use and the list is wide enough for columns
 * ([MailLayout.TABLE_FROM]); a finger, and a narrow list, get the two-line rows.
 *
 * Which message is open, the filters and the scroll position are kept by [ui], so resizing the
 * window from one layout into another keeps them. The messages come from [mail] and follow it;
 * coming to the front, the screen has [mail] bring what it shows up to date.
 *
 * The search is this screen as well ([MailView.Search]): the field ([SearchBox]) stands above
 * what is found, and what is found is listed, opened and acted on like any other mail.
 *
 * @param title the view's name, for the header of a large window.
 * @param onBack set on a phone for a folder opened from the Folders list: the top bar gets a back arrow.
 * @param onCompose "New message"; the phone shows it as the accent icon of the top bar.
 * @param onWrite begins a reply to a message, or sends it on; null where nothing can be written.
 * @param actions archives, moves and deletes messages, and says what came of it.
 * @param files opens, saves and shares the files of messages.
 * @param onAddAccount where "Add an account" leads while there is none.
 * @param onOpenAccount an account's page, for one that has to sign in again.
 * @param readerUnderBars on a phone the open message scrolls beneath the bars (an experiment's switch).
 * @param resumes how often the window came back to the front: each time is a reason to look for new mail.
 */
@Composable
fun MailScreen(
    route: String,
    view: MailView,
    title: String,
    mail: MailSource,
    ui: MailUiState,
    prefs: DeskPreferences,
    onBack: (() -> Unit)?,
    onCompose: () -> Unit,
    onWrite: ((DraftKind, MailMessage) -> Unit)?,
    actions: MailActions,
    files: AttachmentHandler,
    onAddAccount: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    readerUnderBars: Boolean,
    body: @Composable (MailContent.Ready, Boolean, Modifier) -> Unit,
    resumes: Int = 0,
) {
    val sidebar = LocalNavKind.current == NavKind.Sidebar
    val compact = currentWidthClass() == WidthClass.Compact
    val pointer = DeskTheme.inputMode == InputMode.Pointer
    val filters = ui.filters(route)
    val searching = view as? MailView.Search
    // newest first, as the store hands them out: what was last seen of the view at once, then what is
    val listed by remember(mail, view) { mail.observe(view) }.collectAsState(initial = mail.cached(view))
    // While the next thing typed is being looked for, what the last one found stays on screen.
    val lastFound = remember { mutableStateOf<List<MailMessage>?>(null) }
    if (searching != null && listed != null) SideEffect { lastFound.value = listed }
    val known = listed ?: if (searching != null) lastFound.value else null
    val all = known.orEmpty()
    // nothing is known yet: neither "no account" nor "no messages" may be said
    val reading = known == null || !mail.loaded
    val noAccount = !reading && mail.accounts.isEmpty()
    val filtered = remember(all, filters) { filterMessages(all, filters) }
    val openedId = ui.openedId(route)
    // from the store, so a message stays open when a filter takes it off the list
    val opened = if (openedId == null) null else key(openedId) { watchedMessage(mail, openedId, all.firstOrNull { it.id == openedId }) }
    val open: (MailMessage) -> Unit = { message -> openMessage(mail, ui, route, message.id) }
    val refresh: () -> Unit = { mail.refresh(view) }
    val resources = appResources()
    // Several messages at once: rows selected with the mouse (Ctrl, Shift), or picked with a finger.
    val chosenIds = ui.selection(route)
    val picking = ui.picking(route)
    val chosen = remember(all, chosenIds) { if (chosenIds.isEmpty()) emptyList() else all.filter { it.id in chosenIds } }
    // a message that left the list (moved or deleted, here or elsewhere) is selected no longer
    LaunchedEffect(known, chosenIds) {
        if (known != null && chosen.size != chosenIds.size) ui.select(route, chosen.mapTo(HashSet()) { it.id })
    }
    val several = chosen.size > 1 || (picking && chosen.isNotEmpty())
    val bulk = BulkActions(
        // read them all while one is unread; unread only when all were read
        onToggleUnread = { mail.markUnread(chosen.map { it.id }, unread = chosen.none { it.unread }) },
        onToggleStar = { mail.markStarred(chosen.map { it.id }, starred = !chosen.all { it.starred }) },
        onArchive = if (chosen.any { archivable(mail, it) }) ({ actions.archive(chosen.map { it.id }) }) else null,
        onMove = chosen.firstOrNull { mail.moveTargets(it).isNotEmpty() }?.let { anchor -> { ui.moveSeveral(anchor, chosen.map { it.id }) } },
        onDelete = { actions.delete(chosen.map { it.id }) },
        onClear = { ui.clearSelection(route) },
    )
    // what travels when a row is dragged to a folder: the selection when the row is part of it
    val drag: (MailMessage) -> DeskDragPayload? = { message ->
        val ids = if (message.id in chosenIds) chosen.map { it.id } else listOf(message.id)
        DeskDragPayload(MESSAGE_DRAG, ids.map { it.toString() }, resources.getQuantityString(R.plurals.message_count, ids.size, ids.size))
    }
    val composeLabel = stringResource(R.string.action_new_message)
    val refreshLabel = stringResource(R.string.action_refresh)
    val openLabel = stringResource(R.string.action_open_message)
    val markReadLabel = stringResource(R.string.action_mark_read)
    val markUnreadLabel = stringResource(R.string.action_mark_unread)
    val starLabel = stringResource(R.string.action_star)
    val unstarLabel = stringResource(R.string.action_unstar)
    val archiveLabel = stringResource(R.string.action_archive)
    val moveLabel = stringResource(R.string.action_move)
    val deleteLabel = stringResource(R.string.action_delete)
    val replyLabel = stringResource(R.string.action_reply)
    val replyAllLabel = stringResource(R.string.action_reply_all)
    val forwardLabel = stringResource(R.string.action_forward)
    val selectLabel = stringResource(R.string.action_select)
    val editLabel = stringResource(R.string.action_edit_draft)
    // What can be done with a message from its row: a long press, a right click, the Menu key.
    val rowMenu: (MailMessage) -> List<MenuEntry> = { message ->
        if (several && message.id in chosenIds) {
            // one of several selected rows: what is done is done to all of them
            buildList {
                add(MenuEntry.Item(if (chosen.any { it.unread }) markReadLabel else markUnreadLabel, bulk.onToggleUnread, shortcut = "U"))
                add(MenuEntry.Item(if (chosen.all { it.starred }) unstarLabel else starLabel, bulk.onToggleStar, shortcut = "S"))
                add(MenuEntry.Separator)
                bulk.onArchive?.let { add(MenuEntry.Item(archiveLabel, it, shortcut = "E", icon = MailIcons.Archive)) }
                bulk.onMove?.let { add(MenuEntry.Item(moveLabel, it, shortcut = "V", icon = MailIcons.MoveTo)) }
                add(MenuEntry.Item(deleteLabel, bulk.onDelete, shortcut = "Delete", icon = DeskIcons.Delete, destructive = true))
            }
        } else buildList {
            add(MenuEntry.Item(openLabel, { open(message) }))
            add(MenuEntry.Item(if (message.unread) markReadLabel else markUnreadLabel, { mail.toggleUnread(message.id) }, shortcut = "U"))
            add(MenuEntry.Item(if (message.starred) unstarLabel else starLabel, { mail.toggleStarred(message.id) }, shortcut = "S"))
            add(MenuEntry.Separator)
            if (onWrite != null && isDraft(mail, message)) {
                // a draft is gone on with, not answered
                add(MenuEntry.Item(editLabel, { onWrite(DraftKind.Edit, message) }, icon = MailIcons.Compose))
                add(MenuEntry.Separator)
            } else if (onWrite != null) {
                add(MenuEntry.Item(replyLabel, { onWrite(DraftKind.Reply, message) }, shortcut = "R", icon = MailIcons.Reply))
                add(MenuEntry.Item(replyAllLabel, { onWrite(DraftKind.ReplyAll, message) }, shortcut = "A", icon = MailIcons.ReplyAll))
                add(MenuEntry.Item(forwardLabel, { onWrite(DraftKind.Forward, message) }, shortcut = "F", icon = MailIcons.ForwardMail))
                add(MenuEntry.Separator)
            }
            if (archivable(mail, message)) add(MenuEntry.Item(archiveLabel, { actions.archive(listOf(message.id)) }, shortcut = "E", icon = MailIcons.Archive))
            if (mail.moveTargets(message).isNotEmpty()) add(MenuEntry.Item(moveLabel, { ui.moving = message }, shortcut = "V", icon = MailIcons.MoveTo))
            add(MenuEntry.Item(deleteLabel, { actions.delete(listOf(message.id)) }, shortcut = "Delete", icon = DeskIcons.Delete, destructive = true))
            add(MenuEntry.Separator)
            // from here on a tap adds or removes a row: how several are chosen with a finger
            add(MenuEntry.Item(selectLabel, { ui.pick(route, message.id) }))
        }
    }
    // What the phone's bars cover. Asked for here, in the screen's own composition, and handed
    // down: asked for from inside the BoxWithConstraints below, the shell does not notice and pads
    // the screen as well, so everything stands a bar's height too low (docs/KIT-ISTEKLERI.md, 12).
    val bars = rememberShellContentPadding()

    // In front (again), or with other accounts than before: what the screen shows may be out of date.
    val accountIds = mail.accounts.map { it.id }
    LaunchedEffect(mail, view, accountIds, resumes) { mail.viewShown(view) }

    // the accounts whose mail this view shows and that are in trouble
    val troubled = when (view) {
        is MailView.Folder -> listOfNotNull(mail.folder(view.id)?.let { mail.account(it.accountId) })
        else -> mail.accounts
    }.filter { it.state != AccountState.Ok }

    val chips: @Composable () -> Unit = {
        Column {
            if (searching != null) SearchBox(ui)
            if (mail.syncing) {
                DeskProgressBar(
                    fraction = null,
                    modifier = Modifier.padding(horizontal = DeskTheme.dimens.pagePadding),
                    height = 2.dp,
                    contentDescription = stringResource(R.string.list_syncing),
                )
            }
            AccountNotices(troubled, onRetry = refresh, onOpenAccount = onOpenAccount)
            if (!noAccount) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MailFilterChips(
                        selected = filters,
                        onToggle = { ui.toggleFilter(route, it) },
                        onClear = { ui.clearFilters(route) },
                        modifier = Modifier.weight(1f).padding(vertical = DeskTheme.dimens.gapS),
                    )
                    // a phone has it in its top bar, a large window in its header
                    if (!compact && !sidebar) {
                        DeskIconButton(MailIcons.Refresh, refreshLabel, refresh, Modifier.padding(end = DeskTheme.dimens.pagePadding - 8.dp))
                    }
                }
            }
        }
    }
    val empty: @Composable () -> Unit = {
        when {
            // not known yet, or on its way from the server: neither is "no messages"
            reading || (searching == null && mail.syncing && all.isEmpty()) -> SkeletonList()
            noAccount -> MessageScreen(
                title = stringResource(R.string.welcome_title),
                icon = MailIcons.Mail,
                modifier = Modifier.padding(top = 48.dp),
                description = stringResource(R.string.welcome_text),
                actionLabel = stringResource(R.string.welcome_action),
                onAction = onAddAccount,
            )
            // nothing typed that could be looked for: what to type
            searching != null && SearchQuery.match(searching.query) == null -> MessageScreen(
                title = stringResource(R.string.search_start_title),
                icon = DeskIcons.Search,
                modifier = Modifier.padding(top = 48.dp),
                description = stringResource(R.string.search_start_text),
            )
            // nothing found: where was looked, so that "it is not there" is not read as "it does not exist"
            searching != null -> MessageScreen(
                title = stringResource(if (filters.isEmpty()) R.string.search_none_title else R.string.list_empty_filtered),
                icon = DeskIcons.Search,
                modifier = Modifier.padding(top = 48.dp),
                description = stringResource(R.string.search_scope),
            )
            else -> MessageScreen(
                title = stringResource(if (filters.isEmpty()) R.string.list_empty_title else R.string.list_empty_filtered),
                icon = MailIcons.Mail,
                modifier = Modifier.padding(top = 48.dp),
            )
        }
    }
    val reader: @Composable (MailMessage, (() -> Unit)?, @Composable () -> Unit) -> Unit = { message, onClose, trailing ->
        MessageReader(
            message = message,
            // watched here, by the reader that shows it: a message that is being closed keeps its text while it leaves
            content = watchedContent(mail, message.id),
            showImages = ui.showsImages(message.id),
            onToggleStar = { mail.toggleStarred(message.id) },
            onToggleUnread = { mail.toggleUnread(message.id) },
            onWrite = onWrite?.let { write -> { kind -> write(kind, message) } },
            onArchive = if (archivable(mail, message)) ({ actions.archive(listOf(message.id)) }) else null,
            onDelete = { actions.delete(listOf(message.id)) },
            onMove = if (mail.moveTargets(message).isNotEmpty()) ({ ui.moving = message }) else null,
            onClose = onClose,
            onShowRemoteImages = { ui.showImages(message.id) },
            onAttachment = files::handle,
            onRetry = { mail.retryContent(message.id) },
            barsPadding = bars,
            body = body,
            modifier = Modifier.testTag(MailTags.READER),
            underBars = readerUnderBars,
            fetchingFile = files.busy,
            draft = isDraft(mail, message),
            trailingActions = { trailing() },
        )
    }
    // "Move…": which folder, asked over whatever layout the window has
    val moving = ui.moving
    // read here, with the message: choosing a folder clears both before the move is asked for
    val movingIds = if (moving == null) emptyList() else ui.movingIds.ifEmpty { listOf(moving.id) }
    MoveChooser(
        message = moving,
        mail = mail,
        onMove = { _, folderId -> actions.move(movingIds, folderId) },
        onDismiss = { ui.moving = null },
    )
    // The rows as a phone, a tablet held upright and a window beside a rail list them: under the
    // chips, which scroll away with them. They are what the keyboard steps through there (J, K).
    val rows: @Composable () -> Unit = {
        // a phone's top bar: the view's name, back for a folder opened from the Folders list, "New message", "Sync"
        CompactTopBar(
            onBack = onBack,
            actions = listOf(
                TopBarAction(MailIcons.Compose, composeLabel, onCompose, primary = true),
                TopBarAction(MailIcons.Refresh, refreshLabel, refresh),
            ),
            // the search screen has its own field; the bar's magnifier would open the palette beside it
            showSearch = searching == null,
        )
        Box(Modifier.fillMaxSize()) {
            MessageRows(
                messages = filtered,
                openedId = opened?.id,
                state = ui.listState(route),
                onOpen = open,
                // the last rows come to rest above the selection's bar, not under it
                contentPadding = if (several) PaddingValues(top = bars.calculateTopPadding(), bottom = bars.calculateBottomPadding() + BULK_BAR_HEIGHT) else bars,
                modifier = Modifier.testTag(MailTags.LIST),
                header = chips,
                empty = empty,
                menu = rowMenu,
                selection = chosenIds,
                onPick = if (picking) ({ message -> ui.togglePicked(route, message.id) }) else null,
            )
            if (several) {
                // above the phone's bottom bar, over the end of the list
                BulkBar(chosen, bulk, Modifier.align(Alignment.BottomCenter).padding(bottom = bars.calculateBottomPadding()))
            }
        }
        // Back ends the picking before it does anything else
        BackHandler(enabled = picking) { ui.clearSelection(route) }
    }
    if (!sidebar) SideEffect { ui.setShown(route, filtered.map { it.id }, leadingItems = 1) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // what the list and the reader have between them
        val room = maxWidth.value
        // before the panes are measured: the list's share of this window's room
        SideEffect { ui.fitList(room) }
        when {
            sidebar -> {
                // DeX and large windows: list | reader | inspector
                val inspector = ui.inspector
                WorkstationLayout(
                    state = inspector,
                    main = {
                        HorizontalSplitPane(
                            state = ui.listSplit,
                            start = {
                                ListPane(
                                    route = route,
                                    title = title,
                                    count = all.size,
                                    messages = filtered,
                                    openedId = opened?.id,
                                    ui = ui,
                                    prefs = prefs,
                                    pointer = pointer,
                                    onOpen = open,
                                    onRefresh = refresh,
                                    chips = chips,
                                    empty = empty,
                                    menu = rowMenu,
                                    chosen = chosen,
                                    several = several,
                                    bulk = bulk,
                                    drag = drag,
                                )
                            },
                            end = {
                                if (opened == null) {
                                    NoMessageOpen()
                                } else {
                                    reader(opened, null) { InspectorToggleButton(inspector) }
                                }
                            },
                            minStart = MailLayout.MIN_LIST.dp,
                            minEnd = MailLayout.MIN_READER.dp,
                            dividerLabel = stringResource(R.string.list_resize),
                        )
                    },
                    inspector = { MessageInspector(opened, mail, files, inspector.selectedTab, { inspector.selectedTab = it }, inspector::toggle) },
                    minMain = 680.dp,
                    showFrom = WidthClass.Large,
                )
            }
            room >= MailLayout.TWO_PANES_FROM -> {
                // beside a rail with room for both: list | reader
                HorizontalSplitPane(
                    state = ui.listSplit,
                    start = rows,
                    end = { if (opened == null) NoMessageOpen() else reader(opened, null) {} },
                    minStart = MailLayout.MIN_LIST.dp,
                    minEnd = MailLayout.MIN_READER.dp,
                    dividerLabel = stringResource(R.string.list_resize),
                )
            }
            else -> {
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

/**
 * One message as the store has it now, wherever it is filed: [listed] (the list's copy) until
 * the store has answered. Null once the message is gone.
 */
@Composable
private fun watchedMessage(mail: MailSource, id: Long, listed: MailMessage?): MailMessage? {
    val message by remember(mail, id) { mail.observeMessage(id) }.collectAsState(initial = listed ?: mail.cachedMessage(id))
    return message
}

/** What a message says, as far as the device has it; watching it has the text fetched when it is missing. */
@Composable
private fun watchedContent(mail: MailSource, id: Long): MailContent = key(id) {
    val content by remember(mail, id) { mail.observeContent(id) }.collectAsState(initial = MailContent.Loading)
    content
}

/** A strip for every account that is in trouble: what is wrong, and the way out. */
@Composable
private fun AccountNotices(accounts: List<MailAccount>, onRetry: () -> Unit, onOpenAccount: (Long) -> Unit) {
    val dimens = DeskTheme.dimens
    accounts.forEach { account ->
        val login = account.state == AccountState.NeedsLogin
        StatusBanner(
            tone = if (login) BannerTone.Error else BannerTone.Warning,
            text = stringResource(
                when (account.state) {
                    AccountState.NeedsLogin -> R.string.account_notice_login
                    AccountState.Unreachable -> R.string.account_notice_unreachable
                    else -> R.string.account_notice_error
                },
                account.name,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding).padding(top = dimens.gapS),
            actionLabel = stringResource(if (login) R.string.account_action_reconnect else R.string.action_retry),
            onAction = { if (login) onOpenAccount(account.id) else onRetry() },
        )
    }
}

/**
 * The list of a large window, under the view's name and the chips: a table when a mouse is in use
 * ([pointer]) and the pane is wide enough for columns, the rows otherwise. A table is sorted by its
 * header; the rows are always newest first.
 */
@Composable
private fun ListPane(
    route: String,
    title: String,
    count: Int,
    messages: List<MailMessage>,
    openedId: Long?,
    ui: MailUiState,
    prefs: DeskPreferences,
    pointer: Boolean,
    onOpen: (MailMessage) -> Unit,
    onRefresh: () -> Unit,
    chips: @Composable () -> Unit,
    empty: @Composable () -> Unit,
    menu: (MailMessage) -> List<MenuEntry>,
    chosen: List<MailMessage>,
    several: Boolean,
    bulk: BulkActions,
    drag: (MailMessage) -> DeskDragPayload?,
) {
    val selection = ui.selection(route)
    val picking = ui.picking(route)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val table = pointer && maxWidth >= MailLayout.TABLE_FROM.dp
        val sort = ui.sort
        val shown = remember(messages, sort, table) { if (table) sortMessages(messages, sort) else messages }
        // what the keyboard steps through (J, K)
        SideEffect { ui.setShown(route, shown.map { it.id }, leadingItems = 0) }
        Column(Modifier.fillMaxSize()) {
            PageHeader(title, subtitle = pluralString(R.plurals.message_count, count)) {
                DeskIconButton(MailIcons.Refresh, stringResource(R.string.action_refresh), onRefresh)
            }
            chips()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    shown.isEmpty() -> empty()
                    table -> MessageTable(
                        messages = shown,
                        openedId = openedId,
                        state = ui.listState(route),
                        sort = sort,
                        onSortChange = { ui.sort = it },
                        onOpen = onOpen,
                        prefs = prefs,
                        modifier = Modifier.testTag(MailTags.LIST),
                        menu = menu,
                        selection = selection,
                        onSelect = { ui.select(route, it) },
                        picking = picking,
                        drag = drag,
                    )
                    else -> MessageRows(
                        messages = shown,
                        openedId = openedId,
                        state = ui.listState(route),
                        onOpen = onOpen,
                        contentPadding = PaddingValues(bottom = 8.dp),
                        modifier = Modifier.testTag(MailTags.LIST),
                        menu = menu,
                        selection = selection,
                        onPick = if (picking) ({ message -> ui.togglePicked(route, message.id) }) else null,
                    )
                }
            }
            // under the list, where the hand that selected the rows already is
            if (several) BulkBar(chosen, bulk)
        }
    }
}

/** What is done to several messages at once; null where it cannot be done to any of them. */
class BulkActions(
    val onToggleUnread: () -> Unit,
    val onToggleStar: () -> Unit,
    val onArchive: (() -> Unit)?,
    val onMove: (() -> Unit)?,
    val onDelete: () -> Unit,
    val onClear: () -> Unit,
)

/** The bar of a selection: how many messages, what can be done to them all, and the way out. */
@Composable
private fun BulkBar(chosen: List<MailMessage>, bulk: BulkActions, modifier: Modifier = Modifier) {
    val anyUnread = chosen.any { it.unread }
    val allStarred = chosen.isNotEmpty() && chosen.all { it.starred }
    SelectionBar(
        text = pluralString(R.plurals.selection_count, chosen.size),
        onClose = bulk.onClear,
        modifier = modifier.testTag(MailTags.SELECTION),
    ) {
        DeskIconButton(
            if (anyUnread) MailIcons.MailOpen else MailIcons.Mail,
            stringResource(if (anyUnread) R.string.action_mark_read else R.string.action_mark_unread),
            bulk.onToggleUnread,
        )
        DeskIconButton(
            if (allStarred) MailIcons.StarFilled else MailIcons.Star,
            stringResource(if (allStarred) R.string.action_unstar else R.string.action_star),
            bulk.onToggleStar,
        )
        bulk.onArchive?.let { DeskIconButton(MailIcons.Archive, stringResource(R.string.action_archive), it) }
        bulk.onMove?.let { DeskIconButton(MailIcons.MoveTo, stringResource(R.string.action_move), it) }
        DeskIconButton(DeskIcons.Delete, stringResource(R.string.action_delete), bulk.onDelete)
    }
}

/** The room the selection's bar takes over the end of a phone's list: a row of the kit's icon buttons and its rule. */
private val BULK_BAR_HEIGHT = 58.dp

/** Whether the message is a draft that lies in its account's Drafts folder: one to go on writing, not one to answer. */
fun isDraft(mail: MailSource, message: MailMessage): Boolean = mail.folder(message.folderId)?.role == FolderRole.Drafts

/** The kind of what is dragged out of a list of messages; the folders of the sidebar take it. */
const val MESSAGE_DRAG = "message"

/** Opens the message [id] in [route]'s reader. A message that is opened is read. */
fun openMessage(mail: MailSource, ui: MailUiState, route: String, id: Long) {
    ui.open(route, id)
    mail.markRead(id)
}

fun openMessage(mail: MailSource, ui: MailUiState, route: String, message: MailMessage) = openMessage(mail, ui, route, message.id)

/** Names the tests find the screen's two parts by. */
object MailTags {
    const val LIST = "mail-list"
    const val READER = "mail-reader"
    const val SELECTION = "mail-selection"
    const val SEARCH = "mail-search"
}

/** The inspector of a large window: the open message's details, and its attachments (a click opens one, its menu saves or shares it). */
@Composable
private fun MessageInspector(message: MailMessage?, mail: MailSource, files: AttachmentHandler, selected: Int, onSelect: (Int) -> Unit, onCollapse: () -> Unit) {
    val resources = appResources()
    val context = LocalContext.current
    val dates = rememberDateLabels()
    val dimens = DeskTheme.dimens
    val tabs = listOf(
        InspectorTab("details", stringResource(R.string.inspector_details)),
        InspectorTab("attachments", stringResource(R.string.inspector_attachments), MailIcons.Attachment),
    )
    val openLabel = stringResource(R.string.attachment_open)
    val saveLabel = stringResource(R.string.attachment_save)
    val shareLabel = stringResource(R.string.attachment_share)
    InspectorPane(tabs = tabs, selected = selected.coerceIn(0, tabs.lastIndex), onSelect = onSelect, onCollapse = onCollapse) { tab ->
        when {
            message == null -> NoMessageOpen()
            tab.id == "details" -> Column(Modifier.fillMaxSize().padding(horizontal = dimens.gapL, vertical = dimens.gapS)) {
                val folder = mail.folder(message.folderId)
                val from = if (message.fromAddress == message.fromName) message.fromName else message.fromName + "\n" + message.fromAddress
                KeyValueRow(stringResource(R.string.detail_from), from, selectable = true)
                KeyValueRow(stringResource(R.string.detail_to), message.to, selectable = true)
                if (message.cc.isNotEmpty()) KeyValueRow(stringResource(R.string.detail_cc), message.cc, selectable = true)
                KeyValueRow(stringResource(R.string.detail_date), dates.full(message.sentAt))
                KeyValueRow(stringResource(R.string.detail_account), mail.account(message.accountId)?.name.orEmpty())
                if (folder != null) KeyValueRow(stringResource(R.string.detail_folder), folderPathLabel(resources, folder))
            }
            else -> {
                val attachments = (watchedContent(mail, message.id) as? MailContent.Ready)?.attachments.orEmpty()
                if (attachments.isEmpty()) {
                    MessageScreen(title = stringResource(R.string.inspector_no_attachments), icon = MailIcons.Attachment)
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(attachments) { attachment ->
                            DeskListRow(
                                title = attachment.name,
                                leading = MailIcons.Attachment,
                                trailing = Formatter.formatShortFileSize(context, attachment.bytes),
                                onClick = { files.handle(attachment, AttachmentAction.Open) },
                                menu = {
                                    listOf(
                                        MenuEntry.Item(openLabel, { files.handle(attachment, AttachmentAction.Open) }),
                                        MenuEntry.Item(saveLabel, { files.handle(attachment, AttachmentAction.Save) }),
                                        MenuEntry.Item(shareLabel, { files.handle(attachment, AttachmentAction.Share) }, icon = MailIcons.Share),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
