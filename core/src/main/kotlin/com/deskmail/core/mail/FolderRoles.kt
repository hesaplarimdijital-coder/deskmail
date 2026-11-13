package com.deskmail.core.mail

import com.deskmail.core.text.TurkishFold

/**
 * Works out what the folders of an account are for.
 *
 * A server that knows RFC 6154 says so itself with an attribute ("\Sent", "\Trash"); that is
 * taken first. Where it says nothing, a folder at the top of the tree (or just under "[Gmail]" or
 * "INBOX") is recognised by the names the mail programs and providers give these folders, in
 * English and in Turkish. Each role is given once per account: a second "Sent" is a folder like any other.
 */
object FolderRoles {

    /** [folders] with their roles filled in; the order is kept. */
    fun assign(folders: List<RemoteFolder>): List<RemoteFolder> {
        val roles = arrayOfNulls<FolderRole>(folders.size)
        val taken = HashSet<FolderRole>()
        // the inbox is the one folder the protocol names
        folders.forEachIndexed { index, folder ->
            if (folder.path.equals("INBOX", ignoreCase = true) && taken.add(FolderRole.Inbox)) roles[index] = FolderRole.Inbox
        }
        folders.forEachIndexed { index, folder ->
            if (roles[index] == null) byAttribute(folder.attributes)?.let { if (taken.add(it)) roles[index] = it }
        }
        folders.forEachIndexed { index, folder ->
            if (roles[index] == null && folder.selectable) byName(folder)?.let { if (taken.add(it)) roles[index] = it }
        }
        return folders.mapIndexed { index, folder -> folder.copy(role = roles[index] ?: FolderRole.Other) }
    }

    /** The role a special-use attribute stands for, or null: "\Flagged" and "\Important" are views, not places. */
    fun byAttribute(attributes: Collection<String>): FolderRole? {
        for (attribute in attributes) {
            when (attribute.lowercase()) {
                "\\sent" -> return FolderRole.Sent
                "\\drafts" -> return FolderRole.Drafts
                "\\trash" -> return FolderRole.Trash
                "\\junk" -> return FolderRole.Junk
                "\\archive" -> return FolderRole.Archive
                "\\all" -> return FolderRole.All
                // the attribute some servers give a folder of their own snoozing
                "\\snoozed" -> return FolderRole.Snoozed
            }
        }
        return null
    }

    private fun byName(folder: RemoteFolder): FolderRole? {
        val segments = folder.segments
        val nested = segments.dropLast(1)
        // "Projeler/Sent" is the user's own; "[Gmail]/Sent Mail" and "INBOX.Sent" are the server's
        if (nested.size > 1 || (nested.size == 1 && TurkishFold.fold(nested[0]) !in CONTAINERS)) return null
        return NAMES[TurkishFold.fold(segments.last()).trim()]
    }

    /** Folders that only hold the server's own folders. */
    private val CONTAINERS = setOf("[gmail]", "[google mail]", "inbox")

    /** Folded names (lower case, Turkish letters on their Latin neighbours) and what they are. */
    private val NAMES: Map<String, FolderRole> = buildMap {
        fun add(role: FolderRole, vararg names: String) = names.forEach { put(TurkishFold.fold(it), role) }
        add(FolderRole.Sent, "Sent", "Sent Items", "Sent Mail", "Sent Messages", "Gönderilmiş Öğeler", "Gönderilenler", "Gönderilen", "Gönderilmiş", "Gönderilmiş Postalar")
        add(FolderRole.Drafts, "Drafts", "Draft", "Taslaklar", "Taslak")
        add(FolderRole.Trash, "Trash", "Deleted Items", "Deleted Messages", "Deleted", "Bin", "Çöp Kutusu", "Çöp", "Silinmiş Öğeler", "Silinenler")
        add(FolderRole.Junk, "Junk", "Junk Email", "Junk E-mail", "Spam", "Bulk Mail", "Önemsiz", "Önemsiz E-posta", "Gereksiz", "İstenmeyen", "İstenmeyen Posta")
        add(FolderRole.Archive, "Archive", "Archives", "Arşiv")
        add(FolderRole.All, "All Mail", "Tüm Postalar")
        add(FolderRole.Snoozed, "Ertelenenler", "Snoozed")
    }
}
