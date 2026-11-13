package com.deskmail.ui

import android.content.res.Resources
import androidx.compose.ui.graphics.vector.ImageVector
import com.deskmail.R
import com.deskmail.core.mail.FolderRole
import com.deskmail.mail.MailAccount
import com.deskmail.mail.MailFolder
import com.deskmail.mail.SavedSearch
import desktopkit.components.DeskIcons
import desktopkit.shell.MoreStyle
import desktopkit.shell.NavItem
import desktopkit.shell.NavSection
import desktopkit.shell.ShellSpec

/**
 * What the shell shows: the views over every account, then one section per account with its
 * folders, Settings at the foot. The phone's bottom bar carries Inbox, Folders and Search (and
 * "⋯"); the rail adds Unread and Starred; the sidebar lists everything.
 *
 * The folders are top-level screens of the shell, which is what gives them a place in the sidebar,
 * a badge and (later) a drop target. The kit builds its navigation graph from this list, so it must
 * only be built anew when the accounts or the folders really change (see docs/KIT-ISTEKLERI.md, 3).
 */
fun buildShellSpec(
    resources: Resources,
    accounts: List<MailAccount>,
    folders: List<MailFolder>,
    startRoute: String? = null,
    saved: List<SavedSearch> = emptyList(),
): ShellSpec {
    val views = NavSection(
        title = null,
        items = listOf(
            NavItem(Routes.INBOX, resources.getString(R.string.nav_inbox), MailIcons.Inbox),
            NavItem(Routes.UNREAD, resources.getString(R.string.nav_unread), MailIcons.Mail),
            NavItem(Routes.STARRED, resources.getString(R.string.nav_starred), MailIcons.Star),
            NavItem(Routes.VIP, resources.getString(R.string.nav_vip), MailIcons.Vip),
            NavItem(Routes.FOLDERS, resources.getString(R.string.nav_folders), DeskIcons.Folder),
            NavItem(Routes.SEARCH, resources.getString(R.string.nav_search), DeskIcons.Search),
            NavItem(Routes.OUTBOX, resources.getString(R.string.nav_outbox), MailIcons.Sent),
        ),
    )
    // the searches the user keeps as folders, under a heading of their own
    val searches = if (saved.isEmpty()) emptyList() else listOf(
        NavSection(
            title = resources.getString(R.string.nav_saved_searches),
            items = saved.map { NavItem(Routes.saved(it.id), it.name, MailIcons.SavedSearch) },
        ),
    )
    val perAccount = accounts.map { account ->
        NavSection(
            title = account.name,
            items = folders.filter { it.accountId == account.id }.map { folder ->
                NavItem(Routes.folder(folder.id), folderPathLabel(resources, folder), folderIcon(folder.role))
            },
        )
    }
    return ShellSpec(
        appTitle = resources.getString(R.string.app_name),
        sections = listOf(views) + searches + perAccount,
        footer = NavItem(Routes.SETTINGS, resources.getString(desktopkit.R.string.dk_settings), DeskIcons.Settings),
        bottomBar = listOf(Routes.INBOX, Routes.FOLDERS, Routes.SEARCH),
        rail = listOf(Routes.INBOX, Routes.UNREAD, Routes.STARRED, Routes.FOLDERS, Routes.SEARCH),
        startRoute = startRoute,
        moreStyle = MoreStyle.Sheet,
    )
}

/**
 * The counts beside the sidebar's entries: unread messages, and what is in the outbox. The
 * unified inbox counts the inboxes; "All Mail" holds the same messages once more and is left out
 * of "Unread".
 */
fun navBadges(folders: List<MailFolder>, outbox: Int = 0): Map<String, String> = buildMap {
    // what has not left yet: drafts, and what waits or failed
    if (outbox > 0) put(Routes.OUTBOX, outbox.toString())
    val inbox = folders.filter { it.role == FolderRole.Inbox }.sumOf { it.unread }
    if (inbox > 0) put(Routes.INBOX, inbox.toString())
    val unread = folders.filter { it.role != FolderRole.All && it.role != FolderRole.Junk && it.role != FolderRole.Trash }.sumOf { it.unread }
    if (unread > 0) put(Routes.UNREAD, unread.toString())
    folders.forEach { folder -> if (folder.unread > 0) put(Routes.folder(folder.id), folder.unread.toString()) }
}

/** A folder's own name: the translated name of its role, or what the user called it. */
fun folderName(resources: Resources, folder: MailFolder): String = when (folder.role) {
    FolderRole.Inbox -> resources.getString(R.string.folder_inbox)
    FolderRole.Drafts -> resources.getString(R.string.folder_drafts)
    FolderRole.Sent -> resources.getString(R.string.folder_sent)
    FolderRole.Archive -> resources.getString(R.string.folder_archive)
    FolderRole.Junk -> resources.getString(R.string.folder_junk)
    FolderRole.Trash -> resources.getString(R.string.folder_trash)
    FolderRole.All -> resources.getString(R.string.folder_all)
    FolderRole.Other -> folder.name
}

/**
 * A folder's name where nothing shows the tree it is in (the sidebar, a title): a folder of the
 * user's inside another one is named with its path, "Projeler / 2026".
 */
fun folderPathLabel(resources: Resources, folder: MailFolder): String =
    if (folder.role == FolderRole.Other) folder.path.joinToString(" / ") else folderName(resources, folder)

fun folderIcon(role: FolderRole): ImageVector = when (role) {
    FolderRole.Inbox -> MailIcons.Inbox
    FolderRole.Drafts -> MailIcons.Drafts
    FolderRole.Sent -> MailIcons.Sent
    FolderRole.Archive -> MailIcons.Archive
    FolderRole.Junk -> MailIcons.Junk
    FolderRole.Trash -> DeskIcons.Delete
    FolderRole.All -> MailIcons.Mail
    FolderRole.Other -> DeskIcons.Folder
}
