package com.deskmail.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.data.DAY_MS
import com.deskmail.db.AwaitingEntity
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.testAccount
import com.deskmail.db.testFolder
import com.deskmail.db.testMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The user's messages whose answers are waited for: reminded of once their time has passed
 * without an answer, forgotten when one comes, and the background work there when each is due.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr")
class ReplyReminderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val now = 1_800_000_000_000L
    private val scheduled = CopyOnWriteArrayList<Long>()
    private lateinit var db: DeskMailDatabase
    private lateinit var store: NotifyStore
    private lateinit var reminder: ReplyReminder
    private var account = 0L
    private var inbox = 0L
    private var sent = 0L

    @Before
    fun open() {
        db = DeskMailDatabase.inMemory(context)
        store = NotifyStore(context.getSharedPreferences("replies-test", Context.MODE_PRIVATE))
        reminder = ReplyReminder(context, db, store, AppLog(), schedule = { scheduled += it }, now = { now })
        runBlocking {
            account = db.accounts().insert(testAccount("deniz@example.org", name = "Posta"))
            inbox = db.folders().insert(testFolder(account, "INBOX", role = "Inbox"))
            sent = db.folders().insert(testFolder(account, "Sent", role = "Sent", position = 1))
        }
    }

    @After
    fun close() {
        db.close()
    }

    /** The plan that went to Mehmet three days ago: its copy under Sent, and the wait for his answer, due at [dueAt]. */
    private suspend fun waitsForPlan(dueAt: Long, toldAt: Long = 0): Pair<Long, Long> {
        val copy = db.messages().insert(
            listOf(
                testMessage(account, sent, 1, "Proje planı").copy(
                    globalId = PLAN,
                    fromList = "Deniz Aksoy\tdeniz@example.org",
                    toList = "Mehmet Demir\tmehmet@example.org",
                    seen = true,
                ),
            ),
        ).single()
        val wait = db.awaiting().insert(
            AwaitingEntity(
                accountId = account,
                messageId = PLAN,
                subject = "Proje planı",
                recipients = "Mehmet Demir",
                sentAt = now - 3 * DAY_MS,
                dueAt = dueAt,
                toldAt = toldAt,
            ),
        )
        return copy to wait
    }

    /** A message arrives in the inbox from [from], answering [answers] by its In-Reply-To. */
    private suspend fun arrives(uid: Long, answers: String, from: String = "Mehmet Demir\tmehmet@example.org", refs: String = "") {
        db.messages().insert(listOf(testMessage(account, inbox, uid, "Re: Proje planı").copy(inReplyTo = answers, refs = refs, fromList = from)))
    }

    private fun reminders() = manager.activeNotifications.filter { it.tag == ReplyReminder.TAG_REMINDER }

    private fun Notification.text(key: String): String = extras.getCharSequence(key)?.toString().orEmpty()

    @Test
    fun anAnswerThatDidNotComeInTimeIsRemindedOfOnce() = runBlocking {
        val (copy, wait) = waitsForPlan(dueAt = now - 60_000)

        reminder.check(account)

        val shown = reminders().single()
        assertEquals(wait.toInt(), shown.id)
        val one = shown.notification
        assertEquals("Yanıt gelmedi", one.text(Notification.EXTRA_TITLE))
        assertEquals("Proje planı", one.text(Notification.EXTRA_TEXT))
        assertEquals("Proje planı\nKime: Mehmet Demir · 3 gün önce gönderildi", one.text(Notification.EXTRA_BIG_TEXT))
        assertEquals("Posta", one.text(Notification.EXTRA_SUB_TEXT))
        assertEquals(ReplyReminder.CHANNEL, one.channelId)
        assertEquals(listOf("Beklemeyi bırak"), one.actions.map { it.title.toString() })
        // a channel of its own: it can be silenced apart from new mail
        assertEquals("Yanıt bekleyenler", manager.getNotificationChannel(ReplyReminder.CHANNEL).name.toString())

        // tapped, it opens the copy under Sent among those waited for
        val opened = shadowOf(one.contentIntent).savedIntent
        assertEquals(OpenRequest(copy, OpenRequest.Place.Awaiting), OpenRequest.from(opened, store.token))
        // its button names what is waited for
        val stop = shadowOf(one.actions.single().actionIntent).savedIntent
        assertEquals(NotifyActionReceiver.STOP_AWAITING, stop.action)
        assertEquals(wait, stop.getLongExtra(NotifyActionReceiver.EXTRA_AWAITING, 0))

        // told of: once, also when it was swept away
        assertEquals(now, db.awaiting().byId(wait)!!.toldAt)
        manager.cancelAll()
        reminder.check(account)
        assertTrue(reminders().isEmpty())
    }

    @Test
    fun whatIsNotDueYetIsNotToldOf() = runBlocking {
        val (_, wait) = waitsForPlan(dueAt = now + DAY_MS)

        reminder.check(account)

        assertTrue(reminders().isEmpty())
        assertEquals(0L, db.awaiting().byId(wait)!!.toldAt)
    }

    @Test
    fun anAnswerEndsTheWaitAndTakesTheReminderDown() = runBlocking {
        val (_, wait) = waitsForPlan(dueAt = now - 60_000)
        reminder.check(account)
        assertEquals(1, reminders().size)

        arrives(2, answers = PLAN)
        reminder.check(account)

        assertNull(db.awaiting().byId(wait))
        assertTrue(reminders().isEmpty())
    }

    /** An answer further down a conversation names the message among its References. */
    @Test
    fun anAnswerFurtherDownTheConversationCountsToo() = runBlocking {
        val (_, wait) = waitsForPlan(dueAt = now + DAY_MS)

        arrives(2, answers = "<other@example.org>", refs = "$PLAN <other@example.org>")
        reminder.check(account)

        assertNull(db.awaiting().byId(wait))
    }

    /** What the user himself wrote after it answers nothing: the wait goes on. */
    @Test
    fun theUsersOwnFollowUpIsNoAnswer() = runBlocking {
        val (_, wait) = waitsForPlan(dueAt = now + DAY_MS)

        arrives(2, answers = PLAN, from = "Deniz Aksoy\tDeniz@Example.org")
        reminder.check(account)

        assertNotEquals(null, db.awaiting().byId(wait))
    }

    /** The background work is there for every time a reminder is due; what was told of needs none. */
    @Test
    fun theWorkIsArrangedForEveryTimeAReminderIsDue() = runBlocking {
        waitsForPlan(dueAt = now + DAY_MS)
        db.awaiting().insert(AwaitingEntity(accountId = account, messageId = "<b@example.org>", subject = "B", recipients = "", sentAt = now, dueAt = now + 2 * DAY_MS))
        db.awaiting().insert(AwaitingEntity(accountId = account, messageId = "<c@example.org>", subject = "C", recipients = "", sentAt = now, dueAt = now + DAY_MS))
        db.awaiting().insert(AwaitingEntity(accountId = account, messageId = "<d@example.org>", subject = "D", recipients = "", sentAt = now, dueAt = now - DAY_MS, toldAt = now))

        reminder.arrange()

        assertEquals(listOf(now + DAY_MS, now + 2 * DAY_MS), scheduled.sorted())
    }

    @Test
    fun stoppingForgetsItAndTakesItsReminderDown() = runBlocking {
        val (_, wait) = waitsForPlan(dueAt = now - 60_000)
        reminder.check(account)
        assertEquals(1, reminders().size)

        reminder.stop(wait)

        assertNull(db.awaiting().byId(wait))
        assertTrue(reminders().isEmpty())
    }

    /** Stopped elsewhere (in a window of the app): the next look takes the reminder down. */
    @Test
    fun aReminderOfWhatIsNoLongerWaitedForGoesAtTheNextLook() = runBlocking {
        val (_, wait) = waitsForPlan(dueAt = now - 60_000)
        reminder.check(account)

        db.awaiting().delete(wait)
        reminder.arrange()

        assertTrue(reminders().isEmpty())
    }

    private companion object {
        const val PLAN = "<plan@example.org>"
    }
}
