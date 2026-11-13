package com.deskmail.core.mail

import com.deskmail.core.mail.AuthResults.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun aMessageWithoutTheHeaderHasNoResults() {
        assertNull(AuthResults.read("Subject: Merhaba\nFrom: Ayşe <ayse@posta.example>"))
        assertNull(AuthResults.read(""))
        // a provider that checked nothing says so
        assertEquals(AuthResults.Results("mx.example", emptyList()), AuthResults.read("Authentication-Results: mx.example; none"))
    }
}
