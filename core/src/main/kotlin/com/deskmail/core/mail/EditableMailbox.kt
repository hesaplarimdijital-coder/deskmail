package com.deskmail.core.mail

import java.io.Closeable

/** A mark on a message that its reader sets and clears. */
enum class Mark { Seen, Flagged }

/**
 * A message that was moved: its UID where it was, and where the server tells (UIDPLUS) the UID it
 * has where it went, with that folder's UIDVALIDITY; both 0 where it does not.
 */
data class Moved(val uid: Long, val newUid: Long = 0, val newUidValidity: Long = 0)

/**
 * An open folder whose messages can be marked, moved and removed: how the server is told what was
 * done on the device. (The IMAP implementation is [com.deskmail.core.imap.ImapEditor]; the sync's
 * own tests put a made-up server behind this.)
 */
interface EditableMailbox : Closeable {
    /** The server's stamp on this folder's UIDs: a change made for another stamp's UIDs is not carried out. */
    val uidValidity: Long

    /** Sets or clears [mark] on the messages. A UID the folder no longer has is passed over. */
    fun mark(uids: List<Long>, mark: Mark, value: Boolean)

    /**
     * Moves the messages to the folder at [target].
     *
     * @return the messages that were moved. A UID the folder no longer had is missing from the answer.
     */
    fun move(uids: List<Long>, target: String): List<Moved>

    /** Removes the messages for good. A UID the folder no longer has is passed over. */
    fun expunge(uids: List<Long>)
}
