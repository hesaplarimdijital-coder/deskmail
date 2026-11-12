package com.deskmail.core.sync

import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.EditableMailbox
import com.deskmail.core.mail.MailException
import com.deskmail.core.mail.Mark
import com.deskmail.core.mail.RemoteAccount

/**
 * Something done on the device that its server has not been told of yet: a mark set or cleared,
 * a message moved to another folder, or removed for good. The message is named as the server
 * knows it: by its folder, the folder's UIDVALIDITY as the device last knew it, and its UID there.
 *
 * @param id the device's own name for the change.
 * @param value for a mark: set, or cleared.
 * @param target for a move: the folder the message goes to.
 */
data class PendingChange(
    val id: Long,
    val folder: String,
    val uidValidity: Long,
    val uid: Long,
    val kind: Kind,
    val value: Boolean = false,
    val target: String = "",
) {
    enum class Kind { Seen, Flagged, Move, Expunge }
}

/** What became of a [PendingChange]. */
sealed interface ChangeOutcome {
    /**
     * The server has it.
     *
     * @param newUid after a move: the message's UID in the folder it went to, and [newUidValidity]
     *   that folder's stamp on its UIDs; both 0 where the server did not tell.
     */
    data class Done(val newUid: Long = 0, val newUidValidity: Long = 0) : ChangeOutcome

    /**
     * There was nothing to do it to: the message is no longer where it was (another device got
     * there first), or the folder's UIDs have a new meaning since.
     */
    data object Gone : ChangeOutcome

    /** The server said no, and would say so again. */
    data class Refused(val reason: String) : ChangeOutcome
}

/**
 * Tells an account's server what was done on the device: the other half of the sync, which only
 * reads ([FolderSync]).
 */
class ChangeSync(private val log: AppLog? = null) {

    /**
     * Carries out [changes] on the server, folder by folder. Within a folder the marks go first
     * (a message takes its marks along when it moves), then the moves, then what is removed.
     *
     * [settled] is told of each change as soon as the server has answered for it: a connection
     * that breaks half way leaves the rest, and only the rest, for the next time.
     *
     * @throws MailException when the connection or the sign-in fails; what was not settled stays as it is.
     */
    suspend fun push(account: RemoteAccount, changes: List<PendingChange>, settled: suspend (PendingChange, ChangeOutcome) -> Unit) {
        for ((path, ofFolder) in changes.groupBy { it.folder }) {
            val editor = try {
                account.edit(path)
            } catch (e: MailException) {
                if (e.kind != MailException.Kind.Server) throw e
                // the folder is gone, or the server will not have it changed
                log?.warn(TAG, "$path: cannot be opened for changes: ${e.message}")
                ofFolder.forEach { settled(it, ChangeOutcome.Refused(e.message.orEmpty())) }
                continue
            }
            editor.use { folder(it, path, ofFolder, settled) }
        }
    }

    private suspend fun folder(editor: EditableMailbox, path: String, changes: List<PendingChange>, settled: suspend (PendingChange, ChangeOutcome) -> Unit) {
        val (current, void) = changes.partition { it.uidValidity == editor.uidValidity }
        if (void.isNotEmpty()) log?.info(TAG, "$path: ${void.size} changes were for UIDs that mean something else now")
        void.forEach { settled(it, ChangeOutcome.Gone) }

        val marks = current.filter { it.kind == PendingChange.Kind.Seen || it.kind == PendingChange.Kind.Flagged }
        for ((what, same) in marks.groupBy { it.kind to it.value }) {
            attempt(path, same, settled) {
                val mark = if (what.first == PendingChange.Kind.Seen) Mark.Seen else Mark.Flagged
                editor.mark(same.map { it.uid }, mark, what.second)
                same.forEach { settled(it, ChangeOutcome.Done()) }
            }
        }
        for ((target, moves) in current.filter { it.kind == PendingChange.Kind.Move }.groupBy { it.target }) {
            attempt(path, moves, settled) {
                val moved = editor.move(moves.map { it.uid }, target).associateBy { it.uid }
                moves.forEach { change -> settled(change, moved[change.uid]?.let { ChangeOutcome.Done(it.newUid, it.newUidValidity) } ?: ChangeOutcome.Gone) }
            }
        }
        val removals = current.filter { it.kind == PendingChange.Kind.Expunge }
        if (removals.isNotEmpty()) {
            attempt(path, removals, settled) {
                editor.expunge(removals.map { it.uid })
                removals.forEach { settled(it, ChangeOutcome.Done()) }
            }
        }
        log?.info(TAG, "$path: ${marks.size} marks, ${current.count { it.kind == PendingChange.Kind.Move }} moves, ${removals.size} removals sent")
    }

    /** Runs [work] for [changes]; a "no" from the server settles them as refused, anything else is for the caller. */
    private suspend fun attempt(path: String, changes: List<PendingChange>, settled: suspend (PendingChange, ChangeOutcome) -> Unit, work: suspend () -> Unit) {
        try {
            work()
        } catch (e: MailException) {
            // the connection is gone: all of it waits for the next time
            if (e.kind != MailException.Kind.Server) throw e
            log?.warn(TAG, "$path: the server refused ${changes.size} changes (${changes.first().kind}): ${e.message}")
            changes.forEach { settled(it, ChangeOutcome.Refused(e.message.orEmpty())) }
        }
    }

    private companion object {
        const val TAG = "sync"
    }
}
