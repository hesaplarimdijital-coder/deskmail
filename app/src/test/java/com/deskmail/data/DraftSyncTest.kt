package com.deskmail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.FolderEntity
import com.deskmail.mail.DraftKind
import jakarta.mail.Flags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/**
 * What is being written is kept among the drafts on its server too, against a mail server inside
 * the test: a draft's copy is put there, replaced when the draft is written on, and removed when
 * the message is sent or thrown away; and a draft that lies there is taken up to be written on.
 */
@RunWith(AndroidJUnit4::class)
class DraftSyncTest {

    @get:Rule(order = 0)
    val guard = StuckGuard()

    @get:Rule(order = 1)
    val server = TestImapServer()

    private val start: Instant = Instant.parse("2026-11-13T09:30:00Z")
    private val log = AppLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = AtomicLong(start.toEpochMilli())

    /** When the drafts were asked to be looked at, as delays from the moment of asking. */
    private val asked = CopyOnWriteArrayList<Long>()
    private lateinit var db: DeskMailDatabase
    private lateinit var accounts: AccountStore
    private lateinit var sync: MailSync
    private lateinit var mail: LiveMail
    private lateinit var sender: MailSend
    private lateinit var drafts: DraftSync
    private lateinit var composer: LiveComposer

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
        val root = File(context.cacheDir, "drafts-" + System.nanoTime())
        val files = File(root, "outbox")
        mail = LiveMail(db, sync, scope, File(root, "files"), now = { clock.get() }, paceMs = 0, tellAfterMs = 0)
        sender = MailSend(
            db, accounts, sync, log,
            work = File(root, "outgoing"), files = files, now = { clock.get() }, zone = { ZoneOffset.UTC },
            onDraftCopies = { asked += 0L },
        )
        drafts = DraftSync(db, sync, log, work = File(root, "outgoing"), now = { clock.get() }, zone = { ZoneOffset.UTC })
        composer = LiveComposer(
            db = db,
            sync = sync,
            scope = scope,
            files = files,
            scheduler = { },
            fetchFile = { mail.fetch(it) },
            words = { words },
            recover = { sender.recover() },
            log = log,
            now = { clock.get() },
            undoMs = { UNDO_MS },
            saveDrafts = { delay -> asked += delay },
        )
    }

    @After
    fun close() {
        endAndWait(scope)
        db.close()
    }

    /** An account whose folders are known on the device, a Drafts folder among them unless [withDrafts] says otherwise. */
    private suspend fun account(withDrafts: Boolean = true): Long {
        if (withDrafts) server.createFolder(DRAFTS)
        val id = accounts.add(TestImapServer.ADDRESS, "Posta", "", server.settings, TestImapServer.PASSWORD)
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        return id
    }

    private suspend fun draftsFolder(account: Long): FolderEntity = db.folders().ofAccount(account).first { it.path == DRAFTS }

    /** Writes something into the draft, a second later than whatever was written before. */
    private suspend fun write(id: Long, account: Long, subject: String = "Yarım kalan", body: String = "Devamı gelecek.\n") {
        clock.addAndGet(1_000)
        composer.save(id, account, "Ayşe Yılmaz <ayse@example.org>", "", "gizli@example.org", subject, body)
    }

    @Test
    fun whatIsWrittenIsKeptAmongTheDraftsOnTheServer() = runBlocking {
        val account = account()
        val id = composer.start()!!
        // nothing written into it yet: nothing of it is worth a place on the server
        assertFalse(drafts.run())
        assertTrue(server.uids(DRAFTS).isEmpty())
        assertTrue(asked.isEmpty())

        write(id, account)
        // asked for a moment after the typing, not with every word
        assertEquals(listOf(DraftSync.SAVE_AFTER_MS), asked.toList())
        assertFalse(drafts.run())

        val uid = server.uids(DRAFTS).single()
        val copy = server.message(uid, DRAFTS)!!
        assertEquals("Yarım kalan", copy.subject)
        assertTrue(copy.sentText().startsWith("Devamı gelecek."))
        assertTrue(server.has(uid, Flags.Flag.DRAFT, DRAFTS))
        assertTrue(server.has(uid, Flags.Flag.SEEN, DRAFTS))
        // with its hidden recipient: the writer finds it again where he goes on writing
        assertEquals("gizli@example.org", copy.getHeader("Bcc")?.single())
        val row = db.outbox().byId(id)!!
        assertEquals(uid, row.draftUid)
        assertEquals(draftsFolder(account).id, row.draftFolderId)
        // the Drafts folder on the device shows it
        assertEquals(listOf("Yarım kalan"), db.messages().observeFolder(row.draftFolderId).first().map { it.subject })

        // nothing more to do until it is written on again
        assertFalse(drafts.run())
        assertEquals(listOf(uid), server.uids(DRAFTS))
    }

    @Test
    fun aDraftThatIsWrittenOnHasItsCopyReplaced() = runBlocking {
        val account = account()
        val id = composer.start()!!
        write(id, account)
        assertFalse(drafts.run())
        val first = server.uids(DRAFTS).single()

        write(id, account, subject = "Bitti sayılır", body = "Devamı geldi.\n")
        assertFalse(drafts.run())

        // one copy, the new one
        val second = server.uids(DRAFTS).single()
        assertNotEquals(first, second)
        assertEquals("Bitti sayılır", server.message(second, DRAFTS)!!.subject)
        assertEquals(second, db.outbox().byId(id)!!.draftUid)
        assertEquals(listOf("Bitti sayılır"), db.messages().observeFolder(draftsFolder(account).id).first().map { it.subject })
    }

    @Test
    fun aFileThatComesPutsTheDraftOnTheServerAnewWithIt() = runBlocking {
        val account = account()
        val id = composer.start()!!
        write(id, account)
        assertFalse(drafts.run())
        asked.clear()

        clock.addAndGet(1_000)
        assertTrue(composer.attach(id, "not.txt", "text/plain") { "ekteki not".toByteArray().inputStream() })

        assertEquals(listOf(DraftSync.SAVE_AFTER_MS), asked.toList())
        assertFalse(drafts.run())
        val copy = server.message(server.uids(DRAFTS).single(), DRAFTS)!!
        assertEquals(listOf("not.txt"), copy.sentFiles().map { it.first })
    }

    @Test
    fun aDraftThatIsThrownAwayLosesItsCopy() = runBlocking {
        val account = account()
        val id = composer.start()!!
        write(id, account)
        assertFalse(drafts.run())
        assertEquals(1, server.uids(DRAFTS).size)
        asked.clear()

        assertTrue(composer.discard(id))

        // at once, as far as the asking goes
        assertEquals(listOf(0L), asked.toList())
        assertEquals(1, db.outbox().removals().size)
        assertFalse(drafts.run())
        assertTrue(server.uids(DRAFTS).isEmpty())
        assertTrue(db.outbox().removals().isEmpty())
        assertTrue(db.messages().observeFolder(draftsFolder(account).id).first().isEmpty())
    }

    @Test
    fun aMessageThatIsSentLosesItsCopyAmongTheDrafts() = runBlocking {
        val account = account()
        val id = composer.start()!!
        write(id, account)
        assertFalse(drafts.run())
        assertEquals(1, server.uids(DRAFTS).size)
        asked.clear()

        composer.send(id)
        clock.addAndGet(UNDO_MS)
        sender.sendDue()

        // gone to whom it was for, and its draft is of no use any more
        assertEquals(1, server.inboxOf("ayse@example.org").size)
        assertEquals(listOf(0L), asked.toList())
        assertFalse(drafts.run())
        assertTrue(server.uids(DRAFTS).isEmpty())
    }

    @Test
    fun withTheServerOutOfReachTheDraftWaitsOnTheDevice() = runBlocking {
        val account = account()
        val id = composer.start()!!
        write(id, account)

        server.stop()

        // something is left to do: asked for again in a while
        assertTrue(drafts.run())
        assertEquals(listOf(id), db.outbox().draftsToSave().map { it.id })
        assertEquals(0L, db.outbox().byId(id)!!.draftUid)
    }

    @Test
    fun anAccountWithoutADraftsFolderKeepsItsDraftsOnTheDevice() = runBlocking {
        val account = account(withDrafts = false)
        val id = composer.start()!!
        write(id, account)

        assertFalse(drafts.run())

        // not looked at again until it is written on
        assertTrue(db.outbox().draftsToSave().isEmpty())
        assertEquals(0L, db.outbox().byId(id)!!.draftUid)
    }

    /** A draft that lies in the Drafts folder, written on another device, is taken up; its copy there becomes this message's. */
    @Test
    fun aDraftThatWasWrittenElsewhereIsTakenUpAndItsCopyReplaced() = runBlocking {
        val account = account()
        val uid = server.deliver("Başka cihazdan", "Orada başladım.", start.minus(Duration.ofHours(1)), folder = DRAFTS)
        val folder = draftsFolder(account)
        assertEquals(MailSync.Outcome.Done, sync.folder(folder.id))
        val source = db.messages().observeFolder(folder.id).first().single()

        val id = composer.start(DraftKind.Edit, sourceId = source.id)!!

        val row = db.outbox().byId(id)!!
        assertEquals("Başka cihazdan", row.subject)
        assertTrue(row.body, row.body.startsWith("Orada başladım."))
        assertEquals(listOf(folder.id, uid), listOf(row.draftFolderId, row.draftUid))
        // as it lies there: nothing to put on the server
        assertTrue(db.outbox().draftsToSave().isEmpty())
        assertTrue(asked.isEmpty())
        // taken up once more, it is the same message, not a second one
        assertEquals(id, composer.start(DraftKind.Edit, sourceId = source.id))

        clock.addAndGet(1_000)
        composer.save(id, account, row.toText, "", "", "Başka cihazdan", "Orada başladım.\nBurada bitirdim.\n")
        assertFalse(drafts.run())

        // written on here, the copy on the server is this device's: one, and the new one
        val now = server.uids(DRAFTS).single()
        assertNotEquals(uid, now)
        assertTrue(server.message(now, DRAFTS)!!.sentText().contains("Burada bitirdim."))
    }

    private companion object {
        const val DRAFTS = "Drafts"
        const val UNDO_MS = 10_000L
    }
}
