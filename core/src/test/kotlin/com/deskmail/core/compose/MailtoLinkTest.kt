package com.deskmail.core.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailtoLinkTest {

    @Test
    fun anAddressAloneIsWhoItGoesTo() {
        assertEquals(MailtoLink(to = "ayse@example.org"), MailtoLink.parse("mailto:ayse@example.org"))
        assertEquals(MailtoLink(to = "ayse@example.org"), MailtoLink.parse("  MAILTO:ayse%40example.org "))
        // nobody yet: a message with nothing in it
        assertEquals(MailtoLink(), MailtoLink.parse("mailto:"))
    }

    @Test
    fun whatIsNoMailtoLinkIsNotRead() {
        assertNull(MailtoLink.parse("https://example.org/?subject=x"))
        assertNull(MailtoLink.parse("ayse@example.org"))
        assertNull(MailtoLink.parse(""))
    }

    @Test
    fun recipientsSubjectAndTextAreTakenAsTheyWereEncoded() {
        val link = MailtoLink.parse(
            "mailto:ayse@example.org,elif@example.org?cc=mehmet@example.org&bcc=arsiv@example.org" +
                "&subject=Cumartesi%20kahvalt%C4%B1%3F&body=Selam%2C%0D%0A%0D%0AOlur%20mu%3F",
        )!!

        assertEquals("ayse@example.org, elif@example.org", link.to)
        assertEquals("mehmet@example.org", link.cc)
        assertEquals("arsiv@example.org", link.bcc)
        assertEquals("Cumartesi kahvaltı?", link.subject)
        assertEquals("Selam,\n\nOlur mu?", link.body)
    }

    @Test
    fun recipientsNamedAsHeadersJoinTheOthers() {
        val link = MailtoLink.parse("mailto:ayse@example.org?to=elif@example.org&TO=deniz%40example.org&Cc=a@example.org&cc=b@example.org")!!

        assertEquals("ayse@example.org, elif@example.org, deniz@example.org", link.to)
        assertEquals("a@example.org, b@example.org", link.cc)
    }

    /** RFC 6068: a plus sign is a plus sign, and what was encoded is not taken for a separator. */
    @Test
    fun aPlusIsAPlusAndAnEncodedSeparatorIsText() {
        val link = MailtoLink.parse("mailto:deniz+liste@example.org?subject=1+1%3D2%20%26%20daha&body=a%26b=c")!!

        assertEquals("deniz+liste@example.org", link.to)
        assertEquals("1+1=2 & daha", link.subject)
        assertEquals("a&b=c", link.body)
    }

    @Test
    fun lettersThatWereNotEncodedAreKept() {
        val link = MailtoLink.parse("mailto:ayşe@örnek.example?subject=Şubat özeti 📎")!!

        assertEquals("ayşe@örnek.example", link.to)
        assertEquals("Şubat özeti 📎", link.subject)
        assertEquals("Fatura #12", MailtoLink.parse("mailto:?subject=Fatura #12")!!.subject)
    }

    /** A link sets recipients, subject and text, and nothing the form does not show. */
    @Test
    fun otherHeadersAreLeftOut() {
        val link = MailtoLink.parse("mailto:ayse@example.org?from=patron@example.org&reply-to=x@example.org&x-priority=1&attach=/etc/passwd&subject=Merhaba")!!

        assertEquals(MailtoLink(to = "ayse@example.org", subject = "Merhaba"), link)
    }

    /** A line break smuggled into a header's value does not make a second header. */
    @Test
    fun aValueThatTriesToBreakItsLineStaysOneLine() {
        val link = MailtoLink.parse("mailto:ayse@example.org%0D%0ABcc:gizli@example.org?subject=Merhaba%0D%0ABcc%3A%20gizli%40example.org")!!

        assertEquals("ayse@example.org Bcc:gizli@example.org", link.to)
        assertEquals("Merhaba Bcc: gizli@example.org", link.subject)
        assertEquals("", link.bcc)
    }

    @Test
    fun whatIsBrokenIsReadAsFarAsItGoes() {
        val link = MailtoLink.parse("mailto:ayse@example.org?subject=%zz%4&body=100%&&=x&cc&#parça")!!

        assertEquals("ayse@example.org", link.to)
        assertEquals("%zz%4", link.subject)
        assertEquals("100%", link.body)
        assertEquals("", link.cc)
    }
}
