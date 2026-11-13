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
import desktopkit.shell.NavKind
import desktopkit.shell.NavSection
import desktopkit.shell.ShellSpec

/**
 * What the shell shows: the views over every account, then one section per account with its
 * folders, Settings at the foot. The phone's bottom bar carries Inbox, Folders and Search (and
 * "⋯"); the rail adds Unread and Starred.
 *
 * The folders are screens of the shell, which gives them a place in the sidebar, a badge and a
 * drop target. Only the sidebar lists them: under their account's heading, which opens and shuts,
 * and a folder of the user's inside another one indented under that one. A phone and a rail reach
 * them through the Folders screen instead, and they are its pages ([NavItem.parent]): its entry
 * stays marked while one of them is open. The Folders and Search screens are the bottom bar's and
 * the rail's alone; a large window lists the folders in its sidebar and searches from its menu bar
 * (and with "/"). The kit adds a new folder to its navigation graph without leaving the screen that
 * is open (desktop-kit 2.8.0; docs/KIT-ISTEKLERI.md, 3).
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
            NavItem(Routes.AWAITING, resources.getString(R.string.nav_awaiting), MailIcons.Awaiting),
            NavItem(Routes.SNOOZED, resources.getString(R.string.nav_snoozed), MailIcons.Snooze),
            NavItem(Routes.FOLDERS, resources.getString(R.string.nav_folders), DeskIcons.Folder, showIn = PHONE_AND_RAIL),
            NavItem(Routes.SEARCH, resources.getString(R.string.nav_search), DeskIcons.Search, showIn = PHONE_AND_RAIL),
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
        val own = folders.filter { it.accountId == account.id }
        val byPath = own.associateBy { it.path }
        NavSection(
            title = account.name,
            items = own.map { folder ->
                // a folder of the user's under the one it is in, where the account has that one (as the
                // Folders screen shows it); else a page of the Folders screen, its path in its name
                val outer = if (folder.role == FolderRole.Other && folder.path.size > 1) byPath[folder.path.dropLast(1)] else null
                NavItem(
                    route = Routes.folder(folder.id),
                    label = if (outer != null) folderName(resources, folder) else folderPathLabel(resources, folder),
                    icon = folderIcon(folder.role),
                    parent = outer?.let { Routes.folder(it.id) } ?: Routes.FOLDERS,
                    showIn = SIDEBAR_ONLY,
                )
            },
            collapsible = true,
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

private val PHONE_AND_RAIL = setOf(NavKind.BottomBar, NavKind.Rail)
private val SIDEBAR_ONLY = setOf(NavKind.Sidebar)

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
    val unread = folders.filter { it.role != FolderRole.All && it.role != FolderRole.Junk && it.role != FolderRole.Trash && it.role != FolderRole.Snoozed }.sumOf { it.unread }
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
    FolderRole.Snoozed -> resources.getString(R.string.folder_snoozed)
    FolderRole.Other -> folder.name
}

/**
 * A folder's name where nothing shows the tree it is in (a title, a menu, the palette): a folder of
 * the user's inside another one is named with its path, "Projeler / 2026".
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
    FolderRole.Snoozed -> MailIcons.Snooze
    FolderRole.Other -> DeskIcons.Folder
}
