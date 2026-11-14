package com.deskmail.core.provider

import com.deskmail.core.oauth.OAuthConfig

/**
 * How Outlook.com accounts sign in: at Microsoft, in the browser, with OAuth 2.0 and PKCE. The app
 * is known there by DeskMail's own registration (Microsoft Entra ID, personal accounts only;
 * docs/PLAN.md, "Azure kaydı"), never by another mail program's.
 *
 * The registration's id is not a secret, and there is none beside it: an app on the user's own
 * device could keep nothing from him, and Microsoft deals with it as such (a public client).
 */
object MicrosoftSignIn {
    /** The registration's "Application (client) ID". Empty while there is none: Outlook then cannot be added. */
    private const val CLIENT_ID = ""

    /** Where the browser comes back to the app; registered at Microsoft under "Mobile and desktop applications". */
    const val REDIRECT_URI = "com.deskmail://oauth2redirect"

    /** Personal accounts ("consumers"): work and school accounts would need a registration Microsoft vets for every organisation. */
    private const val ENDPOINTS = "https://login.microsoftonline.com/consumers/oauth2/v2.0"

    /** Reading and changing the mail over IMAP, sending it over SMTP, and staying signed in (a refresh token). */
    val SCOPES = listOf(
        "https://outlook.office.com/IMAP.AccessAsUser.All",
        "https://outlook.office.com/SMTP.Send",
        "offline_access",
    )

    /** What the sign-in goes by; null while the app has no registration. */
    val config: OAuthConfig? = configFor(CLIENT_ID)

    internal fun configFor(clientId: String): OAuthConfig? =
        clientId.trim().takeIf { it.isNotEmpty() }?.let { OAuthConfig("$ENDPOINTS/authorize", "$ENDPOINTS/token", it, REDIRECT_URI, SCOPES) }
}

/**
 * The sign-in in the browser that accounts of [auth] go through; null for those with a password,
 * and for a way the app has no registration for (yet).
 */
fun browserSignIn(auth: AuthKind): OAuthConfig? = when (auth) {
    AuthKind.MicrosoftOAuth -> MicrosoftSignIn.config
    AuthKind.Password, AuthKind.AppPassword -> null
}
