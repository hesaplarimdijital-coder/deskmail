package com.deskmail.core.mail

import java.io.Closeable

/**
 * A signed-in account on its server, as the sync uses it. (The IMAP implementation is
 * [com.deskmail.core.imap.ImapConnection]; the sync's own tests put a made-up server behind this.)
 */
interface RemoteAccount : Closeable {
    /** Every folder of the account in the server's order, each with its role. */
    fun folders(): List<RemoteFolder>

    /** How many messages a folder holds and how many are unread, without opening it; null where the server will not say. */
    fun counts(path: String): FolderCounts?

    /** Opens a folder for reading. One at a time: close it before opening the next. */
    fun open(path: String): OpenMailbox

    /** Opens a folder for changing what is in it. One at a time, as with [open]. */
    fun edit(path: String): EditableMailbox
}

/** A [RemoteMailbox] that is closed when its reader is done. */
interface OpenMailbox : RemoteMailbox, Closeable

/** A folder's size as the server counts it. */
data class FolderCounts(val total: Int, val unread: Int)
