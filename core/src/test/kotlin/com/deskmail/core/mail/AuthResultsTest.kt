package com.deskmail.core.mail

import com.deskmail.core.mail.AuthResults.Check
import com.deskmail.core.provider.ProviderTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthResultsTest {

    @Test
    fun gmailsHeaderIsRead() {
        val headers = """
            Delivered-To: deniz@gmail.com
            ARC-Authentication-Results: i=1; mx.google.com; dkim=fail header.i=@other.example
            Authentication-Results: mx.google.com;
                   dkim=pass header.i=@posta.example header.s=s1 header.b=AbC123;
                   spf=pass (google.com: domain of ayse@posta.example designates 203.0.113.5 as permitted sender) smtp.mailfrom=ayse@posta.example;
                   dmarc=pass (p=NONE sp=NONE dis=NONE) header.from=posta.example
            Received: by 2002:a05:6000:1 with SMTP id x; Thu, 12 Nov 2026 07:30:00 -0800
            Authentication-Results: mx.google.com; spf=fail smtp.mailfrom=liar@forged.example
            Subject: Cumartesi kahvaltı?
        """.trimIndent()

        val results = AuthResults.read(headers)!!

        assertEquals("mx.google.com", results.server)
        assertEquals(
            listOf(
                Check("dkim", "pass", "posta.example"),
                Check("spf", "pass", "ayse@posta.example"),
                Check("dmarc", "pass", "posta.example"),
            ),
            results.checks,
        )
    }

    /** Microsoft's header names no server, and runs its results together. */
    @Test
    fun microsoftsHeaderIsRead() {
        val headers = "Authentication-Results: spf=softfail (sender IP is 198.51.100.7)\r\n smtp.mailfrom=bulten.example; dkim=none (message not signed)\r\n header.d=none;dmarc=fail action=quarantine\r\n header.from=bank.example;compauth=fail reason=000\r\nSubject: Hesabınız"

        val results = AuthResults.read(headers)!!

        assertEquals("", results.server)
        assertEquals(
            listOf(
                Check("spf", "softfail", "bulten.example"),
                Check("dkim", "none", "none"),
                Check("dmarc", "fail", "bank.example"),
            ),
            results.checks,
        )
    }

    @Test
    fun aCommentMayHoldAnything() {
        val results = AuthResults.read("Authentication-Results: mx.example; dkim=pass (good; dkim=fail spf=fail) header.d=a.example")!!
        assertEquals(listOf(Check("dkim", "pass", "a.example")), results.checks)
    }

    /**
     * The topmost header is the provider's word only where it names the provider's server. Where the
     * provider added none, a header that came with the message is on top, and may say anything.
     */
    @Test
    fun onlyTheProvidersOwnHeaderIsItsWord() {
        val gmail = ProviderTable.Gmail.authServId
        val outlook = ProviderTable.Outlook.authServId
        val google = AuthResults.read("Authentication-Results: mx.google.com; dmarc=pass header.from=bank.example")!!
        val microsoft = AuthResults.read("Authentication-Results: spf=pass (sender IP is 203.0.113.5) smtp.mailfrom=bank.example; dmarc=pass header.from=bank.example")!!
        val forged = AuthResults.read("Authentication-Results: mx.google.com; spf=pass smtp.mailfrom=bank.example; dkim=pass header.d=bank.example; dmarc=pass header.from=bank.example")!!

        assertTrue(AuthResults.isProviders(google, gmail))
        assertTrue(AuthResults.isProviders(microsoft, outlook))
        // Gmail's name on an Outlook account's message, Microsoft's form on a Gmail account's: not theirs
        assertFalse(AuthResults.isProviders(forged, outlook))
        assertFalse(AuthResults.isProviders(microsoft, gmail))
        // a provider the app does not know: nobody's word can be told from anybody's
        assertFalse(AuthResults.isProviders(google, null))
        assertFalse(AuthResults.isProviders(microsoft, null))

        assertEquals(ProviderTable.Gmail, ProviderTable.forImapHost("imap.gmail.com"))
        assertEquals(ProviderTable.Outlook, ProviderTable.forImapHost("OUTLOOK.office365.com"))
        assertNull(ProviderTable.forImapHost("imap.posta.example"))
    }

    @Test
    fun aMessageWithoutTheHeaderHasNoResults() {
        assertNull(AuthResults.read("Subject: Merhaba\nFrom: Ayşe <ayse@posta.example>"))
        assertNull(AuthResults.read(""))
        // a provider that checked nothing says so
        assertEquals(AuthResults.Results("mx.example", emptyList()), AuthResults.read("Authentication-Results: mx.example; none"))
    }
}
