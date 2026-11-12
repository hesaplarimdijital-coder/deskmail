package com.deskmail.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
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

    /** A folder's messages, newest first. */
    @Query("SELECT * FROM message WHERE folderId = :folderId ORDER BY date DESC, uid DESC")
    abstract fun observeFolder(folderId: Long): Flow<List<MessageEntity>>

    /** The inboxes of every account as one list. */
    @Query(
        "SELECT message.* FROM message JOIN folder ON folder.id = message.folderId " +
            "WHERE folder.role = 'Inbox' ORDER BY message.date DESC, message.id DESC",
    )
    abstract fun observeInboxes(): Flow<List<MessageEntity>>

    /** Everything unread, wherever it is filed: not Spam, not Trash, and not once more from "All Mail". */
    @Query(
        "SELECT message.* FROM message JOIN folder ON folder.id = message.folderId " +
            "WHERE message.seen = 0 AND folder.role NOT IN ('Junk', 'Trash', 'All') ORDER BY message.date DESC, message.id DESC",
    )
    abstract fun observeUnread(): Flow<List<MessageEntity>>

    @Query(
        "SELECT message.* FROM message JOIN folder ON folder.id = message.folderId " +
            "WHERE message.flagged = 1 AND folder.role NOT IN ('Trash', 'All') ORDER BY message.date DESC, message.id DESC",
    )
    abstract fun observeStarred(): Flow<List<MessageEntity>>

    /** The newest messages of every account, each once: "All Mail" holds the same messages once more. */
    @Query(
        "SELECT message.* FROM message JOIN folder ON folder.id = message.folderId " +
            "WHERE folder.role != 'All' ORDER BY message.date DESC, message.id DESC LIMIT :limit",
    )
    abstract fun observeNewest(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM message WHERE id = :id")
    abstract fun observe(id: Long): Flow<MessageEntity?>

    @Query("SELECT * FROM body WHERE messageId = :messageId")
    abstract fun observeBody(messageId: Long): Flow<BodyEntity?>

    @Query("SELECT * FROM attachment WHERE messageId = :messageId ORDER BY id")
    abstract fun observeAttachments(messageId: Long): Flow<List<AttachmentEntity>>

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

    @Query("DELETE FROM message_fts WHERE rowid IN (SELECT id FROM message WHERE folderId = :folderId AND uid IN (:uids))")
    abstract suspend fun deleteFtsOf(folderId: Long, uids: List<Long>)

    @Query("DELETE FROM message WHERE folderId = :folderId AND uid IN (:uids)")
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
