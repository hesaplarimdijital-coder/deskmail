package com.deskmail.core.oauth

/**
 * Where a provider's OAuth 2.0 sign-in happens (RFC 6749), and what the app is known there by. A
 * public client's, with no secret: an app on the user's own device could keep none from him. PKCE
 * ([Pkce]) does what a secret would.
 *
 * @param authorizeUrl the page the browser opens to sign in.
 * @param tokenUrl where a sign-in's code and later the refresh token are traded for tokens.
 * @param clientId the app's registration at the provider.
 * @param redirectUri where the browser comes back to the app with the answer; registered with the provider.
 * @param scopes what the app asks to be allowed to do.
 */
data class OAuthConfig(
    val authorizeUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String>,
) {
    /** The scopes as the requests carry them. */
    val scope: String get() = scopes.joinToString(" ")
}
