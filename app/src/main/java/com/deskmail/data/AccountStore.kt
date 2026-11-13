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
import com.deskmail.mail.PortableAccount

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
     * with: it waits for its password ("Reconnect"), and nothing is fetched for it before. What can
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
        log?.info(TAG, "account $id taken from a settings file: ${entity.address} at ${entity.imapHost}; it waits for its password")
        return id
    }

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
