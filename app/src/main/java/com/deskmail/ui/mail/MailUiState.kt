package com.deskmail.ui.mail

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deskmail.mail.MailMessage
import desktopkit.components.InspectorState
import desktopkit.components.SplitState
import desktopkit.components.TableSort

/** What a list can be narrowed to; more than one narrows further. */
enum class MailFilter { Unread, Starred, Attachments }

/**
 * What one window shows of the mail: per screen (route) the open message, the filters and the
 * list's scroll position; for every screen the table's sort order and the width of the list beside
 * the reader.
 *
 * It lives outside the screens on purpose. The shell drops what its screens saved whenever the
 * list of folders changes (docs/KIT-ISTEKLERI.md, 3), and a screen is laid out anew when its window
 * crosses from list → message to two panes: neither may lose the open message.
 */
@Stable
class MailUiState {
    private val opened = mutableStateMapOf<String, Long>()
    private val filters = mutableStateMapOf<String, Set<MailFilter>>()
    private val lists = HashMap<String, LazyListState>()

    /** The table's sort: its column (see [MessageColumns]) and direction. Newest first to begin with. */
    var sort by mutableStateOf(TableSort(MessageColumns.DATE, ascending = false))

    /** The width of the list beside the reader, shared by every screen. */
    val listSplit = SplitState(440f)

    /**
     * The inspector of a large window, shared by every screen. Closed to begin with: beside the
     * sidebar, the table and the reader it leaves little room on anything but a monitor.
     */
    val inspector = InspectorState(initialWidthDp = 320f, initialCollapsed = true)

    fun openedId(route: String): Long? = opened[route]

    fun open(route: String, message: MailMessage) {
        opened[route] = message.id
    }

    fun close(route: String) {
        opened.remove(route)
    }

    fun filters(route: String): Set<MailFilter> = filters[route] ?: emptySet()

    fun toggleFilter(route: String, filter: MailFilter) {
        val current = filters(route)
        filters[route] = if (filter in current) current - filter else current + filter
    }

    fun clearFilters(route: String) {
        filters.remove(route)
    }

    fun listState(route: String): LazyListState = lists.getOrPut(route) { LazyListState() }
}

/** The columns of the message table, by their place in it; the sort refers to them. */
object MessageColumns {
    const val UNREAD = 0
    const val STAR = 1
    const val FROM = 2
    const val SUBJECT = 3
    const val ATTACHMENTS = 4
    const val DATE = 5
}

/** [messages] narrowed by [filters]; every filter must hold. */
fun filterMessages(messages: List<MailMessage>, filters: Set<MailFilter>): List<MailMessage> =
    if (filters.isEmpty()) messages else messages.filter { message ->
        filters.all { filter ->
            when (filter) {
                MailFilter.Unread -> message.unread
                MailFilter.Starred -> message.starred
                MailFilter.Attachments -> message.attachments.isNotEmpty()
            }
        }
    }

/** [messages] in the table's order. Equal messages keep the order they came in (newest first). */
fun sortMessages(messages: List<MailMessage>, sort: TableSort): List<MailMessage> {
    val sorted = when (sort.column) {
        MessageColumns.UNREAD -> messages.sortedBy { !it.unread }
        MessageColumns.STAR -> messages.sortedBy { !it.starred }
        MessageColumns.FROM -> messages.sortedBy { counterpart(it).lowercase() }
        MessageColumns.SUBJECT -> messages.sortedBy { it.subject.lowercase() }
        MessageColumns.ATTACHMENTS -> messages.sortedBy { -it.attachments.size }
        else -> messages.sortedBy { it.sentAt }
    }
    return if (sort.ascending) sorted else sorted.reversed()
}

/** Who a message is with: its sender, or its recipients for one the user wrote. */
fun counterpart(message: MailMessage): String = if (message.outgoing) message.to else message.fromName
