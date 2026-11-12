package com.deskmail.ui.mail

import android.text.format.Formatter
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deskmail.R
import com.deskmail.mail.MailAttachment
import com.deskmail.mail.MailMessage
import com.deskmail.ui.MailIcons
import desktopkit.components.BannerTone
import desktopkit.components.DeskChip
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.StatusBanner
import desktopkit.screens.MessageScreen
import desktopkit.shell.CompactTopBar
import desktopkit.shell.TopBarAction
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme

/**
 * One message: its actions, the subject, who it is from and to, when, its attachments, a strip
 * when images were kept from loading, and the body ([body], the WebView in the app). Everything
 * scrolls as one page, under the phone's bars.
 *
 * On a phone the actions are in the top bar, with the back arrow ([onClose]); wider windows show
 * them in a row above the subject, with [trailingActions] at its end and a back arrow only where
 * the reader covers the list ([onClose] set).
 */
@Composable
fun MessageReader(
    message: MailMessage,
    onToggleStar: () -> Unit,
    onToggleUnread: () -> Unit,
    onClose: (() -> Unit)?,
    onShowRemoteImages: () -> Unit,
    onAttachment: (MailAttachment) -> Unit,
    body: @Composable (MailMessage, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    trailingActions: @Composable RowScope.() -> Unit = {},
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val compact = currentWidthClass() == WidthClass.Compact
    val starIcon = if (message.starred) MailIcons.StarFilled else MailIcons.Star
    val starLabel = stringResource(if (message.starred) R.string.action_unstar else R.string.action_star)
    val unreadIcon = if (message.unread) MailIcons.MailOpen else MailIcons.Mail
    val unreadLabel = stringResource(if (message.unread) R.string.action_mark_read else R.string.action_mark_unread)
    if (onClose != null) {
        // a phone: back, the star and read/unread in the top bar (nothing happens on wider windows)
        CompactTopBar(
            onBack = onClose,
            actions = listOf(
                TopBarAction(starIcon, starLabel, onToggleStar),
                TopBarAction(unreadIcon, unreadLabel, onToggleUnread),
            ),
        )
    }
    // every message starts at its top
    val scroll = remember(message.id) { ScrollState(0) }
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(scroll)
            .padding(rememberShellContentPadding()),
    ) {
        if (!compact) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding - 8.dp).padding(top = dimens.gapXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onClose != null) DeskIconButton(DeskIcons.ArrowLeft, stringResource(desktopkit.R.string.dk_back), onClose)
                Spacer(Modifier.weight(1f))
                DeskIconButton(starIcon, starLabel, onToggleStar, tint = if (message.starred) colors.warning else colors.textSecondary)
                DeskIconButton(unreadIcon, unreadLabel, onToggleUnread)
                trailingActions()
            }
        }
        ReaderHeader(message)
        if (message.attachments.isNotEmpty()) Attachments(message.attachments, onAttachment)
        if (message.remoteImages) {
            StatusBanner(
                tone = BannerTone.Info,
                text = stringResource(R.string.reader_remote_blocked),
                modifier = Modifier.padding(horizontal = dimens.pagePadding).padding(bottom = dimens.gapM),
                actionLabel = stringResource(R.string.reader_remote_show),
                onAction = onShowRemoteImages,
            )
        }
        // a view of its own for every message
        key(message.id) { body(message, Modifier.fillMaxWidth()) }
    }
}

/** The subject, then the sender's name and address, the recipients and the whole date. */
@Composable
private fun ReaderHeader(message: MailMessage) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val dates = rememberDateLabels()
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding).padding(top = dimens.gapM, bottom = dimens.gapL)) {
            Text(message.subject, style = DeskTheme.type.headline, color = colors.text, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(dimens.gapM))
            Text(message.fromName, style = DeskTheme.type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(message.fromAddress, style = DeskTheme.type.labelSmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(dimens.gapXs))
            Text(
                stringResource(R.string.message_to, message.to),
                style = DeskTheme.type.labelSmall,
                color = colors.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(dates.full(message.sentAt), style = DeskTheme.type.labelSmall, color = colors.textTertiary)
        }
    }
}

/** The attachments as chips on one line that scrolls sideways: name and size. */
@Composable
private fun Attachments(attachments: List<MailAttachment>, onAttachment: (MailAttachment) -> Unit) {
    val context = LocalContext.current
    val dimens = DeskTheme.dimens
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = dimens.pagePadding)
            .padding(bottom = dimens.gapM),
        horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { attachment ->
            DeskChip(
                text = attachment.name,
                selected = false,
                onClick = { onAttachment(attachment) },
                icon = MailIcons.Attachment,
                count = Formatter.formatShortFileSize(context, attachment.bytes),
            )
        }
    }
}

/** The reader's place while no message is open (two panes). */
@Composable
fun NoMessageOpen(modifier: Modifier = Modifier) {
    MessageScreen(
        title = stringResource(R.string.reader_empty_title),
        icon = MailIcons.MailOpen,
        modifier = modifier,
        description = stringResource(R.string.reader_empty_text),
    )
}
