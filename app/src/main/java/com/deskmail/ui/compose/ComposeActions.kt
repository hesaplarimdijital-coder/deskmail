package com.deskmail.ui.compose

import android.content.res.Resources
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deskmail.R
import com.deskmail.mail.Draft
import com.deskmail.mail.DraftKind
import com.deskmail.mail.DraftState
import com.deskmail.mail.MailComposer
import com.deskmail.mail.MailSource
import com.deskmail.mail.SendCheck
import com.deskmail.ui.Routes
import com.deskmail.ui.mail.MailUiState
import desktopkit.components.ToastDuration
import desktopkit.components.ToastResult
import desktopkit.components.ToastState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a window does about writing on the user's word: begins a message (a new one, a reply, a
 * forwarded one), takes one up from the outbox, puts one away, and, once a message was handed
 * over, says so and offers the way back.
 *
 * "Send" does not send at once. The message waits ([SendCheck.Queued.undoMs]) with a notice on
 * screen that offers "Undo"; taken back, it is open to be written on again. When the notice goes
 * (its time is up, it was swiped away, another notice took its place) the message is sent
 * without waiting any longer.
 *
 * A message that was begun here and closed with nothing written into it is thrown away: the
 * outbox does not fill with empty drafts.
 *
 * @param scope where the work runs: the window's, so a notice does not outlive it. A message
 *   whose window closes while it waits is sent when its time is up all the same.
 */
@Stable
class ComposeActions(
    private val composer: MailComposer,
    private val mail: MailSource,
    private val ui: MailUiState,
    private val scope: CoroutineScope,
    private val toasts: ToastState,
    private val resources: Resources,
) {
    /** Where the window finds the "Send" of the compose screen in front (Ctrl+Enter). */
    val host = ComposeHost()

    /** The screen in front, told by the window: where a message that was taken back opens again. */
    var front: () -> String = { Routes.INBOX }

    /** The message whose "Sending…" notice is on screen right now. */
    private var noticed by mutableStateOf<Long?>(null)

    /** Whether there is a "Send" for Ctrl+Z to take back. */
    val canUndo: Boolean get() = noticed != null

    /**
     * Begins a message over the screen [route]: an empty one, or what [kind] takes from the
     * message [sourceId]. A new message is from the account whose folder is in front, else from
     * the first account.
     */
    fun begin(route: String, kind: DraftKind = DraftKind.New, sourceId: Long? = null) {
        scope.launch {
            val accountId = Routes.folderId(route)?.let { mail.folder(it)?.accountId }
            val id = composer.start(kind, accountId, sourceId)
            if (id == null) {
                toasts.show(resources.getString(if (mail.accounts.isEmpty()) R.string.compose_no_account else R.string.compose_gone))
                return@launch
            }
            putAway()
            ui.writing = Writing(id, route, fresh = true)
        }
    }

    /**
     * Begins a new message over [route] with what another app asked for: recipients, a subject,
     * a text, files. Closed with nothing written into it, a message that only had its
     * recipients is thrown away like any other that was begun and left; one that came with more
     * stays in the outbox.
     *
     * The screen opens at once and the files are copied into the message behind it: a video takes
     * its time, and until it is in the message there would be nothing to see of what was asked
     * for. The screen says that files are on their way, and does not send before they are in.
     */
    fun begin(route: String, request: ComposeRequest) {
        scope.launch {
            val accountId = Routes.folderId(route)?.let { mail.folder(it)?.accountId }
            val id = composer.start(DraftKind.New, accountId, seed = request.seed)
            if (id == null) {
                toasts.show(resources.getString(R.string.compose_no_account))
                return@launch
            }
            putAway()
            val writing = Writing(id, route, fresh = !request.hasContent)
            ui.writing = writing
            if (request.files.isEmpty()) return@launch
            val fields = writing.fields
            fields.attachBegun(request.files.size)
            var failed = false
            for (file in request.files) {
                // to its end, whatever becomes of the window meanwhile: half a file is of no use to the message
                val added = withContext(NonCancellable) { composer.attach(id, file.name, file.mimeType, file.open) }
                fields.attachEnded(added)
                if (!added) failed = true
            }
            // thrown away meanwhile: there is nothing to say about its files
            if (failed && !fields.thrownAway) toasts.show(resources.getString(R.string.compose_attach_failed))
        }
    }

    /** Takes a message of the outbox up over [route]. One that waits to be sent is taken back first. */
    fun takeUp(route: String, draft: Draft) {
        scope.launch {
            if (draft.state == DraftState.Waiting && !composer.unsend(draft.id)) {
                toasts.show(resources.getString(R.string.compose_too_late))
                return@launch
            }
            if (ui.writing?.draftId != draft.id) putAway()
            ui.writing = Writing(draft.id, route)
        }
    }

    /** Closes the compose screen. What was written stays in the outbox, and the screen says so. */
    fun close() {
        if (putAway()) scope.launch { toasts.show(resources.getString(R.string.compose_kept)) }
    }

    /**
     * Puts away the message that is being written: it is kept in the outbox, unless it was begun
     * here and nothing was written into it. True when there is a draft to speak of.
     */
    private fun putAway(): Boolean {
        val writing = ui.writing ?: return false
        ui.writing = null
        if (!writing.fresh || writing.fields.touched) return true
        writing.fields.discarded()
        scope.launch { composer.discard(writing.draftId) }
        return false
    }

    /** Throws a message of the outbox away, and says what came of it. */
    fun discard(draft: Draft) = discard(draft.id)

    /** Throws away the message that is being written: its screen goes, and nothing of it is kept on the way out. */
    fun discard(writing: Writing) = discard(writing.draftId)

    private fun discard(id: Long) {
        ui.writing?.takeIf { it.draftId == id }?.let { open ->
            open.fields.discarded()
            ui.writing = null
        }
        scope.launch {
            val gone = composer.discard(id)
            toasts.show(resources.getString(if (gone) R.string.compose_discarded else R.string.compose_too_late))
        }
    }

    /**
     * The compose screen handed [writing]'s message over: the screen closes, and for as long as the
     * message waits a notice offers to take it back.
     */
    fun sent(writing: Writing, check: SendCheck.Queued) {
        if (ui.writing === writing) ui.writing = null
        val id = writing.draftId
        scope.launch {
            noticed = id
            val result = try {
                // up for exactly as long as the message waits; gone sooner when something else is to be said
                withTimeoutOrNull(check.undoMs) {
                    toasts.show(
                        resources.getString(R.string.compose_sending),
                        actionLabel = resources.getString(R.string.action_undo),
                        duration = ToastDuration.Indefinite,
                        shortcut = UNDO_KEYS,
                    )
                }
            } finally {
                if (noticed == id) noticed = null
            }
            if (result != ToastResult.ActionPerformed) {
                // the offer is gone: no reason to wait any longer
                composer.sendNow(id)
            } else if (!composer.unsend(id)) {
                toasts.show(resources.getString(R.string.compose_too_late))
            } else if (ui.writing == null) {
                ui.writing = Writing(id, front())
            } else {
                // something else is being written by now: this one waits in the outbox
                toasts.show(resources.getString(R.string.compose_kept))
            }
        }
    }

    /** Ctrl+Z while the "Sending…" notice is up: the same as the notice's own "Undo". False when there is none. */
    fun undoSend(): Boolean {
        val toast = toasts.current
        if (noticed == null || toast == null) return false
        toast.performAction()
        return true
    }

    private companion object {
        /** Shown beside "Undo" where there is a keyboard. */
        const val UNDO_KEYS = "Ctrl+Z"
    }
}
