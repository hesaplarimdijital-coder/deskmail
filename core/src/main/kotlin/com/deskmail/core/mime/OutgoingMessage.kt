package com.deskmail.core.mime

import com.deskmail.core.mail.MailAddress
import java.io.InputStream
import java.time.Instant

/**
 * A message the user wrote, as [MessageWriter] puts it on the wire.
 *
 * @param messageId the Message-ID header, in angle brackets: the same for the copy that is sent
 *   and the copies that are kept (Sent, Drafts).
 * @param inReplyTo the Message-ID of the message this one answers, [references] the thread so far;
 *   both empty for a message that starts a thread.
 * @param text what it says, as plain text.
 */
data class OutgoingMessage(
    val from: MailAddress,
    val to: List<MailAddress>,
    val cc: List<MailAddress> = emptyList(),
    val bcc: List<MailAddress> = emptyList(),
    val subject: String,
    val text: String,
    val messageId: String,
    val date: Instant,
    val inReplyTo: String = "",
    val references: String = "",
    val attachments: List<OutgoingAttachment> = emptyList(),
) {
    /** Everybody the message goes to, the hidden recipients included, each address once. */
    val recipients: List<String> get() = (to + cc + bcc).map { it.address }.distinctBy { it.lowercase() }
}

/**
 * A file that goes with a message.
 *
 * @param mimeType what kind of file it is ("application/pdf"); anything unreadable is sent as a file of no known kind.
 * @param open the file's content, read once for every copy of the message that is written.
 */
class OutgoingAttachment(val name: String, val mimeType: String, val open: () -> InputStream)
