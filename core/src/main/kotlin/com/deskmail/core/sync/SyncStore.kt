package com.deskmail.core.sync

import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.RemoteAttachment
import com.deskmail.core.mail.RemoteFlags
import com.deskmail.core.mail.RemoteFolder
import com.deskmail.core.mail.RemoteMessage
import java.time.Instant

/**
 * What the last sync knew of a folder on the server.
 *
 * @param uidValidity the server's stamp on the folder's UIDs; 0 before the first sync.
 * @param uidNext the UID the next message will get.
 * @param highestModSeq the server's change counter (CONDSTORE); 0 where it keeps none.
 */
data class FolderState(val uidValidity: Long = 0, val uidNext: Long = 0, val highestModSeq: Long = 0)

/** A folder as the device knows it. */
data class StoredFolder(
    val id: Long,
    val path: String,
    val role: FolderRole,
    val selectable: Boolean,
    val state: FolderState = FolderState(),
)

/**
 * A message's content as it is kept on the device.
 *
 * @param text what it says as plain text: the mail's own, or read off its HTML.
 * @param html its HTML, cleaned ([com.deskmail.core.html.HtmlSanitizer]); null for a message without.
 * @param remoteImages whether the HTML asks for pictures from the network.
 * @param preview the first words, on one line, for the list.
 */
data class StoredBody(
    val text: String,
    val html: String?,
    val remoteImages: Boolean,
    val preview: String,
    val attachments: List<RemoteAttachment>,
    val headers: String = "",
)

/**
 * The device's copy of the mail, as the sync writes it. The app keeps it in its database; the
 * sync's tests keep it in memory. Messages are named by their folder and UID, as on the server.
 */
interface SyncStore {
    /**
     * Makes the account's folders those the server lists: new ones are added, ones that are gone are
     * removed with their messages, the others keep what was known of them. A role the user gave a
     * folder stays. Returns the folders in the server's order.
     */
    suspend fun saveFolders(accountId: Long, folders: List<RemoteFolder>): List<StoredFolder>

    /** The server's own counts for a folder: what the badges show. */
    suspend fun saveCounts(folderId: Long, total: Int, unread: Int)

    /** The UIDs of the folder's messages on the device, ascending. */
    suspend fun uids(folderId: Long): List<Long>

    /** Forgets every message of the folder: the server gave its UIDs a new meaning. */
    suspend fun clear(folderId: Long)

    suspend fun insert(folderId: Long, messages: List<RemoteMessage>)

    suspend fun delete(folderId: Long, uids: Collection<Long>)

    suspend fun updateFlags(folderId: Long, flags: List<RemoteFlags>)

    /** Up to [limit] messages of the folder whose content has not been fetched yet, newest first. */
    suspend fun uidsWithoutBody(folderId: Long, limit: Int): List<Long>

    /** Keeps a message's content; null when there is none to keep (too large, or gone), so it is not asked for again. */
    suspend fun saveBody(folderId: Long, uid: Long, body: StoredBody?)

    /** What is now known of the folder on the server. [syncedAt] is given once a sync has run to its end. */
    suspend fun saveState(folderId: Long, state: FolderState, syncedAt: Instant?)
}
