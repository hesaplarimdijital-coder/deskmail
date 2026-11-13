package com.deskmail.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {

    @Test
    fun wordsAreFoldedAndLookedForFromTheirBeginning() {
        assertEquals("sarki*", SearchQuery.match("Şarkı"))
        assertEquals("kasim* faturasi*", SearchQuery.match("  Kasım   FATURASI "))
        assertEquals("isparta*", SearchQuery.match("ISPARTA"))
    }

    @Test
    fun whatIsNeitherLetterNorDigitSeparatesWords() {
        // as the index cuts an address
        assertEquals("ayse* posta* example*", SearchQuery.match("ayse@posta.example"))
        assertEquals("proje* plani* v3*", SearchQuery.match("Proje planı (v3)"))
        assertEquals("2026* 11*", SearchQuery.match("2026-11"))
    }

    @Test
    fun nothingOfWhatIsTypedIsSyntaxToTheIndex() {
        assertEquals("fatura* or* kasim*", SearchQuery.match("fatura OR kasım"))
        assertEquals("fatura* kasim*", SearchQuery.match("\"fatura\" -kasım*"))
        assertEquals("near* 2* subject* x*", SearchQuery.match("NEAR/2 subject:x"))
        assertEquals("a* b*", SearchQuery.match("(a) ^b"))
    }

    @Test
    fun aWordIsLookedForOnce() {
        assertEquals("fatura* kasim*", SearchQuery.match("fatura Fatura kasım FATURA"))
    }

    @Test
    fun tooLittleIsNotLookedFor() {
        assertNull(SearchQuery.match(""))
        assertNull(SearchQuery.match("   "))
        assertNull(SearchQuery.match("?!"))
        // one letter is in nearly every message
        assertNull(SearchQuery.match("a"))
        assertEquals("ab*", SearchQuery.match("ab"))
        // two of them narrow already
        assertEquals("a* b*", SearchQuery.match("a b"))
    }

    @Test
    fun aPastedParagraphIsCutShort() {
        val pasted = (1..30).joinToString(" ") { "sozcuk$it" }
        assertEquals(8, SearchQuery.words(pasted).size)
        assertEquals("sozcuk1*", SearchQuery.match(pasted)!!.substringBefore(' '))
    }

    @Test
    fun textIsMatchedAsTheIndexWouldMatchIt() {
        val text = "Kasım 2026 faturanız hazır: Elektrik Dağıtım <fatura@elektrik.example>"
        assertTrue(SearchQuery.matches(SearchQuery.words("fatura"), text))
        assertTrue(SearchQuery.matches(SearchQuery.words("ELEKTRİK dagi"), text))
        assertTrue(SearchQuery.matches(SearchQuery.words("kas fat 2026"), text))
        // every word must be there, and from its beginning
        assertFalse(SearchQuery.matches(SearchQuery.words("fatura aralık"), text))
        assertFalse(SearchQuery.matches(SearchQuery.words("atura"), text))
        assertFalse(SearchQuery.matches(emptyList(), text))
    }
}
