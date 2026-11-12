package com.deskmail.core.imap

import com.deskmail.core.mail.RemoteAttachment
import com.deskmail.core.mail.RemoteBody
import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.MultipartDataSource
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimeUtility
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Reads a message's parts: which of them are what it says (plain text, HTML) and which are files.
 *
 * Over IMAP the shape of a message is known from its BODYSTRUCTURE, so walking the parts fetches
 * nothing; only the text that is read here is fetched, part by part. A part's place is written as
 * IMAP numbers it ("2", "1.2"): that is how an attachment is asked for later.
 */
internal object MessageParts {

    /** How many files the message carries that are worth a paperclip: not its text, and not a picture its HTML shows. */
    fun attachmentCount(part: Part): Int {
        if (isMultipart(part)) return children(part).sumOf { attachmentCount(it) }
        if (isText(part)) return 0
        val disposition = disposition(part)
        if (disposition == "attachment") return 1
        // a picture the HTML shows has a content id; what else is not text is a file
        if (contentId(part).isNotEmpty()) return 0
        return if (disposition != "inline" || fileName(part).isNotEmpty()) 1 else 0
    }

    /** The message's text and its files. Text parts larger than [maxTextBytes] are not fetched. */
    fun read(message: Part, maxTextBytes: Int): RemoteBody {
        val collector = Collector(maxTextBytes)
        collector.walk(message, "")
        return RemoteBody(collector.text, collector.html, collector.attachments, collector.tooLarge)
    }

    /**
     * The content of the part at [place] ("2", "1.2": as [read] names the parts), decoded; null
     * when the message has no such part. Over IMAP the content is fetched as the stream is read.
     */
    fun stream(message: Part, place: String): InputStream? {
        var part = message
        if (isMultipart(message)) {
            for (level in place.split('.')) {
                val number = level.toIntOrNull() ?: return null
                if (!isMultipart(part)) return null
                part = children(part).getOrNull(number - 1) ?: return null
            }
        } else if (place != "1") {
            return null
        }
        if (isMultipart(part)) return null
        // The content as it travelled, decoded here: asking the part for its decoded content would
        // send a message inside a message through the library's table of content handlers, a
        // resource file that has to survive the packaging of an Android app.
        val raw = when (part) {
            is MimeBodyPart -> part.rawInputStream
            is MimeMessage -> part.rawInputStream
            else -> return part.inputStream
        }
        val encoding = runCatching { (part as MimePart).encoding }.getOrNull()
        if (encoding.isNullOrBlank()) return raw
        // an encoding nobody knows: the bytes as they are
        return try {
            MimeUtility.decode(raw, encoding)
        } catch (e: MessagingException) {
            raw
        }
    }

    private class Collector(private val maxTextBytes: Int) {
        var text: String? = null
        var html: String? = null
        var tooLarge = false
        val attachments = ArrayList<RemoteAttachment>()

        fun walk(part: Part, path: String) {
            if (isMultipart(part)) {
                children(part).forEachIndexed { index, child ->
                    walk(child, if (path.isEmpty()) "${index + 1}" else "$path.${index + 1}")
                }
                return
            }
            val place = path.ifEmpty { "1" }
            if (isText(part)) {
                val read = readText(part)
                if (read == null) {
                    tooLarge = true
                } else if (baseType(part) == "text/html") {
                    // a second HTML part is rare, and no reader shows two pages
                    if (html == null) html = read
                } else {
                    // a list's footer, a signature added on the way: plain text after plain text
                    text = text?.let { it.trimEnd() + "\n\n" + read } ?: read
                }
                return
            }
            val disposition = disposition(part)
            val contentId = contentId(part)
            attachments += RemoteAttachment(
                part = place,
                name = fileName(part),
                mimeType = baseType(part),
                size = fileSize(part),
                contentId = contentId,
                inline = disposition != "attachment" && (disposition == "inline" || contentId.isNotEmpty()),
            )
        }

        /** The part's text, or null when it is larger than the limit. */
        private fun readText(part: Part): String? {
            if (runCatching { part.size }.getOrDefault(0) > maxTextBytes) return null
            val bytes = part.inputStream.use { it.readNBytes(maxTextBytes + 1) }
            if (bytes.size > maxTextBytes) return null
            val charset = charset(part) ?: (if (baseType(part) == "text/html") declaredCharset(bytes) else null) ?: Charsets.UTF_8
            return String(bytes, charset).replace("\r\n", "\n")
        }
    }

    private fun isMultipart(part: Part): Boolean = baseType(part).startsWith("multipart/")

    /**
     * The parts of a multipart. Over IMAP they are known from the message's structure and handed
     * out as they are: nothing is fetched, and nothing is looked up in the library's table of
     * content handlers (a resource file that has to survive the packaging of an Android app).
     * A message read from a stream goes the library's usual way.
     */
    private fun children(part: Part): List<Part> {
        val source = runCatching { part.dataHandler.dataSource }.getOrNull()
        if (source is MultipartDataSource) return (0 until source.count).map { source.getBodyPart(it) }
        val multipart = runCatching { part.content }.getOrNull() as? Multipart ?: return emptyList()
        return (0 until multipart.count).map { multipart.getBodyPart(it) }
    }

    /** What the message says, as opposed to a file that happens to be text. */
    private fun isText(part: Part): Boolean {
        val type = baseType(part)
        return (type == "text/plain" || type == "text/html") && disposition(part) != "attachment" && fileName(part).isEmpty()
    }

    /** "text/plain", lower case, without parameters; a type nobody can read counts as a file of unknown kind. */
    private fun baseType(part: Part): String =
        runCatching { ContentType(part.contentType).baseType.lowercase() }.getOrDefault("application/octet-stream")

    private fun disposition(part: Part): String = runCatching { part.disposition }.getOrNull().orEmpty().lowercase()

    private fun fileName(part: Part): String {
        val name = runCatching { part.fileName }.getOrNull() ?: return ""
        // a name written as an encoded word where a parameter was expected
        val decoded = if (name.contains("=?")) runCatching { MimeUtility.decodeText(name) }.getOrDefault(name) else name
        return decoded.replace(Regex("[\\r\\n\\t]"), " ").trim()
    }

    private fun fileSize(part: Part): Long {
        val travelling = runCatching { part.size.toLong() }.getOrDefault(0).coerceAtLeast(0)
        val encoding = runCatching { (part as? MimePart)?.encoding }.getOrNull().orEmpty()
        return fileSize(travelling, encoding)
    }

    /**
     * About how large a file is whose part the server counts as [travelling] bytes. In base64, as
     * files travel, 57 bytes are a line of 76 letters and a line break.
     */
    fun fileSize(travelling: Long, encoding: String): Long =
        if (encoding.trim().equals("base64", ignoreCase = true)) travelling * 57 / 78 else travelling

    private fun contentId(part: Part): String {
        val id = runCatching { (part as? MimePart)?.contentID }.getOrNull() ?: return ""
        return id.trim().removePrefix("<").removeSuffix(">")
    }

    /** The charset the part's header names, when Java knows it. */
    private fun charset(part: Part): Charset? {
        val name = runCatching { ContentType(part.contentType).getParameter("charset") }.getOrNull() ?: return null
        return runCatching { Charset.forName(MimeUtility.javaCharset(name)) }.getOrNull()
    }

    /** The charset an HTML page declares for itself in a meta tag near its start. */
    private fun declaredCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val name = META_CHARSET.find(head)?.groupValues?.get(1) ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private val META_CHARSET = Regex("""(?i)<meta[^>]+charset\s*=\s*["']?\s*([A-Za-z0-9_.:-]+)""")
}
