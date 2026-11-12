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
import desktopkit.components.DeskListRow
import desktopkit.components.FilterChips
import desktopkit.components.TableColumn
import desktopkit.components.TableSort
import desktopkit.components.rememberTableColumnsState
import desktopkit.pluralString
import desktopkit.settings.DeskPreferences
import desktopkit.shell.rememberShellContentPadding
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
    )
}

/**
 * The messages as rows, for a finger: who it is from (bold while unread, with a dot before it),
 * the subject and the first words under it, the date and the marks at the end. The list lies under
 * the phone's bars and pads its ends with the shell's padding; [header] scrolls away with the rows.
 */
@Composable
fun MessageRows(
    messages: List<MailMessage>,
    openedId: Long?,
    state: LazyListState,
    onOpen: (MailMessage) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
    empty: @Composable () -> Unit = {},
) {
    val dates = rememberDateLabels()
    LazyColumn(modifier.fillMaxSize(), state = state, contentPadding = rememberShellContentPadding()) {
        item(key = "header", contentType = "header") { header() }
        if (messages.isEmpty()) item(key = "empty", contentType = "empty") { empty() }
        items(messages, key = { it.id }, contentType = { "message" }) { message ->
            MessageRow(message, selected = message.id == openedId, date = dates.short(message.sentAt), onOpen = onOpen)
        }
    }
}

@Composable
private fun MessageRow(message: MailMessage, selected: Boolean, date: String, onOpen: (MailMessage) -> Unit) {
    val colors = DeskTheme.colors
    DeskListRow(
        title = counterpartLabel(message),
        subtitle = previewLine(message),
        subtitleColor = if (message.unread) colors.textSecondary else colors.textTertiary,
        leadingContent = { UnreadDot(message.unread) },
        trailingContent = { MessageMarks(message, date) },
        strong = message.unread,
        selected = selected,
        onClick = { onOpen(message) },
    )
}

/** The sender, or "To: …" for a message the user wrote. */
@Composable
private fun counterpartLabel(message: MailMessage): String =
    if (message.outgoing) stringResource(R.string.message_to, message.to) else message.fromName

/** The subject and the first words of the message on one line. */
private fun previewLine(message: MailMessage): String {
    val preview = message.preview
    return if (preview.isEmpty()) message.subject else message.subject + " — " + preview
}

/** The accent dot before an unread message; a read one keeps the space, so the names line up. */
@Composable
private fun UnreadDot(unread: Boolean) {
    val label = stringResource(R.string.message_unread)
    val dot = if (unread) Modifier.background(DeskTheme.colors.accent, CircleShape).semantics { contentDescription = label } else Modifier
    Box(Modifier.size(8.dp).then(dot))
}

/** The date over the marks: a paperclip for attachments, a star. */
@Composable
private fun MessageMarks(message: MailMessage, date: String) {
    val colors = DeskTheme.colors
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(date, style = DeskTheme.type.caption, color = if (message.unread) colors.accentText else colors.textTertiary, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (message.attachments.isNotEmpty()) {
                Icon(
                    MailIcons.Attachment,
                    contentDescription = pluralString(R.plurals.message_attachments, message.attachments.size),
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
 * them ([prefs] remembers the choice). desktop-kit 2.4.0 draws a cell as text in its column's
 * style, so an unread message is marked by a dot in a column of its own, a star and the number of
 * attachments likewise (docs/KIT-ISTEKLERI.md, 2).
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
) {
    val dates = rememberDateLabels()
    val toPrefix = stringResource(R.string.message_to, "")
    // in the order of MessageColumns
    val columns = listOf(
        TableColumn<MailMessage>(UNREAD_MARK, ColumnWidth.Fixed(36.dp), id = "unread") { if (it.unread) UNREAD_MARK else "" },
        TableColumn<MailMessage>(STAR_MARK, ColumnWidth.Fixed(36.dp), id = "star") { if (it.starred) STAR_MARK else "" },
        TableColumn<MailMessage>(stringResource(R.string.column_from), ColumnWidth.Weight(0.3f), id = "from") {
            if (it.outgoing) toPrefix + it.to else it.fromName
        },
        TableColumn<MailMessage>(stringResource(R.string.column_subject), ColumnWidth.Weight(0.7f), id = "subject") { previewLine(it) },
        TableColumn<MailMessage>(stringResource(R.string.column_attachments), ColumnWidth.Fixed(48.dp), alignEnd = true, mono = true, id = "attachments") {
            if (it.attachments.isEmpty()) "" else it.attachments.size.toString()
        },
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
        contentPadding = PaddingValues(bottom = 8.dp + bottomPadding),
        columnsState = if (prefs == null) null else rememberTableColumnsState("messages", prefs),
    )
}

private const val UNREAD_MARK = "●"
private const val STAR_MARK = "★"
