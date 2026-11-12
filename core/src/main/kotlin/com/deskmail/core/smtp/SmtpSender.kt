package com.deskmail.core.smtp

import com.deskmail.core.imap.MailLibrary
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.MailException
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.net.ServerIdentity
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.URLName
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.util.SharedFileInputStream
import org.eclipse.angus.mail.smtp.SMTPTransport
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * Hands a message to an account's outgoing server (SMTP), over Angus Mail. Nothing of that library
 * shows through: a failure comes out as a [MailException] whose kind says what to do about it.
 *
 * The call blocks until the server has taken the message or said no; it belongs on a thread that
 * may wait.
 */
object SmtpSender {
    private const val TAG = "smtp"

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000

    /**
     * What the app calls itself when the server asks who is there: an address that says nothing.
     * The device's own name or address in the network is nobody's business, and "localhost" is
     * turned away by some servers.
     */
    private const val HELLO = "[127.0.0.1]"

    /**
     * @param from the address the message is sent from (the envelope's sender).
     * @param recipients every address the message goes to, the hidden ones (Bcc) among them.
     * @param message the whole message as it travels ([com.deskmail.core.mime.MessageWriter]). A
     *   Bcc header in it is not sent on.
     * @throws MailException [MailException.Kind.Authentication] when the server refused the
     *   sign-in, [MailException.Kind.Security] for its certificate or missing TLS,
     *   [MailException.Kind.Network] when it could not be reached, [MailException.Kind.Server]
     *   when it would not take the message (a recipient it refuses, a message too large).
     */
    fun send(server: ServerAddress, credentials: Credentials, from: String, recipients: List<String>, message: File, log: AppLog? = null) {
        val what = "send as ${credentials.username}"
        try {
            MailLibrary.prepare()
            val session = Session.getInstance(properties(server, credentials, from))
            // made directly, not looked up by name: see ImapConnection.open
            val transport = SMTPTransport(session, URLName("smtp", server.host, server.port, null, credentials.username, null))
            val secret = when (credentials) {
                is Credentials.Password -> credentials.password
                is Credentials.OAuth -> credentials.accessToken
            }
            try {
                transport.connect(server.host, server.port, credentials.username, secret)
                // read from the file as it is sent: a message with large files is never whole in memory
                SharedFileInputStream(message).use { stream ->
                    transport.sendMessage(MimeMessage(session, stream), recipients.map { InternetAddress(it) }.toTypedArray())
                }
            } finally {
                runCatching { transport.close() }
            }
            log?.info(TAG, "${server.host}: took a message for ${recipients.size} recipients")
        } catch (e: MailException) {
            throw e
        } catch (e: Exception) {
            throw failed(server, log, what, e)
        } catch (e: LinkageError) {
            // a part of the mail library this device cannot run: said, not died of
            throw failed(server, log, what, e)
        }
    }

    private fun failed(server: ServerAddress, log: AppLog?, what: String, error: Throwable): MailException {
        val failure = MailException.from(error)
        val text = "${server.host}: $what failed (${failure.kind}): ${MailException.trail(error)}"
        if (error is MessagingException || error is IOException) log?.warn(TAG, text) else log?.error(TAG, text, error)
        return failure
    }

    /** The library's settings for one connection. */
    internal fun properties(server: ServerAddress, credentials: Credentials, from: String): Properties = Properties().apply {
        put("mail.transport.protocol", "smtp")
        put("mail.smtp.host", server.host)
        put("mail.smtp.port", server.port.toString())
        put("mail.smtp.from", from)
        put("mail.smtp.localhost", HELLO)
        put("mail.smtp.connectiontimeout", CONNECT_TIMEOUT_MS.toString())
        put("mail.smtp.timeout", READ_TIMEOUT_MS.toString())
        put("mail.smtp.writetimeout", READ_TIMEOUT_MS.toString())
        when (server.security) {
            Security.Tls -> put("mail.smtp.ssl.enable", "true")
            Security.StartTls -> {
                put("mail.smtp.starttls.enable", "true")
                // never fall back to a plain connection
                put("mail.smtp.starttls.required", "true")
            }
            Security.None -> Unit
        }
        // the app's own check of the server's name, as for IMAP (ImapConnection.properties)
        put("mail.smtp.ssl.checkserveridentity", "false")
        put("mail.smtp.ssl.hostnameverifier", ServerIdentity)
        put("mail.smtp.auth", "true")
        when (credentials) {
            is Credentials.Password -> put("mail.smtp.auth.mechanisms", "PLAIN LOGIN")
            is Credentials.OAuth -> put("mail.smtp.auth.mechanisms", "XOAUTH2")
        }
        // a recipient the server refuses fails the whole message: nothing is sent to some and not to others
        put("mail.smtp.sendpartial", "false")
        // lenient with what real addresses look like
        put("mail.mime.address.strict", "false")
    }
}
