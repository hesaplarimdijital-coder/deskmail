package com.deskmail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailAddress
import com.deskmail.core.mail.MessageFlags
import com.deskmail.core.mail.RemoteFolder
import com.deskmail.core.mail.RemoteMessage
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.testAccount
import com.deskmail.mail.MailView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** Conversations on the real database: what a message's References and In-Reply-To make of it. */
@RunWith(AndroidJUnit4::class)
class ConversationsTest {

    private val log = AppLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: DeskMailDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var mail: LiveMail
    private var account = 0L

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = DeskMailDatabase.inMemory(context)
        val accounts = AccountStore(db, testBox(), log)
        store = RoomSyncStore(db)
        mail = LiveMail(db, MailSync(db, accounts, log, scope), scope, File(context.cacheDir, "conversations-" + System.nanoTime()), paceMs = 0, tellAfterMs = 0)
        account = runBlocking { db.accounts().insert(testAccount("deniz@example.org")) }
    }

    @After
    fun close() {
        endAndWait(scope)
        db.close()
    }

    private fun message(uid: Long, subject: String, id: String, inReplyTo: String = "", references: String = "", hours: Long) = RemoteMessage(
        uid = uid,
        globalId = id,
        inReplyTo = inReplyTo,
        references = references,
        subject = subject,
        from = listOf(MailAddress("Ayşe Yılmaz", "ayse@posta.example")),
        to = listOf(MailAddress("Deniz Aksoy", "deniz@example.org")),
        cc = emptyList(),
        bcc = emptyList(),
        replyTo = emptyList(),
        sentAt = Instant.parse("2026-11-10T08:00:00Z").plusSeconds(hours * 3600),
        receivedAt = Instant.parse("2026-11-10T08:00:00Z").plusSeconds(hours * 3600),
        size = 2_000,
        flags = MessageFlags(),
        attachments = 0,
    )

    @Test
    fun aConversationIsWhatAnswersItsFirstMessageWhereverItIsFiled() = runBlocking {
        val ids = store.saveFolders(
            account,
            listOf(
                RemoteFolder("INBOX", '/', emptySet(), selectable = true, role = FolderRole.Inbox),
                RemoteFolder("Sent", '/', emptySet(), selectable = true, role = FolderRole.Sent),
                RemoteFolder("Trash", '/', emptySet(), selectable = true, role = FolderRole.Trash),
            ),
        ).associate { it.path to it.id }
        store.insert(
            ids.getValue("INBOX"),
            listOf(
                message(1, "Cumartesi kahvaltı?", "<a@posta>", hours = 1),
                message(2, "Re: Cumartesi kahvaltı?", "<c@posta>", inReplyTo = "<b@deniz>", references = "<a@posta> <b@deniz>", hours = 3),
                message(3, "Başka bir şey", "<x@posta>", hours = 4),
            ),
        )
        store.insert(ids.getValue("Sent"), listOf(message(4, "Re: Cumartesi kahvaltı?", "<b@deniz>", inReplyTo = "<a@posta>", references = "<a@posta>", hours = 2)))
        // what went to the bin is out of the conversation
        store.insert(ids.getValue("Trash"), listOf(message(5, "Re: Cumartesi kahvaltı?", "<d@posta>", inReplyTo = "<c@posta>", references = "<a@posta> <b@deniz> <c@posta>", hours = 5)))

        val inbox = withTimeout(15_000) { mail.observe(MailView.Inbox).first { it.size == 3 } }
        val newest = inbox.first { it.subject == "Re: Cumartesi kahvaltı?" }
        val first = inbox.first { it.subject == "Cumartesi kahvaltı?" }
        // each knows the message the conversation began with
        assertEquals("$account <a@posta>", newest.thread)
        assertEquals(newest.thread, first.thread)

        val conversation = withTimeout(15_000) { mail.observeThread(newest).first { it.size == 3 } }
        assertEquals(listOf("Cumartesi kahvaltı?", "Re: Cumartesi kahvaltı?", "Re: Cumartesi kahvaltı?"), conversation.map { it.subject })
        // the user's own answer from Sent among them, in its place
        assertEquals(ids.getValue("Sent"), conversation[1].folderId)
        // a message of its own is a conversation of its own
        val other = inbox.first { it.subject == "Başka bir şey" }
        assertEquals(listOf(other.id), withTimeout(15_000) { mail.observeThread(other).first() }.map { it.id })
    }
}
