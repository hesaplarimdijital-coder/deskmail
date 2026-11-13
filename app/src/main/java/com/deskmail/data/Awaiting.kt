package com.deskmail.data

import com.deskmail.core.mail.MailAddress
import com.deskmail.db.AwaitingEntity
import com.deskmail.db.DeskMailDatabase
import com.deskmail.mail.AwaitedReply
import java.time.Instant

/** A day, in milliseconds: how long an answer is waited for is chosen in days. */
internal const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Whether an answer to the message [globalId] is among the account's messages on the device: one
 * that names it in its In-Reply-To or References and that somebody other than the account itself
 * ([own], its address) sent. The user's own follow-ups answer nothing.
 */
internal suspend fun DeskMailDatabase.answered(accountId: Long, own: String, globalId: String): Boolean =
    messages().answersTo(accountId, globalId).any { from ->
        val sender = MailAddress.decode(from).firstOrNull()?.address.orEmpty()
        sender.isNotEmpty() && !sender.equals(own, ignoreCase = true)
    }

/** Whom a message went to, as one line for a reminder to name: its To, then its Cc. */
internal fun recipientsOf(toList: String, ccList: String): String =
    MailAddress.labels(MailAddress.decode(toList) + MailAddress.decode(ccList))

internal fun AwaitingEntity.toAwaited() = AwaitedReply(
    id = id,
    accountId = accountId,
    messageId = messageId,
    subject = subject,
    recipients = recipients,
    sentAt = Instant.ofEpochMilli(sentAt),
    dueAt = Instant.ofEpochMilli(dueAt),
    told = toldAt != 0L,
)
