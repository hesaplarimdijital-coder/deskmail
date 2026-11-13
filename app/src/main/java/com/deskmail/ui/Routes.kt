package com.deskmail.ui

/**
 * The shell's routes: the views over every account, one per folder, and the screens. A route is
 * also what a new window is opened at.
 */
object Routes {
    /** The inboxes of every account in one list. */
    const val INBOX = "inbox"
    const val UNREAD = "unread"
    const val STARRED = "starred"

    /** The mail of the senders the user called VIPs. */
    const val VIP = "vip"

    /** The user's messages that answers are waited for. */
    const val AWAITING = "awaiting"

    /** Every account's folders; how a phone or a tablet's rail reaches them. */
    const val FOLDERS = "folders"
    const val SEARCH = "search"

    /** What is being written and what waits to be sent. */
    const val OUTBOX = "outbox"
    const val SETTINGS = "settings"

    private const val FOLDER = "folder/"
    private const val SAVED = "saved/"

    fun folder(id: Long): String = FOLDER + id

    /** A search kept as a folder of its own. */
    fun saved(id: Long): String = SAVED + id

    /** The saved search a route shows, or null for any other route. */
    fun savedId(route: String?): Long? =
        if (route != null && route.startsWith(SAVED)) route.substring(SAVED.length).toLongOrNull() else null

    /** The folder a route shows, or null for any other route. */
    fun folderId(route: String?): Long? =
        if (route != null && route.startsWith(FOLDER)) route.substring(FOLDER.length).toLongOrNull() else null
}
