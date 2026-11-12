package com.deskmail.core.imap

import com.deskmail.core.net.Credentials
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.icegreen.greenmail.store.MailFolder
import com.icegreen.greenmail.user.GreenMailUser
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import jakarta.mail.Flags
import org.junit.rules.ExternalResource
import java.time.Instant
import java.util.Date

/**
 * A mail server for a test (GreenMail): IMAP, IMAP over TLS with a certificate nobody trusts, and
 * SMTP, each on a free local port, with one account. Folders nest with a dot.
 */
class TestMailServer : ExternalResource() {
    private val greenMail = GreenMail(
        arrayOf(
            ServerSetup(0, LOCALHOST, ServerSetup.PROTOCOL_IMAP),
            ServerSetup(0, LOCALHOST, ServerSetup.PROTOCOL_IMAPS),
            ServerSetup(0, LOCALHOST, ServerSetup.PROTOCOL_SMTP),
        ),
    )

    lateinit var user: GreenMailUser
        private set

    val imap: ServerAddress get() = ServerAddress(LOCALHOST, greenMail.imap.port, Security.None)

    /** The same server behind TLS; its certificate is self-signed. */
    val imapTls: ServerAddress get() = ServerAddress(LOCALHOST, greenMail.imaps.port, Security.Tls)

    val smtp: ServerAddress get() = ServerAddress(LOCALHOST, greenMail.smtp.port, Security.None)

    val credentials: Credentials get() = Credentials.Password(USER, PASSWORD)

    override fun before() {
        greenMail.start()
        user = greenMail.setUser(ADDRESS, USER, PASSWORD)
    }

    override fun after() {
        greenMail.stop()
    }

    /** Makes a folder on the server; "Projeler.2026" nests. */
    fun createFolder(path: String) {
        greenMail.managers.imapHostManager.createMailbox(user, path)
    }

    private fun folder(path: String): MailFolder {
        val host = greenMail.managers.imapHostManager
        return if (path.equals("INBOX", ignoreCase = true)) host.getInbox(user) else host.getFolder(user, path)
    }

    /**
     * Puts one of the sample messages (src/test/resources/mail) into a folder as if it had arrived
     * at [received]; returns its UID.
     */
    fun deliver(sample: String, received: Instant, folder: String = "INBOX", flags: Flags = Flags()): Long {
        val message = javaClass.getResourceAsStream("/mail/$sample.eml").use { GreenMailUtil.newMimeMessage(it) }
        return folder(folder).appendMessage(message, flags, Date.from(received))
    }

    /** Sets or clears a flag, as another mail program would. */
    fun setFlag(uid: Long, flag: Flags.Flag, value: Boolean, folder: String = "INBOX") {
        folder(folder).setFlags(Flags(flag), value, uid, null, false)
    }

    /** Deletes a message for good, as another mail program would. */
    fun expunge(uid: Long, folder: String = "INBOX") {
        setFlag(uid, Flags.Flag.DELETED, true, folder)
        folder(folder).expunge()
    }

    companion object {
        const val LOCALHOST = "127.0.0.1"
        const val ADDRESS = "deniz@example.org"
        const val USER = "deniz"
        const val PASSWORD = "a-test-password"
    }
}
