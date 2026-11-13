package com.deskmail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.MailException
import com.deskmail.core.provider.Autoconfig
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.MessageEntity
import com.deskmail.mail.AccountState
import com.deskmail.mail.SetupResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * The whole way on the device's side: a real IMAP server inside the test, the app's sync, and the
 * app's database. The work the app would do in the background by itself is held back here and
 * asked for step by step.
 */
@RunWith(AndroidJUnit4::class)
class MailSyncTest {

    @get:Rule
    val server = TestImapServer()

    private val now: Instant = Instant.parse("2026-11-12T09:30:00Z")
    private val log = AppLog()
    private val held = HeldDispatcher()
    private val background = CoroutineScope(SupervisorJob() + held)
    private lateinit var db: DeskMailDatabase
    private lateinit var accounts: AccountStore
    private lateinit var sync: MailSync
    private lateinit var setup: AccountSetup

    @Before
    fun open() {
        db = DeskMailDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        accounts = AccountStore(db, testBox(), log)
        sync = MailSync(db, accounts, log, background, clock = { now })
        setup = AccountSetup(db, accounts, sync, Autoconfig(fetch = { null }), log, background)
    }

    @After
    fun close() {
        background.cancel()
        db.close()
    }

    private fun hoursAgo(hours: Long): Instant = now.minus(Duration.ofHours(hours))

    private suspend fun account(password: String = TestImapServer.PASSWORD): Long =
        accounts.add(TestImapServer.ADDRESS, "Posta", "", server.settings, password)

    private suspend fun folderId(accountId: Long, path: String): Long = db.folders().ofAccount(accountId).first { it.path == path }.id

    @Test
    fun aFirstSyncBringsTheFoldersTheInboxAndItsTexts() = runBlocking {
        server.createFolder("Sent")
        server.createFolder("Projeler")
        server.deliver("Cumartesi kahvaltı?", "Selam Deniz,\n\nCumartesi sabahı kahvaltıya ne dersin?", hoursAgo(3))
        server.deliver(
            "Kasım bülteni",
            "Bu ay raflara 140 kitap girdi.",
            hoursAgo(2),
            html = "<p>Bu ay raflara <b>140</b> kitap girdi.</p><img src=\"https://sahaf.example/afis.png\"><script>alert(1)</script>",
            from = "Sahaf Kitabevi",
        )
        server.deliver("Okunmuş", "Bunu okudum.", hoursAgo(1), seen = true)
        server.deliver("Proje planı", "Plan ekte.", hoursAgo(5), folder = "Projeler")
        val id = account()

        assertEquals(MailSync.Outcome.Done, sync.account(id))

        assertEquals(setOf("INBOX", "Sent", "Projeler"), db.folders().ofAccount(id).map { it.path }.toSet())
        val inbox = db.folders().ofAccount(id).first { it.path == "INBOX" }
        assertEquals("Inbox", inbox.role)
        assertEquals("Sent", db.folders().ofAccount(id).first { it.path == "Sent" }.role)
        assertEquals(3, inbox.total)
        assertEquals(2, inbox.unread)
        assertEquals(now.toEpochMilli(), inbox.syncedAt)
        assertTrue(inbox.uidValidity != 0L)

        val messages = db.messages().observeFolder(inbox.id).first()
        assertEquals(listOf("Okunmuş", "Kasım bülteni", "Cumartesi kahvaltı?"), messages.map { it.subject })
        assertEquals(listOf(true, false, false), messages.map { it.seen })
        assertTrue(messages.all { it.bodyState == MessageEntity.BODY_KEPT })
        assertEquals("Selam Deniz, Cumartesi sabahı kahvaltıya ne dersin?", messages[2].preview)
        val newsletter = db.messages().observeBody(messages[1].id).first()!!
        assertEquals("Bu ay raflara 140 kitap girdi.", newsletter.text)
        // cleaned before it was kept, and known to ask for a picture from the network
        assertFalse(newsletter.html!!.contains("script"))
        assertTrue(newsletter.html.contains("<b>140</b>"))
        assertTrue(newsletter.remoteImages)
        // found by its sender, folded
        assertEquals(listOf(messages[1].id), db.messages().search("sahaf"))

        // a folder nobody opened yet: counted, not fetched
        val projects = db.folders().ofAccount(id).first { it.path == "Projeler" }
        assertEquals(1, projects.total)
        assertEquals(1, projects.unread)
        assertTrue(db.messages().observeFolder(projects.id).first().isEmpty())

        assertEquals(AccountState.Ok, accounts.byId(id)!!.accountState())
    }

    @Test
    fun aFolderIsSyncedWhenItIsAskedFor() = runBlocking {
        server.createFolder("Projeler")
        server.deliver("Proje planı", "Plan ekte.", hoursAgo(5), folder = "Projeler")
        val id = account()
        sync.account(id)
        val projects = folderId(id, "Projeler")

        assertEquals(MailSync.Outcome.Done, sync.folder(projects))

        val messages = db.messages().observeFolder(projects).first()
        assertEquals(listOf("Proje planı"), messages.map { it.subject })
        assertEquals("Plan ekte.", messages[0].preview)
        assertEquals(MailSync.Outcome.Gone, sync.folder(404))
    }

    @Test
    fun whatIsNewOrChangedElsewhereShowsAfterTheNextSync() = runBlocking {
        val first = server.deliver("ilk", "Birinci ileti.", hoursAgo(4))
        val id = account()
        sync.account(id)
        val inbox = folderId(id, "INBOX")

        server.deliver("ikinci", "Yeni geldi.", hoursAgo(1))
        server.setFlag(first, jakarta.mail.Flags.Flag.SEEN, true)
        server.setFlag(first, jakarta.mail.Flags.Flag.FLAGGED, true)
        assertEquals(MailSync.Outcome.Done, sync.account(id))

        val messages = db.messages().observeFolder(inbox).first()
        assertEquals(listOf("ikinci", "ilk"), messages.map { it.subject })
        assertTrue(messages[1].seen && messages[1].flagged)
        assertEquals(1, db.folders().byId(inbox)!!.unread)
    }

    @Test
    fun theTextsASyncLeavesAreFetchedAfterwards() = runBlocking {
        val count = MailSync.FIRST_BODIES + 3
        repeat(count) { server.deliver("ileti ${it + 1}", "Metin ${it + 1}.", hoursAgo((count - it).toLong())) }
        val id = account()

        sync.account(id)

        val inbox = folderId(id, "INBOX")
        // the list is whole; the newest have their text, the oldest three wait
        val listed = db.messages().observeFolder(inbox).first()
        assertEquals(count, listed.size)
        assertEquals(listOf("ileti 1", "ileti 2", "ileti 3"), listed.filter { it.bodyState == MessageEntity.BODY_MISSING }.map { it.subject }.sorted())
        // the app starts the rest by itself; here it is held back
        assertEquals(1, held.waiting)

        // one of them is opened: its text comes ahead of its turn
        val opened = listed.first { it.subject == "ileti 2" }
        assertEquals(MailSync.Outcome.Done, sync.body(opened.id))
        assertEquals("Metin 2.", db.messages().observeBody(opened.id).first()!!.text)
        assertEquals(MailSync.Outcome.Done, sync.body(opened.id))
        assertEquals(MailSync.Outcome.Gone, sync.body(404))

        assertEquals(MailSync.Outcome.Done, sync.backlog(id))
        assertTrue(db.messages().observeFolder(inbox).first().all { it.bodyState == MessageEntity.BODY_KEPT })
        assertTrue(db.messages().foldersWithoutBodies(id).isEmpty())
        // nothing left: no connection is made
        assertEquals(MailSync.Outcome.Done, sync.backlog(id))
    }

    @Test
    fun aRefusedSignInAsksForSigningInAgain() = runBlocking {
        server.deliver("ilk", "Birinci ileti.", hoursAgo(4))
        val id = account(password = "eski-parola")

        val outcome = sync.account(id)

        assertEquals(MailException.Kind.Authentication, (outcome as MailSync.Outcome.Failed).kind)
        assertEquals(AccountState.NeedsLogin, accounts.byId(id)!!.accountState())
        assertEquals(0, db.messages().count())
        // neither password went into the log
        assertTrue(log.lines().none { it.contains("eski-parola") || it.contains(TestImapServer.PASSWORD) })

        // "Reconnect": a wrong one is not kept, the right one is
        assertTrue(setup.reconnect(id, "yine-yanlis") is SetupResult.Failed)
        assertEquals(AccountState.NeedsLogin, accounts.byId(id)!!.accountState())
        assertEquals(SetupResult.Done(id), setup.reconnect(id, TestImapServer.PASSWORD))
        assertEquals(AccountState.Ok, accounts.byId(id)!!.accountState())
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        assertEquals(1, db.messages().count())
    }

    @Test
    fun aServerThatDoesNotAnswerIsUnreachable() = runBlocking {
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        server.stop()

        val outcome = sync.account(id)

        assertEquals(MailException.Kind.Network, (outcome as MailSync.Outcome.Failed).kind)
        val stored = accounts.byId(id)!!
        assertEquals(AccountState.Unreachable, stored.accountState())
        assertTrue(stored.stateDetail.isNotEmpty())
        assertEquals(MailSync.Outcome.Gone, sync.account(404))
    }

    @Test
    fun aPasswordThisDeviceCannotOpenAsksForSigningInAgain() = runBlocking {
        val id = account()
        // as after the keystore lost its key
        val other = MailSync(db, AccountStore(db, testBox(seed = 5), log), log, background, clock = { now })

        val outcome = other.account(id)

        assertEquals(MailException.Kind.Authentication, (outcome as MailSync.Outcome.Failed).kind)
        assertEquals(AccountState.NeedsLogin, accounts.byId(id)!!.accountState())
    }

    /** What tells of new mail hangs on every sync of an account that worked; what it does is not the sync's. */
    @Test
    fun whatFollowsASyncIsToldOnceItWorkedAndCannotFailIt() = runBlocking {
        server.deliver("ilk", "Birinci ileti.", hoursAgo(2))
        val id = account()
        val told = ArrayList<Long>()
        sync.onSynced = { told += it }

        assertEquals(MailSync.Outcome.Done, sync.account(id))
        assertEquals(listOf(id), told)

        // what follows breaks: the sync itself still worked
        sync.onSynced = { error("the notifier broke") }
        assertEquals(MailSync.Outcome.Done, sync.account(id))

        // a sync that did not work is followed by nothing
        sync.onSynced = { told += it }
        server.stop()
        assertTrue(sync.account(id) is MailSync.Outcome.Failed)
        assertEquals(listOf(id), told)
    }
}
