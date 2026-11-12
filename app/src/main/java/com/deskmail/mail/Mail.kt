package com.deskmail.mail

import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailException
import java.time.Instant

/** An account as the screens show it. */
data class MailAccount(
    val id: Long,
    /** The name the user knows the account by: "Outlook", "Gmail", "İş". */
    val name: String,
    val address: String,
    val state: AccountState = AccountState.Ok,
    /** The last failure in the server's words; empty while all is well. */
    val detail: String = "",
    /** Where its mail is fetched from, for the account's page: "imap.gmail.com". */
    val server: String = "",
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

/** A message as the lists and the reader's header show it. What it says is its [MailContent]. */
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
    /** The first words as one line, for the list; empty while the message's text has not been fetched. */
    val preview: String = "",
    val unread: Boolean = false,
    val starred: Boolean = false,
    /** How many files it carries. */
    val attachmentCount: Int = 0,
    /** Written by the user (Sent, Drafts): lists name the recipients instead of the sender. */
    val outgoing: Boolean = false,
    /** Who else it went to ("Cc"), as one line; empty for nobody. */
    val cc: String = "",
)

/** What a message says, as far as the device has it. */
sealed interface MailContent {
    /** Being fetched from the server. */
    data object Loading : MailContent

    /**
     * @param text the message as plain text: shown where HTML cannot be.
     * @param html the message as HTML, cleaned, when it has such a part.
     * @param remoteImages the HTML asks for images from the network, which are not loaded unless the reader says so.
     */
    data class Ready(
        val text: String,
        val html: String? = null,
        val remoteImages: Boolean = false,
        val attachments: List<MailAttachment> = emptyList(),
    ) : MailContent

    /** It could not be fetched; [kind] says why, as far as that is known. */
    data class Failed(val kind: MailException.Kind) : MailContent

    /** There is nothing to show: the server kept its text back (too large), or the message is gone. */
    data object Unavailable : MailContent
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
