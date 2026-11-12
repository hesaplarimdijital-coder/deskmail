package com.deskmail.data

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailException
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.FolderEntity
import com.deskmail.db.MessageEntity
import com.deskmail.mail.MailAccount
import com.deskmail.mail.MailContent
import com.deskmail.mail.MailFolder
import com.deskmail.mail.MailMessage
import com.deskmail.mail.MailSource
import com.deskmail.mail.MailView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The mail of the app's accounts as the screens see it: what the database holds, watched, and
 * the sync asked for whatever is missing or may be out of date.
 *
 * This pre-release reads: opening a message does not mark it read on the server, and the star
 * cannot be changed ([canMark] is false). Both arrive with the queue of pending changes.
 *
 * @param scope where the watching and the syncing run; it outlives the screens.
 * @param paceMs a list that changes many times a second (mail arriving during a first sync) is
 *   handed on at most once in this long.
 */
@Stable
class LiveMail(
    private val db: DeskMailDatabase,
    private val sync: MailSync,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val paceMs: Long = PACE_MS,
    private val mapping: CoroutineDispatcher = Dispatchers.Default,
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

    override val canMark: Boolean get() = false

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
    }

    override fun observe(view: MailView): Flow<List<MailMessage>> {
        val messages = db.messages()
        val rows = when (view) {
            MailView.Inbox -> messages.observeInboxes()
            MailView.Unread -> messages.observeUnread()
            MailView.Starred -> messages.observeStarred()
            is MailView.Folder -> messages.observeFolder(view.id)
        }
        return shown(rows).onEach { keep(view, it) }
    }

    /** Stored messages as the screens show them, handed on at the pace a screen can use. */
    private fun shown(rows: Flow<List<MessageEntity>>): Flow<List<MailMessage>> =
        rows.paced(paceMs)
            .combine(outgoing.filterNotNull()) { list, written -> list.map { it.toMessage(it.folderId in written) } }
            .flowOn(mapping)

    override fun cached(view: MailView): List<MailMessage>? = lists[view]

    private fun keep(view: MailView, list: List<MailMessage>) {
        // a handful of views is all a window shows; a folder that was looked at long ago is read again
        if (lists.size >= KEPT_LISTS && !lists.containsKey(view)) lists.keys.firstOrNull()?.let { lists.remove(it) }
        lists[view] = list
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
                message.bodyState == MessageEntity.BODY_KEPT && body != null -> MailContent.Ready(body.text, body.html, body.remoteImages, files.toFiles())
                message.bodyState == MessageEntity.BODY_NONE -> MailContent.Unavailable
                failure != null -> MailContent.Failed(failure)
                else -> MailContent.Loading
            }
        }.distinctUntilChanged().onEach { content ->
            // somebody is looking at a message the device has no text of yet: it is fetched ahead of its turn
            if (content == MailContent.Loading) fetch(id)
        }
    }

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

    // ------------------------------------------------------------------ marks: not in this pre-release

    override fun markRead(id: Long) = Unit

    override fun toggleUnread(id: Long) = Unit

    override fun toggleStarred(id: Long) = Unit

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

        /** A list is handed to its screen at most once in this long. */
        const val PACE_MS = 250L

        /** What was synced less than this long ago is not synced again just because its screen came to the front. */
        const val STALE_MS = 2 * 60 * 1000L

        private const val KEPT_LISTS = 12
    }
}

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
