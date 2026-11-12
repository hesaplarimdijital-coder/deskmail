package com.deskmail.core.compose

import com.deskmail.core.mail.MailAddress

/**
 * What a reply and a forwarded message take from the message they come from: the subject, who
 * they go to, the quoted text and the thread's headers. Words that depend on the reader's
 * language (who wrote when) are handed in.
 */
object Replies {

    /** "Re: " before the subject, unless it is a reply's subject already ("Re:", "RE:", "Ynt:", "YNT:", "AW:"). */
    fun replySubject(subject: String): String {
        val clean = subject.trim()
        return if (REPLY_PREFIX.containsMatchIn(clean)) clean else "Re: $clean".trim()
    }

    /** "Fwd: " before the subject, unless it is a forwarded message's subject already ("Fwd:", "Fw:", "İlt:", "WG:"). */
    fun forwardSubject(subject: String): String {
        val clean = subject.trim()
        return if (FORWARD_PREFIX.containsMatchIn(clean)) clean else "Fwd: $clean".trim()
    }

    /** Who a reply goes to. */
    data class Recipients(val to: List<MailAddress>, val cc: List<MailAddress>)

    /**
     * Who a reply to a message goes to.
     *
     * A reply goes to the Reply-To addresses, or to the sender where there are none. A reply to a
     * message the user wrote ([mine] holds his own addresses) goes to whom that message went.
     * With [all], everybody else the message went to gets a copy; the user himself does not.
     */
    fun recipients(
        from: List<MailAddress>,
        replyTo: List<MailAddress>,
        to: List<MailAddress>,
        cc: List<MailAddress>,
        mine: Set<String>,
        all: Boolean,
    ): Recipients {
        val own = mine.mapTo(HashSet()) { it.lowercase() }
        val wroteIt = from.isNotEmpty() && from.all { it.address.lowercase() in own }
        val first = distinct(if (wroteIt) to else replyTo.ifEmpty { from })
        if (!all) return Recipients(first, emptyList())
        val taken = first.mapTo(HashSet()) { it.address.lowercase() }
        val others = distinct((if (wroteIt) cc else to + cc).filter { it.address.lowercase() !in own && it.address.lowercase() !in taken })
        // a reply to everybody of a message that went to nobody else is a plain reply
        return Recipients(first.ifEmpty { others.take(1) }, if (first.isEmpty()) others.drop(1) else others)
    }

    /**
     * The text a reply starts with: an empty line to write in, [attribution] ("12 Kasım'da Ayşe
     * Yılmaz yazdı:"), then the original text with every line marked as quoted.
     */
    fun quote(text: String, attribution: String): String {
        val quoted = text.replace("\r\n", "\n").trimEnd().lines().joinToString("\n") { line ->
            // a line that is quoted already gets one mark more, without a space between the marks
            if (line.startsWith(">")) ">$line" else if (line.isEmpty()) ">" else "> $line"
        }
        return "\n\n$attribution\n$quoted\n"
    }

    /**
     * The text a forwarded message starts with: an empty line to write in, then [banner]
     * ("---------- İletilen ileti ----------"), [details] (who, when, what, to whom: a line each)
     * and the original text as it is.
     */
    fun forward(text: String, banner: String, details: List<String>): String =
        "\n\n$banner\n" + details.joinToString("\n") + "\n\n" + text.replace("\r\n", "\n").trimEnd() + "\n"

    /**
     * The References header of a reply: the thread so far and the message that is answered.
     * A long thread keeps its first message and its latest ones; a header's length has a limit.
     */
    fun references(references: String, messageId: String): String {
        val ids = (references.split(WHITESPACE) + messageId).map { it.trim() }.filter { it.startsWith("<") && it.endsWith(">") }.distinct()
        return (if (ids.size <= MAX_REFERENCES) ids else listOf(ids.first()) + ids.takeLast(MAX_REFERENCES - 1)).joinToString(" ")
    }

    private fun distinct(addresses: List<MailAddress>): List<MailAddress> = addresses.filter { it.address.isNotBlank() }.distinctBy { it.address.lowercase() }

    private const val MAX_REFERENCES = 20
    private val WHITESPACE = Regex("\\s+")
    private val REPLY_PREFIX = Regex("^(re|ynt|yan[ıi]t|aw|sv|antw)\\s*(\\[\\d+])?\\s*:", RegexOption.IGNORE_CASE)
    private val FORWARD_PREFIX = Regex("^(fwd?|[iİ]lt|wg|tr)\\s*:", RegexOption.IGNORE_CASE)
}
