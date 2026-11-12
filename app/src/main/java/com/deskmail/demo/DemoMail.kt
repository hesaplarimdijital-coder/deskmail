package com.deskmail.demo

import com.deskmail.core.mail.FolderRole
import com.deskmail.mail.MailAccount
import com.deskmail.mail.MailFolder

/**
 * The made-up accounts and folders the first pre-release shows instead of real mail, so the shell
 * and the layouts can be tried on the devices before the app talks to a server. The addresses use
 * the reserved ".example" domain: they belong to nobody.
 */
class DemoMail {
    val accounts: List<MailAccount> = listOf(
        MailAccount(OUTLOOK, "Outlook", "deniz@outlook.example"),
        MailAccount(GMAIL, "Gmail", "deniz@gmail.example"),
    )

    val folders: List<MailFolder> = listOf(
        MailFolder(11, OUTLOOK, listOf("Inbox"), FolderRole.Inbox, unread = 4, total = 9),
        MailFolder(12, OUTLOOK, listOf("Drafts"), FolderRole.Drafts, total = 1),
        MailFolder(13, OUTLOOK, listOf("Sent"), FolderRole.Sent, total = 3),
        MailFolder(14, OUTLOOK, listOf("Archive"), FolderRole.Archive, total = 2),
        MailFolder(15, OUTLOOK, listOf("Junk"), FolderRole.Junk, unread = 1, total = 1),
        MailFolder(16, OUTLOOK, listOf("Deleted"), FolderRole.Trash),
        MailFolder(17, OUTLOOK, listOf("Faturalar"), FolderRole.Other, total = 2),
        MailFolder(18, OUTLOOK, listOf("Projeler"), FolderRole.Other),
        MailFolder(19, OUTLOOK, listOf("Projeler", "2026"), FolderRole.Other, unread = 1, total = 2),
        MailFolder(21, GMAIL, listOf("INBOX"), FolderRole.Inbox, unread = 2, total = 5),
        MailFolder(22, GMAIL, listOf("[Gmail]", "Drafts"), FolderRole.Drafts),
        MailFolder(23, GMAIL, listOf("[Gmail]", "Sent Mail"), FolderRole.Sent, total = 1),
        MailFolder(24, GMAIL, listOf("[Gmail]", "All Mail"), FolderRole.All, unread = 2, total = 7),
        MailFolder(25, GMAIL, listOf("[Gmail]", "Spam"), FolderRole.Junk),
        MailFolder(26, GMAIL, listOf("[Gmail]", "Trash"), FolderRole.Trash),
        MailFolder(27, GMAIL, listOf("Seyahat"), FolderRole.Other, total = 1),
    )

    companion object {
        const val OUTLOOK = 1L
        const val GMAIL = 2L

        /** One set of demo data for every window of the app. */
        val shared: DemoMail by lazy { DemoMail() }
    }
}
