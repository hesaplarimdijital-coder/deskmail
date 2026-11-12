package com.deskmail.data

import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailAddress
import com.deskmail.core.mail.RemoteFlags
import com.deskmail.core.mail.RemoteFolder
import com.deskmail.core.mail.RemoteMessage
import com.deskmail.core.sync.FolderState
import com.deskmail.core.sync.StoredBody
import com.deskmail.core.sync.StoredFolder
import com.deskmail.core.sync.SyncStore
import com.deskmail.core.text.TurkishFold
import com.deskmail.db.AttachmentEntity
import com.deskmail.db.BodyEntity
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.FlagRow
import com.deskmail.db.FolderEntity
import com.deskmail.db.MessageEntity
import com.deskmail.db.MessageFts
import java.time.Instant

/**
 * The device's copy of the mail in the app's database, as the sync writes it
 * ([com.deskmail.core.sync.AccountSync]). Every message and every text it keeps is also put into
 * the search index, folded.
 */
class RoomSyncStore(db: DeskMailDatabase) : SyncStore {
    private val folders = db.folders()
    private val messages = db.messages()

    override suspend fun saveFolders(accountId: Long, folders: List<RemoteFolder>): List<StoredFolder> {
        val listed = folders.mapIndexed { index, folder ->
            FolderEntity(
                accountId = accountId,
                path = folder.path,
                delimiter = folder.delimiter?.toString().orEmpty(),
                role = folder.role.name,
                roleFixed = false,
                selectable = folder.selectable,
                position = index,
                uidValidity = 0,
                uidNext = 0,
                highestModSeq = 0,
                unread = 0,
                total = 0,
                syncedAt = 0,
            )
        }
        val deleted = this.folders.replace(accountId, listed)
        // the index has no ties to the messages: what it held of a deleted folder's goes here
        if (deleted > 0) messages.purgeFts()
        return this.folders.ofAccount(accountId).map { it.toStored() }
    }

    override suspend fun saveCounts(folderId: Long, total: Int, unread: Int) = folders.setCounts(folderId, total, unread)

    override suspend fun uids(folderId: Long): List<Long> = messages.uids(folderId)

    override suspend fun clear(folderId: Long) = messages.clearFolder(folderId)

    override suspend fun insert(folderId: Long, messages: List<RemoteMessage>) {
        // a folder that was deleted while it was being synced takes no more messages
        val folder = folders.byId(folderId) ?: return
        val rows = messages.map { it.toEntity(folder.accountId, folderId) }
        this.messages.add(rows) { row -> indexOf(row, text = "") }
    }

    override suspend fun delete(folderId: Long, uids: Collection<Long>) = messages.delete(folderId, uids)

    override suspend fun updateFlags(folderId: Long, flags: List<RemoteFlags>) {
        val rows = flags.map { FlagRow(it.uid, it.flags.seen, it.flags.flagged, it.flags.answered, it.flags.forwarded, it.flags.draft) }
        messages.updateFlags(folderId, rows)
    }

    override suspend fun uidsWithoutBody(folderId: Long, limit: Int): List<Long> = messages.uidsWithoutBody(folderId, limit)

    override suspend fun saveBody(folderId: Long, uid: Long, body: StoredBody?) {
        // gone since it was asked for
        val message = messages.byUid(folderId, uid) ?: return
        if (body == null) {
            messages.setBodyState(message.id, MessageEntity.BODY_NONE)
            return
        }
        messages.keepBody(
            body = BodyEntity(message.id, body.text, body.html, body.remoteImages),
            attachments = body.attachments.map { AttachmentEntity(0, message.id, it.part, it.name, it.mimeType, it.size, it.contentId, it.inline) },
            preview = body.preview,
            index = indexOf(message, body.text),
        )
    }

    override suspend fun saveState(folderId: Long, state: FolderState, syncedAt: Instant?) {
        folders.setState(folderId, state.uidValidity, state.uidNext, state.highestModSeq)
        if (syncedAt != null) folders.setSyncedAt(folderId, syncedAt.toEpochMilli())
    }

    /** Takes out of the search index what it holds of messages that are gone with their account. */
    suspend fun forget() = messages.purgeFts()

    companion object {
        /** No more of a message's text than this is indexed: enough to find it by, whatever its length. */
        private const val INDEXED_CHARS = 100_000

        /** A message's row of the search index: its subject, the people on it and [text], folded for searching. */
        internal fun indexOf(message: MessageEntity, text: String): MessageFts {
            val people = sequenceOf(message.fromList, message.toList, message.ccList)
                .flatMap { MailAddress.decode(it) }
                .joinToString(" ") { (it.name + " " + it.address).trim() }
            return MessageFts(
                rowId = message.id,
                subject = TurkishFold.fold(message.subject),
                people = TurkishFold.fold(people),
                body = TurkishFold.fold(text.take(INDEXED_CHARS)),
            )
        }
    }
}

/** What the sync needs to know of a stored folder. A role this version does not know is a folder like any other. */
internal fun FolderEntity.toStored(): StoredFolder = StoredFolder(
    id = id,
    path = path,
    role = folderRole(role),
    selectable = selectable,
    state = FolderState(uidValidity, uidNext, highestModSeq),
)

internal fun folderRole(name: String): FolderRole = FolderRole.entries.firstOrNull { it.name == name } ?: FolderRole.Other

private fun RemoteMessage.toEntity(accountId: Long, folderId: Long) = MessageEntity(
    accountId = accountId,
    folderId = folderId,
    uid = uid,
    globalId = globalId,
    inReplyTo = inReplyTo,
    refs = references,
    subject = subject,
    fromList = MailAddress.encode(from),
    toList = MailAddress.encode(to),
    ccList = MailAddress.encode(cc),
    bccList = MailAddress.encode(bcc),
    replyToList = MailAddress.encode(replyTo),
    sentAt = sentAt?.toEpochMilli() ?: 0,
    date = receivedAt.toEpochMilli(),
    size = size,
    seen = flags.seen,
    flagged = flags.flagged,
    answered = flags.answered,
    forwarded = flags.forwarded,
    draft = flags.draft,
    attachments = attachments,
    preview = "",
    bodyState = MessageEntity.BODY_MISSING,
)
