package com.deskmail.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.deskmail.deskMail
import kotlinx.coroutines.launch

/**
 * The buttons of a new-mail notification: "Read", "Archive", "Delete". What was asked for is
 * done on the device at once and written down before this returns; the server is told by
 * background work that waits for a network if it has to. The app need not be open. A
 * notification that was swept away is heard of here as well.
 */
class NotifyActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val what = intent.action ?: return
        val messageId = intent.getLongExtra(EXTRA_MESSAGE, 0)
        if (messageId == 0L || what !in ACTIONS) return
        val app = context.deskMail
        // the system waits for what is written down, for a few seconds at most
        val waiting = goAsync()
        app.background.launch {
            try {
                app.notifyActions.act(what, messageId)
            } catch (e: Exception) {
                app.log.error("notify", "a notification's button could not be carried out", e)
            } finally {
                waiting.finish()
            }
        }
    }

    companion object {
        const val READ = "com.deskmail.notify.READ"
        const val ARCHIVE = "com.deskmail.notify.ARCHIVE"
        const val DELETE = "com.deskmail.notify.DELETE"

        /** The user swept the notification away: nothing is done to its message. */
        const val DISMISSED = "com.deskmail.notify.DISMISSED"
        const val EXTRA_MESSAGE = "message"

        private val ACTIONS = setOf(READ, ARCHIVE, DELETE, DISMISSED)
    }
}
