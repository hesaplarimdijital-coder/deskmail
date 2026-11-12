package com.deskmail.data

import com.deskmail.core.imap.ImapConnection
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.MailException
import com.deskmail.core.mail.RemoteAccount
import com.deskmail.core.mail.RemoteFolder
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.Autoconfig
import com.deskmail.core.provider.ProviderTable
import com.deskmail.db.DeskMailDatabase
import com.deskmail.mail.AccountActions
import com.deskmail.mail.AccountDraft
import com.deskmail.mail.AccountState
import com.deskmail.mail.SetupPlan
import com.deskmail.mail.SetupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adds, reconnects and removes accounts. An account is only kept once its server has taken the
 * sign-in: the folders the server lists then are the account's first, and its mail starts to arrive.
 *
 * Only the IMAP server is asked here. The SMTP server's settings are kept as they were found and
 * are first used when the app can send.
 *
 * @param startSync has an account's mail fetched once it was added or signed in again; the app
 *   hands that to the one place that keeps track of the syncs under way ([LiveMail.sync]).
 */
class AccountSetup(
    db: DeskMailDatabase,
    private val accounts: AccountStore,
    private val sync: MailSync,
    private val autoconfig: Autoconfig,
    private val log: AppLog,
    private val scope: CoroutineScope,
    private val connect: (ServerAddress, Credentials) -> RemoteAccount = { server, credentials -> ImapConnection.open(server, credentials, log) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val startSync: (accountId: Long) -> Unit = { accountId -> scope.launch { sync.account(accountId) } },
) : AccountActions {
    private val store = RoomSyncStore(db)

    override suspend fun find(address: String): SetupPlan = withContext(io) {
        val typed = address.trim()
        if (typed.any { it.isWhitespace() } || ProviderTable.domainOf(typed) == null) return@withContext SetupPlan.NotAnAddress
        if (accounts.byAddress(typed) != null) return@withContext SetupPlan.Duplicate
        val found = autoconfig.find(typed) ?: return@withContext SetupPlan.Manual
        val provider = found.provider
        if (found.settings.auth == AuthKind.MicrosoftOAuth && provider != null) SetupPlan.NotYet(provider) else SetupPlan.Found(found.settings, provider)
    }

    override suspend fun add(draft: AccountDraft): SetupResult = withContext(io) {
        val address = draft.address.trim()
        if (accounts.byAddress(address) != null) return@withContext SetupResult.Duplicate
        val password = password(draft.password, draft.settings.auth)
        val folders = try {
            signIn(draft.settings.imap, Credentials.Password(draft.settings.username, password))
        } catch (e: MailException) {
            return@withContext SetupResult.Failed(e.kind, e.message.orEmpty())
        }
        val id = try {
            accounts.add(address, nameFor(address, draft), draft.provider?.id.orEmpty(), draft.settings, password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext notKept(e)
        }
        store.saveFolders(id, folders)
        startSync(id)
        SetupResult.Done(id)
    }

    override suspend fun reconnect(accountId: Long, password: String): SetupResult = withContext(io) {
        val account = accounts.byId(accountId) ?: return@withContext SetupResult.Failed(MailException.Kind.Server, "")
        val secret = password(password, account.auth())
        try {
            signIn(account.imapServer(), Credentials.Password(account.username, secret))
        } catch (e: MailException) {
            return@withContext SetupResult.Failed(e.kind, e.message.orEmpty())
        }
        try {
            accounts.savePassword(accountId, secret)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext notKept(e)
        }
        accounts.setState(accountId, AccountState.Ok)
        log.info(TAG, "account $accountId signed in again")
        startSync(accountId)
        SetupResult.Done(accountId)
    }

    override suspend fun remove(accountId: Long) = withContext(io) {
        accounts.remove(accountId)
        // the index has no ties to the messages
        store.forget()
    }

    /**
     * The server took the sign-in and the device could not keep it: its keystore would not seal the
     * password, or its database would not take the row. Said to the user, with the place it happened
     * in the log; nothing of the account is left behind.
     */
    private fun notKept(error: Exception): SetupResult {
        log.error(TAG, "the sign-in could not be kept on this device", error)
        return SetupResult.Failed(MailException.Kind.Server, error.toString())
    }

    /** Signs in and reads the list of folders: proof that the address, the password and the server belong together. */
    private fun signIn(server: ServerAddress, credentials: Credentials): List<RemoteFolder> =
        connect(server, credentials).use { remote -> remote.folders().filter { it.isListed } }

    /**
     * The password as it is sent. An app password is shown in groups of four with spaces between
     * them, and is copied that way; the spaces are not part of it.
     */
    private fun password(typed: String, auth: AuthKind): String =
        if (auth == AuthKind.AppPassword) typed.filterNot { it.isWhitespace() } else typed

    /** What the account is called to begin with: its provider, its domain, or its address when that name is taken. */
    private suspend fun nameFor(address: String, draft: AccountDraft): String {
        val wanted = draft.provider?.name ?: ProviderTable.domainOf(address) ?: address
        return if (accounts.all().any { it.name.equals(wanted, ignoreCase = true) }) address else wanted
    }

    private companion object {
        const val TAG = "accounts"
    }
}
