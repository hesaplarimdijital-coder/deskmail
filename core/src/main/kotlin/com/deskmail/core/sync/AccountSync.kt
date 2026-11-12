package com.deskmail.core.sync

import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.RemoteAccount
import java.time.Instant

/**
 * Brings the device's copy of an account up to its server: the list of folders with the server's
 * counts, then the messages of the folders that are kept current.
 *
 * The inbox always is; any other folder is synced when the app asks for it (the user opened it).
 * The calls to the server block: run this where waiting is allowed.
 */
class AccountSync(private val store: SyncStore, private val log: AppLog? = null) {
    private val folderSync = FolderSync(store, log)

    /**
     * @param folders the account's folders after this sync.
     * @param synced what became of each folder that was synced, by its id.
     */
    data class Result(val folders: List<StoredFolder>, val synced: Map<Long, FolderSync.Result>) {
        /** Some folder has messages whose text is still to be fetched: another run goes on. */
        val moreToFetch: Boolean get() = synced.values.any { it.bodiesLeft }
    }

    /**
     * @param also folders to sync besides the inbox, by their paths.
     * @param days how far back messages are kept; 0 for all.
     * @param bodyLimit how many messages of each synced folder get their text in this run; the rest
     *   is left for [FolderSync.bodies].
     */
    suspend fun sync(
        account: RemoteAccount,
        accountId: Long,
        days: Int,
        now: Instant,
        also: Set<String> = emptySet(),
        bodyLimit: Int = FolderSync.BODIES_PER_RUN,
    ): Result {
        // what holds no messages of its own is not kept: a folder of folders, a view over the others
        val folders = store.saveFolders(accountId, account.folders().filter { it.isListed })
        val wanted = folders.filter { it.selectable && (it.role == FolderRole.Inbox || it.path in also) }
        val synced = LinkedHashMap<Long, FolderSync.Result>()
        for (folder in wanted) synced[folder.id] = folder(account, folder, days, now, bodyLimit)
        // the others are only counted: what the badges show of them
        for (folder in folders) {
            if (!folder.selectable || folder.id in synced) continue
            account.counts(folder.path)?.let { store.saveCounts(folder.id, it.total, it.unread) }
        }
        log?.info(TAG, "account $accountId: ${folders.size} folders, ${synced.size} synced")
        return Result(folders, synced)
    }

    /** Syncs one folder of the account. */
    suspend fun folder(account: RemoteAccount, folder: StoredFolder, days: Int, now: Instant, bodyLimit: Int = FolderSync.BODIES_PER_RUN): FolderSync.Result =
        account.open(folder.path).use { mailbox -> folderSync.sync(mailbox, folder, days, now, bodyLimit) }

    private companion object {
        const val TAG = "sync"
    }
}
