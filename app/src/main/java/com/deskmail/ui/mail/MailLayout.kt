package com.deskmail.ui.mail

/**
 * The numbers behind the layout of a view over the mail, in dp. "Room" is the width beside the
 * navigation (the rail, the sidebar): what the list and the reader have between them.
 */
object MailLayout {
    /** With this much room the list and the reader stand side by side; with less, a message covers the list. */
    const val TWO_PANES_FROM = 880f

    /** The list is never narrower than this beside the reader, the reader never narrower than [MIN_READER]. */
    const val MIN_LIST = 320f
    const val MIN_READER = 360f

    /**
     * A list at least this wide can be a table (with a mouse; a finger always gets the rows).
     * Narrower, a table's columns show a few letters each and the two-line rows say more.
     */
    const val TABLE_FROM = 520f

    /** The room the divider between list and reader takes, at its widest (touch). */
    private const val DIVIDER = 16f

    /**
     * The width of the list in [room] when it is to have [share] of it (0 to 1): the share, kept
     * within what leaves both panes usable.
     */
    fun listWidth(room: Float, share: Float): Float {
        val widest = (room - MIN_READER - DIVIDER).coerceAtLeast(MIN_LIST)
        return (room * share).coerceIn(MIN_LIST, widest)
    }

    /**
     * The share of [room] that a list dragged to [width] has. The kit's divider lets a drag run on
     * past the panes' limits; the share is that of the width that was shown.
     */
    fun listShare(room: Float, width: Float): Float {
        val widest = (room - MIN_READER - DIVIDER).coerceAtLeast(MIN_LIST)
        return width.coerceIn(MIN_LIST, widest) / room
    }
}
