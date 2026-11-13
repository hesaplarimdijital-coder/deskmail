package com.deskmail

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.deskmail.ui.Commands
import com.deskmail.ui.KeyRouter
import com.deskmail.ui.Routes
import com.deskmail.ui.compose.ComposeHost
import com.deskmail.ui.compose.WriteEnd
import com.deskmail.ui.compose.WriteWindow
import com.deskmail.ui.compose.Writing
import com.deskmail.ui.compose.closing
import com.deskmail.ui.isTyping
import com.deskmail.ui.shortcuts
import kotlinx.coroutines.launch

/**
 * A window of its own that writes one message: on DeX and on Android's desktop windowing, writing
 * opens here, beside the window with the mail ([com.deskmail.ui.compose.WriteApart]). The window
 * goes once the message is sent, put away or thrown away (also by the system's own close button);
 * the window it was opened from says what came of it, and offers the way back after "Send".
 */
class WriteActivity : ComponentActivity() {

    private val state: WriteState by lazy { ViewModelProvider(this)[WriteState::class.java] }

    /** The message this window writes. */
    private var draftId = 0L

    /** The window with the mail this one was opened from: its task. */
    private var opener = 0

    /** Whether the window came to its end through its own buttons; otherwise the system closed it. */
    private var ended = false

    /** Ctrl+Enter sends; the other keys of the table are the mail's, and do nothing here. */
    private val keys = KeyRouter(
        registry = shortcuts,
        paletteOpen = { false },
        typing = { isTyping(window.decorView, currentFocus) },
        perform = { id -> perform(id) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        draftId = intent.getLongExtra(EXTRA_DRAFT, 0L)
        opener = intent.getIntExtra(EXTRA_OPENER, 0)
        if (draftId <= 0L) {
            ended = true
            finishAndRemoveTask()
            return
        }
        val begunFresh = intent.getBooleanExtra(EXTRA_FRESH, false)
        val writing = state.writing ?: Writing(draftId, Routes.OUTBOX, fresh = fresh(restored = savedInstanceState != null, begunFresh)).also { state.writing = it }
        deskMail.writeWindows.opened(draftId, taskId)
        setContent {
            WriteWindow(
                writing = writing,
                composer = deskMail.composer,
                mail = deskMail.mail,
                host = state.host,
                onEnd = ::end,
                onTitle = ::name,
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keys.route(event) || super.dispatchKeyEvent(event)

    private fun perform(id: String): Boolean = when (id) {
        Commands.SEND -> state.host.send?.let { send ->
            send()
            true
        } ?: false
        Commands.CLOSE -> {
            if (!ended) state.writing?.let { end(it.closing()) }
            true
        }
        else -> false
    }

    /** The window is called by what it writes, where the desktop lists its windows. */
    private fun name(title: String) {
        setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(title).build())
    }

    /**
     * The message is done with here: what came of it is told to the window it was opened from (or
     * to another window with the mail), and this window goes. What still has to be done for the
     * message is done by the app, not by this window, which is going.
     */
    private fun end(how: WriteEnd) {
        if (ended) return
        ended = true
        conclude(how)
        finishAndRemoveTask()
    }

    private fun conclude(how: WriteEnd) {
        val app = deskMail
        val id = draftId
        val from = opener
        when (how) {
            WriteEnd.Kept -> app.writeWindows.homeFor(from)?.say(getString(R.string.compose_kept))
            WriteEnd.Untouched -> app.background.launch { app.composer.discard(id) }
            WriteEnd.Discarded -> app.background.launch {
                val gone = app.composer.discard(id)
                app.writeWindows.homeFor(from)?.say(app.getString(if (gone) R.string.compose_discarded else R.string.compose_too_late))
            }
            // without a window to offer "Undo" in, there is no reason to wait
            is WriteEnd.Sent -> app.writeWindows.homeFor(from)?.sentApart(id, how.check) ?: app.composer.sendNow(id)
        }
        app.writeWindows.closed(id, taskId)
    }

    override fun onDestroy() {
        // closed by the system's own button (or with the task): the same as the screen's close button
        if (isFinishing && !ended) {
            ended = true
            state.writing?.let { conclude(it.closing()) }
        }
        super.onDestroy()
    }

    companion object {
        /**
         * Whether the window writes a message begun just now, to be thrown away when it is closed
         * untouched: only as it was first opened. A window the system makes anew, after it ended the
         * app's process, reads the message as it was kept, with what was typed into it, and would
         * take that for "untouched": the message, and its copy among the server's drafts, would go.
         */
        internal fun fresh(restored: Boolean, begunFresh: Boolean): Boolean = begunFresh && !restored

        private const val EXTRA_DRAFT = "com.deskmail.extra.DRAFT"
        private const val EXTRA_FRESH = "com.deskmail.extra.FRESH"
        private const val EXTRA_OPENER = "com.deskmail.extra.OPENER"

        /**
         * Writes the message [draftId] in a window of its own. [fresh]: begun just now, so that closed
         * untouched it is thrown away; [opener]: the task of the window with the mail it is opened from.
         */
        fun intent(context: Context, draftId: Long, fresh: Boolean, opener: Int): Intent =
            Intent(context, WriteActivity::class.java)
                .putExtra(EXTRA_DRAFT, draftId)
                .putExtra(EXTRA_FRESH, fresh)
                .putExtra(EXTRA_OPENER, opener)
    }
}

/** What the window writes, kept when something recreates its activity. */
class WriteState : ViewModel() {
    var writing: Writing? = null
    val host = ComposeHost()
}
