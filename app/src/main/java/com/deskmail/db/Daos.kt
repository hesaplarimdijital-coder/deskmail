package com.deskmail.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import com.deskmail.core.sync.ChangeOutcome
import kotlinx.coroutines.flow.Flow

@Dao
abstract class AccountDao {
    /** Every account in the user's order; emits again whenever one changes. */
    @Query("SELECT * FROM account ORDER BY position, id")
    abstract fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account ORDER BY position, id")
    abstract suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM account WHERE id = :id")
    abstract suspend fun byId(id: Long): AccountEntity?

    /** An address is one account: capitals do not make another. */
    @Query("SELECT * FROM account WHERE LOWER(address) = LOWER(:address) LIMIT 1")
    abstract suspend fun byAddress(address: String): AccountEntity?

    /** The place after the last account. */
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM account")
    abstract suspend fun nextPosition(): Int

    /** @return the new account's id. */
    @Insert
    abstract suspend fun insert(account: AccountEntity): Long

    @Update
    abstract suspend fun update(account: AccountEntity)

    /** How the account is doing; written only when it differs, so that nothing that watches the accounts wakes for nothing. */
    @Query("UPDATE account SET state = :state, stateDetail = :detail WHERE id = :id AND (state != :state OR stateDetail != :detail)")
    abstract suspend fun setState(id: Long, state: String, detail: String)

    /** What the account's mail is written with: the name it is sent under, and what it is signed with. */
    @Query("UPDATE account SET senderName = :senderName, signature = :signature WHERE id = :id")
    abstract suspend fun setWriting(id: Long, senderName: String, signature: String)

    /** Deletes the account and, with it, its credential, its folders and their messages. */
    @Query("DELETE FROM account WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Upsert
    abstract suspend fun saveCredential(credential: CredentialEntity)

    @Query("SELECT * FROM credential WHERE accountId = :accountId")
    abstract suspend fun credential(accountId: Long): CredentialEntity?

    /**
     * Adds an account together with what it signs in with: an account without a credential is of
     * no use, and a credential is sealed for the id its account was given.
     */
    @Transaction
    open suspend fun add(account: AccountEntity, credential: (accountId: Long) -> CredentialEntity): Long {
        val id = insert(account.copy(position = nextPosition()))
        saveCredential(credential(id))
        return id
    }
}

@Dao
abstract class FolderDao {
    /** Every folder of every account, in the accounts' order and then the servers'. */
    @Query("SELECT folder.* FROM folder JOIN account ON account.id = folder.accountId ORDER BY account.position, account.id, folder.position, folder.id")
    abstract fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folder WHERE accountId = :accountId ORDER BY position, id")
    abstract suspend fun ofAccount(accountId: Long): List<FolderEntity>

    @Query("SELECT * FROM folder WHERE id = :id")
    abstract suspend fun byId(id: Long): FolderEntity?

    @Insert
    abstract suspend fun insert(folder: FolderEntity): Long

    @Update
    abstract suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM folder WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /** The server's own counts; written only when they differ, so that nothing that watches the folders wakes for nothing. */
    @Query("UPDATE folder SET total = :total, unread = :unread WHERE id = :id AND (total != :total OR unread != :unread)")
    abstract suspend fun setCounts(id: Long, total: Int, unread: Int)

    @Query("UPDATE folder SET uidValidity = :uidValidity, uidNext = :uidNext, highestModSeq = :highestModSeq WHERE id = :id")
    abstract suspend fun setState(id: Long, uidValidity: Long, uidNext: Long, highestModSeq: Long)

    @Query("UPDATE folder SET syncedAt = :syncedAt WHERE id = :id")
    abstract suspend fun setSyncedAt(id: Long, syncedAt: Long)

    /**
     * Makes the account's folders those of [listed], which are matched by their paths: a new one
     * is added, one that is no longer listed is deleted with its messages, and the others keep
     * their ids, what the last sync knew of them, and a role the user gave them.
     *
     * @return how many folders were deleted.
     */
    @Transaction
    open suspend fun replace(accountId: Long, listed: List<FolderEntity>): Int {
        val stored = ofAccount(accountId).associateBy { it.path }
        val paths = listed.mapTo(HashSet()) { it.path }
        var deleted = 0
        for (folder in stored.values) {
            if (folder.path in paths) continue
            delete(folder.id)
            deleted++
        }
        for (wanted in listed) {
            val existing = stored[wanted.path]
            if (existing == null) {
                insert(wanted.copy(id = 0, accountId = accountId))
                continue
            }
            val updated = existing.copy(
                delimiter = wanted.delimiter,
                role = if (existing.roleFixed) existing.role else wanted.role,
                selectable = wanted.selectable,
                position = wanted.position,
            )
            // an unchanged folder is not written: the screens watch this table
            if (updated != existing) update(updated)
        }
        return deleted
    }
}

/**
 * Holds for a message that is not on its way out of its folder: no move and no removal of it is
 * waiting to be told to the server ([PendingOpEntity]).
 */
private const val STAYING = "NOT EXISTS (SELECT 1 FROM pending_op WHERE pending_op.messageId = message.id AND pending_op.kind IN ('Move', 'Expunge'))"

/** A message no search of the server found lately ([SearchHitEntity]): the sync may remove it. */
private const val NOT_FOUND = "NOT EXISTS (SELECT 1 FROM search_hit WHERE search_hit.messageId = message.id)"

/**
 * The message's sender is a VIP: its first sender's address, between the tab after the name and
 * the end of the line, is one of the VIPs' (kept in lower case). Compared as text, not as a pattern:
 * an address may have a "_" in it.
 */
private const val FROM_VIP =
    "EXISTS (SELECT 1 FROM sender WHERE sender.vip = 1 AND " +
        "instr(LOWER(substr(message.fromList, 1, instr(message.fromList || char(10), char(10)) - 1)) || char(10), char(9) || sender.address || char(10)) > 0)"

/** A message's UID and marks, for comparing with the server's. */
data class FlagRow(
    val uid: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    val forwarded: Boolean,
    val draft: Boolean,
)

@Dao
abstract class MessageDao {

    // ------------------------------------------------------------------ what the screens watch

    // A message that was archived, moved or deleted is off every list at once, while the server
    // is still to be told ([STAYING]).

    /** A folder's messages, newest first. */
    @Query("SELECT * FROM message WHERE folderId = :folderId AND $STAYING ORDER BY date DESC, uid DESC")
    abstract fun observeFolder(folderId: Long): Flow<List<MessageEntity>>

    /** The inboxes of every account as one list. */
    @Query(
        "SELECT message.* FROM message JOIN folder ON folder.id = message.folderId " +
            "WHERE folder.role = 'Inbox' AND $STAYING ORDER BY message.date DESC, message.id DESC",
    )
    abstract fun observeInboxes(): Flow<List<MessageEntity>>

    /** Everything unread, wherever it is filed: not Spam, not Trash, and not once more from "All Mail". */
    @Query(
        "SELECT message.* FROM message JOIN folder ON folder.id = message.folderId " +
            "WHERE message.seen = 0 AND folder.role NOT IN ('Junk', 'Trash', 'All', 'Snoozed') AND $STAYING ORDER BY message.date DESC, message.id DESC",
    )
    abstract fun observeUnread(): Flow<List<MessageEntity>>

    @Query(
        "SELECT message.* FROM message JOIN folder ON folder.id = message.folderId " +
            "WHERE message.flagged = 1 AND folder.role NOT IN ('Trash', 'All', 'Snoozed') AND $STAYING ORDER BY message.date DESC, message.id DESC",
    )
    abstract fun observeStarred(): Flow<List<MessageEntity>>

    /**
     * The mail of the VIPs in every account's inbox: messages whose sender (the first of [MessageEntity.fromList],
     * "name<TAB>address" on a line) is a sender the user called a VIP.
     */
    @Query(
        "SELECT message.* FROM message JOIN folder ON folder.id = message.folderId " +
            "WHERE folder.role = 'Inbox' AND $STAYING AND $FROM_VIP ORDER BY message.date DESC, message.id DESC",
    )
    abstract fun observeVip(): Flow<List<MessageEntity>>

    /**
     * The messages of a conversation in an account, oldest first: the one it began with ([root], its
     * Message-ID) and those that answer it, by their References or In-Reply-To. Not in Trash or Spam.
     */
    @Query(
        "SELECT message.* FROM message JOIN folder ON folder.id = message.folderId " +
            "WHERE message.accountId = :accountId AND folder.role NOT IN ('Trash', 'Junk') AND $STAYING " +
            "AND (message.globalId = :root OR message.inReplyTo = :root OR instr(message.refs, :root) > 0) " +
            "ORDER BY message.date, message.id",
    )
    abstract fun observeThread(accountId: Long, root: String): Flow<List<MessageEntity>>

    /**
     * The copies under Sent of the messages the user waits for answers to ([AwaitingEntity]), the
     * one that is due first on top.
     */
    @Query(
        "SELECT message.* FROM message JOIN folder ON folder.id = message.folderId " +
            "JOIN awaiting ON awaiting.accountId = message.accountId AND awaiting.messageId = message.globalId " +
            "WHERE folder.role = 'Sent' AND $STAYING ORDER BY awaiting.dueAt, message.id",
    )
    abstract fun observeAwaiting(): Flow<List<MessageEntity>>

    /**
     * The snoozed messages of every account, in their Snoozed folders: the one to come back first on
     * top, those whose time the device does not know at the end.
     */
    @Query(
        "SELECT message.* FROM message JOIN folder ON folder.id = message.folderId " +
            "LEFT JOIN snooze ON snooze.accountId = message.accountId AND snooze.messageId = message.globalId " +
            "WHERE folder.role = 'Snoozed' AND $STAYING " +
            "ORDER BY snooze.wakeAt IS NULL, snooze.wakeAt, message.date DESC, message.id DESC",
    )
    abstract fun observeSnoozed(): Flow<List<MessageEntity>>

    /** Who sent the account's messages that answer the message [globalId]: by their In-Reply-To or References. */
    @Query("SELECT fromList FROM message WHERE accountId = :accountId AND (inReplyTo = :globalId OR instr(refs, :globalId) > 0)")
    abstract suspend fun answersTo(accountId: Long, globalId: String): List<String>

    /** The account's copy under Sent of the message [globalId], by the app's id; null when the device has none. */
    @Query(
        "SELECT message.id FROM message JOIN folder ON folder.id = message.folderId " +
            "WHERE message.accountId = :accountId AND message.globalId = :globalId AND folder.role = 'Sent' LIMIT 1",
    )
    abstract suspend fun sentCopy(accountId: Long, globalId: String): Long?

    /** The newest messages of every account, each once: "All Mail" holds the same messages once more. */
    @Query(
        "SELECT message.* FROM message JOIN folder ON folder.id = message.folderId " +
            "WHERE folder.role != 'All' AND $STAYING ORDER BY message.date DESC, message.id DESC LIMIT :limit",
    )
    abstract fun observeNewest(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM message WHERE id = :id")
    abstract fun observe(id: Long): Flow<MessageEntity?>

    @Query("SELECT * FROM body WHERE messageId = :messageId")
    abstract fun observeBody(messageId: Long): Flow<BodyEntity?>

    @Query("SELECT * FROM attachment WHERE messageId = :messageId ORDER BY id")
    abstract fun observeAttachments(messageId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachment WHERE id = :id")
    abstract suspend fun attachment(id: Long): AttachmentEntity?

    @Query("SELECT * FROM attachment WHERE messageId = :messageId ORDER BY id")
    abstract suspend fun attachmentsOf(messageId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM body WHERE messageId = :messageId")
    abstract suspend fun bodyOf(messageId: Long): BodyEntity?

    /** The header lines of a message whose text was fetched before they were kept with it. */
    @Query("UPDATE body SET headers = :headers WHERE messageId = :messageId")
    abstract suspend fun setHeaders(messageId: Long, headers: String)

    // ------------------------------------------------------------------ what new mail is told of

    /** The highest UID the device has of a folder; null for one it has nothing of. */
    @Query("SELECT MAX(uid) FROM message WHERE folderId = :folderId")
    abstract suspend fun highestUid(folderId: Long): Long?

    /** A folder's unread messages that arrived after the one numbered [uid] and are still there, the newest first. */
    @Query("SELECT * FROM message WHERE folderId = :folderId AND uid > :uid AND seen = 0 AND $STAYING ORDER BY uid DESC LIMIT :limit")
    abstract suspend fun unreadAfter(folderId: Long, uid: Long, limit: Int): List<MessageEntity>

    /** Which of the messages [ids] are still unread and still where they were. */
    @Query("SELECT id FROM message WHERE id IN (:ids) AND seen = 0 AND $STAYING")
    abstract suspend fun stillUnread(ids: List<Long>): List<Long>

    /** How many unread messages there are that are still where they were: changes whenever one is read, moved or arrives. */
    @Query("SELECT COUNT(*) FROM message WHERE seen = 0 AND $STAYING")
    abstract fun observeUnreadCount(): Flow<Int>

    // ------------------------------------------------------------------ what the sync reads and writes

    @Query("SELECT * FROM message WHERE id = :id")
    abstract suspend fun byId(id: Long): MessageEntity?

    @Query("SELECT * FROM message WHERE folderId = :folderId AND uid = :uid")
    abstract suspend fun byUid(folderId: Long, uid: Long): MessageEntity?

    @Query("SELECT uid FROM message WHERE folderId = :folderId ORDER BY uid")
    abstract suspend fun uids(folderId: Long): List<Long>

    @Query("SELECT uid, seen, flagged, answered, forwarded, draft FROM message WHERE folderId = :folderId")
    abstract suspend fun flags(folderId: Long): List<FlagRow>

    @Query("SELECT uid FROM message WHERE folderId = :folderId AND bodyState = 0 ORDER BY date DESC, uid DESC LIMIT :limit")
    abstract suspend fun uidsWithoutBody(folderId: Long, limit: Int): List<Long>

    /** The folders of an account that hold messages whose text has not been fetched yet. */
    @Query("SELECT DISTINCT folderId FROM message WHERE accountId = :accountId AND bodyState = 0")
    abstract suspend fun foldersWithoutBodies(accountId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM message")
    abstract suspend fun count(): Int

    /**
     * The messages the index finds for [query] (FTS4 syntax over folded text) that are still
     * where they are filed, the newest first: every folder but Spam.
     */
    @Query(
        "SELECT message.* FROM message JOIN folder ON folder.id = message.folderId " +
            "WHERE message.id IN (SELECT rowid FROM message_fts WHERE message_fts MATCH :query) AND folder.role != 'Junk' AND $STAYING " +
            "ORDER BY message.date DESC, message.id DESC LIMIT :limit",
    )
    abstract fun observeFound(query: String, limit: Int): Flow<List<MessageEntity>>

    /** The ids of the messages the index finds for [query] (FTS4 syntax over folded text). */
    @Query("SELECT rowid FROM message_fts WHERE message_fts MATCH :query")
    abstract suspend fun search(query: String): List<Long>

    /** A message the folder already has is left as it is; its place in the result is -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(messages: List<MessageEntity>): List<Long>

    @Query("UPDATE message SET seen = :seen, flagged = :flagged, answered = :answered, forwarded = :forwarded, draft = :draft WHERE folderId = :folderId AND uid = :uid")
    abstract suspend fun setFlags(folderId: Long, uid: Long, seen: Boolean, flagged: Boolean, answered: Boolean, forwarded: Boolean, draft: Boolean)

    @Query("UPDATE message SET bodyState = :state WHERE id = :id")
    abstract suspend fun setBodyState(id: Long, state: Int)

    @Query("UPDATE message SET bodyState = :state, preview = :preview WHERE id = :id")
    abstract suspend fun setBody(id: Long, state: Int, preview: String)

    @Upsert
    abstract suspend fun saveBody(body: BodyEntity)

    @Query("DELETE FROM attachment WHERE messageId = :messageId")
    abstract suspend fun deleteAttachments(messageId: Long)

    @Insert
    abstract suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveFts(rows: List<MessageFts>)

    @Query("DELETE FROM message_fts WHERE rowid IN (SELECT id FROM message WHERE folderId = :folderId AND uid IN (:uids) AND $NOT_FOUND)")
    abstract suspend fun deleteFtsOf(folderId: Long, uids: List<Long>)

    // ------------------------------------------------------------------ what a search of the server found

    /** What was found is remembered under [SearchHitEntity.query]; a message that was found before is found again, and then. */
    @Upsert
    abstract suspend fun upsertHits(hits: List<SearchHitEntity>)

    @Transaction
    open suspend fun remember(query: String, messageIds: Collection<Long>, now: Long) {
        val known = hitsFor(query).associateBy { it.messageId }
        upsertHits(messageIds.map { id -> SearchHitEntity(id = known[id]?.id ?: 0, query = query, messageId = id, foundAt = now) })
    }

    @Query("SELECT * FROM search_hit WHERE `query` = :query")
    abstract suspend fun hitsFor(query: String): List<SearchHitEntity>

    /** The messages a search of the server found for [query], newest first; those on their way elsewhere are left out. */
    @Query(
        "SELECT message.* FROM message JOIN search_hit ON search_hit.messageId = message.id JOIN folder ON folder.id = message.folderId " +
            "WHERE search_hit.`query` = :query AND folder.role != 'Junk' AND $STAYING ORDER BY message.date DESC, message.id DESC LIMIT :limit",
    )
    abstract fun observeHits(query: String, limit: Int): Flow<List<MessageEntity>>

    /** Forgets what was found before [before]: the sync may take those messages off the device again. */
    @Query("DELETE FROM search_hit WHERE foundAt < :before")
    abstract suspend fun forgetHits(before: Long)

    /** A message a search of the server found is left alone while it is remembered ([SearchHitEntity]). */
    @Query("DELETE FROM message WHERE folderId = :folderId AND uid IN (:uids) AND $NOT_FOUND")
    abstract suspend fun deleteRows(folderId: Long, uids: List<Long>)

    @Query("DELETE FROM message WHERE folderId = :folderId")
    abstract suspend fun deleteFolderRows(folderId: Long)

    /** The index has no ties to the messages: what was indexed of messages that are gone is removed here. */
    @Query("DELETE FROM message_fts WHERE rowid NOT IN (SELECT id FROM message)")
    abstract suspend fun purgeFts()

    // ------------------------------------------------------------------ several writes as one

    /** Adds messages with their place in the search index; returns how many were new. */
    @Transaction
    open suspend fun add(messages: List<MessageEntity>, index: (MessageEntity) -> MessageFts): Int {
        val ids = insert(messages)
        val rows = ArrayList<MessageFts>(messages.size)
        for (position in messages.indices) {
            if (ids[position] != -1L) rows += index(messages[position].copy(id = ids[position]))
        }
        if (rows.isNotEmpty()) saveFts(rows)
        return rows.size
    }

    /** Removes messages by their UIDs, and what the index holds of them. */
    @Transaction
    open suspend fun delete(folderId: Long, uids: Collection<Long>) {
        // well under the number of values one statement may name
        for (chunk in uids.chunked(500)) {
            deleteFtsOf(folderId, chunk)
            deleteRows(folderId, chunk)
        }
    }

    @Transaction
    open suspend fun clearFolder(folderId: Long) {
        deleteFolderRows(folderId)
        purgeFts()
    }

    /** Brings the marks of a folder's messages to [current], writing only the rows that differ; returns how many did. */
    @Transaction
    open suspend fun updateFlags(folderId: Long, current: List<FlagRow>): Int {
        val stored = flags(folderId).associateBy { it.uid }
        var changed = 0
        for (row in current) {
            if (stored[row.uid]?.let { it != row } != true) continue
            setFlags(folderId, row.uid, row.seen, row.flagged, row.answered, row.forwarded, row.draft)
            changed++
        }
        return changed
    }

    /** Keeps a message's content: its text, its files, its first words, and its place in the index. */
    @Transaction
    open suspend fun keepBody(body: BodyEntity, attachments: List<AttachmentEntity>, preview: String, index: MessageFts) {
        saveBody(body)
        deleteAttachments(body.messageId)
        if (attachments.isNotEmpty()) insertAttachments(attachments)
        setBody(body.messageId, MessageEntity.BODY_KEPT, preview)
        saveFts(listOf(index))
    }
}

/** A mark that is waiting to be told to the server, by its message's UID: what a sync must not overwrite. */
data class PendingMark(val uid: Long, val kind: String, val value: Boolean)

/**
 * A change that is due, with what the server knows its message by.
 *
 * @param uidValidity the stamp of the message's folder as the last sync knew it.
 * @param targetPath where a moved message goes; null when that folder is gone.
 */
data class DueChange(
    val id: Long,
    val kind: String,
    val value: Boolean,
    val messageId: Long,
    val uid: Long,
    val folderId: Long,
    val folderPath: String,
    val uidValidity: Long,
    val targetFolderId: Long,
    val targetPath: String?,
)

/**
 * What was done on the device and is still to be told to the server ([PendingOpEntity]), and the
 * device's half of every such change: the message shows it at once.
 */
@Dao
abstract class PendingDao {

    @Insert
    abstract suspend fun insert(op: PendingOpEntity): Long

    @Query("SELECT * FROM pending_op WHERE id = :id")
    abstract suspend fun byId(id: Long): PendingOpEntity?

    @Query("SELECT * FROM pending_op ORDER BY id")
    abstract suspend fun all(): List<PendingOpEntity>

    @Query("DELETE FROM pending_op WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("DELETE FROM pending_op WHERE messageId = :messageId AND kind = :kind")
    abstract suspend fun deleteOf(messageId: Long, kind: String)

    /** Whether the message is on its way out of its folder: 1 or more when a move or a removal of it is waiting. */
    @Query("SELECT COUNT(*) FROM pending_op WHERE messageId = :messageId AND kind IN ('Move', 'Expunge')")
    abstract suspend fun leaving(messageId: Long): Int

    @Query("UPDATE pending_op SET notBefore = :time WHERE id IN (:ids) AND notBefore > :time")
    abstract suspend fun bringForward(ids: List<Long>, time: Long)

    /** The accounts that have something to tell their server. */
    @Query("SELECT DISTINCT accountId FROM pending_op")
    abstract suspend fun accounts(): List<Long>

    /** When the account's next change that is not due yet will be; null for none. */
    @Query("SELECT MIN(notBefore) FROM pending_op WHERE accountId = :accountId AND notBefore > :now")
    abstract suspend fun nextDue(accountId: Long, now: Long): Long?

    /** The account's changes that are due, oldest first. */
    @Query(
        "SELECT pending_op.id AS id, pending_op.kind AS kind, pending_op.value AS value, message.id AS messageId, message.uid AS uid, " +
            "folder.id AS folderId, folder.path AS folderPath, folder.uidValidity AS uidValidity, " +
            "pending_op.targetFolderId AS targetFolderId, target.path AS targetPath " +
            "FROM pending_op JOIN message ON message.id = pending_op.messageId JOIN folder ON folder.id = message.folderId " +
            "LEFT JOIN folder AS target ON target.id = pending_op.targetFolderId " +
            "WHERE pending_op.accountId = :accountId AND pending_op.notBefore <= :now ORDER BY pending_op.id",
    )
    abstract suspend fun due(accountId: Long, now: Long): List<DueChange>

    /** The marks of a folder's messages that are waiting to be sent. */
    @Query(
        "SELECT message.uid AS uid, pending_op.kind AS kind, pending_op.value AS value FROM pending_op " +
            "JOIN message ON message.id = pending_op.messageId " +
            "WHERE message.folderId = :folderId AND pending_op.kind IN ('Seen', 'Flagged', 'Answered', 'Forwarded')",
    )
    abstract suspend fun marksOf(folderId: Long): List<PendingMark>

    // ------------------------------------------------------------------ what a change touches of the mail

    @Query("SELECT * FROM message WHERE id = :id")
    abstract suspend fun message(id: Long): MessageEntity?

    @Query("SELECT * FROM folder WHERE id = :id")
    abstract suspend fun folder(id: Long): FolderEntity?

    @Query("SELECT id FROM message WHERE folderId = :folderId AND uid = :uid")
    abstract suspend fun idAt(folderId: Long, uid: Long): Long?

    @Query("UPDATE message SET seen = :seen WHERE id = :id")
    abstract suspend fun setSeen(id: Long, seen: Boolean)

    @Query("UPDATE message SET flagged = :flagged WHERE id = :id")
    abstract suspend fun setFlagged(id: Long, flagged: Boolean)

    @Query("UPDATE message SET answered = 1 WHERE id = :id")
    abstract suspend fun setAnswered(id: Long)

    @Query("UPDATE message SET forwarded = 1 WHERE id = :id")
    abstract suspend fun setForwarded(id: Long)

    @Query("UPDATE message SET folderId = :folderId, uid = :uid WHERE id = :id")
    abstract suspend fun place(id: Long, folderId: Long, uid: Long)

    @Query("DELETE FROM message WHERE id = :id")
    abstract suspend fun deleteMessage(id: Long)

    @Query("DELETE FROM message_fts WHERE rowid = :id")
    abstract suspend fun deleteIndexed(id: Long)

    /** Moves a folder's counts by so much; never below nothing. The server's own counts replace them with the next sync. */
    @Query("UPDATE folder SET unread = MAX(unread + :unread, 0), total = MAX(total + :total, 0) WHERE id = :id")
    abstract suspend fun count(id: Long, unread: Int, total: Int)

    @Query("UPDATE folder SET uidValidity = :uidValidity WHERE id = :id AND uidValidity = 0")
    abstract suspend fun learnUidValidity(id: Long, uidValidity: Long)

    // ------------------------------------------------------------------ the device's half of a change

    /**
     * Sets or clears marks on a message: on the device at once, and queued for its server. Null
     * leaves a mark as it is.
     *
     * @return the account whose server is to be told, or null when nothing changed (the message
     *   is gone, or already so marked).
     */
    @Transaction
    open suspend fun mark(messageId: Long, seen: Boolean?, flagged: Boolean?, now: Long): Long? {
        val message = message(messageId) ?: return null
        var changed = false
        if (seen != null && seen != message.seen) {
            setSeen(messageId, seen)
            // a message on its way out of the folder was taken off the folder's count already
            if (leaving(messageId) == 0) count(message.folderId, unread = if (seen) -1 else 1, total = 0)
            queueMark(message, PendingOpEntity.SEEN, seen, now)
            changed = true
        }
        if (flagged != null && flagged != message.flagged) {
            setFlagged(messageId, flagged)
            queueMark(message, PendingOpEntity.FLAGGED, flagged, now)
            changed = true
        }
        return if (changed) message.accountId else null
    }

    /** Read becomes unread and back ([seen]), or starred becomes unstarred and back. */
    @Transaction
    open suspend fun toggle(messageId: Long, seen: Boolean, now: Long): Long? {
        val message = message(messageId) ?: return null
        return if (seen) mark(messageId, seen = !message.seen, flagged = null, now = now) else mark(messageId, seen = null, flagged = !message.flagged, now = now)
    }

    /**
     * Marks a message as answered, or with [forwarded] as sent on: on the device at once, and
     * queued for its server. Returns the account to tell, or null when the message is gone or
     * marked so already.
     */
    @Transaction
    open suspend fun markAnswered(messageId: Long, forwarded: Boolean, now: Long): Long? {
        val message = message(messageId) ?: return null
        if (if (forwarded) message.forwarded else message.answered) return null
        if (forwarded) setForwarded(messageId) else setAnswered(messageId)
        queueMark(message, if (forwarded) PendingOpEntity.FORWARDED else PendingOpEntity.ANSWERED, true, now)
        return message.accountId
    }

    /** The last word on a mark counts: an earlier one that was not sent yet is void. */
    private suspend fun queueMark(message: MessageEntity, kind: String, value: Boolean, now: Long) {
        deleteOf(message.id, kind)
        insert(PendingOpEntity(accountId = message.accountId, messageId = message.id, kind = kind, value = value, targetFolderId = 0, notBefore = now, createdAt = now))
    }

    /**
     * Takes messages off their lists and queues them for the folder [targetFolderId], or, with 0,
     * for removal. The server is told at [notBefore]; until then [takeBack] undoes it.
     *
     * A message that is already on its way somewhere, that is in the target already, or that
     * belongs to another account than the target is passed over.
     *
     * @return what was queued.
     */
    @Transaction
    open suspend fun leave(messageIds: List<Long>, targetFolderId: Long, notBefore: Long, now: Long): List<PendingOpEntity> {
        val target = if (targetFolderId == 0L) null else (folder(targetFolderId) ?: return emptyList())
        val queued = ArrayList<PendingOpEntity>()
        for (id in messageIds) {
            val message = message(id) ?: continue
            if (target != null && (message.folderId == target.id || message.accountId != target.accountId)) continue
            if (leaving(id) > 0) continue
            val op = PendingOpEntity(
                accountId = message.accountId,
                messageId = id,
                kind = if (target == null) PendingOpEntity.EXPUNGE else PendingOpEntity.MOVE,
                value = false,
                targetFolderId = targetFolderId,
                notBefore = notBefore,
                createdAt = now,
            )
            queued += op.copy(id = insert(op))
            val unread = if (message.seen) 0 else 1
            count(message.folderId, unread = -unread, total = -1)
            if (target != null) count(target.id, unread = unread, total = 1)
        }
        return queued
    }

    /**
     * Takes back a move or a removal the server has not been told of: the message is on its list
     * again. False when the change is no longer waiting.
     */
    @Transaction
    open suspend fun takeBack(opId: Long): Boolean {
        val op = byId(opId) ?: return false
        delete(opId)
        restoreCounts(op)
        return true
    }

    private suspend fun restoreCounts(op: PendingOpEntity) {
        val message = message(op.messageId) ?: return
        val unread = if (message.seen) 0 else 1
        count(message.folderId, unread = unread, total = 1)
        if (op.kind == PendingOpEntity.MOVE) count(op.targetFolderId, unread = -unread, total = -1)
    }

    /**
     * The server has answered for a change: it is no longer waiting, and the device's copy
     * follows what became of the message.
     *
     * - moved, and the server said where to: the message is in its new folder, with its text;
     * - moved without a word on where to, removed, or no longer there to be moved: the message
     *   is off the device, and the next sync of the folder it went to brings it from there;
     * - refused: the message is on its list again. A refused mark stays as the user set it until
     *   the next sync brings the server's.
     */
    @Transaction
    open suspend fun settle(opId: Long, outcome: ChangeOutcome) {
        // a later word on the same mark took this one's place while it was being sent: that one still waits
        val op = byId(opId) ?: return
        delete(opId)
        val leaves = op.kind == PendingOpEntity.MOVE || op.kind == PendingOpEntity.EXPUNGE
        when {
            !leaves -> Unit
            outcome is ChangeOutcome.Refused -> restoreCounts(op)
            op.kind == PendingOpEntity.MOVE && outcome is ChangeOutcome.Done && outcome.newUid > 0 -> arrive(op.messageId, op.targetFolderId, outcome.newUid, outcome.newUidValidity)
            else -> forget(op.messageId)
        }
    }

    /** The message is now in [folderId] under [uid], which belongs to the folder's stamp [uidValidity]. */
    private suspend fun arrive(messageId: Long, folderId: Long, uid: Long, uidValidity: Long) {
        val folder = folder(folderId)
        val there = idAt(folderId, uid)
        when {
            // The folder's UIDs mean something else than the device thinks: its next sync starts
            // over, and a message filed under the new numbers would only be in its way.
            folder == null || (folder.uidValidity != 0L && folder.uidValidity != uidValidity) -> forget(messageId)
            // a sync of that folder got there first and has the message already
            there != null && there != messageId -> forget(messageId)
            else -> {
                // a folder that was never synced learns its stamp here: what is done to the message next names it by it
                learnUidValidity(folderId, uidValidity)
                place(messageId, folderId, uid)
            }
        }
    }

    private suspend fun forget(messageId: Long) {
        deleteIndexed(messageId)
        deleteMessage(messageId)
    }
}

/** The messages that are being written and those waiting to be sent ([OutboxEntity]), with their files. */
@Dao
abstract class OutboxDao {

    @Insert
    abstract suspend fun insert(message: OutboxEntity): Long

    /**
     * Keeps what was typed. Only what is typed is written, and only while the message is being
     * written on: a late keeping can neither bring a message back that was handed over meanwhile
     * nor change one that is on its way. 0 when it was no longer to be written on.
     */
    @Query(
        "UPDATE outbox SET accountId = :accountId, toText = :to, ccText = :cc, bccText = :bcc, subject = :subject, body = :body, " +
            "messageId = :messageId, updatedAt = :now WHERE id = :id AND state IN ('Draft', 'Failed')",
    )
    abstract suspend fun write(id: Long, accountId: Long, to: String, cc: String, bcc: String, subject: String, body: String, messageId: String, now: Long): Int

    @Query("SELECT * FROM outbox WHERE id = :id")
    abstract suspend fun byId(id: Long): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE id = :id")
    abstract fun observe(id: Long): Flow<OutboxEntity?>

    /** Everything that is being written, waiting or failed, the latest touched first. */
    @Query("SELECT * FROM outbox ORDER BY updatedAt DESC, id DESC")
    abstract fun observeAll(): Flow<List<OutboxEntity>>

    /** The message has left: it is no longer the outbox's. */
    @Query("DELETE FROM outbox WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /** Throws a message away unless it is being handed to its server this moment; 0 when it was not thrown away. */
    @Query("DELETE FROM outbox WHERE id = :id AND state != 'Sending'")
    abstract suspend fun discard(id: Long): Int

    @Query("UPDATE outbox SET state = :state, sendAfter = :sendAfter, error = :error, updatedAt = :now WHERE id = :id")
    abstract suspend fun setState(id: Long, state: String, sendAfter: Long, error: String, now: Long)

    /** Hands a draft over for sending; false when it is no draft any more (sent from another window). */
    @Query("UPDATE outbox SET state = 'Queued', sendAfter = :sendAfter, error = '', updatedAt = :now WHERE id = :id AND state != 'Queued'")
    abstract suspend fun queue(id: Long, sendAfter: Long, now: Long): Int

    /** Takes a message back that is waiting to be sent; 0 when it has left already. */
    @Query("UPDATE outbox SET state = 'Draft', sendAfter = 0, updatedAt = :now WHERE id = :id AND state = 'Queued'")
    abstract suspend fun unqueue(id: Long, now: Long): Int

    /** A waiting message no longer waits out its time. */
    @Query("UPDATE outbox SET sendAfter = :time WHERE id = :id AND state = 'Queued' AND sendAfter > :time")
    abstract suspend fun bringForward(id: Long, time: Long)

    /** Takes a waiting message to send it; 0 when it was taken back in this very moment. */
    @Query("UPDATE outbox SET state = 'Sending', updatedAt = :now WHERE id = :id AND state = 'Queued'")
    abstract suspend fun claim(id: Long, now: Long): Int

    /**
     * What an earlier run was sending when it ended: whether the server took it is not known. It
     * is not sent a second time unasked; it is shown as failed, and its writer decides.
     */
    @Query("UPDATE outbox SET state = 'Failed', error = :error, updatedAt = :now WHERE state = 'Sending'")
    abstract suspend fun failInterrupted(error: String, now: Long): Int

    /**
     * How many days an answer to the message is to be waited for once it was sent; 0 for none.
     * Nothing the message says changes: its copy among the drafts on the server is not behind.
     */
    @Query("UPDATE outbox SET awaitDays = :days WHERE id = :id AND state != 'Sending'")
    abstract suspend fun setAwait(id: Long, days: Int): Int

    /** When the first of the waiting messages is to go (a moment that may have passed); null when none waits. */
    @Query("SELECT MIN(sendAfter) FROM outbox WHERE state = 'Queued'")
    abstract suspend fun earliest(): Long?

    /** What is to be sent now, the oldest first. */
    @Query("SELECT * FROM outbox WHERE state = 'Queued' AND sendAfter <= :now ORDER BY id")
    abstract suspend fun due(now: Long): List<OutboxEntity>

    /** When the next message that is not due yet will be; null for none. */
    @Query("SELECT MIN(sendAfter) FROM outbox WHERE state = 'Queued' AND sendAfter > :now")
    abstract suspend fun nextDue(now: Long): Long?

    // ------------------------------------------------------------------ the draft's copy on the server

    /** The drafts that were touched since their copy was put on the server, or have none there yet. */
    @Query("SELECT * FROM outbox WHERE state = 'Draft' AND updatedAt > draftSavedAt ORDER BY id")
    abstract suspend fun draftsToSave(): List<OutboxEntity>

    /**
     * Notes where a draft's copy lies now, and as of when ([savedAt]: the draft's updatedAt that
     * was put there). 0 when the draft is gone: sent or thrown away meanwhile.
     */
    @Query("UPDATE outbox SET draftFolderId = :folderId, draftUid = :uid, draftUidValidity = :uidValidity, draftSavedAt = :savedAt WHERE id = :id")
    abstract suspend fun setDraftCopy(id: Long, folderId: Long, uid: Long, uidValidity: Long, savedAt: Long): Int

    /**
     * The draft's copy on the server becomes this message's: it is written under the copy's
     * [messageId] and stands for it, up to date as it is.
     */
    @Query("UPDATE outbox SET messageId = :messageId, draftFolderId = :folderId, draftUid = :uid, draftUidValidity = :uidValidity, draftSavedAt = updatedAt WHERE id = :id")
    abstract suspend fun takeUp(id: Long, messageId: String, folderId: Long, uid: Long, uidValidity: Long): Int

    /** Nothing has been written into the draft that is not where it came from: no copy of it is to be put on the server yet. */
    @Query("UPDATE outbox SET draftSavedAt = updatedAt WHERE id = :id")
    abstract suspend fun asItCame(id: Long): Int

    /** The draft being written as a copy of the server's draft [sourceId] ([OutboxEntity.EDIT]), if there is one. */
    @Query("SELECT * FROM outbox WHERE sourceKind = 'Edit' AND sourceId = :sourceId AND state IN ('Draft', 'Failed') ORDER BY id LIMIT 1")
    abstract suspend fun copiedFrom(sourceId: Long): OutboxEntity?

    /** The message that is being written under this Message-ID, if the device has it: a draft's copies all carry it. */
    @Query("SELECT * FROM outbox WHERE messageId = :messageId AND state IN ('Draft', 'Failed') ORDER BY id LIMIT 1")
    abstract suspend fun writtenUnder(messageId: String): OutboxEntity?

    /** A file came or went: the draft is ahead of its copy, as after something typed. */
    @Query("UPDATE outbox SET updatedAt = :now WHERE id = :id AND state IN ('Draft', 'Failed')")
    abstract suspend fun touch(id: Long, now: Long)

    @Insert
    abstract suspend fun addRemoval(removal: DraftRemovalEntity): Long

    @Query("SELECT * FROM draft_removal ORDER BY id")
    abstract suspend fun removals(): List<DraftRemovalEntity>

    @Query("DELETE FROM draft_removal WHERE id = :id")
    abstract suspend fun removalDone(id: Long)

    /**
     * Throws a message away unless it is being handed to its server this moment ([discard]); a copy
     * it has on the server is noted for removal. 0 when it was not thrown away.
     */
    @Transaction
    open suspend fun discardWithCopy(id: Long): Int {
        val row = byId(id) ?: return 0
        val gone = discard(id)
        if (gone > 0) noteCopy(row)
        return gone
    }

    /** A message was sent: it leaves the outbox, and the copy it had among the drafts is noted for removal. */
    @Transaction
    open suspend fun deleteSent(id: Long) {
        val row = byId(id) ?: return
        delete(id)
        noteCopy(row)
    }

    private suspend fun noteCopy(row: OutboxEntity) {
        if (row.draftFolderId != 0L && row.draftUid != 0L) {
            addRemoval(DraftRemovalEntity(accountId = row.accountId, folderId = row.draftFolderId, uid = row.draftUid, uidValidity = row.draftUidValidity))
        }
    }

    @Query("SELECT * FROM outbox_file WHERE outboxId = :outboxId ORDER BY id")
    abstract suspend fun files(outboxId: Long): List<OutboxFileEntity>

    @Query("SELECT * FROM outbox_file ORDER BY id")
    abstract fun observeFiles(): Flow<List<OutboxFileEntity>>

    @Query("SELECT * FROM outbox_file WHERE id = :id")
    abstract suspend fun file(id: Long): OutboxFileEntity?

    @Insert
    abstract suspend fun addFile(file: OutboxFileEntity): Long

    @Query("DELETE FROM outbox_file WHERE id = :id")
    abstract suspend fun removeFile(id: Long)
}

/** What the user keeps for himself: texts to put into a message, searches kept as folders, and what was said of senders. */
@Dao
abstract class ListDao {
    @Query("SELECT * FROM template ORDER BY position, id")
    abstract fun observeTemplates(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM template ORDER BY position, id")
    abstract suspend fun templates(): List<TemplateEntity>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM template")
    abstract suspend fun nextTemplatePosition(): Int

    @Insert
    abstract suspend fun insertTemplate(template: TemplateEntity): Long

    @Query("UPDATE template SET name = :name, body = :body WHERE id = :id")
    abstract suspend fun updateTemplate(id: Long, name: String, body: String): Int

    @Query("DELETE FROM template WHERE id = :id")
    abstract suspend fun deleteTemplate(id: Long)

    @Query("SELECT * FROM saved_search ORDER BY position, id")
    abstract fun observeSearches(): Flow<List<SavedSearchEntity>>

    @Query("SELECT * FROM saved_search ORDER BY position, id")
    abstract suspend fun searches(): List<SavedSearchEntity>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM saved_search")
    abstract suspend fun nextSearchPosition(): Int

    @Insert
    abstract suspend fun insertSearch(search: SavedSearchEntity): Long

    @Query("UPDATE saved_search SET name = :name, `query` = :query WHERE id = :id")
    abstract suspend fun updateSearch(id: Long, name: String, query: String): Int

    @Query("DELETE FROM saved_search WHERE id = :id")
    abstract suspend fun deleteSearch(id: Long)

    @Query("SELECT * FROM sender ORDER BY address")
    abstract fun observeSenders(): Flow<List<SenderEntity>>

    @Query("SELECT * FROM sender ORDER BY address")
    abstract suspend fun senders(): List<SenderEntity>

    @Query("SELECT * FROM sender WHERE address = :address")
    abstract suspend fun sender(address: String): SenderEntity?

    @Upsert
    abstract suspend fun saveSender(sender: SenderEntity)

    @Query("DELETE FROM sender WHERE address = :address")
    abstract suspend fun deleteSender(address: String)

    /** Keeps what was said of the sender [address] (in lower case); one with nothing said of him any more is not kept. */
    @Transaction
    open suspend fun setSender(address: String, vip: Boolean, images: Boolean) {
        if (!vip && !images) deleteSender(address) else saveSender(SenderEntity(address, vip, images))
    }
}

/** The user's messages that answers are waited for ([AwaitingEntity]). */
@Dao
abstract class AwaitingDao {
    @Query("SELECT * FROM awaiting ORDER BY dueAt, id")
    abstract fun observeAll(): Flow<List<AwaitingEntity>>

    @Query("SELECT * FROM awaiting ORDER BY dueAt, id")
    abstract suspend fun all(): List<AwaitingEntity>

    @Query("SELECT * FROM awaiting WHERE accountId = :accountId ORDER BY dueAt, id")
    abstract suspend fun ofAccount(accountId: Long): List<AwaitingEntity>

    @Query("SELECT * FROM awaiting WHERE id = :id")
    abstract suspend fun byId(id: Long): AwaitingEntity?

    /** Waits for an answer to a message; the same message waited for anew takes the new time. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(awaiting: AwaitingEntity): Long

    @Query("DELETE FROM awaiting WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /** The user was reminded of it. */
    @Query("UPDATE awaiting SET toldAt = :at WHERE id = :id")
    abstract suspend fun told(id: Long, at: Long)
}

/** The snoozed messages ([SnoozeEntity]). */
@Dao
abstract class SnoozeDao {
    @Query("SELECT * FROM snooze WHERE state != 'Removed' ORDER BY wakeAt, id")
    abstract fun observeShown(): Flow<List<SnoozeEntity>>

    @Query("SELECT * FROM snooze WHERE accountId = :accountId")
    abstract suspend fun ofAccount(accountId: Long): List<SnoozeEntity>

    @Query("SELECT * FROM snooze WHERE accountId = :accountId AND messageId = :messageId")
    abstract suspend fun of(accountId: Long, messageId: String): SnoozeEntity?

    /** The accounts that have something snoozed, or a change to write. */
    @Query("SELECT DISTINCT accountId FROM snooze")
    abstract suspend fun accounts(): List<Long>

    /** When the next snoozed message is due, of those that are not being brought back; null for none. */
    @Query("SELECT MIN(wakeAt) FROM snooze WHERE state != 'Removed'")
    abstract suspend fun nextWake(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(snooze: SnoozeEntity): Long

    @Query("DELETE FROM snooze WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /**
     * The account's snoozes as a look at its server left them ([rows]), in the place of what the
     * device had before [before]. A snooze changed on the device while the server was being asked
     * is left as it is: the next look takes it up.
     */
    @Transaction
    open suspend fun settle(accountId: Long, rows: List<SnoozeEntity>, before: Long) {
        val newer = ofAccount(accountId).filter { it.changedAt > before }.mapTo(HashSet()) { it.messageId }
        dropUntil(accountId, before)
        for (row in rows) if (row.messageId !in newer) put(row.copy(id = 0))
    }

    @Query("DELETE FROM snooze WHERE accountId = :accountId AND changedAt <= :before")
    protected abstract suspend fun dropUntil(accountId: Long, before: Long)
}
