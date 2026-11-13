package com.deskmail.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import com.deskmail.R
import com.deskmail.core.log.AppLog
import com.deskmail.data.DAY_MS
import com.deskmail.data.answered
import com.deskmail.db.AccountEntity
import com.deskmail.db.AwaitingEntity
import com.deskmail.db.DeskMailDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reminds the user of his messages whose answers did not come in time ("Awaiting replies",
 * [AwaitingEntity]). After an account was synced, [check] looks at what it waits for:
 *
 * - a message that was answered meanwhile (somebody else's message names it in its In-Reply-To
 *   or References) is no longer waited for, and its reminder goes, should there be one;
 * - one whose time has passed is told of in a notification of its own, once: whom it went to and
 *   how long ago. Tapping it opens the message under "Awaiting replies"; "Stop waiting" forgets it.
 *
 * When the next of them is due, the background work looks again ([schedule]: the accounts are
 * synced first, and the answers that came meanwhile are known of). [arrange] has that work there
 * for every time a reminder is due; it is called whenever something comes to be waited for.
 */
class ReplyReminder(
    private val context: Context,
    private val db: DeskMailDatabase,
    private val store: NotifyStore,
    private val log: AppLog,
    private val schedule: (dueAt: Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    /** Looks at what the account waits for: the answered is forgotten, what is due is reminded of. */
    suspend fun check(accountId: Long) = withContext(Dispatchers.Default) {
        lock.withLock {
            look(accountId)
            tidy()
        }
    }

    /** [check] for every account that waits for something. */
    suspend fun checkAll() = withContext(Dispatchers.Default) {
        lock.withLock {
            db.awaiting().all().map { it.accountId }.distinct().forEach { look(it) }
            tidy()
        }
    }

    /**
     * Has the background work look again whenever a reminder is due, and takes down the reminders
     * of what is no longer waited for.
     */
    suspend fun arrange() = withContext(Dispatchers.Default) {
        lock.withLock {
            db.awaiting().all().filter { it.toldAt == 0L }.map { it.dueAt }.distinct().forEach(schedule)
            tidy()
        }
    }

    /** No longer waits for an answer to the message [id] ([AwaitingEntity.id]): its reminder goes with it. */
    suspend fun stop(id: Long) = lock.withLock {
        db.awaiting().delete(id)
        manager.cancel(TAG_REMINDER, id.toInt())
    }

    private suspend fun look(accountId: Long) {
        val waited = db.awaiting().ofAccount(accountId)
        if (waited.isEmpty()) return
        val account = db.accounts().byId(accountId) ?: return
        val time = now()
        for (row in waited) {
            if (db.answered(accountId, account.address, row.messageId)) {
                db.awaiting().delete(row.id)
                manager.cancel(TAG_REMINDER, row.id.toInt())
                log.info(TAG, "account $accountId: a message an answer was waited for was answered")
                continue
            }
            if (row.toldAt != 0L || row.dueAt > time) continue
            // once: whether or not the system lets it be shown
            db.awaiting().told(row.id, time)
            if (!manager.areNotificationsEnabled()) {
                log.info(TAG, "account $accountId: an answer did not come in time; the system does not let the app tell of it")
                continue
            }
            post(account, row, time)
            log.info(TAG, "account $accountId: an answer did not come in time; the user was reminded")
        }
    }

    /** Takes down the reminders of messages that are no longer waited for (answered, or the user stopped waiting). */
    private suspend fun tidy() {
        val shown = manager.activeNotifications.filter { it.tag == TAG_REMINDER }
        if (shown.isEmpty()) return
        val waited = db.awaiting().all().mapTo(HashSet()) { it.id.toInt() }
        shown.filter { it.id !in waited }.forEach { manager.cancel(TAG_REMINDER, it.id) }
    }

    /** "No answer yet": the message's subject, whom it went to and how many days ago; it opens the message. */
    private suspend fun post(account: AccountEntity, row: AwaitingEntity, time: Long) {
        ensureChannel()
        val subject = row.subject.ifBlank { context.getString(R.string.message_no_subject) }
        val days = ((time - row.sentAt) / DAY_MS).toInt().coerceAtLeast(1)
        val sent = context.resources.getQuantityString(R.plurals.notify_awaiting_sent, days, days)
        val said = if (row.recipients.isBlank()) sent else context.getString(R.string.notify_awaiting_to, row.recipients) + " · " + sent
        // the copy under Sent, where the device has it: the reminder opens it among those waited for
        val copy = db.messages().sentCopy(row.accountId, row.messageId) ?: 0L
        val open = PendingIntent.getActivity(
            context,
            0,
            OpenRequest.intent(context, copy, store.token, OpenRequest.Place.Awaiting),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(context.getString(R.string.notify_awaiting_title))
            .setContentText(subject)
            .setStyle(Notification.BigTextStyle().bigText(subject + "\n" + said))
            .setSubText(account.name)
            .setWhen(row.sentAt)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, context.getString(R.string.awaiting_stop), stopIntent(row.id)).build())
            .build()
        manager.notify(TAG_REMINDER, row.id.toInt(), notification)
    }

    /** "Stop waiting": has the app's receiver forget the message [id] that an answer was waited for. */
    private fun stopIntent(id: Long): PendingIntent {
        val intent = Intent(context, NotifyActionReceiver::class.java)
            .setAction(NotifyActionReceiver.STOP_AWAITING)
            // what tells two of these apart: extras do not
            .setData(Uri.parse("deskmail://awaiting/$id"))
            .putExtra(NotifyActionReceiver.EXTRA_AWAITING, id)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The reminders' channel; made the first time it is needed. */
    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notify_awaiting_channel), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.notify_awaiting_channel_description)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "replies"

        /** The tag the reminders are posted under, each by its [AwaitingEntity.id]. */
        const val TAG_REMINDER = "awaiting"

        /** The channel the reminders are told on. */
        const val CHANNEL = "awaiting"
    }
}
