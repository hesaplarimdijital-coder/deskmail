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
        assertEquals("near* 2* title* x*", SearchQuery.match("NEAR/2 title:x"))
        // a column of the index is chosen only by the app's own words, and only with the app's own names
        assertEquals("people* x*", SearchQuery.match("people:x"))
        assertEquals("a* b*", SearchQuery.match("(a) ^b"))
    }

    /** "kimden:", "kime:", "konu:" (and their English names) say where a piece is looked for. */
    @Test
    fun aPieceCanBeToldWhereToBeLookedFor() {
        assertEquals("people:ayse* fatura*", SearchQuery.match("Kimden:Ayşe fatura"))
        assertEquals("subject:kasim* subject:faturasi*", SearchQuery.match("konu:\"Kasım faturası\""))
        assertEquals("people:elif*", SearchQuery.match("KİME:elif"))
        assertEquals("people:ayse* people:posta* people:example*", SearchQuery.match("from:ayse@posta.example"))
        // with a space after the colon
        assertEquals("subject:fatura*", SearchQuery.match("konu: fatura"))
        // a prefix with nothing after it looks for nothing
        assertNull(SearchQuery.match("kimden:"))

        val terms = SearchQuery.terms("kimden: Ayşe konu:Kasım faturası")
        assertEquals(listOf(SearchField.From, SearchField.Subject, SearchField.Any), terms.map { it.field })
        // what a server is asked for is what was typed
        assertEquals(listOf("Ayşe", "Kasım", "faturası"), terms.map { it.typed })
        assertEquals(listOf(listOf("ayse"), listOf("kasim"), listOf("faturasi")), terms.map { it.words })
    }

    /** One name for every way of typing the same thing: what the server found for it is kept under it. */
    @Test
    fun whatWasTypedHasOneKey() {
        assertEquals(SearchQuery.key("Kimden:Ayşe  fatura"), SearchQuery.key("from:ayse FATURA"))
        assertEquals("from:ayse any:fatura", SearchQuery.key("Kimden:Ayşe fatura"))
        assertTrue(SearchQuery.key("fatura") != SearchQuery.key("konu:fatura"))
        assertNull(SearchQuery.key("a"))
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
