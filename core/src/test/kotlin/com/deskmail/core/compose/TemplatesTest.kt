package com.deskmail.core.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplatesTest {

    private val text = "Toplantı perşembe 14.00'te.\nYerimiz: 3. kat."

    @Test
    fun aNewMessageBeginsWithTheText() {
        assertEquals(text, Templates.insert("", text))
        // with the signature under it
        assertEquals("$text\n\n-- \nDeniz\n", Templates.insert("\n\n-- \nDeniz\n", text))
    }

    @Test
    fun theTextFollowsWhatTheUserWrote() {
        assertEquals("Selam Elif,\n\n$text\n\n-- \nDeniz\n", Templates.insert("Selam Elif,\n\n\n-- \nDeniz\n", text))
        assertEquals("Selam Elif,\n\n$text", Templates.insert("Selam Elif,\n", text))
    }

    @Test
    fun aReplyKeepsItsQuoteUnderTheText() {
        val reply = "\n\n12 Kasım 2026 tarihinde Ayşe yazdı:\n> Cumartesi kahvaltı?\n>\n> Ayşe\n"
        assertEquals("$text\n\n12 Kasım 2026 tarihinde Ayşe yazdı:\n> Cumartesi kahvaltı?\n>\n> Ayşe\n", Templates.insert(reply, text))
        val written = "Olur.\n" + reply
        assertEquals("Olur.\n\n$text\n\n12 Kasım 2026 tarihinde Ayşe yazdı:\n> Cumartesi kahvaltı?\n>\n> Ayşe\n", Templates.insert(written, text))
    }

    @Test
    fun aForwardedMessageStaysUnderTheText() {
        val forward = "\n\n---------- İletilen ileti ----------\nKimden: Ayşe\nKonu: Plan\n\nMetin.\n"
        assertEquals("$text\n\n---------- İletilen ileti ----------\nKimden: Ayşe\nKonu: Plan\n\nMetin.\n", Templates.insert(forward, text))
    }

    @Test
    fun nothingToPutInLeavesTheMessageAsItIs() {
        assertEquals("Selam,\n", Templates.insert("Selam,\n", " \n"))
    }

    @Test
    fun theUsersOwnLinesWithAGreaterThanSignAreNotTakenForAQuote() {
        // a line that begins with ">" right at the start is the user's: there is no line before it
        assertEquals(">5 kişi\n\n$text", Templates.insert(">5 kişi", text))
    }
}
