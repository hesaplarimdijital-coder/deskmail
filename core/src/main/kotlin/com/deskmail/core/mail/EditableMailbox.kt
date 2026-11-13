package com.deskmail.core.mail

import java.io.Closeable
import java.io.File

/**
 * A mark on a message: read and starred, which its reader sets and clears, and answered and
 * forwarded, which the app sets when a reply or a forwarded copy has been sent.
 */
enum class Mark { Seen, Flagged, Answered, Forwarded }

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

    /**
     * Whether [append] tells where the message came to lie (UIDPLUS). Without that a copy that
     * was put there cannot be taken away again by whoever put it there: nothing is kept on such a
     * server that is meant to be replaced, as a draft is.
     */
    val appendsWithUid: Boolean get() = true

    /**
     * Adds a message to the folder: the copy of what was sent, or a draft.
     *
     * @param message the whole message as it travels ([com.deskmail.core.mime.MessageWriter]).
     * @param seen whether it is put there as read; [draft] whether as a draft.
     * @return the UID it has in the folder where the server tells (UIDPLUS), else 0.
     */
    fun append(message: File, seen: Boolean, draft: Boolean): Long
}
