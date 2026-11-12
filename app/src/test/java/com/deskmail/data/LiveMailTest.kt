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
import com.deskmail.mail.MailContent
import com.deskmail.mail.MailView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

    @Before
    fun open() {
        db = DeskMailDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        accounts = AccountStore(db, testBox(), log)
        sync = MailSync(
            db, accounts, log, scope,
            connect = { address, credentials ->
                connections.incrementAndGet()
                ImapConnection.open(address, credentials, log)
            },
            clock = { now },
        )
        mail = LiveMail(db, sync, scope, now = { clock.get() }, paceMs = 0)
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
        // this pre-release only reads
        assertFalse(mail.canMark)
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
}
