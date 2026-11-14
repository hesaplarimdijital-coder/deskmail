package com.deskmail.core.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class AuthorizationRequestTest {

    private val config = OAuthConfig(
        authorizeUrl = "https://login.example/consumers/oauth2/v2.0/authorize",
        tokenUrl = "https://login.example/consumers/oauth2/v2.0/token",
        clientId = "0f6e2c1a-1111-2222-3333-444455556666",
        redirectUri = "com.deskmail://oauth2redirect",
        scopes = listOf("https://outlook.office.com/IMAP.AccessAsUser.All", "https://outlook.office.com/SMTP.Send", "offline_access"),
    )

    private val request = AuthorizationRequest(config, loginHint = " deniz@outlook.com ", state = "k3-state", verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")

    /** The fields of a URL's query, decoded. */
    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { pair -> pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8) }

    @Test
    fun thePageAsksForACodeWithTheChallenge() {
        val url = request.url()
        assertTrue(url, url.startsWith("https://login.example/consumers/oauth2/v2.0/authorize?"))
        assertEquals(
            mapOf(
                "client_id" to "0f6e2c1a-1111-2222-3333-444455556666",
                "response_type" to "code",
                "redirect_uri" to "com.deskmail://oauth2redirect",
                "response_mode" to "query",
                "scope" to "https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send offline_access",
                "state" to "k3-state",
                "code_challenge" to "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                "code_challenge_method" to "S256",
                "login_hint" to "deniz@outlook.com",
            ),
            query(url),
        )
        // the spaces between the scopes as %20, the verifier nowhere
        assertTrue(url, "IMAP.AccessAsUser.All%20https" in url)
        assertTrue(url, "dBjftJeZ4CVP" !in url)
    }

    @Test
    fun withoutAHintThereIsNoHint() {
        val url = AuthorizationRequest(config, loginHint = "  ").url()
        assertTrue(url, "login_hint" !in url)
        // a state and a verifier of its own
        val fields = query(url)
        assertEquals(43, fields.getValue("state").length)
        assertEquals(43, fields.getValue("code_challenge").length)
    }

    @Test
    fun theAnswerCarriesTheCode() {
        val answer = request.answer("com.deskmail://oauth2redirect?code=M.C507_BAY.2.U.6e3a%2Bb-1&state=k3-state")
        assertEquals("M.C507_BAY.2.U.6e3a+b-1", (answer as AuthorizationAnswer.Code).code)
        // the code is not printed
        assertEquals("Code", answer.toString())
    }

    @Test
    fun aRefusalSaysWhy() {
        assertEquals(
            AuthorizationAnswer.Refused("access_denied", "The user has denied access to the scope requested by the client application."),
            request.answer(
                "com.deskmail://oauth2redirect?error=access_denied&error_description=The+user+has+denied+access+to+the+scope+requested+by+the+client+application.&state=k3-state",
            ),
        )
        // neither a code nor an error
        assertEquals(AuthorizationAnswer.Refused("no_code", ""), request.answer("com.deskmail://oauth2redirect?state=k3-state&code="))
    }

    @Test
    fun anAnswerToAnotherRequestIsNoneOfItsBusiness() {
        // another state, or none
        assertNull(request.answer("com.deskmail://oauth2redirect?code=abc&state=other"))
        assertNull(request.answer("com.deskmail://oauth2redirect?code=abc"))
        // another address
        assertNull(request.answer("com.deskmail://elsewhere?code=abc&state=k3-state"))
        assertNull(request.answer("https://oauth2redirect?code=abc&state=k3-state"))
        assertNull(request.answer("com.deskmail://oauth2redirect/more?code=abc&state=k3-state"))
        assertNull(request.answer("not a uri at all ::"))
        assertNull(request.answer(""))
        // the scheme and the host as Android may hand them over, in other letters
        assertEquals("abc", (request.answer("COM.DESKMAIL://OAuth2Redirect?code=abc&state=k3-state") as AuthorizationAnswer.Code).code)
    }

    @Test
    fun anAndroidRedirectWithTheSignatureHashInItsPath() {
        // Microsoft's form for Android apps: the package and the signing certificate's hash, URL-encoded
        val android = AuthorizationRequest(config.copy(redirectUri = "msauth://com.deskmail/6S4i%2BiP1XZO3GIRA49nml7NnavU%3D"), state = "s1")
        assertEquals("c1", (android.answer("msauth://com.deskmail/6S4i%2BiP1XZO3GIRA49nml7NnavU%3D?code=c1&state=s1") as AuthorizationAnswer.Code).code)
        assertNull(android.answer("msauth://com.deskmail/AAAA?code=c1&state=s1"))
        assertNull(android.answer("msauth://com.other/6S4i%2BiP1XZO3GIRA49nml7NnavU%3D?code=c1&state=s1"))
    }
}
