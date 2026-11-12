package com.deskmail.core.imap

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

class MessagePartsTest {

    /** One of the sample messages (src/test/resources/mail), read as it travels. */
    private fun sample(name: String): MimeMessage =
        javaClass.getResourceAsStream("/mail/$name.eml").use { MimeMessage(Session.getInstance(Properties()), it) }

    @Test
    fun aFilesSizeIsWorkedOutFromHowItTravels() {
        // 440 bytes are 588 letters of base64 in eight lines: 602 bytes with their line breaks
        assertEquals(439, MessageParts.fileSize(602, "base64"))
        assertEquals(439, MessageParts.fileSize(602, " BASE64 "))
        // what travels as it is, is as large as it travels
        assertEquals(602, MessageParts.fileSize(602, "7bit"))
        assertEquals(602, MessageParts.fileSize(602, "quoted-printable"))
        assertEquals(602, MessageParts.fileSize(602, ""))
        assertEquals(0, MessageParts.fileSize(0, "base64"))
    }

    @Test
    fun aFileIsReadDecodedFromItsPlaceInTheMessage() {
        val places = MessageParts.read(sample("plan"), 100_000).attachments.associate { it.name to it.part }
        val pdf = MessageParts.stream(sample("plan"), places.getValue("Proje planı v3.pdf"))!!.use { it.readBytes() }
        assertEquals(440, pdf.size)
        assertTrue(String(pdf, Charsets.US_ASCII).startsWith("%PDF-1.4\n% a made-up file for the tests\n0123456789"))

        // the picture the HTML shows is a file like any other here
        val logo = MessageParts.stream(sample("plan"), "2")!!.use { it.readBytes() }
        assertEquals(listOf(0x89, 'P'.code, 'N'.code, 'G'.code), logo.take(4).map { it.toInt() and 0xff })
    }

    @Test
    fun aPlaceTheMessageDoesNotHaveIsNoFile() {
        // no such part, a part that only holds other parts, and what is no place at all
        assertNull(MessageParts.stream(sample("plan"), "7"))
        assertNull(MessageParts.stream(sample("plan"), "1"))
        assertNull(MessageParts.stream(sample("plan"), "3.1"))
        assertNull(MessageParts.stream(sample("plan"), "x"))
        assertNull(MessageParts.stream(sample("plan"), ""))
        // a message of one part has that part and no other
        assertNull(MessageParts.stream(sample("breakfast"), "2"))
    }

    /** A message that is nothing but its text: the text is its one part. */
    @Test
    fun aMessageOfOnePartIsThatPart() {
        val text = MessageParts.stream(sample("breakfast"), "1")!!.use { String(it.readBytes(), charset("ISO-8859-9")) }
        assertTrue(text, text.contains("kahvaltı"))
    }
}
