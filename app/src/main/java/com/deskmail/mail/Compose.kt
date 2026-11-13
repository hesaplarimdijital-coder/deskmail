package com.deskmail.mail

import com.deskmail.core.mail.MailAddress
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.time.Instant

/**
 * How a message that is being written came about. [Edit]: it is a draft that lies in its
 * account's Drafts folder (put there by this device, or written elsewhere) and is taken up to be
 * written on.
 */
enum class DraftKind { New, Reply, ReplyAll, Forward, Edit }

/** Where a message of the user's own stands. */
enum class DraftState {
    /** Being written, or put away to be written on. */
    Writing,

    /** Handed over: it leaves once the time to take it back has passed, or as soon as the server can be reached. */
    Waiting,

    /** The server would not take it. */
    Failed,
}

/** A file that goes with a message being written. */
data class DraftFile(val id: Long, val name: String, val bytes: Long)

/**
 * A message that is being written, is waiting to be sent, or could not be sent.
 *
 * @param to what was typed into "To", as it was typed; [cc] and [bcc] likewise.
 * @param error why it could not be sent, in the server's words.
 */
data class Draft(
    val id: Long,
    val accountId: Long,
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    val files: List<DraftFile> = emptyList(),
    val state: DraftState = DraftState.Writing,
    val error: String = "",
    val updatedAt: Instant = Instant.EPOCH,
    /** How many days an answer is to be waited for once it was sent ([MailComposer.awaitReply]); 0 for none. */
    val awaitDays: Int = 0,
) {
    companion object {
        /**
         * What stands in [error] for a message that was being handed to its server when the app
         * ended: whether the server took it is not known. The screens put it into words.
         */
        const val INTERRUPTED = "interrupted"
    }
}

/** How many days an answer to a message can be waited for: what there is to choose from. */
val AWAIT_DAYS = listOf(1, 2, 3, 7)

/** What a new message starts with when something outside asked for it: a mailto link, something shared. */
data class DraftSeed(val to: String = "", val cc: String = "", val subject: String = "", val body: String = "", val bcc: String = "")

/** What became of handing a message over to be sent. */
sealed interface SendCheck {
    /** It leaves in [undoMs]; until then it can be taken back. */
    data class Queued(val undoMs: Long) : SendCheck

    /** There is nobody to send it to. */
    data object NoRecipients : SendCheck

    /** These pieces of what was typed are no addresses. */
    data class BadAddresses(val pieces: List<String>) : SendCheck

    /** The message, or its account, is no longer there. */
    data object Gone : SendCheck
}

/**
 * Writing and sending: what the compose screen asks for. A message that is being written is kept
 * on the device as it is typed; handed over, it waits a few seconds (the time to take it back)
 * and is then sent in the background, now or when the server can next be reached. The window that
 * shows "Undo" has it sent as soon as that offer is gone ([sendNow]).
 */
interface MailComposer {
    /**
     * Everything of the user's own that has not left yet: what is being written, what waits to
     * be sent and what could not be sent, the latest touched first. Snapshot state.
     */
    val outbox: List<Draft>

    /**
     * Starts a message and returns its id: an empty one from [accountId] (or the first account),
     * or what [kind] takes from the message [sourceId] (the recipients, the subject, the quoted
     * text, a forwarded message's files). Null when there is no account, or the message is gone.
     */
    suspend fun start(kind: DraftKind = DraftKind.New, accountId: Long? = null, sourceId: Long? = null, seed: DraftSeed = DraftSeed()): Long?

    /** The message as it is kept; null once it is gone (sent, or thrown away). */
    fun observe(id: Long): Flow<Draft?>

    /** Keeps what was typed, and returns once it is kept. A message that was handed over meanwhile is left as it is. */
    suspend fun save(id: Long, accountId: Long, to: String, cc: String, bcc: String, subject: String, body: String)

    /** Keeps what was typed without anybody waiting for it: for a screen that is on its way out, and a window that is closing. */
    fun keep(id: Long, accountId: Long, to: String, cc: String, bcc: String, subject: String, body: String)

    /** Adds a file: its content is copied now, so it is still there when the message is sent. False when it could not be read. */
    suspend fun attach(id: Long, name: String, mimeType: String, open: () -> InputStream?): Boolean

    suspend fun detach(fileId: Long)

    /** Hands the message over to be sent, if it can be: it needs somebody to go to. */
    suspend fun send(id: Long): SendCheck

    /** Takes a message back that has not left yet: it is being written again. False when it has left. */
    suspend fun unsend(id: Long): Boolean

    /** Sends a waiting message now rather than when its time is up; a failed one is tried again. */
    fun sendNow(id: Long)

    /** Throws the message away. False when it could not be any more: it is being handed to its server this moment. */
    suspend fun discard(id: Long): Boolean

    /** The addresses the user has had mail with that begin like [typed], the most recent first. */
    fun suggest(typed: String, limit: Int = 5): List<MailAddress>

    /**
     * Has an answer to the message waited for [days] days once it was sent (0: not at all): should
     * none come by then, the user is reminded of it ("Awaiting replies").
     */
    suspend fun awaitReply(id: Long, days: Int) {}

    /** The texts the user keeps to put into a message, in the user's order. Snapshot state. */
    val templates: List<MessageTemplate>

    /** Keeps a text: a new one for [id] 0, else that one changed. Its id. */
    suspend fun saveTemplate(id: Long, name: String, body: String): Long

    suspend fun removeTemplate(id: Long)
}

/** A text the user keeps to put into a message ("quick text"): [name] is what the menu offers it as. */
data class MessageTemplate(val id: Long, val name: String, val body: String)
