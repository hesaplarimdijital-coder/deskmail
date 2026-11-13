package com.deskmail.data

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailAddress
import com.deskmail.core.mail.MailException
import com.deskmail.core.text.SearchField
import com.deskmail.core.text.SearchQuery
import com.deskmail.core.text.SearchTerm
import com.deskmail.db.AwaitingEntity
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.FolderEntity
import com.deskmail.db.MessageEntity
import com.deskmail.db.SavedSearchEntity
import com.deskmail.db.SnoozeEntity
import com.deskmail.mail.AwaitOutcome
import com.deskmail.mail.AwaitedReply
import com.deskmail.mail.FetchedFile
import com.deskmail.mail.MailAccount
import com.deskmail.mail.MailAttachment
import com.deskmail.mail.MailContent
import com.deskmail.mail.MailFolder
import com.deskmail.mail.MailMessage
import com.deskmail.mail.MailSource
import com.deskmail.mail.MailUndo
import com.deskmail.mail.MailView
import com.deskmail.mail.SavedSearch
import com.deskmail.mail.SenderMarks
import com.deskmail.mail.ServerSearch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The mail of the app's accounts as the screens see it: what the database holds, watched, and
 * the sync asked for whatever is missing or may be out of date.
 *
 * What the user does to a message (read, star, archive, move, delete) is done on the device at
 * once and queued for the server ([com.deskmail.db.PendingDao]); the sync tells the server in the
 * background, now or when it can next be reached.
 *
 * @param scope where the watching and the syncing run; it outlives the screens.
 * @param files where the files of messages are kept once they were fetched, to be opened from.
 * @param paceMs a list that changes many times a second (mail arriving during a first sync) is
 *   handed on at most once in this long.
 * @param undoMs how long a move or a removal waits before the server is told, unless the screen
 *   says sooner that it stands ([commit]): for that long it is simply taken back.
 * @param tellAfterMs how long a change waits for others to go along with it on one connection.
 * @param awaitingChanged something came to be waited for, or no longer is ([awaitReply], [stopAwaiting]):
 *   the reminders are arranged anew.
 * @param snoozesChanged the account's snoozes were changed on the device ([snooze], [unsnooze], and
 *   taking either back): the server's list is to follow once the time to take it back has passed.
 */
@Stable
class LiveMail(
    private val db: DeskMailDatabase,
    private val sync: MailSync,
    private val scope: CoroutineScope,
    private val files: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val paceMs: Long = PACE_MS,
    private val mapping: CoroutineDispatcher = Dispatchers.Default,
    private val undoMs: Long = UNDO_MS,
    private val tellAfterMs: Long = TELL_AFTER_MS,
    private val awaitingChanged: suspend () -> Unit = {},
    private val snoozesChanged: suspend (accountId: Long) -> Unit = {},
) : MailSource {

    override var accounts: List<MailAccount> by mutableStateOf(emptyList())
        private set

    override var folders: List<MailFolder> by mutableStateOf(emptyList())
        private set

    override var loaded: Boolean by mutableStateOf(false)
        private set

    override var recent: List<MailMessage> by mutableStateOf(emptyList())
        private set

    override var syncing: Boolean by mutableStateOf(false)
        private set

    override var savedSearches: List<SavedSearch> by mutableStateOf(emptyList())
        private set

    override var senders: Map<String, SenderMarks> by mutableStateOf(emptyMap())
        private set

    override var awaited: List<AwaitedReply> by mutableStateOf(emptyList())
        private set

    override var snoozes: Map<Pair<Long, String>, Instant> by mutableStateOf(emptyMap())
        private set

    /**
     * What snoozing (or bringing back) changed of the device's snoozes, by what takes it back: each
     * row as it was, null for none. Kept as long as somebody may still take it back (Ctrl+Z, after
     * its notice too).
     */
    private val snoozedBefore: MutableMap<MailUndo, List<Pair<Pair<Long, String>, SnoozeEntity?>>> = Collections.synchronizedMap(WeakHashMap())

    /** The folders as they are stored: their paths and whose they are, for asking the sync. */
    @Volatile
    private var stored: List<FolderEntity> = emptyList()

    /**
     * The folders of the user's own writing (Sent, Drafts): their messages are listed by recipient.
     * Null until the folders have been read; no list is handed out before, so none is drawn wrongly first.
     */
    private val outgoing = MutableStateFlow<Set<Long>?>(null)

    /** What each view listed when it was last looked at, to draw at once the next time. */
    private val lists = ConcurrentHashMap<MailView, List<MailMessage>>()

    /** The messages whose text could not be fetched, and why. */
    private val failures = MutableStateFlow<Map<Long, MailException.Kind>>(emptyMap())

    /** The messages whose text is being fetched because somebody opened them. */
    private val fetching = ConcurrentHashMap.newKeySet<Long>()

    /** The syncs that are asked for and not done yet, and when each was last asked for: by account ("a3") or folder ("f12"). */
    private val asking = ConcurrentHashMap.newKeySet<String>()
    private val asked = ConcurrentHashMap<String, Long>()

    /** One fetch at a time for each file. */
    private val fetches = ConcurrentHashMap<Long, Mutex>()

    init {
        scope.launch {
            combine(db.accounts().observeAll(), db.folders().observeAll()) { accountRows, folderRows -> accountRows to folderRows }
                .collect { (accountRows, folderRows) ->
                    stored = folderRows
                    outgoing.value = folderRows.filter { folderRole(it.role).let { role -> role == FolderRole.Sent || role == FolderRole.Drafts } }.mapTo(HashSet()) { it.id }
                    accounts = accountRows.map { it.toAccount() }
                    folders = arrange(folderRows)
                    loaded = true
                }
        }
        scope.launch { shown(db.messages().observeNewest(RECENT)).collect { recent = it } }
        scope.launch { sync.syncing.collect { syncing = it.isNotEmpty() } }
        scope.launch { db.lists().observeSearches().collect { rows -> savedSearches = rows.map { SavedSearch(it.id, it.name, it.query) } } }
        scope.launch { db.lists().observeSenders().collect { rows -> senders = rows.associate { it.address to SenderMarks(it.vip, it.images) } } }
        scope.launch { db.awaiting().observeAll().collect { rows -> awaited = rows.map { it.toAwaited() } } }
        scope.launch { db.snoozes().observeShown().collect { rows -> snoozes = rows.associate { (it.accountId to it.messageId) to Instant.ofEpochMilli(it.wakeAt) } } }
        // what an earlier run did and could not tell its server any more
        scope.launch { db.pending().accounts().forEach { tellSoon(it) } }
        scope.launch(Dispatchers.IO) { forgetOldFiles() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(view: MailView): Flow<List<MailMessage>> {
        val messages = db.messages()
        val rows = when (view) {
            MailView.Inbox -> messages.observeInboxes()
            MailView.Unread -> messages.observeUnread()
            MailView.Starred -> messages.observeStarred()
            MailView.Vip -> messages.observeVip()
            MailView.Awaiting -> messages.observeAwaiting()
            MailView.Snoozed -> messages.observeSnoozed()
            is MailView.Folder -> messages.observeFolder(view.id)
            // what is typed changes with every letter: nothing of it is worth keeping for the next time
            is MailView.Search -> return found(view.query)
            // the search as it now stands: changed, the folder follows
            is MailView.Saved -> return db.lists().observeSearches()
                .map { rows -> rows.firstOrNull { it.id == view.id }?.query.orEmpty() }
                .distinctUntilChanged()
                .flatMapLatest { query -> found(query) }
                .onEach { keep(view, it) }
        }
        return shown(rows).onEach { keep(view, it) }
    }

    /**
     * What the index finds for what was typed, with what a search of the servers found for it;
     * nothing where there is nothing in it to look for. The index has one column for everybody on
     * a message: what is asked of the sender ("kimden:") or the recipients ("kime:") alone is
     * looked for there and then made sure of.
     */
    private fun found(typed: String): Flow<List<MailMessage>> {
        val query = SearchQuery.match(typed) ?: return flowOf(emptyList())
        val key = SearchQuery.key(typed) ?: return flowOf(emptyList())
        val people = SearchQuery.terms(typed).filter { it.field == SearchField.From || it.field == SearchField.To }
        val indexed = db.messages().observeFound(query, FOUND_LIMIT).map { rows -> rows.filter { row -> people.all { term -> peopleMatch(row, term) } } }
        val rows = combine(indexed, db.messages().observeHits(key, FOUND_LIMIT)) { local, server ->
            (local + server).distinctBy { it.id }.sortedWith(compareByDescending<MessageEntity> { it.date }.thenByDescending { it.id })
        }
        return shown(rows.map { once(it, stored) })
    }

    /** Whether the people [term] asks about ("kimden:", "kime:") are on the message where it asks for them. */
    private fun peopleMatch(row: MessageEntity, term: SearchTerm): Boolean {
        val lists = if (term.field == SearchField.From) listOf(row.fromList) else listOf(row.toList, row.ccList)
        val people = lists.flatMap { MailAddress.decode(it) }.joinToString(" ") { it.name + " " + it.address }
        return SearchQuery.matches(term.words, people)
    }

    override suspend fun saveSearch(id: Long, name: String, query: String): Long {
        val kept = name.replace(CONTROLS, " ").trim().take(MAX_NAME).ifBlank { query.trim().take(MAX_NAME) }
        val typed = query.replace(CONTROLS, " ").trim().take(MAX_QUERY)
        val lists = db.lists()
        if (id != 0L && lists.updateSearch(id, kept, typed) > 0) return id
        return lists.insertSearch(SavedSearchEntity(name = kept, query = typed, position = lists.nextSearchPosition()))
    }

    override suspend fun removeSearch(id: Long) = db.lists().deleteSearch(id)

    override suspend fun markSender(address: String, marks: SenderMarks) {
        val key = address.trim().lowercase()
        if (key.isEmpty()) return
        db.lists().setSender(key, marks.vip, marks.images)
    }

    override suspend fun awaitReply(id: Long, days: Int): AwaitOutcome {
        val message = db.messages().byId(id) ?: return AwaitOutcome.Gone
        val account = db.accounts().byId(message.accountId) ?: return AwaitOutcome.Gone
        // an answer names the message by its Message-ID: without one, none could be told apart
        if (message.globalId.isBlank()) return AwaitOutcome.Gone
        if (db.answered(account.id, account.address, message.globalId)) return AwaitOutcome.Answered
        val time = now()
        db.awaiting().insert(
            AwaitingEntity(
                accountId = account.id,
                messageId = message.globalId,
                subject = message.subject,
                recipients = recipientsOf(message.toList, message.ccList),
                sentAt = message.sentAt.takeIf { it > 0 } ?: message.date,
                // from now: the message may have been sent long ago
                dueAt = time + days.coerceAtLeast(1) * DAY_MS,
            ),
        )
        awaitingChanged()
        return AwaitOutcome.Waiting
    }

    override suspend fun stopAwaiting(id: Long) {
        db.awaiting().delete(id)
        awaitingChanged()
    }

    override suspend fun searchServers(query: String): ServerSearch? {
        val key = SearchQuery.key(query) ?: return null
        val terms = SearchQuery.terms(query)
        val time = now()
        // what was found long ago is forgotten: the sync may take it off the device again
        db.messages().forgetHits(time - HITS_KEPT_MS)
        var added = 0
        var unreachable = 0
        for (account in db.accounts().all()) {
            val (outcome, count) = sync.searchServer(account.id, terms, key, SERVER_FOUND_LIMIT, time)
            added += count
            if (outcome != MailSync.Outcome.Done) unreachable++
        }
        return ServerSearch(added, unreachable)
    }

    /** Stored messages as the screens show them, handed on at the pace a screen can use. */
    private fun shown(rows: Flow<List<MessageEntity>>): Flow<List<MailMessage>> =
        rows.paced(paceMs)
            .combine(outgoing.filterNotNull()) { list, written -> list.map { it.toMessage(it.folderId in written) } }
            .flowOn(mapping)

    override fun cached(view: MailView): List<MailMessage>? =
        if (view is MailView.Search && SearchQuery.match(view.query) == null) emptyList() else lists[view]

    private fun keep(view: MailView, list: List<MailMessage>) {
        // a handful of views is all a window shows; a folder that was looked at long ago is read again
        if (lists.size >= KEPT_LISTS && !lists.containsKey(view)) lists.keys.firstOrNull()?.let { lists.remove(it) }
        lists[view] = list
    }

    override fun observeThread(message: MailMessage): Flow<List<MailMessage>> {
        val root = message.thread.substringAfter(' ', "")
        if (root.isEmpty()) return flowOf(listOf(message))
        return shown(db.messages().observeThread(message.accountId, root).map { once(it, stored) })
    }

    override fun observeMessage(id: Long): Flow<MailMessage?> =
        db.messages().observe(id)
            .combine(outgoing.filterNotNull()) { row, written -> row?.toMessage(row.folderId in written) }
            .distinctUntilChanged()

    override fun cachedMessage(id: Long): MailMessage? =
        recent.firstOrNull { it.id == id } ?: lists.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == id } }

    override fun observeContent(id: Long): Flow<MailContent> {
        val messages = db.messages()
        return combine(messages.observe(id), messages.observeBody(id), messages.observeAttachments(id), failures) { message, body, files, failed ->
            val failure = failed[id]
            when {
                message == null -> MailContent.Unavailable
                message.bodyState == MessageEntity.BODY_KEPT && body != null ->
                    MailContent.Ready(body.text, body.html, body.remoteImages, files.toFiles(), files.toPictures(), body.headers)
                message.bodyState == MessageEntity.BODY_NONE -> MailContent.Unavailable
                failure != null -> MailContent.Failed(failure)
                else -> MailContent.Loading
            }
        }.distinctUntilChanged().onEach { content ->
            // somebody is looking at a message the device has no text of yet: it is fetched ahead of its turn
            if (content == MailContent.Loading) fetch(id)
        }
    }

    override suspend fun fetchHeaders(id: Long): Boolean = sync.headers(id) == MailSync.Outcome.Done

    override fun retryContent(id: Long) {
        failures.update { it - id }
        fetch(id)
    }

    private fun fetch(id: Long) {
        if (!fetching.add(id)) return
        scope.launch {
            try {
                val outcome = sync.body(id)
                failures.update { if (outcome is MailSync.Outcome.Failed) it + (id to outcome.kind) else it - id }
            } finally {
                fetching.remove(id)
            }
        }
    }

    // ------------------------------------------------------------------ what the user does to a message

    override fun markRead(id: Long) = change { db.pending().mark(id, seen = true, flagged = null, now = now()) }

    override fun toggleUnread(id: Long) = change { db.pending().toggle(id, seen = true, now = now()) }

    override fun toggleStarred(id: Long) = change { db.pending().toggle(id, seen = false, now = now()) }

    override fun markUnread(ids: List<Long>, unread: Boolean) = changeEach(ids) { db.pending().mark(it, seen = !unread, flagged = null, now = now()) }

    override fun markStarred(ids: List<Long>, starred: Boolean) = changeEach(ids) { db.pending().mark(it, seen = null, flagged = starred, now = now()) }

    /** Makes a change to each of [ids] on the device; every account that has something to hear of is told once. */
    private fun changeEach(ids: List<Long>, work: suspend (Long) -> Long?) {
        scope.launch { ids.mapNotNull { work(it) }.toSet().forEach { tellSoon(it) } }
    }

    /** Makes a change on the device; [work] answers with the account whose server is to be told of it. */
    private fun change(work: suspend () -> Long?) {
        scope.launch { work()?.let { tellSoon(it) } }
    }

    /** Has the account's server told what is due, a moment from now: what else is done meanwhile goes along. */
    private fun tellSoon(accountId: Long, afterMs: Long = tellAfterMs) {
        scope.launch {
            if (afterMs > 0) delay(afterMs)
            sync.changes(accountId)
        }
    }

    override suspend fun archive(ids: List<Long>): MailUndo? = leave(ids, MailUndo.Action.Archived) { _, own ->
        // Gmail has no archive folder: there, archived mail is what is in "All Mail" and nowhere else
        (own.firstOrNull { folderRole(it.role) == FolderRole.Archive } ?: own.firstOrNull { folderRole(it.role) == FolderRole.All })?.id
    }

    override suspend fun delete(ids: List<Long>): MailUndo? = leave(ids, MailUndo.Action.Deleted) { message, own ->
        val trash = own.firstOrNull { folderRole(it.role) == FolderRole.Trash }
        // in the trash already, or an account without one: removed for good
        if (trash == null || trash.id == message.folderId) REMOVE else trash.id
    }

    override suspend fun move(ids: List<Long>, folderId: Long): MailUndo? =
        leave(ids, MailUndo.Action.Moved, folderId) { _, own -> own.firstOrNull { it.id == folderId }?.id }

    /**
     * Takes the messages off their lists and queues each for where [target] sends it: a folder
     * of its own account ([target] is handed those), [REMOVE], or nowhere (null: it stays).
     */
    private suspend fun leave(ids: List<Long>, action: MailUndo.Action, folder: Long = 0, target: (MessageEntity, List<FolderEntity>) -> Long?): MailUndo? {
        val time = now()
        val folders = stored
        val wanted = LinkedHashMap<Long, MutableList<MessageEntity>>()
        for (id in ids) {
            val message = db.messages().byId(id) ?: continue
            val to = target(message, folders.filter { it.accountId == message.accountId }) ?: continue
            wanted.getOrPut(to) { ArrayList() } += message
        }
        val changes = ArrayList<MailUndo.Change>()
        for ((to, messages) in wanted) {
            val from = messages.associate { it.id to it.folderId }
            db.pending().leave(messages.map { it.id }, to, notBefore = time + undoMs, now = time)
                .mapTo(changes) { MailUndo.Change(it.id, it.accountId, it.messageId, from.getValue(it.messageId), to) }
        }
        if (changes.isEmpty()) return null
        // told once the time to take it back has passed, whether or not a screen says before that it stands
        changes.map { it.accountId }.distinct().forEach { tellSoon(it, undoMs + tellAfterMs) }
        val forGood = action == MailUndo.Action.Deleted && changes.all { it.to == REMOVE }
        return MailUndo(if (forGood) MailUndo.Action.DeletedForGood else action, changes.map { it.messageId }, folder, changes)
    }

    override suspend fun snooze(ids: List<Long>, wakeAt: Instant): MailUndo? {
        val messages = ids.mapNotNull { db.messages().byId(it) }.filter { it.globalId.isNotBlank() }
        // each account's folder for it; made on the server the first time, which needs a network
        val folders = HashMap<Long, Long>()
        for (accountId in messages.map { it.accountId }.distinct()) sync.snoozeFolder(accountId)?.let { folders[accountId] = it }
        val snoozable = messages.filter { it.accountId in folders }
        if (snoozable.isEmpty()) return null
        val time = now()
        val before = ArrayList<Pair<Pair<Long, String>, SnoozeEntity?>>()
        for (message in snoozable) {
            val was = db.snoozes().of(message.accountId, message.globalId)
            before += (message.accountId to message.globalId) to was
            val snoozedAlready = message.folderId == folders[message.accountId]
            // back to where it lies now; one snoozed again keeps the folder it came from
            val home = if (snoozedAlready) was?.folder ?: INBOX_PATH else db.folders().byId(message.folderId)?.path ?: INBOX_PATH
            db.snoozes().put(
                SnoozeEntity(
                    id = was?.id ?: 0,
                    accountId = message.accountId,
                    messageId = message.globalId,
                    folder = home,
                    subject = message.subject,
                    wakeAt = wakeAt.toEpochMilli(),
                    state = SnoozeEntity.ADDED,
                    changedAt = time,
                ),
            )
        }
        val moving = snoozable.filter { it.folderId != folders[it.accountId] }.map { it.id }
        val undo = (if (moving.isEmpty()) null else leave(moving, MailUndo.Action.Snoozed) { message, _ -> folders[message.accountId] })
            ?: MailUndo(MailUndo.Action.Snoozed, snoozable.map { it.id })
        snoozedBefore[undo] = before
        snoozable.map { it.accountId }.distinct().forEach { snoozesChanged(it) }
        return undo
    }

    override suspend fun unsnooze(ids: List<Long>): MailUndo? {
        val time = now()
        val messages = ids.mapNotNull { db.messages().byId(it) }
        val before = ArrayList<Pair<Pair<Long, String>, SnoozeEntity?>>()
        val homes = HashMap<Long, String>()
        for (message in messages) {
            val was = db.snoozes().of(message.accountId, message.globalId) ?: continue
            before += (message.accountId to message.globalId) to was
            homes[message.id] = was.folder
            db.snoozes().put(was.copy(state = SnoozeEntity.REMOVED, changedAt = time))
        }
        // back where it came from; to the inbox where that folder is gone (or the device does not know it)
        val undo = leave(ids, MailUndo.Action.Unsnoozed) { message, own ->
            if (own.none { it.id == message.folderId && folderRole(it.role) == FolderRole.Snoozed }) return@leave null
            own.firstOrNull { it.path == homes[message.id] && it.selectable && folderRole(it.role) != FolderRole.Snoozed }?.id
                ?: own.firstOrNull { folderRole(it.role) == FolderRole.Inbox }?.id
        }
        if (undo == null) {
            // nothing moved: what was marked to come back stays as it was
            for ((_, was) in before) if (was != null) db.snoozes().put(was)
            return null
        }
        snoozedBefore[undo] = before
        messages.map { it.accountId }.distinct().forEach { snoozesChanged(it) }
        return undo
    }

    override suspend fun undo(undo: MailUndo): Int {
        var back = 0
        // what snoozing changed of the device's snoozes is as it was
        snoozedBefore.remove(undo)?.let { rows ->
            for ((key, was) in rows) {
                if (was != null) {
                    db.snoozes().put(was.copy(changedAt = now()))
                } else {
                    db.snoozes().of(key.first, key.second)?.let { db.snoozes().delete(it.id) }
                }
            }
            rows.map { it.first.first }.distinct().forEach { snoozesChanged(it) }
            // a message snoozed once more moved nowhere: setting its time back is all there is to it
            if (undo.changes.isEmpty()) return undo.count
        }
        // told to the server already: these go back the way they came
        val returning = LinkedHashMap<Long, MutableList<Long>>()
        for ((accountId, changes) in undo.changes.groupBy { it.accountId }) {
            // not while the account's changes are being sent: each is then waiting or settled, never half way
            sync.betweenChanges(accountId) {
                for (change in changes) {
                    if (db.pending().takeBack(change.id)) {
                        back++
                    } else if (change.to != REMOVE && db.messages().byId(change.messageId)?.folderId == change.to) {
                        returning.getOrPut(change.from) { ArrayList() } += change.messageId
                    }
                }
            }
        }
        val time = now()
        for ((from, messages) in returning) {
            val queued = db.pending().leave(messages, from, notBefore = time, now = time)
            back += queued.size
            queued.map { it.accountId }.distinct().forEach { tellSoon(it, 0) }
        }
        return back
    }

    override fun commit(undo: MailUndo) {
        scope.launch {
            db.pending().bringForward(undo.changes.map { it.id }, now())
            undo.changes.map { it.accountId }.distinct().forEach { sync.changes(it) }
        }
    }

    // What a notification's buttons ask for. Written down before the call returns, and due at
    // once: there is nobody to take it back, and the process may not live much longer. Telling
    // the server is the caller's to arrange. True when something was done.

    /** Marks the message read, for good. */
    suspend fun readNow(id: Long): Boolean = db.pending().mark(id, seen = true, flagged = null, now = now()) != null

    /** Archives the message, for good. */
    suspend fun archiveNow(id: Long): Boolean = standNow(archive(listOf(id)))

    /** Deletes the message (to the trash; from the trash, for good), for good. */
    suspend fun deleteNow(id: Long): Boolean = standNow(delete(listOf(id)))

    private suspend fun standNow(undo: MailUndo?): Boolean {
        if (undo == null) return false
        db.pending().bringForward(undo.changes.map { it.id }, now())
        return true
    }

    /** Tells every server that has something waiting for it what is due; true when one of them could not be reached. */
    suspend fun tellAll(): Boolean {
        var unreachable = false
        for (accountId in db.pending().accounts()) {
            val outcome = sync.changes(accountId)
            if (outcome is MailSync.Outcome.Failed && outcome.kind == MailException.Kind.Network) unreachable = true
        }
        return unreachable
    }

    /** Brings every account's inbox up to date, as the app does in the background; true when a server could not be reached. */
    suspend fun syncAll(): Boolean {
        var unreachable = false
        for (account in db.accounts().all()) {
            val outcome = sync.account(account.id)
            if (outcome is MailSync.Outcome.Failed && outcome.kind == MailException.Kind.Network) unreachable = true
        }
        return unreachable
    }

    // ------------------------------------------------------------------ files

    override suspend fun fetch(attachment: MailAttachment): FetchedFile {
        if (attachment.id == 0L) return FetchedFile.Gone
        // a directory of its own for every file: two files of one name do not meet
        val target = File(File(files, attachment.id.toString()), safeFileName(attachment.name))
        return fetches.getOrPut(attachment.id) { Mutex() }.withLock {
            if (target.isFile) return@withLock FetchedFile.Ready(target)
            when (val outcome = sync.attachment(attachment.id, target)) {
                MailSync.Outcome.Done -> FetchedFile.Ready(target)
                is MailSync.Outcome.Failed -> FetchedFile.Failed(outcome.kind)
                MailSync.Outcome.Gone -> FetchedFile.Gone
            }
        }
    }

    /** Fetched files are copies of what the server keeps: after a week they make room. */
    private fun forgetOldFiles() {
        val before = now() - KEEP_FILES_MS
        files.listFiles()?.forEach { directory -> if (directory.lastModified() < before) directory.deleteRecursively() }
    }

    // ------------------------------------------------------------------ asking the servers

    override fun viewShown(view: MailView) {
        val folder = (view as? MailView.Folder)?.let { shown -> stored.firstOrNull { it.id == shown.id } }
        when {
            // a view over every account: each account's inbox, with its folders and their counts
            view !is MailView.Folder -> accounts.forEach { syncAccount(it.id, emptySet(), onlyIfStale = true) }
            folder == null -> Unit
            folderRole(folder.role) == FolderRole.Inbox -> syncAccount(folder.accountId, emptySet(), onlyIfStale = true)
            else -> ask("f${folder.id}", onlyIfStale = true) { sync.folder(folder.id) }
        }
    }

    override fun refresh(view: MailView?) {
        val folder = (view as? MailView.Folder)?.let { shown -> stored.firstOrNull { it.id == shown.id } }
        for (account in accounts) {
            val also = if (folder != null && folder.accountId == account.id) setOf(folder.path) else emptySet()
            syncAccount(account.id, also, onlyIfStale = false)
        }
    }

    /** Has an account synced now: it was just added, or signed in again. */
    fun sync(accountId: Long) = syncAccount(accountId, emptySet(), onlyIfStale = false)

    private fun syncAccount(accountId: Long, also: Set<String>, onlyIfStale: Boolean) =
        ask("a$accountId", onlyIfStale) { sync.account(accountId, also) }

    /**
     * Has [work] run unless the same is already under way, or, with [onlyIfStale], was asked for a
     * moment ago: a screen that comes to the front again does not send the app to the server each time.
     */
    private fun ask(key: String, onlyIfStale: Boolean, work: suspend () -> Unit) {
        val time = now()
        val last = asked[key]
        if (onlyIfStale && last != null && time - last < STALE_MS) return
        if (!asking.add(key)) return
        asked[key] = time
        scope.launch {
            try {
                work()
            } finally {
                asking.remove(key)
            }
        }
    }

    companion object {
        /** How many of the newest messages the command palette offers. */
        const val RECENT = 300

        /** Where a snoozed message goes back to when the folder it came from is not known. */
        private const val INBOX_PATH = "INBOX"

        /** A search shows this many of what it finds, the newest: more than anybody reads through. */
        const val FOUND_LIMIT = 500

        /** How many messages a search of one account's server brings onto the device at most. */
        const val SERVER_FOUND_LIMIT = 100

        /** How long what a search of the servers found is kept on the device, whatever the sync keeps. */
        const val HITS_KEPT_MS = 24 * 60 * 60 * 1000L

        /** A saved search's name is one line of the sidebar; what it looks for, one line of the search field. */
        const val MAX_NAME = 80
        const val MAX_QUERY = 200

        /** Line breaks, tabs and the like, however many in a row. */
        private val CONTROLS = Regex("\\p{Cntrl}+")

        /** A list is handed to its screen at most once in this long. */
        const val PACE_MS = 250L

        /** What was synced less than this long ago is not synced again just because its screen came to the front. */
        const val STALE_MS = 2 * 60 * 1000L

        /** How long a move or a removal can simply be taken back before the server is told. */
        const val UNDO_MS = 15_000L

        /** A change is told a moment after it was made: the next ones go along on the same connection. */
        const val TELL_AFTER_MS = 400L

        /** How long a fetched file is kept on the device. */
        private const val KEEP_FILES_MS = 7 * 24 * 60 * 60 * 1000L

        /** Where a message goes that is removed for good: nowhere. */
        private const val REMOVE = 0L

        private const val KEPT_LISTS = 12
    }
}

/**
 * A name a file can have on the device, from the name a message gives it: nothing that could
 * lead out of its directory, nothing a file system refuses, and not longer than a name may be.
 */
internal fun safeFileName(name: String): String {
    val cleaned = name.map { if (it < ' ' || it in "/\\:*?\"<>|") '_' else it }.joinToString("").trim().trim('.')
    if (cleaned.isEmpty()) return "ek"
    if (cleaned.length <= MAX_FILE_NAME) return cleaned
    // the ending says what the file is: it stays
    val dot = cleaned.lastIndexOf('.')
    val ending = if (dot > 0 && cleaned.length - dot <= 10) cleaned.substring(dot) else ""
    return cleaned.take(MAX_FILE_NAME - ending.length) + ending
}

private const val MAX_FILE_NAME = 120

/** The flow's values at most one every [ms]: the first at once, later ones when the time is up, the newest then. */
internal fun <T> Flow<T>.paced(ms: Long): Flow<T> {
    if (ms <= 0) return this
    val source = this
    return flow {
        source.collect { value ->
            emit(value)
            delay(ms)
        }
    }
}
