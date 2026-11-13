package com.deskmail.core.imap

import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderWatch
import com.deskmail.core.mail.MailException
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.ServerAddress
import jakarta.mail.event.MessageChangedListener
import jakarta.mail.event.MessageCountEvent
import jakarta.mail.event.MessageCountListener
import org.eclipse.angus.mail.imap.IMAPFolder

/**
 * A folder that is waited on: the server says when a message comes, goes or is marked (IMAP IDLE,
 * RFC 2177). The connection is the watch's own and is closed with it.
 *
 * How the mail library waits, which is what the rest leans on:
 * - The wait reads from the server without a time limit of its own: a read that times out with
 *   nothing read is taken for silence, and the wait goes on. A line that went dead is therefore
 *   not found out by waiting. It is found out by [refresh], which asks the server something and
 *   does give up when no answer comes.
 * - The wait ends when another thread asks something of the folder; that is how [refresh] ends
 *   it. The waiting thread then begins anew.
 * - News is handed out on a thread of the library's own, one after another.
 */
class ImapWatch internal constructor(
    private val connection: ImapConnection,
    private val folder: IMAPFolder,
    private val path: String,
    onNews: () -> Unit,
) : FolderWatch {

    @Volatile
    private var closed = false

    init {
        folder.addMessageCountListener(object : MessageCountListener {
            override fun messagesAdded(event: MessageCountEvent) = onNews()
            override fun messagesRemoved(event: MessageCountEvent) = onNews()
        })
        // read, starred or answered somewhere else
        folder.addMessageChangedListener(MessageChangedListener { onNews() })
    }

    override fun await() {
        if (closed) throw gone()
        try {
            folder.idle()
        } catch (e: Exception) {
            // cut by close(): that is no failure, and nothing for the log
            throw if (closed) gone() else connection.failure("the wait on $path", e)
        }
        if (closed) throw gone()
    }

    override fun refresh() {
        if (closed) throw gone()
        connection.call("begin the wait on $path anew") {
            // asking for the folder's connection ends the wait; the answer is the sign of life
            folder.doCommand(IMAPFolder.ProtocolCommand { protocol ->
                protocol.noop()
                null
            })
            Unit
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // The server is not asked to close anything: it may be gone, and the asking would wait
        // for it. The line is cut, and whoever waits on it finds out.
        runCatching { folder.forceClose() }
        connection.close()
    }

    private fun gone() = MailException(MailException.Kind.Network, "the watch on $path was closed")

    companion object {
        /**
         * Signs in to [server] on a connection of the watch's own and begins to watch the folder
         * at [path]. [onNews] is called whenever the server says that a message came, went or was
         * marked there. It is called on a thread of the mail library's: it should pass the word
         * on and return.
         *
         * Null when this server cannot be waited on (it has no IDLE): its mail is then looked for
         * from time to time instead.
         *
         * @throws MailException as [ImapConnection.open] throws it, and when the folder cannot be opened.
         */
        fun open(server: ServerAddress, credentials: Credentials, path: String, log: AppLog? = null, onNews: () -> Unit): ImapWatch? {
            val connection = ImapConnection.open(server, credentials, log)
            val watch = try {
                connection.watch(path, onNews)
            } catch (e: Throwable) {
                connection.close()
                throw e
            }
            if (watch == null) connection.close()
            return watch
        }
    }
}
