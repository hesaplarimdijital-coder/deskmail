package com.deskmail.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deskmail.R
import com.deskmail.core.log.AppLog
import com.deskmail.ui.MailIcons
import desktopkit.components.DeskButton
import desktopkit.components.DeskIcons
import desktopkit.pluralString
import desktopkit.screens.MessageScreen
import desktopkit.screens.PageHeader
import desktopkit.shell.CompactTopBar
import desktopkit.shell.LocalNavKind
import desktopkit.shell.NavKind
import desktopkit.shell.TopBarAction
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme

/**
 * The log: what the app did and what went wrong, newest at the end, where the screen opens.
 * Warnings and errors are coloured; the further lines of an entry (a stack trace) are dimmed.
 * Passwords and tokens are masked before a line is ever kept. The log can be copied (its end, to
 * paste into a chat), shared as a file (all of it) and cleared.
 */
@Composable
fun LogScreen(
    log: AppLog,
    onClose: () -> Unit,
    onCopy: (List<String>) -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    // follows the log while the screen is open; the log may be written from any thread
    val lines by produceState(initialValue = log.lines(), log) {
        val remove = log.addListener { value = log.lines() }
        value = log.lines()
        awaitDispose { remove() }
    }
    val title = stringResource(R.string.settings_log)
    val share = stringResource(R.string.log_share)
    val copy = stringResource(R.string.log_copy)
    val clear = stringResource(R.string.log_clear)
    val sidebar = LocalNavKind.current == NavKind.Sidebar
    // a phone and a rail: the title, back and the actions in the top bar; a large window has them in the header
    CompactTopBar(
        title = title,
        onBack = onClose,
        actions = listOf(
            TopBarAction(MailIcons.Share, share, onShare, primary = true),
            TopBarAction(MailIcons.Copy, copy, { onCopy(lines) }),
            TopBarAction(DeskIcons.Delete, clear, onClear),
        ),
        showSearch = false,
        showOnRail = true,
    )
    val shell = rememberShellContentPadding()
    val list = rememberLazyListState()
    // the latest lines are what one came for
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex)
    }
    Column(Modifier.fillMaxSize().padding(top = shell.calculateTopPadding())) {
        PageHeader(title, subtitle = pluralString(R.plurals.log_lines, lines.size)) {
            if (sidebar) {
                DeskButton(stringResource(desktopkit.R.string.dk_back), onClose, icon = DeskIcons.ArrowLeft)
                DeskButton(copy, { onCopy(lines) }, icon = MailIcons.Copy)
                DeskButton(share, onShare, icon = MailIcons.Share, primary = true)
                DeskButton(clear, onClear, icon = DeskIcons.Delete)
            }
        }
        if (lines.isEmpty()) {
            MessageScreen(title = stringResource(R.string.log_empty), icon = MailIcons.Log)
        } else {
            SelectionContainer {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = list,
                    contentPadding = PaddingValues(bottom = shell.calculateBottomPadding() + 8.dp),
                ) {
                    itemsIndexed(lines) { _, line -> LogLine(line) }
                }
            }
        }
    }
}

@Composable
private fun LogLine(line: String) {
    val colors = DeskTheme.colors
    val color = when (AppLog.levelOf(line)) {
        'E' -> colors.danger
        'W' -> colors.warning
        'I' -> colors.textSecondary
        else -> colors.textTertiary
    }
    Text(
        line,
        style = DeskTheme.type.mono,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(horizontal = DeskTheme.dimens.pagePadding, vertical = 1.dp),
    )
}
