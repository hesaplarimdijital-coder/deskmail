package com.deskmail.core.imap

import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderRoles
import com.deskmail.core.mail.MailException
import com.deskmail.core.mail.RemoteFolder
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import jakarta.mail.Folder
import jakarta.mail.Session
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import java.io.Closeable
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
) : Closeable {

    /** What the server says it can do, of the things the app asks about. */
    val capabilities: Set<String> = KNOWN_CAPABILITIES.filterTo(LinkedHashSet()) { name ->
        runCatching { store.hasCapability(name) }.getOrDefault(false)
    }

    fun has(capability: String): Boolean = capability.uppercase() in capabilities

    /**
     * Every folder of the account, in the server's order, each with its role
     * ([FolderRoles]: by the server's own attributes where it gives them, by name otherwise).
     */
    fun folders(): List<RemoteFolder> = call("list folders") {
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

    val isConnected: Boolean get() = runCatching { store.isConnected }.getOrDefault(false)

    override fun close() {
        runCatching { store.close() }
    }

    /** Runs [block] against the server; whatever it throws comes out as a [MailException], noted in the log. */
    private inline fun <T> call(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            val failure = MailException.from(e)
            log?.warn(TAG, "${server.host}: $what failed (${failure.kind}): ${failure.message}")
            throw failure
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
            val session = Session.getInstance(properties(server, credentials))
            val store = session.getStore("imap") as IMAPStore
            val secret = when (credentials) {
                is Credentials.Password -> credentials.password
                is Credentials.OAuth -> credentials.accessToken
            }
            try {
                store.connect(server.host, server.port, credentials.username, secret)
            } catch (e: Exception) {
                runCatching { store.close() }
                val failure = MailException.from(e)
                log?.warn(TAG, "${server.host}: sign-in as ${credentials.username} failed (${failure.kind}): ${failure.message}")
                throw failure
            }
            val connection = ImapConnection(store, server, log)
            log?.info(TAG, "${server.host}: signed in as ${credentials.username}; " + connection.capabilities.joinToString(" ").ifEmpty { "no extensions" })
            return connection
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
            // the certificate must be the named host's
            put("mail.imap.ssl.checkserveridentity", "true")
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
