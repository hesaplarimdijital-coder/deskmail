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
    /** The name its mail is sent under; empty for the bare address. */
    val senderName: String = "",
    /** What its messages are signed with; empty for nothing. */
    val signature: String = "",
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

/**
 * A file a message carries.
 *
 * @param id the app's own name for it, for fetching it; 0 for one that cannot be fetched (made-up mail).
 * @param mimeType what kind of file its sender says it is ("application/pdf").
 */
data class MailAttachment(val name: String, val bytes: Long, val id: Long = 0, val mimeType: String = "application/octet-stream")

/** What became of fetching a file a message carries. */
sealed interface FetchedFile {
    /** It is on the device, at [file]. */
    data class Ready(val file: java.io.File) : FetchedFile

    /** It could not be fetched; [kind] says why, as far as that is known. */
    data class Failed(val kind: MailException.Kind) : FetchedFile

    /** The message, or the file in it, is no longer there. */
    data object Gone : FetchedFile
}

/**
 * Something that was just done to messages and can still be taken back ([MailSource.undo]).
 *
 * @param messages the messages it was done to.
 * @param folder for [Action.Moved]: where they went.
 * @param changes what the source needs to take it back; nothing a screen reads.
 */
class MailUndo(val action: Action, val messages: List<Long>, val folder: Long = 0, val changes: List<Change> = emptyList()) {
    enum class Action { Archived, Deleted, DeletedForGood, Moved }

    /** A message on its way: the change that is waiting, where the message came [from] and where it goes [to] (0: nowhere, it is removed). */
    class Change(val id: Long, val accountId: Long, val messageId: Long, val from: Long, val to: Long)

    val count: Int get() = messages.size
}

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
    /** It was answered, here or by another mail program; [forwarded] likewise for sending it on. */
    val answered: Boolean = false,
    val forwarded: Boolean = false,
    /**
     * The conversation it belongs to, in its account: the message the conversation began with, as
     * its References (or In-Reply-To) name it. Empty for one that is a conversation of its own.
     */
    val thread: String = "",
    /** How many messages of its conversation a list's row of it stands for ([com.deskmail.ui.mail.Threads]). */
    val threadCount: Int = 1,
)

/** What a message says, as far as the device has it. */
sealed interface MailContent {
    /** Being fetched from the server. */
    data object Loading : MailContent

    /**
     * @param text the message as plain text: shown where HTML cannot be.
     * @param html the message as HTML, cleaned, when it has such a part.
     * @param remoteImages the HTML asks for images from the network, which are not loaded unless the reader says so.
     * @param pictures the pictures the HTML shows in its own place ("cid:…"), by the name it refers
     *   to them by. They are the message's own: fetched from its server when the page asks for them.
     * @param headers its header lines as they arrived; empty for a message whose text was fetched
     *   before they were kept with it ([MailSource.fetchHeaders]).
     */
    data class Ready(
        val text: String,
        val html: String? = null,
        val remoteImages: Boolean = false,
        val attachments: List<MailAttachment> = emptyList(),
        val pictures: Map<String, MailAttachment> = emptyMap(),
        val headers: String = "",
    ) : MailContent

    /** It could not be fetched; [kind] says why, as far as that is known. */
    data class Failed(val kind: MailException.Kind) : MailContent

    /** There is nothing to show: the server kept its text back (too large), or the message is gone. */
    data object Unavailable : MailContent
}

/**
 * What a search of the servers came to: [added] messages the device did not have, and
 * [unreachable] accounts whose server could not be asked (no network, a sign-in it refused).
 */
data class ServerSearch(val added: Int, val unreachable: Int)

/** Which messages a screen lists. */
sealed interface MailView {
    /** The inboxes of every account. */
    data object Inbox : MailView

    /** Everything unread, wherever it is filed (not Spam, not Trash, and not once more from "All Mail"). */
    data object Unread : MailView

    data object Starred : MailView

    data class Folder(val id: Long) : MailView

    /**
     * What the search finds for [query] among the messages the device has, in every account and
     * folder but Spam: every word typed must be in the message (its subject, the people on it or
     * its text), as the beginning of a word. Nothing for a query with nothing to look for.
     */
    data class Search(val query: String) : MailView

    /** The mail of the senders the user called VIPs ([SenderMarks.vip]), in every account's inbox. */
    data object Vip : MailView

    /** A search kept as a folder of its own ([SavedSearch]): what the search finds for its query as it now stands. */
    data class Saved(val id: Long) : MailView
}

/** A search kept as a folder of its own in the sidebar: [query] as it was typed, [name] what the folder is called. */
data class SavedSearch(val id: Long, val name: String, val query: String)

/** What the user said of a sender: a VIP, whose mail has a folder of its own; one whose remote images are shown without being asked. */
data class SenderMarks(val vip: Boolean = false, val images: Boolean = false)
