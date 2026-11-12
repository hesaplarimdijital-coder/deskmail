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

    /** Every account's folders; how a phone or a tablet's rail reaches them. */
    const val FOLDERS = "folders"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    private const val FOLDER = "folder/"

    fun folder(id: Long): String = FOLDER + id

    /** The folder a route shows, or null for any other route. */
    fun folderId(route: String?): Long? =
        if (route != null && route.startsWith(FOLDER)) route.substring(FOLDER.length).toLongOrNull() else null
}
