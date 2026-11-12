package com.deskmail.mail

import kotlinx.coroutines.flow.Flow

/**
 * Where the screens get the mail from and what they can ask of it: the device's copy of the
 * accounts' mail in the app, made-up mail in the tests and the screenshots.
 *
 * [accounts], [folders], [recent], [loaded] and [syncing] are snapshot state: a screen that reads
 * them is drawn again when they change. The lists of messages are watched one view at a time.
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

    fun account(id: Long): MailAccount? = accounts.firstOrNull { it.id == id }

    fun folder(id: Long): MailFolder? = folders.firstOrNull { it.id == id }

    /** The messages of [view], newest first, now and whenever they change. */
    fun observe(view: MailView): Flow<List<MailMessage>>

    /** The messages of [view] as they were last seen, to draw at once; null when they have to be read first. */
    fun cached(view: MailView): List<MailMessage>?

    /** One message, wherever it is filed; null once it is gone. */
    fun observeMessage(id: Long): Flow<MailMessage?>

    /** The message as it was last seen, or null. */
    fun cachedMessage(id: Long): MailMessage?

    /** What the message says. Watching it has the text fetched when the device does not have it yet. */
    fun observeContent(id: Long): Flow<MailContent>

    /** Asks once more for a message's text after it could not be fetched. */
    fun retryContent(id: Long)

    /**
     * Whether read/unread and the star can be changed. This pre-release only reads the mail of
     * real accounts: the marks are the server's, and changing them there comes with the next one.
     */
    val canMark: Boolean

    /** A message that is opened is read. */
    fun markRead(id: Long)

    fun toggleUnread(id: Long)

    fun toggleStarred(id: Long)

    /** Told that a screen showing [view] came to the front: what it shows is brought up to date if it has not been lately. */
    fun viewShown(view: MailView)

    /** Asks the servers for what is new now: every account's inbox, and [view]'s folder. */
    fun refresh(view: MailView? = null)
}
