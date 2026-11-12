package com.deskmail.core.mime

import com.deskmail.core.imap.MailLibrary
import com.deskmail.core.mail.MailAddress
import jakarta.mail.internet.ContentDisposition
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeUtility
import jakarta.mail.internet.ParameterList
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes a message as it travels (RFC 5322 with MIME): the headers, the text as quoted-printable
 * UTF-8, and the files in base64 behind it.
 *
 * The message is written here, byte for byte, and not put together from the mail library's parts:
 * that way goes through the library's table of content handlers, a resource file that has to
 * survive the packaging of an Android app. The library still lends its encoders (encoded words in
 * headers, quoted-printable, base64, parameters), which need no such table. What was written is
 * handed to the server as it is, and the same bytes are kept as the copy in Sent or Drafts.
 */
object MessageWriter {

    /**
     * @param zone the time zone the Date header is written in: the writer's own, as mail programs do.
     * @param withBcc writes the hidden recipients too: for the copy the writer keeps, never for one that is sent on.
     */
    fun write(message: OutgoingMessage, out: OutputStream, zone: ZoneId = ZoneOffset.UTC, withBcc: Boolean = false) {
        MailLibrary.prepare()
        val head = StringBuilder()
        header(head, "Date", DATE.format(message.date.atZone(zone)))
        header(head, "From", addresses(listOf(message.from)))
        if (message.to.isNotEmpty()) header(head, "To", addresses(message.to))
        if (message.cc.isNotEmpty()) header(head, "Cc", addresses(message.cc))
        if (withBcc && message.bcc.isNotEmpty()) header(head, "Bcc", addresses(message.bcc))
        header(head, "Subject", words(message.subject, "Subject".length + 2))
        header(head, "Message-ID", oneLine(message.messageId))
        if (message.inReplyTo.isNotBlank()) header(head, "In-Reply-To", oneLine(message.inReplyTo))
        if (message.references.isNotBlank()) header(head, "References", oneLine(message.references).split(' ').filter { it.isNotEmpty() }.joinToString("\r\n "))
        header(head, "MIME-Version", "1.0")
        out.ascii(head.toString())

        if (message.attachments.isEmpty()) {
            writeText(message.text, out)
            return
        }
        val boundary = boundary()
        out.ascii("Content-Type: multipart/mixed;\r\n boundary=\"$boundary\"\r\n\r\n")
        out.ascii("--$boundary\r\n")
        writeText(message.text, out)
        for (attachment in message.attachments) {
            // The line break before a boundary belongs to the boundary: one more keeps the text's
            // own last line break. (A file's base64 does not mind losing its.)
            out.ascii("\r\n--$boundary\r\n")
            writeFile(attachment, out)
        }
        out.ascii("\r\n--$boundary--\r\n")
    }

    /** The text part: its two headers, an empty line, and the text in lines no server has to break. */
    private fun writeText(text: String, out: OutputStream) {
        out.ascii("Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n")
        // lines end as mail's lines do, and the last one ends too
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n').replace("\n", "\r\n") + "\r\n"
        MimeUtility.encode(KeepOpen(out), "quoted-printable").use { it.write(lines.toByteArray(Charsets.UTF_8)) }
    }

    private fun writeFile(attachment: OutgoingAttachment, out: OutputStream) {
        val name = oneLine(attachment.name).ifEmpty { "file" }
        val type = try {
            ContentType(attachment.mimeType).let { ContentType(it.primaryType, it.subType, null) }
        } catch (e: Exception) {
            ContentType("application", "octet-stream", null)
        }
        // a name that is not plain ASCII is written the way RFC 2231 says (name*=UTF-8''…)
        type.parameterList = ParameterList().apply { set("name", name, "UTF-8") }
        val disposition = ContentDisposition("attachment", ParameterList().apply { set("filename", name, "UTF-8") })
        out.ascii("Content-Type: " + type.toString() + "\r\n")
        out.ascii("Content-Transfer-Encoding: base64\r\n")
        out.ascii("Content-Disposition: " + disposition.toString() + "\r\n\r\n")
        // closing the encoder writes what is left of the last group, and the line's end
        MimeUtility.encode(KeepOpen(out), "base64").use { encoder -> attachment.open().use { it.copyTo(encoder) } }
    }

    private fun header(head: StringBuilder, name: String, value: String) {
        head.append(name).append(": ").append(value).append("\r\n")
    }

    /** Addresses one to a line: a header's line may not be long, and a name in another alphabet makes it so. */
    private fun addresses(list: List<MailAddress>): String = list.joinToString(",\r\n ") { address ->
        val name = oneLine(address.name)
        val plain = oneLine(address.address)
        if (name.isEmpty() || name == plain) plain else InternetAddress(plain, name, "UTF-8").toString()
    }

    /**
     * A header's free text: as it is where it is plain ASCII, as encoded words (RFC 2047) where it
     * is not; folded either way. [used] is how much of the first line the header's name takes.
     */
    private fun words(text: String, used: Int): String {
        val clean = oneLine(text)
        // the encoder leaves its words on one line: folding them is the caller's
        return MimeUtility.fold(used, if (clean.all { it.code in 32..126 }) clean else MimeUtility.encodeText(clean, "UTF-8", "B"))
    }

    /** No line break gets into a header from what somebody typed: a second header could ride in on it. */
    private fun oneLine(text: String): String = text.replace(LINE_BREAKS, " ").trim()

    private fun boundary(): String {
        val bytes = ByteArray(12).also { RANDOM.nextBytes(it) }
        return "=_deskmail_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun OutputStream.ascii(text: String) = write(text.toByteArray(Charsets.US_ASCII))

    /** An encoder is closed to make it write its last bytes; the stream under it goes on. */
    private class KeepOpen(out: OutputStream) : FilterOutputStream(out) {
        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)

        override fun close() = flush()
    }

    private val RANDOM = SecureRandom()
    private val LINE_BREAKS = Regex("[\\r\\n]+")

    /** "Thu, 12 Nov 2026 12:30:00 +0300": English names whatever the device speaks. */
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US)
}
