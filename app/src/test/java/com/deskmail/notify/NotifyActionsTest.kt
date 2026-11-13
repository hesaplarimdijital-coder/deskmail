package com.deskmail.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.data.AccountStore
import com.deskmail.data.ComposeWords
import com.deskmail.data.LiveComposer
import com.deskmail.data.LiveMail
import com.deskmail.data.MailSync
import com.deskmail.data.TestImapServer
import com.deskmail.data.endAndWait
import com.deskmail.data.eventually
import com.deskmail.data.testBox
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.OutboxEntity
import jakarta.mail.Flags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * New mail from the server to a notification, and a notification's buttons back to the server,
 * against a mail server inside the test: what the background work and the receiver do, without
 * the system's scheduler between them.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr")
class NotifyActionsTest {

    @get:Rule
    val server = TestImapServer()

    private val start: Instant = Instant.parse("2026-11-12T09:30:00Z")
    private val clock = AtomicLong(start.toEpochMilli())
    private val log = AppLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** How often the servers were asked to be told, by work that outlives the button's press. */
    private val asked = AtomicInteger()
    private lateinit var db: DeskMailDatabase
    private lateinit var accounts: AccountStore
    private lateinit var sync: MailSync
    private lateinit var mail: LiveMail
    private lateinit var notifier: MailNotifier
    private lateinit var composer: LiveComposer
    private lateinit var actions: NotifyActions

    /** When the outbox was asked to be looked at, as delays from the moment of asking. */
    private val scheduled = CopyOnWriteArrayList<Long>()

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
        db = DeskMailDatabase.inMemory(context)
        accounts = AccountStore(db, testBox(), log)
        sync = MailSync(db, accounts, log, scope, clock = { Instant.ofEpochMilli(clock.get()) })
        mail = LiveMail(db, sync, scope, File(context.cacheDir, "notify-" + System.nanoTime()), now = { clock.get() }, paceMs = 0, tellAfterMs = 0)
        notifier = MailNotifier(context, db, NotifyStore(context.getSharedPreferences("notify-actions-test", Context.MODE_PRIVATE)), FrontViews(), log)
        // as the app has it: whoever has an account synced, new mail is told of afterwards
        sync.onSynced = { notifier.check(it) }
        composer = LiveComposer(
            db = db,
            sync = sync,
            scope = scope,
            files = File(context.cacheDir, "notify-outbox-" + System.nanoTime()),
            scheduler = { delay -> scheduled += delay },
            fetchFile = { mail.fetch(it) },
            words = { words },
            log = log,
            now = { clock.get() },
        )
        actions = NotifyActions(mail, notifier, log, composer, tellServers = { asked.incrementAndGet() })
    }

    @After
    fun close() {
        endAndWait(scope)
        db.close()
    }

    private fun hoursAgo(hours: Long): Instant = start.minus(Duration.ofHours(hours))

    private suspend fun account(): Long = accounts.add(TestImapServer.ADDRESS, "Posta", "", server.settings, TestImapServer.PASSWORD)

    private fun shownMessages(): List<Long> = manager.activeNotifications.filter { it.tag == MailNotifier.MESSAGE }.map { it.id.toLong() }

    /** The device's id of the message with this subject. */
    private suspend fun DeskMailDatabase.idOf(accountId: Long, subject: String): Long {
        val inbox = folders().ofAccount(accountId).first { it.path == "INBOX" }.id
        return messages().unreadAfter(inbox, 0, 100).first { it.subject == subject }.id
    }

    @Test
    fun mailThatArrivesIsToldOfByTheSyncThatBringsIt() = runBlocking {
        server.deliver("Eski", "Bunu biliyorsun.", hoursAgo(5))
        val id = account()
        // the first sync: everything in the inbox is old mail
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        assertTrue(manager.activeNotifications.isEmpty())

        server.deliver("Cumartesi kahvaltı?", "Selam Deniz, kahvaltıya ne dersin?", hoursAgo(1))
        // as the background work does it: every account, with its servers in reach
        assertFalse(mail.syncAll())

        val one = manager.activeNotifications.single { it.tag == MailNotifier.MESSAGE }
        assertEquals(db.idOf(id, "Cumartesi kahvaltı?"), one.id.toLong())
        assertEquals("Ayşe Yılmaz", one.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Cumartesi kahvaltı?", one.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        // its first lines came with it
        assertEquals(
            "Cumartesi kahvaltı?\nSelam Deniz, kahvaltıya ne dersin?",
            one.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString(),
        )

        // looked for again with nothing new: nothing more is said
        assertFalse(mail.syncAll())
        assertEquals(1, shownMessages().size)
    }

    @Test
    fun aNotificationsButtonsAreCarriedOutOnTheDeviceAtOnceAndToldToTheServer() = runBlocking {
        server.createFolder("Archive")
        server.createFolder("Trash")
        server.deliver("Eski", "Bunu biliyorsun.", hoursAgo(5))
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        val toRead = server.deliver("Okunacak", "Birinci.", hoursAgo(3))
        server.deliver("Arşivlenecek", "İkinci.", hoursAgo(2))
        server.deliver("Silinecek", "Üçüncü.", hoursAgo(1))
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        val read = db.idOf(id, "Okunacak")
        val archived = db.idOf(id, "Arşivlenecek")
        val deleted = db.idOf(id, "Silinecek")
        assertEquals(setOf(read, archived, deleted), shownMessages().toSet())

        // "Read": read on the device before the call returns, its notification gone
        actions.act(NotifyActionReceiver.READ, read)
        assertTrue(db.messages().byId(read)!!.seen)
        assertEquals(setOf(archived, deleted), shownMessages().toSet())
        assertEquals(1, asked.get())

        actions.act(NotifyActionReceiver.ARCHIVE, archived)
        actions.act(NotifyActionReceiver.DELETE, deleted)
        // every notification is gone, and the account's line with the last
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(3, asked.get())

        // what the background work then does: nothing waits out a time to be taken back
        assertFalse(mail.tellAll())
        assertTrue(server.has(toRead, Flags.Flag.SEEN))
        assertEquals(1, server.uids("Archive").size)
        assertEquals(1, server.uids("Trash").size)
        // the old message and the one that was read stay in the inbox
        assertEquals(2, server.uids().size)
        assertTrue(db.pending().all().isEmpty())
        assertFalse(mail.tellAll())
    }

    /** Three buttons, as many as the system shows: "Reply" with a field to type into, "Read", and a way to put the message away. */
    @Test
    fun aNotificationOffersToReplyFromWhereItIs() = runBlocking {
        server.deliver("Eski", "Bunu biliyorsun.", hoursAgo(5))
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        server.deliver("Cumartesi kahvaltı?", "Selam Deniz, kahvaltıya ne dersin?", hoursAgo(1))
        assertEquals(MailSync.Outcome.Done, sync.account(id))

        val buttons = manager.activeNotifications.single { it.tag == MailNotifier.MESSAGE }.notification.actions
        // an account without an archive deletes
        assertEquals(listOf("Yanıtla", "Okundu", "Sil"), buttons.map { it.title.toString() })
        assertEquals(NotifyActionReceiver.REPLY_TEXT, buttons[0].remoteInputs.single().resultKey)
        assertEquals(Notification.Action.SEMANTIC_ACTION_REPLY, buttons[0].semanticAction)
    }

    /** What is typed into a notification is a reply as "Reply" begins it, with the text on top, and it goes at once. */
    @Test
    fun aReplyTypedIntoANotificationIsSentAtOnce() = runBlocking {
        server.deliver("Eski", "Bunu biliyorsun.", hoursAgo(5))
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        server.deliver("Cumartesi kahvaltı?", "Selam Deniz, kahvaltıya ne dersin?", hoursAgo(1))
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        val message = db.idOf(id, "Cumartesi kahvaltı?")

        assertTrue(actions.reply(message, "  Olur, geliyorum.  "))

        // the notification is gone, and an answered message counts as read
        assertTrue(shownMessages().isEmpty())
        assertTrue(db.messages().byId(message)!!.seen)
        assertEquals(1, asked.get())
        // handed over, and nothing waits out a time to be taken back
        eventually("the reply is due now") { scheduled.contains(0L) }
        val reply = db.outbox().observeAll().first().single()
        assertEquals(OutboxEntity.QUEUED, reply.state)
        assertTrue(reply.sendAfter <= clock.get())
        assertEquals("Re: Cumartesi kahvaltı?", reply.subject)
        assertTrue(reply.toText, reply.toText.contains("ayse@example.org"))
        assertTrue(reply.body, reply.body.startsWith("Olur, geliyorum.\n"))
        assertTrue(reply.body, reply.body.contains("> Selam Deniz, kahvaltıya ne dersin?"))
        assertEquals(message, reply.sourceId)
    }

    /** Nothing typed, or a message that went meanwhile: nothing is written, nothing is sent. */
    @Test
    fun anEmptyReplyOrOneToAMessageThatWentSendsNothing() = runBlocking {
        server.deliver("Eski", "Bunu biliyorsun.", hoursAgo(5))
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        server.deliver("Cumartesi kahvaltı?", "Selam Deniz, kahvaltıya ne dersin?", hoursAgo(1))
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        val message = db.idOf(id, "Cumartesi kahvaltı?")

        assertFalse(actions.reply(message, "   "))
        assertFalse(actions.reply(404, "Olur."))

        assertTrue(db.outbox().observeAll().first().isEmpty())
        assertFalse(db.messages().byId(message)!!.seen)
        assertEquals(0, asked.get())
    }

    /** Swept away: nothing is done to the message, and nothing is told to anybody. */
    @Test
    fun aNotificationThatWasSweptAwayLeavesItsMessageAsItIs() = runBlocking {
        server.deliver("Eski", "Bunu biliyorsun.", hoursAgo(5))
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        server.deliver("Sonra bakarım", "Birinci.", hoursAgo(3))
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        val later = db.idOf(id, "Sonra bakarım")

        actions.act(NotifyActionReceiver.DISMISSED, later)

        assertTrue(manager.activeNotifications.isEmpty())
        assertFalse(db.messages().byId(later)!!.seen)
        assertTrue(db.pending().all().isEmpty())
        assertEquals(0, asked.get())
    }

    @Test
    fun aButtonForAMessageThatIsNoLongerThereDoesNothing() = runBlocking {
        account()

        actions.act(NotifyActionReceiver.READ, 404)
        actions.act(NotifyActionReceiver.ARCHIVE, 404)
        actions.act(NotifyActionReceiver.DELETE, 404)
        actions.act("com.deskmail.notify.SOMETHING", 404)

        assertEquals(0, asked.get())
        assertTrue(db.pending().all().isEmpty())
        assertNull(db.messages().byId(404))
    }

    @Test
    fun aServerThatCannotBeReachedKeepsWhatWasDoneWaiting() = runBlocking {
        server.deliver("Eski", "Bunu biliyorsun.", hoursAgo(5))
        val id = account()
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        server.deliver("Okunacak", "Birinci.", hoursAgo(3))
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        val read = db.idOf(id, "Okunacak")
        actions.act(NotifyActionReceiver.READ, read)
        server.stop()

        // the work says so, to be tried again; what was done stays written down
        assertTrue(mail.tellAll())
        assertEquals(1, db.pending().all().size)
        assertTrue(db.messages().byId(read)!!.seen)
        // and looking for new mail says the same
        assertTrue(mail.syncAll())
    }
}
