package com.deskmail.data

import com.deskmail.core.imap.ImapConnection
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.MailException
import com.deskmail.core.mail.RemoteAccount
import com.deskmail.core.mail.RemoteFolder
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.oauth.AuthorizationAnswer
import com.deskmail.core.oauth.AuthorizationRequest
import com.deskmail.core.oauth.OAuthConfig
import com.deskmail.core.oauth.TokenClient
import com.deskmail.core.oauth.Tokens
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.Autoconfig
import com.deskmail.core.provider.MailSettings
import com.deskmail.core.provider.Provider
import com.deskmail.core.provider.ProviderTable
import com.deskmail.core.provider.browserSignIn
import com.deskmail.db.DeskMailDatabase
import com.deskmail.mail.AccountActions
import com.deskmail.mail.AccountDraft
import com.deskmail.mail.AccountState
import com.deskmail.mail.PortableAccount
import com.deskmail.mail.SetupPlan
import com.deskmail.mail.SetupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adds, reconnects and removes accounts. An account is only kept once its server has taken the
 * sign-in: the folders the server lists then are the account's first, and its mail starts to arrive.
 *
 * Only the IMAP server is asked here. The SMTP server's settings are kept as they were found and
 * are first used when the app can send.
 *
 * An account that signs in at its provider (Microsoft) does so in the browser: the provider's page
 * is opened, the browser comes back to the app with a code ([browser]), and the code is traded
 * for tokens. The access token then signs in to the IMAP server like a password would.
 *
 * @param startSync has an account's mail fetched once it was added or signed in again; the app
 *   hands that to the one place that keeps track of the syncs under way ([LiveMail.sync]).
 * @param browser the way back from the browser; the app has one, which the browser comes back to.
 * @param signIns where the accounts of a kind sign in in the browser: [browserSignIn], but in the tests.
 * @param tokenClient asks a provider's token endpoint: [TokenClient], but in the tests.
 */
class AccountSetup(
    private val db: DeskMailDatabase,
    private val accounts: AccountStore,
    private val sync: MailSync,
    private val autoconfig: Autoconfig,
    private val log: AppLog,
    private val scope: CoroutineScope,
    private val connect: (ServerAddress, Credentials) -> RemoteAccount = { server, credentials -> ImapConnection.open(server, credentials, log) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val startSync: (accountId: Long) -> Unit = { accountId -> scope.launch { sync.account(accountId) } },
    private val onRemoved: (accountId: Long, folderIds: List<Long>) -> Unit = { _, _ -> },
    private val browser: BrowserSignIn = BrowserSignIn(log),
    private val signIns: (AuthKind) -> OAuthConfig? = { browserSignIn(it) },
    private val tokenClient: (OAuthConfig) -> TokenClient = { TokenClient(it) },
) : AccountActions {
    private val store = RoomSyncStore(db)

    override suspend fun find(address: String): SetupPlan = withContext(io) {
        val typed = address.trim()
        if (typed.any { it.isWhitespace() } || ProviderTable.domainOf(typed) == null) return@withContext SetupPlan.NotAnAddress
        if (accounts.byAddress(typed) != null) return@withContext SetupPlan.Duplicate
        val found = autoconfig.find(typed) ?: return@withContext SetupPlan.Manual
        val provider = found.provider
        if (found.settings.auth != AuthKind.MicrosoftOAuth || provider == null) return@withContext SetupPlan.Found(found.settings, provider)
        // The app's registration at Microsoft takes personal accounts: an address at a domain of its
        // own whose mail Microsoft takes is a work or school one (Microsoft 365).
        val hosted = found.source != Autoconfig.Source.Table
        if (hosted || signIns(found.settings.auth) == null) SetupPlan.NotYet(provider, hosted) else SetupPlan.SignIn(found.settings, provider)
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
            accounts.add(address, nameFor(address, draft.provider), draft.provider?.id.orEmpty(), draft.settings, password)
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

    override suspend fun addSignedIn(address: String, plan: SetupPlan.SignIn, open: suspend (url: String) -> Boolean): SetupResult {
        val typed = address.trim()
        if (withContext(io) { accounts.byAddress(typed) } != null) return SetupResult.Duplicate
        val config = signIns(plan.settings.auth) ?: return SetupResult.Failed(MailException.Kind.Server, "")
        val tokens = when (val signed = signInAt(config, typed, open)) {
            is Signed.In -> signed.tokens
            is Signed.Not -> return signed.result
        }
        return withContext(io) {
            val folders = try {
                signIn(plan.settings.imap, Credentials.OAuth(plan.settings.username, tokens.accessToken))
            } catch (e: MailException) {
                return@withContext SetupResult.Failed(e.kind, e.message.orEmpty())
            }
            // the server took it: it is kept, whoever stops waiting for it now
            withContext(NonCancellable) {
                val id = try {
                    accounts.addSignedIn(typed, nameFor(typed, plan.provider), plan.provider.id, plan.settings, tokens)
                } catch (e: Exception) {
                    return@withContext notKept(e)
                }
                store.saveFolders(id, folders)
                startSync(id)
                SetupResult.Done(id)
            }
        }
    }

    override suspend fun reconnectSignedIn(accountId: Long, open: suspend (url: String) -> Boolean): SetupResult {
        val account = withContext(io) { accounts.byId(accountId) } ?: return SetupResult.Failed(MailException.Kind.Server, "")
        val config = signIns(account.auth()) ?: return SetupResult.Failed(MailException.Kind.Server, "")
        val tokens = when (val signed = signInAt(config, account.address, open)) {
            is Signed.In -> signed.tokens
            is Signed.Not -> return signed.result
        }
        return withContext(io) {
            try {
                signIn(account.imapServer(), Credentials.OAuth(account.username, tokens.accessToken))
            } catch (e: MailException) {
                return@withContext SetupResult.Failed(e.kind, e.message.orEmpty())
            }
            withContext(NonCancellable) {
                try {
                    accounts.saveTokens(accountId, tokens)
                } catch (e: Exception) {
                    return@withContext notKept(e)
                }
                accounts.setState(accountId, AccountState.Ok)
                log.info(TAG, "account $accountId signed in again in the browser")
                startSync(accountId)
                SetupResult.Done(accountId)
            }
        }
    }

    /** What came of signing in at the provider in the browser: the tokens, or the result that ends it there. */
    private sealed interface Signed {
        class In(val tokens: Tokens) : Signed

        class Not(val result: SetupResult) : Signed
    }

    /**
     * Opens the provider's page with [open], waits for the browser to come back, and trades the
     * code it brought for tokens. Only a sign-in with a refresh token will do: without one the
     * account would have to be signed in to again within the hour.
     */
    private suspend fun signInAt(config: OAuthConfig, address: String, open: suspend (url: String) -> Boolean): Signed {
        val request = AuthorizationRequest(config, loginHint = address)
        val answer = when (val outcome = browser.ask(request, open)) {
            BrowserSignIn.Outcome.NoBrowser -> return Signed.Not(SetupResult.NoBrowser)
            BrowserSignIn.Outcome.TimedOut -> return Signed.Not(SetupResult.NotSignedIn(""))
            is BrowserSignIn.Outcome.Answered -> outcome.answer
        }
        val code = when (answer) {
            is AuthorizationAnswer.Code -> answer.code
            is AuthorizationAnswer.Refused -> {
                log.info(TAG, "the sign-in in the browser came back without a code: ${answer.error}")
                return Signed.Not(SetupResult.NotSignedIn(answer.description.ifBlank { answer.error }))
            }
        }
        val tokens = try {
            withContext(io) { tokenClient(config).redeem(code, request) }
        } catch (e: MailException) {
            log.warn(TAG, "the sign-in's code brought no tokens (${e.kind}): ${e.message}")
            // a code that is no longer good (it was used, or took too long) is signed in for anew
            val result = if (e.kind == MailException.Kind.Authentication) SetupResult.NotSignedIn(e.message.orEmpty()) else SetupResult.Failed(e.kind, e.message.orEmpty())
            return Signed.Not(result)
        }
        if (tokens.refreshToken == null) {
            log.warn(TAG, "the sign-in brought no refresh token; it cannot be kept")
            return Signed.Not(SetupResult.NotSignedIn(""))
        }
        return Signed.In(tokens)
    }

    override suspend fun saveWriting(accountId: Long, senderName: String, signature: String) = withContext(io) {
        accounts.saveWriting(accountId, senderName, signature)
    }

    override suspend fun portable(): List<PortableAccount> = withContext(io) {
        accounts.all().map { account ->
            PortableAccount(
                address = account.address,
                name = account.name,
                senderName = account.senderName,
                provider = account.provider,
                settings = MailSettings(account.imapServer(), account.smtpServer(), account.username, account.auth()),
                syncDays = account.syncDays,
                notifyMode = account.notifyMode,
                signature = account.signature,
            )
        }
    }

    override suspend fun adopt(accounts: List<PortableAccount>): Int = withContext(io) {
        accounts.count { account -> this@AccountSetup.accounts.addWaiting(account) != null }
    }

    override suspend fun remove(accountId: Long) = withContext(io) {
        val folders = db.folders().ofAccount(accountId).map { it.id }
        accounts.remove(accountId)
        // the index has no ties to the messages
        store.forget()
        onRemoved(accountId, folders)
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
    private suspend fun nameFor(address: String, provider: Provider?): String {
        val wanted = provider?.name ?: ProviderTable.domainOf(address) ?: address
        return if (accounts.all().any { it.name.equals(wanted, ignoreCase = true) }) address else wanted
    }

    private companion object {
        const val TAG = "accounts"
    }
}
