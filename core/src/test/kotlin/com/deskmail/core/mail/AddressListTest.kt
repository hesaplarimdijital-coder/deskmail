package com.deskmail.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressListTest {

    @Test
    fun addressesAreReadAsTheyAreTyped() {
        val parsed = AddressList.parse("Ayşe Yılmaz <ayse@example.org>, deniz@example.org; \"Şahin, Elif\" <elif@isyeri.example>\nmehmet@example.org")
        assertEquals(
            listOf(
                MailAddress("Ayşe Yılmaz", "ayse@example.org"),
                MailAddress("", "deniz@example.org"),
                MailAddress("Şahin, Elif", "elif@isyeri.example"),
                MailAddress("", "mehmet@example.org"),
            ),
            parsed.addresses,
        )
        assertTrue(parsed.isValid)
    }

    @Test
    fun whatIsNoAddressIsSaidToBeNone() {
        val parsed = AddressList.parse("deniz@example.org, ayse, elif@, <@example.org>, Zeynep <zeynep at example.org>, a b@example.org")
        assertEquals(listOf(MailAddress("", "deniz@example.org")), parsed.addresses)
        assertEquals(listOf("ayse", "elif@", "<@example.org>", "Zeynep <zeynep at example.org>", "a b@example.org"), parsed.invalid)
        assertFalse(parsed.isValid)
    }

    @Test
    fun nothingTypedIsNobody() {
        assertEquals(AddressList.Parsed(emptyList(), emptyList()), AddressList.parse("  , ;\n "))
        assertEquals(AddressList.Parsed(emptyList(), emptyList()), AddressList.parse(""))
    }

    @Test
    fun anAddressTypedTwiceCountsOnce() {
        val parsed = AddressList.parse("Deniz <deniz@example.org>, DENIZ@example.org, deniz@example.org")
        assertEquals(listOf(MailAddress("Deniz", "deniz@example.org")), parsed.addresses)
    }

    @Test
    fun addressesAreWrittenBackSoThatTheyReadTheSame() {
        val addresses = listOf(
            MailAddress("Ayşe Yılmaz", "ayse@example.org"),
            MailAddress("", "deniz@example.org"),
            MailAddress("Şahin, Elif", "elif@isyeri.example"),
            MailAddress("Dr. \"Mehmet\" Demir", "mehmet@example.org"),
            MailAddress("can@example.org", "can@example.org"),
        )
        val written = AddressList.format(addresses)
        assertEquals(
            "Ayşe Yılmaz <ayse@example.org>, deniz@example.org, \"Şahin, Elif\" <elif@isyeri.example>, " +
                "\"Dr. \\\"Mehmet\\\" Demir\" <mehmet@example.org>, can@example.org",
            written,
        )
        // a name that is only the address again is no name
        assertEquals(addresses.dropLast(1) + MailAddress("", "can@example.org"), AddressList.parse(written).addresses)
    }

    @Test
    fun anAddressNeedsAnAtAndADomainWithADot() {
        assertTrue(AddressList.isAddress("deniz@example.org"))
        assertTrue(AddressList.isAddress("deniz.aksoy+posta@alt.example.com.tr"))
        assertFalse(AddressList.isAddress("deniz@localhost"))
        assertFalse(AddressList.isAddress("deniz@example."))
        assertFalse(AddressList.isAddress("deniz@@example.org"))
        assertFalse(AddressList.isAddress("deniz @example.org"))
        assertFalse(AddressList.isAddress(""))
    }
}
