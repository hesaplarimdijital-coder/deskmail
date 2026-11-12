package com.deskmail.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TurkishFoldTest {

    @Test
    fun `Turkish letters fold to their plain neighbours`() {
        assertEquals("sarki", TurkishFold.fold("Şarkı"))
        assertEquals("cagri ozturk", TurkishFold.fold("Çağrı Öztürk"))
        assertEquals("igdir", TurkishFold.fold("Iğdır"))
    }

    @Test
    fun `both capital I's become i`() {
        assertEquals("istanbul", TurkishFold.fold("İSTANBUL"))
        assertEquals("isparta", TurkishFold.fold("ISPARTA"))
        assertEquals("isik", TurkishFold.fold("IŞIK"))
    }

    @Test
    fun `other accents are dropped and plain text only loses its case`() {
        assertEquals("cafe creme", TurkishFold.fold("Café Crème"))
        assertEquals("re: invoice 2026/11", TurkishFold.fold("Re: Invoice 2026/11"))
        assertEquals("", TurkishFold.fold(""))
    }

    @Test
    fun `a folded query matches folded text`() {
        val subject = TurkishFold.fold("Toplantı Notları: Şubat Bütçesi")
        assertEquals(true, subject.contains(TurkishFold.fold("BÜTÇE")))
        assertEquals(true, subject.contains(TurkishFold.fold("subat")))
    }
}
