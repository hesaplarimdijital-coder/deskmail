package com.deskmail.core.sync

import com.deskmail.core.mail.EditableMailbox
import com.deskmail.core.mail.FolderCounts
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailAddress
import com.deskmail.core.mail.MailException
import com.deskmail.core.mail.Mark
import com.deskmail.core.mail.MessageFlags
import com.deskmail.core.mail.Moved
import com.deskmail.core.mail.OpenMailbox
import com.deskmail.core.mail.RemoteAccount
import com.deskmail.core.mail.RemoteAttachment
import com.deskmail.core.mail.RemoteBody
import com.deskmail.core.mail.RemoteFlags
import com.deskmail.core.mail.RemoteFolder
import com.deskmail.core.mail.RemoteMessage
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A folder on a made-up server: the tests change it as a server (or another device) would. */
class FakeFolder(val path: String, var uidValidity: Long = 7, private val condstore: Boolean = false) {
    private class Entry(var header: RemoteMessage, var body: RemoteBody?, var modSeq: Long)

    private val entries = sortedMapOf<Long, Entry>()
    private var nextUid = 1L
    private var modSeq = 1L

    /** What the server was asked, in order: for telling a careful sync from a wasteful one. */
    val asked = ArrayList<String>()

    /** The next question the server is asked breaks the connection. */
    var failNext: MailException? = null

    /** UIDs the server will not hand the text of. */
    val refused = HashSet<Long>()

    fun deliver(subject: String, received: Instant, text: String? = "Merhaba", html: String? = null, flags: MessageFlags = MessageFlags()): Long {
        val uid = nextUid++
        val header = RemoteMessage(
            uid = uid,
            globalId = "<$uid@example.org>",
            inReplyTo = "",
            references = "",
            subject = subject,
            from = listOf(MailAddress("Ayşe Yılmaz", "ayse@example.org")),
            to = listOf(MailAddress("Deniz Aksoy", "deniz@example.org")),
            cc = emptyList(),
            bcc = emptyList(),
            replyTo = emptyList(),
            sentAt = received,
            receivedAt = received,
            size = 1000,
            flags = flags,
            attachments = 0,
        )
        entries[uid] = Entry(header, RemoteBody(text, html, emptyList()), ++modSeq)
        return uid
    }

    /** What the files of the messages hold, by UID and part. */
    val files = HashMap<Pair<Long, String>, ByteArray>()

    /** Gives a message a file, as its sender would have attached it. */
    fun attach(uid: Long, name: String, content: ByteArray, part: String = "2") {
        val entry = entries.getValue(uid)
        val body = entry.body ?: RemoteBody(null, null, emptyList())
        entry.body = body.copy(attachments = body.attachments + RemoteAttachment(part, name, "application/octet-stream", content.size.toLong(), "", inline = false))
        entry.header = entry.header.copy(attachments = entry.header.attachments + 1)
        files[uid to part] = content
    }

    fun flags(uid: Long): MessageFlags? = entries[uid]?.header?.flags

    fun subjects(): List<String> = entries.values.map { it.header.subject }

    /** A message arrives from another folder: it gets a UID of this one. */
    private fun take(header: RemoteMessage, body: RemoteBody?): Long {
        val uid = nextUid++
        entries[uid] = Entry(header.copy(uid = uid), body, ++modSeq)
        return uid
    }

    /** The folder opened for changes, as [FakeAccount.edit] hands it out. */
    fun edit(account: FakeAccount): EditableMailbox = object : EditableMailbox {
        override val uidValidity: Long = this@FakeFolder.uidValidity

        override fun mark(uids: List<Long>, mark: Mark, value: Boolean) {
            ask("mark $mark=$value $uids")
            for (uid in uids) {
                val flags = entries[uid]?.header?.flags ?: continue
                setFlags(uid, if (mark == Mark.Seen) flags.copy(seen = value) else flags.copy(flagged = value))
            }
        }

        override fun move(uids: List<Long>, target: String): List<Moved> {
            ask("move $uids to $target")
            val destination = account.folders.firstOrNull { it.path == target }
                ?: throw MailException(MailException.Kind.Server, "NO [TRYCREATE] no such folder: $target")
            return uids.mapNotNull { uid ->
                val entry = entries.remove(uid) ?: return@mapNotNull null
                modSeq++
                val arrived = destination.take(entry.header, entry.body)
                if (account.uidPlus) Moved(uid, arrived, destination.uidValidity) else Moved(uid)
            }
        }

        override fun expunge(uids: List<Long>) {
            ask("expunge $uids")
            uids.forEach { if (entries.containsKey(it)) this@FakeFolder.expunge(it) }
        }

        override fun close() = Unit
    }

    private fun ask(what: String) {
        failNext?.let { failure ->
            failNext = null
            throw failure
        }
        asked += what
    }

    fun setBody(uid: Long, body: RemoteBody?) {
        entries.getValue(uid).body = body
    }

    fun setFlags(uid: Long, flags: MessageFlags) {
        val entry = entries.getValue(uid)
        entry.header = entry.header.copy(flags = flags)
        entry.modSeq = ++modSeq
    }

    fun expunge(uid: Long) {
        entries.remove(uid)
        modSeq++
    }

    /** The server renumbers the folder: every UID means something else from now on. */
    fun renumber() {
        val kept = entries.values.toList()
        entries.clear()
        uidValidity++
        nextUid = 100
        for (entry in kept) {
            val uid = nextUid++
            entries[uid] = Entry(entry.header.copy(uid = uid), entry.body, ++modSeq)
        }
    }

    fun open(): OpenMailbox = object : OpenMailbox {
        override val uidValidity: Long = this@FakeFolder.uidValidity
        override val uidNext: Long = nextUid
        override val highestModSeq: Long = if (condstore) modSeq else 0
        override val total: Int get() = entries.size
        override val unread: Int get() = entries.values.count { !it.header.flags.seen }

        override fun uids(since: LocalDate?): List<Long> {
            ask("uids since $since")
            return entries.values
                .filter { since == null || !LocalDate.ofInstant(it.header.receivedAt, ZoneOffset.UTC).isBefore(since) }
                .map { it.header.uid }
        }

        override fun headers(uids: List<Long>): List<RemoteMessage> {
            ask("headers $uids")
            return uids.mapNotNull { entries[it]?.header }
        }

        override fun flags(uids: List<Long>): List<RemoteFlags> {
            ask("flags of ${uids.size}")
            return uids.mapNotNull { uid -> entries[uid]?.let { RemoteFlags(uid, it.header.flags) } }
        }

        override fun flagsChangedSince(modSeq: Long): List<RemoteFlags>? {
            if (!condstore) return null
            ask("flags changed since $modSeq")
            return entries.values.filter { it.modSeq > modSeq }.map { RemoteFlags(it.header.uid, it.header.flags) }
        }

        override fun body(uid: Long, maxTextBytes: Int): RemoteBody? {
            ask("body $uid")
            if (uid in refused) throw MailException(MailException.Kind.Server, "NO [SERVERBUG] cannot fetch $uid")
            return entries[uid]?.body
        }

        override fun attachment(uid: Long, part: String, into: OutputStream): Boolean {
            ask("attachment $uid/$part")
            val content = files[uid to part] ?: return false
            into.write(content)
            return true
        }

        override fun close() = Unit
    }
}

/** An account on a made-up server. */
class FakeAccount(vararg folders: FakeFolder) : RemoteAccount {
    val folders = folders.toMutableList()
    val attributes = HashMap<String, Set<String>>()
    var opened = 0
        private set

    /** Whether the server tells where a moved message went (UIDPLUS). */
    var uidPlus = true

    /** Folders the server will not have changed. */
    val readOnly = HashSet<String>()

    override fun folders(): List<RemoteFolder> =
        com.deskmail.core.mail.FolderRoles.assign(folders.map { RemoteFolder(it.path, '/', attributes[it.path].orEmpty(), selectable = true) })

    override fun counts(path: String): FolderCounts? = folders.firstOrNull { it.path == path }?.open()?.let { FolderCounts(it.total, it.unread) }

    override fun open(path: String): OpenMailbox {
        opened++
        return folders.first { it.path == path }.open()
    }

    override fun edit(path: String): EditableMailbox {
        opened++
        if (path in readOnly) throw MailException(MailException.Kind.Server, "NO [READ-ONLY] $path cannot be changed")
        val folder = folders.firstOrNull { it.path == path } ?: throw MailException(MailException.Kind.Server, "NO no such folder: $path")
        return folder.edit(this)
    }

    override fun close() = Unit
}

/** The device's copy of the mail, in memory. */
class MemoryStore : SyncStore {
    class Message(var header: RemoteMessage, var body: StoredBody? = null, var bodyState: Int = 0)

    class Folder(val id: Long, val accountId: Long, var remote: RemoteFolder, var role: FolderRole) {
        var state = FolderState()
        var syncedAt: Instant? = null
        var total = 0
        var unread = 0
        val messages = sortedMapOf<Long, Message>()
    }

    val folders = LinkedHashMap<Long, Folder>()
    private var nextId = 1L

    /** How many rows were written, for telling a careful sync from a wasteful one. */
    var flagWrites = 0

    fun folder(path: String): Folder = folders.values.first { it.remote.path == path }

    fun stored(path: String): StoredFolder = folder(path).let { StoredFolder(it.id, it.remote.path, it.role, it.remote.selectable, it.state) }

    fun subjects(path: String): List<String> = folder(path).messages.values.sortedByDescending { it.header.uid }.map { it.header.subject }

    override suspend fun saveFolders(accountId: Long, folders: List<RemoteFolder>): List<StoredFolder> {
        val paths = folders.map { it.path }.toSet()
        this.folders.values.removeAll { it.accountId == accountId && it.remote.path !in paths }
        for (remote in folders) {
            val existing = this.folders.values.firstOrNull { it.accountId == accountId && it.remote.path == remote.path }
            if (existing == null) {
                val id = nextId++
                this.folders[id] = Folder(id, accountId, remote, remote.role)
            } else {
                existing.remote = remote
                existing.role = remote.role
            }
        }
        return folders.map { stored(it.path) }
    }

    override suspend fun saveCounts(folderId: Long, total: Int, unread: Int) {
        folders.getValue(folderId).total = total
        folders.getValue(folderId).unread = unread
    }

    override suspend fun uids(folderId: Long): List<Long> = folders.getValue(folderId).messages.keys.toList()

    override suspend fun clear(folderId: Long) = folders.getValue(folderId).messages.clear()

    override suspend fun insert(folderId: Long, messages: List<RemoteMessage>) {
        for (message in messages) folders.getValue(folderId).messages[message.uid] = Message(message)
    }

    override suspend fun delete(folderId: Long, uids: Collection<Long>) {
        uids.forEach { folders.getValue(folderId).messages.remove(it) }
    }

    override suspend fun updateFlags(folderId: Long, flags: List<RemoteFlags>) {
        for (update in flags) {
            val message = folders.getValue(folderId).messages[update.uid] ?: continue
            if (message.header.flags != update.flags) {
                message.header = message.header.copy(flags = update.flags)
                flagWrites++
            }
        }
    }

    override suspend fun uidsWithoutBody(folderId: Long, limit: Int): List<Long> =
        folders.getValue(folderId).messages.values.filter { it.bodyState == 0 }.map { it.header.uid }.sortedDescending().take(limit)

    override suspend fun saveBody(folderId: Long, uid: Long, body: StoredBody?) {
        val message = folders.getValue(folderId).messages[uid] ?: return
        message.body = body
        message.bodyState = if (body == null) 2 else 1
    }

    override suspend fun saveState(folderId: Long, state: FolderState, syncedAt: Instant?) {
        val folder = folders.getValue(folderId)
        folder.state = state
        if (syncedAt != null) folder.syncedAt = syncedAt
    }
}
