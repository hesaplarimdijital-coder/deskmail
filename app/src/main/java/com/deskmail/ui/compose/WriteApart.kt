package com.deskmail.ui.compose

/**
 * Writing in a window of its own. On DeX and on Android's desktop windowing every window of the
 * app is one of the desktop's, and a message that is begun there opens beside the window with the
 * mail, as it does in a desktop's mail program; elsewhere it is written over the screen it was
 * begun on.
 */
interface WriteApart {
    /** Whether a message that is begun now opens in a window of its own. */
    fun now(): Boolean

    /**
     * Opens the message [draftId] in a window of its own, or brings the window it is already being
     * written in to the front. [fresh]: begun just now, so that closed untouched it is thrown away.
     */
    fun open(draftId: Long, fresh: Boolean)

    /**
     * The writing of the window with the mail ([actions]; null once that window goes): what a window
     * of its own did with its message is told there, and "Undo" is offered there.
     */
    fun home(actions: ComposeActions?)
}

/**
 * The app's windows of their own for writing ([com.deskmail.WriteActivity]), each a task of its
 * own, and the windows with the mail they were opened from. One message is written in one window
 * at most: asked for again, the window it is open in comes to the front.
 *
 * Only ever used on the main thread.
 */
class WriteWindows {
    /** The window each message is being written in, by its task. */
    private val writing = HashMap<Long, Int>()

    /** The windows with the mail, by their task: what writes their messages. */
    private val homes = LinkedHashMap<Int, ComposeActions>()

    /** The message [draftId] is being written in the window of [task]. */
    fun opened(draftId: Long, task: Int) {
        writing[draftId] = task
    }

    /** The window of [task] no longer writes [draftId]. */
    fun closed(draftId: Long, task: Int) {
        if (writing[draftId] == task) writing.remove(draftId)
    }

    /** The task of the window [draftId] is being written in; null when it is in none. */
    fun taskOf(draftId: Long): Int? = writing[draftId]

    /** The window with the mail of [task] writes with [actions]. */
    fun home(task: Int, actions: ComposeActions) {
        homes[task] = actions
    }

    /** The window with the mail of [task] goes; it is forgotten unless another took its place meanwhile. */
    fun leave(task: Int, actions: ComposeActions) {
        if (homes[task] === actions) homes.remove(task)
    }

    /**
     * Where to tell what came of a message written in a window of its own: the window it was opened
     * from, or, once that one is gone, another window with the mail; null when there is none.
     */
    fun homeFor(opener: Int): ComposeActions? = homes[opener] ?: homes.values.lastOrNull()
}
