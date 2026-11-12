package com.deskmail.core.imap

import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailException
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket

/** Signing in to a server and listing what it has, against a real IMAP server inside the test. */
class ImapConnectionServerTest {

    @get:Rule
    val server = TestMailServer()

    private fun failure(address: ServerAddress, credentials: Credentials = server.credentials, log: AppLog? = null): MailException =
        try {
            ImapConnection.open(address, credentials, log).close()
            fail("the connection was made")
            throw AssertionError()
        } catch (e: MailException) {
            e
        }

    @Test
    fun signsInAndListsTheFoldersWithTheirRoles() {
        server.createFolder("Sent")
        server.createFolder("Taslaklar")
        server.createFolder("Çöp Kutusu")
        server.createFolder("Projeler")
        server.createFolder("Projeler.2026")
        val log = AppLog()
        ImapConnection.open(server.imap, server.credentials, log).use { connection ->
            assertTrue(connection.isConnected)
            assertTrue(connection.has("IDLE"))
            assertTrue(connection.has("move"))
            assertFalse(connection.has("CONDSTORE"))

            val folders = connection.folders().associateBy { it.path }
            assertEquals(FolderRole.Inbox, folders.getValue("INBOX").role)
            assertEquals(FolderRole.Sent, folders.getValue("Sent").role)
            assertEquals(FolderRole.Drafts, folders.getValue("Taslaklar").role)
            assertEquals(FolderRole.Trash, folders.getValue("Çöp Kutusu").role)
            assertEquals(FolderRole.Other, folders.getValue("Projeler").role)
            val nested = folders.getValue("Projeler.2026")
            assertEquals(listOf("Projeler", "2026"), nested.segments)
            assertTrue(nested.selectable)
        }
        assertTrue(log.lines().any { it.contains("signed in as deniz") })
    }

    @Test
    fun aWrongPasswordIsAnAuthenticationFailureAndStaysOutOfTheLog() {
        val log = AppLog()
        val failure = failure(server.imap, Credentials.Password(TestMailServer.USER, "not-the-password"), log)
        assertEquals(MailException.Kind.Authentication, failure.kind)
        assertTrue(log.lines().any { it.contains("sign-in as deniz failed (Authentication)") })
        assertFalse(log.lines().any { it.contains("not-the-password") })
    }

    @Test
    fun aPortNobodyListensOnIsANetworkFailure() {
        val free = ServerSocket(0).use { it.localPort }
        assertEquals(MailException.Kind.Network, failure(ServerAddress(TestMailServer.LOCALHOST, free, Security.None)).kind)
    }

    /** The test server's certificate is signed by nobody: the connection is refused, not made anyway. */
    @Test
    fun aCertificateNobodyVouchesForIsRefused() {
        assertEquals(MailException.Kind.Security, failure(server.imapTls).kind)
    }

    /** STARTTLS is required where it is chosen: a server without it is not talked to in the clear. */
    @Test
    fun aServerWithoutStartTlsIsNotSpokenToInTheClear() {
        val plain = server.imap
        assertEquals(MailException.Kind.Security, failure(ServerAddress(plain.host, plain.port, Security.StartTls)).kind)
    }
}
