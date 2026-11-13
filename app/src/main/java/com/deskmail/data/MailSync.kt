package com.deskmail.data

import com.deskmail.core.imap.ImapConnection
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailException
import com.deskmail.core.mail.RemoteAccount
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.sync.AccountSync
import com.deskmail.core.sync.ChangeOutcome
import com.deskmail.core.sync.ChangeSync
import com.deskmail.core.sync.FolderSync
import com.deskmail.core.sync.PendingChange
import com.deskmail.core.text.SearchTerm
import com.deskmail.db.AccountEntity
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.DueChange
import com.deskmail.db.MessageEntity
import com.deskmail.db.PendingOpEntity
import com.deskmail.mail.AccountState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the sync ([AccountSync]) for the app's accounts: signs in, brings the device's copy up to
 * what the server has, and writes down on the account how that went.
 *
 * Four kinds of work, each on a connection of its own, so that none waits for another:
 * - what was done on the device ([changes]): marks, moves and removals, told to the server;
 * - the lists ([account], [folder]): which messages there are and their marks, and the text of
 *   the newest few. One at a time per account, and never before the server was told what the
 *   device changed: the lists then read back what the user did, not what was there before.
 * - the texts the lists left for later ([backlog]), in the background until none is missing;
 * - what the user is waiting for: the text of the message just opened ([body]), a file of it
 *   ([attachment]).
 *
 * @param scope where the background work runs; it outlives the screens.
 * @param connect signs in to an account's IMAP server; the tests hand in a server of their own.
 */
class MailSync(
    private val db: DeskMailDatabase,
    private val accounts: AccountStore,
    private val log: AppLog,
    private val scope: CoroutineScope,
    private val connect: (ServerAddress, Credentials) -> RemoteAccount = { server, credentials -> ImapConnection.open(server, credentials, log) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Instant = Instant::now,
) {
    /** What became of a piece of work. */
    sealed interface Outcome {
        data object Done : Outcome

        /** [kind] says what to do about it; [detail] is the failure in the server's (or the system's) words. */
        data class Failed(val kind: MailException.Kind, val detail: String) : Outcome

        /** The account, the folder or the message is no longer on the device. */
        data object Gone : Outcome
    }

    private val store = RoomSyncStore(db)
    private val accountSync = AccountSync(store, log)
    private val folderSync = FolderSync(store, log)
    private val changeSync = ChangeSync(log)
    private val listLocks = ConcurrentHashMap<Long, Mutex>()
    private val changeLocks = ConcurrentHashMap<Long, Mutex>()
    private val readLocks = ConcurrentHashMap<Long, Mutex>()
    private val backlogs = ConcurrentHashMap<Long, Boolean>()
    private val listing = MutableStateFlow<Set<Long>>(emptySet())

    /** The accounts whose lists are being brought up to date right now. */
    val syncing: StateFlow<Set<Long>> get() = listing

    /** Told after an account was brought up to date, whoever asked for it: what tells of new mail hangs here. */
    @Volatile
    var onSynced: (suspend (accountId: Long) -> Unit)? = null

    /**
     * Brings an account up to date: its folders with the server's counts, its inbox, and the
     * folders at the paths [also].
     */
    suspend fun account(accountId: Long, also: Set<String> = emptySet()): Outcome {
        val outcome = listed(accountId) { account, remote ->
            accountSync.sync(remote, accountId, account.syncDays, clock(), also, FIRST_BODIES)
        }
        if (outcome == Outcome.Done) {
            try {
                onSynced?.invoke(accountId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // what follows a sync is not the sync: it worked
                log.error(TAG, "account $accountId was synced, but what follows that failed", e)
            }
        }
        return outcome
    }

    /** Brings one folder up to date. */
    suspend fun folder(folderId: Long): Outcome {
        val accountId = db.folders().byId(folderId)?.accountId ?: return Outcome.Gone
        return listed(accountId) { account, remote ->
            // read again now that it is this work's turn: an earlier sync may have changed what is known of it
            val folder = db.folders().byId(folderId)
            if (folder != null) accountSync.folder(remote, folder.toStored(), account.syncDays, clock(), FIRST_BODIES)
        }
    }

    private suspend fun listed(accountId: Long, work: suspend (AccountEntity, RemoteAccount) -> Unit): Outcome {
        val outcome = listLocks.getOrPut(accountId) { Mutex() }.withLock {
            listing.update { it + accountId }
            try {
                connected(accountId) { account, remote ->
                    // first what the device changed, then what the server has
                    changeLock(accountId).withLock { push(accountId, remote) }
                    work(account, remote)
                }
            } finally {
                listing.update { it - accountId }
            }
        }
        if (outcome == Outcome.Done) startBacklog(accountId)
        return outcome
    }

    // ------------------------------------------------------------------ what was done on the device

    /**
     * Tells the account's server what was done on the device and is due: marks, moves and
     * removals. Without anything due no connection is made.
     */
    suspend fun changes(accountId: Long): Outcome = changeLock(accountId).withLock {
        if (db.pending().due(accountId, clock().toEpochMilli()).isEmpty()) return@withLock Outcome.Done
        connected(accountId) { _, remote -> push(accountId, remote) }
    }

    /**
     * Runs [block] while nothing of the account is being told to its server: a change is then
     * either still waiting or settled, never half way.
     */
    suspend fun <T> betweenChanges(accountId: Long, block: suspend () -> T): T = changeLock(accountId).withLock { block() }

    private fun changeLock(accountId: Long): Mutex = changeLocks.getOrPut(accountId) { Mutex() }

    /** Sends what is due, and what becomes due meanwhile; then asks for the counts of the folders it touched. */
    private suspend fun push(accountId: Long, remote: RemoteAccount) {
        val pending = db.pending()
        val touched = LinkedHashSet<Long>()
        for (round in 1..PUSH_ROUNDS) {
            val due = pending.due(accountId, clock().toEpochMilli())
            if (due.isEmpty()) break
            val rows = due.associateBy { it.id }
            val sendable = ArrayList<PendingChange>(due.size)
            for (row in due) {
                val change = row.toChange()
                // moved to a folder that is gone since: there is nothing to tell the server
                if (change == null) pending.settle(row.id, ChangeOutcome.Refused("nowhere to move it to")) else sendable += change
            }
            changeSync.push(remote, sendable) { change, outcome ->
                val row = rows.getValue(change.id)
                pending.settle(change.id, outcome)
                touched += row.folderId
                if (row.targetFolderId != 0L) touched += row.targetFolderId
            }
        }
        // the device moved its counts by what it did; here is what the server makes of it
        for (folderId in touched) {
            val folder = db.folders().byId(folderId) ?: continue
            remote.counts(folder.path)?.let { db.folders().setCounts(folderId, it.total, it.unread) }
        }
    }

    private fun startBacklog(accountId: Long) {
        // one at a time per account; a run that is under way finds what was added meanwhile
        if (backlogs.putIfAbsent(accountId, true) != null) return
        scope.launch {
            try {
                backlog(accountId)
            } finally {
                backlogs.remove(accountId)
            }
        }
    }

    /**
     * Fetches the text of every message of the account that has none yet, folder by folder and
     * newest first, until none is missing or the connection is.
     */
    suspend fun backlog(accountId: Long): Outcome {
        if (db.messages().foldersWithoutBodies(accountId).isEmpty()) return Outcome.Done
        return connected(accountId) { _, remote ->
            do {
                var fetched = 0
                for (folderId in db.messages().foldersWithoutBodies(accountId)) {
                    val path = db.folders().byId(folderId)?.path ?: continue
                    remote.open(path).use { mailbox ->
                        do {
                            currentCoroutineContext().ensureActive()
                            // as the last list sync left it: the UIDs the device has are that sync's
                            val folder = db.folders().byId(folderId)?.toStored() ?: break
                            val round = folderSync.bodies(mailbox, folder, BACKLOG_BODIES)
                            fetched += round.fetched
                        } while (round.more)
                    }
                }
                // what a list sync added in the meantime; no progress means what is left cannot be fetched now
            } while (fetched > 0 && db.messages().foldersWithoutBodies(accountId).isNotEmpty())
        }
    }

    /** Fetches the text of one message now: the user opened it and the device does not have it yet. */
    suspend fun body(messageId: Long): Outcome {
        val accountId = db.messages().byId(messageId)?.accountId ?: return Outcome.Gone
        return readLocks.getOrPut(accountId) { Mutex() }.withLock {
            val message = db.messages().byId(messageId) ?: return@withLock Outcome.Gone
            // the backlog got there first
            if (message.bodyState != MessageEntity.BODY_MISSING) return@withLock Outcome.Done
            val folder = db.folders().byId(message.folderId) ?: return@withLock Outcome.Gone
            var renumbered = false
            val outcome = connected(accountId) { _, remote ->
                remote.open(folder.path).use { mailbox ->
                    folderSync.body(mailbox, folder.toStored(), message.uid)
                    renumbered = mailbox.uidValidity != folder.uidValidity
                }
            }
            if (outcome == Outcome.Done && renumbered) {
                // the folder's UIDs mean something else now: its list has to be read again first
                scope.launch { folder(folder.id) }
                Outcome.Failed(MailException.Kind.Server, "the folder has changed on the server")
            } else {
                outcome
            }
        }
    }

    /**
     * Fetches the header lines of a message whose text was fetched before they were kept with it
     * (the inspector's "Security" and "Headers").
     */
    suspend fun headers(messageId: Long): Outcome {
        val message = db.messages().byId(messageId) ?: return Outcome.Gone
        val folder = db.folders().byId(message.folderId) ?: return Outcome.Gone
        var found: String? = null
        val outcome = connected(message.accountId) { _, remote ->
            remote.open(folder.path).use { mailbox ->
                // the folder's UIDs mean something else now: not this message's lines, then
                if (folder.uidValidity == 0L || mailbox.uidValidity == folder.uidValidity) found = mailbox.headers(message.uid)
            }
        }
        val lines = found ?: return if (outcome == Outcome.Done) Outcome.Gone else outcome
        db.messages().setHeaders(messageId, lines)
        return Outcome.Done
    }

    /**
     * Fetches a file a message carries into [target], which exists only once all of it is there.
     *
     * @return [Outcome.Gone] when the message or the file in it is no longer on the server.
     */
    suspend fun attachment(attachmentId: Long, target: File): Outcome {
        val file = db.messages().attachment(attachmentId) ?: return Outcome.Gone
        val message = db.messages().byId(file.messageId) ?: return Outcome.Gone
        val folder = db.folders().byId(message.folderId) ?: return Outcome.Gone
        var found = false
        val outcome = connected(message.accountId) { _, remote ->
            remote.open(folder.path).use { mailbox ->
                // the folder's UIDs mean something else now: its list has to be read again first
                if (mailbox.uidValidity != folder.uidValidity) return@use
                target.parentFile?.mkdirs()
                val partial = File(target.path + ".part")
                try {
                    found = partial.outputStream().buffered().use { out -> mailbox.attachment(message.uid, file.part, out) }
                    if (found) {
                        target.delete()
                        found = partial.renameTo(target)
                    }
                } finally {
                    partial.delete()
                }
            }
        }
        return if (outcome == Outcome.Done && !found) Outcome.Gone else outcome
    }

    /**
     * What became of adding a message to a folder.
     *
     * @param uid the UID the message has there; 0 where the server does not tell, or it failed.
     * @param uidValidity the folder's stamp on its UIDs, where [uid] was asked for.
     */
    data class Appended(val outcome: Outcome, val uid: Long = 0, val uidValidity: Long = 0)

    /**
     * Adds a message the user wrote to a folder of its account: the copy of what was sent, or a
     * draft that is put away. [message] is the message as it travels.
     */
    suspend fun append(accountId: Long, folderId: Long, message: File, seen: Boolean, draft: Boolean): Appended {
        val folder = db.folders().byId(folderId) ?: return Appended(Outcome.Gone)
        var uid = 0L
        val outcome = connected(accountId) { _, remote -> remote.edit(folder.path).use { uid = it.append(message, seen, draft) } }
        return Appended(outcome, uid)
    }

    /**
     * Puts a draft into a folder of its account, in place of the copy it had there ([replaces],
     * its UID under the stamp [replacesValidity]; 0 for none). The old copy goes once the new one
     * is in place, and only while the folder's UIDs still mean what they meant.
     *
     * Nothing is put on a server that does not tell where a message came to lie: the copy could
     * never be taken away again. That is [Outcome.Done] with [Appended.uid] 0.
     */
    suspend fun saveDraft(accountId: Long, folderId: Long, message: File, replaces: Long, replacesValidity: Long): Appended {
        val folder = db.folders().byId(folderId) ?: return Appended(Outcome.Gone)
        var uid = 0L
        var validity = 0L
        val outcome = connected(accountId) { _, remote ->
            remote.edit(folder.path).use { box ->
                if (!box.appendsWithUid) return@use
                validity = box.uidValidity
                uid = box.append(message, seen = true, draft = true)
                if (uid != 0L && replaces != 0L && replacesValidity == validity) box.expunge(listOf(replaces))
            }
        }
        return Appended(outcome, uid, validity)
    }

    /** Removes a draft's copy from its folder on the server, while the folder's UIDs still mean what they meant ([uidValidity]). */
    suspend fun removeDraft(accountId: Long, folderId: Long, uid: Long, uidValidity: Long): Outcome {
        val folder = db.folders().byId(folderId) ?: return Outcome.Gone
        return connected(accountId) { _, remote ->
            remote.edit(folder.path).use { box -> if (box.uidValidity == uidValidity) box.expunge(listOf(uid)) }
        }
    }

    /**
     * Asks the account's server what [terms] find, and brings what the device does not have onto
     * it: the newest [limit] of them, as a list shows them (their text comes when they are
     * opened). Every message found, the device's own among them, is remembered under [key] for a
     * while ([com.deskmail.db.SearchHitEntity]): the search screen lists them, and the sync leaves
     * them on the device although they may be older than what it keeps.
     *
     * Looked through: Gmail's "All Mail" where the account has one (everything once, Sent among it),
     * else every folder that holds mail but Drafts, Trash and Spam.
     *
     * @return [Outcome.Done] with how many messages were new on the device, or what failed.
     */
    suspend fun searchServer(accountId: Long, terms: List<SearchTerm>, key: String, limit: Int, now: Long): Pair<Outcome, Int> {
        var added = 0
        val outcome = connected(accountId) { _, remote ->
            val folders = db.folders().ofAccount(accountId).filter { it.selectable }
            val all = folders.firstOrNull { folderRole(it.role) == FolderRole.All }
            val targets = if (all != null) listOf(all) else folders.filter { folderRole(it.role) !in UNSEARCHED }
            var left = limit
            for (folder in targets) {
                remote.open(folder.path).use { box ->
                    // the folder's UIDs mean something else than what the device knows them as: its next sync starts over first
                    if (folder.uidValidity != 0L && folder.uidValidity != box.uidValidity) return@use
                    if (folder.uidValidity == 0L) db.folders().setState(folder.id, box.uidValidity, folder.uidNext, folder.highestModSeq)
                    val matched = try {
                        box.search(terms)
                    } catch (e: MailException) {
                        // A server that will not search this folder for this (a word it does not take, a
                        // character set it does not know): the folder is left out, and the account is not
                        // the worse for it. A connection that is gone is the account's business.
                        if (e.kind != MailException.Kind.Server) throw e
                        log.warn(TAG, "account $accountId: ${folder.path}: the server would not search it (${e.message})")
                        emptyList()
                    }.takeLast(MATCHES_KEPT)
                    if (matched.isEmpty()) return@use
                    val have = store.uids(folder.id).toHashSet()
                    val missing = matched.filter { it !in have }.sortedDescending().take(left.coerceAtLeast(0))
                    if (missing.isNotEmpty()) {
                        // marked for deletion and not yet removed: as good as gone
                        val headers = box.headers(missing).filter { !it.flags.deleted }
                        store.insert(folder.id, headers)
                        added += headers.size
                        left -= headers.size
                    }
                    val ids = matched.mapNotNull { uid -> db.pending().idAt(folder.id, uid) }
                    db.messages().remember(key, ids, now)
                }
            }
        }
        if (outcome == Outcome.Done) log.info(TAG, "account $accountId: the server's search brought $added messages the device did not have")
        return outcome to added
    }

    /**
     * Signs in to the account's server and runs [work] there. How it went is written on the
     * account: all well, sign in again, or what failed.
     */
    private suspend fun connected(accountId: Long, work: suspend (AccountEntity, RemoteAccount) -> Unit): Outcome = withContext(io) {
        val account = accounts.byId(accountId) ?: return@withContext Outcome.Gone
        val credentials = accounts.credentials(account)
        if (credentials == null) {
            accounts.setState(accountId, AccountState.NeedsLogin)
            return@withContext Outcome.Failed(MailException.Kind.Authentication, "")
        }
        try {
            connect(account.imapServer(), credentials).use { remote -> work(account, remote) }
            accounts.setState(accountId, AccountState.Ok)
            Outcome.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: MailException) {
            // already in the log, where it happened
            accounts.setState(accountId, stateFor(e.kind), e.message.orEmpty())
            Outcome.Failed(e.kind, e.message.orEmpty())
        } catch (e: Exception) {
            brokeOff(accountId, e)
        } catch (e: LinkageError) {
            // a part of a library this device cannot run: said on the account, not died of
            brokeOff(accountId, e)
        }
    }

    /** A fault that is neither the server's nor the network's: into the log with where it happened, and onto the account. */
    private suspend fun brokeOff(accountId: Long, error: Throwable): Outcome {
        log.error(TAG, "account $accountId: the sync broke off", error)
        accounts.setState(accountId, AccountState.Error, error.toString())
        return Outcome.Failed(MailException.Kind.Server, error.toString())
    }

    companion object {
        private const val TAG = "sync"

        /** How many messages of a folder get their text while its list is synced: what the user sees first. */
        const val FIRST_BODIES = 20

        /** How many texts the backlog fetches before it looks again at what is waiting. */
        const val BACKLOG_BODIES = 50

        /** The folders a search of the server does not look through. */
        private val UNSEARCHED = setOf(FolderRole.Drafts, FolderRole.Trash, FolderRole.Junk)

        /** Of what a search of the server finds in a folder, the newest so many are kept in mind. */
        private const val MATCHES_KEPT = 500

        /** How often one connection looks again for changes that became due while it was sending. */
        private const val PUSH_ROUNDS = 5

        fun stateFor(kind: MailException.Kind): AccountState = when (kind) {
            MailException.Kind.Authentication -> AccountState.NeedsLogin
            MailException.Kind.Network -> AccountState.Unreachable
            MailException.Kind.Security, MailException.Kind.Server -> AccountState.Error
        }
    }
}

/** The change as the server is told of it; null for a move whose target folder is gone. */
internal fun DueChange.toChange(): PendingChange? {
    val what = when (kind) {
        PendingOpEntity.SEEN -> PendingChange.Kind.Seen
        PendingOpEntity.FLAGGED -> PendingChange.Kind.Flagged
        PendingOpEntity.ANSWERED -> PendingChange.Kind.Answered
        PendingOpEntity.FORWARDED -> PendingChange.Kind.Forwarded
        PendingOpEntity.MOVE -> PendingChange.Kind.Move
        PendingOpEntity.EXPUNGE -> PendingChange.Kind.Expunge
        // written by a newer version: nothing this one knows how to say
        else -> return null
    }
    if (what == PendingChange.Kind.Move && targetPath == null) return null
    return PendingChange(id, folderPath, uidValidity, uid, what, value, targetPath.orEmpty())
}
