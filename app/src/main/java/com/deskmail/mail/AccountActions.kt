package com.deskmail.mail

import com.deskmail.core.mail.MailException
import com.deskmail.core.provider.MailSettings
import com.deskmail.core.provider.Provider

/** How an account is doing. */
enum class AccountState {
    Ok,

    /** The server no longer takes what the account signs in with: the user has to sign in again. */
    NeedsLogin,

    /** The server could not be reached the last time (no network, a server that is down). */
    Unreachable,

    /** The last sync failed for another reason: the server said no, or its certificate was not accepted. */
    Error,
}

/** What the search for an address's servers came to. */
sealed interface SetupPlan {
    /**
     * The servers are known; what is missing is the password.
     *
     * @param provider the provider the app knows by heart, when it is one of those.
     */
    data class Found(val settings: MailSettings, val provider: Provider? = null) : SetupPlan

    /** A provider whose way of signing in this version does not have yet (Microsoft's, until the next pre-release). */
    data class NotYet(val provider: Provider) : SetupPlan

    /** Nobody knows the domain's servers: they are entered by hand. */
    data object Manual : SetupPlan

    /** The address is already one of the accounts. */
    data object Duplicate : SetupPlan

    /** What was typed is no e-mail address. */
    data object NotAnAddress : SetupPlan
}

/** An account about to be added: the address, where its servers are, and the password to sign in with. */
class AccountDraft(val address: String, val settings: MailSettings, val provider: Provider?, val password: String)

/** What became of adding an account, or of signing in to one again. */
sealed interface SetupResult {
    data class Done(val accountId: Long) : SetupResult

    /** The server was asked and it did not work; [kind] says what to do about it, [detail] is the server's own word. */
    data class Failed(val kind: MailException.Kind, val detail: String) : SetupResult

    data object Duplicate : SetupResult
}

/**
 * An account as a settings file carries it to another device: what it is and how it is set up,
 * never what it signs in with. [provider] is the provider table's id, empty for servers set up by
 * hand; [syncDays] and [notifyMode] are what the account keeps and tells of.
 */
data class PortableAccount(
    val address: String,
    val name: String,
    val senderName: String,
    val provider: String,
    val settings: MailSettings,
    val syncDays: Int,
    val notifyMode: String,
    val signature: String,
)

/** What Settings can do with the accounts. Every call waits for the server where it has to ask it. */
interface AccountActions {
    /** Works out where the servers of [address] are; only its domain is ever sent anywhere. */
    suspend fun find(address: String): SetupPlan

    /** Signs in with [draft] and keeps the account when the server takes it; its mail then starts to arrive. */
    suspend fun add(draft: AccountDraft): SetupResult

    /** Signs in to an account again with a new password ("Reconnect"), and keeps it when the server takes it. */
    suspend fun reconnect(accountId: Long, password: String): SetupResult

    /** Keeps the name the account's mail is sent under, and what its messages are signed with. */
    suspend fun saveWriting(accountId: Long, senderName: String, signature: String)

    /** Takes the account off the device with everything kept of its mail. The mail stays on its server. */
    suspend fun remove(accountId: Long)

    /** Every account as a settings file carries it to another device. */
    suspend fun portable(): List<PortableAccount>

    /**
     * Adds the accounts of a settings file that the device does not have yet, by their addresses;
     * each waits for its password ("Reconnect"). Those it has are left as they are. How many were added.
     */
    suspend fun adopt(accounts: List<PortableAccount>): Int
}
