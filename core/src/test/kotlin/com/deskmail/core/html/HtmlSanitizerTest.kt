package com.deskmail.core.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlSanitizerTest {

    private fun clean(html: String) = HtmlSanitizer.clean(html).html

    @Test
    fun whatRunsOrEmbedsIsTakenOut() {
        val cleaned = clean(
            """
            <html><head><script>steal()</script><meta http-equiv="refresh" content="0;url=https://evil.example"><base href="https://evil.example/">
            <link rel="stylesheet" href="https://evil.example/a.css"><title>t</title></head>
            <body><p>Merhaba</p><iframe src="https://evil.example"></iframe><object data="x.swf"></object><embed src="x">
            <svg onload="steal()"><circle/></svg><video src="https://evil.example/v.mp4"></video>
            <noscript><img src="https://evil.example/pixel.gif"></noscript></body></html>
            """.trimIndent(),
        )
        assertTrue(cleaned.contains("<p>Merhaba</p>"))
        for (gone in listOf("script", "steal", "iframe", "object", "embed", "svg", "video", "meta", "base", "link", "evil.example", "<title")) {
            assertFalse("$gone is still there: $cleaned", cleaned.contains(gone))
        }
    }

    @Test
    fun aFormLosesItsFieldsAndKeepsItsWords() {
        val cleaned = clean("""<form action="https://phish.example/login" method="post"><p>Şifrenizi girin:</p><input type="password" name="p"><button>Gönder</button></form>""")
        assertTrue(cleaned.contains("Şifrenizi girin:"))
        assertFalse(cleaned.contains("<form"))
        assertFalse(cleaned.contains("<input"))
        assertFalse(cleaned.contains("<button"))
        assertFalse(cleaned.contains("phish.example"))
    }

    @Test
    fun handlersAndAddressesThatAreNotAddressesGo() {
        val cleaned = clean(
            """
            <a href="javascript:steal()" onclick="steal()">bir</a>
            <a href="https://example.org/a?b=1" OnMouseOver="x()">iki</a>
            <a href="mailto:ayse@example.org">üç</a>
            <a href="vbscript:x">dört</a>
            <img src="data:text/html;base64,PHNjcmlwdD4=">
            <img src="data:image/png;base64,iVBORw0KGgo=" srcset="https://evil.example/2x.png 2x">
            <a href="/relative">beş</a>
            """.trimIndent(),
        )
        assertFalse(cleaned.contains("javascript"))
        assertFalse(cleaned.contains("vbscript"))
        assertFalse(cleaned.lowercase().contains("onclick"))
        assertFalse(cleaned.lowercase().contains("onmouseover"))
        assertFalse(cleaned.contains("data:text/html"))
        assertFalse(cleaned.contains("srcset"))
        assertTrue(cleaned.contains("""href="https://example.org/a?b=1""""))
        assertTrue(cleaned.contains("""href="mailto:ayse@example.org""""))
        assertTrue(cleaned.contains("data:image/png;base64,iVBORw0KGgo="))
        assertTrue(cleaned.contains("""href="/relative""""))
        // the links' words are all still there
        for (word in listOf("bir", "iki", "üç", "dört", "beş")) assertTrue(cleaned.contains(word))
    }

    @Test
    fun remotePicturesAreNoticedAndPicturesInsideTheMessageAreNot() {
        assertTrue(HtmlSanitizer.clean("""<p>a</p><img src="https://example.org/logo.png">""").remoteImages)
        assertTrue(HtmlSanitizer.clean("""<img src="//example.org/logo.png">""").remoteImages)
        assertTrue(HtmlSanitizer.clean("""<table background="http://example.org/bg.gif"><tr><td>a</td></tr></table>""").remoteImages)
        assertTrue(HtmlSanitizer.clean("""<div style="background: url('https://example.org/bg.png')">a</div>""").remoteImages)
        assertTrue(HtmlSanitizer.clean("""<style>.a{background:url(https://example.org/bg.png)}</style><p class="a">a</p>""").remoteImages)

        assertFalse(HtmlSanitizer.clean("""<img src="cid:logo@example.org"><img src="data:image/gif;base64,R0lGOD==">""").remoteImages)
        // a link is somewhere to go, not something that loads
        assertFalse(HtmlSanitizer.clean("""<a href="https://example.org">site</a>""").remoteImages)
        assertFalse(HtmlSanitizer.clean("""<p style="color:red">a</p>""").remoteImages)
    }

    @Test
    fun theMessagesOwnStylesStayWithoutImports() {
        val cleaned = clean(
            """
            <html><head><style>@import url("https://evil.example/a.css"); .kutu { color: #333; padding: 8px }</style></head>
            <body bgcolor="#ffffff" text="#222222" style="margin:0"><div class="kutu" style="font-weight:bold">a</div></body></html>
            """.trimIndent(),
        )
        assertTrue(cleaned.startsWith("<style>"))
        assertTrue(cleaned.contains(".kutu { color: #333; padding: 8px }"))
        assertFalse(cleaned.contains("@import"))
        assertFalse(cleaned.contains("evil.example"))
        assertTrue(cleaned.contains("""<div class="kutu" style="font-weight:bold">a</div>"""))
        // what the body said of itself is on a div around the content
        assertTrue(cleaned.contains("""<div style="margin:0;background-color:#ffffff;color:#222222">"""))
    }

    @Test
    fun aStyleSheetCannotCloseItsOwnTag() {
        val cleaned = clean("<style>p{color:red}</style><p>a</p>")
        assertEquals("<style>p{color:red}</style><p>a</p>", cleaned)
    }

    @Test
    fun theTextOfAPageReadsAsAPersonWouldReadIt() {
        val text = HtmlSanitizer.text(
            """
            <html><head><style>p{color:red}</style><title>Bülten</title></head><body>
            <h1>Kasım   bülteni</h1>
            <p>Merhaba&nbsp;Deniz,<br>bu ay raflara <b>140</b> kitap girdi.</p>
            <ul><li>Şiir</li><li>Roman</li></ul>
            <table><tr><td>Tutar</td><td>893,52 TL</td></tr></table>
            <script>x()</script>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(
            """
            Kasım bülteni

            Merhaba Deniz,
            bu ay raflara 140 kitap girdi.

            • Şiir
            • Roman

            Tutar 893,52 TL
            """.trimIndent(),
            text,
        )
        // divs and line breaks are lines; what is inline stays on its line
        assertEquals("bir iki\nüç\n\ndört", HtmlSanitizer.text("<div>bir <span>iki</span></div><div>üç<br><br>dört</div>"))
        assertEquals("", HtmlSanitizer.text("<p> </p>"))
    }
}
