package com.deskmail.ui.mail

import android.text.format.Formatter
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deskmail.R
import com.deskmail.core.mail.MailException
import com.deskmail.mail.MailAttachment
import com.deskmail.mail.MailContent
import com.deskmail.mail.MailMessage
import com.deskmail.ui.MailIcons
import desktopkit.components.BannerTone
import desktopkit.components.DeskChip
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.SkeletonList
import desktopkit.components.StatusBanner
import desktopkit.screens.MessageScreen
import desktopkit.shell.CompactTopBar
import desktopkit.shell.TopBarAction
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import desktopkit.theme.DeskTheme
import kotlinx.coroutines.delay

/**
 * One message: its actions, the subject, who it is from and to, when, and then what it says
 * ([content]): its attachments, a strip when images were kept from loading, and the body ([body],
 * the WebView in the app). While the text is on its way there is a placeholder in its place, and
 * a strip with "Try again" when it could not be fetched. Everything scrolls as one page: beneath
 * the phone's bars with [underBars], between them without. [barsPadding] is what the bars cover
 * (the shell's padding, which the screen asks for).
 *
 * On a phone the actions are in the top bar, with the back arrow ([onClose]); wider windows show
 * them in a row above the subject, with [trailingActions] at its end and a back arrow only where
 * the reader covers the list ([onClose] set).
 *
 * @param showImages the reader asked for this message's remote images.
 */
@Composable
fun MessageReader(
    message: MailMessage,
    content: MailContent,
    showImages: Boolean,
    onToggleStar: () -> Unit,
    onToggleUnread: () -> Unit,
    onClose: (() -> Unit)?,
    onShowRemoteImages: () -> Unit,
    onAttachment: (MailAttachment) -> Unit,
    onRetry: () -> Unit,
    barsPadding: PaddingValues,
    body: @Composable (MailContent.Ready, Boolean, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    underBars: Boolean = true,
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
    // The bars' padding inside the scrolling page lets the page pass beneath them and still rest
    // clear of them at its ends; around the page it keeps the page between the bars.
    val bars = Modifier.padding(barsPadding)
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .then(if (underBars) Modifier else bars)
            .verticalScroll(scroll)
            .then(if (underBars) bars else Modifier),
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
        val strip = Modifier.padding(horizontal = dimens.pagePadding).padding(bottom = dimens.gapM)
        when (content) {
            is MailContent.Ready -> {
                if (content.attachments.isNotEmpty()) Attachments(content.attachments, onAttachment)
                if (content.remoteImages && !showImages) {
                    StatusBanner(
                        tone = BannerTone.Info,
                        text = stringResource(R.string.reader_remote_blocked),
                        modifier = strip,
                        actionLabel = stringResource(R.string.reader_remote_show),
                        onAction = onShowRemoteImages,
                    )
                }
                // a view of its own for every message
                key(message.id) { body(content, showImages, Modifier.fillMaxWidth()) }
            }
            MailContent.Loading -> ContentOnItsWay()
            is MailContent.Failed -> StatusBanner(
                tone = BannerTone.Warning,
                text = stringResource(
                    when (content.kind) {
                        MailException.Kind.Network -> R.string.reader_failed_network
                        MailException.Kind.Authentication -> R.string.reader_failed_login
                        else -> R.string.reader_failed_other
                    },
                ),
                modifier = strip,
                actionLabel = stringResource(R.string.action_retry),
                onAction = onRetry,
            )
            MailContent.Unavailable -> Text(
                stringResource(R.string.reader_unavailable),
                style = DeskTheme.type.body,
                color = colors.textSecondary,
                modifier = strip,
            )
        }
    }
}

/**
 * The place of a message's text while it is fetched. Text the device already has is there within
 * a frame or two; the placeholder only shows when the wait is long enough to see.
 */
@Composable
private fun ContentOnItsWay() {
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SHOW_PLACEHOLDER_AFTER_MS)
        waited = true
    }
    if (waited) SkeletonList(count = 4, leading = false, lines = 1)
}

private const val SHOW_PLACEHOLDER_AFTER_MS = 300L

/** The subject, then the sender's name and address, the recipients and the whole date. */
@Composable
private fun ReaderHeader(message: MailMessage) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val dates = rememberDateLabels()
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding).padding(top = dimens.gapM, bottom = dimens.gapL)) {
            Text(
                message.subject.ifBlank { stringResource(R.string.message_no_subject) },
                style = DeskTheme.type.headline,
                color = colors.text,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(dimens.gapM))
            Text(message.fromName, style = DeskTheme.type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // a sender without a name is already shown by the address
            if (message.fromAddress != message.fromName) {
                Text(message.fromAddress, style = DeskTheme.type.labelSmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(dimens.gapXs))
            Text(
                stringResource(R.string.message_to, message.to),
                style = DeskTheme.type.labelSmall,
                color = colors.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (message.cc.isNotEmpty()) {
                Text(
                    stringResource(R.string.message_cc, message.cc),
                    style = DeskTheme.type.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
