package com.deskmail.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.deskmail.MainActivity

/**
 * What a tapped notification asks of a window: the inbox, with the message [messageId] open in
 * it; 0 for the inbox alone (an account's summary was tapped). The unified inbox, because it is
 * always there: on every size of window, and before the folders have been read.
 */
data class OpenRequest(val messageId: Long) {
    companion object {
        const val ACTION = "com.deskmail.OPEN"
        const val EXTRA_MESSAGE = "message"

        /** What [intent] asks for, or null when it asks for nothing of the kind. */
        fun from(intent: Intent?): OpenRequest? {
            if (intent?.action != ACTION) return null
            // a window brought back from the list of recent apps: the tap was acted on when it was made
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            return OpenRequest(intent.getLongExtra(EXTRA_MESSAGE, 0))
        }

        /** Asks a window of the app for the message [messageId]: the one there is, or a new one. */
        fun intent(context: Context, messageId: Long): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION)
                // what tells two of these apart: extras do not
                .setData(Uri.parse("deskmail://open/$messageId"))
                .putExtra(EXTRA_MESSAGE, messageId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
