package com.deskmail.notify

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.deskmail.deskMail
import kotlinx.coroutines.launch

/**
 * The buttons of a new-mail notification: "Reply" (with what was typed into it), "Read",
 * "Archive", "Delete". What was asked for is done on the device at once and written down before
 * this returns; the server is told by background work that waits for a network if it has to. The
 * app need not be open. A notification that was swept away is heard of here as well, and so is
 * "Stop waiting" under the reminder of an answer that did not come ([ReplyReminder]).
 */
class NotifyActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val what = intent.action ?: return
        if (what == STOP_AWAITING) {
            stopAwaiting(context, intent.getLongExtra(EXTRA_AWAITING, 0))
            return
        }
        val messageId = intent.getLongExtra(EXTRA_MESSAGE, 0)
        if (messageId == 0L || what !in ACTIONS) return
        val app = context.deskMail
        // the system waits for what is written down, for a few seconds at most
        val waiting = goAsync()
        app.background.launch {
            try {
                if (what == REPLY) {
                    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_TEXT)?.toString().orEmpty()
                    app.notifyActions.reply(messageId, text)
                } else {
                    app.notifyActions.act(what, messageId)
                }
            } catch (e: Exception) {
                app.log.error("notify", "a notification's button could not be carried out", e)
            } finally {
                waiting.finish()
            }
        }
    }

    /** The message an answer was waited for ([com.deskmail.db.AwaitingEntity.id]) is no longer waited for. */
    private fun stopAwaiting(context: Context, id: Long) {
        if (id == 0L) return
        val app = context.deskMail
        val waiting = goAsync()
        app.background.launch {
            try {
                app.replies.stop(id)
            } catch (e: Exception) {
                app.log.error("notify", "the waiting for an answer could not be stopped", e)
            } finally {
                waiting.finish()
            }
        }
    }

    companion object {
        const val REPLY = "com.deskmail.notify.REPLY"
        const val READ = "com.deskmail.notify.READ"
        const val ARCHIVE = "com.deskmail.notify.ARCHIVE"
        const val DELETE = "com.deskmail.notify.DELETE"

        /** The user swept the notification away: nothing is done to its message. */
        const val DISMISSED = "com.deskmail.notify.DISMISSED"
        const val EXTRA_MESSAGE = "message"

        /** "Stop waiting" under a reminder: [EXTRA_AWAITING] names what was waited for. */
        const val STOP_AWAITING = "com.deskmail.notify.STOP_AWAITING"
        const val EXTRA_AWAITING = "awaiting"

        /** Where the system puts what was typed into "Reply". */
        const val REPLY_TEXT = "reply"

        private val ACTIONS = setOf(REPLY, READ, ARCHIVE, DELETE, DISMISSED)
    }
}
