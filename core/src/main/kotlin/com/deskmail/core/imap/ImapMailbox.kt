package com.deskmail.core.imap

import com.deskmail.core.mail.MailAddress
import com.deskmail.core.mail.MessageFlags
import com.deskmail.core.mail.OpenMailbox
import com.deskmail.core.mail.RemoteBody
import com.deskmail.core.mail.RemoteFlags
import com.deskmail.core.mail.RemoteMessage
import com.deskmail.core.text.SearchField
import com.deskmail.core.text.SearchTerm
import jakarta.mail.Address
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeUtility
import org.eclipse.angus.mail.iap.Argument
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPMessage
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol
import org.eclipse.angus.mail.imap.protocol.IMAPResponse
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A folder of an [ImapConnection], opened for reading (EXAMINE): nothing the sync does here changes
 * a flag on the server. Calls block; failures come out as [com.deskmail.core.mail.MailException].
 */
class ImapMailbox internal constructor(
    private val connection: ImapConnection,
    private val folder: IMAPFolder,
    private val condstore: Boolean,
) : OpenMailbox {

    override val uidValidity: Long = connection.call("read UIDVALIDITY") { folder.uidValidity }

    override val uidNext: Long = connection.call("read UIDNEXT") { folder.uidNext.coerceAtLeast(0) }

    override val highestModSeq: Long = if (!condstore) 0 else connection.call("read HIGHESTMODSEQ") { folder.highestModSeq.coerceAtLeast(0) }

    override val total: Int get() = connection.call("count") { folder.messageCount.coerceAtLeast(0) }

    override val unread: Int get() = connection.call("count unread") { folder.unreadMessageCount.coerceAtLeast(0) }

    override fun uids(since: LocalDate?): List<Long> = connection.call("search") {
        val criteria = if (since == null) "ALL" else "SINCE " + IMAP_DATE.format(since)
        val found = folder.doCommand(IMAPFolder.ProtocolCommand { protocol -> uidSearch(protocol, criteria, null) }) as LongArray
        found.sorted()
    }

    override fun search(terms: List<SearchTerm>): List<Long> = connection.call("search the server") {
        if (terms.isEmpty()) return@call emptyList()
        // what is not plain ASCII is sent as UTF-8, and the server is told so
        val utf8 = terms.any { term -> term.typed.any { it.code > ASCII } }
        val args = Argument()
        if (utf8) args.writeAtom("CHARSET").writeAtom("UTF-8")
        for (term in terms) {
            when (term.field) {
                // TEXT: the headers and the text of the message
                SearchField.Any -> args.writeAtom("TEXT").writeString(term.typed, Charsets.UTF_8)
                SearchField.From -> args.writeAtom("FROM").writeString(term.typed, Charsets.UTF_8)
                SearchField.To -> args.writeAtom("OR").writeAtom("TO").writeString(term.typed, Charsets.UTF_8).writeAtom("CC").writeString(term.typed, Charsets.UTF_8)
                SearchField.Subject -> args.writeAtom("SUBJECT").writeString(term.typed, Charsets.UTF_8)
            }
        }
        // marked for removal, and not yet removed: as good as gone
        args.writeAtom("NOT").writeAtom("DELETED")
        // Asked of the server and nowhere else: the mail library would look through every message
        // of the folder itself where the server says no to a search, and that is never wanted here.
        val found = folder.doCommand(IMAPFolder.ProtocolCommand { protocol -> uidSearch(protocol, "", args) }) as LongArray
        found.sorted()
    }

    override fun headers(uids: List<Long>): List<RemoteMessage> = connection.call("fetch headers") {
        val out = ArrayList<RemoteMessage>(uids.size)
        for (batch in uids.chunked(HEADER_BATCH)) {
            val messages = present(folder.getMessagesByUID(batch.toLongArray()))
            if (messages.isEmpty()) continue
            folder.fetch(messages, HEADER_PROFILE)
            for (message in messages) {
                // one message the library cannot read must not cost the folder its sync
                try {
                    out += header(message as IMAPMessage)
                } catch (e: Exception) {
                    connection.note("a message's header could not be read and is left out: $e")
                }
            }
        }
        out
    }

    override fun flags(uids: List<Long>): List<RemoteFlags> = connection.call("fetch flags") {
        val out = ArrayList<RemoteFlags>(uids.size)
        for (batch in uids.chunked(FLAG_BATCH)) {
            val messages = present(folder.getMessagesByUID(batch.toLongArray()))
            if (messages.isEmpty()) continue
            folder.fetch(messages, FLAG_PROFILE)
            messages.mapTo(out) { RemoteFlags(folder.getUID(it), flags(it.flags)) }
        }
        out
    }

    override fun flagsChangedSince(modSeq: Long): List<RemoteFlags>? {
        if (!condstore) return null
        return connection.call("fetch changed flags") {
            val messages = present(folder.getMessagesByUIDChangedSince(1, UIDFolder.LASTUID, modSeq))
            if (messages.isNotEmpty()) folder.fetch(messages, FLAG_PROFILE)
            messages.map { RemoteFlags(folder.getUID(it), flags(it.flags)) }
        }
    }

    override fun body(uid: Long, maxTextBytes: Int): RemoteBody? = connection.call("fetch body") {
        val message = folder.getMessageByUID(uid) as IMAPMessage? ?: return@call null
        MessageParts.read(message, maxTextBytes)
    }

    override fun attachment(uid: Long, part: String, into: OutputStream): Boolean = connection.call("fetch attachment") {
        val message = folder.getMessageByUID(uid) as IMAPMessage? ?: return@call false
        val content = MessageParts.stream(message, part) ?: return@call false
        // fetched from the server a piece at a time as it is read
        content.use { it.copyTo(into) }
        true
    }

    override fun close() {
        runCatching { folder.close(false) }
    }

    private fun header(message: IMAPMessage): RemoteMessage {
        val received = message.receivedDate ?: message.sentDate
        return RemoteMessage(
            uid = folder.getUID(message),
            globalId = message.messageID.orEmpty().trim(),
            inReplyTo = message.inReplyTo.orEmpty().trim(),
            references = message.getHeader("References", " ").orEmpty().replace(WHITESPACE, " ").trim(),
            subject = subject(message),
            from = addresses(runCatching { message.from }.getOrNull()),
            to = addresses(runCatching { message.getRecipients(Message.RecipientType.TO) }.getOrNull()),
            cc = addresses(runCatching { message.getRecipients(Message.RecipientType.CC) }.getOrNull()),
            bcc = addresses(runCatching { message.getRecipients(Message.RecipientType.BCC) }.getOrNull()),
            replyTo = addresses(runCatching { message.replyTo }.getOrNull()),
            sentAt = message.sentDate?.toInstant(),
            receivedAt = received?.toInstant() ?: Instant.EPOCH,
            size = message.sizeLong.coerceAtLeast(0),
            flags = flags(message.flags),
            attachments = runCatching { MessageParts.attachmentCount(message) }.getOrDefault(0),
        )
    }

    /** The subject with its encoded words read; a subject the library cannot decode is shown as it travelled. */
    private fun subject(message: IMAPMessage): String {
        val subject = runCatching { message.subject }.getOrNull() ?: return ""
        return subject.replace(WHITESPACE, " ").trim()
    }

    companion object {
        private const val HEADER_BATCH = 100
        private const val FLAG_BATCH = 500

        /** The last character that is plain ASCII. */
        private const val ASCII = 127

        private val WHITESPACE = Regex("\\s+")

        /** IMAP writes a date as 1-Nov-2026, in English. */
        private val IMAP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH)

        /** What a list needs of a message: who and what, its marks, and the shape of its parts. */
        private val HEADER_PROFILE = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(FetchProfile.Item.FLAGS)
            add(FetchProfile.Item.CONTENT_INFO)
            add(UIDFolder.FetchProfileItem.UID)
            add("References")
        }

        private val FLAG_PROFILE = FetchProfile().apply {
            add(FetchProfile.Item.FLAGS)
            add(UIDFolder.FetchProfileItem.UID)
        }

        /** The library answers a UID the folder does not have with a gap. */
        private fun present(messages: Array<Message?>?): Array<Message> = messages.orEmpty().filterNotNull().toTypedArray()

        internal fun flags(flags: Flags): MessageFlags = MessageFlags(
            seen = flags.contains(Flags.Flag.SEEN),
            flagged = flags.contains(Flags.Flag.FLAGGED),
            answered = flags.contains(Flags.Flag.ANSWERED),
            forwarded = flags.userFlags.any { it.equals("\$Forwarded", ignoreCase = true) },
            draft = flags.contains(Flags.Flag.DRAFT),
            deleted = flags.contains(Flags.Flag.DELETED),
        )

        /** The addresses of a header; a group ("Ekip: a@x, b@x;") is its members. */
        internal fun addresses(addresses: Array<Address>?): List<MailAddress> {
            if (addresses == null) return emptyList()
            val out = ArrayList<MailAddress>(addresses.size)
            for (address in addresses) {
                val internet = address as? InternetAddress
                if (internet == null) {
                    out += MailAddress("", address.toString())
                    continue
                }
                val members = if (internet.isGroup) runCatching { internet.getGroup(false) }.getOrNull() else null
                if (members != null) {
                    members.mapTo(out) { MailAddress(name(it), it.address.orEmpty()) }
                } else {
                    out += MailAddress(name(internet), internet.address.orEmpty())
                }
            }
            return out
        }

        private fun name(address: InternetAddress): String {
            val personal = address.personal ?: return ""
            // some senders encode the name twice, or forget to mark the encoding's end
            val decoded = runCatching { MimeUtility.decodeText(personal) }.getOrDefault(personal)
            return decoded.replace(WHITESPACE, " ").trim()
        }

        /** UID SEARCH with [criteria] (written out) and [args] (strings the library writes out as they must be): the UIDs the server answers with. */
        private fun uidSearch(protocol: IMAPProtocol, criteria: String, args: Argument?): LongArray {
            val responses = protocol.command(if (criteria.isEmpty()) "UID SEARCH" else "UID SEARCH $criteria", args)
            val last = responses[responses.size - 1]
            val uids = ArrayList<Long>()
            if (last.isOK) {
                for (index in responses.indices) {
                    val response = responses[index] as? IMAPResponse ?: continue
                    if (!response.keyEquals("SEARCH")) continue
                    while (true) {
                        val uid = response.readLong()
                        if (uid == -1L) break
                        uids += uid
                    }
                    responses[index] = null
                }
            }
            // what else the server said in passing (new mail, expunges) goes to the folder; a NO or BAD is thrown
            protocol.notifyResponseHandlers(responses)
            protocol.handleResult(last)
            return uids.toLongArray()
        }
    }
}
