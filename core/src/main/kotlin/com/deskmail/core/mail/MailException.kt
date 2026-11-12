package com.deskmail.core.mail

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FolderNotFoundException
import jakarta.mail.MessagingException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Talking to a mail server failed. The [kind] is what the app acts on (ask for the password again,
 * try later, tell the user); the message is the server's or the system's own text, for the log and
 * for the user to read.
 */
class MailException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {

    enum class Kind {
        /** The server refused the user name, the password or the token: the account needs its owner. */
        Authentication,

        /** No network, no such host, a timeout, a dropped connection: worth another try later. */
        Network,

        /** The server's certificate was not accepted, or it has no TLS where TLS is required. */
        Security,

        /** The server answered, and the answer was no: a folder that is gone, a command it refuses. */
        Server,
    }

    companion object {
        /** [error] as a [MailException]: the kind is read from the exception and from what caused it. */
        fun from(error: Throwable): MailException {
            if (error is MailException) return error
            val text = describe(error)
            if (error is AuthenticationFailedException) return MailException(Kind.Authentication, text, error)
            // what the library wraps is what tells the kinds apart
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth < 8) {
                when (cause) {
                    is AuthenticationFailedException -> return MailException(Kind.Authentication, text, error)
                    is CertificateException, is SSLException -> return MailException(Kind.Security, text, error)
                    is UnknownHostException, is ConnectException, is SocketTimeoutException -> return MailException(Kind.Network, text, error)
                    is FolderNotFoundException -> return MailException(Kind.Server, text, error)
                }
                cause = cause.cause?.takeIf { it !== cause }
                depth++
            }
            val root = generateSequence(error) { e -> e.cause?.takeIf { it !== e } }.take(8).last()
            // STARTTLS asked for and not offered: the library says so in words only
            if (error is MessagingException && text.contains("STARTTLS", ignoreCase = true)) return MailException(Kind.Security, text, error)
            if (root is IOException) return MailException(Kind.Network, text, error)
            // Neither the server's words nor the network's: a fault in the app or in the library on
            // this device. Its name is the best there is to go by, so it is said with it.
            val name = error.javaClass.simpleName
            val named = if (error is MessagingException || text.startsWith(name)) text else "$name: $text"
            return MailException(Kind.Server, named, error)
        }

        /**
         * Everything that led to [error], on one line for the log: each exception's name and words,
         * from the one that was caught down to the first cause. The kind and a sentence tell the user
         * what to do; this tells whoever reads the log what happened.
         */
        fun trail(error: Throwable): String =
            generateSequence(error) { e -> e.cause?.takeIf { it !== e } }
                .take(TRAIL_DEPTH)
                .joinToString(" <- ") { e ->
                    val words = e.message?.replace(Regex("\\s+"), " ")?.trim()?.take(TRAIL_WORDS).orEmpty()
                    if (words.isEmpty()) e.javaClass.simpleName else "${e.javaClass.simpleName}: $words"
                }

        private const val TRAIL_DEPTH = 6
        private const val TRAIL_WORDS = 240

        /** The exception's own words, and those of its cause where they say more. */
        private fun describe(error: Throwable): String {
            val own = error.message?.trim().orEmpty()
            val cause = error.cause?.takeIf { it !== error }
            val inner = cause?.message?.trim().orEmpty()
            return when {
                own.isEmpty() && inner.isEmpty() -> error.javaClass.simpleName
                own.isEmpty() -> inner
                inner.isEmpty() || own.contains(inner) -> own
                else -> "$own ($inner)"
            }
        }
    }
}
