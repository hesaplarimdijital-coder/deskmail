package com.deskmail.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRedactorTest {

    private fun redact(text: String) = LogRedactor.redact(text)

    @Test
    fun imapLoginKeepsTheUserAndDropsThePassword() {
        assertEquals("C: a1 LOGIN deniz@gmail.example ***", redact("C: a1 LOGIN deniz@gmail.example abcd-efgh-ijkl-mnop"))
        assertEquals("""C: a1 LOGIN "deniz@gmail.example" ***""", redact("""C: a1 LOGIN "deniz@gmail.example" "pass word \" with quote""""))
    }

    @Test
    fun authenticateAndAuthDropTheirResponse() {
        assertEquals("C: a2 AUTHENTICATE XOAUTH2 ***", redact("C: a2 AUTHENTICATE XOAUTH2 dXNlcj1kZW5pekBvdXRsb29rLmV4YW1wbGUBYXV0aD1CZWFyZXIgRXdBQQEB"))
        assertEquals("C: AUTH PLAIN ***", redact("C: AUTH PLAIN AGRlbml6AHNlY3JldA=="))
        // the mechanism alone has nothing to hide
        assertEquals("C: AUTH LOGIN", redact("C: AUTH LOGIN"))
        // nor has a server listing its mechanisms
        assertEquals("S: 250-AUTH LOGIN XOAUTH2", redact("S: 250-AUTH LOGIN XOAUTH2"))
        assertEquals("S: 250 AUTH PLAIN LOGIN XOAUTH2", redact("S: 250 AUTH PLAIN LOGIN XOAUTH2"))
        assertEquals("S: * CAPABILITY IMAP4rev1 AUTH=PLAIN AUTH=XOAUTH2 IDLE", redact("S: * CAPABILITY IMAP4rev1 AUTH=PLAIN AUTH=XOAUTH2 IDLE"))
    }

    @Test
    fun httpAuthorizationAndDecodedXoauth2() {
        assertEquals("Authorization: Bearer ***", redact("Authorization: Bearer EwAoA8l6BAAU"))
        assertEquals("authorization: Basic ***", redact("authorization: Basic ZGVuaXo6c2VjcmV0"))
        assertEquals("user=deniz@outlook.example\u0001auth=Bearer ***\u0001\u0001", redact("user=deniz@outlook.example\u0001auth=Bearer EwAoA8l6BAAU\u0001\u0001"))
    }

    @Test
    fun oauthFieldsInUrlsAndForms() {
        assertEquals(
            "com.deskmail://oauth2redirect?code=***&state=k3",
            redact("com.deskmail://oauth2redirect?code=M.C507_BAY.2.U.abc-123&state=k3"),
        )
        assertEquals(
            "grant_type=refresh_token&refresh_token=***&client_id=1b2c",
            redact("grant_type=refresh_token&refresh_token=M.C5_BL2.0.U.-Cg!x*y&client_id=1b2c"),
        )
        assertEquals("code_verifier=***&code=***", redact("code_verifier=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk&code=abc"))
        assertEquals("password=*** user=deniz", redact("password=hunter2 user=deniz"))
    }

    @Test
    fun oauthFieldsInJson() {
        assertEquals(
            """{"token_type":"Bearer","expires_in":3600,"access_token":"***","refresh_token":"***"}""",
            redact("""{"token_type":"Bearer","expires_in":3600,"access_token":"EwAoA8l6BAAU","refresh_token":"M.C5_BL2.0.U.-Cg"}"""),
        )
        assertEquals("""{"password" : "***"}""", redact("""{"password" : "a \"quoted\" one"}"""))
    }

    @Test
    fun aJsonWebTokenAnywhere() {
        val jwt = "eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        assertEquals("id token ***, as received", redact("id token $jwt, as received"))
    }

    @Test
    fun ordinaryLinesStayAsTheyAre() {
        val lines = listOf(
            "S: * 12 EXISTS",
            "S: a3 OK [READ-WRITE] SELECT completed",
            "sync: Inbox: 3 new, UIDNEXT 4512, UIDVALIDITY 1699000001",
            "S: 535 5.7.139 Authentication unsuccessful, SmtpClientAuthentication is disabled for the Mailbox.",
            "Subject: Your password is ready, the code is in the post",
            "error_code=70000 error=invalid_grant",
            "login to outlook.office365.com:993 as deniz@outlook.example",
            "auth failed for deniz@outlook.example: the server refused the token",
            "token endpoint answered 400",
        )
        lines.forEach { assertEquals(it, redact(it)) }
    }

    @Test
    fun nothingOfASecretSurvives() {
        val secret = "S3cr3t-Value_1234567890"
        val lines = listOf(
            "a1 LOGIN user $secret",
            "AUTHENTICATE PLAIN $secret",
            "Authorization: Bearer $secret",
            "access_token=$secret",
            "{\"refresh_token\": \"$secret\"}",
            "client_secret=$secret&x=1",
        )
        lines.forEach { assertFalse(it, redact(it).contains(secret)) }
    }
}
