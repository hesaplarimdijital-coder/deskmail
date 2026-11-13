package com.deskmail.core.imap

import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderCounts
import com.deskmail.core.mail.FolderRoles
import com.deskmail.core.mail.MailException
import com.deskmail.core.mail.RemoteAccount
import com.deskmail.core.mail.RemoteFolder
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.net.ServerIdentity
import jakarta.mail.Folder
import jakarta.mail.MessagingException
import jakarta.mail.Session
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.eclipse.angus.mail.imap.ResyncData
import java.io.IOException
import java.util.Properties

/**
 * One signed-in connection to an account's IMAP server, over Angus Mail (the Jakarta Mail
 * implementation). Nothing of that library shows through: the app and the sync see folders and
 * messages as this module's own types, and every failure as a [MailException].
 *
 * Calls block until the server has answered; they belong on a thread that may wait. A connection
 * is used by one caller at a time and closed when that caller is done.
 */
class ImapConnection private constructor(
    private val store: IMAPStore,
    private val server: ServerAddress,
    private val log: AppLog?,
) : RemoteAccount {

    /** What the server says it can do, of the things the app asks about. */
    val capabilities: Set<String> = KNOWN_CAPABILITIES.filterTo(LinkedHashSet()) { name ->
        runCatching { store.hasCapability(name) }.getOrDefault(false)
    }

    fun has(capability: String): Boolean = capability.uppercase() in capabilities

    /**
     * Every folder of the account, in the server's order, each with its role
     * ([FolderRoles]: by the server's own attributes where it gives them, by name otherwise).
     */
    override fun folders(): List<RemoteFolder> = call("list folders") {
        val listed = store.defaultFolder.list("*").map { folder ->
            val imap = folder as IMAPFolder
            RemoteFolder(
                path = imap.fullName,
                delimiter = imap.separator.takeIf { it != NO_DELIMITER },
                attributes = imap.attributes.toSet(),
                selectable = imap.type and Folder.HOLDS_MESSAGES != 0,
            )
        }
        FolderRoles.assign(listed)
    }

    /**
     * Opens the folder at [path] for reading. Close the mailbox before opening another: a server
     * allows an account only so many connections.
     */
    override fun open(path: String): ImapMailbox = call("open $path") {
        val folder = store.getFolder(path) as IMAPFolder
        val condstore = has("CONDSTORE")
        if (condstore) folder.open(Folder.READ_ONLY, ResyncData.CONDSTORE) else folder.open(Folder.READ_ONLY)
        ImapMailbox(this, folder, condstore)
    }

    /**
     * Opens the folder at [path] for changing it (SELECT): marks, moves and removals. Close it
     * before opening another, as with [open].
     */
    override fun edit(path: String): ImapEditor = call("open $path for changes") {
        val folder = store.getFolder(path) as IMAPFolder
        folder.open(Folder.READ_WRITE)
        ImapEditor(this, folder) { target -> store.getFolder(target) as IMAPFolder }
    }

    /**
     * Opens the folder at [path] to be waited on ([ImapWatch]); null when the server cannot do
     * that (no IDLE). The connection is the watch's from then on, and is closed with it.
     */
    internal fun watch(path: String, onNews: () -> Unit): ImapWatch? {
        if (!has("IDLE")) {
            note("$path cannot be waited on: the server has no IDLE")
            return null
        }
        return call("open $path to wait on it") {
            val folder = store.getFolder(path) as IMAPFolder
            folder.open(Folder.READ_ONLY)
            ImapWatch(this, folder, path, onNews)
        }
    }

    /**
     * How many messages the folder at [path] holds and how many of them are unread, without opening
     * it (STATUS); null where the server will not say.
     */
    override fun counts(path: String): FolderCounts? = try {
        val folder = store.getFolder(path) as IMAPFolder
        FolderCounts(total = folder.messageCount.coerceAtLeast(0), unread = folder.unreadMessageCount.coerceAtLeast(0))
    } catch (e: Exception) {
        note("$path: no counts (${MailException.from(e).message})")
        null
    }

    val isConnected: Boolean get() = runCatching { store.isConnected }.getOrDefault(false)

    override fun close() {
        runCatching { store.close() }
    }

    /** Runs [block] against the server; whatever it throws comes out as a [MailException], noted in the log. */
    internal fun <T> call(what: String, block: () -> T): T = guarded(server, log, what, block = block)

    /** [error] as a [MailException], noted in the log: for what is not run through [call]. */
    internal fun failure(what: String, error: Throwable): MailException = if (error is MailException) error else failed(server, log, what, error)

    /** Something worth knowing later that is no failure. */
    internal fun note(text: String) {
        log?.info(TAG, "${server.host}: $text")
    }

    companion object {
        private const val TAG = "imap"

        /** What the library reports for a server whose folders do not nest. */
        private const val NO_DELIMITER = '\u0000'

        /** The capabilities the app's behaviour depends on. */
        val KNOWN_CAPABILITIES = listOf("IDLE", "CONDSTORE", "QRESYNC", "MOVE", "UIDPLUS", "SPECIAL-USE", "X-GM-EXT-1", "ID", "NAMESPACE")

        /** How long a connection attempt and a silent server are waited for. */
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000

        /**
         * Connects to [server] and signs in.
         *
         * @throws MailException with the kind that says what to do about it: [MailException.Kind.Authentication]
         *   when the server refused the sign-in, [MailException.Kind.Security] when its certificate was
         *   not accepted or it has no TLS, [MailException.Kind.Network] when it could not be reached.
         */
        fun open(server: ServerAddress, credentials: Credentials, log: AppLog? = null): ImapConnection {
            val signIn = "sign-in as ${credentials.username}"
            val store = guarded(server, log, signIn) {
                MailLibrary.prepare()
                val session = Session.getInstance(properties(server, credentials))
                // made directly, not looked up by name: the library's list of providers is a resource
                // file, and whether it survives the packaging of an Android app is nothing to depend on
                IMAPStore(session, null)
            }
            val secret = when (credentials) {
                is Credentials.Password -> credentials.password
                is Credentials.OAuth -> credentials.accessToken
            }
            guarded(server, log, signIn, onFailure = { runCatching { store.close() } }) {
                store.connect(server.host, server.port, credentials.username, secret)
            }
            val connection = ImapConnection(store, server, log)
            log?.info(TAG, "${server.host}: signed in as ${credentials.username}; " + connection.capabilities.joinToString(" ").ifEmpty { "no extensions" })
            return connection
        }

        /**
         * Runs [block]; whatever it throws comes out as a [MailException], noted in the log with
         * everything that led to it. That includes a part of the mail library this device cannot
         * run (a [LinkageError]): the user is told, and the app goes on.
         */
        private inline fun <T> guarded(server: ServerAddress, log: AppLog?, what: String, onFailure: () -> Unit = {}, block: () -> T): T =
            try {
                block()
            } catch (e: MailException) {
                // noted where it was first caught
                throw e
            } catch (e: Exception) {
                onFailure()
                throw failed(server, log, what, e)
            } catch (e: LinkageError) {
                onFailure()
                throw failed(server, log, what, e)
            }

        private fun failed(server: ServerAddress, log: AppLog?, what: String, error: Throwable): MailException {
            val failure = MailException.from(error)
            val text = "${server.host}: $what failed (${failure.kind}): ${MailException.trail(error)}"
            // The library and the network fail now and then, and say why. Anything else is a fault:
            // that goes into the log with the place it happened.
            if (error is MessagingException || error is IOException) log?.warn(TAG, text) else log?.error(TAG, text, error)
            return failure
        }

        /** The library's settings for one connection. */
        internal fun properties(server: ServerAddress, credentials: Credentials): Properties = Properties().apply {
            put("mail.store.protocol", "imap")
            put("mail.imap.host", server.host)
            put("mail.imap.port", server.port.toString())
            put("mail.imap.connectiontimeout", CONNECT_TIMEOUT_MS.toString())
            put("mail.imap.timeout", READ_TIMEOUT_MS.toString())
            put("mail.imap.writetimeout", READ_TIMEOUT_MS.toString())
            when (server.security) {
                Security.Tls -> put("mail.imap.ssl.enable", "true")
                Security.StartTls -> {
                    put("mail.imap.starttls.enable", "true")
                    // never fall back to a plain connection
                    put("mail.imap.starttls.required", "true")
                }
                Security.None -> Unit
            }
            // The certificate must be the named host's. The library would leave that to the platform
            // under a name ("LDAPS") that Java's TLS knows and Android's does not, where the check
            // would silently not happen. So it is the app's own, the same everywhere, right after
            // the handshake; that the certificate is signed by a trusted authority stays the platform's.
            put("mail.imap.ssl.checkserveridentity", "false")
            put("mail.imap.ssl.hostnameverifier", ServerIdentity)
            when (credentials) {
                is Credentials.Password -> put("mail.imap.auth.mechanisms", "PLAIN LOGIN")
                is Credentials.OAuth -> put("mail.imap.auth.mechanisms", "XOAUTH2")
            }
            // looking at a message is not reading it: \Seen is set when the app says so
            put("mail.imap.peek", "true")
            // bodies come in pieces of this size
            put("mail.imap.fetchsize", (256 * 1024).toString())
            // lenient with what real mail looks like
            put("mail.mime.address.strict", "false")
        }
    }
}
