package com.deskmail.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagePartsTest {

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
}
