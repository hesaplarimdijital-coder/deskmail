package com.deskmail.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.deskmail.R
import com.deskmail.mail.MailMessage
import com.deskmail.ui.MailIcons
import desktopkit.components.ChipOption
import desktopkit.components.ColumnWidth
import desktopkit.components.DenseTable
import desktopkit.components.DeskDragPayload
import desktopkit.components.DeskListRow
import desktopkit.components.FilterChips
import desktopkit.components.MenuEntry
import desktopkit.components.SwipeAction
import desktopkit.components.TableColumn
import desktopkit.components.TableSort
import desktopkit.components.rememberTableColumnsState
import desktopkit.pluralString
import desktopkit.settings.DeskPreferences
import desktopkit.theme.DeskTheme

/** The chips over a list: All, Unread, Starred, With attachments. "All" is no filter at all. */
@Composable
fun MailFilterChips(selected: Set<MailFilter>, onToggle: (MailFilter) -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(
        ChipOption<MailFilter?>(null, stringResource(R.string.filter_all)),
        ChipOption<MailFilter?>(MailFilter.Unread, stringResource(R.string.filter_unread)),
        ChipOption<MailFilter?>(MailFilter.Starred, stringResource(R.string.filter_starred)),
        ChipOption<MailFilter?>(MailFilter.Attachments, stringResource(R.string.filter_attachments)),
    )
    val shown: Set<MailFilter?> = if (selected.isEmpty()) setOf(null) else selected
    FilterChips(
        options = options,
        selected = shown,
        onToggle = { filter -> if (filter == null) onClear() else onToggle(filter) },
        modifier = modifier,
        // one line in every layout, scrolling sideways where it is short: a second line of chips
        // would take a row of messages off a narrow list
        wrap = false,
    )
}

/**
 * The messages as rows, for a finger: who it is from (bold while unread, with a dot before it),
 * the subject under it and a line of the message's first words under that, the date and the marks
 * at the end. The list lies under the phone's bars and pads its ends with [contentPadding] (the
 * shell's, which the screen asks for); [header], when there is one, scrolls away with the rows as
 * the list's first item.
 *
 * @param menu what can be done with a row's message: opened by a long press, a right click or the Menu key.
 * @param selection the rows that are selected; they are drawn as the open one is.
 * @param onPick set while rows are being picked: a tap adds or removes its row instead of opening it.
 * @param swipeStart what a finger does by sliding a row toward its end (archive), [swipeEnd] toward
 *   its start (snooze); null for a row it cannot be done to. Not while rows are being picked.
 */
@Composable
fun MessageRows(
    messages: List<MailMessage>,
    openedId: Long?,
    state: LazyListState,
    onOpen: (MailMessage) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    empty: @Composable () -> Unit = {},
    menu: ((MailMessage) -> List<MenuEntry>)? = null,
    selection: Set<Long> = emptySet(),
    onPick: ((MailMessage) -> Unit)? = null,
    swipeStart: ((MailMessage) -> SwipeAction?)? = null,
    swipeEnd: ((MailMessage) -> SwipeAction?)? = null,
) {
    val dates = rememberDateLabels()
    LazyColumn(modifier.fillMaxSize(), state = state, contentPadding = contentPadding) {
        if (header != null) item(key = "header", contentType = "header") { header() }
        if (messages.isEmpty()) item(key = "empty", contentType = "empty") { empty() }
        items(messages, key = { it.id }, contentType = { "message" }) { message ->
            MessageRow(
                message = message,
                // while rows are picked the open message is no row to tell apart
                selected = if (onPick != null) message.id in selection else message.id == openedId || message.id in selection,
                date = dates.short(message.sentAt),
                onOpen = onPick ?: onOpen,
                menu = menu,
                swipeStart = if (onPick == null) swipeStart?.invoke(message) else null,
                swipeEnd = if (onPick == null) swipeEnd?.invoke(message) else null,
            )
        }
    }
}

@Composable
private fun MessageRow(
    message: MailMessage,
    selected: Boolean,
    date: String,
    onOpen: (MailMessage) -> Unit,
    menu: ((MailMessage) -> List<MenuEntry>)?,
    swipeStart: SwipeAction?,
    swipeEnd: SwipeAction?,
) {
    val colors = DeskTheme.colors
    DeskListRow(
        title = counterpartLabel(message),
        subtitle = message.subject.ifBlank { stringResource(R.string.message_no_subject) },
        // the subject in the text's colour while the message is unread, its first words fainter under it
        subtitleColor = if (message.unread) colors.text else colors.textSecondary,
        preview = message.preview.ifEmpty { null },
        previewMaxLines = 1,
        leadingContent = { UnreadDot(message.unread) },
        trailingContent = { MessageMarks(message, date) },
        strong = message.unread,
        selected = selected,
        onClick = { onOpen(message) },
        menu = if (menu == null) null else ({ menu(message) }),
        swipeStart = swipeStart,
        swipeEnd = swipeEnd,
    )
}

/** The sender, or "To: …" for a message the user wrote; with how many messages a conversation's row stands for. */
@Composable
private fun counterpartLabel(message: MailMessage): String =
    withCount(if (message.outgoing) stringResource(R.string.message_to, message.to) else message.fromName, message)

/** [label] with the number of messages of the conversation the row stands for, where it stands for more than one. */
private fun withCount(label: String, message: MailMessage): String =
    if (message.threadCount > 1) "$label (${message.threadCount})" else label

/** The subject and the first words of the message on one line (a table's cell); [noSubject] stands in for a subject the mail does not have. */
private fun previewLine(message: MailMessage, noSubject: String): String {
    val subject = message.subject.ifBlank { noSubject }
    val preview = message.preview
    return if (preview.isEmpty()) subject else "$subject — $preview"
}

/** The accent dot before an unread message; a read one keeps the space, so the names line up. */
@Composable
private fun UnreadDot(unread: Boolean) {
    val label = stringResource(R.string.message_unread)
    val dot = if (unread) Modifier.background(DeskTheme.colors.accent, CircleShape).semantics { contentDescription = label } else Modifier
    Box(Modifier.size(8.dp).then(dot))
}

/** The date over the marks: answered or sent on, a paperclip for attachments, a star. */
@Composable
private fun MessageMarks(message: MailMessage, date: String) {
    val colors = DeskTheme.colors
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(date, style = DeskTheme.type.caption, color = if (message.unread) colors.accentText else colors.textTertiary, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (message.answered) {
                Icon(MailIcons.Reply, contentDescription = stringResource(R.string.message_answered), tint = colors.textTertiary, modifier = Modifier.size(MARK))
            }
            if (message.forwarded) {
                Icon(MailIcons.ForwardMail, contentDescription = stringResource(R.string.message_forwarded), tint = colors.textTertiary, modifier = Modifier.size(MARK))
            }
            if (message.attachmentCount > 0) {
                Icon(
                    MailIcons.Attachment,
                    contentDescription = pluralString(R.plurals.message_attachments, message.attachmentCount),
                    tint = colors.textTertiary,
                    modifier = Modifier.size(MARK),
                )
            }
            if (message.starred) {
                Icon(MailIcons.StarFilled, contentDescription = stringResource(R.string.message_starred), tint = colors.warning, modifier = Modifier.size(MARK))
            }
            // a row without marks is as tall as one with them
            Spacer(Modifier.size(width = 0.dp, height = MARK))
        }
    }
}

private val MARK = 14.dp

/**
 * The messages as a table, for a mouse: sortable columns, the header's menu chooses and orders
 * them ([prefs] remembers the choice). An unread message's row is bold, with a dot in a narrow
 * column of its own; the marks are drawn in theirs: the star, which a click turns on and off
 * without opening the message ([onToggleStar]), answered and sent on, a paperclip
 * (desktop-kit 2.8.0; docs/KIT-ISTEKLERI.md, 2). What a column is sorted by is its value.
 *
 * Several rows are selected the way the kit's table has it: Ctrl+click adds or removes a row,
 * Shift+click takes the rows in between, Ctrl+A all of them, Esc none; a plain click selects its
 * row alone and opens it. With [picking] every click adds or removes its row. Dragging from a row
 * that is not selected draws a frame, and the rows it touches are selected (the kit's table does
 * that); selected rows are dragged together ([drag] says what travels).
 */
@Composable
fun MessageTable(
    messages: List<MailMessage>,
    openedId: Long?,
    state: LazyListState,
    sort: TableSort,
    onSortChange: (TableSort) -> Unit,
    onOpen: (MailMessage) -> Unit,
    prefs: DeskPreferences?,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    menu: ((MailMessage) -> List<MenuEntry>)? = null,
    selection: Set<Long> = emptySet(),
    onSelect: ((Set<Long>) -> Unit)? = null,
    picking: Boolean = false,
    drag: ((MailMessage) -> DeskDragPayload?)? = null,
    onToggleStar: ((MailMessage) -> Unit)? = null,
) {
    val dates = rememberDateLabels()
    val toPrefix = stringResource(R.string.message_to, "")
    val noSubject = stringResource(R.string.message_no_subject)
    val starLabel = stringResource(R.string.action_star)
    val unstarLabel = stringResource(R.string.action_unstar)
    // in the order of MessageColumns
    val columns = listOf(
        TableColumn<MailMessage>(UNREAD_MARK, ColumnWidth.Fixed(36.dp), id = "unread", cell = { UnreadDot(it.unread) }) { if (it.unread) UNREAD_MARK else "" },
        TableColumn<MailMessage>(
            STAR_MARK,
            ColumnWidth.Fixed(36.dp),
            id = "star",
            cell = { StarMark(it.starred) },
            onCellClick = onToggleStar,
            cellClickLabel = { if (it.starred) unstarLabel else starLabel },
        ) { if (it.starred) STAR_MARK else "" },
        // answered, sent on, or both
        TableColumn<MailMessage>(ANSWERED_MARK, ColumnWidth.Fixed(36.dp), id = "answered", cell = { AnsweredMarks(it) }) { answeredMark(it) },
        // wide enough for a name whole, at any width of the table; the subject takes what is left
        TableColumn<MailMessage>(stringResource(R.string.column_from), ColumnWidth.Fixed(128.dp), id = "from") {
            withCount(if (it.outgoing) toPrefix + it.to else it.fromName, it)
        },
        TableColumn<MailMessage>(stringResource(R.string.column_subject), ColumnWidth.Weight(1f), id = "subject") { previewLine(it, noSubject) },
        TableColumn<MailMessage>(
            stringResource(R.string.column_attachments),
            ColumnWidth.Fixed(48.dp),
            alignEnd = true,
            mono = true,
            id = "attachments",
            cell = { AttachmentMark(it.attachmentCount) },
        ) { if (it.attachmentCount == 0) "" else it.attachmentCount.toString() },
        TableColumn<MailMessage>(stringResource(R.string.column_date), ColumnWidth.Fixed(104.dp), alignEnd = true, mono = true, id = "date") {
            dates.short(it.sentAt)
        },
    )
    DenseTable(
        columns = columns,
        rows = messages,
        key = { it.id },
        onRowClick = { message, _ -> onOpen(message) },
        modifier = modifier,
        state = state,
        selectedKey = openedId,
        sort = sort,
        onSortChange = onSortChange,
        rowMenu = if (menu == null) null else ({ message, _ -> menu(message) }),
        contentPadding = PaddingValues(bottom = 8.dp + bottomPadding),
        selectedKeys = if (onSelect == null) null else selection,
        onSelectionChange = if (onSelect == null) null else ({ keys -> onSelect(keys.filterIsInstance<Long>().toSet()) }),
        toggleOnClick = picking,
        columnsState = if (prefs == null) null else rememberTableColumnsState("messages", prefs),
        rowDrag = if (drag == null) null else ({ message, _ -> drag(message) }),
        rowEmphasis = { it.unread },
    )
}

/** A table's star: the accent star of a starred message, a faint outline to click for another one. */
@Composable
private fun StarMark(starred: Boolean) {
    val colors = DeskTheme.colors
    if (starred) {
        Icon(MailIcons.StarFilled, contentDescription = stringResource(R.string.message_starred), tint = colors.warning, modifier = Modifier.size(MARK))
    } else {
        Icon(MailIcons.Star, contentDescription = null, tint = colors.textTertiary.copy(alpha = 0.5f), modifier = Modifier.size(MARK))
    }
}

/** A table's marks of a message that was answered, sent on, or both. */
@Composable
private fun AnsweredMarks(message: MailMessage) {
    val tint = DeskTheme.colors.textTertiary
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (message.answered) Icon(MailIcons.Reply, contentDescription = stringResource(R.string.message_answered), tint = tint, modifier = Modifier.size(MARK))
        if (message.forwarded) Icon(MailIcons.ForwardMail, contentDescription = stringResource(R.string.message_forwarded), tint = tint, modifier = Modifier.size(MARK))
    }
}

/** A table's paperclip, with the number of files where there is more than one. */
@Composable
private fun AttachmentMark(count: Int) {
    if (count == 0) return
    val colors = DeskTheme.colors
    Row(
        Modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count > 1) Text(count.toString(), style = DeskTheme.type.mono, color = colors.textSecondary, maxLines = 1)
        Icon(MailIcons.Attachment, contentDescription = pluralString(R.plurals.message_attachments, count), tint = colors.textTertiary, modifier = Modifier.size(MARK))
    }
}

private const val UNREAD_MARK = "●"
private const val STAR_MARK = "★"
private const val ANSWERED_MARK = "↩"
private const val FORWARDED_MARK = "→"

/** What the table's narrow column says of a message that was answered or sent on. */
internal fun answeredMark(message: MailMessage): String = (if (message.answered) ANSWERED_MARK else "") + (if (message.forwarded) FORWARDED_MARK else "")
