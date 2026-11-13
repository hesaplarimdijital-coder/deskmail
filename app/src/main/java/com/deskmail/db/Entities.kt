package com.deskmail.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Fts4
import androidx.room3.FtsOptions
import androidx.room3.Index
import androidx.room3.PrimaryKey

// The tables of the app's database. What an enum would be is stored as its name, and read back
// leniently: a value written by a newer version must not stop an older one from starting.

/**
 * A mail account: who it is, where its servers are, and how it is doing.
 *
 * @param address the e-mail address.
 * @param name what the user knows the account by ("Outlook", "İş").
 * @param senderName the name its mail is sent under.
 * @param provider the entry of the provider table the account was set up from; empty for a server set up by hand.
 * @param authKind how it signs in: a password, or OAuth with a provider.
 * @param syncDays how far back messages are kept on the device; 0 for all of them.
 * @param notifyMode how new mail is learnt of (push, every so often, by hand).
 * @param state whether the account works, needs its owner to sign in again, or last failed.
 * @param stateDetail the last failure in the server's words, for the user to read.
 * @param position where the account stands among the others.
 */
@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val name: String,
    val senderName: String,
    val provider: String,
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: String,
    val username: String,
    val authKind: String,
    val syncDays: Int,
    val notifyMode: String,
    val signature: String,
    val state: String,
    val stateDetail: String,
    val position: Int,
    val createdAt: Long,
)

/**
 * What an account signs in with (a password, or OAuth tokens), encrypted with a key that never
 * leaves the device's keystore: [secret] is AES-GCM ciphertext, [iv] its nonce. In a table of its
 * own so that nothing that lists accounts reads it by the way.
 */
@Entity(
    tableName = "credential",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
)
class CredentialEntity(
    @PrimaryKey val accountId: Long,
    val kind: String,
    val iv: ByteArray,
    val secret: ByteArray,
    val updatedAt: Long,
)

/**
 * A folder of an account as the server lists it, and what the last sync knew of it.
 *
 * @param path the folder's full name on the server; [delimiter] separates its levels (empty where folders do not nest).
 * @param role what the folder is for; [roleFixed] when the user said so and the server's word no longer counts.
 * @param selectable whether it holds messages.
 * @param position its place in the server's list.
 * @param uidValidity the server's stamp on the folder's UIDs; when it changes, what is stored of the folder is void. 0 before the first sync.
 * @param uidNext the UID the next message will get, as of the last sync.
 * @param highestModSeq the server's change counter as of the last sync (CONDSTORE); 0 without.
 * @param unread the server's own count of unseen messages, and [total] of all: the whole folder, not only what is on the device.
 * @param syncedAt when the folder was last synced; 0 for never.
 */
@Entity(
    tableName = "folder",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId", "path"], unique = true)],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val path: String,
    val delimiter: String,
    val role: String,
    val roleFixed: Boolean,
    val selectable: Boolean,
    val position: Int,
    val uidValidity: Long,
    val uidNext: Long,
    val highestModSeq: Long,
    val unread: Int,
    val total: Int,
    val syncedAt: Long,
)

/**
 * A message of a folder as lists show it: who, what, when and its marks. The server names it by
 * its folder and [uid]; the app by [id].
 *
 * @param globalId the Message-ID header: the same for every copy of the message, in every folder.
 * @param refs the References header as the mail has it, for threads.
 * @param fromList the senders, and [toList], [ccList], [bccList], [replyToList] likewise: a line per
 *   address, name and address a tab apart ([com.deskmail.core.mail.MailAddress.encode]).
 * @param sentAt the mail's own date in milliseconds, 0 where it has none; [date] is when the server
 *   received it, which is what lists are ordered by.
 * @param attachments how many files it carries.
 * @param preview its first words, once its text has been fetched.
 * @param bodyState 0 while its text has not been fetched, 1 when it is kept ([BodyEntity]), 2 when
 *   there is none to keep (too large, or the server would not hand it over).
 */
@Entity(
    tableName = "message",
    foreignKeys = [ForeignKey(entity = FolderEntity::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["folderId", "uid"], unique = true),
        Index(value = ["folderId", "date"]),
        Index(value = ["accountId"]),
        Index(value = ["globalId"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val folderId: Long,
    val uid: Long,
    val globalId: String,
    val inReplyTo: String,
    val refs: String,
    val subject: String,
    val fromList: String,
    val toList: String,
    val ccList: String,
    val bccList: String,
    val replyToList: String,
    val sentAt: Long,
    val date: Long,
    val size: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    val forwarded: Boolean,
    val draft: Boolean,
    val attachments: Int,
    val preview: String,
    val bodyState: Int,
) {
    companion object {
        const val BODY_MISSING = 0
        const val BODY_KEPT = 1
        const val BODY_NONE = 2
    }
}

/**
 * What a message says: its plain text and, where it has one, its HTML, cleaned before it was
 * stored. [remoteImages] says the HTML asks for pictures from the network.
 */
@Entity(
    tableName = "body",
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
)
data class BodyEntity(
    @PrimaryKey val messageId: Long,
    val text: String,
    val html: String?,
    val remoteImages: Boolean,
    /** The message's header lines as they arrived: what the inspector shows, and where the provider's authentication results are read. */
    val headers: String = "",
)

/**
 * A file inside a message: where it is ([part], as IMAP numbers the parts), what it is, and the
 * name the message's HTML refers to it by ([contentId], empty for none).
 */
@Entity(
    tableName = "attachment",
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["messageId"])],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val part: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val contentId: String,
    val inline: Boolean,
)

/**
 * Something done to a message on the device that its server has not been told of yet. The
 * message shows the change at once; the row is gone as soon as the server has answered for it.
 *
 * @param kind what was done: a mark set or cleared ([SEEN], [FLAGGED]; [value] says which), the
 *   message moved to another folder ([MOVE], to [targetFolderId]) or removed for good ([EXPUNGE]).
 *   A message with a move or a removal waiting is on none of the lists.
 * @param targetFolderId where a moved message goes; 0 for anything else.
 * @param notBefore the server is not told before this time (milliseconds): for as long, what was
 *   done is simply taken back.
 */
@Entity(
    tableName = "pending_op",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["accountId"]), Index(value = ["messageId"])],
)
data class PendingOpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val messageId: Long,
    val kind: String,
    val value: Boolean,
    val targetFolderId: Long,
    val notBefore: Long,
    val createdAt: Long,
) {
    companion object {
        const val SEEN = "Seen"
        const val FLAGGED = "Flagged"

        /** Set when a reply to the message was sent, [FORWARDED] when it was sent on. */
        const val ANSWERED = "Answered"
        const val FORWARDED = "Forwarded"
        const val MOVE = "Move"
        const val EXPUNGE = "Expunge"
    }
}

/**
 * A message the user is writing or has handed over to be sent. It is the device's until the
 * server has taken it; what was sent, and a draft that was put away, are then the server's, as
 * all mail is (Sent, Drafts).
 *
 * @param state [DRAFT] while it is written, [QUEUED] once it was handed over: it is sent when
 *   [sendAfter] has passed (the time to take it back), and is [SENDING] while the server is
 *   being handed it. [FAILED] when the server would not take it; [error] says why in the
 *   server's words.
 * @param toText what was typed into "To", as it was typed; [ccText] and [bccText] likewise. They
 *   are read as addresses when the message is sent.
 * @param messageId the Message-ID the message travels under, the same for every copy of it.
 * @param inReplyTo the Message-ID it answers and [refs] its thread so far; empty for a new thread.
 * @param sourceId the message it answers or forwards, by the app's own id, 0 for none; [sourceKind]
 *   says which ([REPLY], [FORWARD]): that message is marked so once this one is sent.
 * @param draftFolderId where the draft's copy on the server lies and [draftUid] its UID there; 0 for none.
 * @param draftSavedAt what [updatedAt] was when that copy was put there: a draft that was touched
 *   since is ahead of its copy.
 * @param draftUidValidity the server's stamp on the folder's UIDs when the copy was put there:
 *   under another stamp [draftUid] names some other message, and is not acted on.
 * @param awaitDays how many days an answer is waited for once it was sent ([AwaitingEntity]); 0 for none.
 */
@Entity(
    tableName = "outbox",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId"])],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val state: String,
    val toText: String,
    val ccText: String,
    val bccText: String,
    val subject: String,
    val body: String,
    val messageId: String,
    val inReplyTo: String,
    val refs: String,
    val sourceId: Long,
    val sourceKind: String,
    val draftFolderId: Long,
    val draftUid: Long,
    val sendAfter: Long,
    val error: String,
    val createdAt: Long,
    val updatedAt: Long,
    val draftSavedAt: Long = 0,
    val draftUidValidity: Long = 0,
    val awaitDays: Int = 0,
) {
    companion object {
        const val DRAFT = "Draft"
        const val QUEUED = "Queued"

        /** Being handed to the server right now. */
        const val SENDING = "Sending"
        const val FAILED = "Failed"

        const val REPLY = "Reply"
        const val FORWARD = "Forward"
    }
}

/**
 * A message a search of the server found ([query]: [com.deskmail.core.text.SearchQuery.key] of
 * what was typed). A message that was found is not taken off the device by the sync while it is
 * remembered, also when it is older than what the device keeps; after a while ([foundAt]) it is
 * forgotten, and the sync does with it what it does with any other.
 */
@Entity(
    tableName = "search_hit",
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["messageId"]), Index(value = ["query", "messageId"], unique = true)],
)
data class SearchHitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val messageId: Long,
    val foundAt: Long,
)

/**
 * A draft's copy on the server that is of no use any more: its message was sent or thrown away,
 * and with it the row that knew of the copy. It is noted here until the server has been told to
 * remove it, which may be later (no network) than the message went.
 */
@Entity(
    tableName = "draft_removal",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId"])],
)
data class DraftRemovalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val folderId: Long,
    val uid: Long,
    val uidValidity: Long,
)

/**
 * A file that goes with a message that is being written: [path] is its copy in the app's own
 * storage, taken when it was attached, so that it is still there when the message is sent.
 */
@Entity(
    tableName = "outbox_file",
    foreignKeys = [ForeignKey(entity = OutboxEntity::class, parentColumns = ["id"], childColumns = ["outboxId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["outboxId"])],
)
data class OutboxFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val outboxId: Long,
    val name: String,
    val mimeType: String,
    val size: Long,
    val path: String,
)

/**
 * The search index: a message's subject, the people on it and its text, folded
 * ([com.deskmail.core.text.TurkishFold]) so that "sarki" finds "Şarkı". The row's id is the message's.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "message_fts")
data class MessageFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val subject: String,
    val people: String,
    val body: String,
)

/** A text the user keeps to put into a message ("quick text"), in the user's order. */
@Entity(tableName = "template")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val body: String,
    val position: Int,
)

/** A search the user keeps as a folder of its own in the sidebar ([query] as it was typed), in the user's order. */
@Entity(tableName = "saved_search")
data class SavedSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val query: String,
    val position: Int,
)

/**
 * What the user said of a sender, by [address] in lower case: a VIP ([vip]), whose mail has a folder
 * of its own; one whose messages show their remote images without being asked ([images]). A
 * sender with neither is not kept.
 */
@Entity(tableName = "sender")
data class SenderEntity(
    @PrimaryKey val address: String,
    val vip: Boolean,
    val images: Boolean,
)

/**
 * A message of the user's that an answer is waited for ("Awaiting replies"): sent from the account
 * [accountId] under [messageId], its Message-ID. It is remembered until a message that answers it
 * arrives (by its In-Reply-To or References, from somebody else); when [dueAt] passes without one,
 * the user is reminded of it once, at [toldAt].
 *
 * @param recipients whom it went to, as one line, for the reminder to name.
 */
@Entity(
    tableName = "awaiting",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId", "messageId"], unique = true)],
)
data class AwaitingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val messageId: String,
    val subject: String,
    val recipients: String,
    val sentAt: Long,
    val dueAt: Long,
    val toldAt: Long = 0,
)
