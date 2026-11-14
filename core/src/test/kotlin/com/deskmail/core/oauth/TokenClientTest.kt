package com.deskmail.core.oauth

import com.deskmail.core.mail.MailException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class TokenClientTest {

    private val config = OAuthConfig(
        authorizeUrl = "https://login.example/consumers/oauth2/v2.0/authorize",
        tokenUrl = "https://login.example/consumers/oauth2/v2.0/token",
        clientId = "0f6e2c1a-1111-2222-3333-444455556666",
        redirectUri = "com.deskmail://oauth2redirect",
        scopes = listOf("https://outlook.office.com/IMAP.AccessAsUser.All", "offline_access"),
    )

    /** What was posted where; the answer is the test's. */
    private val posted = ArrayList<Pair<String, Map<String, String>>>()

    private fun client(status: Int, body: String) = TokenClient(config) { url, fields ->
        posted += url to fields
        HttpForm.Answer(status, body)
    }

    private fun failing(error: Exception) = TokenClient(config) { _, _ -> throw error }

    private val granted = """{"token_type":"Bearer","scope":"https://outlook.office.com/IMAP.AccessAsUser.All","expires_in":3599,"access_token":"EwBIA-access","refresh_token":"M.C540-refresh"}"""

    @Test
    fun theCodeIsTradedWithTheVerifier() {
        val request = AuthorizationRequest(config, state = "s", verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")

        val tokens = client(200, granted).redeem("M.C507-code", request)

        assertEquals("EwBIA-access", tokens.accessToken)
        assertEquals("M.C540-refresh", tokens.refreshToken)
        assertEquals(3599L, tokens.lifetimeSeconds)
        val (url, fields) = posted.single()
        assertEquals("https://login.example/consumers/oauth2/v2.0/token", url)
        assertEquals(
            mapOf(
                "client_id" to "0f6e2c1a-1111-2222-3333-444455556666",
                "grant_type" to "authorization_code",
                "code" to "M.C507-code",
                "redirect_uri" to "com.deskmail://oauth2redirect",
                "code_verifier" to "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
                "scope" to "https://outlook.office.com/IMAP.AccessAsUser.All offline_access",
            ),
            fields,
        )
        // a public client: no secret goes anywhere
        assertTrue(fields.keys.none { "secret" in it })
        // neither token is printed
        assertEquals("Tokens(good for 3599s, with a refresh token)", tokens.toString())
    }

    @Test
    fun theRefreshTokenBringsANewAccessToken() {
        val tokens = client(200, """{"token_type":"bearer","expires_in":"3600","access_token":"EwB-new"}""").refresh("M.C540-refresh")

        assertEquals("EwB-new", tokens.accessToken)
        // this answer had no new refresh token: the old one stays
        assertNull(tokens.refreshToken)
        assertEquals(3600L, tokens.lifetimeSeconds)
        assertEquals(
            mapOf(
                "client_id" to "0f6e2c1a-1111-2222-3333-444455556666",
                "grant_type" to "refresh_token",
                "refresh_token" to "M.C540-refresh",
                "scope" to "https://outlook.office.com/IMAP.AccessAsUser.All offline_access",
            ),
            posted.single().second,
        )
    }

    @Test
    fun anAnswerThatSaysNothingOfItsLifetimeIsTakenForAnHour() {
        assertEquals(3600L, client(200, """{"access_token":"a"}""").refresh("r").lifetimeSeconds)
        assertEquals(3600L, client(200, """{"access_token":"a","expires_in":0}""").refresh("r").lifetimeSeconds)
    }

    @Test
    fun aRefreshTokenThatIsNoLongerGoodMeansSigningInAgain() {
        val error = assertThrows(MailException::class.java) {
            client(
                400,
                """{"error":"invalid_grant","error_description":"AADSTS70000: The provided value for the 'refresh_token' is not valid.\r\nTrace ID: 1c8d\r\nCorrelation ID: 77\r\nTimestamp: 2026-11-14 09:00:00Z","error_codes":[70000]}""",
            ).refresh("M.C540-old")
        }
        assertEquals(MailException.Kind.Authentication, error.kind)
        // the provider's words, without its trace numbers
        assertEquals("invalid_grant: AADSTS70000: The provided value for the 'refresh_token' is not valid.", error.message)

        for (name in listOf("interaction_required", "login_required", "consent_required")) {
            assertEquals(name, MailException.Kind.Authentication, refusalOf(400, """{"error":"$name"}""").kind)
        }
    }

    @Test
    fun aProviderThatCannotAnswerNowIsAskedAgainLater() {
        assertEquals(MailException.Kind.Network, refusalOf(400, """{"error":"temporarily_unavailable"}""").kind)
        assertEquals(MailException.Kind.Network, refusalOf(503, "<html>Service Unavailable</html>").kind)
        assertEquals(MailException.Kind.Network, refusalOf(429, "").kind)
        assertEquals("HTTP 503", refusalOf(503, "<html>Service Unavailable</html>").message)
    }

    @Test
    fun aRegistrationTheProviderWillNotDealWithIsTheServersFault() {
        val error = refusalOf(401, """{"error":"invalid_client","error_description":"AADSTS700016: Application with identifier 'x' was not found. Trace ID: 9"}""")
        assertEquals(MailException.Kind.Server, error.kind)
        assertEquals("invalid_client: AADSTS700016: Application with identifier 'x' was not found.", error.message)
        assertEquals(MailException.Kind.Server, refusalOf(400, """{"error":"invalid_scope"}""").kind)
        assertEquals(MailException.Kind.Server, refusalOf(404, "").kind)
    }

    @Test
    fun anAnswerWithoutAToken() {
        for (body in listOf("", "<html></html>", """{"token_type":"Bearer"}""", """{"access_token":"","token_type":"Bearer"}""", """{"access_token":"a","token_type":"mac"}""")) {
            val error = assertThrows(body, MailException::class.java) { client(200, body).refresh("r") }
            assertEquals(body, MailException.Kind.Server, error.kind)
        }
    }

    @Test
    fun theNetworksFailuresAreItsOwn() {
        assertEquals(MailException.Kind.Network, assertThrows(MailException::class.java) { failing(UnknownHostException("login.example")).refresh("r") }.kind)
        assertEquals(MailException.Kind.Security, assertThrows(MailException::class.java) { failing(SSLHandshakeException("no")).refresh("r") }.kind)
    }

    private fun refusalOf(status: Int, body: String): MailException =
        assertThrows(MailException::class.java) { client(status, body).refresh("r") }
}
