package com.deskmail.ui.mail

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deskmail.mail.MailMessage
import com.deskmail.mail.ServerSearch
import com.deskmail.notify.OpenRequest
import com.deskmail.settings.AppSettings
import com.deskmail.ui.compose.ComposeRequest
import com.deskmail.ui.compose.Writing
import desktopkit.components.InspectorState
import desktopkit.components.SplitState
import desktopkit.components.TableSort

/** What a list can be narrowed to; more than one narrows further. */
enum class MailFilter { Unread, Starred, Attachments }

/**
 * What one window shows of the mail: per screen (route) the open message, the filters and the
 * list's scroll position; for every screen the table's sort order, the width of the list beside
 * the reader, the messages whose remote images the user asked to see, and the message that is
 * being written.
 *
 * It lives outside the screens on purpose. The shell drops what its screens saved whenever the
 * list of folders changes (docs/KIT-ISTEKLERI.md, 3), and a screen is laid out anew when its window
 * crosses from list → message to two panes: neither may lose the open message.
 */
@Stable
class MailUiState(private val settings: AppSettings? = null) {
    private val opened = mutableStateMapOf<String, Long>()
    private val selections = mutableStateMapOf<String, Set<Long>>()
    private val pickings = mutableStateMapOf<String, Boolean>()
    private val filters = mutableStateMapOf<String, Set<MailFilter>>()
    private val images = mutableStateMapOf<Long, Boolean>()
    private val lists = HashMap<String, LazyListState>()
    private val shown = HashMap<String, Shown>()

    /** A screen's messages in the order it lists them, and how many items its list has before the first. */
    private class Shown(val ids: List<Long>, val leadingItems: Int)

    /** The table's sort: its column (see [MessageColumns]) and direction. Newest first to begin with. */
    var sort by mutableStateOf(TableSort(MessageColumns.DATE, ascending = false))

    /**
     * The list's share of the room it has with the reader, shared by every screen:
     * [AppSettings.listShare] to begin with, then what the divider is dragged to in this window.
     */
    var listShare = settings?.listShare ?: AppSettings.DEFAULT_LIST_SHARE
        private set

    /**
     * The divider between the list and the reader. The kit keeps a width in it; [fitList] makes that
     * width the list's share of the window's room, so the split follows a window that is resized.
     */
    val listSplit = SplitState(MailLayout.listWidth(MailLayout.TWO_PANES_FROM, listShare))

    /** The room last fitted to, and the width that gave the list: any other width in [listSplit] was dragged to. */
    private var room = 0f
    private var fitted = Float.NaN

    /**
     * The inspector of a large window, shared by every screen. Its width and whether it is open
     * are the app's settings ([AppSettings.inspectorCollapsed]: closed to begin with); without
     * settings (a test) they last as long as the window.
     */
    val inspector = InspectorState(
        initialWidthDp = settings?.inspectorWidth ?: 320f,
        initialCollapsed = settings?.inspectorCollapsed ?: true,
    ) { width, collapsed -> settings?.updateInspector(width, collapsed) }

    /**
     * Told by a screen how much room the list and the reader have between them: gives the list its
     * share of it. A drag since the last time becomes the share first.
     */
    fun fitList(room: Float) {
        noteDraggedList()
        this.room = room
        val width = MailLayout.listWidth(room, listShare)
        fitted = width
        if (listSplit.startWidthDp != width) listSplit.startWidthDp = width
    }

    /**
     * Looks whether the divider was dragged since the list was last fitted: the width it was
     * dragged to becomes the list's share, here and in the settings.
     */
    fun noteDraggedList() {
        val width = listSplit.startWidthDp
        if (room <= 0f || width == fitted) return
        fitted = width
        listShare = MailLayout.listShare(room, width)
        settings?.updateListShare(listShare)
    }

    fun openedId(route: String): Long? = opened[route]

    fun open(route: String, id: Long) {
        opened[route] = id
        // One selected row is the open message's row: clicked with the mouse, the table selects it
        // itself; opened by other means (the keyboard), the selection follows. Ctrl+click and
        // Shift+click then go on from the open message.
        if (!picking(route) && selection(route).size <= 1) selections[route] = setOf(id)
    }

    fun close(route: String) {
        opened.remove(route)
        if (!picking(route) && selection(route).size <= 1) selections.remove(route)
    }

    /** Closes these messages wherever a screen has one of them open, and takes them out of what is selected: they have left their lists. */
    fun closeMessages(ids: Collection<Long>) {
        opened.entries.filter { it.value in ids }.map { it.key }.forEach { opened.remove(it) }
        for ((route, chosen) in selections.entries.toList()) {
            if (chosen.any { it in ids }) select(route, chosen - ids.toSet())
        }
    }

    // ------------------------------------------------------------------ several messages at once

    /**
     * The messages that are selected in [route]'s list. A row that was clicked with a mouse is
     * selected too; several of them are what the selection bar and the row menu act on.
     */
    fun selection(route: String): Set<Long> = selections[route] ?: emptySet()

    fun select(route: String, ids: Set<Long>) {
        if (ids.isEmpty()) {
            selections.remove(route)
            pickings.remove(route)
        } else {
            selections[route] = ids
        }
    }

    /**
     * Whether rows are being picked with a finger in [route]'s list ("Select" in a row's menu):
     * a tap then adds or removes its row instead of opening it. Ends with the last row taken out.
     */
    fun picking(route: String): Boolean = pickings[route] == true

    /** Begins to pick rows with a finger, with this one; rows that were selected together stay selected. */
    fun pick(route: String, id: Long) {
        val chosen = selection(route)
        // one selected row is only the open message's: picking begins with the row it was asked on
        val kept = if (picking(route) || chosen.size > 1) chosen else emptySet()
        pickings[route] = true
        selections[route] = kept + id
    }

    /** Adds the row to what is selected, or takes it out. */
    fun togglePicked(route: String, id: Long) {
        val chosen = selection(route)
        select(route, if (id in chosen) chosen - id else chosen + id)
    }

    fun clearSelection(route: String) = select(route, emptySet())

    /**
     * What an action on [route]'s list is done to: the selection when several rows are selected
     * (or are being picked), every message the rows stand for ([standFor]: a conversation's row
     * stands for all of its messages in the list); otherwise the open message; empty when there is
     * neither.
     */
    fun targets(route: String): List<Long> {
        val chosen = selection(route)
        if (chosen.size > 1 || (picking(route) && chosen.isNotEmpty())) {
            val all = standing[route]
            return if (all != null && all.first == chosen) all.second else chosen.toList()
        }
        return listOfNotNull(openedId(route))
    }

    /** For each route, the selection as its screen last listed it, and every message those rows stand for. */
    private val standing = HashMap<String, Pair<Set<Long>, List<Long>>>()

    /**
     * The screen of [route] says what its selected rows ([chosen]) stand for ([ids]): the commands of
     * the keyboard and the menus act on all of them, as its bar does.
     */
    fun standFor(route: String, chosen: Set<Long>, ids: List<Long>) {
        standing[route] = chosen to ids
    }

    /** The message of the user's own that is being written in this window, and over which screen; null for none. */
    var writing by mutableStateOf<Writing?>(null)

    /** The message being written over [route], if that is where one is being written. */
    fun writingOver(route: String): Writing? = writing?.takeIf { it.route == route }

    /** A message another app asked to be written (a mailto link, something shared); taken up, and cleared, by the window's shell. */
    var composeRequest by mutableStateOf<ComposeRequest?>(null)

    /** A message a notification asked to be opened; taken up, and cleared, by the window's shell. */
    var openRequest by mutableStateOf<OpenRequest?>(null)

    /** What is typed into the search field. */
    var searchText by mutableStateOf("")

    /** What the search screen is looking for: what is typed, a moment after the typing pauses. */
    var searchQuery by mutableStateOf("")

    /** The last search of the servers: for what (its key, [com.deskmail.core.text.SearchQuery.key]) and what it came to; null while it runs. */
    var serverSearch by mutableStateOf<ServerSearchState?>(null)

    /** "/" asked for the search field: it takes the focus when its screen is drawn. */
    var searchFocusAsked by mutableStateOf(false)

    /** Looks for [text] at once, as if it had been typed and waited for. */
    fun search(text: String) {
        searchText = text
        searchQuery = text
    }

    /** The messages a time is being chosen for ("Snooze…"); empty while none is. */
    var snoozing by mutableStateOf<List<Long>>(emptyList())

    private var movingMessage by mutableStateOf<MailMessage?>(null)

    /** The message a folder is being chosen for ("Move…"), or null. Alone, unless [moveSeveral] set it. */
    var moving: MailMessage?
        get() = movingMessage
        set(value) {
            movingMessage = value
            movingIds = emptyList()
        }

    /** Everything that moves to the folder chosen for [moving]: a selection. Empty for the one message alone. */
    var movingIds: List<Long> = emptyList()
        private set

    /** A folder is to be chosen for several messages; [anchor], one of them, says which folders there are to choose from. */
    fun moveSeveral(anchor: MailMessage, ids: List<Long>) {
        movingMessage = anchor
        movingIds = ids
    }

    /** How many message bodies this window is drawing with a WebView right now: what the shell's glass goes by ([shellGlass]). */
    var webBodies by mutableIntStateOf(0)
        private set

    fun webBodyShown() {
        webBodies++
    }

    fun webBodyGone() {
        if (webBodies > 0) webBodies--
    }

    /** Whether the images a message asks for from the network are loaded: only after the reader said so, and until the window closes. */
    fun showsImages(id: Long): Boolean = images[id] == true

    fun showImages(id: Long) {
        images[id] = true
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

    /**
     * Told by a screen what it lists, filtered and sorted as it is shown: the keyboard steps through
     * that. [leadingItems] is how many items of its list come before the first message (the chips).
     */
    fun setShown(route: String, ids: List<Long>, leadingItems: Int) {
        shown[route] = Shown(ids, leadingItems)
    }

    /**
     * The message [step] rows after (or before) the open one in the list as it is shown; with none
     * open, the first one going down and the last one going up. Null at the ends of the list.
     */
    fun neighbour(route: String, step: Int): Long? {
        val ids = shown[route]?.ids.orEmpty()
        if (ids.isEmpty()) return null
        val index = ids.indexOf(opened[route])
        return if (index < 0) (if (step > 0) ids.first() else ids.last()) else ids.getOrNull(index + step)
    }

    /**
     * Scrolls [route]'s list just far enough to show the message [id] clear of what covers the
     * list's ends (the phone's bars, a table's header): for a message opened from the keyboard.
     */
    suspend fun reveal(route: String, id: Long) {
        val list = shown[route] ?: return
        val state = lists[route] ?: return
        val index = list.ids.indexOf(id)
        if (index < 0) return
        val item = index + list.leadingItems
        val info = state.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == item }
        val end = info.viewportEndOffset - info.afterContentPadding
        when {
            row == null -> state.scrollToItem((item - 1).coerceAtLeast(0))
            row.offset < 0 -> state.animateScrollBy(row.offset.toFloat())
            row.offset + row.size > end -> state.animateScrollBy((row.offset + row.size - end).toFloat())
        }
    }
}

/** The columns of the message table, by their place in it; the sort refers to them. */
object MessageColumns {
    const val UNREAD = 0
    const val STAR = 1
    const val ANSWERED = 2
    const val FROM = 3
    const val SUBJECT = 4
    const val ATTACHMENTS = 5
    const val DATE = 6
}

/** [messages] narrowed by [filters]; every filter must hold. */
fun filterMessages(messages: List<MailMessage>, filters: Set<MailFilter>): List<MailMessage> =
    if (filters.isEmpty()) messages else messages.filter { message ->
        filters.all { filter ->
            when (filter) {
                MailFilter.Unread -> message.unread
                MailFilter.Starred -> message.starred
                MailFilter.Attachments -> message.attachmentCount > 0
            }
        }
    }

/** [messages] in the table's order. Equal messages keep the order they came in (newest first). */
fun sortMessages(messages: List<MailMessage>, sort: TableSort): List<MailMessage> {
    val sorted = when (sort.column) {
        MessageColumns.UNREAD -> messages.sortedBy { !it.unread }
        MessageColumns.STAR -> messages.sortedBy { !it.starred }
        MessageColumns.ANSWERED -> messages.sortedBy { !(it.answered || it.forwarded) }
        MessageColumns.FROM -> messages.sortedBy { counterpart(it).lowercase() }
        MessageColumns.SUBJECT -> messages.sortedBy { it.subject.lowercase() }
        MessageColumns.ATTACHMENTS -> messages.sortedBy { -it.attachmentCount }
        else -> messages.sortedBy { it.sentAt }
    }
    return if (sort.ascending) sorted else sorted.reversed()
}

/** Who a message is with: its sender, or its recipients for one the user wrote. */
fun counterpart(message: MailMessage): String = if (message.outgoing) message.to else message.fromName

/** A search of the servers for what was typed ([key]); [result] is null while it runs. */
@Immutable
class ServerSearchState(val key: String, val result: ServerSearch?)
