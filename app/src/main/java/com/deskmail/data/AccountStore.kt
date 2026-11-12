package com.deskmail.data

import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.MailException
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.MailSettings
import com.deskmail.db.AccountEntity
import com.deskmail.db.CredentialEntity
import com.deskmail.db.DeskMailDatabase
import com.deskmail.mail.AccountState

/**
 * The accounts and what they sign in with. A password is sealed ([SecretBox]) before it is stored
 * and opened only to sign in; it is never written anywhere else, the log included.
 */
class AccountStore(db: DeskMailDatabase, private val box: SecretBox, private val log: AppLog? = null, private val now: () -> Long = System::currentTimeMillis) {
    private val accounts = db.accounts()

    suspend fun all(): List<AccountEntity> = accounts.all()

    suspend fun byId(id: Long): AccountEntity? = accounts.byId(id)

    suspend fun byAddress(address: String): AccountEntity? = accounts.byAddress(address.trim())

    /**
     * Adds an account that signs in with a password.
     *
     * @param name what the user knows it by; [provider] is the provider table's id, empty for servers set up by hand.
     * @return the account's id.
     */
    suspend fun add(address: String, name: String, provider: String, settings: MailSettings, password: String): Long {
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
        val id = accounts.add(account) { accountId -> sealPassword(accountId, password) }
        log?.info(TAG, "account $id added: ${account.address} at ${account.imapHost}")
        return id
    }

    /** Replaces what the account signs in with ("Reconnect"). */
    suspend fun savePassword(accountId: Long, password: String) {
        accounts.saveCredential(sealPassword(accountId, password))
    }

    /**
     * What the account signs in with, or null when there is nothing stored that this device can
     * open (the keystore's key is gone): the user has to sign in again.
     */
    suspend fun credentials(account: AccountEntity): Credentials? {
        val stored = accounts.credential(account.id) ?: return null
        if (stored.kind != KIND_PASSWORD) return null
        return try {
            val password = String(box.open(Sealed(stored.iv, stored.secret), purpose(account.id)), Charsets.UTF_8)
            Credentials.Password(account.username, password)
        } catch (e: Exception) {
            log?.warn(TAG, "account ${account.id}: the stored sign-in could not be opened (${MailException.trail(e)})")
            null
        }
    }

    suspend fun setState(accountId: Long, state: AccountState, detail: String = "") = accounts.setState(accountId, state.name, detail)

    /** Removes the account from the device with everything that was kept of its mail. The mail stays on its server. */
    suspend fun remove(accountId: Long) {
        accounts.delete(accountId)
        log?.info(TAG, "account $accountId removed")
    }

    private fun sealPassword(accountId: Long, password: String): CredentialEntity {
        val sealed = box.seal(password.toByteArray(Charsets.UTF_8), purpose(accountId))
        return CredentialEntity(accountId, KIND_PASSWORD, sealed.iv, sealed.data, now())
    }

    private fun purpose(accountId: Long) = "credential/$accountId"

    companion object {
        private const val TAG = "accounts"

        /** How a stored credential says what it is; OAuth tokens get a name of their own. */
        const val KIND_PASSWORD = "password"

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
