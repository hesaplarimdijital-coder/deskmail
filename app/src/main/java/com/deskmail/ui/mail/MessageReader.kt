package com.deskmail.ui.mail

import android.text.format.Formatter
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.deskmail.R
import com.deskmail.core.mail.MailException
import com.deskmail.mail.DraftKind
import com.deskmail.mail.MailAttachment
import com.deskmail.mail.MailContent
import com.deskmail.mail.MailMessage
import com.deskmail.ui.MailIcons
import desktopkit.components.ActionGroup
import desktopkit.components.BannerTone
import desktopkit.components.DeskButton
import desktopkit.components.DeskChip
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.DeskProgressBar
import desktopkit.components.MenuEntry
import desktopkit.components.MoreSheet
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
 * On a phone the actions are in the top bar, with the back arrow ([onClose]): archive and delete,
 * the rest behind the bar's "more". Wider windows show them in a row above the subject, with
 * [trailingActions] at its end and a back arrow only where the reader covers the list ([onClose]
 * set). The star is beside the sender in every layout.
 *
 * Answering is offered where the hand is when the message has been read: under its text, as
 * "Reply", "Reply all" and "Forward". A phone has "Reply" beside the sender as well, and the
 * rest behind the bar's "more"; a wider window has the three at the start of its row of actions.
 *
 * @param showImages the reader asked for this message's remote images.
 * @param onWrite begins a reply (to the sender, to everybody) or sends the message on; null
 *   where nothing can be written.
 * @param onArchive null where the message has no archive to go to (it is in it, or its account has none).
 * @param onMove asks which folder to move it to; null where there is no other folder.
 * @param onAttachment what to do with a file of the message: open, save or share it.
 * @param fetchingFile a file is on its way from the server.
 * @param draft the message is a draft that lies in its account's Drafts folder: there is nothing
 *   to answer, it is taken up to be written on ([DraftKind.Edit]).
 */
@Composable
fun MessageReader(
    message: MailMessage,
    content: MailContent,
    showImages: Boolean,
    onToggleStar: () -> Unit,
    onToggleUnread: () -> Unit,
    onWrite: ((DraftKind) -> Unit)?,
    onArchive: (() -> Unit)?,
    onDelete: () -> Unit,
    onMove: (() -> Unit)?,
    onClose: (() -> Unit)?,
    onShowRemoteImages: () -> Unit,
    onAttachment: (MailAttachment, AttachmentAction) -> Unit,
    onRetry: () -> Unit,
    barsPadding: PaddingValues,
    body: @Composable (MailContent.Ready, Boolean, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    underBars: Boolean = true,
    fetchingFile: Boolean = false,
    draft: Boolean = false,
    trailingActions: @Composable RowScope.() -> Unit = {},
    onAlwaysShowImages: (() -> Unit)? = null,
    vip: Boolean? = null,
    onToggleVip: () -> Unit = {},
    conversation: @Composable () -> Unit = {},
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val compact = currentWidthClass() == WidthClass.Compact
    val unreadIcon = if (message.unread) MailIcons.MailOpen else MailIcons.Mail
    val unreadLabel = stringResource(if (message.unread) R.string.action_mark_read else R.string.action_mark_unread)
    val archiveLabel = stringResource(R.string.action_archive)
    val deleteLabel = stringResource(R.string.action_delete)
    val moveLabel = stringResource(R.string.action_move)
    val replyLabel = stringResource(R.string.action_reply)
    val replyAllLabel = stringResource(R.string.action_reply_all)
    val forwardLabel = stringResource(R.string.action_forward)
    val editLabel = stringResource(R.string.action_edit_draft)
    val vipLabel = stringResource(if (vip == true) R.string.action_vip_remove else R.string.action_vip_add)
    val vipIcon = if (vip == true) MailIcons.VipFilled else MailIcons.Vip
    // what there is to write from here: on with the draft itself, or an answer to the message
    val answers = onWrite?.takeIf { !draft }
    val edit: (() -> Unit)? = onWrite?.takeIf { draft }?.let { write -> { write(DraftKind.Edit) } }
    if (onClose != null) {
        // a phone: back and the actions in the top bar (nothing happens on wider windows)
        CompactTopBar(
            onBack = onClose,
            actions = listOfNotNull(
                onArchive?.let { TopBarAction(MailIcons.Archive, archiveLabel, it) },
                edit?.let { TopBarAction(MailIcons.Compose, editLabel, it, primary = true) },
                TopBarAction(DeskIcons.Delete, deleteLabel, onDelete),
                answers?.let { write -> TopBarAction(MailIcons.Reply, replyLabel, { write(DraftKind.Reply) }) },
                answers?.let { write -> TopBarAction(MailIcons.ReplyAll, replyAllLabel, { write(DraftKind.ReplyAll) }) },
                answers?.let { write -> TopBarAction(MailIcons.ForwardMail, forwardLabel, { write(DraftKind.Forward) }) },
                TopBarAction(unreadIcon, unreadLabel, onToggleUnread),
                onMove?.let { TopBarAction(MailIcons.MoveTo, moveLabel, it) },
                vip?.let { TopBarAction(vipIcon, vipLabel, onToggleVip) },
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
                if (answers != null) {
                    DeskIconButton(MailIcons.Reply, replyLabel, { answers(DraftKind.Reply) })
                    DeskIconButton(MailIcons.ReplyAll, replyAllLabel, { answers(DraftKind.ReplyAll) })
                    DeskIconButton(MailIcons.ForwardMail, forwardLabel, { answers(DraftKind.Forward) })
                }
                if (edit != null) DeskIconButton(MailIcons.Compose, editLabel, edit)
                Spacer(Modifier.weight(1f))
                if (onArchive != null) DeskIconButton(MailIcons.Archive, archiveLabel, onArchive)
                DeskIconButton(DeskIcons.Delete, deleteLabel, onDelete)
                if (onMove != null) DeskIconButton(MailIcons.MoveTo, moveLabel, onMove)
                DeskIconButton(unreadIcon, unreadLabel, onToggleUnread)
                if (vip != null) DeskIconButton(vipIcon, vipLabel, onToggleVip)
                trailingActions()
            }
        }
        // the other messages of its conversation, to go to
        conversation()
        // a phone has no row of actions above the subject: "Reply" is beside the sender there
        ReaderHeader(message, onToggleStar, onReply = if (compact && answers != null) ({ answers(DraftKind.Reply) }) else null)
        val strip = Modifier.padding(horizontal = dimens.pagePadding).padding(bottom = dimens.gapM)
        when (content) {
            is MailContent.Ready -> {
                if (content.attachments.isNotEmpty()) Attachments(content.attachments, fetchingFile, onAttachment)
                if (content.remoteImages && !showImages) {
                    StatusBanner(
                        tone = BannerTone.Info,
                        text = stringResource(R.string.reader_remote_blocked),
                        modifier = strip,
                        actionLabel = stringResource(R.string.reader_remote_show),
                        onAction = onShowRemoteImages,
                    )
                    // the sender's pictures every time, without being asked again
                    if (onAlwaysShowImages != null) {
                        DeskButton(
                            stringResource(R.string.reader_remote_always),
                            onAlwaysShowImages,
                            Modifier.padding(horizontal = dimens.pagePadding).padding(bottom = dimens.gapM),
                        )
                    }
                }
                // a view of its own for every message
                key(message.id) { body(content, showImages, Modifier.fillMaxWidth()) }
                if (answers != null) AnswerRow(answers)
                if (edit != null) EditRow(edit)
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

/** "Reply", "Reply all" and "Forward" under a message's text, on one line that scrolls sideways where it has to. */
@Composable
private fun AnswerRow(onWrite: (DraftKind) -> Unit) {
    val dimens = DeskTheme.dimens
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = dimens.pagePadding).padding(top = dimens.gapL, bottom = dimens.gapXl),
        horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeskButton(stringResource(R.string.action_reply), { onWrite(DraftKind.Reply) }, icon = MailIcons.Reply)
        DeskButton(stringResource(R.string.action_reply_all), { onWrite(DraftKind.ReplyAll) }, icon = MailIcons.ReplyAll)
        DeskButton(stringResource(R.string.action_forward), { onWrite(DraftKind.Forward) }, icon = MailIcons.ForwardMail)
    }
}

/** Under a draft's text: the way on with it. */
@Composable
private fun EditRow(onEdit: () -> Unit) {
    val dimens = DeskTheme.dimens
    Row(Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding).padding(top = dimens.gapL, bottom = dimens.gapXl)) {
        DeskButton(stringResource(R.string.draft_continue), onEdit, icon = MailIcons.Compose, primary = true)
    }
}

/**
 * The subject, then the sender's name and address with the star beside them (and "Reply", with
 * [onReply]), the recipients and the whole date.
 */
@Composable
private fun ReaderHeader(message: MailMessage, onToggleStar: () -> Unit, onReply: (() -> Unit)?) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val dates = rememberDateLabels()
    val starIcon = if (message.starred) MailIcons.StarFilled else MailIcons.Star
    val starLabel = stringResource(if (message.starred) R.string.action_unstar else R.string.action_star)
    Row(
        Modifier.fillMaxWidth().padding(start = dimens.pagePadding, end = dimens.pagePadding - 8.dp).padding(top = dimens.gapM, bottom = dimens.gapL),
        verticalAlignment = Alignment.Top,
    ) {
        SelectionContainer(Modifier.weight(1f)) {
            Column {
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
        if (onReply != null) DeskIconButton(MailIcons.Reply, stringResource(R.string.action_reply), onReply)
        DeskIconButton(starIcon, starLabel, onToggleStar, tint = if (message.starred) colors.warning else colors.textSecondary)
    }
}

/**
 * The attachments as chips on one line that scrolls sideways: name and size. A chip opens what
 * can be done with its file; a thin line under the chips shows that one is being fetched ([busy]).
 */
@Composable
private fun Attachments(attachments: List<MailAttachment>, busy: Boolean, onAttachment: (MailAttachment, AttachmentAction) -> Unit) {
    val context = LocalContext.current
    val dimens = DeskTheme.dimens
    Column(Modifier.fillMaxWidth().padding(bottom = dimens.gapM)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = dimens.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            attachments.forEach { attachment ->
                AttachmentChip(attachment, Formatter.formatShortFileSize(context, attachment.bytes), onAttachment)
            }
        }
        if (busy) {
            DeskProgressBar(
                fraction = null,
                modifier = Modifier.padding(horizontal = dimens.pagePadding).padding(top = dimens.gapS),
                height = 2.dp,
                contentDescription = stringResource(R.string.attachment_fetching),
            )
        }
    }
}

/** A file of the message; tapping it offers to open, save or share it (a sheet on a phone, a menu under the chip elsewhere). */
@Composable
private fun AttachmentChip(attachment: MailAttachment, size: String, onAttachment: (MailAttachment, AttachmentAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(0) }
    val actions = listOf(
        MenuEntry.Item(stringResource(R.string.attachment_open), { onAttachment(attachment, AttachmentAction.Open) }),
        MenuEntry.Item(stringResource(R.string.attachment_save), { onAttachment(attachment, AttachmentAction.Save) }),
        MenuEntry.Item(stringResource(R.string.attachment_share), { onAttachment(attachment, AttachmentAction.Share) }, icon = MailIcons.Share),
    )
    Box(Modifier.onSizeChanged { height = it.height }) {
        DeskChip(
            text = attachment.name,
            selected = false,
            onClick = { open = true },
            icon = MailIcons.Attachment,
            count = size,
        )
        MoreSheet(
            visible = open,
            onDismiss = { open = false },
            groups = if (open) listOf(ActionGroup(null, actions)) else emptyList(),
            title = attachment.name,
            menuOffset = IntOffset(0, height),
        )
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
