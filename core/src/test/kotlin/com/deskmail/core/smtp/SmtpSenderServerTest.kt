package com.deskmail.core.smtp

import com.deskmail.core.imap.MessageParts
import com.deskmail.core.imap.TestMailServer
import com.deskmail.core.mail.MailAddress
import com.deskmail.core.mail.MailException
import com.deskmail.core.mime.MessageWriter
import com.deskmail.core.mime.OutgoingAttachment
import com.deskmail.core.mime.OutgoingMessage
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.time.Instant

/** Handing a message to a real SMTP server inside the test. */
class SmtpSenderServerTest {

    @get:Rule
    val server = TestMailServer()

    private val pdf = ByteArray(3000) { (it % 251).toByte() }

    private fun message() = OutgoingMessage(
        from = MailAddress("Deniz Aksoy", TestMailServer.ADDRESS),
        to = listOf(MailAddress("Ayşe Yılmaz", "ayse@example.org")),
        cc = listOf(MailAddress("", "elif@example.org")),
        bcc = listOf(MailAddress("", "mehmet@example.org")),
        subject = "Cumartesi kahvaltı?",
        text = "Selam Ayşe,\n\nOlur, geliyorum. Çağrı için teşekkürler.\n",
        messageId = "<yanit-1@example.org>",
        date = Instant.parse("2026-11-12T09:30:00Z"),
        attachments = listOf(OutgoingAttachment("Proje planı v3.pdf", "application/pdf") { ByteArrayInputStream(pdf) }),
    )

    private fun written(message: OutgoingMessage): File =
        File.createTempFile("deskmail-test", ".eml").apply {
            deleteOnExit()
            outputStream().use { MessageWriter.write(message, it) }
        }

    @Test
    fun aMessageIsHandedOverForEverybodyItGoesTo() {
        val message = message()

        SmtpSender.send(server.smtp, server.credentials, TestMailServer.ADDRESS, message.recipients, written(message))

        // one copy for each recipient, the hidden one among them
        val delivered = server.delivered()
        assertEquals(3, delivered.size)
        val copy = delivered.first()
        assertEquals("Cumartesi kahvaltı?", copy.subject)
        assertEquals("Deniz Aksoy", (copy.from.single() as InternetAddress).personal)
        assertEquals("ayse@example.org", (copy.getRecipients(Message.RecipientType.TO).single() as InternetAddress).address)
        assertEquals("<yanit-1@example.org>", copy.messageID)
        // nobody reads who else got it in secret
        assertNull(copy.getHeader("Bcc", null))
        val body = MessageParts.read(copy, 100_000)
        assertEquals("Selam Ayşe,\n\nOlur, geliyorum. Çağrı için teşekkürler.\n", body.text)
        assertEquals(listOf("Proje planı v3.pdf"), body.attachments.map { it.name })
        assertArrayEquals(pdf, MessageParts.stream(copy, body.attachments.single().part)!!.use { it.readBytes() })
    }

    @Test
    fun aServerThatIsNotThereIsANetworkFailure() {
        // a port nobody listens on
        val port = ServerSocket(0).use { it.localPort }
        val message = message()
        try {
            SmtpSender.send(ServerAddress(TestMailServer.LOCALHOST, port, Security.None), server.credentials, TestMailServer.ADDRESS, message.recipients, written(message))
            fail("a message was sent to a server that is not there")
        } catch (e: MailException) {
            assertEquals(MailException.Kind.Network, e.kind)
        }
        assertTrue(server.delivered().isEmpty())
    }

    @Test
    fun aServerWithoutTlsIsRefusedWhereTlsIsAskedFor() {
        val message = message()
        try {
            SmtpSender.send(server.smtp.copy(security = Security.StartTls), server.credentials, TestMailServer.ADDRESS, message.recipients, written(message))
            fail("a message was sent in the clear where TLS was asked for")
        } catch (e: MailException) {
            assertEquals(MailException.Kind.Security, e.kind)
        }
        assertTrue(server.delivered().isEmpty())
    }
}
