package com.deskmail.core.mail

import java.time.Instant

/** A name and an address, as in "Ayşe Yılmaz <ayse@example.org>"; the name is empty where the mail gave none. */
data class MailAddress(val name: String, val address: String) {

    /** What a list shows for this address: the name, or the address where there is none. */
    val label: String get() = name.ifBlank { address }

    override fun toString(): String = if (name.isBlank()) address else "$name <$address>"

    companion object {
        /**
         * [addresses] as one string for a column of the database: a line per address, the name and
         * the address a tab apart. Neither may hold a tab or a line break, so nothing needs escaping.
         */
        fun encode(addresses: List<MailAddress>): String =
            addresses.joinToString("\n") { clean(it.name) + "\t" + clean(it.address) }

        fun decode(text: String): List<MailAddress> =
            if (text.isEmpty()) emptyList() else text.split('\n').map { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) MailAddress("", line) else MailAddress(line.substring(0, tab), line.substring(tab + 1))
            }

        /** The names (or addresses) as one line: "Deniz Aksoy, Elif Şahin". */
        fun labels(addresses: List<MailAddress>): String = addresses.joinToString(", ") { it.label }

        private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
    }
}

/** The marks a message carries on the server. */
data class MessageFlags(
    val seen: Boolean = false,
    val flagged: Boolean = false,
    val answered: Boolean = false,
    val forwarded: Boolean = false,
    val draft: Boolean = false,
    /** Marked for deletion and not yet removed: the app treats such a message as gone. */
    val deleted: Boolean = false,
)

/** A message's flags as the server has them now. */
data class RemoteFlags(val uid: Long, val flags: MessageFlags)

/**
 * A message as a folder lists it: who, what, when, and its marks; not its text.
 *
 * @param uid the message's number in its folder, lasting as long as the folder's UIDVALIDITY.
 * @param globalId the Message-ID header: the same for every copy of the message, in every folder.
 * @param references the References header as the mail has it, for threads.
 * @param sentAt the Date header, when the mail has a usable one.
 * @param receivedAt when the server received it (INTERNALDATE): what lists are ordered by.
 * @param size the whole message in bytes, as the server counts it.
 * @param hasAttachments whether the message's structure holds a part that is a file.
 */
data class RemoteMessage(
    val uid: Long,
    val globalId: String,
    val inReplyTo: String,
    val references: String,
    val subject: String,
    val from: List<MailAddress>,
    val to: List<MailAddress>,
    val cc: List<MailAddress>,
    val bcc: List<MailAddress>,
    val replyTo: List<MailAddress>,
    val sentAt: Instant?,
    val receivedAt: Instant,
    val size: Long,
    val flags: MessageFlags,
    val hasAttachments: Boolean,
)

/**
 * A file inside a message.
 *
 * @param part where it is in the message, as IMAP numbers the parts ("2", "1.2").
 * @param size its size as it travels (encoded), in bytes; the file itself is some three quarters of that.
 * @param contentId the name the message's HTML refers to it by ("cid:…"), without the angle brackets; empty for none.
 * @param inline shown inside the message rather than offered as a file.
 */
data class RemoteAttachment(
    val part: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val contentId: String,
    val inline: Boolean,
)

/**
 * What a message says: its plain text and its HTML, each when the message has it and it was small
 * enough to fetch, and the files it carries.
 *
 * @param tooLarge a text part was left on the server because it is larger than the limit.
 */
data class RemoteBody(
    val text: String?,
    val html: String?,
    val attachments: List<RemoteAttachment>,
    val tooLarge: Boolean = false,
)
