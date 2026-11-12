package com.deskmail.core.sync

import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.MailException
import com.deskmail.core.mail.RemoteMailbox
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Brings the device's copy of one folder up to what the server has, without any IMAP extension:
 *
 * 1. A new UIDVALIDITY voids what was kept of the folder.
 * 2. The server names the messages of the window (the last so many days, or all). What the device
 *    has and the server did not name is removed: deleted there, moved away, or too old now.
 * 3. What the server named and the device does not have is fetched, newest first.
 * 4. The flags of what the device already had are compared: only the changed ones where the
 *    server keeps a change counter (CONDSTORE), all of them where it does not.
 * 5. The text of messages that have none yet is fetched, cleaned and kept, a number at a time.
 *
 * The server is the truth: nothing here changes it. A sync that is cut off leaves what it had
 * done so far, and the next one picks up from there.
 */
class FolderSync(private val store: SyncStore, private val log: AppLog? = null) {

    /**
     * @param added messages new on the device, [removed] ones taken off it, [flagsChanged] ones whose
     *   marks were brought up to date, [bodies] ones whose text was fetched.
     * @param bodiesLeft there are more messages without text than this run fetched; another run goes on.
     */
    data class Result(val added: Int, val removed: Int, val flagsChanged: Int, val bodies: Int, val bodiesLeft: Boolean)

    /**
     * @param days how far back messages are kept; 0 or less for all of them.
     * @param bodyLimit how many messages get their text in this run.
     */
    suspend fun sync(mailbox: RemoteMailbox, folder: StoredFolder, days: Int, now: Instant, bodyLimit: Int = BODIES_PER_RUN): Result {
        var state = folder.state
        var known = store.uids(folder.id)
        var removed = 0
        if (state.uidValidity != 0L && state.uidValidity != mailbox.uidValidity) {
            log?.info(TAG, "${folder.path}: UIDVALIDITY ${state.uidValidity} → ${mailbox.uidValidity}, starting over")
            store.clear(folder.id)
            removed += known.size
            known = emptyList()
            state = FolderState()
        }
        // from here on the UIDs on the device are this validity's, whatever becomes of this run
        if (state.uidValidity != mailbox.uidValidity) {
            state = state.copy(uidValidity = mailbox.uidValidity)
            store.saveState(folder.id, state, syncedAt = null)
        }

        // the server compares whole days, and a day begins at another hour there: one day more
        val since = if (days <= 0) null else LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(days.toLong() + 1)
        val remote = mailbox.uids(since)
        val onServer = remote.toHashSet()
        val onDevice = known.toHashSet()

        val gone = known.filter { it !in onServer }
        if (gone.isNotEmpty()) {
            store.delete(folder.id, gone)
            removed += gone.size
        }

        var added = 0
        for (batch in remote.filter { it !in onDevice }.sortedDescending().chunked(HEADERS_PER_FETCH)) {
            // marked for deletion and not yet removed: as good as gone
            val headers = mailbox.headers(batch).filter { !it.flags.deleted }
            if (headers.isNotEmpty()) store.insert(folder.id, headers)
            added += headers.size
        }

        var flagsChanged = 0
        val kept = known.filter { it in onServer }
        if (kept.isNotEmpty()) {
            val counter = mailbox.highestModSeq
            val changed = when {
                state.highestModSeq <= 0 || counter <= 0 -> null
                counter == state.highestModSeq -> emptyList()
                else -> mailbox.flagsChangedSince(state.highestModSeq)
            }
            val current = (changed ?: mailbox.flags(kept)).filter { it.uid in onDevice }
            val deleted = current.filter { it.flags.deleted }.map { it.uid }
            if (deleted.isNotEmpty()) {
                store.delete(folder.id, deleted)
                removed += deleted.size
            }
            val live = current.filter { !it.flags.deleted }
            if (live.isNotEmpty()) store.updateFlags(folder.id, live)
            flagsChanged = if (changed == null) 0 else live.size
        }

        val waiting = store.uidsWithoutBody(folder.id, bodyLimit + 1)
        val bodies = fetch(mailbox, folder, waiting.take(bodyLimit))

        store.saveCounts(folder.id, mailbox.total, mailbox.unread)
        store.saveState(folder.id, FolderState(mailbox.uidValidity, mailbox.uidNext, mailbox.highestModSeq), syncedAt = now)
        val result = Result(added, removed, flagsChanged, bodies, bodiesLeft = waiting.size > bodyLimit)
        log?.info(TAG, "${folder.path}: +$added −$removed, $flagsChanged changed, $bodies fetched" + if (result.bodiesLeft) ", more to fetch" else "")
        return result
    }

    /**
     * @param fetched how many messages got their text; [more] when others are still waiting for theirs.
     */
    data class Bodies(val fetched: Int, val more: Boolean)

    /**
     * Fetches the text of up to [limit] messages of the folder that have none yet, newest first:
     * what a sync left for later. [folder] is the folder as the last sync left it; when the server
     * has given the folder's UIDs a new meaning since, nothing is fetched and the next sync starts over.
     */
    suspend fun bodies(mailbox: RemoteMailbox, folder: StoredFolder, limit: Int = BODIES_PER_RUN): Bodies {
        if (!sameUids(mailbox, folder)) return Bodies(0, more = false)
        val waiting = store.uidsWithoutBody(folder.id, limit + 1)
        return Bodies(fetch(mailbox, folder, waiting.take(limit)), more = waiting.size > limit)
    }

    /**
     * Fetches the text of one message now, ahead of its turn: the user opened it.
     *
     * @return whether there was text to keep.
     */
    suspend fun body(mailbox: RemoteMailbox, folder: StoredFolder, uid: Long): Boolean =
        sameUids(mailbox, folder) && fetch(mailbox, folder, listOf(uid)) == 1

    private fun sameUids(mailbox: RemoteMailbox, folder: StoredFolder): Boolean =
        folder.state.uidValidity != 0L && folder.state.uidValidity == mailbox.uidValidity

    /** Fetches, cleans and keeps the text of the messages [uids]; returns how many had text to keep. */
    private suspend fun fetch(mailbox: RemoteMailbox, folder: StoredFolder, uids: List<Long>): Int {
        var kept = 0
        for (uid in uids) {
            val body = try {
                mailbox.body(uid, MAX_TEXT_BYTES)
            } catch (e: MailException) {
                // the connection is gone: the rest waits for the next run. One message the server
                // will not hand over must not hold up the others.
                if (e.kind != MailException.Kind.Server) throw e
                log?.warn(TAG, "${folder.path}: message $uid could not be fetched: ${e.message}")
                null
            }
            val content = body?.let { BodyProcessor.process(it) }
            store.saveBody(folder.id, uid, content)
            if (content != null) kept++
        }
        return kept
    }

    companion object {
        private const val TAG = "sync"

        private const val HEADERS_PER_FETCH = 50

        /** How many messages get their text in one run; the list is complete before, and the next run goes on. */
        const val BODIES_PER_RUN = 100

        /** A text part larger than this stays on the server. */
        const val MAX_TEXT_BYTES = 1024 * 1024
    }
}
