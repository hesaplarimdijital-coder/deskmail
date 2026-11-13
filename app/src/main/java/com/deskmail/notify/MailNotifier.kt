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
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailAddress
import com.deskmail.data.folderRole
import com.deskmail.db.AccountEntity
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tells of new mail with the system's notifications. After an account was synced, [check] looks
 * at its inboxes: what arrived since the last look and is still unread is told of, a
 * notification for each message (who it is from, the subject, its first lines) under a summary
 * for its account. Each account has a channel of its own, so the system's settings can give each
 * its own sound, or silence one.
 *
 * - The first look at an inbox tells of nothing: everything in it is old mail.
 * - Mail that arrives in a list the user is looking at ([FrontViews]) is not told of: the list
 *   shows it.
 * - A notification goes when its message is read, moved or deleted, here or on another device.
 *
 * Every notification offers "Read", "Archive" (where the account has an archive) and "Delete"
 * ([NotifyActionReceiver]); tapping it opens the message. The account's line counts the
 * notifications that are shown of its mail, and goes with the last of them.
 */
class MailNotifier(
    private val context: Context,
    private val db: DeskMailDatabase,
    private val store: NotifyStore,
    private val front: FrontViews,
    private val log: AppLog,
) {
    private val lock = Mutex()

    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    /** Whether the system lets the app show notifications: the user said yes, and has not turned them off since. */
    val allowed: Boolean get() = manager.areNotificationsEnabled()

    /**
     * Has the notifications follow the mail for as long as [scope] lives: one that was read, moved
     * or deleted (in a window of the app, or by a sync that learnt of it) is taken down.
     */
    fun watch(scope: CoroutineScope) {
        // off the main thread, whatever the scope's own is: the system is asked what it shows
        scope.launch(Dispatchers.Default) {
            db.messages().observeUnreadCount().distinctUntilChanged().collectLatest {
                // a moment for what else is being done to settle: several marks, a move with its count
                delay(TIDY_AFTER_MS)
                try {
                    tidy()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(TAG, "the notifications could not be brought up to date", e)
                }
            }
        }
    }

    /** Tells of the mail that arrived in the account's inboxes since they were last looked at. */
    suspend fun check(accountId: Long) = lock.withLock {
        val account = db.accounts().byId(accountId) ?: return@withLock
        val folders = db.folders().ofAccount(accountId)
        val archive = folders.any { folderRole(it.role) == FolderRole.Archive || folderRole(it.role) == FolderRole.All }
        for (inbox in folders) {
            if (folderRole(inbox.role) != FolderRole.Inbox) continue
            val highest = db.messages().highestUid(inbox.id) ?: 0
            val told = store.told(inbox.id)
            // the first look at this inbox, or its server numbered it anew: what is in it counts as told of
            if (told == null || told.uidValidity != inbox.uidValidity) {
                store.tell(inbox.id, Watermark(inbox.uidValidity, highest))
                continue
            }
            if (highest <= told.uid) continue
            val fresh = db.messages().unreadAfter(inbox.id, told.uid, MAX_TOLD)
            store.tell(inbox.id, Watermark(inbox.uidValidity, highest))
            if (fresh.isEmpty()) continue
            when {
                front.shows(inbox.id) -> log.info(TAG, "account $accountId: ${fresh.size} new messages, in front of the user already")
                !allowed -> log.info(TAG, "account $accountId: ${fresh.size} new messages; the system does not let the app tell of them")
                else -> {
                    post(account, fresh, archive)
                    log.info(TAG, "account $accountId: told of ${fresh.size} new messages")
                }
            }
        }
    }

    /** Takes one message's notification down: something was done with it, or the user swept it away. */
    suspend fun cancel(messageId: Long) = lock.withLock {
        manager.cancel(MESSAGE, messageId.toInt())
        retell(gone = setOf(messageId.toInt()))
    }

    /**
     * Takes down the notifications of messages that no longer need telling of (read, moved or
     * deleted since), and has each account's line say what is left.
     */
    suspend fun tidy() = lock.withLock {
        val shown = manager.activeNotifications.filter { it.tag == MESSAGE }
        val unread = if (shown.isEmpty()) emptySet() else db.messages().stillUnread(shown.map { it.id.toLong() }).toSet()
        val gone = shown.filter { it.id.toLong() !in unread }.mapTo(HashSet()) { it.id }
        gone.forEach { manager.cancel(MESSAGE, it) }
        retell(gone)
    }

    /** An account was removed: its notifications, its channel and its inboxes' marks go with it. */
    fun forget(accountId: Long, folderIds: Collection<Long>) {
        val group = group(accountId)
        manager.activeNotifications.filter { it.notification.group == group }.forEach { manager.cancel(it.tag, it.id) }
        manager.deleteNotificationChannel(channelId(accountId))
        store.forget(folderIds)
    }

    private fun post(account: AccountEntity, fresh: List<MessageEntity>, archive: Boolean) {
        ensureChannel(account)
        val group = group(account.id)
        val posted = fresh.mapTo(HashSet()) { it.id.toInt() }
        // what is still shown of this account's mail from earlier looks: the account's line heads that too
        val earlier = manager.activeNotifications.filter { it.tag == MESSAGE && it.notification.group == group && it.id !in posted }
        // the oldest first, so that the newest ends up on top
        for (message in fresh.asReversed()) manager.notify(MESSAGE, message.id.toInt(), one(account, message, archive))
        val lines = fresh.map { line(senderOf(it), subjectOf(it)) } + earlier.sortedByDescending { it.notification.`when` }.map { lineOf(it.notification) }
        manager.notify(SUMMARY, account.id.toInt(), summary(channelId(account.id), group, account.name, account.address, lines, open(0)))
    }

    /** One message: who it is from, its subject, and, opened up, its first lines. */
    private fun one(account: AccountEntity, message: MessageEntity, archive: Boolean): Notification {
        val subject = subjectOf(message)
        val builder = Notification.Builder(context, channelId(account.id))
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(senderOf(message))
            .setContentText(subject)
            .setStyle(Notification.BigTextStyle().bigText(if (message.preview.isBlank()) subject else subject + "\n" + message.preview))
            // whose mail it is, beside the app's name
            .setSubText(account.name)
            .setWhen(message.date)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_EMAIL)
            .setAutoCancel(true)
            .setGroup(group(account.id))
            .setContentIntent(open(message.id))
            // swept away: the account's line is to count one less
            .setDeleteIntent(broadcast(NotifyActionReceiver.DISMISSED, message.id))
            .addAction(action(R.string.notify_read, NotifyActionReceiver.READ, message.id))
        if (archive) builder.addAction(action(R.string.action_archive, NotifyActionReceiver.ARCHIVE, message.id))
        builder.addAction(action(R.string.action_delete, NotifyActionReceiver.DELETE, message.id))
        return builder.build()
    }

    /**
     * An account's line over its messages: how many are shown, and who from. Made from what the
     * notifications themselves say, so that it can be made again when some of them have gone.
     */
    private fun summary(channel: String, group: String, name: String, address: String, lines: List<String>, open: PendingIntent): Notification {
        val style = Notification.InboxStyle().setSummaryText(address)
        lines.take(SUMMARY_LINES).forEach { style.addLine(it) }
        return Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(name)
            .setContentText(countOf(lines.size))
            .setStyle(style)
            .setSubText(name)
            .setCategory(Notification.CATEGORY_EMAIL)
            .setAutoCancel(true)
            .setGroup(group)
            .setGroupSummary(true)
            // the messages make the sound, not their heading as well; and not again when it is made anew
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    /** Some notifications went: each account's line counts what is left of its own, and goes with the last. */
    private fun retell(gone: Set<Int>) {
        val active = manager.activeNotifications
        val left = active.filter { it.tag == MESSAGE && it.id !in gone }.groupBy { it.notification.group }
        for (heading in active.filter { it.tag == SUMMARY }) {
            val was = heading.notification
            val messages = left[was.group]
            if (messages == null) {
                manager.cancel(SUMMARY, heading.id)
                continue
            }
            val lines = messages.sortedByDescending { it.notification.`when` }.map { lineOf(it.notification) }
            // it says what is shown already: left as it is
            if (was.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == countOf(lines.size)) continue
            val name = was.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val address = was.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()
            manager.notify(SUMMARY, heading.id, summary(was.channelId, was.group, name, address, lines, was.contentIntent))
        }
    }

    private fun countOf(messages: Int): String = context.resources.getQuantityString(R.plurals.notify_new_mail, messages, messages)

    private fun line(sender: String, subject: String): String = "$sender  $subject"

    /** A message's line in its account's summary, from the message's own notification. */
    private fun lineOf(notification: Notification): String =
        line(notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(), notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())

    private fun senderOf(message: MessageEntity): String =
        MailAddress.decode(message.fromList).firstOrNull()?.label.orEmpty().ifBlank { context.getString(R.string.notify_unknown_sender) }

    private fun subjectOf(message: MessageEntity): String = message.subject.ifBlank { context.getString(R.string.message_no_subject) }

    /** The account's channel; made the first time it is needed, renamed when the account's name changes. */
    private fun ensureChannel(account: AccountEntity) {
        val channel = NotificationChannel(channelId(account.id), account.name, NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.notify_channel_description, account.address)
        channel.setShowBadge(true)
        // making a channel that exists changes only its name and description: what the user chose for it stays
        manager.createNotificationChannel(channel)
    }

    /** Opens the message [messageId] in a window of the app; 0 for the inbox alone. */
    private fun open(messageId: Long): PendingIntent =
        PendingIntent.getActivity(context, 0, OpenRequest.intent(context, messageId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun action(label: Int, what: String, messageId: Long): Notification.Action =
        Notification.Action.Builder(null as Icon?, context.getString(label), broadcast(what, messageId)).build()

    /** Has the app's receiver told [what] happened to the notification of the message [messageId]. */
    private fun broadcast(what: String, messageId: Long): PendingIntent {
        val intent = Intent(context, NotifyActionReceiver::class.java)
            .setAction(what)
            // what tells two of these apart: extras do not
            .setData(Uri.parse("deskmail://message/$messageId"))
            .putExtra(NotifyActionReceiver.EXTRA_MESSAGE, messageId)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val TAG = "notify"

        /** The tags the app's notifications are posted under: one for each message, one for an account's summary. */
        const val MESSAGE = "message"
        const val SUMMARY = "account"

        /** More new messages than this at once are not each told of: the newest are. */
        const val MAX_TOLD = 12
        private const val SUMMARY_LINES = 6
        private const val TIDY_AFTER_MS = 500L

        fun channelId(accountId: Long): String = "mail-$accountId"

        fun group(accountId: Long): String = "account-$accountId"
    }
}
