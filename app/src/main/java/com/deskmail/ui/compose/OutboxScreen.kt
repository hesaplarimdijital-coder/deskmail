package com.deskmail.ui.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deskmail.R
import com.deskmail.mail.Draft
import com.deskmail.mail.DraftState
import com.deskmail.mail.MailComposer
import com.deskmail.ui.MailIcons
import com.deskmail.ui.mail.rememberDateLabels
import desktopkit.components.BannerTone
import desktopkit.components.DeskIcons
import desktopkit.components.DeskListRow
import desktopkit.components.MenuEntry
import desktopkit.components.StatusBanner
import desktopkit.screens.MessageScreen
import desktopkit.screens.PageHeader
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme

/**
 * The outbox: what is being written, what waits to be sent, and what could not be sent, the
 * latest touched first. A draft opens to be written on; a message that waits can be sent now or
 * taken back; one that failed says why, and can be tried again or changed.
 *
 * @param onOpen takes a message up in the compose screen; one that waits is taken back for it.
 * @param onDelete throws a message away.
 */
@Composable
fun OutboxScreen(
    composer: MailComposer,
    onOpen: (Draft) -> Unit,
    onDelete: (Draft) -> Unit,
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val dates = rememberDateLabels()
    val drafts = composer.outbox
    val draftLabel = stringResource(R.string.outbox_draft)
    val waitingLabel = stringResource(R.string.outbox_waiting)
    val failedLabel = stringResource(R.string.outbox_failed)
    val nobody = stringResource(R.string.outbox_no_recipients)
    val noSubject = stringResource(R.string.message_no_subject)
    val editLabel = stringResource(R.string.outbox_edit)
    val sendNowLabel = stringResource(R.string.outbox_send_now)
    val retryLabel = stringResource(R.string.outbox_retry)
    val takeBackLabel = stringResource(R.string.outbox_take_back)
    val deleteLabel = stringResource(R.string.outbox_delete)
    val interrupted = stringResource(R.string.outbox_interrupted)
    val unreachable = stringResource(R.string.outbox_unreachable)
    if (drafts.isEmpty()) {
        MessageScreen(
            title = stringResource(R.string.outbox_empty_title),
            icon = MailIcons.Sent,
            pageTitle = stringResource(R.string.nav_outbox),
            description = stringResource(R.string.outbox_empty_text),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = rememberShellContentPadding()) {
        item(key = "header") { PageHeader(stringResource(R.string.nav_outbox)) }
        items(drafts, key = { it.id }) { draft ->
            val state = when (draft.state) {
                DraftState.Writing -> draftLabel
                DraftState.Waiting -> waitingLabel
                DraftState.Failed -> failedLabel
            }
            val open: () -> Unit = { onOpen(draft) }
            DeskListRow(
                title = draft.to.ifBlank { nobody },
                subtitle = previewOf(draft, noSubject),
                trailing = state,
                trailingColor = if (draft.state == DraftState.Failed) colors.danger else colors.textSecondary,
                trailingSub = dates.short(draft.updatedAt),
                strong = draft.state != DraftState.Writing,
                onClick = open,
                menu = {
                    buildList {
                        when (draft.state) {
                            DraftState.Writing -> add(MenuEntry.Item(editLabel, open))
                            DraftState.Waiting -> {
                                add(MenuEntry.Item(sendNowLabel, { composer.sendNow(draft.id) }, icon = MailIcons.Sent))
                                add(MenuEntry.Item(takeBackLabel, open))
                            }
                            DraftState.Failed -> {
                                add(MenuEntry.Item(retryLabel, { composer.sendNow(draft.id) }, icon = MailIcons.Refresh))
                                add(MenuEntry.Item(editLabel, open))
                            }
                        }
                        add(MenuEntry.Separator)
                        add(MenuEntry.Item(deleteLabel, { onDelete(draft) }, icon = DeskIcons.Delete, destructive = true))
                    }
                },
                // a tap does not take a message back that is about to leave: it asks what to do with it
                openMenuOnTap = draft.state == DraftState.Waiting,
            )
            val under = Modifier.padding(horizontal = dimens.pagePadding).padding(bottom = dimens.gapS, top = 2.dp)
            when {
                // why it did not go, in the server's words; a message cut short is put into the app's own
                draft.state == DraftState.Failed && draft.error.isNotBlank() -> StatusBanner(
                    tone = BannerTone.Warning,
                    text = if (draft.error == Draft.INTERRUPTED) interrupted else draft.error,
                    modifier = under,
                    actionLabel = retryLabel,
                    onAction = { composer.sendNow(draft.id) },
                )
                // tried, and the server was not to be reached: it goes by itself when it is
                draft.state == DraftState.Waiting && draft.error.isNotBlank() -> StatusBanner(
                    tone = BannerTone.Info,
                    text = unreachable,
                    modifier = under,
                    actionLabel = retryLabel,
                    onAction = { composer.sendNow(draft.id) },
                )
            }
        }
    }
}

/** The subject and the first words of what was written, on one line. */
private fun previewOf(draft: Draft, noSubject: String): String {
    val subject = draft.subject.ifBlank { noSubject }
    val words = draft.body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith(">") }.orEmpty()
    return if (words.isEmpty()) subject else "$subject — ${words.take(PREVIEW_CHARS)}"
}

private const val PREVIEW_CHARS = 120
