package com.deskmail.mail

import com.deskmail.core.mail.FolderRole
import java.time.Instant

/** An account as the screens show it. */
data class MailAccount(
    val id: Long,
    /** The name the user knows the account by: "Outlook", "Gmail", "İş". */
    val name: String,
    val address: String,
)

/** A folder of an account as the screens show it. */
data class MailFolder(
    val id: Long,
    val accountId: Long,
    /** The folder's place in the account's tree, outermost first: "Projeler", "2026". */
    val path: List<String>,
    val role: FolderRole,
    val unread: Int = 0,
    val total: Int = 0,
) {
    val name: String get() = path.last()

    /** 0 for a top-level folder. */
    val depth: Int get() = path.size - 1
}

data class MailAttachment(val name: String, val bytes: Long)

/** A message as the list and the reader show it. */
data class MailMessage(
    val id: Long,
    val accountId: Long,
    val folderId: Long,
    val fromName: String,
    val fromAddress: String,
    /** The recipients as one line: "Deniz Aksoy, Elif Şahin". */
    val to: String,
    val subject: String,
    val sentAt: Instant,
    /** The message as plain text: what the list previews, and what is shown where HTML cannot be. */
    val text: String,
    /** The message as HTML, when it has such a part. */
    val html: String? = null,
    val unread: Boolean = false,
    val starred: Boolean = false,
    val attachments: List<MailAttachment> = emptyList(),
    /** The HTML asks for images from the network, which are not loaded unless the reader says so. */
    val remoteImages: Boolean = false,
    /** Written by the user (Sent, Drafts): lists name the recipients instead of the sender. */
    val outgoing: Boolean = false,
) {
    /** The first lines as one line, for the list. */
    val preview: String get() = text.trim().replace(WHITESPACE, " ").take(PREVIEW_LENGTH)

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val PREVIEW_LENGTH = 140
    }
}

/** Which messages a screen lists. */
sealed interface MailView {
    /** The inboxes of every account. */
    data object Inbox : MailView

    /** Everything unread, wherever it is filed (not Spam, not Trash, and not once more from "All Mail"). */
    data object Unread : MailView

    data object Starred : MailView

    data class Folder(val id: Long) : MailView
}
