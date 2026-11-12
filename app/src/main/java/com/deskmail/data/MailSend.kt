package com.deskmail.data

import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.AddressList
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailAddress
import com.deskmail.core.mail.MailException
import com.deskmail.core.mime.MessageWriter
import com.deskmail.core.mime.OutgoingAttachment
import com.deskmail.core.mime.OutgoingMessage
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.provider.ProviderTable
import com.deskmail.core.smtp.SmtpSender
import com.deskmail.db.AccountEntity
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.OutboxDao
import com.deskmail.db.OutboxEntity
import com.deskmail.mail.AccountState
import com.deskmail.mail.Draft
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
 * Has the outbox looked at ([MailSend.sendDue]) once [delayMs] have passed, as soon as there is a
 * network then. There is one such appointment at a time: a new one takes the place of the one
 * before it.
 */
fun interface SendScheduler {
    fun schedule(delayMs: Long)
}

/** Has the outbox looked at when the first of its waiting messages is to go; not at all when none waits. */
internal suspend fun SendScheduler.scheduleNext(outbox: OutboxDao, now: Long) {
    val first = outbox.earliest() ?: return
    schedule((first - now).coerceAtLeast(0))
}

/**
 * What a look at the outbox came to.
 *
 * @param unreachable a message is still waiting because its server could not be reached: worth
 *   another try later.
 * @param nextInMs how long until the next message has waited out its time to be taken back; null
 *   when none is waiting for that.
 */
data class SendRound(val unreachable: Boolean, val nextInMs: Long?)

/**
 * Sends what waits in the outbox ([OutboxEntity]): writes each message as it travels, hands it to
 * its account's outgoing server, and then sees to what follows from its having been sent.
 *
 * - The copy under Sent: the server keeps one itself with the providers that say so
 *   ([com.deskmail.core.provider.Provider.savesSentItself]: Gmail, Outlook); for everybody else
 *   the same message is added to the account's Sent folder, with its hidden recipients.
 * - The message that was answered or sent on is marked so, on the device at once and on the
 *   server through the queue of pending changes.
 *
 * A message the server cannot be reached for stays waiting. One the server will not take
 * (a recipient it refuses, a sign-in it no longer accepts) is kept as failed, with the server's
 * words, for its writer to look at.
 *
 * Nothing here decides when to look: [sendDue] is called by the background work that the
 * [SendScheduler] has run ([com.deskmail.work.SendWorker] in the app).
 *
 * @param work where a message is written out before it is handed over.
 * @param files where the files attached to waiting messages are kept, a directory for each message.
 * @param send hands a written message to an outgoing server; the tests look at what is handed over.
 */
class MailSend(
    private val db: DeskMailDatabase,
    private val accounts: AccountStore,
    private val sync: MailSync,
    private val log: AppLog,
    private val work: File,
    private val files: File,
    private val send: (ServerAddress, Credentials, String, List<String>, File) -> Unit =
        { server, credentials, from, recipients, message -> SmtpSender.send(server, credentials, from, recipients, message, log) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val lock = Mutex()

    /** Whether this run of the app has looked at what the run before it left behind ([recover]). */
    private var recovered = false

    /**
     * A message an earlier run of the app was handing to its server when it ended is kept as
     * failed: whether the server took it is not known, and it is not sent a second time unasked.
     *
     * Looked at once in a run, and before that run sends anything: whoever asks later finds it
     * done, so a message this run is handing over right now is never taken for one that was cut
     * short. Returns how many there were.
     */
    suspend fun recover(): Int = lock.withLock { withContext(NonCancellable + io) { recoverOnce() } }

    private suspend fun recoverOnce(): Int {
        if (recovered) return 0
        recovered = true
        val interrupted = db.outbox().failInterrupted(Draft.INTERRUPTED, now())
        if (interrupted > 0) log.warn(TAG, "$interrupted messages were being sent when the app ended; they are kept as failed")
        return interrupted
    }

    /** Sends every message whose time has come, one after the other. */
    suspend fun sendDue(): SendRound = lock.withLock {
        // Once a message is being handed over it is seen through: stopping half way would leave
        // nobody knowing whether the server took it.
        withContext(NonCancellable + io) {
            recoverOnce()
            val outbox = db.outbox()
            var unreachable = false
            for (row in outbox.due(now())) {
                // taken back in this very moment: then it is no longer waiting
                if (outbox.claim(row.id, now()) == 0) continue
                val failure = try {
                    sendOne(row)
                } catch (e: Exception) {
                    // nothing here is expected to throw; if something does, the message does not stay "being sent" for ever
                    log.error(TAG, "message ${row.id} could not be sent", e)
                    MailException(MailException.Kind.Server, e.toString())
                }
                when {
                    failure == null -> {
                        outbox.delete(row.id)
                        File(files, row.id.toString()).deleteRecursively()
                    }
                    failure.kind == MailException.Kind.Network -> {
                        outbox.setState(row.id, OutboxEntity.QUEUED, row.sendAfter, failure.message.orEmpty(), now())
                        unreachable = true
                    }
                    else -> outbox.setState(row.id, OutboxEntity.FAILED, 0, failure.message.orEmpty(), now())
                }
            }
            // what is still within its time to be taken back
            val time = now()
            SendRound(unreachable, outbox.nextDue(time)?.let { it - time })
        }
    }

    /** Sends one message; null when the server took it, else why it did not. */
    private suspend fun sendOne(row: OutboxEntity): MailException? {
        val account = accounts.byId(row.accountId) ?: return MailException(MailException.Kind.Server, "the account is gone")
        val credentials = accounts.credentials(account)
        if (credentials == null) {
            accounts.setState(account.id, AccountState.NeedsLogin)
            return MailException(MailException.Kind.Authentication, "the account has to sign in again")
        }
        val message = try {
            written(account, row)
        } catch (e: IllegalArgumentException) {
            return MailException(MailException.Kind.Server, e.message.orEmpty())
        }
        val file = File(work, "${row.id}.eml")
        try {
            work.mkdirs()
            file.outputStream().buffered().use { MessageWriter.write(message, it, zone()) }
            send(account.smtpServer(), credentials, account.address, message.recipients, file)
        } catch (e: MailException) {
            file.delete()
            if (e.kind == MailException.Kind.Authentication) accounts.setState(account.id, AccountState.NeedsLogin, e.message.orEmpty())
            return e
        } catch (e: IOException) {
            // a file that was attached is no longer where it was kept
            file.delete()
            log.warn(TAG, "message ${row.id} could not be written: $e")
            return MailException(MailException.Kind.Server, "a file of the message could not be read")
        }
        log.info(TAG, "account ${account.id}: a message to ${message.recipients.size} recipients was sent, ${message.attachments.size} files")
        // Sent it is, whatever becomes of the rest: nothing below may make it look unsent.
        try {
            keepCopy(account, message, file)
            markSource(row)
        } catch (e: Exception) {
            log.warn(TAG, "message ${row.id} was sent, but not everything after that worked: $e")
        } finally {
            file.delete()
        }
        return null
    }

    /** The message as it will travel. @throws IllegalArgumentException when it has nobody to go to. */
    private suspend fun written(account: AccountEntity, row: OutboxEntity): OutgoingMessage {
        val to = AddressList.parse(row.toText).addresses
        val cc = AddressList.parse(row.ccText).addresses
        val bcc = AddressList.parse(row.bccText).addresses
        require(to.isNotEmpty() || cc.isNotEmpty() || bcc.isNotEmpty()) { "the message has nobody to go to" }
        val attached = db.outbox().files(row.id)
        return OutgoingMessage(
            from = MailAddress(account.senderName, account.address),
            to = to,
            cc = cc,
            bcc = bcc,
            subject = row.subject,
            text = row.body,
            messageId = row.messageId,
            date = Instant.ofEpochMilli(now()),
            inReplyTo = row.inReplyTo,
            references = row.refs,
            attachments = attached.map { kept -> OutgoingAttachment(kept.name, kept.mimeType) { File(kept.path).inputStream() } },
        )
    }

    /** Files the sent message under Sent where the provider does not, and has the Sent folder's list brought up to date. */
    private suspend fun keepCopy(account: AccountEntity, message: OutgoingMessage, file: File) {
        val sent = db.folders().ofAccount(account.id).firstOrNull { folderRole(it.role) == FolderRole.Sent }
        if (sent == null) {
            log.info(TAG, "account ${account.id}: no Sent folder to file the message under")
            return
        }
        if (ProviderTable.byId(account.provider)?.savesSentItself != true) {
            // with the hidden recipients: the writer may read whom he wrote to
            file.outputStream().buffered().use { MessageWriter.write(message, it, zone(), withBcc = true) }
            val appended = sync.append(account.id, sent.id, file, seen = true, draft = false)
            if (appended.outcome != MailSync.Outcome.Done) log.warn(TAG, "account ${account.id}: the sent message could not be filed under ${sent.path}: ${appended.outcome}")
        }
        // a Sent folder that is kept on the device shows the message without waiting to be opened
        if (sent.syncedAt != 0L) sync.folder(sent.id)
    }

    /** The message that was answered or sent on is marked so. */
    private suspend fun markSource(row: OutboxEntity) {
        if (row.sourceId == 0L) return
        val forwarded = row.sourceKind == OutboxEntity.FORWARD
        if (!forwarded && row.sourceKind != OutboxEntity.REPLY) return
        val accountId = db.pending().markAnswered(row.sourceId, forwarded, now()) ?: return
        sync.changes(accountId)
    }

    private companion object {
        const val TAG = "send"
    }
}
