package com.deskmail.core.imap

import com.deskmail.core.mail.EditableMailbox
import com.deskmail.core.mail.Mark
import com.deskmail.core.mail.Moved
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.util.SharedFileInputStream
import org.eclipse.angus.mail.imap.AppendUID
import org.eclipse.angus.mail.imap.IMAPFolder
import java.io.File

/**
 * A folder of an [ImapConnection], opened for changing it (SELECT): the marks, moves and removals
 * the user made on the device. Calls block; failures come out as
 * [com.deskmail.core.mail.MailException].
 *
 * @param folderAt the folder at a path on the same connection: where a message is moved to.
 */
class ImapEditor internal constructor(
    private val connection: ImapConnection,
    private val folder: IMAPFolder,
    private val folderAt: (String) -> IMAPFolder,
) : EditableMailbox {

    override val uidValidity: Long = connection.call("read UIDVALIDITY") { folder.uidValidity }

    override fun mark(uids: List<Long>, mark: Mark, value: Boolean) = connection.call("mark") {
        // a keyword is kept only by a server that keeps keywords (Gmail does, Outlook.com does not): one that does not is not asked
        if (mark == Mark.Forwarded && !keepsForwarded()) return@call
        val messages = present(uids)
        if (messages.isNotEmpty()) folder.setFlags(messages, flags(mark), value)
    }

    /** Whether the folder keeps the keyword for a forwarded message: any keyword at all, or this one by name. */
    private fun keepsForwarded(): Boolean {
        val kept = folder.permanentFlags ?: return false
        return kept.contains(Flags.Flag.USER) || kept.contains(FORWARDED)
    }

    override fun move(uids: List<Long>, target: String): List<Moved> = connection.call("move to $target") {
        val messages = present(uids)
        if (messages.isEmpty()) return@call emptyList()
        // read before they go: afterwards the folder no longer knows them
        val source = messages.map { folder.getUID(it) }
        val destination = folderAt(target)
        val uidPlus = connection.has("UIDPLUS")
        val arrived: Array<AppendUID?>? = when {
            connection.has("MOVE") && uidPlus -> folder.moveUIDMessages(messages, destination)
            connection.has("MOVE") -> {
                folder.moveMessages(messages, destination)
                null
            }
            else -> {
                // the long way round: a copy there, then gone from here
                val copied: Array<AppendUID?>? = if (uidPlus) {
                    folder.copyUIDMessages(messages, destination)
                } else {
                    folder.copyMessages(messages, destination)
                    null
                }
                remove(messages, uidPlus)
                copied
            }
        }
        source.mapIndexed { index, uid ->
            val there = arrived?.getOrNull(index)
            // both or neither: a UID means nothing without the stamp it belongs to
            if (there == null || there.uid <= 0 || there.uidvalidity <= 0) Moved(uid) else Moved(uid, there.uid, there.uidvalidity)
        }
    }

    override fun expunge(uids: List<Long>) = connection.call("expunge") {
        val messages = present(uids)
        if (messages.isNotEmpty()) remove(messages, connection.has("UIDPLUS"))
    }

    override val appendsWithUid: Boolean get() = connection.has("UIDPLUS")

    override fun append(message: File, seen: Boolean, draft: Boolean): Long = connection.call("append") {
        // read from the file as it is sent: the library asks for it twice, once to count its bytes
        SharedFileInputStream(message).use { stream ->
            val copy = MimeMessage(null as Session?, stream)
            val flags = Flags()
            if (seen) flags.add(Flags.Flag.SEEN)
            if (draft) flags.add(Flags.Flag.DRAFT)
            copy.setFlags(flags, true)
            if (connection.has("UIDPLUS")) {
                val appended: Array<AppendUID?>? = folder.appendUIDMessages(arrayOf<Message>(copy))
                appended?.firstOrNull()?.uid?.coerceAtLeast(0) ?: 0
            } else {
                folder.appendMessages(arrayOf<Message>(copy))
                0
            }
        }
    }

    override fun close() {
        // nothing is removed in passing: what is removed is removed by name
        runCatching { folder.close(false) }
    }

    /**
     * Removes [messages] for good: these and no others where the server can be told which
     * (UIDPLUS). A server that cannot removes everything in the folder that is marked as deleted,
     * as every mail program does there.
     */
    private fun remove(messages: Array<Message>, uidPlus: Boolean) {
        folder.setFlags(messages, Flags(Flags.Flag.DELETED), true)
        if (uidPlus) folder.expunge(messages) else folder.expunge()
    }

    /** The messages with these UIDs that the folder has. */
    private fun present(uids: List<Long>): Array<Message> =
        folder.getMessagesByUID(uids.toLongArray()).orEmpty().filterNotNull().toTypedArray()

    private fun flags(mark: Mark): Flags = when (mark) {
        Mark.Seen -> Flags(Flags.Flag.SEEN)
        Mark.Flagged -> Flags(Flags.Flag.FLAGGED)
        Mark.Answered -> Flags(Flags.Flag.ANSWERED)
        // no flag of the protocol's own: the keyword mail programs agree on (RFC 5788)
        Mark.Forwarded -> Flags(FORWARDED)
    }

    private companion object {
        const val FORWARDED = "\$Forwarded"
    }
}
