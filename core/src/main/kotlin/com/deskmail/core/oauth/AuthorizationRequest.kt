package com.deskmail.core.oauth

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * One sign-in in the browser (RFC 6749, 4.1; RFC 7636): the page to open, and how its answer is
 * told from any other. [state] ties the answer to this request: a page that comes back with
 * another one was not asked for here. [verifier] stays in the app until the code is traded for
 * tokens ([TokenClient.redeem]).
 *
 * @param loginHint the address the user signs in with, filled in on the provider's page.
 */
class AuthorizationRequest(
    val config: OAuthConfig,
    val loginHint: String? = null,
    val state: String = Pkce.verifier(),
    val verifier: String = Pkce.verifier(),
) {
    /** The page that asks the user to sign in and to allow what the app asks for. */
    fun url(): String {
        val fields = linkedMapOf(
            "client_id" to config.clientId,
            "response_type" to "code",
            "redirect_uri" to config.redirectUri,
            "response_mode" to "query",
            "scope" to config.scope,
            "state" to state,
            "code_challenge" to Pkce.challenge(verifier),
            "code_challenge_method" to "S256",
        )
        loginHint?.trim()?.takeIf { it.isNotEmpty() }?.let { fields["login_hint"] = it }
        val query = fields.entries.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        return "${config.authorizeUrl}?$query"
    }

    /**
     * What came back to the app at [redirect]: the code, or why there is none. Null when it is not
     * the answer to this request: it came to another address, or with another [state].
     */
    fun answer(redirect: String): AuthorizationAnswer? {
        val uri = parse(redirect) ?: return null
        val registered = parse(config.redirectUri) ?: return null
        if (!sameTarget(uri, registered)) return null
        val fields = fields(uri.rawQuery)
        if (fields["state"] != state) return null
        val code = fields["code"].orEmpty()
        if (code.isNotEmpty()) return AuthorizationAnswer.Code(code)
        return AuthorizationAnswer.Refused(fields["error"].orEmpty().ifEmpty { NO_CODE }, fields["error_description"].orEmpty())
    }

    override fun toString() = "AuthorizationRequest(${config.authorizeUrl})"

    private companion object {
        /** What a refusal is called that names no error of its own. */
        const val NO_CODE = "no_code"

        /** A query's value, with its spaces as %20: the form encoding's "+" is not everywhere taken for one. */
        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        fun parse(text: String): URI? = try {
            URI(text.trim())
        } catch (e: URISyntaxException) {
            null
        }

        /** The same scheme, the same host and the same path: the address the answer was asked to come to. */
        fun sameTarget(a: URI, b: URI): Boolean =
            a.scheme.equals(b.scheme, ignoreCase = true) &&
                a.authority.orEmpty().equals(b.authority.orEmpty(), ignoreCase = true) &&
                a.path.orEmpty().removeSuffix("/") == b.path.orEmpty().removeSuffix("/")

        /** The fields of a query, decoded; of a name that comes twice, the first. */
        fun fields(query: String?): Map<String, String> {
            if (query.isNullOrEmpty()) return emptyMap()
            val fields = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val name = decode(pair.substringBefore('='))
                val value = if ('=' in pair) decode(pair.substringAfter('=')) else ""
                fields.putIfAbsent(name, value)
            }
            return fields
        }

        fun decode(text: String): String = try {
            URLDecoder.decode(text, Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            text
        }
    }
}

/** What the browser brought back from a sign-in. */
sealed interface AuthorizationAnswer {
    /** The user signed in and allowed what was asked: the code to trade for tokens. Never printed. */
    class Code(val code: String) : AuthorizationAnswer {
        override fun toString() = "Code"
    }

    /**
     * No code: the user said no, or the provider would not let him sign in here.
     *
     * @param error OAuth's name for why ("access_denied" when the user said no).
     * @param description the provider's words for it; may be empty.
     */
    data class Refused(val error: String, val description: String) : AuthorizationAnswer
}
