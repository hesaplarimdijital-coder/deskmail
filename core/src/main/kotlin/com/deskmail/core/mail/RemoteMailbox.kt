package com.deskmail.core.mail

import com.deskmail.core.text.SearchTerm
import java.io.OutputStream
import java.time.LocalDate

/**
 * An open folder on the server, as the sync reads it. (The IMAP implementation is
 * [com.deskmail.core.imap.ImapMailbox]; the sync's own tests put a made-up server behind this.)
 */
interface RemoteMailbox {
    companion object {
        /** A message's header lines are kept up to this many characters: a header block is seldom longer than a few thousand. */
        const val MAX_HEADERS = 64_000
    }

    /** The server's stamp on this folder's UIDs: when it differs from last time, every UID known of the folder is void. */
    val uidValidity: Long

    /** The UID the next message will get; 0 where the server does not say. */
    val uidNext: Long

    /** The folder's change counter (CONDSTORE); 0 where the server keeps none. */
    val highestModSeq: Long

    /** How many messages the folder holds, and how many of them are unread: the whole folder. */
    val total: Int
    val unread: Int

    /** The UIDs of the messages the server received on [since] or later, or of all of them; ascending. */
    fun uids(since: LocalDate?): List<Long>

    /** The messages with these UIDs as a list shows them. A UID the folder no longer has is left out. */
    fun headers(uids: List<Long>): List<RemoteMessage>

    /** The flags of the messages with these UIDs, as they are now. */
    fun flags(uids: List<Long>): List<RemoteFlags>

    /**
     * The flags of every message whose flags changed after the counter stood at [modSeq];
     * null where the server cannot tell (no CONDSTORE), and all flags must be asked for.
     */
    fun flagsChangedSince(modSeq: Long): List<RemoteFlags>?

    /**
     * What the message says. A text part larger than [maxTextBytes] stays on the server.
     * Null when the folder no longer has the message.
     */
    fun body(uid: Long, maxTextBytes: Int): RemoteBody?

    /**
     * The message's header lines as they arrived, up to [MAX_HEADERS] characters: what the inspector
     * shows as they are, and where the provider's authentication results are read from. Null when
     * the message is gone; empty where the server says nothing of them.
     */
    fun headers(uid: Long): String? = null

    /**
     * Writes a file the message carries into [into], decoded: the part at [part], as
     * [RemoteAttachment.part] names it. False when the folder no longer has the message, or the
     * message no such part.
     */
    fun attachment(uid: Long, part: String, into: OutputStream): Boolean

    /**
     * The UIDs of the messages the server finds for [terms], ascending: every term must hold. A
     * term for anything is looked for in the message's headers and its text; the server compares
     * as it does (usually as a part of a word, without regard to case), not as the device's index.
     */
    fun search(terms: List<SearchTerm>): List<Long> = emptyList()

    /** The UIDs of the folder's messages whose Message-ID is [messageId]: a snoozed message, found again. */
    fun withMessageId(messageId: String): List<Long> = emptyList()
}
