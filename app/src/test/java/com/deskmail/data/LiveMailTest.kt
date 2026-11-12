package com.deskmail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.imap.ImapConnection
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailException
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.MessageEntity
import com.deskmail.mail.AccountState
import com.deskmail.mail.FetchedFile
import com.deskmail.mail.MailAttachment
import com.deskmail.mail.MailContent
import com.deskmail.mail.MailUndo
import com.deskmail.mail.MailView
import jakarta.mail.Flags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Long enough that nothing is told to the server before a test says it stands. */
private const val UNDO_MS = 10 * 60 * 1000L

/** What the screens see of real mail: the database watched, and the server asked when somebody looks. */
@RunWith(AndroidJUnit4::class)
class LiveMailTest {

    @get:Rule
    val server = TestImapServer()

    private val now: Instant = Instant.parse("2026-11-12T09:30:00Z")
    private val log = AppLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connections = AtomicInteger()
    private val clock = AtomicLong(1_000_000)
    private lateinit var db: DeskMailDatabase
    private lateinit var accounts: AccountStore
    private lateinit var sync: MailSync
    private lateinit var mail: LiveMail
    private lateinit var files: File

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = DeskMailDatabase.inMemory(context)
        accounts = AccountStore(db, testBox(), log)
        sync = MailSync(
            db, accounts, log, scope,
            connect = { address, credentials ->
                connections.incrementAndGet()
                ImapConnection.open(address, credentials, log)
            },
            clock = { now },
        )
        files = File(context.cacheDir, "files-" + System.nanoTime())
        mail = LiveMail(db, sync, scope, files, now = { clock.get() }, paceMs = 0, undoMs = UNDO_MS, tellAfterMs = 0)
    }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun hoursAgo(hours: Long): Instant = now.minus(Duration.ofHours(hours))

    private suspend fun account(): Long = accounts.add(TestImapServer.ADDRESS, "Posta", "", server.settings, TestImapServer.PASSWORD)

    @Test
    fun theScreensSeeTheAccountsTheFoldersAndTheMail() = runBlocking {
        server.createFolder("Sent")
        server.createFolder("Projeler")
        server.createFolder("Projeler.2026")
        server.deliver("Cumartesi kahvaltı?", "Selam Deniz, kahvaltıya ne dersin?", hoursAgo(3))
        server.deliver("Okunmuş", "Bunu okudum.", hoursAgo(1), seen = true)
        server.deliver("Re: Cumartesi kahvaltı?", "Olur, geliyorum.", hoursAgo(2), folder = "Sent", seen = true)
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id, also = setOf("Sent")))

        eventually("the accounts and folders are read") { mail.loaded && mail.folders.size == 4 }
        assertEquals(listOf("Posta"), mail.accounts.map { it.name })
        assertEquals(AccountState.Ok, mail.accounts[0].state)
        assertEquals(TestImapServer.LOCALHOST, mail.accounts[0].server)
        // the inbox first, then the folders with a role, then the user's own by name
        assertEquals(listOf(FolderRole.Inbox, FolderRole.Sent, FolderRole.Other, FolderRole.Other), mail.folders.map { it.role })
        assertEquals(listOf(listOf("Projeler"), listOf("Projeler", "2026")), mail.folders.drop(2).map { it.path })
        val inbox = mail.folders[0]
        assertEquals(1, inbox.unread)
        assertEquals(2, inbox.total)
        assertEquals(inbox, mail.folder(inbox.id))

        val listed = mail.observe(MailView.Inbox).first()
        assertEquals(listOf("Okunmuş", "Cumartesi kahvaltı?"), listed.map { it.subject })
        val breakfast = listed[1]
        assertEquals("Ayşe Yılmaz", breakfast.fromName)
        assertEquals("ayse@example.org", breakfast.fromAddress)
        assertEquals("Deniz Aksoy", breakfast.to)
        assertEquals("Selam Deniz, kahvaltıya ne dersin?", breakfast.preview)
        assertTrue(breakfast.unread)
        assertFalse(breakfast.outgoing)
        assertEquals(hoursAgo(3), breakfast.sentAt)
        assertFalse(listed[0].unread)
        // what a view listed is there to draw at once the next time
        assertEquals(listed, mail.cached(MailView.Inbox))
        assertNull(mail.cached(MailView.Starred))
        assertEquals(breakfast, mail.cachedMessage(breakfast.id))
        assertEquals(breakfast, mail.observeMessage(breakfast.id).first())
        assertNull(mail.observeMessage(404).first())

        assertEquals(listOf("Cumartesi kahvaltı?"), mail.observe(MailView.Unread).first().map { it.subject })
        // what the user wrote is listed by its recipient
        val sent = mail.observe(MailView.Folder(mail.folders[1].id)).first()
        assertEquals(listOf("Re: Cumartesi kahvaltı?"), sent.map { it.subject })
        assertTrue(sent[0].outgoing)
        eventually("the newest messages are read") { mail.recent.size == 3 }
    }

    @Test
    fun aMessagesTextIsFetchedWhenSomebodyLooks() = runBlocking {
        val count = MailSync.FIRST_BODIES + 2
        repeat(count) { server.deliver("ileti ${it + 1}", "Metin ${it + 1}.", hoursAgo((count - it).toLong())) }
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        val inbox = db.folders().ofAccount(id).first { it.path == "INBOX" }.id

        val newest = db.messages().observeFolder(inbox).first().first()
        val ready = withTimeout(15_000) { mail.observeContent(newest.id).first { it is MailContent.Ready } } as MailContent.Ready
        assertEquals("Metin $count.", ready.text)
        assertNull(ready.html)
        assertTrue(ready.attachments.isEmpty())

        // one whose text nobody has fetched yet (the app's own background run may get to it first: either way it arrives)
        val oldest = db.messages().observeFolder(inbox).first().last()
        val fetched = withTimeout(15_000) { mail.observeContent(oldest.id).first { it is MailContent.Ready } } as MailContent.Ready
        assertEquals("Metin 1.", fetched.text)
        assertEquals(MessageEntity.BODY_KEPT, db.messages().byId(oldest.id)!!.bodyState)

        assertEquals(MailContent.Unavailable, mail.observeContent(404).first())
    }

    @Test
    fun aTextThatCannotBeFetchedSaysSo() = runBlocking {
        server.deliver("ilk", "Birinci ileti.", hoursAgo(2))
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        val inbox = db.folders().ofAccount(id).first { it.path == "INBOX" }.id
        val message = db.messages().observeFolder(inbox).first().single()
        // as if its text had not been fetched yet, and the server is gone
        db.messages().setBodyState(message.id, MessageEntity.BODY_MISSING)
        server.stop()

        val failed = withTimeout(15_000) { mail.observeContent(message.id).first { it is MailContent.Failed } } as MailContent.Failed
        assertEquals(MailException.Kind.Network, failed.kind)
        eventually("the account says so too") { mail.accounts.firstOrNull()?.state == AccountState.Unreachable }

        // asked again, it fails again: still said, not waited for
        mail.retryContent(message.id)
        val again = withTimeout(15_000) { mail.observeContent(message.id).first { it is MailContent.Failed } }
        assertEquals(failed, again)
    }

    @Test
    fun anAccountThatWasJustAddedIsSyncedOnce() = runBlocking {
        server.deliver("ilk", "Birinci ileti.", hoursAgo(2))
        val id = account()
        eventually("the account is read") { mail.accounts.size == 1 }

        // what adding an account asks for, and then what the inbox asks for when it shows the new account
        mail.sync(id)
        mail.viewShown(MailView.Inbox)

        eventually("its mail is here") { runBlocking { db.messages().count() } == 1 && !mail.syncing }
        Thread.sleep(300)
        assertEquals(1, connections.get())
    }

    @Test
    fun aViewThatComesToTheFrontIsSyncedUnlessItJustWas() = runBlocking {
        server.createFolder("Projeler")
        server.deliver("ilk", "Birinci ileti.", hoursAgo(2))
        server.deliver("plan", "Plan ekte.", hoursAgo(2), folder = "Projeler")
        val id = account()
        eventually("the account is read") { mail.accounts.size == 1 }

        // the inbox comes to the front: the account is synced
        mail.viewShown(MailView.Inbox)
        eventually("the first sync") { connections.get() == 1 && !mail.syncing && runBlocking { db.messages().count() } == 1 }
        // again a moment later: not synced again
        mail.viewShown(MailView.Inbox)
        mail.viewShown(MailView.Unread)
        Thread.sleep(300)
        assertEquals(1, connections.get())

        // a folder that was not opened before is synced when it is
        eventually("the folders are read") { mail.folders.size == 2 }
        val projects = mail.folders.first { it.role == FolderRole.Other }
        mail.viewShown(MailView.Folder(projects.id))
        eventually("the folder's sync") { connections.get() == 2 && runBlocking { db.messages().count() } == 2 }
        mail.viewShown(MailView.Folder(projects.id))
        Thread.sleep(300)
        assertEquals(2, connections.get())

        // later it is
        clock.addAndGet(LiveMail.STALE_MS + 1)
        mail.viewShown(MailView.Inbox)
        eventually("the later sync") { connections.get() == 3 }

        // and whenever the user says so, with the folder in front
        eventually("the sync is over") { !mail.syncing }
        server.deliver("yeni plan", "Yeni plan ekte.", hoursAgo(1), folder = "Projeler")
        eventually("the refresh") {
            // asked again while one is under way, nothing more happens
            mail.refresh(MailView.Folder(projects.id))
            runBlocking { db.messages().count() } == 3
        }
        assertTrue(connections.get() >= 4)
    }

    // ------------------------------------------------------------------ what the user does to a message

    private suspend fun folderId(accountId: Long, path: String): Long = db.folders().ofAccount(accountId).first { it.path == path }.id

    private suspend fun stored(messageId: Long) = db.messages().byId(messageId)

    @Test
    fun readingAndStarringShowAtOnceAndAreToldToTheServer() = runBlocking {
        val uid = server.deliver("Cumartesi kahvaltı?", "Selam Deniz.", hoursAgo(3))
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        val message = mail.observe(MailView.Inbox).first().single()
        assertTrue(message.unread)

        mail.markRead(message.id)
        mail.toggleStarred(message.id)

        eventually("the device shows it") { runBlocking { stored(message.id)!!.let { it.seen && it.flagged } } }
        eventually("the server is told") { server.has(uid, Flags.Flag.SEEN) && server.has(uid, Flags.Flag.FLAGGED) }
        eventually("nothing is left waiting") { runBlocking { db.pending().all().isEmpty() } }
        eventually("the folder's count follows") { mail.folders.firstOrNull()?.unread == 0 }

        // and back again
        mail.toggleUnread(message.id)
        mail.toggleStarred(message.id)
        eventually("the server is told again") { !server.has(uid, Flags.Flag.SEEN) && !server.has(uid, Flags.Flag.FLAGGED) }
        assertTrue(mail.observe(MailView.Inbox).first().single().unread)
    }

    @Test
    fun anArchivedMessageIsTakenBackBeforeTheServerHearsOfIt() = runBlocking {
        server.createFolder("Archive")
        val uid = server.deliver("Plan", "Ekte.", hoursAgo(3))
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        eventually("the folders are read") { mail.folders.size == 2 }
        val message = mail.observe(MailView.Inbox).first().single()

        val undo = mail.archive(listOf(message.id))!!

        assertEquals(MailUndo.Action.Archived, undo.action)
        assertEquals(listOf(message.id), undo.messages)
        // off the list at once, while the server knows nothing yet
        assertTrue(mail.observe(MailView.Inbox).first().isEmpty())
        assertEquals(listOf(uid), server.uids())

        assertEquals(1, mail.undo(undo))
        assertEquals(listOf("Plan"), mail.observe(MailView.Inbox).first().map { it.subject })
        assertTrue(db.pending().all().isEmpty())
        assertEquals(listOf(uid), server.uids())
        // a message that is not there cannot be archived
        assertNull(mail.archive(listOf(404)))
    }

    @Test
    fun whatStandsIsToldToTheServerAndCanStillBeBroughtBack() = runBlocking {
        server.createFolder("Archive")
        server.deliver("Plan", "Planın üçüncü sürümü ekte.", hoursAgo(3))
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        eventually("the folders are read") { mail.folders.size == 2 }
        val inbox = folderId(id, "INBOX")
        val archive = folderId(id, "Archive")
        val message = mail.observe(MailView.Inbox).first().single()

        val undo = mail.archive(listOf(message.id))!!
        mail.commit(undo)

        eventually("the server moved it") { server.uids().isEmpty() && server.uids("Archive").size == 1 }
        eventually("the device's copy went along") { runBlocking { stored(message.id)?.folderId == archive } }
        // with its text: nothing is fetched again
        val moved = stored(message.id)!!
        assertEquals(server.uids("Archive").single(), moved.uid)
        assertEquals(MessageEntity.BODY_KEPT, moved.bodyState)
        assertEquals(listOf("Plan"), mail.observe(MailView.Folder(archive)).first().map { it.subject })
        eventually("nothing is left waiting") { runBlocking { db.pending().all().isEmpty() } }

        // taken back after the server was told: it goes back the way it came
        assertEquals(1, mail.undo(undo))
        eventually("the server moved it back") { server.uids("Archive").isEmpty() && server.uids().size == 1 }
        eventually("the device's copy is back in the inbox") { runBlocking { stored(message.id)?.folderId == inbox } }
        assertEquals(listOf("Plan"), mail.observe(MailView.Inbox).first().map { it.subject })
    }

    @Test
    fun deletingMovesToTheTrashAndFromThereForGood() = runBlocking {
        server.createFolder("Trash")
        server.deliver("Reklam", "Hemen al.", hoursAgo(3))
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        eventually("the folders are read") { mail.folders.size == 2 }
        val trash = folderId(id, "Trash")
        val message = mail.observe(MailView.Inbox).first().single()

        val first = mail.delete(listOf(message.id))!!
        assertEquals(MailUndo.Action.Deleted, first.action)
        mail.commit(first)
        eventually("it is in the server's trash") { server.uids().isEmpty() && server.uids("Trash").size == 1 }
        eventually("and in the device's") { runBlocking { stored(message.id)?.folderId == trash } }
        eventually("nothing is left waiting") { runBlocking { db.pending().all().isEmpty() } }

        // deleted from the trash, it is gone for good
        val second = mail.delete(listOf(message.id))!!
        assertEquals(MailUndo.Action.DeletedForGood, second.action)
        assertTrue(mail.observe(MailView.Folder(trash)).first().isEmpty())
        // still on the server for as long as it can be taken back
        assertEquals(1, server.uids("Trash").size)
        mail.commit(second)
        eventually("it is gone from the server") { server.uids("Trash").isEmpty() }
        eventually("and from the device") { runBlocking { stored(message.id) == null } }
    }

    @Test
    fun aMoveGoesToTheFolderThatWasChosen() = runBlocking {
        server.createFolder("Projeler")
        server.deliver("Plan", "Ekte.", hoursAgo(3))
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        eventually("the folders are read") { mail.folders.size == 2 }
        val projects = folderId(id, "Projeler")
        val message = mail.observe(MailView.Inbox).first().single()
        assertEquals(listOf(projects), mail.moveTargets(message).map { it.id })

        val undo = mail.move(listOf(message.id), projects)!!
        assertEquals(MailUndo.Action.Moved, undo.action)
        assertEquals(projects, undo.folder)
        mail.commit(undo)

        eventually("the server moved it") { server.uids().isEmpty() && server.uids("Projeler").size == 1 }
        eventually("the device's copy went along") { runBlocking { stored(message.id)?.folderId == projects } }
        // where it is already, there is nothing to do
        assertNull(mail.move(listOf(message.id), projects))
    }

    @Test
    fun whatWasDoneWhileTheServerWasAwayIsToldBeforeTheNextSyncReads() = runBlocking {
        val uid = server.deliver("Cumartesi kahvaltı?", "Selam Deniz.", hoursAgo(3))
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        val message = mail.observe(MailView.Inbox).first().single()
        // done on the device as if no server could be reached: only queued
        db.pending().mark(message.id, seen = true, flagged = true, now = clock.get())
        assertFalse(server.has(uid, Flags.Flag.SEEN))

        assertEquals(MailSync.Outcome.Done, sync.account(id))

        assertTrue(server.has(uid, Flags.Flag.SEEN) && server.has(uid, Flags.Flag.FLAGGED))
        assertTrue(db.pending().all().isEmpty())
        // the sync read the marks back as the user left them
        assertTrue(stored(message.id)!!.let { it.seen && it.flagged })
        assertEquals(0, db.folders().byId(message.folderId)!!.unread)
    }

    @Test
    fun aFileIsFetchedOnceAndKeptOnTheDevice() = runBlocking {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        server.deliverWithFile("Rapor", "Ekte.", "rapor.pdf", "application/pdf", bytes, hoursAgo(2))
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        val message = mail.observe(MailView.Inbox).first().single()
        val content = withTimeout(15_000) { mail.observeContent(message.id).first { it is MailContent.Ready } } as MailContent.Ready
        val attachment = content.attachments.single()
        assertEquals("rapor.pdf", attachment.name)
        assertEquals("application/pdf", attachment.mimeType)
        assertTrue(attachment.id != 0L)

        val before = connections.get()
        val fetched = mail.fetch(attachment) as FetchedFile.Ready

        assertArrayEquals(bytes, fetched.file.readBytes())
        assertEquals("rapor.pdf", fetched.file.name)
        assertTrue(fetched.file.path.startsWith(files.path))
        assertEquals(before + 1, connections.get())
        // kept: no second trip to the server
        assertEquals(fetched, mail.fetch(attachment))
        assertEquals(before + 1, connections.get())
        // a file nobody knows, and one of made-up mail
        assertEquals(FetchedFile.Gone, mail.fetch(MailAttachment("yok.pdf", 1, id = 404)))
        assertEquals(FetchedFile.Gone, mail.fetch(MailAttachment("yok.pdf", 1)))
        // fetching a file is not reading the message
        assertTrue(mail.observe(MailView.Inbox).first().single().unread)
    }

    @Test
    fun aFilesNameCannotLeadOutOfItsDirectory() {
        assertEquals("rapor.pdf", safeFileName("rapor.pdf"))
        // no separator is left to climb with, and no leading dot to hide behind
        assertEquals("_.._etc_passwd", safeFileName("../../etc/passwd"))
        assertEquals("a_b_c.txt", safeFileName("a:b\\c.txt"))
        assertEquals("ek", safeFileName(""))
        assertEquals("ek", safeFileName(" .. "))
        assertEquals("satır_sonu", safeFileName("satır\nsonu"))
        val long = safeFileName("a".repeat(300) + ".pdf")
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".pdf"))
    }
}
