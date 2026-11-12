package com.deskmail.core.mail

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FolderNotFoundException
import jakarta.mail.MessagingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class MailExceptionTest {

    private fun kind(error: Throwable) = MailException.from(error).kind

    @Test
    fun aRefusedSignInNeedsTheUser() {
        val refused = MailException.from(AuthenticationFailedException("[AUTHENTICATIONFAILED] Invalid credentials (Failure)"))
        assertEquals(MailException.Kind.Authentication, refused.kind)
        assertEquals("[AUTHENTICATIONFAILED] Invalid credentials (Failure)", refused.message)
    }

    @Test
    fun whatCannotBeReachedIsWorthAnotherTry() {
        assertEquals(MailException.Kind.Network, kind(MessagingException("Couldn't connect to host", UnknownHostException("imap.example.org"))))
        assertEquals(MailException.Kind.Network, kind(MessagingException("Couldn't connect", ConnectException("Connection refused"))))
        assertEquals(MailException.Kind.Network, kind(MessagingException("Read timed out", SocketTimeoutException("Read timed out"))))
        // the connection dropped in the middle of an answer
        assertEquals(MailException.Kind.Network, kind(MessagingException("Connection dropped by server?", EOFException())))
    }

    @Test
    fun aCertificateThatIsNotAcceptedIsNotANetworkProblem() {
        val handshake = SSLHandshakeException("PKIX path building failed").apply { initCause(CertificateException("unable to find valid certification path")) }
        assertEquals(MailException.Kind.Security, kind(MessagingException("Could not connect", handshake)))
        assertEquals(MailException.Kind.Security, kind(MessagingException("STARTTLS is required but host does not support STARTTLS")))
    }

    @Test
    fun aServerThatSaysNoIsToldApart() {
        assertEquals(MailException.Kind.Server, kind(MessagingException("A3 NO [NONEXISTENT] Unknown Mailbox: Projeler (Failure)")))
        assertEquals(MailException.Kind.Server, kind(FolderNotFoundException()))
    }

    @Test
    fun theMessageSaysWhatTheCauseKnowsOnce() {
        // the cause adds nothing: its words are in the message already
        val unknown = MailException.from(MessagingException("Couldn't connect to host, port: imap.example.org, 993", UnknownHostException("imap.example.org")))
        assertEquals("Couldn't connect to host, port: imap.example.org, 993", unknown.message)
        // the cause says why
        val refused = MailException.from(MessagingException("Couldn't connect to host", ConnectException("Connection refused")))
        assertEquals("Couldn't connect to host (Connection refused)", refused.message)
        // an exception without words is named
        assertEquals("FolderNotFoundException", MailException.from(FolderNotFoundException()).message)
        // an exception of the app's own passes through
        assertSame(refused, MailException.from(refused))
    }

    @Test
    fun aFaultThatIsNobodysAnswerIsSaidWithItsName() {
        // a part of the library the device cannot run, a slip of the app's own: not the server's words, not the network's
        val missing = MailException.from(NoClassDefFoundError("javax/security/sasl/SaslClient"))
        assertEquals(MailException.Kind.Server, missing.kind)
        assertEquals("NoClassDefFoundError: javax/security/sasl/SaslClient", missing.message)
        assertEquals("IllegalStateException: Not connected", MailException.from(IllegalStateException("Not connected")).message)
        // named once where the name is all there is
        assertEquals("NullPointerException", MailException.from(NullPointerException()).message)
        // what the library says stays as the library said it
        assertEquals("A3 NO Unknown Mailbox", MailException.from(MessagingException("A3 NO Unknown Mailbox")).message)
        assertEquals("Read timed out", MailException.from(SocketTimeoutException("Read timed out")).message)
    }

    @Test
    fun theLogIsToldEverythingThatLedToAFailure() {
        val handshake = SSLHandshakeException("Chain validation failed").apply { initCause(CertificateException("Trust anchor for certification path not found.")) }
        val failed = MessagingException("Unable to check\n  server identity for: imap.example.org", handshake)
        assertEquals(
            "MessagingException: Unable to check server identity for: imap.example.org" +
                " <- SSLHandshakeException: Chain validation failed" +
                " <- CertificateException: Trust anchor for certification path not found.",
            MailException.trail(failed),
        )
        // an exception without words is its name
        assertEquals("MessagingException: Connection dropped by server? <- EOFException", MailException.trail(MessagingException("Connection dropped by server?", EOFException())))
        // a long chain is cut, and so is a long answer
        var deep: Exception = IllegalStateException("0")
        repeat(20) { deep = RuntimeException("${it + 1}", deep) }
        assertEquals(6, MailException.trail(deep).split(" <- ").size)
        assertTrue(MailException.trail(MessagingException("x".repeat(5_000))).length < 300)
    }
}
