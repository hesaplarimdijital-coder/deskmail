package com.deskmail.db

import androidx.room3.Entity
import androidx.room3.ForeignKey
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
