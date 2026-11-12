package com.deskmail.ui

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import com.deskmail.R
import desktopkit.input.CommonShortcuts
import desktopkit.input.KeyChord
import desktopkit.input.Shortcut
import desktopkit.input.ShortcutGroup
import desktopkit.input.ShortcutRegistry
import desktopkit.input.ShortcutScope

/** The app's commands by name: what a shortcut, a menu entry or the palette asks for. */
object Commands {
    const val NEW_MESSAGE = "new_message"
    const val REFRESH = "refresh"
    const val NEXT = "message.next"
    const val PREVIOUS = "message.previous"
    const val STAR = "message.star"
    const val TOGGLE_READ = "message.toggle_read"
    const val CLOSE = "message.close"
    const val FIND = "message.find"
}

val FileGroup = ShortcutGroup("file", R.string.menu_file)
val MessageGroup = ShortcutGroup("message", R.string.menu_message)

/**
 * Where the single keys work: a list of messages or an open message, while nothing is being
 * typed (see [KeyRouter]).
 */
val MailScope = ShortcutScope("mail")

/**
 * Every shortcut of the app in one table: the menu bar, the command palette, the system's list of
 * shortcuts and the list in Settings are all drawn from it.
 *
 * Ctrl+N writes a new message, as in every mail program. The kit's "New window" is Ctrl+N by
 * default and left out of its common set for that reason; here it is Ctrl+Shift+N, under the
 * kit's own name. F5 asks the servers for what is new.
 */
val shortcuts = ShortcutRegistry(
    groups = listOf(FileGroup, MessageGroup, CommonShortcuts.ViewGroup, CommonShortcuts.HelpGroup),
    shortcuts = listOf(
        Shortcut(Commands.NEW_MESSAGE, FileGroup, R.string.action_new_message, KeyChord(Key.N, ctrl = true)),
        Shortcut(CommonShortcuts.NEW_WINDOW, FileGroup, desktopkit.R.string.dk_new_window, KeyChord(Key.N, ctrl = true, shift = true)),
        Shortcut(Commands.REFRESH, FileGroup, R.string.action_refresh, KeyChord(Key.F5)),
        Shortcut(Commands.NEXT, MessageGroup, R.string.action_next_message, KeyChord(Key.J), MailScope),
        Shortcut(Commands.PREVIOUS, MessageGroup, R.string.action_previous_message, KeyChord(Key.K), MailScope),
        Shortcut(Commands.STAR, MessageGroup, R.string.action_toggle_star, KeyChord(Key.S), MailScope),
        Shortcut(Commands.TOGGLE_READ, MessageGroup, R.string.action_toggle_read, KeyChord(Key.U), MailScope),
        Shortcut(Commands.CLOSE, MessageGroup, R.string.action_close_message, KeyChord(Key.Escape), MailScope),
        Shortcut(Commands.FIND, MessageGroup, R.string.action_find, KeyChord(Key.Slash), MailScope),
    ) + CommonShortcuts.all,
)

/** One window's commands, for whoever routes its keys; the composition says what performs them. */
class CommandHost {
    /** Runs the command [id]; false when there is nothing for it to do right now. */
    var perform: (String) -> Boolean = { false }
}

/**
 * What a key press does. The activity asks here first (dispatchKeyEvent), before the focused
 * view sees the key:
 *
 * - while the command palette is open, nothing: its field and its list take the keys;
 * - a chord (Ctrl+N, Ctrl+K, …) or a function key runs its command wherever the focus is;
 * - a single key (J, K, S, U, Esc, /) runs its command only while nothing is being typed
 *   ([typing]: the focus is in a text field). A key held down repeats J and K down the list, and
 *   nothing else;
 * - "/" also counts where it is typed with another key (Shift+7 on a Turkish keyboard), since a
 *   shortcut table knows keys, not the characters they type.
 *
 * A command with nothing to do (S with no message open) leaves the key to the focused view.
 */
class KeyRouter(
    private val registry: ShortcutRegistry,
    private val paletteOpen: () -> Boolean,
    private val typing: () -> Boolean,
    private val perform: (String) -> Boolean,
) {
    /** True when the key was taken. */
    fun route(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || paletteOpen()) return false
        registry.matchNative(event)?.let { return perform(it.id) }
        if (typing()) return false
        val id = registry.matchNative(event, setOf(MailScope), allowPlainKeys = true)?.id
            ?: (if (typesSlash(event)) Commands.FIND else return false)
        // held down: J and K walk the list, anything else would flip back and forth
        if (event.repeatCount > 0 && id != Commands.NEXT && id != Commands.PREVIOUS) return true
        return perform(id)
    }

    private fun typesSlash(event: KeyEvent): Boolean =
        !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && event.unicodeChar == '/'.code
}
