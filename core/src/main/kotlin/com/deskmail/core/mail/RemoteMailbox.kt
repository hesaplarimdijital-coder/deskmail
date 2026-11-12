package com.deskmail.core.mail

import java.time.LocalDate

/**
 * An open folder on the server, as the sync reads it. (The IMAP implementation is
 * [com.deskmail.core.imap.ImapMailbox]; the sync's own tests put a made-up server behind this.)
 */
interface RemoteMailbox {
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
}
