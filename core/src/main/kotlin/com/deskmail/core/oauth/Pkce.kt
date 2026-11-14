package com.deskmail.core.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Proof Key for Code Exchange (RFC 7636): the code the browser hands back is worth nothing without
 * the verifier that only the app knows. That is what keeps an app with no secret of its own safe
 * from another app that catches the code on its way.
 */
object Pkce {
    private val random = SecureRandom()

    /** A new verifier, or any value nobody can guess: 32 random bytes, 43 characters of base64url. */
    fun verifier(): String = base64Url(ByteArray(RANDOM_BYTES).also(random::nextBytes))

    /** What the request to sign in carries in the verifier's place: its SHA-256 (the method "S256"). */
    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private const val RANDOM_BYTES = 32
}
