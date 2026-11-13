package com.deskmail.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.deskmail.R
import com.deskmail.mail.MailComposer
import com.deskmail.mail.MailSource
import com.deskmail.mail.SendCheck
import desktopkit.DesktopKitRoot
import desktopkit.components.ToastHost
import desktopkit.components.rememberToastState
import desktopkit.settings.DeskPreferences
import desktopkit.settings.rememberDeskPreferences
import kotlinx.coroutines.launch

/**
 * A window of its own that writes one message (DeX, Android's desktop windowing): the compose
 * screen and nothing else, with its notices at the bottom. It has no sidebar and no menu bar: the
 * system's caption bar names the window, the screen's own row holds its actions, and Ctrl+Enter
 * sends.
 *
 * The window goes once the message is sent, put away or thrown away; [onEnd] says which, once. The
 * screen goes with the first end (a message that was handed over is no longer one to write on, and
 * would close its screen again). What is left to do then is not this window's: it outlives none of
 * its own work.
 *
 * @param prefs the kit's look (theme, accent, density), the same in every window of the app.
 * @param onTitle what the window is to be called: the subject, once there is one.
 */
@Composable
fun WriteWindow(
    writing: Writing,
    composer: MailComposer,
    mail: MailSource,
    host: ComposeHost,
    onEnd: (WriteEnd) -> Unit,
    prefs: DeskPreferences = rememberDeskPreferences(),
    onTitle: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val toasts = rememberToastState()
    var ended by remember { mutableStateOf(false) }
    val end: (() -> WriteEnd) -> Unit = { how ->
        if (!ended) {
            ended = true
            onEnd(how())
        }
    }
    val title = writing.fields.subject.ifBlank { stringResource(R.string.compose_title) }
    LaunchedEffect(title) { onTitle(title) }
    DesktopKitRoot(prefs) { _ ->
        Box(Modifier.fillMaxSize()) {
            if (!ended) {
                ComposeScreen(
                    writing = writing,
                    composer = composer,
                    mail = mail,
                    scope = scope,
                    host = host,
                    onClose = { end { writing.closing() } },
                    onDiscard = {
                        end {
                            writing.fields.discarded()
                            WriteEnd.Discarded
                        }
                    },
                    onSent = { check -> end { WriteEnd.Sent(check) } },
                    onNotify = { text -> scope.launch { toasts.show(text) } },
                    ownWindow = true,
                )
            }
            ToastHost(toasts, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** How a window of its own came to its end. */
sealed interface WriteEnd {
    /** Closed with something in it that was worth keeping: the draft waits in the outbox. */
    data object Kept : WriteEnd

    /** Closed as it was begun, with nothing written into it: thrown away without a word. */
    data object Untouched : WriteEnd

    /** Thrown away on the user's word. */
    data object Discarded : WriteEnd

    /** Handed over; the message waits out [check]'s time to be taken back. */
    data class Sent(val check: SendCheck.Queued) : WriteEnd
}

/**
 * What closing the window makes of the message: kept, unless it was begun for this window and
 * nothing was written into it (then nothing is kept of it on the way out either).
 */
fun Writing.closing(): WriteEnd {
    if (!fresh || fields.touched) return WriteEnd.Kept
    fields.discarded()
    return WriteEnd.Untouched
}
