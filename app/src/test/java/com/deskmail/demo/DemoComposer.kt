package com.deskmail.demo

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import com.deskmail.core.compose.Replies
import com.deskmail.core.mail.AddressList
import com.deskmail.core.mail.MailAddress
import com.deskmail.mail.Draft
import com.deskmail.mail.DraftFile
import com.deskmail.mail.DraftKind
import com.deskmail.mail.DraftSeed
import com.deskmail.mail.DraftState
import com.deskmail.mail.MailComposer
import com.deskmail.mail.MailContent
import com.deskmail.mail.SendCheck
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.InputStream
import java.time.Instant

/**
 * Writing and sending as the tests and the screenshots make them up: the messages are kept in
 * memory, and one that is sent moves to [sent]. Nothing leaves the device, and nothing is timed
 * here: the window that shows "Undo" says when a message is to go ([sendNow]), as it does in the
 * app.
 *
 * A test can say that the server will not take a message ([refusal]) or that a message has left
 * before it could be taken back ([tooLate]), and can put a message into the outbox as it would
 * stand there ([put]).
 *
 * @param mail the made-up mail a reply takes its original from.
 * @param undoMs how long "Send" says a message waits.
 */
class DemoComposer(private val mail: DemoMail, private val now: Instant = Instant.now(), var undoMs: Long = 10_000) : MailComposer {

    private val drafts = mutableStateListOf<Draft>()
    private var nextId = 1L
    private var nextFileId = 1L

    override val outbox: List<Draft> get() = drafts

    /** The messages that left, as they stood when they did. */
    val sent = ArrayList<Draft>()

    /** What the screens kept without waiting ([keep]), by the message's id. */
    val keptOnTheWayOut = ArrayList<Long>()

    /** Why the server takes nothing; null while it takes everything. */
    var refusal: String? = null

    /** A message that waits has in fact left: it cannot be taken back. */
    var tooLate = false

    fun draft(id: Long): Draft? = drafts.firstOrNull { it.id == id }

    /** Puts a message into the outbox as it would stand there; returns its id. */
    fun put(draft: Draft): Long {
        val id = nextId++
        drafts.add(0, draft.copy(id = id, updatedAt = now))
        return id
    }

    override suspend fun start(kind: DraftKind, accountId: Long?, sourceId: Long?, seed: DraftSeed): Long? {
        val source = if (kind == DraftKind.New || sourceId == null) null else (mail.message(sourceId) ?: return null)
        val account = (source?.accountId ?: accountId)?.let { id -> mail.accounts.firstOrNull { it.id == id } } ?: mail.accounts.firstOrNull() ?: return null
        val id = nextId++
        val signed = if (account.signature.isBlank()) "" else "\n\n-- \n" + account.signature + "\n"
        val draft = if (source == null) {
            Draft(id, account.id, to = seed.to, cc = seed.cc, subject = seed.subject, body = seed.body + signed)
        } else {
            val text = (mail.observeContent(source.id).first() as? MailContent.Ready)?.text.orEmpty()
            val sender = AddressList.format(listOf(MailAddress(source.fromName, source.fromAddress)))
            if (kind == DraftKind.Forward) {
                val details = listOf("Kimden: $sender", "Konu: ${source.subject}", "Kime: ${source.to}")
                Draft(
                    id, account.id,
                    subject = Replies.forwardSubject(source.subject),
                    body = signed + Replies.forward(text, "---------- İletilen ileti ----------", details),
                    files = (mail.observeContent(source.id).first() as? MailContent.Ready)?.attachments.orEmpty().map { DraftFile(nextFileId++, it.name, it.bytes) },
                )
            } else {
                Draft(
                    id, account.id,
                    to = sender,
                    // everybody else the message went to; the made-up mail only has their names
                    cc = if (kind == DraftKind.ReplyAll) source.to.split(", ").filter { it != DemoMail.ME }.joinToString(", ") { "$it <${addressOf(it)}>" } else "",
                    subject = Replies.replySubject(source.subject),
                    body = signed + Replies.quote(text, "${source.fromName} yazdı:"),
                )
            }
        }
        drafts.add(0, draft.copy(updatedAt = now))
        return id
    }

    /** An address for somebody the made-up mail knows by name only. */
    private fun addressOf(name: String): String = name.lowercase().replace(' ', '.').replace('ş', 's').replace('ı', 'i') + "@isyeri.example"

    override fun observe(id: Long): Flow<Draft?> = snapshotFlow { draft(id) }

    override suspend fun save(id: Long, accountId: Long, to: String, cc: String, bcc: String, subject: String, body: String) {
        edit(id) { draft ->
            if (draft.state == DraftState.Waiting) draft else draft.copy(accountId = accountId, to = to, cc = cc, bcc = bcc, subject = subject, body = body)
        }
    }

    override fun keep(id: Long, accountId: Long, to: String, cc: String, bcc: String, subject: String, body: String) {
        keptOnTheWayOut += id
        edit(id) { draft ->
            if (draft.state == DraftState.Waiting) draft else draft.copy(accountId = accountId, to = to, cc = cc, bcc = bcc, subject = subject, body = body)
        }
    }

    override suspend fun attach(id: Long, name: String, mimeType: String, open: () -> InputStream?): Boolean {
        val bytes = open()?.use { it.readBytes().size.toLong() } ?: return false
        edit(id) { it.copy(files = it.files + DraftFile(nextFileId++, name, bytes)) }
        return draft(id) != null
    }

    override suspend fun detach(fileId: Long) {
        drafts.toList().forEach { draft -> if (draft.files.any { it.id == fileId }) edit(draft.id) { it.copy(files = it.files.filter { file -> file.id != fileId }) } }
    }

    override suspend fun send(id: Long): SendCheck {
        val draft = draft(id) ?: return SendCheck.Gone
        val typed = listOf(draft.to, draft.cc, draft.bcc).map { AddressList.parse(it) }
        val bad = typed.flatMap { it.invalid }
        if (bad.isNotEmpty()) return SendCheck.BadAddresses(bad)
        if (typed.all { it.addresses.isEmpty() }) return SendCheck.NoRecipients
        edit(id) { it.copy(state = DraftState.Waiting, error = "") }
        return SendCheck.Queued(undoMs)
    }

    override suspend fun unsend(id: Long): Boolean {
        val draft = draft(id) ?: return false
        if (draft.state != DraftState.Waiting || tooLate) return false
        edit(id) { it.copy(state = DraftState.Writing) }
        return true
    }

    override fun sendNow(id: Long) {
        val draft = draft(id) ?: return
        if (draft.state == DraftState.Writing) return
        val refused = refusal
        if (refused != null) {
            edit(id) { it.copy(state = DraftState.Failed, error = refused) }
        } else {
            drafts.remove(draft)
            sent += draft
        }
    }

    override suspend fun discard(id: Long): Boolean {
        drafts.removeAll { it.id == id }
        return true
    }

    override fun suggest(typed: String, limit: Int): List<MailAddress> = emptyList()

    private fun edit(id: Long, change: (Draft) -> Draft) {
        val index = drafts.indexOfFirst { it.id == id }
        if (index < 0) return
        val edited = change(drafts[index])
        if (edited != drafts[index]) drafts[index] = edited.copy(updatedAt = now)
    }
}
