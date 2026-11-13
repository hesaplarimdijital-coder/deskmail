package com.deskmail.core.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderRolesTest {

    private fun folder(path: String, vararg attributes: String, delimiter: Char? = '/', selectable: Boolean = true) =
        RemoteFolder(path, delimiter, attributes.toSet(), selectable)

    private fun roles(vararg folders: RemoteFolder): Map<String, FolderRole> =
        FolderRoles.assign(folders.toList()).associate { it.path to it.role }

    /** Gmail in Turkish: the names are translated, the attributes say what the folders are. */
    @Test
    fun theServersAttributesSayWhatAFolderIs() {
        val roles = roles(
            folder("INBOX", "\\HasNoChildren"),
            folder("[Gmail]", "\\HasChildren", "\\Noselect", selectable = false),
            folder("[Gmail]/Tüm Postalar", "\\All", "\\HasNoChildren"),
            folder("[Gmail]/Gönderilmiş Postalar", "\\HasNoChildren", "\\Sent"),
            folder("[Gmail]/Taslaklar", "\\Drafts", "\\HasNoChildren"),
            folder("[Gmail]/Çöp Kutusu", "\\HasNoChildren", "\\Trash"),
            folder("[Gmail]/Spam", "\\HasNoChildren", "\\Junk"),
            folder("[Gmail]/Yıldızlı", "\\Flagged", "\\HasNoChildren"),
            folder("[Gmail]/Önemli", "\\HasNoChildren", "\\Important"),
            folder("Seyahat", "\\HasNoChildren"),
        )
        assertEquals(FolderRole.Inbox, roles["INBOX"])
        assertEquals(FolderRole.Other, roles["[Gmail]"])
        assertEquals(FolderRole.All, roles["[Gmail]/Tüm Postalar"])
        assertEquals(FolderRole.Sent, roles["[Gmail]/Gönderilmiş Postalar"])
        assertEquals(FolderRole.Drafts, roles["[Gmail]/Taslaklar"])
        assertEquals(FolderRole.Trash, roles["[Gmail]/Çöp Kutusu"])
        assertEquals(FolderRole.Junk, roles["[Gmail]/Spam"])
        // views of the same mail, not places of their own
        assertEquals(FolderRole.Other, roles["[Gmail]/Yıldızlı"])
        assertEquals(FolderRole.Other, roles["[Gmail]/Önemli"])
        assertEquals(FolderRole.Other, roles["Seyahat"])
    }

    /** A server that gives no attributes: the folders are known by what mail programs call them. */
    @Test
    fun withoutAttributesTheNamesSayIt() {
        val turkish = roles(
            folder("Inbox"),
            folder("Gönderilmiş Öğeler"),
            folder("Silinmiş Öğeler"),
            folder("Önemsiz E-posta"),
            folder("Taslaklar"),
            folder("Arşiv"),
            folder("Faturalar"),
        )
        assertEquals(FolderRole.Inbox, turkish["Inbox"])
        assertEquals(FolderRole.Sent, turkish["Gönderilmiş Öğeler"])
        assertEquals(FolderRole.Trash, turkish["Silinmiş Öğeler"])
        assertEquals(FolderRole.Junk, turkish["Önemsiz E-posta"])
        assertEquals(FolderRole.Drafts, turkish["Taslaklar"])
        assertEquals(FolderRole.Archive, turkish["Arşiv"])
        assertEquals(FolderRole.Other, turkish["Faturalar"])

        val english = roles(folder("INBOX"), folder("Sent Items"), folder("Deleted Items"), folder("Junk Email"), folder("Drafts"), folder("Archive"))
        assertEquals(
            listOf(FolderRole.Inbox, FolderRole.Sent, FolderRole.Trash, FolderRole.Junk, FolderRole.Drafts, FolderRole.Archive),
            english.values.toList(),
        )
        // capitals of a Turkish keyboard do not matter
        assertEquals(FolderRole.Trash, roles(folder("ÇÖP KUTUSU"))["ÇÖP KUTUSU"])
    }

    /** The server's own folders can sit under INBOX; a folder of that name deeper down is the user's. */
    @Test
    fun onlyTheServersOwnFoldersAreKnownByName() {
        val roles = roles(
            folder("INBOX", delimiter = '.'),
            folder("INBOX.Sent", delimiter = '.'),
            folder("INBOX.Trash", delimiter = '.'),
            folder("Projeler", delimiter = '.'),
            folder("Projeler.Drafts", delimiter = '.'),
            folder("Projeler.2026.Spam", delimiter = '.'),
        )
        assertEquals(FolderRole.Sent, roles["INBOX.Sent"])
        assertEquals(FolderRole.Trash, roles["INBOX.Trash"])
        assertEquals(FolderRole.Other, roles["Projeler.Drafts"])
        assertEquals(FolderRole.Other, roles["Projeler.2026.Spam"])
    }

    /** One folder per role: the attribute wins over a name, the first name over a second. */
    @Test
    fun everyRoleIsGivenOnce() {
        val roles = roles(
            folder("INBOX"),
            folder("Sent"),
            folder("Sent Items", "\\Sent"),
            folder("Trash"),
            folder("Deleted Items"),
            // a folder that holds no messages has no role, whatever it is called
            folder("Archive", "\\Noselect", selectable = false),
        )
        assertEquals(FolderRole.Sent, roles["Sent Items"])
        assertEquals(FolderRole.Other, roles["Sent"])
        assertEquals(FolderRole.Trash, roles["Trash"])
        assertEquals(FolderRole.Other, roles["Deleted Items"])
        assertEquals(FolderRole.Other, roles["Archive"])
    }

    @Test
    fun aFoldersLevelsAreSplitAtTheServersDelimiter() {
        assertEquals(listOf("Projeler", "2026"), folder("Projeler/2026").segments)
        assertEquals(listOf("Projeler.2026"), folder("Projeler.2026").segments)
        assertEquals(listOf("Projeler", "2026"), folder("Projeler.2026", delimiter = '.').segments)
        assertEquals(listOf("a/b"), folder("a/b", delimiter = null).segments)
    }

    /** DeskMail's own folder for snoozed mail, by its name or by the attribute a server may give it. */
    @Test
    fun theSnoozedFolderIsKnown() {
        assertEquals(FolderRole.Snoozed, roles(folder("INBOX"), folder("Ertelenenler"))["Ertelenenler"])
        assertEquals(FolderRole.Snoozed, roles(folder("INBOX"), folder("Later", "\\Snoozed"))["Later"])
        assertEquals(FolderRole.Other, roles(folder("INBOX"), folder("Projeler/Ertelenenler"))["Projeler/Ertelenenler"])
    }
}
