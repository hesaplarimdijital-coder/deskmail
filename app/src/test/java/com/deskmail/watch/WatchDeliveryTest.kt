package com.deskmail.watch

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.data.AccountStore
import com.deskmail.data.MailSync
import com.deskmail.data.TestImapServer
import com.deskmail.data.endAndWait
import com.deskmail.data.eventually
import com.deskmail.data.testBox
import com.deskmail.db.DeskMailDatabase
import com.deskmail.notify.FrontViews
import com.deskmail.notify.MailNotifier
import com.deskmail.notify.NotifyStore
import jakarta.mail.Flags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * Mail from the server to a notification with nobody looking for it: the watch waits on the
 * inbox of an account on a mail server inside the test, the sync fetches what the server tells
 * of, and the notifier tells the user. As the app wires them, without the service around them.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr")
class WatchDeliveryTest {

    @get:Rule
    val server = TestImapServer()

    private val start: Instant = Instant.parse("2026-11-13T09:30:00Z")
    private val log = AppLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var db: DeskMailDatabase
    private lateinit var accounts: AccountStore
    private lateinit var sync: MailSync
    private lateinit var store: NotifyStore
    private lateinit var watch: MailWatch

    @Before
    fun open() {
        db = DeskMailDatabase.inMemory(context)
        accounts = AccountStore(db, testBox(), log)
        sync = MailSync(db, accounts, log, scope, clock = { start })
        store = NotifyStore(context.getSharedPreferences("watch-delivery-test", Context.MODE_PRIVATE))
        // as the app has it: the notifications follow the mail, and whoever has an account synced, new mail is told of afterwards
        val notifier = MailNotifier(context, db, store, FrontViews(), log).also { it.watch(scope) }
        sync.onSynced = { notifier.check(it) }
        watch = MailWatch(db, accounts, log, scope, onNews = { sync.account(it) })
    }

    @After
    fun close() {
        runBlocking { watch.stop() }
        endAndWait(scope)
        db.close()
    }

    private fun hoursAgo(hours: Long): Instant = start.minus(Duration.ofHours(hours))

    private fun told(): List<android.service.notification.StatusBarNotification> = manager.activeNotifications.filter { it.tag == MailNotifier.MESSAGE }

    /** Begins to watch the test server's account, and waits until its inbox was looked at once. */
    private suspend fun watched(): Long {
        val id = accounts.add(TestImapServer.ADDRESS, "Posta", "", server.settings, TestImapServer.PASSWORD)
        watch.arrange()
        eventually("the inbox is waited on") { watch.watched.value == setOf(id) }
        // the first look: what is in the inbox is old mail, and how far it was told of is written down
        eventually("the inbox was looked at") {
            runBlocking { db.folders().ofAccount(id).firstOrNull { it.path == "INBOX" } }?.let { store.told(it.id) } != null
        }
        return id
    }

    @Test
    fun mailThatComesIsToldOfWithNobodyLookingForIt() = runBlocking {
        server.deliver("Eski", "Bunu biliyorsun.", hoursAgo(5))
        watched()
        assertTrue(told().isEmpty())

        server.deliver("Cumartesi kahvaltı?", "Selam Deniz, kahvaltıya ne dersin?", hoursAgo(0))

        eventually("the new message is told of") { told().size == 1 }
        val one = told().single()
        assertEquals("Ayşe Yılmaz", one.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Cumartesi kahvaltı?", one.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun aMessageThatIsReadSomewhereElseLosesItsNotification() = runBlocking {
        watched()
        val uid = server.deliver("Cumartesi kahvaltı?", "Selam Deniz, kahvaltıya ne dersin?", hoursAgo(0))
        eventually("the new message is told of") { told().size == 1 }

        // read in another mail program: the server says so on the line that waits
        server.setFlag(uid, Flags.Flag.SEEN, true)

        eventually("its notification is gone") { told().isEmpty() }
    }

    @Test
    fun theWaitGoesOnAfterItWasBegunAnew() = runBlocking {
        watched()

        watch.refresh()
        watch.refresh()
        server.deliver("Sonradan gelen", "Hat duruyor mu?", hoursAgo(0))

        eventually("the new message is told of") { told().size == 1 }
        assertTrue(log.lines().count { it.contains("the wait was begun anew") } == 2)
    }

    @Test
    fun aServerThatGoesAwayIsTriedAgain() = runBlocking {
        val id = watched()

        server.stop()

        eventually("the line is found lost") { log.lines().any { it.contains("account $id: the next try is in") } }
        assertTrue(watch.watched.value.isEmpty())
    }
}
