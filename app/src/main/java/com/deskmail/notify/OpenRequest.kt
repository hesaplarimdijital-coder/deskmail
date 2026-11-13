package com.deskmail.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.deskmail.MainActivity

/**
 * What a tapped notification asks of a window: the inbox, with the message [messageId] open in
 * it; 0 for the inbox alone (an account's summary was tapped). The unified inbox, because it is
 * always there: on every size of window, and before the folders have been read. A reminder of an
 * answer that did not come asks for "Awaiting replies" instead ([Place.Awaiting]).
 */
data class OpenRequest(val messageId: Long, val place: Place = Place.Inbox) {

    /** Which list the message opens in. */
    enum class Place { Inbox, Awaiting }

    companion object {
        const val ACTION = "com.deskmail.OPEN"
        const val EXTRA_MESSAGE = "message"
        private const val EXTRA_PLACE = "place"
        private const val EXTRA_TOKEN = "token"

        /**
         * What [intent] asks for, or null when it asks for nothing of the kind. The window that is
         * asked can be started by any app; only a request that carries [token], which the app's
         * own notifications do, is taken for one of theirs.
         */
        fun from(intent: Intent?, token: String): OpenRequest? {
            if (intent?.action != ACTION) return null
            // a window brought back from the list of recent apps: the tap was acted on when it was made
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            return try {
                if (intent.getStringExtra(EXTRA_TOKEN) != token) return null
                val place = Place.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_PLACE) } ?: Place.Inbox
                OpenRequest(intent.getLongExtra(EXTRA_MESSAGE, 0), place)
            } catch (e: RuntimeException) {
                // extras that cannot be read: not one of the app's own
                null
            }
        }

        /** Asks a window of the app for the message [messageId] in [place]: the window there is, or a new one. */
        fun intent(context: Context, messageId: Long, token: String, place: Place = Place.Inbox): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION)
                // what tells two of these apart: extras do not
                .setData(Uri.parse(if (place == Place.Inbox) "deskmail://open/$messageId" else "deskmail://open/${place.name.lowercase()}/$messageId"))
                .putExtra(EXTRA_MESSAGE, messageId)
                .putExtra(EXTRA_PLACE, place.name)
                .putExtra(EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
