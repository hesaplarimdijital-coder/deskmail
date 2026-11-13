package com.deskmail.core.imap

import com.deskmail.core.mail.MailAddress
import com.deskmail.core.mail.MailException
import com.deskmail.core.mail.MessageFlags
import com.deskmail.core.text.SearchQuery
import jakarta.mail.Flags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate

/** Reading a folder (what it holds, who wrote what, the text and the files) from a real IMAP server inside the test. */
class ImapMailboxServerTest {

    @get:Rule
    val server = TestMailServer()

    private val monday: Instant = Instant.parse("2026-11-09T07:00:00Z")
    private val tuesday: Instant = Instant.parse("2026-11-10T05:00:00Z")
    private val thursday: Instant = Instant.parse("2026-11-12T06:05:00Z")

    private fun <T> inbox(block: (ImapMailbox) -> T): T =
        ImapConnection.open(server.imap, server.credentials).use { connection -> connection.open("INBOX").use(block) }

    /** The server is asked, and only the server: by its subject, its people, its text; every term must hold. */
    @Test
    fun theServerFindsMessagesByWhatTheySay() {
        val newsletter = server.deliver("newsletter", tuesday)
        val plan = server.deliver("plan", monday)
        val breakfast = server.deliver("breakfast", thursday)
        inbox { mailbox ->
            assertEquals(listOf(plan), mailbox.search(SearchQuery.terms("Proje")))
            assertEquals(listOf(newsletter), mailbox.search(SearchQuery.terms("kimden:sahaf")))
            assertEquals(listOf(plan), mailbox.search(SearchQuery.terms("from:mehmet konu:proje")))
            // everybody wrote to Deniz
            assertEquals(listOf(newsletter, plan, breakfast).sorted(), mailbox.search(SearchQuery.terms("kime:deniz")))
            assertTrue(mailbox.search(SearchQuery.terms("kimden:sahaf konu:proje")).isEmpty())
            assertTrue(mailbox.search(emptyList()).isEmpty())
        }
    }

    /** What is not plain ASCII reaches the server as UTF-8. */
    @Test
    fun theServerIsAskedInUtf8() {
        val newsletter = server.deliver("newsletter", tuesday)
        server.deliver("plan", monday)
        inbox { mailbox ->
            assertEquals(listOf(newsletter), mailbox.search(SearchQuery.terms("konu:Kasım")))
        }
    }

    @Test
    fun theFolderSaysWhatItHoldsAndWhichMessagesAreRecent() {
        val newsletter = server.deliver("newsletter", tuesday, flags = Flags(Flags.Flag.SEEN))
        val plan = server.deliver("plan", monday)
        val breakfast = server.deliver("breakfast", thursday)
        inbox { mailbox ->
            assertTrue(mailbox.uidValidity > 0)
            assertEquals(breakfast + 1, mailbox.uidNext)
            // this server keeps no change counter
            assertEquals(0, mailbox.highestModSeq)
            assertNull(mailbox.flagsChangedSince(1))
            assertEquals(3, mailbox.total)
            assertEquals(2, mailbox.unread)

            assertEquals(listOf(newsletter, plan, breakfast), mailbox.uids(null))
            assertEquals(listOf(newsletter, breakfast), mailbox.uids(LocalDate.of(2026, 11, 10)))
            assertEquals(listOf(breakfast), mailbox.uids(LocalDate.of(2026, 11, 11)))
            assertTrue(mailbox.uids(LocalDate.of(2026, 11, 13)).isEmpty())
        }
    }

    @Test
    fun aMessagesHeaderIsReadWithItsTurkishLetters() {
        val uid = server.deliver("breakfast", thursday)
        val header = inbox { it.headers(listOf(uid)) }.single()

        assertEquals(uid, header.uid)
        assertEquals("Cumartesi kahvaltı?", header.subject)
        assertEquals(listOf(MailAddress("Ayşe Yılmaz", "ayse@example.org")), header.from)
        assertEquals(listOf(MailAddress("Deniz Aksoy", "deniz@example.org"), MailAddress("Sahin, Elif", "elif@example.org")), header.to)
        assertEquals(listOf(MailAddress("Mehmet Demir", "mehmet@example.org")), header.cc)
        assertEquals("<kahvalti-1@example.org>", header.globalId)
        assertEquals("<onceki@example.org>", header.inReplyTo)
        assertEquals("<ilk@example.org> <onceki@example.org>", header.references)
        assertEquals(Instant.parse("2026-11-12T06:05:00Z"), header.sentAt)
        assertEquals(thursday, header.receivedAt)
        assertTrue(header.size > 0)
        assertEquals(MessageFlags(), header.flags)
        assertEquals(0, header.attachments)
    }

    @Test
    fun headersComeForTheUidsThatExist() {
        val plan = server.deliver("plan", monday, flags = Flags(Flags.Flag.FLAGGED))
        val breakfast = server.deliver("breakfast", thursday)
        val headers = inbox { it.headers(listOf(breakfast, 999, plan)) }.associateBy { it.uid }

        assertEquals(setOf(plan, breakfast), headers.keys)
        assertEquals("Re: Proje planı (v3)", headers.getValue(plan).subject)
        assertTrue(headers.getValue(plan).flags.flagged)
        // a PDF is a file; the picture the HTML shows is not
        assertEquals(1, headers.getValue(plan).attachments)
    }

    @Test
    fun flagsAreReadAsTheyAreNow() {
        val plan = server.deliver("plan", monday)
        val breakfast = server.deliver("breakfast", thursday)
        server.setFlag(plan, Flags.Flag.SEEN, true)
        server.setFlag(plan, Flags.Flag.ANSWERED, true)
        server.setFlag(breakfast, Flags.Flag.FLAGGED, true)

        val flags = inbox { it.flags(listOf(plan, breakfast, 999)) }.associate { it.uid to it.flags }

        assertEquals(mapOf(plan to MessageFlags(seen = true, answered = true), breakfast to MessageFlags(flagged = true)), flags)
    }

    /** A plain-text message in the Turkish Latin alphabet's old encoding, quoted-printable. */
    @Test
    fun plainTextIsDecodedFromItsCharset() {
        val uid = server.deliver("breakfast", thursday)
        val body = inbox { it.body(uid, 100_000) }!!

        assertEquals("Selam Deniz,\n\nCumartesi sabahı Moda'da kahvaltıya ne dersin? Zeynep de geliyor.\n\nAyşe", body.text!!.trim())
        assertNull(body.html)
        assertTrue(body.attachments.isEmpty())
        assertFalse(body.tooLarge)
    }

    /** multipart/mixed holding the two forms of the text, a picture the HTML shows, and a PDF. */
    @Test
    fun aMessageInPartsGivesItsTextItsHtmlAndItsFiles() {
        val uid = server.deliver("plan", monday)
        val body = inbox { it.body(uid, 100_000) }!!

        assertEquals("Deniz selam,\n\nPlanın üçüncü sürümü ekte.", body.text!!.trim())
        // as the sender wrote it: cleaning comes after
        assertTrue(body.html!!.contains("<b>üçüncü</b>"))
        assertTrue(body.html.contains("<script>"))

        assertEquals(2, body.attachments.size)
        val (logo, pdf) = body.attachments
        assertEquals("2", logo.part)
        assertEquals("image/png", logo.mimeType)
        assertEquals("logo@example.org", logo.contentId)
        assertTrue(logo.inline)
        assertEquals("3", pdf.part)
        assertEquals("Proje planı v3.pdf", pdf.name)
        assertEquals("application/pdf", pdf.mimeType)
        assertEquals("", pdf.contentId)
        assertFalse(pdf.inline)
        // The file is 440 bytes and travels as some 600 of base64. This test's server counts the
        // part's own header lines as well (a real one does not), so no closer than this:
        assertTrue("${pdf.size}", pdf.size in 400..650)
    }

    @Test
    fun aFileIsFetchedDecoded() {
        val uid = server.deliver("plan", monday)
        val pdf = ByteArrayOutputStream()
        assertTrue(inbox { it.attachment(uid, "3", pdf) })
        assertEquals(440, pdf.size())
        assertTrue(pdf.toString(Charsets.US_ASCII).startsWith("%PDF-1.4\n% a made-up file for the tests"))

        // a part the message does not have, and a message the folder does not have
        assertFalse(inbox { it.attachment(uid, "9", ByteArrayOutputStream()) })
        assertFalse(inbox { it.attachment(999, "3", ByteArrayOutputStream()) })
        // fetching a file is not reading the message
        assertEquals(MessageFlags(), inbox { it.flags(listOf(uid)) }.single().flags)
    }

    /** An HTML message whose header names no charset: the page's own declaration counts. */
    @Test
    fun anHtmlPageIsDecodedByTheCharsetItDeclares() {
        val uid = server.deliver("newsletter", tuesday)
        val body = inbox { it.body(uid, 100_000) }!!

        assertNull(body.text)
        assertTrue(body.html!!.contains("<h1>Kasım bülteni</h1>"))
        assertTrue(body.html.contains("şiir, öykü, çağdaş roman"))
    }

    @Test
    fun aTextLargerThanTheLimitStaysOnTheServer() {
        val uid = server.deliver("plan", monday)
        val body = inbox { it.body(uid, 20) }!!

        assertNull(body.text)
        assertNull(body.html)
        assertTrue(body.tooLarge)
        // what the message carries is known all the same
        assertEquals(2, body.attachments.size)
    }

    @Test
    fun aMessageThatIsGoneHasNoBody() {
        server.deliver("breakfast", thursday)
        assertNull(inbox { it.body(999, 100_000) })
    }

    /** Looking at a message is not reading it: the sync leaves the flags as they are. */
    @Test
    fun readingAMessageDoesNotMarkItRead() {
        val uid = server.deliver("plan", monday)
        inbox { mailbox ->
            mailbox.headers(listOf(uid))
            mailbox.body(uid, 100_000)
        }
        assertEquals(MessageFlags(), inbox { it.flags(listOf(uid)) }.single().flags)
    }

    @Test
    fun aFolderThatDoesNotExistIsTheServersNo() {
        try {
            ImapConnection.open(server.imap, server.credentials).use { it.open("Yok") }
            fail("a folder that is not there was opened")
        } catch (e: MailException) {
            assertEquals(MailException.Kind.Server, e.kind)
        }
    }

    @Test
    fun foldersAreCountedWithoutOpeningThem() {
        server.createFolder("Projeler")
        server.deliver("plan", monday, folder = "Projeler")
        server.deliver("breakfast", thursday, folder = "Projeler", flags = Flags(Flags.Flag.SEEN))
        ImapConnection.open(server.imap, server.credentials).use { connection ->
            val counts = connection.counts("Projeler")!!
            assertEquals(2, counts.total)
            assertEquals(1, counts.unread)
        }
    }
}
