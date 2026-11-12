package com.deskmail.ui.mail

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.deskmail.R
import com.deskmail.core.mail.FolderRole
import com.deskmail.mail.MailAccount
import com.deskmail.mail.MailFolder
import com.deskmail.ui.folderIcon
import com.deskmail.ui.folderName
import desktopkit.appResources
import desktopkit.components.CountBadge
import desktopkit.components.DeskListRow
import desktopkit.components.SectionCaption
import desktopkit.screens.PageHeader
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme

/**
 * Every account's folders with their unread counts: the phone's and the rail's way to a folder
 * (the sidebar of a large window lists the folders itself). A folder of the user's inside another
 * one is indented under it.
 */
@Composable
fun FoldersScreen(accounts: List<MailAccount>, folders: List<MailFolder>, onOpen: (MailFolder) -> Unit) {
    val resources = appResources()
    val dimens = DeskTheme.dimens
    LazyColumn(Modifier.fillMaxSize(), contentPadding = rememberShellContentPadding()) {
        item(key = "header") { PageHeader(stringResource(R.string.nav_folders)) }
        accounts.forEach { account ->
            item(key = "account-${account.id}") {
                SectionCaption(
                    text = stringResource(R.string.folders_account, account.name, account.address),
                    modifier = Modifier
                        .padding(start = dimens.pagePadding, end = dimens.pagePadding, top = dimens.gapL, bottom = dimens.gapXs)
                        .semantics { heading() },
                )
            }
            items(folders.filter { it.accountId == account.id }, key = { "folder-${it.id}" }) { folder ->
                DeskListRow(
                    title = folderName(resources, folder),
                    leading = folderIcon(folder.role),
                    indent = if (folder.role == FolderRole.Other) NESTED_INDENT * folder.depth else 0.dp,
                    trailingContent = { if (folder.unread > 0) CountBadge(folder.unread) },
                    onClick = { onOpen(folder) },
                )
            }
        }
    }
}

private val NESTED_INDENT = 24.dp
