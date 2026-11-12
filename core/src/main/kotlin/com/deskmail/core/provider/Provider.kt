package com.deskmail.core.provider

import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress

/** How an account proves who it is. */
enum class AuthKind {
    /** The account's own password. */
    Password,

    /** A password the provider makes for one mail program (Gmail, with two-step verification on). */
    AppPassword,

    /** Signing in at Microsoft in the browser; the app gets tokens, never the password. */
    MicrosoftOAuth,
}

/**
 * Where an account's mail is fetched and sent, and how it signs in.
 *
 * @param username what the servers know the account by; mostly the address itself.
 */
data class MailSettings(
    val imap: ServerAddress,
    val smtp: ServerAddress,
    val username: String,
    val auth: AuthKind,
)

/**
 * A mail provider the app knows: its servers, and the ways it differs from the others. Whatever
 * the app does differently for one provider is said here and nowhere else.
 *
 * @param id the name the app stores an account's provider under; never changes.
 * @param domains the address endings that are this provider's.
 * @param savesSentItself the provider files what is sent through its SMTP server under Sent by
 *   itself: the app must not add a second copy.
 * @param archiveLeavesAllMail archiving is taking a message out of the inbox; it stays in "All Mail"
 *   (Gmail). Elsewhere a message is archived by moving it to the Archive folder.
 */
data class Provider(
    val id: String,
    val name: String,
    val domains: Set<String>,
    val imap: ServerAddress,
    val smtp: ServerAddress,
    val auth: AuthKind,
    val savesSentItself: Boolean,
    val archiveLeavesAllMail: Boolean,
) {
    fun settingsFor(address: String) = MailSettings(imap, smtp, username = address, auth = auth)
}

/** The providers the app knows by heart. Everyone else is looked up ([Autoconfig]) or set up by hand. */
object ProviderTable {

    val Gmail = Provider(
        id = "gmail",
        name = "Gmail",
        domains = setOf("gmail.com", "googlemail.com"),
        imap = ServerAddress("imap.gmail.com", 993, Security.Tls),
        smtp = ServerAddress("smtp.gmail.com", 465, Security.Tls),
        auth = AuthKind.AppPassword,
        savesSentItself = true,
        archiveLeavesAllMail = true,
    )

    /**
     * Outlook.com and its older names. Microsoft takes no password from mail programs for these
     * accounts any more: sign-in is OAuth2. Whether its SMTP server files the sent message is
     * written down here as Microsoft documents it and checked against a real account's log.
     */
    val Outlook = Provider(
        id = "outlook",
        name = "Outlook",
        domains = setOf(
            "outlook.com", "outlook.com.tr", "hotmail.com", "hotmail.com.tr", "live.com", "msn.com", "windowslive.com",
            "outlook.de", "outlook.fr", "outlook.es", "outlook.it", "outlook.jp", "outlook.co.uk",
            "hotmail.co.uk", "hotmail.de", "hotmail.fr", "hotmail.es", "hotmail.it", "live.co.uk", "live.de", "live.fr", "live.nl", "live.it",
        ),
        imap = ServerAddress("outlook.office365.com", 993, Security.Tls),
        smtp = ServerAddress("smtp-mail.outlook.com", 587, Security.StartTls),
        auth = AuthKind.MicrosoftOAuth,
        savesSentItself = true,
        archiveLeavesAllMail = false,
    )

    val all: List<Provider> = listOf(Gmail, Outlook)

    fun byId(id: String): Provider? = all.firstOrNull { it.id == id }

    /** The provider an address belongs to, by what follows its "@". */
    fun forAddress(address: String): Provider? {
        val domain = domainOf(address) ?: return null
        return all.firstOrNull { domain in it.domains }
    }

    /** What follows the "@", lower case; null for something that is no address. */
    fun domainOf(address: String): String? {
        val at = address.lastIndexOf('@')
        if (at <= 0 || at == address.length - 1) return null
        val domain = address.substring(at + 1).trim().lowercase()
        return domain.takeIf { it.contains('.') && !it.contains(' ') }
    }
}
