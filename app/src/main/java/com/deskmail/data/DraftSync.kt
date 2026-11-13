package com.deskmail.data

import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailException
import com.deskmail.core.mime.MessageWriter
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.DraftRemovalEntity
import com.deskmail.db.OutboxEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/**
 * Keeps a copy of what is being written among the drafts on its account's server, where another
 * device finds it (and, with this app, takes it up).
 *
 * - A draft that was touched since its copy was put there gets a new copy, and the old one goes
 *   once the new one is in place: the server never holds two, and never none.
 * - A message that was sent or thrown away leaves a note of its copy ([DraftRemovalEntity]); the
 *   copy is removed when the server can next be reached.
 * - Nothing is kept on a server that does not say where a message came to lie (no UIDPLUS): its
 *   copy could not be replaced or removed. Nor for an account without a Drafts folder.
 *
 * Nothing here decides when to look: [run] is called by the background work that follows what is
 * written ([com.deskmail.work.DraftWorker] in the app), a moment after the typing pauses.
 *
 * @param work where a draft is written out before it is handed to its server.
 */
class DraftSync(
    private val db: DeskMailDatabase,
    private val sync: MailSync,
    private val log: AppLog,
    private val work: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val lock = Mutex()

    /**
     * Brings the servers up to what is being written. True when something is left to do: a server
     * could not be reached, or a draft was written on, sent or thrown away while this ran. Worth
     * another run in a while.
     */
    suspend fun run(): Boolean = lock.withLock {
        withContext(io) {
            var left = false
            val listed = LinkedHashSet<Long>()
            // each copy is seen through: one that is on the server without anybody knowing where would stay there
            for (removal in db.outbox().removals()) {
                if (!withContext(NonCancellable) { remove(removal, listed) }) left = true
            }
            for (row in db.outbox().draftsToSave()) {
                if (!withContext(NonCancellable) { save(row, listed) }) left = true
            }
            // a Drafts folder that is kept on the device shows what changed without waiting to be opened
            for (folderId in listed) {
                if (db.folders().byId(folderId)?.syncedAt != 0L) sync.folder(folderId)
            }
            // written on, sent or thrown away while this ran: what that left to do is not to wait for another reason to run
            left || db.outbox().draftsToSave().isNotEmpty() || db.outbox().removals().isNotEmpty()
        }
    }

    /** Puts the draft's copy on its server anew. False when the server could not be reached. */
    private suspend fun save(row: OutboxEntity, listed: MutableSet<Long>): Boolean {
        val outbox = db.outbox()
        val account = db.accounts().byId(row.accountId) ?: return true
        val drafts = db.folders().ofAccount(account.id).firstOrNull { folderRole(it.role) == FolderRole.Drafts }
        if (drafts == null) {
            // nowhere to keep it: not looked at again until it is touched
            outbox.setDraftCopy(row.id, row.draftFolderId, row.draftUid, row.draftUidValidity, row.updatedAt)
            return true
        }
        val file = File(work, "draft-${row.id}.eml")
        try {
            work.mkdirs()
            val message = outgoing(account, row, outbox.files(row.id), Instant.ofEpochMilli(row.updatedAt))
            // with the hidden recipients: the writer finds them again where he goes on writing
            file.outputStream().buffered().use { MessageWriter.write(message, it, zone(), withBcc = true) }
        } catch (e: IOException) {
            // a file that was attached is no longer where it was kept
            file.delete()
            log.warn(TAG, "draft ${row.id} could not be written out: $e")
            outbox.setDraftCopy(row.id, row.draftFolderId, row.draftUid, row.draftUidValidity, row.updatedAt)
            return true
        }
        // a copy in another folder (the account's Drafts folder is another one now) is not this folder's to replace
        val inPlace = row.draftFolderId == drafts.id
        val saved = try {
            sync.saveDraft(account.id, drafts.id, file, if (inPlace) row.draftUid else 0, row.draftUidValidity)
        } finally {
            file.delete()
        }
        return when (val outcome = saved.outcome) {
            MailSync.Outcome.Done -> {
                if (saved.uid == 0L) {
                    log.info(TAG, "account ${account.id}: its server does not say where a draft came to lie; its drafts stay on the device")
                    outbox.setDraftCopy(row.id, row.draftFolderId, row.draftUid, row.draftUidValidity, row.updatedAt)
                } else {
                    listed += drafts.id
                    if (!inPlace && row.draftUid != 0L) {
                        outbox.addRemoval(DraftRemovalEntity(accountId = account.id, folderId = row.draftFolderId, uid = row.draftUid, uidValidity = row.draftUidValidity))
                    }
                    // as of what was put there: written on meanwhile, the draft is ahead of its copy again
                    if (outbox.setDraftCopy(row.id, drafts.id, saved.uid, saved.uidValidity, row.updatedAt) == 0) {
                        // sent or thrown away while its copy was on the way: the copy goes after it
                        val late = DraftRemovalEntity(accountId = account.id, folderId = drafts.id, uid = saved.uid, uidValidity = saved.uidValidity)
                        remove(late.copy(id = outbox.addRemoval(late)), listed)
                    }
                    log.info(TAG, "account ${account.id}: draft ${row.id} is kept on the server")
                }
                true
            }
            MailSync.Outcome.Gone -> true
            is MailSync.Outcome.Failed -> {
                // why is in the log, where it happened; tried again when it is next looked at
                outcome.kind != MailException.Kind.Network
            }
        }
    }

    /** Removes a copy that is of no use any more. False when the server could not be reached. */
    private suspend fun remove(removal: DraftRemovalEntity, listed: MutableSet<Long>): Boolean {
        val outcome = sync.removeDraft(removal.accountId, removal.folderId, removal.uid, removal.uidValidity)
        if (outcome is MailSync.Outcome.Failed && outcome.kind == MailException.Kind.Network) return false
        // removed, or not to be had any more (its folder is gone, the server will not): the note has done its work
        db.outbox().removalDone(removal.id)
        if (outcome == MailSync.Outcome.Done) listed += removal.folderId
        return true
    }

    companion object {
        private const val TAG = "drafts"

        /** How long after something was typed its draft is put on the server: a pause in the writing, not every word. */
        const val SAVE_AFTER_MS = 20_000L
    }
}
