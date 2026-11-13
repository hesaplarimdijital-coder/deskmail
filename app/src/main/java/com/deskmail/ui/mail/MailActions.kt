package com.deskmail.ui.mail

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deskmail.R
import com.deskmail.core.mail.FolderRole
import com.deskmail.mail.MailMessage
import com.deskmail.mail.MailSource
import com.deskmail.mail.MailUndo
import com.deskmail.ui.folderIcon
import com.deskmail.ui.folderPathLabel
import desktopkit.appResources
import desktopkit.components.ActionGroup
import desktopkit.components.DeskDialog
import desktopkit.components.DeskListRow
import desktopkit.components.MenuEntry
import desktopkit.components.MoreSheet
import desktopkit.components.ToastResult
import desktopkit.components.ToastState
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * What a window does to messages on the user's word, whoever passes that word on: a button of
 * the reader, a row's menu, a key. The mail does the work ([MailSource]); this closes a reader
 * whose message has left, says what was done, and offers the way back: "Undo" for as long as the
 * notice is up, Ctrl+Z ([undoLast]) after that as well.
 *
 * While the notice is up the server has not been told: taking it back costs nothing. When the
 * notice goes, the server is told ([MailSource.commit]).
 *
 * @param scope where the work runs: the window's, so a notice does not outlive it.
 */
@Stable
class MailActions(
    private val mail: MailSource,
    private val ui: MailUiState,
    private val scope: CoroutineScope,
    private val toasts: ToastState,
    private val resources: Resources,
) {
    /** What was done last and can still be taken back. */
    private var last by mutableStateOf<MailUndo?>(null)

    /** The one whose notice is on screen right now. */
    private var noticed: MailUndo? = null

    /** Whether there is something for Ctrl+Z to take back. */
    val canUndo: Boolean get() = last != null

    fun archive(ids: List<Long>) = act(R.string.archive_nowhere) { mail.archive(ids) }

    fun delete(ids: List<Long>) = act(R.string.action_nothing_done) { mail.delete(ids) }

    fun move(ids: List<Long>, folderId: Long) = act(R.string.action_nothing_done) { mail.move(ids, folderId) }

    /** Snoozes the messages until [wakeAt]; where the account's Snoozed folder could not be had, says so. */
    fun snooze(ids: List<Long>, wakeAt: Instant) = act(R.string.snooze_failed) { mail.snooze(ids, wakeAt) }

    fun unsnooze(ids: List<Long>) = act(R.string.action_nothing_done) { mail.unsnooze(ids) }

    /** Takes back what was done last; false when there is nothing to take back. */
    fun undoLast(): Boolean {
        val undo = last ?: return false
        last = null
        // its notice is still up: the same as the notice's own "Undo"
        val toast = toasts.current
        if (noticed === undo && toast != null) toast.performAction() else scope.launch { takeBack(undo) }
        return true
    }

    private fun act(nothingDone: Int, work: suspend () -> MailUndo?) {
        scope.launch {
            val undo = work()
            if (undo == null) {
                toasts.show(resources.getString(nothingDone))
                return@launch
            }
            // no reader goes on showing a message that has left its list
            ui.closeMessages(undo.messages)
            last = undo
            noticed = undo
            val result = try {
                toasts.show(label(undo), actionLabel = resources.getString(R.string.action_undo), shortcut = UNDO_KEYS)
            } finally {
                if (noticed === undo) noticed = null
            }
            if (result == ToastResult.ActionPerformed) {
                if (last === undo) last = null
                takeBack(undo)
            } else {
                // the notice is gone, and the easy way back with it: the server is told
                mail.commit(undo)
            }
        }
    }

    private suspend fun takeBack(undo: MailUndo) {
        val back = mail.undo(undo)
        if (back < undo.count) toasts.show(resources.getString(R.string.undo_failed))
    }

    private fun label(undo: MailUndo): String {
        val count = undo.count
        return when (undo.action) {
            MailUndo.Action.Archived -> resources.getQuantityString(R.plurals.done_archived, count, count)
            // the ones that were in the trash already are gone for good: not to be read as "in the trash" with the others
            MailUndo.Action.Deleted -> if (undo.changes.any { it.to == 0L }) {
                resources.getQuantityString(R.plurals.done_deleted_some_for_good, count, count)
            } else {
                resources.getQuantityString(R.plurals.done_deleted, count, count)
            }
            MailUndo.Action.DeletedForGood -> resources.getQuantityString(R.plurals.done_removed, count, count)
            MailUndo.Action.Moved -> {
                val folder = mail.folder(undo.folder)?.let { folderPathLabel(resources, it) }.orEmpty()
                resources.getQuantityString(R.plurals.done_moved, count, count, folder)
            }
            MailUndo.Action.Snoozed -> resources.getQuantityString(R.plurals.done_snoozed, count, count)
            MailUndo.Action.Unsnoozed -> resources.getQuantityString(R.plurals.done_unsnoozed, count, count)
        }
    }

    private companion object {
        /** Shown beside "Undo" where there is a keyboard. */
        const val UNDO_KEYS = "Ctrl+Z"
    }
}

/**
 * Whether "Archive" has anywhere to take the message: it lies in the inbox or in a folder of the
 * user's own, and its account has an archive (or, as Gmail, "All Mail").
 */
fun archivable(mail: MailSource, message: MailMessage): Boolean {
    val role = mail.folder(message.folderId)?.role ?: return false
    if (role != FolderRole.Inbox && role != FolderRole.Other) return false
    return mail.folders.any { it.accountId == message.accountId && (it.role == FolderRole.Archive || it.role == FolderRole.All) }
}

/**
 * The folders [message] can be moved to, to choose one from: a sheet on a phone, a dialog in a
 * wider window. Nothing is shown while [message] is null.
 */
@Composable
fun MoveChooser(message: MailMessage?, mail: MailSource, onMove: (MailMessage, Long) -> Unit, onDismiss: () -> Unit) {
    val resources = appResources()
    val title = stringResource(R.string.move_title)
    val targets = if (message == null) emptyList() else mail.moveTargets(message)
    if (currentWidthClass() == WidthClass.Compact) {
        val entries = if (message == null) emptyList() else targets.map { folder ->
            MenuEntry.Item(folderPathLabel(resources, folder), { onMove(message, folder.id) }, icon = folderIcon(folder.role))
        }
        MoreSheet(visible = message != null, onDismiss = onDismiss, groups = listOf(ActionGroup(null, entries)), title = title)
    } else if (message != null) {
        DeskDialog(onDismiss = onDismiss, title = title, maxWidth = 420.dp) {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                targets.forEach { folder ->
                    DeskListRow(
                        title = folderPathLabel(resources, folder),
                        leading = folderIcon(folder.role),
                        onClick = {
                            onDismiss()
                            onMove(message, folder.id)
                        },
                    )
                }
            }
        }
    }
}
