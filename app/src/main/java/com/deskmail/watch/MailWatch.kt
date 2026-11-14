package com.deskmail.watch

import com.deskmail.core.imap.ImapWatch
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.FolderWatch
import com.deskmail.core.mail.MailException
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.ServerAddress
import com.deskmail.data.AccountStore
import com.deskmail.data.accountState
import com.deskmail.data.folderRole
import com.deskmail.data.imapServer
import com.deskmail.db.DeskMailDatabase
import com.deskmail.mail.AccountState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Has the accounts' inboxes waited on for mail as it comes: a connection per account that does
 * nothing but wait ([FolderWatch], IMAP IDLE). What a server tells of is fetched by the sync, on
 * connections of its own ([onNews]); telling the user of new mail is the sync's business from
 * there.
 *
 * - A wait is begun anew every so often ([refresh]): servers end a wait that is left alone for
 *   half an hour, and a line that went dead says nothing and is found out no other way.
 * - A lost line is made anew: at once when the network changed ([networkChanged]) or the server
 *   stopped answering, otherwise after a pause that grows from [firstRetryMs] to
 *   [longestRetryMs].
 * - An account whose server refuses the sign-in is not tried again: it is marked as needing its
 *   owner, and watched once more after it was reconnected ([arrange]).
 * - Every beginning, loss and new beginning is written to the log. Whether the wait lives through
 *   a night on the device is what the log is there to show.
 *
 * @param scope where the watching runs; it outlives the windows.
 * @param onNews something changed in the account's inbox, or the inbox is watched from now on
 *   and may have changed meanwhile: the account is to be synced. One at a time per account; news
 *   that comes while it runs is one more run.
 * @param onStir a server said something, this moment. Called on a thread of the mail library's,
 *   before anything is done about it: the device is kept awake from here.
 * @param open signs in and begins to wait on a folder; null when the server cannot do that. The
 *   tests hand in watches of their own.
 */
class MailWatch(
    private val db: DeskMailDatabase,
    private val accounts: AccountStore,
    private val log: AppLog,
    private val scope: CoroutineScope,
    private val onNews: suspend (accountId: Long) -> Unit,
    private val onStir: () -> Unit = {},
    private val open: (ServerAddress, Credentials, String, () -> Unit) -> FolderWatch? = { server, credentials, path, news ->
        ImapWatch.open(server, credentials, path, log, news)
    },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val firstRetryMs: Long = FIRST_RETRY_MS,
    private val longestRetryMs: Long = LONGEST_RETRY_MS,
) {
    private val arranging = Mutex()
    private val watchers = HashMap<Long, Watcher>()
    private val waiting = MutableStateFlow<Set<Long>>(emptySet())

    /** The accounts whose inbox is being waited on right now. */
    val watched: StateFlow<Set<Long>> get() = waiting

    /**
     * Begins to watch, or brings the watching in line with the accounts there are now: every
     * account that can be signed in to is watched, one that is gone no longer is. Asked again
     * whenever an account was added, reconnected or removed.
     */
    suspend fun arrange() = apart {
        arranging.withLock {
            val wanted = accounts.all().filter { it.accountState() != AccountState.NeedsLogin }.map { it.id }.toSet()
            for ((id, watcher) in watchers.entries.toList()) {
                // gone, or it gave up (its server refused the sign-in, or cannot be waited on)
                if (id !in wanted || !watcher.alive) {
                    watcher.stop()
                    watchers.remove(id)
                }
            }
            for (id in wanted) {
                if (id !in watchers) watchers[id] = Watcher(id).also { it.start() }
            }
        }
    }

    /** Ends all watching. */
    suspend fun stop() = apart {
        arranging.withLock {
            watchers.values.forEach { it.stop() }
            watchers.clear()
        }
    }

    /** Begins every wait anew. Returns when each server has answered or was given up on. */
    suspend fun refresh() = apart {
        val live = arranging.withLock { watchers.values.toList() }
        coroutineScope { live.forEach { watcher -> launch { watcher.refresh() } } }
    }

    /**
     * Runs [work] on the watch's own threads, whoever asks: what is arranged here is never held
     * up by the thread that asked for it (the main thread, where the service and the settings
     * live), and never holds that thread up.
     */
    private suspend fun <T> apart(work: suspend CoroutineScope.() -> T): T = withContext(io, work)

    /**
     * The network the device is on changed: lines made over the old one are dead, or will be.
     * They are made anew now, and whoever pauses before trying again tries now.
     */
    fun networkChanged() {
        scope.launch(io) {
            val live = arranging.withLock { watchers.values.toList() }
            if (live.isNotEmpty()) log.info(TAG, "the network changed: the lines are made anew")
            live.forEach { it.restart() }
        }
    }

    /** How a try at watching an account went. */
    private enum class Session {
        /** It was watched for a while, and the line was lost. */
        Watched,

        /** The line could not be made. */
        Failed,

        /** There is nothing to watch any more: the account is gone, needs its owner, or cannot be waited on. */
        Over,
    }

    private inner class Watcher(private val accountId: Long) {
        /** Ends the pause before the next try. */
        private val retryNow = Channel<Unit>(Channel.CONFLATED)

        @Volatile
        private var watch: FolderWatch? = null
        private var job: Job? = null

        val alive: Boolean get() = job?.isActive == true

        fun start() {
            job = scope.launch(io) { run() }
        }

        /** Ends the watching: the wait is cut, since nothing else ends it. */
        fun stop() {
            job?.cancel()
            cut()
        }

        /** Makes the line anew, now. */
        fun restart() {
            retryNow.trySend(Unit)
            cut()
        }

        fun refresh() {
            val live = watch ?: return
            try {
                live.refresh()
                log.info(TAG, "account $accountId: the wait was begun anew")
            } catch (e: MailException) {
                // why is in the log where it happened; no reason to pause before a new line
                log.warn(TAG, "account $accountId: the server no longer answers; the line is made anew")
                retryNow.trySend(Unit)
                runCatching { live.close() }
            }
        }

        /** Closes the line that is waited on, if there is one. Not here: closing can take a moment. */
        private fun cut() {
            val live = watch ?: return
            scope.launch(io) { runCatching { live.close() } }
        }

        private suspend fun run() {
            var pause = firstRetryMs
            while (true) {
                when (session()) {
                    Session.Over -> return
                    // it held for a while: the next try comes soon
                    Session.Watched -> pause = firstRetryMs
                    Session.Failed -> Unit
                }
                currentCoroutineContext().ensureActive()
                log.info(TAG, "account $accountId: the next try is in ${pause / 1000} s, or when the network changes")
                withTimeoutOrNull(pause) { retryNow.receive() }
                pause = (pause * 2).coerceAtMost(longestRetryMs)
            }
        }

        private suspend fun session(): Session {
            val account = accounts.byId(accountId) ?: return Session.Over
            val credentials = try {
                accounts.credentials(account)
            } catch (e: MailException) {
                // the provider handed out no new access token; why is in the log
                if (e.kind != MailException.Kind.Authentication) return Session.Failed
                accounts.setState(accountId, AccountState.NeedsLogin, e.message.orEmpty())
                log.warn(TAG, "account $accountId: its provider would not renew the sign-in; it is watched again once it was reconnected")
                return Session.Over
            }
            if (credentials == null) {
                accounts.setState(accountId, AccountState.NeedsLogin)
                log.warn(TAG, "account $accountId: what it signs in with cannot be read; it is watched again once it was reconnected")
                return Session.Over
            }
            val inbox = db.folders().ofAccount(accountId).firstOrNull { folderRole(it.role) == FolderRole.Inbox }?.path ?: DEFAULT_INBOX
            val news = Channel<Unit>(Channel.CONFLATED)
            val made = try {
                open(account.imapServer(), credentials, inbox) {
                    onStir()
                    news.trySend(Unit)
                }
            } catch (e: MailException) {
                // why is in the log, where it happened
                if (e.kind != MailException.Kind.Authentication) return Session.Failed
                accounts.setState(accountId, AccountState.NeedsLogin, e.message.orEmpty())
                log.warn(TAG, "account $accountId: its server refused the sign-in; it is watched again once it was reconnected")
                return Session.Over
            }
            if (made == null) {
                log.info(TAG, "account $accountId: its server cannot be waited on; its mail is looked for from time to time")
                return Session.Over
            }
            watch = made
            try {
                // stopped while the line was being made: nobody else knows of it yet
                currentCoroutineContext().ensureActive()
                waiting.update { it + accountId }
                log.info(TAG, "account $accountId: waiting on $inbox")
                coroutineScope {
                    val telling = launch {
                        for (word in news) tell()
                    }
                    // what came while nobody waited
                    news.trySend(Unit)
                    try {
                        while (true) {
                            // until the wait is begun anew, the line is lost, or it is cut
                            made.await()
                            ensureActive()
                        }
                    } finally {
                        telling.cancel()
                    }
                }
            } catch (e: MailException) {
                log.info(TAG, "account $accountId: the wait on $inbox ended: ${e.message}")
            } finally {
                watch = null
                waiting.update { it - accountId }
                runCatching { made.close() }
            }
            return Session.Watched
        }

        private suspend fun tell() {
            try {
                onNews(accountId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // what follows the news is not the watching: that goes on
                log.error(TAG, "account $accountId: there was news, but what follows that failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "watch"

        /** Where the mail arrives when the account's folders are not known yet. */
        private const val DEFAULT_INBOX = "INBOX"

        /** The pause before a lost line is tried again, and what it grows to while the tries fail. */
        const val FIRST_RETRY_MS = 30_000L
        const val LONGEST_RETRY_MS = 15 * 60_000L
    }
}
