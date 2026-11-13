package com.deskmail.data

import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailAddress
import com.deskmail.core.sync.Snooze
import com.deskmail.core.sync.SnoozeWork
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.SnoozeEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps each account's snoozes and the list on its server ([com.deskmail.core.sync.SnoozeList]) one
 * and the same, and brings back what is due. The server's list is what counts: every device of the
 * user reads it and writes it, and so each brings back what is due, whichever is first.
 *
 * A look ([run]) at an account:
 * 1. reads the list, and which snoozed messages its Snoozed folder holds;
 * 2. lays this device's changes over it: a message snoozed here goes onto the list once the server
 *    holds it in the folder (its move may still be on its way); one brought back here comes off it;
 * 3. takes off what the folder no longer holds (brought back elsewhere, or moved by the user);
 * 4. brings back, unread, what is due: to the folder it came from, or the inbox where that is gone;
 * 5. writes the list anew where it changed, and takes the old one away;
 * 6. keeps the result on the device, and has the next look arranged for when the next one is due.
 *
 * A look is made after the account was synced (at most every [READ_EVERY_MS] unless something is
 * due or was changed here), after a move into or out of the folder was told to the server, and when
 * a snooze is due ([com.deskmail.work.SnoozeWorker]).
 *
 * @param work where the list is written out before it is handed to the server.
 * @param schedule has a look made when [schedule]'s time (milliseconds since 1970) has come.
 */
class SnoozeSync(
    private val db: DeskMailDatabase,
    private val sync: MailSync,
    private val log: AppLog,
    private val work: File,
    private val schedule: (Long) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    /** When each account's list was last read, in this run of the app. */
    private val read = ConcurrentHashMap<Long, Long>()

    /**
     * Looks at the account's snoozes; with [force], also when nothing seems to call for it.
     *
     * @return how many messages were brought back: their folders want syncing.
     */
    suspend fun run(accountId: Long, force: Boolean = false): Int = locks.getOrPut(accountId) { Mutex() }.withLock {
        val time = now()
        val rows = db.snoozes().ofAccount(accountId)
        val folders = db.folders().ofAccount(accountId)
        val folder = folders.firstOrNull { folderRole(it.role) == FolderRole.Snoozed }
        if (folder == null) {
            // the folder went (removed on the server): nothing waits there any more
            if (rows.isNotEmpty()) db.snoozes().settle(accountId, emptyList(), time)
            return@withLock 0
        }
        val changed = rows.any { it.state != SnoozeEntity.LISTED }
        val due = rows.any { it.state != SnoozeEntity.REMOVED && it.wakeAt <= time }
        val stale = time - (read[accountId] ?: 0L) >= READ_EVERY_MS
        if (!force && !changed && !due && !stale) return@withLock 0
        var woke = 0
        val outcome = sync.onServer(accountId) { account, remote ->
            val listed = SnoozeWork.read(remote, folder.path)
            read[accountId] = time
            val standing = LinkedHashMap<String, Snooze>()
            for (snooze in listed.snoozes) standing[snooze.messageId] = snooze
            for (row in rows) {
                when (row.state) {
                    // the server holds it in the folder: onto the list, at its new time
                    SnoozeEntity.ADDED -> if (row.messageId in listed.held) standing[row.messageId] = row.toSnooze()
                    SnoozeEntity.REMOVED -> standing.remove(row.messageId)
                }
            }
            // no longer in the folder: brought back already, by another device or by hand
            standing.keys.retainAll(listed.held)
            val inbox = folders.firstOrNull { folderRole(it.role) == FolderRole.Inbox }?.path ?: INBOX
            for (snooze in standing.values.filter { it.wakeAt.toEpochMilli() <= time }) {
                val home = folders.firstOrNull { it.path == snooze.folder && it.selectable && folderRole(it.role) != FolderRole.Snoozed }
                // where the look found it: what has left the folder since is brought back by whoever moved it
                if (SnoozeWork.wake(remote, folder.path, listed.uids[snooze.messageId].orEmpty(), home?.path ?: inbox)) woke++
                standing.remove(snooze.messageId)
            }
            val list = standing.values.toList()
            if (list.toSet() != listed.snoozes.toSet() || listed.stale.isNotEmpty()) {
                work.mkdirs()
                val file = File(work, "snoozes-$accountId.eml")
                try {
                    SnoozeWork.writeList(list, MailAddress(account.senderName, account.address), Instant.ofEpochMilli(time), zone(), file)
                    // on Gmail a list taken out of its label would stay in "All Mail": to the trash with it
                    val gmail = folders.any { folderRole(it.role) == FolderRole.All }
                    val trash = if (gmail) folders.firstOrNull { folderRole(it.role) == FolderRole.Trash }?.path else null
                    SnoozeWork.replace(remote, folder.path, file, listOfNotNull(listed.uid.takeIf { it != 0L }) + listed.stale, trash)
                } finally {
                    file.delete()
                }
            }
            // on the device: the list as it now stands, and what is still on its way to or from the folder
            val waiting = rows.filter { row ->
                (row.state == SnoozeEntity.ADDED && row.messageId !in listed.held && time - row.changedAt < GIVE_UP_MS) ||
                    (row.state == SnoozeEntity.REMOVED && row.messageId in listed.held && time - row.changedAt < GIVE_UP_MS)
            }
            db.snoozes().settle(accountId, list.map { it.toRow(accountId) } + waiting, time)
            log.info(TAG, "account $accountId: ${list.size} snoozed, $woke brought back")
        }
        if (outcome != MailSync.Outcome.Done) log.warn(TAG, "account $accountId: the snoozes could not be looked at: $outcome")
        // what came back is no longer in the folder on the device either
        if (woke > 0) sync.folder(folder.id)
        arrange()
        woke
    }

    /**
     * Has a look made when the next snoozed message is due; one that is due already (it could not be
     * brought back: no network, a server that said no) in a while, not again at once.
     */
    suspend fun arrange() {
        val next = db.snoozes().nextWake() ?: return
        val time = now()
        schedule(if (next > time) next else time + RETRY_MS)
    }

    private fun SnoozeEntity.toSnooze() = Snooze(messageId, Instant.ofEpochMilli(wakeAt), folder, subject)

    private fun Snooze.toRow(accountId: Long) = SnoozeEntity(
        accountId = accountId,
        messageId = messageId,
        folder = folder,
        subject = subject,
        wakeAt = wakeAt.toEpochMilli(),
        state = SnoozeEntity.LISTED,
        // from the server: whatever the device changes later is newer
        changedAt = 0,
    )

    companion object {
        private const val TAG = "snooze"

        /** Another device's snoozes are learnt of at least this often, while the app syncs. */
        const val READ_EVERY_MS = 15 * 60 * 1000L

        /** A change whose move never reached the server is given up after this long. */
        const val GIVE_UP_MS = 24 * 60 * 60 * 1000L

        /** What could not be brought back is tried again after this long. */
        const val RETRY_MS = 15 * 60 * 1000L

        private const val INBOX = "INBOX"
    }
}
