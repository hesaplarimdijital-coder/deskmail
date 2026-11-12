package com.deskmail.core.mime

import com.deskmail.core.imap.MessageParts
import com.deskmail.core.mail.MailAddress
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.Properties

/** A message is written as mail travels, and read back as any mail program would read it. */
class MessageWriterTest {

    private val istanbul = ZoneId.of("Europe/Istanbul")

    private fun message(
        subject: String = "Cumartesi kahvaltı?",
        text: String = "Selam Ayşe,\n\nOlur, geliyorum.\n",
        attachments: List<OutgoingAttachment> = emptyList(),
        to: List<MailAddress> = listOf(MailAddress("Ayşe Yılmaz", "ayse@example.org")),
        bcc: List<MailAddress> = emptyList(),
    ) = OutgoingMessage(
        from = MailAddress("Deniz Aksoy", "deniz@example.org"),
        to = to,
        cc = listOf(MailAddress("", "elif@example.org")),
        bcc = bcc,
        subject = subject,
        text = text,
        messageId = "<yanit-1@example.org>",
        date = Instant.parse("2026-11-12T09:30:00Z"),
        inReplyTo = "<kahvalti-1@example.org>",
        references = "<ilk@example.org> <kahvalti-1@example.org>",
        attachments = attachments,
    )

    private fun written(message: OutgoingMessage, withBcc: Boolean = false): ByteArray =
        ByteArrayOutputStream().also { MessageWriter.write(message, it, istanbul, withBcc) }.toByteArray()

    private fun read(raw: ByteArray): MimeMessage = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw))

    private fun file(name: String, type: String, content: ByteArray) = OutgoingAttachment(name, type) { ByteArrayInputStream(content) }

    @Test
    fun theHeadersSayWhoWhatAndWhen() {
        val read = read(written(message()))

        assertEquals("Cumartesi kahvaltı?", read.subject)
        assertEquals(listOf(InternetAddress("deniz@example.org", "Deniz Aksoy", "UTF-8")), read.from.toList())
        assertEquals("Ayşe Yılmaz", (read.getRecipients(Message.RecipientType.TO).single() as InternetAddress).personal)
        assertEquals("ayse@example.org", (read.getRecipients(Message.RecipientType.TO).single() as InternetAddress).address)
        assertEquals("elif@example.org", (read.getRecipients(Message.RecipientType.CC).single() as InternetAddress).address)
        assertEquals("<yanit-1@example.org>", read.messageID)
        assertEquals("<kahvalti-1@example.org>", read.getHeader("In-Reply-To", null))
        assertEquals("<ilk@example.org> <kahvalti-1@example.org>", read.getHeader("References", " ").replace(Regex("\\s+"), " "))
        assertEquals(Instant.parse("2026-11-12T09:30:00Z"), read.sentDate.toInstant())
        // in the writer's own time zone, with English names
        assertEquals("Thu, 12 Nov 2026 12:30:00 +0300", read.getHeader("Date", null))
        assertEquals("1.0", read.getHeader("MIME-Version", null))
    }

    @Test
    fun theTextArrivesAsItWasWritten() {
        val text = "Selam Ayşe,\n\nÇağrı için teşekkürler: ğüşiöç ĞÜŞİÖÇ. Bir satır = bir düşünce.\n\n" +
            "Çok uzun bir satır da var: " + "kahvaltı ".repeat(30).trim() + "\nSondaki boşluk korunur \n.tek nokta\n"
        val raw = written(message(text = text))
        val body = MessageParts.read(read(raw), 100_000)

        assertEquals(text, body.text)
        assertNull(body.html)
        assertTrue(body.attachments.isEmpty())
        // nothing but ASCII travels, in lines no server has to break
        assertTrue(raw.all { it in 0..126 })
        assertTrue(String(raw, Charsets.US_ASCII).split("\r\n").all { it.length <= 78 })
        assertFalse(String(raw, Charsets.US_ASCII).replace("\r\n", "").contains('\n'))
    }

    @Test
    fun filesTravelWithTheirNamesAndTheirBytes() {
        val pdf = ByteArray(5000) { (it % 251).toByte() }
        val photo = ByteArray(100) { it.toByte() }
        val raw = written(
            message(
                attachments = listOf(
                    file("Proje planı v3.pdf", "application/pdf", pdf),
                    file("şiir.txt", "text/plain", "Çağdaş şiir".toByteArray()),
                    file("bilinmeyen", "bu bir tür değil", photo),
                ),
            ),
        )
        val read = read(raw)
        val body = MessageParts.read(read, 100_000)

        assertEquals("Selam Ayşe,\n\nOlur, geliyorum.\n", body.text)
        assertEquals(listOf("Proje planı v3.pdf", "şiir.txt", "bilinmeyen"), body.attachments.map { it.name })
        assertEquals(listOf("application/pdf", "text/plain", "application/octet-stream"), body.attachments.map { it.mimeType })
        assertTrue(body.attachments.none { it.inline })
        assertArrayEquals(pdf, MessageParts.stream(read, body.attachments[0].part)!!.use { it.readBytes() })
        assertEquals("Çağdaş şiir", MessageParts.stream(read, body.attachments[1].part)!!.use { String(it.readBytes()) })
        assertArrayEquals(photo, MessageParts.stream(read, body.attachments[2].part)!!.use { it.readBytes() })
        assertTrue(String(raw, Charsets.US_ASCII).split("\r\n").all { it.length <= 78 })
    }

    @Test
    fun hiddenRecipientsAreWrittenOnlyIntoTheCopyThatIsKept() {
        val hidden = listOf(MailAddress("Mehmet Demir", "mehmet@example.org"))
        val sent = read(written(message(bcc = hidden)))
        val kept = read(written(message(bcc = hidden), withBcc = true))

        assertNull(sent.getHeader("Bcc", null))
        assertEquals("mehmet@example.org", (kept.getRecipients(Message.RecipientType.BCC).single() as InternetAddress).address)
        // the server is told of all of them either way
        assertEquals(listOf("ayse@example.org", "elif@example.org", "mehmet@example.org"), message(bcc = hidden).recipients)
    }

    @Test
    fun aLongSubjectInAnotherAlphabetIsFoldedAndReadWhole() {
        val subject = "Kasım bülteni: yeni gelenler, imza günü ve çağdaş şiir üzerine söyleşi; ayrıca ğüşiöç harflerinin hepsi"
        val raw = written(message(subject = subject))

        assertEquals(subject, read(raw).subject)
        assertTrue(String(raw, Charsets.US_ASCII).split("\r\n").all { it.length <= 78 })
    }

    @Test
    fun manyRecipientsDoNotMakeALongLine() {
        val many = (1..40).map { MailAddress("Alıcı Numara $it", "alici$it@example.org") }
        val raw = written(message(to = many))

        assertEquals(many.map { it.address }, read(raw).getRecipients(Message.RecipientType.TO).map { (it as InternetAddress).address })
        assertEquals("Alıcı Numara 7", (read(raw).getRecipients(Message.RecipientType.TO)[6] as InternetAddress).personal)
        assertTrue(String(raw, Charsets.US_ASCII).split("\r\n").all { it.length <= 78 })
    }

    /** What somebody types into a subject or a name must not become a header of its own. */
    @Test
    fun aLineBreakInWhatWasTypedDoesNotStartAHeader() {
        val raw = written(
            message(
                subject = "Merhaba\r\nBcc: gizli@example.org",
                to = listOf(MailAddress("Ayşe\nX-Kotu: evet", "ayse@example.org")),
            ),
        )
        val read = read(raw)

        assertNull(read.getHeader("Bcc", null))
        assertNull(read.getHeader("X-Kotu", null))
        assertEquals("Merhaba Bcc: gizli@example.org", read.subject)
        assertEquals("Ayşe X-Kotu: evet", (read.getRecipients(Message.RecipientType.TO).single() as InternetAddress).personal)
    }

    @Test
    fun aMessageWithoutTextStillHasABody() {
        val body = MessageParts.read(read(written(message(text = ""))), 100_000)
        assertEquals("", body.text?.trim())
    }
}
