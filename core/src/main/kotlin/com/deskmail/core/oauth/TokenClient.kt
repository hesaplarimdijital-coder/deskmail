package com.deskmail.core.oauth

import com.deskmail.core.mail.MailException

/**
 * What a token endpoint handed out. Neither token is ever printed.
 *
 * @param refreshToken what the next access token is asked for with; most renewals hand out a new
 *   one, which takes the old one's place. Null when the answer had none.
 * @param lifetimeSeconds how long the access token is good for, from the moment it was handed out.
 */
class Tokens(val accessToken: String, val refreshToken: String?, val lifetimeSeconds: Long) {
    override fun toString() = "Tokens(good for ${lifetimeSeconds}s" + (if (refreshToken != null) ", with a refresh token)" else ")")
}

/**
 * Trades a sign-in's code for tokens, and the refresh token for a new access token, at the
 * provider's token endpoint (RFC 6749, 4.1.3 and 6). It blocks while the provider is asked.
 *
 * Every failure is a [MailException] whose kind says what to do about it:
 * [MailException.Kind.Authentication] when the user has to sign in again (the refresh token
 * expired, or was taken back), [MailException.Kind.Network] when another try later is worth it,
 * [MailException.Kind.Server] when the provider will not deal with the app as it is registered.
 *
 * @param post sends a form and hands back the answer: [HttpForm.post], or the tests' own.
 */
class TokenClient(
    private val config: OAuthConfig,
    private val post: (url: String, fields: Map<String, String>) -> HttpForm.Answer = HttpForm::post,
) {
    /** The tokens for the [code] that came back from [request]. */
    fun redeem(code: String, request: AuthorizationRequest): Tokens = ask(
        linkedMapOf(
            "client_id" to config.clientId,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to config.redirectUri,
            "code_verifier" to request.verifier,
            "scope" to config.scope,
        ),
    )

    /** A new access token for [refreshToken], and mostly a new refresh token with it. */
    fun refresh(refreshToken: String): Tokens = ask(
        linkedMapOf(
            "client_id" to config.clientId,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "scope" to config.scope,
        ),
    )

    private fun ask(fields: Map<String, String>): Tokens {
        val answer = try {
            post(config.tokenUrl, fields)
        } catch (e: Exception) {
            // no network, no such host, a certificate that was not accepted
            throw MailException.from(e)
        }
        val json = try {
            Json.obj(answer.body)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (answer.status != HTTP_OK) throw refusal(answer.status, json)
        if (json == null) throw MailException(MailException.Kind.Server, "the token endpoint's answer could not be read")
        val access = json.string("access_token").orEmpty()
        val type = json.string("token_type")
        if (access.isEmpty() || (type != null && !type.equals("Bearer", ignoreCase = true))) {
            throw MailException(MailException.Kind.Server, "the token endpoint answered without a bearer token")
        }
        val lifetime = json.long("expires_in")?.takeIf { it > 0 } ?: DEFAULT_LIFETIME_S
        return Tokens(access, json.string("refresh_token")?.takeIf { it.isNotEmpty() }, lifetime)
    }

    companion object {
        private const val HTTP_OK = 200

        /** How long an access token is taken to be good for when the answer does not say: an hour, as the providers make them. */
        const val DEFAULT_LIFETIME_S = 3600L

        /** The errors (RFC 6749, 5.2; OpenID Connect) after which only the user can help, by signing in again. */
        private val SIGN_IN_AGAIN = setOf("invalid_grant", "interaction_required", "login_required", "consent_required")

        /**
         * The provider's no, as the app acts on it. Its words are kept for the log and the user, up
         * to where Microsoft's add their trace numbers.
         */
        internal fun refusal(status: Int, json: Map<String, Any?>?): MailException {
            val error = json?.string("error").orEmpty()
            val description = json?.string("error_description").orEmpty()
                .substringBefore("\r\nTrace ID:").substringBefore("\nTrace ID:").substringBefore(" Trace ID:")
                .replace(Regex("\\s+"), " ").trim()
            val words = listOf(error, description).filter { it.isNotEmpty() }.joinToString(": ").ifEmpty { "HTTP $status" }
            val kind = when {
                error in SIGN_IN_AGAIN -> MailException.Kind.Authentication
                error == "temporarily_unavailable" || status >= 500 || status == TOO_MANY -> MailException.Kind.Network
                else -> MailException.Kind.Server
            }
            return MailException(kind, words)
        }

        private const val TOO_MANY = 429
    }
}
