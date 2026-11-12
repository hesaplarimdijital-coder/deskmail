package com.deskmail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.core.provider.ProviderTable
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.OutboxEntity
import com.deskmail.mail.Draft
import com.deskmail.mail.DraftKind
import com.deskmail.mail.DraftState
import com.deskmail.mail.SendCheck
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** How long "Send" can be taken back in these tests. */
private const val UNDO_MS = 5_000L

/**
 * Writing a message and sending it, against a mail server inside the test: what is typed is kept,
 * handed over, waited for and sent; and what follows from its having been sent.
 */
@RunWith(AndroidJUnit4::class)
class MailSendTest {

    @get:Rule
    val server = TestImapServer()

    private val start: Instant = Instant.parse("2026-11-12T09:30:00Z")
    private val log = AppLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The time everything here goes by; a test moves it on. */
    private val clock = AtomicLong(start.toEpochMilli())

    /** When the outbox was asked to be looked at, as delays from the moment of asking. */
    private val scheduled = CopyOnWriteArrayList<Long>()
    private lateinit var db: DeskMailDatabase
    private lateinit var accounts: AccountStore
    private lateinit var sync: MailSync
    private lateinit var mail: LiveMail
    private lateinit var sender: MailSend
    private lateinit var composer: LiveComposer
    private lateinit var files: File

    private val words = ComposeWords(
        wrote = { date, who -> "$date tarihinde $who yazdı:" },
        forwarded = "---------- İletilen ileti ----------",
        from = "Kimden",
        sent = "Tarih",
        subject = "Konu",
        to = "Kime",
        cc = "Bilgi",
        date = { it.toString() },
    )

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = DeskMailDatabase.inMemory(context)
        accounts = AccountStore(db, testBox(), log)
        sync = MailSync(db, accounts, log, scope, clock = { Instant.ofEpochMilli(clock.get()) })
        val root = File(context.cacheDir, "send-" + System.nanoTime())
        files = File(root, "outbox")
        mail = LiveMail(db, sync, scope, File(root, "files"), now = { clock.get() }, paceMs = 0, tellAfterMs = 0)
        sender = MailSend(db, accounts, sync, log, work = File(root, "outgoing"), files = files, now = { clock.get() }, zone = { ZoneOffset.UTC })
        composer = LiveComposer(
            db = db,
            sync = sync,
            scope = scope,
            files = files,
            scheduler = { delay -> scheduled += delay },
            fetchFile = { mail.fetch(it) },
            words = { words },
            recover = { sender.recover() },
            log = log,
            now = { clock.get() },
            undoMs = { UNDO_MS },
        )
    }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun hoursAgo(hours: Long): Instant = start.minus(Duration.ofHours(hours))

    private suspend fun account(provider: String = ""): Long = accounts.add(TestImapServer.ADDRESS, "Posta", provider, server.settings, TestImapServer.PASSWORD)

    private suspend fun draft(id: Long): Draft? = composer.observe(id).first()

    /** Lets the time to take a message back pass, and has the outbox looked at. */
    private suspend fun sendWhatIsDue(): SendRound {
        clock.addAndGet(UNDO_MS)
        return sender.sendDue()
    }

    @Test
    fun aMessageWaitsOutItsTimeAndIsThenSent() = runBlocking {
        val account = account()
        accounts.saveWriting(account, "Deniz Aksoy", "")
        val id = composer.start()!!
        composer.save(id, account, "Ayşe Yılmaz <ayse@example.org>, elif@example.org", "", "mehmet@example.org", "Cumartesi kahvaltı?", "Selam Ayşe,\n\nOlur, geliyorum.\n")

        assertEquals(SendCheck.Queued(UNDO_MS), composer.send(id))
        assertEquals(DraftState.Waiting, draft(id)!!.state)
        // the outbox is to be looked at when the message's time is up
        assertEquals(UNDO_MS, scheduled.last())

        // too early: it can still be taken back
        assertEquals(SendRound(unreachable = false, nextInMs = UNDO_MS), sender.sendDue())
        assertTrue(server.inboxOf("ayse@example.org").isEmpty())

        assertEquals(SendRound(unreachable = false, nextInMs = null), sendWhatIsDue())

        // everybody got it, the hidden recipient among them, and nobody reads who that was
        val copy = server.inboxOf("ayse@example.org").single()
        assertEquals(1, server.inboxOf("elif@example.org").size)
        assertEquals(1, server.inboxOf("mehmet@example.org").size)
        assertEquals("Cumartesi kahvaltı?", copy.subject)
        val from = copy.from.single() as InternetAddress
        assertEquals("Deniz Aksoy", from.personal)
        assertEquals(TestImapServer.ADDRESS, from.address)
        assertEquals(listOf("ayse@example.org", "elif@example.org"), copy.getRecipients(Message.RecipientType.TO).map { (it as InternetAddress).address })
        assertNull(copy.getHeader("Bcc", null))
        assertEquals("Selam Ayşe,\n\nOlur, geliyorum.", copy.sentText().replace("\r\n", "\n").trimEnd())
        // sent is sent: it is no longer the outbox's
        assertNull(draft(id))
        assertNull(db.outbox().byId(id))
    }

    @Test
    fun aMessageThatIsTakenBackIsNotSent() = runBlocking {
        val account = account()
        val id = composer.start()!!
        composer.save(id, account, "ayse@example.org", "", "", "Vazgeçtim", "Bunu göndermeyeceğim.")
        composer.send(id)

        assertTrue(composer.unsend(id))
        assertEquals(SendRound(unreachable = false, nextInMs = null), sendWhatIsDue())

        assertTrue(server.inboxOf("ayse@example.org").isEmpty())
        val kept = draft(id)!!
        assertEquals(DraftState.Writing, kept.state)
        assertEquals("Vazgeçtim", kept.subject)
        // it is being written again: there is nothing to take back a second time
        assertFalse(composer.unsend(id))
    }

    @Test
    fun aMessageThatIsToGoNowDoesNotWait() = runBlocking {
        val account = account()
        val id = composer.start()!!
        composer.save(id, account, "ayse@example.org", "", "", "Hemen", "Bekleme.")
        composer.send(id)

        composer.sendNow(id)
        eventually("the outbox is asked to be looked at now") { scheduled.lastOrNull() == 0L }
        // no time has passed
        sender.sendDue()

        assertEquals("Hemen", server.inboxOf("ayse@example.org").single().subject)
    }

    @Test
    fun whoItGoesToIsCheckedBeforeAnythingIsHandedOver() = runBlocking {
        val account = account()
        val id = composer.start()!!
        composer.save(id, account, "", "", "", "Alıcısız", "Kime?")
        assertEquals(SendCheck.NoRecipients, composer.send(id))

        composer.save(id, account, "ayse@, Elif <elif@example.org>", "", "", "Alıcısız", "Kime?")
        assertEquals(SendCheck.BadAddresses(listOf("ayse@")), composer.send(id))

        assertEquals(DraftState.Writing, draft(id)!!.state)
        assertTrue(scheduled.isEmpty())
        assertEquals(SendCheck.Gone, composer.send(id + 100))
    }

    @Test
    fun whatIsTypedAfterAMessageWasHandedOverDoesNotReachIt() = runBlocking {
        val account = account()
        val id = composer.start()!!
        composer.save(id, account, "ayse@example.org", "", "", "İlk hali", "Böyle gidecek.")
        composer.send(id)

        // a screen on its way out keeps what it has, a moment too late
        composer.save(id, account, "ayse@example.org", "", "", "Sonradan", "Bu gitmemeli.")

        val waiting = draft(id)!!
        assertEquals(DraftState.Waiting, waiting.state)
        assertEquals("İlk hali", waiting.subject)
        sendWhatIsDue()
        assertEquals("İlk hali", server.inboxOf("ayse@example.org").single().subject)
    }

    @Test
    fun theSentMessageIsFiledUnderSentWithItsHiddenRecipients() = runBlocking {
        server.createFolder("Sent")
        val account = account()
        assertEquals(MailSync.Outcome.Done, sync.account(account))
        val id = composer.start()!!
        composer.save(id, account, "ayse@example.org", "", "mehmet@example.org", "Rapor", "Ekte değil, burada.")
        composer.send(id)
        sendWhatIsDue()

        val uid = server.uids("Sent").single()
        val filed = server.message(uid, "Sent")!!
        assertEquals("Rapor", filed.subject)
        // the writer may read whom he wrote to in secret
        assertEquals("mehmet@example.org", (filed.getRecipients(Message.RecipientType.BCC).single() as InternetAddress).address)
        assertTrue(server.has(uid, Flags.Flag.SEEN, "Sent"))
        // the same message as the one that was sent
        assertEquals(server.inboxOf("ayse@example.org").single().messageID, filed.messageID)
    }

    @Test
    fun aProviderThatFilesSentMailItselfGetsNoSecondCopy() = runBlocking {
        server.createFolder("Sent")
        val account = account(provider = ProviderTable.Gmail.id)
        assertEquals(MailSync.Outcome.Done, sync.account(account))
        val id = composer.start()!!
        composer.save(id, account, "ayse@example.org", "", "", "Tek kopya", "Gmail kendisi saklar.")
        composer.send(id)
        sendWhatIsDue()

        assertEquals(1, server.inboxOf("ayse@example.org").size)
        assertTrue(server.uids("Sent").isEmpty())
    }

    @Test
    fun aReplyGoesToItsSenderQuotesTheMessageAndMarksItAnswered() = runBlocking {
        val uid = server.deliver("Cumartesi kahvaltı?", "Selam Deniz, kahvaltıya ne dersin?", hoursAgo(3))
        val account = account()
        assertEquals(MailSync.Outcome.Done, sync.account(account))
        val inbox = db.folders().ofAccount(account).first { it.path == "INBOX" }.id
        val original = db.messages().observeFolder(inbox).first().single()

        val id = composer.start(DraftKind.Reply, sourceId = original.id)!!
        val begun = draft(id)!!
        assertEquals("Ayşe Yılmaz <ayse@example.org>", begun.to)
        assertEquals("", begun.cc)
        assertEquals("Re: Cumartesi kahvaltı?", begun.subject)
        // an empty line to write in, who wrote when, and the message with every line marked
        assertEquals("\n\n${hoursAgo(3)} tarihinde Ayşe Yılmaz <ayse@example.org> yazdı:\n> Selam Deniz, kahvaltıya ne dersin?\n", begun.body)

        composer.save(id, account, begun.to, "", "", begun.subject, "Olur, geliyorum." + begun.body)
        composer.send(id)
        sendWhatIsDue()

        val reply = server.inboxOf("ayse@example.org").single()
        assertEquals("Re: Cumartesi kahvaltı?", reply.subject)
        // the thread it belongs to
        assertEquals("<test-1@example.org>", reply.getHeader("In-Reply-To", null))
        assertEquals("<test-1@example.org>", reply.getHeader("References", null))
        // the original says so, on the device and on the server
        assertTrue(db.messages().byId(original.id)!!.answered)
        assertTrue(server.has(uid, Flags.Flag.ANSWERED))
    }

    @Test
    fun aForwardedMessageTakesItsFilesAlong() = runBlocking {
        val pdf = ByteArray(5000) { (it % 251).toByte() }
        server.deliverWithFile("Proje planı", "Plan ekte.", "plan.pdf", "application/pdf", pdf, hoursAgo(2))
        val account = account()
        assertEquals(MailSync.Outcome.Done, sync.account(account))
        val inbox = db.folders().ofAccount(account).first { it.path == "INBOX" }.id
        val original = db.messages().observeFolder(inbox).first().single()

        val id = composer.start(DraftKind.Forward, sourceId = original.id)!!
        val begun = draft(id)!!
        assertEquals("", begun.to)
        assertEquals("Fwd: Proje planı", begun.subject)
        assertTrue(begun.body, begun.body.contains("---------- İletilen ileti ----------\nKimden: Ayşe Yılmaz <ayse@example.org>\n"))
        assertTrue(begun.body, begun.body.trimEnd().endsWith("Plan ekte."))
        assertEquals(listOf("plan.pdf" to 5000L), begun.files.map { it.name to it.bytes })

        composer.save(id, account, "elif@example.org", "", "", begun.subject, "Bilgine." + begun.body)
        composer.send(id)
        sendWhatIsDue()

        val carried = server.inboxOf("elif@example.org").single().sentFiles().single()
        assertEquals("plan.pdf", carried.first)
        assertArrayEquals(pdf, carried.second)
        assertTrue(db.messages().byId(original.id)!!.forwarded)
        // what was attached is not kept once the message has left
        assertFalse(File(files, id.toString()).exists())
    }

    @Test
    fun aFileIsAttachedAndTakenOutAgain() = runBlocking {
        val account = account()
        val id = composer.start()!!
        composer.save(id, account, "ayse@example.org", "", "", "Ekli", "İki dosyadan biri.")
        assertTrue(composer.attach(id, "notlar.txt", "text/plain") { "Birinci dosya".byteInputStream() })
        assertTrue(composer.attach(id, "../../gizli.txt", "") { "İkinci dosya".byteInputStream() })
        // a file that cannot be read is said, and nothing of it is kept
        assertFalse(composer.attach(id, "yok.txt", "text/plain") { null })
        assertFalse(composer.attach(id + 100, "kimsesiz.txt", "text/plain") { "x".byteInputStream() })

        val attached = draft(id)!!.files
        assertEquals(listOf("notlar.txt", "_.._gizli.txt"), attached.map { it.name })
        composer.detach(attached[1].id)
        assertEquals(listOf("notlar.txt"), draft(id)!!.files.map { it.name })

        composer.send(id)
        sendWhatIsDue()
        val sent = server.inboxOf("ayse@example.org").single()
        assertEquals(listOf("notlar.txt"), sent.sentFiles().map { it.first })
        assertEquals("Birinci dosya", String(sent.sentFiles().single().second, Charsets.UTF_8))
        assertEquals("İki dosyadan biri.", sent.sentText().trim())
    }

    @Test
    fun aServerThatCannotBeReachedLeavesTheMessageWaiting() = runBlocking {
        val account = account()
        val id = composer.start()!!
        composer.save(id, account, "ayse@example.org", "", "", "Sonra", "Sunucu gelince gider.")
        composer.send(id)
        server.stop()

        val round = sendWhatIsDue()

        // worth another try, and still the outbox's
        assertTrue(round.unreachable)
        val waiting = draft(id)!!
        assertEquals(DraftState.Waiting, waiting.state)
        assertTrue(waiting.error.isNotBlank())
        assertEquals(OutboxEntity.QUEUED, db.outbox().byId(id)!!.state)
    }

    @Test
    fun aMessageTheServerWillNotTakeIsKeptAsFailedAndCanBeTriedAgain() = runBlocking {
        val account = account()
        val id = composer.start()!!
        composer.save(id, account, "ayse@example.org", "", "", "Reddedilen", "Sunucu bunu almayacak.")
        composer.send(id)
        // as if the password had been changed elsewhere
        accounts.savePassword(account, "not-the-password")

        val round = sendWhatIsDue()

        // not a matter of waiting: its writer has to look
        assertFalse(round.unreachable)
        val failed = draft(id)!!
        assertEquals(DraftState.Failed, failed.state)
        assertTrue(failed.error.isNotBlank())
        assertTrue(server.inboxOf("ayse@example.org").isEmpty())

        // signed in again, "Try again" hands it over anew
        accounts.savePassword(account, TestImapServer.PASSWORD)
        composer.sendNow(id)
        eventually("it waits again") { runBlocking { db.outbox().byId(id)?.state } == OutboxEntity.QUEUED }
        sender.sendDue()
        assertEquals("Reddedilen", server.inboxOf("ayse@example.org").single().subject)
    }

    @Test
    fun aMessageThatWasBeingSentWhenTheAppEndedIsNotSentASecondTimeUnasked() = runBlocking {
        val account = account()
        val id = composer.start()!!
        composer.save(id, account, "ayse@example.org", "", "", "Yarım kalan", "Gitti mi, gitmedi mi?")
        composer.send(id)
        // the app ended while the message was being handed to its server
        assertEquals(1, db.outbox().claim(id, clock.get()))

        // the next run: its own sender, and the outbox as its first window reads it
        val nextSender = MailSend(db, accounts, sync, log, work = File(files.parentFile, "outgoing-next"), files = files, now = { clock.get() }, zone = { ZoneOffset.UTC })
        val next = LiveComposer(db, sync, scope, files, { delay -> scheduled += delay }, { mail.fetch(it) }, { words }, recover = { nextSender.recover() }, now = { clock.get() })
        eventually("what the last run left is seen") { next.outbox.singleOrNull()?.state == DraftState.Failed }

        assertEquals(Draft.INTERRUPTED, next.outbox.single().error)
        clock.addAndGet(UNDO_MS)
        nextSender.sendDue()
        assertTrue(server.inboxOf("ayse@example.org").isEmpty())
    }

    /** What this run of the app is handing over is its own: it is never taken for something an earlier run left. */
    @Test
    fun whatAnEarlierRunLeftIsLookedAtOnceAndBeforeAnythingIsSent() = runBlocking {
        val account = account()
        // the first look of this run: there is nothing
        assertEquals(0, sender.recover())
        val id = composer.start()!!
        composer.save(id, account, "ayse@example.org", "", "", "Yolda", "Bu çalışmanın kendi gönderdiği.")
        composer.send(id)
        // on its way now, in this run
        assertEquals(1, db.outbox().claim(id, clock.get()))

        assertEquals(0, sender.recover())

        assertEquals(OutboxEntity.SENDING, db.outbox().byId(id)!!.state)
    }

    @Test
    fun theOutboxListsWhatHasNotLeftAndForgetsWhatIsThrownAway() = runBlocking {
        val account = account()
        val first = composer.start()!!
        composer.save(first, account, "ayse@example.org", "", "", "Birinci", "Taslak.")
        clock.addAndGet(1000)
        val second = composer.start()!!
        composer.save(second, account, "elif@example.org", "", "", "İkinci", "Gönderilecek.")
        composer.attach(second, "ek.txt", "text/plain") { "ek".byteInputStream() }
        composer.send(second)

        // the latest touched first
        eventually("the outbox is read") { composer.outbox.size == 2 }
        assertEquals(listOf("İkinci" to DraftState.Waiting, "Birinci" to DraftState.Writing), composer.outbox.map { it.subject to it.state })

        assertTrue(composer.discard(second))
        assertTrue(composer.discard(first))
        eventually("the outbox is empty") { composer.outbox.isEmpty() }
        assertFalse(File(files, second.toString()).exists())
        sendWhatIsDue()
        assertTrue(server.inboxOf("elif@example.org").isEmpty())
    }

    @Test
    fun aMessageBegunWithNoAccountIsNotBegun() = runBlocking {
        assertNull(composer.start())
        val account = account()
        // nor a reply to a message that is no longer there
        assertNull(composer.start(DraftKind.Reply, sourceId = 404))
        assertNotNull(composer.start(accountId = account))
    }

    @Test
    fun theSignatureEndsANewMessageAndStandsAboveWhatIsQuoted() = runBlocking {
        server.deliver("Soru", "Yarın uygun musun?", hoursAgo(1))
        val account = account()
        accounts.saveWriting(account, "Deniz Aksoy", "Deniz Aksoy\nİstanbul")
        assertEquals(MailSync.Outcome.Done, sync.account(account))
        val inbox = db.folders().ofAccount(account).first { it.path == "INBOX" }.id
        val original = db.messages().observeFolder(inbox).first().single()

        assertEquals("\n\n-- \nDeniz Aksoy\nİstanbul\n", draft(composer.start()!!)!!.body)
        val reply = draft(composer.start(DraftKind.Reply, sourceId = original.id)!!)!!
        assertTrue(reply.body, reply.body.startsWith("\n\n-- \nDeniz Aksoy\nİstanbul\n\n\n"))
        assertTrue(reply.body, reply.body.endsWith("> Yarın uygun musun?\n"))
    }
}
