package com.deskmail.notify

import com.deskmail.core.log.AppLog
import com.deskmail.data.LiveMail
import com.deskmail.mail.DraftKind
import com.deskmail.mail.MailComposer
import com.deskmail.mail.SendCheck
import kotlinx.coroutines.flow.first

/**
 * What a notification's buttons do to its message. Each is done for good: there is no "Undo" on
 * a notification, so nothing waits out the time to be taken back. A notification the user swept
 * away only has its account's line count one less.
 *
 * @param composer writes and sends the reply typed into a notification.
 * @param tellServers has the servers told of what was done, by work that outlives this call.
 */
class NotifyActions(
    private val mail: LiveMail,
    private val notifier: MailNotifier,
    private val log: AppLog,
    private val composer: MailComposer,
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

    /**
     * Answers the message [messageId] with [text], typed into its notification: a reply as "Reply"
     * begins it (to whom, the subject, the account's signature and the quote), with the text on top,
     * handed over at once. Answered, the message counts as read. False when it could not be sent:
     * what was written then waits in the outbox, and a notification says so.
     */
    suspend fun reply(messageId: Long, text: String): Boolean {
        notifier.cancel(messageId)
        val typed = text.trim()
        if (typed.isEmpty()) return false
        val id = composer.start(DraftKind.Reply, sourceId = messageId)
        if (id == null) {
            log.warn("notify", "reply from a notification: its message is gone")
            return false
        }
        val draft = composer.observe(id).first { it != null }!!
        composer.save(id, draft.accountId, draft.to, draft.cc, draft.bcc, draft.subject, typed + draft.body)
        val check = composer.send(id)
        if (check !is SendCheck.Queued) {
            log.warn("notify", "reply from a notification: not sent ($check); it waits in the outbox")
            notifier.replyFailed(messageId)
            return false
        }
        // no "Undo" on a notification: nothing to wait for
        composer.sendNow(id)
        log.info("notify", "reply from a notification: handed over")
        if (mail.readNow(messageId)) tellServers()
        return true
    }
}
