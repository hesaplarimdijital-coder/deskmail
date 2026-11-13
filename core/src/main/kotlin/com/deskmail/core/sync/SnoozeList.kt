package com.deskmail.core.sync

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * A message put out of sight until [wakeAt]: it waits in its account's Snoozed folder and then goes
 * back to [folder] (where it was; the inbox should that folder be gone), unread.
 *
 * @param messageId its Message-ID, by which it is found again in the Snoozed folder.
 * @param subject for the person who reads the list in a web mail; nothing depends on it.
 */
data class Snooze(val messageId: String, val wakeAt: Instant, val folder: String, val subject: String = "")

/**
 * The list of what an account has snoozed, as it is kept on the server: one message of its own in
 * the account's Snoozed folder, which every device of the user reads and writes. Changed, a new
 * one is put there and the old one taken away.
 *
 * Its text is for both readers: a few lines for a person who opens it in a web mail, then a line
 * for each snoozed message, its fields a tab apart: when it comes back (UTC), its Message-ID, the
 * folder it goes back to, its subject. A line that is none of these is passed over.
 */
object SnoozeList {
    /** The subject the list goes by. */
    const val SUBJECT = "DeskMail ertelemeleri"

    /** The folder DeskMail makes for snoozing where an account has none. */
    const val FOLDER = "Ertelenenler"

    /** How the list's own Message-ID begins: a message in the folder with such an ID is the list, and no snoozed mail. */
    const val ID_PREFIX = "<deskmail-snoozes."

    /** Whether the message with this Message-ID is a list of snoozes. */
    fun isList(messageId: String): Boolean = messageId.startsWith(ID_PREFIX)

    /** A Message-ID for a new list, under the account's own [domain]. */
    fun newMessageId(unique: String, domain: String): String = "$ID_PREFIX$unique@$domain>"

    /** The list's text: what it is, then a line for each snooze, the first to come back first. */
    fun write(snoozes: List<Snooze>): String = buildString {
        append(INTRO)
        append("\n\n")
        for (snooze in snoozes.sortedWith(compareBy({ it.wakeAt }, { it.messageId }))) {
            append(snooze.wakeAt.toString()).append(TAB)
            append(clean(snooze.messageId)).append(TAB)
            append(clean(snooze.folder)).append(TAB)
            append(clean(snooze.subject)).append('\n')
        }
    }

    /** What a list's [text] says; a line it cannot read, and a second line for the same message, are passed over. */
    fun read(text: String): List<Snooze> {
        val seen = HashSet<String>()
        return text.replace("\r\n", "\n").split('\n').mapNotNull { line ->
            val fields = line.split(TAB)
            if (fields.size < 3) return@mapNotNull null
            val wakeAt = try {
                Instant.parse(fields[0].trim())
            } catch (e: DateTimeParseException) {
                return@mapNotNull null
            }
            val messageId = fields[1].trim()
            val folder = fields[2].trim()
            if (!messageId.startsWith("<") || !messageId.endsWith(">") || folder.isEmpty() || !seen.add(messageId)) return@mapNotNull null
            Snooze(messageId, wakeAt, folder, fields.getOrNull(3)?.trim().orEmpty())
        }
    }

    /** A field on a line of its own: no tab or line break may stand in it. */
    private fun clean(text: String) = text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim()

    private const val TAB = '\t'

    private val INTRO = """
        DeskMail bu klasördeki iletileri erteledi; her biri aşağıdaki zamanda (UTC) yazılı klasöre döner.
        Bu iletiyi silme ya da taşıma: DeskMail ertelenen iletileri bununla geri getirir.

        DeskMail snoozed the messages in this folder; each goes back to the folder written below at its time (UTC).
        Do not delete or move this message: DeskMail brings the snoozed messages back with it.
    """.trimIndent()
}
