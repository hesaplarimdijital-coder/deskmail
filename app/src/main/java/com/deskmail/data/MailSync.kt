package com.deskmail.data

import com.deskmail.core.imap.ImapConnection
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.MailException
import com.deskmail.core.mail.RemoteAccount
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.sync.AccountSync
import com.deskmail.core.sync.FolderSync
import com.deskmail.db.AccountEntity
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.MessageEntity
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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the sync ([AccountSync]) for the app's accounts: signs in, brings the device's copy up to
 * what the server has, and writes down on the account how that went.
 *
 * Three kinds of work, each on a connection of its own, so that none waits for another:
 * - the lists ([account], [folder]): which messages there are and their marks, and the text of
 *   the newest few. One at a time per account.
 * - the texts the lists left for later ([backlog]), in the background until none is missing;
 * - the text of the one message the user has just opened ([body]), ahead of its turn.
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
    private val listLocks = ConcurrentHashMap<Long, Mutex>()
    private val readLocks = ConcurrentHashMap<Long, Mutex>()
    private val backlogs = ConcurrentHashMap<Long, Boolean>()
    private val listing = MutableStateFlow<Set<Long>>(emptySet())

    /** The accounts whose lists are being brought up to date right now. */
    val syncing: StateFlow<Set<Long>> get() = listing

    /**
     * Brings an account up to date: its folders with the server's counts, its inbox, and the
     * folders at the paths [also].
     */
    suspend fun account(accountId: Long, also: Set<String> = emptySet()): Outcome =
        listed(accountId) { account, remote ->
            accountSync.sync(remote, accountId, account.syncDays, clock(), also, FIRST_BODIES)
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
                connected(accountId, work)
            } finally {
                listing.update { it - accountId }
            }
        }
        if (outcome == Outcome.Done) startBacklog(accountId)
        return outcome
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
            log.error(TAG, "account $accountId: the sync broke off", e)
            accounts.setState(accountId, AccountState.Error, e.toString())
            Outcome.Failed(MailException.Kind.Server, e.toString())
        }
    }

    companion object {
        private const val TAG = "sync"

        /** How many messages of a folder get their text while its list is synced: what the user sees first. */
        const val FIRST_BODIES = 20

        /** How many texts the backlog fetches before it looks again at what is waiting. */
        const val BACKLOG_BODIES = 50

        fun stateFor(kind: MailException.Kind): AccountState = when (kind) {
            MailException.Kind.Authentication -> AccountState.NeedsLogin
            MailException.Kind.Network -> AccountState.Unreachable
            MailException.Kind.Security, MailException.Kind.Server -> AccountState.Error
        }
    }
}
