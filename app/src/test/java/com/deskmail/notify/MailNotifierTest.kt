package com.deskmail.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.data.endAndWait
import com.deskmail.data.eventually
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.testAccount
import com.deskmail.db.testFolder
import com.deskmail.db.testMessage
import com.deskmail.mail.MailView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * New mail as the system's notifications show it: what is told of and what is not, what a
 * notification says and offers, and when it goes again.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr")
class MailNotifierTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val front = FrontViews()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: DeskMailDatabase
    private lateinit var store: NotifyStore
    private lateinit var notifier: MailNotifier
    private var account = 0L
    private var inbox = 0L

    @Before
    fun open() {
        db = DeskMailDatabase.inMemory(context)
        store = NotifyStore(context.getSharedPreferences("notify-test", Context.MODE_PRIVATE))
        notifier = MailNotifier(context, db, store, front, AppLog())
        runBlocking {
            account = db.accounts().insert(testAccount("deniz@example.org", name = "Posta"))
            inbox = db.folders().insert(testFolder(account, "INBOX", role = "Inbox").copy(uidValidity = 7))
            db.folders().insert(testFolder(account, "Archive", role = "Archive", position = 1))
        }
    }

    @After
    fun close() {
        endAndWait(scope)
        db.close()
    }

    /** A message arrives in the inbox, as a sync would have stored it; its id. */
    private suspend fun arrives(uid: Long, subject: String = "konu $uid", preview: String = "", seen: Boolean = false, folder: Long = inbox): Long =
        db.messages().insert(listOf(testMessage(account, folder, uid, subject).copy(preview = preview, seen = seen))).single()

    private fun shown(tag: String): List<StatusBarNotification> = manager.activeNotifications.filter { it.tag == tag }

    private fun messagesShown(): List<Long> = shown(MailNotifier.MESSAGE).map { it.id.toLong() }.sorted()

    private fun Notification.text(key: String): String = extras.getCharSequence(key)?.toString().orEmpty()

    @Test
    fun theFirstLookAtAnInboxTellsOfNothing() = runBlocking {
        arrives(1)
        arrives(2)

        notifier.check(account)

        assertTrue(manager.activeNotifications.isEmpty())
        // what is there counts as told of
        assertEquals(Watermark(uidValidity = 7, uid = 2), store.told(inbox))
    }

    @Test
    fun newMailIsToldOfOnceWithWhoItIsFromItsSubjectAndItsFirstLines() = runBlocking {
        arrives(1)
        notifier.check(account)

        val breakfast = arrives(2, "Cumartesi kahvaltı?", preview = "Selam Deniz, kahvaltıya ne dersin?")
        // read on another device before it got here: nothing to tell
        arrives(3, "Okunmuş", seen = true)
        notifier.check(account)

        assertEquals(listOf(breakfast), messagesShown())
        val one = shown(MailNotifier.MESSAGE).single().notification
        assertEquals("Ayşe Yılmaz", one.text(Notification.EXTRA_TITLE))
        assertEquals("Cumartesi kahvaltı?", one.text(Notification.EXTRA_TEXT))
        assertEquals("Cumartesi kahvaltı?\nSelam Deniz, kahvaltıya ne dersin?", one.text(Notification.EXTRA_BIG_TEXT))
        assertEquals("Posta", one.text(Notification.EXTRA_SUB_TEXT))
        assertEquals(MailNotifier.channelId(account), one.channelId)
        assertEquals(MailNotifier.group(account), one.group)
        assertEquals(listOf("Okundu", "Arşivle", "Sil"), one.actions.map { it.title.toString() })

        // the account's line over its messages
        val summary = shown(MailNotifier.SUMMARY).single()
        assertEquals(account.toInt(), summary.id)
        assertEquals("Posta", summary.notification.text(Notification.EXTRA_TITLE))
        assertEquals("1 yeni ileti", summary.notification.text(Notification.EXTRA_TEXT))
        assertTrue(summary.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(MailNotifier.group(account), summary.notification.group)

        // the account's own channel, named after it
        val channel = manager.getNotificationChannel(MailNotifier.channelId(account))
        assertEquals("Posta", channel.name.toString())
        assertEquals("deniz@example.org hesabının yeni iletileri", channel.description)

        // looked at again with nothing new: nothing more is said
        assertEquals(Watermark(uidValidity = 7, uid = 3), store.told(inbox))
        notifier.check(account)
        assertEquals(2, manager.activeNotifications.size)
    }

    /** Tapping a notification asks a window for its message; its buttons ask the receiver. */
    @Test
    fun aNotificationOpensItsMessageAndItsButtonsNameIt() = runBlocking {
        arrives(1)
        notifier.check(account)
        val id = arrives(2)
        notifier.check(account)

        val one = shown(MailNotifier.MESSAGE).single().notification
        val tapped = shadowOf(one.contentIntent).savedIntent
        assertEquals(OpenRequest(id), OpenRequest.from(tapped, store.token))
        // the window brought back later from the list of recent apps is not asked again
        assertNull(OpenRequest.from(Intent(tapped).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), store.token))
        assertNull(OpenRequest.from(Intent(Intent.ACTION_MAIN), store.token))
        // any app can ask the window for a message; only the app's own notifications carry what they are known by
        assertNull(OpenRequest.from(Intent(tapped).apply { removeExtra("token") }, store.token))
        assertNull(OpenRequest.from(Intent(tapped).putExtra("token", "guessed"), store.token))
        assertNull(OpenRequest.from(tapped, "another installation's"))
        val buttons = one.actions.map { shadowOf(it.actionIntent).savedIntent }
        assertEquals(listOf(NotifyActionReceiver.READ, NotifyActionReceiver.ARCHIVE, NotifyActionReceiver.DELETE), buttons.map { it.action })
        assertTrue(buttons.all { it.getLongExtra(NotifyActionReceiver.EXTRA_MESSAGE, 0) == id })
        assertTrue(buttons.all { it.component?.className == NotifyActionReceiver::class.java.name })
        // swept away, the receiver hears of it
        val swept = shadowOf(one.deleteIntent).savedIntent
        assertEquals(NotifyActionReceiver.DISMISSED, swept.action)
        assertEquals(id, swept.getLongExtra(NotifyActionReceiver.EXTRA_MESSAGE, 0))
        // the account's line opens the inbox
        assertEquals(OpenRequest(0), OpenRequest.from(shadowOf(shown(MailNotifier.SUMMARY).single().notification.contentIntent).savedIntent, store.token))
    }

    /** An account without an archive offers none. */
    @Test
    fun withoutAnArchiveThereIsNoButtonForIt() = runBlocking {
        val other = db.accounts().insert(testAccount("elif@example.org", name = "İş", position = 1))
        val otherInbox = db.folders().insert(testFolder(other, "INBOX", role = "Inbox"))
        notifier.check(other)
        db.messages().insert(listOf(testMessage(other, otherInbox, 5)))

        notifier.check(other)

        assertEquals(listOf("Okundu", "Sil"), shown(MailNotifier.MESSAGE).single().notification.actions.map { it.title.toString() })
        assertEquals(other.toInt(), shown(MailNotifier.SUMMARY).single().id)
    }

    @Test
    fun onlyTheInboxIsToldOf() = runBlocking {
        val lists = db.folders().insert(testFolder(account, "Listeler", position = 2))
        notifier.check(account)

        arrives(4, folder = lists)
        notifier.check(account)

        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun mailThatArrivesInAListTheUserIsLookingAtIsNotToldOf() = runBlocking {
        arrives(1)
        notifier.check(account)
        val window = Any()

        front.show(window, MailView.Inbox)
        arrives(2)
        notifier.check(account)
        assertTrue(manager.activeNotifications.isEmpty())

        // the window left the front: the next one is told of, the one that was seen arriving is not
        front.show(window, null)
        val third = arrives(3)
        notifier.check(account)
        assertEquals(listOf(third), messagesShown())
    }

    @Test
    fun whatTheWindowsShowDecidesWhatNeedsNoTelling() {
        val first = Any()
        val second = Any()
        assertFalse(front.shows(inbox))

        front.show(first, MailView.Starred)
        front.show(second, MailView.Search("kahvaltı"))
        assertFalse(front.shows(inbox))
        // another folder's list is not this inbox's
        front.show(first, MailView.Folder(inbox + 1))
        assertFalse(front.shows(inbox))

        front.show(second, MailView.Folder(inbox))
        assertTrue(front.shows(inbox))
        front.show(second, MailView.Unread)
        assertTrue(front.shows(inbox))
        front.show(second, null)
        assertFalse(front.shows(inbox))
    }

    @Test
    fun whenTheSystemDoesNotAllowNotificationsNoneIsShown() = runBlocking {
        arrives(1)
        notifier.check(account)
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(notifier.allowed)

        arrives(2)
        notifier.check(account)

        assertTrue(manager.activeNotifications.isEmpty())
        // and it is not brought up later, once they are allowed: it is old news by then
        shadowOf(manager).setNotificationsEnabled(true)
        notifier.check(account)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun aNotificationGoesWhenItsMessageIsReadAndTheAccountsLineWithTheLast() = runBlocking {
        arrives(1)
        notifier.check(account)
        val second = arrives(2)
        val third = arrives(3)
        notifier.check(account)
        assertEquals(listOf(second, third), messagesShown())
        assertEquals("2 yeni ileti", shown(MailNotifier.SUMMARY).single().notification.text(Notification.EXTRA_TEXT))

        // read in a window of the app, or on another device and learnt of by a sync
        db.pending().mark(second, seen = true, flagged = null, now = 100)
        notifier.tidy()
        assertEquals(listOf(third), messagesShown())
        // the account's line counts what is left
        assertEquals("1 yeni ileti", shown(MailNotifier.SUMMARY).single().notification.text(Notification.EXTRA_TEXT))

        // deleted on another device: gone from the device with the next sync
        db.messages().deleteRows(inbox, listOf(3))
        notifier.tidy()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    /** The system takes a notification down when the user sweeps it away; the account's line follows. */
    @Test
    fun whatTheUserSweptAwayIsNoLongerCounted() = runBlocking {
        arrives(1)
        notifier.check(account)
        val second = arrives(2)
        val third = arrives(3)
        notifier.check(account)

        manager.cancel(MailNotifier.MESSAGE, second.toInt())
        notifier.tidy()
        assertEquals(listOf(third), messagesShown())
        assertEquals("1 yeni ileti", shown(MailNotifier.SUMMARY).single().notification.text(Notification.EXTRA_TEXT))

        // the last one as well: a line with nothing under it does not stay
        manager.cancel(MailNotifier.MESSAGE, third.toInt())
        notifier.tidy()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun theNotificationsFollowTheMailByThemselves() = runBlocking {
        arrives(1)
        notifier.check(account)
        val second = arrives(2)
        notifier.check(account)
        assertEquals(listOf(second), messagesShown())

        notifier.watch(scope)
        db.pending().mark(second, seen = true, flagged = null, now = 100)

        eventually("the notification of the message that was read is gone") { manager.activeNotifications.isEmpty() }
    }

    @Test
    fun aNotificationThatIsDealtWithIsTakenDownAtOnce() = runBlocking {
        arrives(1)
        notifier.check(account)
        val second = arrives(2)
        val third = arrives(3)
        notifier.check(account)

        notifier.cancel(second)
        assertEquals(listOf(third), messagesShown())
        assertEquals(1, shown(MailNotifier.SUMMARY).size)

        notifier.cancel(third)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    /** More at once than anybody reads one by one: the newest are told of. */
    @Test
    fun aFloodIsCutDownToItsNewest() = runBlocking {
        arrives(1)
        notifier.check(account)
        val ids = (2L..(MailNotifier.MAX_TOLD + 5)).map { arrives(it) }

        notifier.check(account)

        assertEquals(ids.takeLast(MailNotifier.MAX_TOLD), messagesShown())
        assertEquals("${MailNotifier.MAX_TOLD} yeni ileti", shown(MailNotifier.SUMMARY).single().notification.text(Notification.EXTRA_TEXT))
        // the rest is not brought up later
        assertEquals(Watermark(7, MailNotifier.MAX_TOLD + 5L), store.told(inbox))
    }

    /** Mail that arrives while earlier mail is still shown: the account's line heads all of it. */
    @Test
    fun theAccountsLineCountsWhatIsShownOfItsMail() = runBlocking {
        arrives(1)
        notifier.check(account)
        val second = arrives(2, "İlk gelen")
        notifier.check(account)
        assertEquals("1 yeni ileti", shown(MailNotifier.SUMMARY).single().notification.text(Notification.EXTRA_TEXT))

        val third = arrives(3, "Sonra gelen")
        notifier.check(account)

        assertEquals(listOf(second, third), messagesShown())
        val summary = shown(MailNotifier.SUMMARY).single().notification
        assertEquals("2 yeni ileti", summary.text(Notification.EXTRA_TEXT))
        // the newest first
        assertEquals(
            listOf("Ayşe Yılmaz  Sonra gelen", "Ayşe Yılmaz  İlk gelen"),
            summary.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() },
        )
    }

    @Test
    fun anInboxTheServerNumberedAnewIsLookedAtAfresh() = runBlocking {
        arrives(1)
        notifier.check(account)

        // the server gave the folder a new stamp: every message has a new number, none of them is news
        db.folders().update(db.folders().byId(inbox)!!.copy(uidValidity = 8))
        arrives(40)
        notifier.check(account)

        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(Watermark(uidValidity = 8, uid = 40), store.told(inbox))
    }

    @Test
    fun anAccountThatIsRemovedTakesItsNotificationsItsChannelAndItsMarksAlong() = runBlocking {
        arrives(1)
        notifier.check(account)
        arrives(2)
        notifier.check(account)
        assertNotNull(manager.getNotificationChannel(MailNotifier.channelId(account)))

        notifier.forget(account, listOf(inbox))

        assertTrue(manager.activeNotifications.isEmpty())
        assertNull(manager.getNotificationChannel(MailNotifier.channelId(account)))
        assertNull(store.told(inbox))
    }

    @Test
    fun theStoreRemembersWhatItWasToldAcrossRuns() {
        val prefs = context.getSharedPreferences("notify-store-test", Context.MODE_PRIVATE)
        val first = NotifyStore(prefs)
        assertNull(first.told(1))
        assertFalse(first.asked)
        assertEquals(0, first.scheduledMinutes)

        first.tell(1, Watermark(7, 12))
        first.tell(2, Watermark(9, 3))
        first.asked = true
        first.scheduledMinutes = 30
        // made up once, and the same from then on
        val token = first.token
        assertTrue(token.length >= 32)
        assertEquals(token, first.token)

        val next = NotifyStore(prefs)
        assertEquals(Watermark(7, 12), next.told(1))
        assertTrue(next.asked)
        assertEquals(30, next.scheduledMinutes)
        assertEquals(token, next.token)
        next.forget(listOf(1))
        assertNull(next.told(1))
        assertEquals(Watermark(9, 3), next.told(2))
    }
}
