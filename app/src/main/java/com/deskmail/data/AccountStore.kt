package com.deskmail.data

import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.MailException
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.oauth.OAuthConfig
import com.deskmail.core.oauth.TokenClient
import com.deskmail.core.oauth.Tokens
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.MailSettings
import com.deskmail.core.provider.browserSignIn
import com.deskmail.db.AccountEntity
import com.deskmail.db.CredentialEntity
import com.deskmail.db.DeskMailDatabase
import com.deskmail.mail.AccountState
import com.deskmail.mail.PortableAccount
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * The accounts and what they sign in with. A password, or the refresh token of an account that
 * signs in at its provider in the browser, is sealed ([SecretBox]) before it is stored and opened
 * only to sign in; it is never written anywhere else, the log included. The access tokens a
 * refresh token is traded for are good for about an hour, and are kept in memory only.
 *
 * @param signIns where the accounts of a kind sign in in the browser: [browserSignIn], but in the tests.
 * @param tokenClient asks a provider's token endpoint: [TokenClient], but in the tests.
 */
class AccountStore(
    db: DeskMailDatabase,
    private val box: SecretBox,
    private val log: AppLog? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val signIns: (AuthKind) -> OAuthConfig? = { browserSignIn(it) },
    private val tokenClient: (OAuthConfig) -> TokenClient = { TokenClient(it) },
) {
    private val accounts = db.accounts()

    /** The access token each account that signs in in the browser was handed last, and until when it is good. */
    private val accessTokens = ConcurrentHashMap<Long, AccessToken>()

    /**
     * One renewal at a time: a renewal spends the refresh token and leaves a new one, and two at
     * once could each spend the one the other needs.
     */
    private val renewal = Mutex()

    private class AccessToken(val value: String, val goodUntil: Long)

    suspend fun all(): List<AccountEntity> = accounts.all()

    suspend fun byId(id: Long): AccountEntity? = accounts.byId(id)

    suspend fun byAddress(address: String): AccountEntity? = accounts.byAddress(address.trim())

    /**
     * Adds an account that signs in with a password.
     *
     * @param name what the user knows it by; [provider] is the provider table's id, empty for servers set up by hand.
     * @return the account's id.
     */
    suspend fun add(address: String, name: String, provider: String, settings: MailSettings, password: String): Long =
        insert(address, name, provider, settings) { accountId -> sealed(accountId, KIND_PASSWORD, password) }

    /**
     * Adds an account that signs in at its provider in the browser. What is kept of the sign-in is
     * the refresh token; the access token that came with it is used for as long as it is good.
     *
     * @throws IllegalArgumentException when [tokens] have no refresh token: such a sign-in cannot be kept.
     */
    suspend fun addSignedIn(address: String, name: String, provider: String, settings: MailSettings, tokens: Tokens): Long {
        val refreshToken = requireNotNull(tokens.refreshToken) { "a sign-in without a refresh token cannot be kept" }
        val id = insert(address, name, provider, settings) { accountId -> sealed(accountId, KIND_OAUTH, refreshToken) }
        hold(id, tokens)
        return id
    }

    private suspend fun insert(address: String, name: String, provider: String, settings: MailSettings, credential: (accountId: Long) -> CredentialEntity): Long {
        val account = AccountEntity(
            address = address.trim(),
            name = name,
            senderName = "",
            provider = provider,
            imapHost = settings.imap.host,
            imapPort = settings.imap.port,
            imapSecurity = settings.imap.security.name,
            smtpHost = settings.smtp.host,
            smtpPort = settings.smtp.port,
            smtpSecurity = settings.smtp.security.name,
            username = settings.username,
            authKind = settings.auth.name,
            syncDays = DEFAULT_SYNC_DAYS,
            notifyMode = DEFAULT_NOTIFY_MODE,
            signature = "",
            state = AccountState.Ok.name,
            stateDetail = "",
            position = 0,
            createdAt = now(),
        )
        val id = accounts.add(account, credential)
        log?.info(TAG, "account $id added: ${account.address} at ${account.imapHost}")
        return id
    }

    /** Replaces what the account signs in with ("Reconnect"). */
    suspend fun savePassword(accountId: Long, password: String) {
        accounts.saveCredential(sealed(accountId, KIND_PASSWORD, password))
    }

    /**
     * Replaces the sign-in of an account that signs in in the browser ("Reconnect").
     *
     * @throws IllegalArgumentException when [tokens] have no refresh token.
     */
    suspend fun saveTokens(accountId: Long, tokens: Tokens) {
        val refreshToken = requireNotNull(tokens.refreshToken) { "a sign-in without a refresh token cannot be kept" }
        renewal.withLock {
            accounts.saveCredential(sealed(accountId, KIND_OAUTH, refreshToken))
            hold(accountId, tokens)
        }
    }

    /**
     * What the account signs in with, or null when there is nothing stored that this device can
     * open (the keystore's key is gone), or the app has no way to sign in to it: the user has to
     * sign in again.
     *
     * For an account that signs in in the browser, that is an access token: the one at hand while
     * it is good for a few minutes more, else a new one for the refresh token. Then the provider is
     * asked, and the call blocks until it answers.
     *
     * @throws MailException when the provider hands out no new access token: the refresh token is
     *   no longer good ([MailException.Kind.Authentication]: the user has to sign in again), or the
     *   provider could not be asked.
     */
    suspend fun credentials(account: AccountEntity): Credentials? {
        val stored = accounts.credential(account.id) ?: return null
        return when (stored.kind) {
            KIND_PASSWORD -> opened(stored, account.id)?.let { Credentials.Password(account.username, it) }
            KIND_OAUTH -> accessToken(account)
            // written by a newer version
            else -> null
        }
    }

    private suspend fun accessToken(account: AccountEntity): Credentials? = renewal.withLock {
        accessTokens[account.id]?.takeIf { now() < it.goodUntil - RENEW_EARLY_MS }?.let { held ->
            return@withLock Credentials.OAuth(account.username, held.value)
        }
        val config = signIns(account.auth())
        if (config == null) {
            log?.warn(TAG, "account ${account.id}: this version has no way to sign in to it")
            return@withLock null
        }
        // read again under the lock: a renewal that went before may have left a new refresh token
        val stored = accounts.credential(account.id)?.takeIf { it.kind == KIND_OAUTH } ?: return@withLock null
        val refreshToken = opened(stored, account.id) ?: return@withLock null
        val renewed = try {
            tokenClient(config).refresh(refreshToken)
        } catch (e: MailException) {
            log?.warn(TAG, "account ${account.id}: no new access token (${e.kind}): ${e.message}")
            throw e
        }
        // the provider hands out a new refresh token with most renewals; it takes the old one's place
        val next = renewed.refreshToken
        if (next != null && next != refreshToken) {
            try {
                accounts.saveCredential(sealed(account.id, KIND_OAUTH, next))
            } catch (e: Exception) {
                // the access token is good all the same; the old refresh token stays, for as long as the provider takes it
                log?.error(TAG, "account ${account.id}: the new refresh token could not be kept", e)
            }
        }
        hold(account.id, renewed)
        log?.info(TAG, "account ${account.id}: a new access token, good for ${renewed.lifetimeSeconds / 60} minutes")
        Credentials.OAuth(account.username, renewed.accessToken)
    }

    /** The access token that came with [tokens], from now until it is no longer good. */
    private fun hold(accountId: Long, tokens: Tokens) {
        accessTokens[accountId] = AccessToken(tokens.accessToken, now() + tokens.lifetimeSeconds * 1000)
    }

    /** What [stored] keeps, or null when this device cannot open it. */
    private fun opened(stored: CredentialEntity, accountId: Long): String? = try {
        String(box.open(Sealed(stored.iv, stored.secret), purpose(accountId)), Charsets.UTF_8)
    } catch (e: Exception) {
        log?.warn(TAG, "account $accountId: the stored sign-in could not be opened (${MailException.trail(e)})")
        null
    }

    suspend fun setState(accountId: Long, state: AccountState, detail: String = "") = accounts.setState(accountId, state.name, detail)

    /**
     * The name the account's mail is sent under and what it is signed with. The name is one line
     * of a header: line breaks and control characters do not belong in it.
     */
    suspend fun saveWriting(accountId: Long, senderName: String, signature: String) {
        val name = senderName.replace(CONTROLS, " ").trim().take(MAX_SENDER_NAME)
        accounts.setWriting(accountId, name, signature.replace("\r\n", "\n").trim().take(MAX_SIGNATURE))
    }

    /**
     * Adds an account that came in a settings file from another device, with nothing to sign in
     * with: it waits for its password or its sign-in in the browser ("Reconnect"), and nothing is
     * fetched for it before. What can
     * be checked of the file's word is: an address, servers, a name and a signature of sensible
     * length. Null when the device has an account with its address already, or the file's word
     * would not do.
     */
    suspend fun addWaiting(account: PortableAccount): Long? {
        val address = account.address.trim()
        if (address.isEmpty() || address.any { it.isWhitespace() } || '@' !in address) return null
        if (account.settings.imap.host.isBlank() || account.settings.smtp.host.isBlank()) return null
        if (byAddress(address) != null) return null
        val entity = AccountEntity(
            address = address,
            name = account.name.replace(CONTROLS, " ").trim().take(MAX_NAME).ifBlank { address },
            senderName = account.senderName.replace(CONTROLS, " ").trim().take(MAX_SENDER_NAME),
            provider = account.provider,
            imapHost = account.settings.imap.host.trim(),
            imapPort = account.settings.imap.port,
            imapSecurity = account.settings.imap.security.name,
            smtpHost = account.settings.smtp.host.trim(),
            smtpPort = account.settings.smtp.port,
            smtpSecurity = account.settings.smtp.security.name,
            username = account.settings.username,
            authKind = account.settings.auth.name,
            syncDays = account.syncDays.takeIf { it > 0 } ?: DEFAULT_SYNC_DAYS,
            notifyMode = account.notifyMode.ifBlank { DEFAULT_NOTIFY_MODE },
            signature = account.signature.replace("\r\n", "\n").trim().take(MAX_SIGNATURE),
            state = AccountState.NeedsLogin.name,
            stateDetail = "",
            position = accounts.nextPosition(),
            createdAt = now(),
        )
        val id = accounts.insert(entity)
        log?.info(TAG, "account $id taken from a settings file: ${entity.address} at ${entity.imapHost}; it waits to be signed in to")
        return id
    }

    /** Removes the account from the device with everything that was kept of its mail. The mail stays on its server. */
    suspend fun remove(accountId: Long) {
        accounts.delete(accountId)
        accessTokens.remove(accountId)
        log?.info(TAG, "account $accountId removed")
    }

    private fun sealed(accountId: Long, kind: String, secret: String): CredentialEntity {
        val sealed = box.seal(secret.toByteArray(Charsets.UTF_8), purpose(accountId))
        return CredentialEntity(accountId, kind, sealed.iv, sealed.data, now())
    }

    private fun purpose(accountId: Long) = "credential/$accountId"

    companion object {
        private const val TAG = "accounts"

        /** How a stored credential says what it is: a password (or an app password)… */
        const val KIND_PASSWORD = "password"

        /** …or the refresh token of a sign-in in the browser (OAuth 2.0). */
        const val KIND_OAUTH = "oauth"

        /** An access token is renewed this long before it runs out: a sync begun with it is not cut off half-way. */
        const val RENEW_EARLY_MS = 5 * 60_000L

        /** Line breaks, tabs and the like, however many in a row. */
        private val CONTROLS = Regex("\\p{Cntrl}+")

        /** A sender's name and a signature are kept up to these lengths; an account's name too. */
        const val MAX_SENDER_NAME = 100
        const val MAX_NAME = 100
        const val MAX_SIGNATURE = 2000

        /** How far back mail is kept until the user says otherwise. */
        const val DEFAULT_SYNC_DAYS = 30

        /** New mail is to be learnt of at once (the notifications that do so come later). */
        const val DEFAULT_NOTIFY_MODE = "push"
    }
}

/** Where the account's mail is fetched. A security this version does not know is read as TLS: never as less. */
fun AccountEntity.imapServer(): ServerAddress = ServerAddress(imapHost, imapPort, security(imapSecurity))

fun AccountEntity.smtpServer(): ServerAddress = ServerAddress(smtpHost, smtpPort, security(smtpSecurity))

fun AccountEntity.accountState(): AccountState = AccountState.entries.firstOrNull { it.name == state } ?: AccountState.Ok

fun AccountEntity.auth(): AuthKind = AuthKind.entries.firstOrNull { it.name == authKind } ?: AuthKind.Password

private fun security(name: String): Security = Security.entries.firstOrNull { it.name == name } ?: Security.Tls
