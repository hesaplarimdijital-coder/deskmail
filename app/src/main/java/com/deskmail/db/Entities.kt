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
