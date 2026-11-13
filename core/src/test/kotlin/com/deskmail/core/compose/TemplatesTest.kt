package com.deskmail.core.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplatesTest {

    private val text = "Toplantı perşembe 14.00'te.\nYerimiz: 3. kat."

    @Test
    fun aNewMessageBeginsWithTheText() {
        assertEquals(text to text.length, Templates.insertAt("", 0, 0, text))
        // the cursor where a new message puts it: before the signature
        assertEquals("$text\n\n-- \nDeniz\n" to text.length, Templates.insertAt("\n\n-- \nDeniz\n", 0, 0, text))
    }

    @Test
    fun aReplyKeepsItsQuoteUnderTheText() {
        val reply = "\n\n12 Kasım 2026 tarihinde Ayşe yazdı:\n> Cumartesi kahvaltı?\n>\n> Ayşe\n"
        assertEquals("$text$reply" to text.length, Templates.insertAt(reply, 0, 0, text))
    }

    @Test
    fun theTextGoesWhereTheCursorIsAsAParagraphOfItsOwn() {
        // after what was written, above the signature
        val written = "Olur.\n\n-- \nDeniz\n"
        assertEquals("Olur.\n\n$text\n\n-- \nDeniz\n" to "Olur.\n\n$text".length, Templates.insertAt(written, 5, 5, text))
        // in the middle of a line: the rest of it goes on under the text
        assertEquals("Olur,\n\n$text\n\ngörüşürüz." to "Olur,\n\n$text".length, Templates.insertAt("Olur,görüşürüz.", 5, 5, text))
        // the line breaks already there count
        assertEquals("Selam,\n\n$text" to "Selam,\n\n$text".length, Templates.insertAt("Selam,\n", 7, 7, text))
        assertEquals("Selam,\n\n$text" to "Selam,\n\n$text".length, Templates.insertAt("Selam,\n\n", 8, 8, text))
    }

    @Test
    fun aSelectionIsReplacedAsItIs() {
        assertEquals("Selam Elif, nasılsın?" to "Selam Elif".length, Templates.insertAt("Selam X, nasılsın?", 6, 7, "Elif"))
        // selected from its end
        assertEquals("Selam Elif, nasılsın?" to "Selam Elif".length, Templates.insertAt("Selam X, nasılsın?", 7, 6, "Elif"))
    }

    @Test
    fun nothingToPutInLeavesTheMessageAsItIs() {
        assertEquals("Selam,\n" to 3, Templates.insertAt("Selam,\n", 3, 3, " \n"))
    }

    @Test
    fun aCursorPastTheEndIsTakenAsTheEnd() {
        assertEquals("Selam,\n\n$text" to "Selam,\n\n$text".length, Templates.insertAt("Selam,", 40, 40, text))
    }

    @Test
    fun whatTheUserWroteEndsBeforeTheSignatureTheQuoteOrTheForwardedMessage() {
        assertEquals("Olur.\n\n".length, Templates.ownEnd("Olur.\n\n-- \nDeniz\n"))
        assertEquals("Olur.\n\n".length, Templates.ownEnd("Olur.\n\n12 Kasım 2026 tarihinde Ayşe yazdı:\n> Cumartesi kahvaltı?\n"))
        assertEquals("\n".length, Templates.ownEnd("\n---------- İletilen ileti ----------\nKimden: Ayşe\n"))
        // a line that begins with ">" right at the start is the user's: there is no line before it
        assertEquals(">5 kişi".length, Templates.ownEnd(">5 kişi"))
    }
}
