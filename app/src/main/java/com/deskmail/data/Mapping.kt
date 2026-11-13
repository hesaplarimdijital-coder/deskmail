package com.deskmail.data

import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailAddress
import com.deskmail.core.text.TurkishFold
import com.deskmail.db.AccountEntity
import com.deskmail.db.AttachmentEntity
import com.deskmail.db.FolderEntity
import com.deskmail.db.MessageEntity
import com.deskmail.mail.MailAccount
import com.deskmail.mail.MailAttachment
import com.deskmail.mail.MailFolder
import com.deskmail.mail.MailMessage
import java.time.Instant

// What is stored, as the screens show it.

internal fun AccountEntity.toAccount() = MailAccount(id, name, address, accountState(), stateDetail, imapHost, senderName, signature)

internal fun FolderEntity.toFolder() = MailFolder(id, accountId, displayPath(path, delimiter), folderRole(role), unread, total)

/**
 * The levels of a folder's path as the user knows them: without the container the server keeps
 * its own folders in ("[Gmail]/Sent Mail", "INBOX.Projeler"), which says nothing to anybody.
 */
internal fun displayPath(path: String, delimiter: String): List<String> {
    val levels = if (delimiter.isEmpty()) listOf(path) else path.split(delimiter).filter { it.isNotEmpty() }.ifEmpty { listOf(path) }
    return if (levels.size > 1 && levels[0].lowercase() in CONTAINERS) levels.drop(1) else levels
}

private val CONTAINERS = setOf("inbox", "[gmail]", "[google mail]")

/**
 * The folders in the order the screens list them: account by account as they came, and within an
 * account the inbox, the folders with a role, then the user's own by name.
 */
internal fun arrange(folders: List<FolderEntity>): List<MailFolder> {
    val accounts = folders.map { it.accountId }.distinct()
    val shown = folders.map { it.toFolder() }
    return accounts.flatMap { account ->
        shown.filter { it.accountId == account }.sortedWith(compareBy<MailFolder> { rank(it.role) }.thenBy { TurkishFold.fold(it.path.joinToString("/")) })
    }
}

private fun rank(role: FolderRole): Int = when (role) {
    FolderRole.Inbox -> 0
    FolderRole.Drafts -> 1
    FolderRole.Sent -> 2
    FolderRole.Archive -> 3
    FolderRole.All -> 4
    FolderRole.Snoozed -> 5
    FolderRole.Junk -> 6
    FolderRole.Trash -> 7
    FolderRole.Other -> 8
}

/**
 * @param outgoing the message lies in a folder of the user's own writing (Sent, Drafts).
 */
internal fun MessageEntity.toMessage(outgoing: Boolean): MailMessage {
    val from = MailAddress.decode(fromList).firstOrNull()
    return MailMessage(
        id = id,
        accountId = accountId,
        folderId = folderId,
        fromName = from?.label.orEmpty(),
        fromAddress = from?.address.orEmpty(),
        to = MailAddress.labels(MailAddress.decode(toList)),
        subject = subject,
        // when the server received it: what the list is ordered by, whatever the mail says of itself
        sentAt = Instant.ofEpochMilli(date),
        preview = preview,
        unread = !seen,
        starred = flagged,
        attachmentCount = attachments,
        outgoing = outgoing,
        cc = MailAddress.labels(MailAddress.decode(ccList)),
        answered = answered,
        forwarded = forwarded,
        thread = threadOf(accountId, refs, inReplyTo, globalId),
        globalId = globalId,
    )
}

/**
 * The conversation a message belongs to: the message it began with, as the first of its References
 * names it, else the one it answers (In-Reply-To), else the message itself. Within its account
 * ("3 <id@host>"); empty for a message without a Message-ID, which is a conversation of its own.
 */
internal fun threadOf(accountId: Long, refs: String, inReplyTo: String, globalId: String): String {
    val root = refs.trim().split(WHITESPACE).firstOrNull { it.isNotEmpty() } ?: inReplyTo.trim().ifEmpty { globalId.trim() }
    return if (root.isEmpty()) "" else "$accountId $root"
}

private val WHITESPACE = Regex("\\s+")

/**
 * [found] with every message once. Gmail's "All Mail" holds every message of the account once
 * more: a message that is also found where it is filed is shown there, and only a message that
 * lies nowhere else (an archived one) is shown from "All Mail".
 */
internal fun once(found: List<MessageEntity>, folders: List<FolderEntity>): List<MessageEntity> {
    val allMail = folders.filter { folderRole(it.role) == FolderRole.All }.mapTo(HashSet()) { it.id }
    if (allMail.isEmpty()) return found
    val filed = found.filter { it.folderId !in allMail && it.globalId.isNotEmpty() }.mapTo(HashSet()) { it.accountId to it.globalId }
    return found.filter { it.folderId !in allMail || (it.accountId to it.globalId) !in filed }
}

/** The files a message offers: not the pictures its HTML shows in its own place. */
internal fun List<AttachmentEntity>.toFiles(): List<MailAttachment> =
    filter { !it.inline || it.contentId.isEmpty() }.map { it.toAttachment() }

/** The parts the message's HTML can show in its own place ("cid:…"), by the name it refers to them by. */
internal fun List<AttachmentEntity>.toPictures(): Map<String, MailAttachment> =
    filter { it.contentId.isNotEmpty() }.associate { it.contentId to it.toAttachment() }

private fun AttachmentEntity.toAttachment() = MailAttachment(name.ifEmpty { mimeType }, size, id, mimeType)
