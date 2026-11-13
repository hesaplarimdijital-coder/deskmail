package com.deskmail.core.sync

import com.deskmail.core.mail.MailAddress
import com.deskmail.core.mail.Mark
import com.deskmail.core.mail.RemoteAccount
import com.deskmail.core.mime.MessageWriter
import com.deskmail.core.mime.OutgoingMessage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The server's side of snoozing, on one account's connection ([SnoozeList] tells what the list is):
 * reading the list from the account's Snoozed folder, putting a new list in the place of the old,
 * and bringing a snoozed message back where it came from.
 */
object SnoozeWork {

    /**
     * The list as the server has it.
     *
     * @param uid the list message's UID in the folder; 0 where there is none.
     * @param snoozes what it says.
     * @param stale older lists still in the folder (a write that was cut short, two devices at once): to go with the next write.
     * @param held the Message-IDs of the snoozed messages the folder holds now.
     */
    data class Listed(val uid: Long, val snoozes: List<Snooze>, val stale: List<Long>, val held: Set<String>)

    /** The list's text is read up to this much: a few hundred snoozes. */
    private const val MAX_LIST_BYTES = 256 * 1024

    /** Reads the list in [folder], and what the folder holds. */
    fun read(remote: RemoteAccount, folder: String): Listed = remote.open(folder).use { box ->
        val headers = box.headers(box.uids(since = null))
        val (lists, snoozed) = headers.partition { SnoozeList.isList(it.globalId) }
        val held = snoozed.mapTo(HashSet()) { it.globalId }
        // the newest list is the one that stands; any other is left over
        val ordered = lists.sortedBy { it.uid }
        val newest = ordered.lastOrNull() ?: return@use Listed(0, emptyList(), emptyList(), held)
        val text = box.body(newest.uid, MAX_LIST_BYTES)?.text.orEmpty()
        Listed(newest.uid, SnoozeList.read(text), ordered.dropLast(1).map { it.uid }, held)
    }

    /**
     * Writes a list saying [snoozes] into [into], as a message of the account's own ([own]) to itself;
     * its Message-ID marks it as the list.
     */
    fun writeList(snoozes: List<Snooze>, own: MailAddress, now: Instant, zone: ZoneId, into: File) {
        val message = OutgoingMessage(
            from = own,
            to = listOf(own),
            subject = SnoozeList.SUBJECT,
            text = SnoozeList.write(snoozes),
            messageId = SnoozeList.newMessageId(UUID.randomUUID().toString(), own.address.substringAfterLast('@').ifBlank { "deskmail.invalid" }),
            date = now,
        )
        into.outputStream().buffered().use { MessageWriter.write(message, it, zone) }
    }

    /**
     * Puts the list in [message] (written by [writeList]) into [folder], read, and takes the lists
     * [old] away: to [trash] where it is given (on Gmail a message taken out of a label is only
     * archived, and the old lists would pile up in "All Mail"), else for good.
     *
     * @return the new list's UID where the server tells it, else 0.
     */
    fun replace(remote: RemoteAccount, folder: String, message: File, old: List<Long>, trash: String?): Long = remote.edit(folder).use { box ->
        val uid = box.append(message, seen = true, draft = false)
        if (old.isNotEmpty()) {
            if (trash != null) box.move(old, trash) else box.expunge(old)
        }
        uid
    }

    /**
     * Brings the snoozed message [messageId] back from [folder] to [target], unread: as new mail.
     * False when the folder no longer holds it (another device brought it back first, or the user
     * moved it elsewhere).
     */
    fun wake(remote: RemoteAccount, folder: String, messageId: String, target: String): Boolean {
        val uids = remote.open(folder).use { it.withMessageId(messageId) }
        if (uids.isEmpty()) return false
        remote.edit(folder).use { box ->
            box.mark(uids, Mark.Seen, false)
            box.move(uids, target)
        }
        return true
    }
}
