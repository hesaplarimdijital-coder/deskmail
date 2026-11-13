package com.deskmail.notify

import com.deskmail.core.log.AppLog
import com.deskmail.data.LiveMail

/**
 * What a notification's buttons do to its message. Each is done for good: there is no "Undo" on
 * a notification, so nothing waits out the time to be taken back. A notification the user swept
 * away only has its account's line count one less.
 *
 * @param tellServers has the servers told of what was done, by work that outlives this call.
 */
class NotifyActions(
    private val mail: LiveMail,
    private val notifier: MailNotifier,
    private val log: AppLog,
    private val tellServers: () -> Unit,
) {
    suspend fun act(what: String, messageId: Long) {
        notifier.cancel(messageId)
        // swept away: its account's line was brought up to date, and the message is left as it is
        if (what == NotifyActionReceiver.DISMISSED) return
        val done = when (what) {
            NotifyActionReceiver.READ -> mail.readNow(messageId)
            NotifyActionReceiver.ARCHIVE -> mail.archiveNow(messageId)
            NotifyActionReceiver.DELETE -> mail.deleteNow(messageId)
            else -> false
        }
        log.info("notify", "${what.substringAfterLast('.')} from a notification: " + if (done) "done" else "nothing to do")
        if (done) tellServers()
    }
}
