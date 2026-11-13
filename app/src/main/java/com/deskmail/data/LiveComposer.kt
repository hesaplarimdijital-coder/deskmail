package com.deskmail.data

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deskmail.core.compose.Replies
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.AddressList
import com.deskmail.core.mail.MailAddress
import com.deskmail.core.text.TurkishFold
import com.deskmail.db.AccountEntity
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.MessageEntity
import com.deskmail.db.OutboxEntity
import com.deskmail.db.OutboxFileEntity
import com.deskmail.db.TemplateEntity
import com.deskmail.mail.Draft
import com.deskmail.mail.DraftFile
import com.deskmail.mail.DraftKind
import com.deskmail.mail.DraftSeed
import com.deskmail.mail.DraftState
import com.deskmail.mail.FetchedFile
import com.deskmail.mail.MailAttachment
import com.deskmail.mail.MailComposer
import com.deskmail.mail.MessageTemplate
import com.deskmail.mail.SendCheck
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.UUID

/**
 * The words a reply and a forwarded message are begun with, in the reader's language.
 *
 * @param wrote the line over a quoted message, from when it was written and by whom.
 * @param forwarded the line that sets a forwarded message off from what is written above it.
 * @param date how a moment is written out.
 */
class ComposeWords(
    val wrote: (date: String, who: String) -> String,
    val forwarded: String,
    val from: String,
    val sent: String,
    val subject: String,
    val to: String,
    val cc: String,
    val date: (Instant) -> String,
)

/**
 * The messages the user writes, kept in the database as they are typed, and handed to
 * [MailSend] when they are to go.
 *
 * @param files where the files attached to messages are copied to, a directory for each message.
 * @param scheduler has the outbox sent; the app's goes through WorkManager.
 * @param fetchFile fetches a file of a received message: what a forwarded message takes along.
 * @param recover sees to what an earlier run of the app left half sent ([MailSend.recover]), before the outbox is shown.
 * @param undoMs how long a message waits after "Send" before it leaves: the time to take it back.
 * @param saveDrafts has what is being written brought to the servers' Drafts folders in so many
 *   milliseconds ([DraftSync]); asked whenever a draft was touched or its copy is of no use any more.
 */
@Stable
class LiveComposer(
    private val db: DeskMailDatabase,
    private val sync: MailSync,
    private val scope: CoroutineScope,
    private val files: File,
    private val scheduler: SendScheduler,
    private val fetchFile: suspend (MailAttachment) -> FetchedFile,
    private val words: () -> ComposeWords,
    private val recover: suspend () -> Unit = {},
    private val log: AppLog? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val undoMs: () -> Long = { UNDO_SEND_MS },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val saveDrafts: (delayMs: Long) -> Unit = {},
) : MailComposer {

    override var outbox: List<Draft> by mutableStateOf(emptyList())
        private set

    override var templates: List<MessageTemplate> by mutableStateOf(emptyList())
        private set

    /** The addresses mail was had with lately, the latest first: what is offered while a recipient is typed. */
    @Volatile
    private var known: List<MailAddress> = emptyList()

    init {
        scope.launch {
            combine(db.outbox().observeAll(), db.outbox().observeFiles()) { rows, kept -> rows.map { row -> row.toDraft(kept.filter { it.outboxId == row.id }) } }
                .distinctUntilChanged()
                .collect { outbox = it }
        }
        scope.launch {
            combine(db.messages().observeNewest(SUGGEST_FROM).paced(SUGGEST_PACE_MS), db.accounts().observeAll()) { messages, accounts ->
                val own = accounts.mapTo(HashSet()) { it.address.lowercase() }
                correspondents(messages, own)
            }.collect { known = it }
        }
        scope.launch {
            db.lists().observeTemplates().collect { rows -> templates = rows.map { MessageTemplate(it.id, it.name, it.body) } }
        }
        scope.launch {
            // what an earlier run left behind: half sent, or waiting with nobody asked to send it
            recover()
            scheduler.scheduleNext(db.outbox(), now())
        }
    }

    override suspend fun start(kind: DraftKind, accountId: Long?, sourceId: Long?, seed: DraftSeed): Long? {
        val time = now()
        val source = if (kind == DraftKind.New || sourceId == null) null else (db.messages().byId(sourceId) ?: return null)
        if (kind == DraftKind.Edit && source != null) {
            // a draft of this device's own is taken up where it was left: every copy of it carries its Message-ID
            if (source.globalId.isNotBlank()) db.outbox().writtenUnder(source.globalId)?.let { return it.id }
            // one being written beside a draft that could not be taken up whole
            db.outbox().copiedFrom(source.id)?.let { return it.id }
        }
        val account = (source?.accountId ?: accountId)?.let { db.accounts().byId(it) } ?: db.accounts().all().firstOrNull() ?: return null
        var row = OutboxEntity(
            accountId = account.id,
            state = OutboxEntity.DRAFT,
            toText = seed.to,
            ccText = seed.cc,
            bccText = seed.bcc,
            subject = seed.subject,
            body = seed.body + signed(account),
            messageId = "<" + UUID.randomUUID() + "@" + account.address.substringAfterLast('@', "deskmail.invalid") + ">",
            inReplyTo = "",
            refs = "",
            sourceId = 0,
            sourceKind = "",
            draftFolderId = 0,
            draftUid = 0,
            sendAfter = 0,
            error = "",
            createdAt = time,
            updatedAt = time,
            // Nothing of it is worth a place on the server before something is written into it; what
            // another app asked for (a link's recipient, something shared) is worth one as it stands.
            draftSavedAt = if (seed == DraftSeed()) time else 0,
        )
        if (source != null) row = from(row, kind, source, account)
        val id = db.outbox().insert(row)
        if (source != null && (kind == DraftKind.Forward || kind == DraftKind.Edit)) {
            val allFiles = takeFilesAlong(id, source)
            if (kind == DraftKind.Edit) takeUp(id, source, allFiles)
        }
        if (row.draftSavedAt < row.updatedAt) saveDrafts(DraftSync.SAVE_AFTER_MS)
        return id
    }

    /** What a reply or a forwarded message takes from the message it comes from. */
    private suspend fun from(row: OutboxEntity, kind: DraftKind, source: MessageEntity, account: AccountEntity): OutboxEntity {
        val words = words()
        // a message that is answered before its text was fetched is fetched now
        if (source.bodyState == MessageEntity.BODY_MISSING) sync.body(source.id)
        val text = db.messages().bodyOf(source.id)?.text.orEmpty()
        val from = MailAddress.decode(source.fromList)
        val to = MailAddress.decode(source.toList)
        val cc = MailAddress.decode(source.ccList)
        if (kind == DraftKind.Edit) {
            // The draft as it lies on the server: what it says, whom it is for, and its place in a
            // thread. Nothing is written into it yet that the server lacks. Whether its copy there
            // becomes this message's is known once its files are here ([takeUp]).
            return row.copy(
                toText = AddressList.format(to),
                ccText = AddressList.format(cc),
                bccText = AddressList.format(MailAddress.decode(source.bccList)),
                subject = source.subject,
                body = text,
                inReplyTo = source.inReplyTo,
                refs = source.refs,
                sourceId = source.id,
                sourceKind = OutboxEntity.EDIT,
                draftSavedAt = row.updatedAt,
            )
        }
        val writer = from.firstOrNull()?.toString().orEmpty()
        val written = words.date(Instant.ofEpochMilli(if (source.sentAt != 0L) source.sentAt else source.date))
        if (kind == DraftKind.Forward) {
            val details = buildList {
                add(words.from + ": " + AddressList.format(from))
                add(words.sent + ": " + written)
                add(words.subject + ": " + source.subject)
                add(words.to + ": " + AddressList.format(to))
                if (cc.isNotEmpty()) add(words.cc + ": " + AddressList.format(cc))
            }
            return row.copy(
                subject = Replies.forwardSubject(source.subject),
                body = signed(account) + Replies.forward(text, words.forwarded, details),
                sourceId = source.id,
                sourceKind = OutboxEntity.FORWARD,
            )
        }
        val mine = db.accounts().all().mapTo(HashSet()) { it.address }
        val recipients = Replies.recipients(from, MailAddress.decode(source.replyToList), to, cc, mine, all = kind == DraftKind.ReplyAll)
        return row.copy(
            toText = AddressList.format(recipients.to),
            ccText = AddressList.format(recipients.cc),
            subject = Replies.replySubject(source.subject),
            body = signed(account) + Replies.quote(text, words.wrote(written, writer)),
            inReplyTo = source.globalId,
            refs = Replies.references(source.refs, source.globalId),
            sourceId = source.id,
            sourceKind = OutboxEntity.REPLY,
        )
    }

    /** The account's signature as a message begins with it, set off the way mail programs know ("-- "). */
    private fun signed(account: AccountEntity): String = if (account.signature.isBlank()) "" else "\n\n-- \n" + account.signature.trim() + "\n"

    /**
     * A forwarded message's files go with it: each is fetched (if it has to be) and copied. One that
     * cannot be had is left out. False when one was.
     */
    private suspend fun takeFilesAlong(draftId: Long, source: MessageEntity): Boolean {
        var all = true
        for (file in db.messages().attachmentsOf(source.id).toFiles()) {
            val fetched = fetchFile(file)
            if (fetched is FetchedFile.Ready && attach(draftId, file.name, file.mimeType) { fetched.file.inputStream() }) continue
            all = false
            log?.warn(TAG, "a file of the message could not be taken along: $fetched")
        }
        return all
    }

    /**
     * A draft from the server's Drafts folder that is edited here: its copy there becomes this
     * message's, to be replaced by this device's copy once something is written, only where nothing
     * of it was lost in the taking. Its text was had; it is plain text (DeskMail writes no HTML); it
     * has no pictures inside its text; every file came along. Otherwise the copy there is left as it
     * is, and what is written here is saved beside it, under a Message-ID of its own. Either way
     * nothing is put on the server before something is written.
     */
    private suspend fun takeUp(id: Long, source: MessageEntity, allFiles: Boolean) {
        val messages = db.messages()
        val whole = allFiles && source.globalId.isNotBlank() &&
            messages.byId(source.id)?.bodyState == MessageEntity.BODY_KEPT &&
            messages.bodyOf(source.id)?.html.isNullOrBlank() &&
            messages.attachmentsOf(source.id).none { it.inline && it.contentId.isNotEmpty() }
        if (whole) {
            val folder = db.folders().byId(source.folderId)
            db.outbox().takeUp(id, source.globalId, source.folderId, source.uid, folder?.uidValidity ?: 0)
        } else {
            log?.info(TAG, "a draft from the server is written as a copy of its own: its copy there is left as it is")
            db.outbox().asItCame(id)
        }
    }

    override fun observe(id: Long): Flow<Draft?> =
        combine(db.outbox().observe(id), db.outbox().observeFiles()) { row, kept -> row?.toDraft(kept.filter { it.outboxId == id }) }.distinctUntilChanged()

    override suspend fun save(id: Long, accountId: Long, to: String, cc: String, bcc: String, subject: String, body: String) {
        val row = db.outbox().byId(id) ?: return
        val edited = row.copy(accountId = accountId, toText = to, ccText = cc, bccText = bcc, subject = subject, body = body)
        if (edited == row) return
        // another account sends under its own domain's name
        val messageId = if (accountId == row.accountId) {
            row.messageId
        } else {
            val domain = db.accounts().byId(accountId)?.address?.substringAfterLast('@') ?: return
            row.messageId.substringBefore('@') + "@" + domain + ">"
        }
        // only while it is being written on: what waits to be sent, or is on its way, stays as it was handed over
        if (db.outbox().write(id, accountId, to, cc, bcc, subject, body, messageId, now()) > 0) saveDrafts(DraftSync.SAVE_AFTER_MS)
    }

    override fun keep(id: Long, accountId: Long, to: String, cc: String, bcc: String, subject: String, body: String) {
        scope.launch { save(id, accountId, to, cc, bcc, subject, body) }
    }

    override suspend fun attach(id: Long, name: String, mimeType: String, open: () -> InputStream?): Boolean = withContext(io) {
        if (db.outbox().byId(id) == null) return@withContext false
        val directory = File(files, id.toString())
        // a directory of its own for every file: two files of one name do not meet
        val target = File(File(directory, UUID.randomUUID().toString()), safeFileName(name))
        try {
            val source = open() ?: return@withContext false
            target.parentFile?.mkdirs()
            source.use { input -> target.outputStream().use { input.copyTo(it) } }
            db.outbox().addFile(OutboxFileEntity(outboxId = id, name = safeFileName(name), mimeType = mimeType.ifBlank { "application/octet-stream" }, size = target.length(), path = target.path))
            touched(id)
            true
        } catch (e: Exception) {
            log?.warn(TAG, "a file could not be attached: $e")
            target.parentFile?.deleteRecursively()
            false
        }
    }

    override suspend fun detach(fileId: Long) {
        val file = db.outbox().file(fileId) ?: return
        db.outbox().removeFile(fileId)
        touched(file.outboxId)
        withContext(io) { File(file.path).parentFile?.deleteRecursively() }
    }

    /** A file came or went: the draft is ahead of its copy on the server, as after something typed. */
    private suspend fun touched(id: Long) {
        db.outbox().touch(id, now())
        saveDrafts(DraftSync.SAVE_AFTER_MS)
    }

    override suspend fun send(id: Long): SendCheck {
        val row = db.outbox().byId(id) ?: return SendCheck.Gone
        val typed = listOf(row.toText, row.ccText, row.bccText).map { AddressList.parse(it) }
        val bad = typed.flatMap { it.invalid }
        if (bad.isNotEmpty()) return SendCheck.BadAddresses(bad)
        if (typed.all { it.addresses.isEmpty() }) return SendCheck.NoRecipients
        val wait = undoMs()
        val time = now()
        if (db.outbox().queue(id, sendAfter = time + wait, now = time) == 0) return SendCheck.Gone
        scheduler.scheduleNext(db.outbox(), time)
        return SendCheck.Queued(wait)
    }

    override suspend fun unsend(id: Long): Boolean = db.outbox().unqueue(id, now()) > 0

    override fun sendNow(id: Long) {
        scope.launch {
            val time = now()
            val row = db.outbox().byId(id) ?: return@launch
            // a failed one is handed over again; a waiting one no longer waits
            if (row.state == OutboxEntity.FAILED) db.outbox().queue(id, sendAfter = time, now = time) else db.outbox().bringForward(id, time)
            // asked for anew even when nothing changed: "now" also means not waiting for the next try of its own
            scheduler.scheduleNext(db.outbox(), time)
        }
    }

    override suspend fun discard(id: Long): Boolean {
        val copied = db.outbox().byId(id)?.let { it.draftUid != 0L } == true
        if (db.outbox().discardWithCopy(id) == 0 && db.outbox().byId(id) != null) return false
        withContext(io) { File(files, id.toString()).deleteRecursively() }
        // the copy it had among the drafts on its server goes after it
        if (copied) saveDrafts(0)
        return true
    }

    override fun suggest(typed: String, limit: Int): List<MailAddress> {
        val wanted = TurkishFold.fold(typed.trim())
        if (wanted.length < SUGGEST_FROM_LETTERS) return emptyList()
        return known.asSequence()
            .filter { address -> TurkishFold.fold(address.address).startsWith(wanted) || TurkishFold.fold(address.name).split(' ').any { it.startsWith(wanted) } }
            .take(limit)
            .toList()
    }

    override suspend fun saveTemplate(id: Long, name: String, body: String): Long {
        val kept = name.replace(CONTROLS, " ").trim().take(MAX_TEMPLATE_NAME)
        val text = body.replace("\r\n", "\n").take(MAX_TEMPLATE)
        val lists = db.lists()
        if (id != 0L && lists.updateTemplate(id, kept, text) > 0) return id
        return lists.insertTemplate(TemplateEntity(name = kept, body = text, position = lists.nextTemplatePosition()))
    }

    override suspend fun removeTemplate(id: Long) = db.lists().deleteTemplate(id)

    override suspend fun awaitReply(id: Long, days: Int) {
        db.outbox().setAwait(id, days.coerceAtLeast(0))
    }

    companion object {
        private const val TAG = "compose"

        /** A text's name is one line of a menu; the text itself is a few paragraphs at most. */
        const val MAX_TEMPLATE_NAME = 80
        const val MAX_TEMPLATE = 10_000

        /** Line breaks, tabs and the like, however many in a row. */
        private val CONTROLS = Regex("\\p{Cntrl}+")

        /** How long a message waits after "Send" unless the settings say otherwise. */
        const val UNDO_SEND_MS = 10_000L

        /** How many of the newest messages the suggestions are drawn from, and how often at most they are drawn again. */
        private const val SUGGEST_FROM = 500
        private const val SUGGEST_PACE_MS = 5_000L
        private const val SUGGEST_FROM_LETTERS = 2

        /**
         * Everybody on [messages] who is not the user himself ([own]), each address once, in the
         * order the messages come (the newest first), with the name it was last seen with.
         */
        internal fun correspondents(messages: List<MessageEntity>, own: Set<String>): List<MailAddress> {
            val seen = HashSet<String>()
            val found = ArrayList<MailAddress>()
            for (message in messages) {
                for (list in listOf(message.fromList, message.toList, message.ccList)) {
                    for (address in MailAddress.decode(list)) {
                        val key = address.address.lowercase()
                        if (key.isEmpty() || key in own || !AddressList.isAddress(address.address) || !seen.add(key)) continue
                        found += address
                    }
                }
            }
            return found
        }
    }
}

internal fun OutboxEntity.toDraft(kept: List<OutboxFileEntity>) = Draft(
    id = id,
    accountId = accountId,
    to = toText,
    cc = ccText,
    bcc = bccText,
    subject = subject,
    body = body,
    files = kept.map { DraftFile(it.id, it.name, it.size) },
    state = when (state) {
        OutboxEntity.QUEUED, OutboxEntity.SENDING -> DraftState.Waiting
        OutboxEntity.FAILED -> DraftState.Failed
        else -> DraftState.Writing
    },
    error = error,
    updatedAt = Instant.ofEpochMilli(updatedAt),
    awaitDays = awaitDays,
)
