package com.deskmail.mail

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Where the screens get the mail from and what they can ask of it: the device's copy of the
 * accounts' mail in the app, made-up mail in the tests and the screenshots.
 *
 * [accounts], [folders], [recent], [loaded], [syncing], [savedSearches] and [senders] are snapshot
 * state: a screen that reads them is drawn again when they change. The lists of messages are
 * watched one view at a time.
 */
interface MailSource {
    /** The accounts in the user's order. */
    val accounts: List<MailAccount>

    /** Every account's folders with their counts, account by account. */
    val folders: List<MailFolder>

    /** False until the accounts and the folders have been read once: "nothing known yet" is not "no accounts". */
    val loaded: Boolean

    /** The newest messages of every account, each once: what the command palette offers. */
    val recent: List<MailMessage>

    /** Whether mail is being fetched right now. */
    val syncing: Boolean

    /** The searches kept as folders of their own ([MailView.Saved]), in the user's order. */
    val savedSearches: List<SavedSearch>

    /** What the user said of senders (VIPs, whose remote images are shown), by address in lower case. */
    val senders: Map<String, SenderMarks>

    /** What was said of the sender [address]; nothing for one nothing was said of. */
    fun sender(address: String): SenderMarks = senders[address.trim().lowercase()] ?: SenderMarks()

    /** Keeps a search as a folder: a new one for [id] 0, else that one changed (named [name], looking for [query]). Its id. */
    suspend fun saveSearch(id: Long, name: String, query: String): Long

    suspend fun removeSearch(id: Long)

    /** Keeps what is said of the sender [address]: a VIP or not, whose remote images are shown or not. */
    suspend fun markSender(address: String, marks: SenderMarks)

    fun account(id: Long): MailAccount? = accounts.firstOrNull { it.id == id }

    fun folder(id: Long): MailFolder? = folders.firstOrNull { it.id == id }

    /**
     * Fetches the header lines of the message [id] from its server, for one whose text came before
     * they were kept with it; its content then has them. False when they could not be had.
     */
    suspend fun fetchHeaders(id: Long): Boolean = false

    /** The messages of [view], newest first, now and whenever they change. */
    fun observe(view: MailView): Flow<List<MailMessage>>

    /** The messages of [view] as they were last seen, to draw at once; null when they have to be read first. */
    fun cached(view: MailView): List<MailMessage>?

    /**
     * The conversation of [message], oldest first: the message it began with and those that answer
     * it, wherever they are filed (the user's own replies too), but not in Trash or Spam. Just the
     * message for one that is a conversation of its own.
     */
    fun observeThread(message: MailMessage): Flow<List<MailMessage>> = flowOf(listOf(message))

    /** One message, wherever it is filed; null once it is gone. */
    fun observeMessage(id: Long): Flow<MailMessage?>

    /** The message as it was last seen, or null. */
    fun cachedMessage(id: Long): MailMessage?

    /** What the message says. Watching it has the text fetched when the device does not have it yet. */
    fun observeContent(id: Long): Flow<MailContent>

    /** Asks once more for a message's text after it could not be fetched. */
    fun retryContent(id: Long)

    // What is done to a message shows at once and is told to its server in the background; while
    // the server cannot be reached it waits, and is told when it can.

    /** A message that is opened is read. */
    fun markRead(id: Long)

    fun toggleUnread(id: Long)

    fun toggleStarred(id: Long)

    /**
     * Asks the servers of every account for what [query] finds ([MailView.Search]), and brings
     * onto the device what it did not have: the search screen lists it from then on. Null when
     * there is nothing in [query] to look for.
     */
    suspend fun searchServers(query: String): ServerSearch?

    /** Marks these messages read, or unread, all the same way: a selection. What is so marked already is left as it is. */
    fun markUnread(ids: List<Long>, unread: Boolean)

    /** Stars these messages, or takes their stars away. */
    fun markStarred(ids: List<Long>, starred: Boolean)

    /**
     * Moves the messages to their account's archive: its Archive folder or, where it has none
     * (Gmail), "All Mail". Null when none of them could be: no such folder, or they are in it.
     */
    suspend fun archive(ids: List<Long>): MailUndo?

    /**
     * Moves the messages to their account's trash. One that is in the trash already (or whose
     * account has none) is removed for good, once the time to take it back has passed.
     */
    suspend fun delete(ids: List<Long>): MailUndo?

    /** Moves the messages to a folder of their own account. Null when none of them could be. */
    suspend fun move(ids: List<Long>, folderId: Long): MailUndo?

    /** Takes back what was done: the messages are where they were. Returns how many of them are. */
    suspend fun undo(undo: MailUndo): Int

    /** Lets what was done stand: the server is told now, not when the time to take it back has passed. */
    fun commit(undo: MailUndo)

    /** The folders the message can be moved to: its account's, without the one it is in. */
    fun moveTargets(message: MailMessage): List<MailFolder> =
        folders.filter { it.accountId == message.accountId && it.id != message.folderId }

    /** Fetches a file a message carries onto the device, to be opened or saved from there. */
    suspend fun fetch(attachment: MailAttachment): FetchedFile

    /** Told that a screen showing [view] came to the front: what it shows is brought up to date if it has not been lately. */
    fun viewShown(view: MailView)

    /** Asks the servers for what is new now: every account's inbox, and [view]'s folder. */
    fun refresh(view: MailView? = null)
}
