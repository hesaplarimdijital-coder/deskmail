package com.deskmail.core.imap

import com.deskmail.core.mail.MailException
import com.deskmail.core.mail.Mark
import jakarta.mail.Flags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Marking, moving and removing messages on a real IMAP server inside the test. */
class ImapEditorServerTest {

    @get:Rule
    val server = TestMailServer()

    private val monday: Instant = Instant.parse("2026-11-09T07:00:00Z")
    private val thursday: Instant = Instant.parse("2026-11-12T06:05:00Z")

    @Before
    fun folders() {
        server.createFolder("Arşiv")
    }

    private fun <T> edit(path: String = "INBOX", block: (ImapEditor) -> T): T =
        ImapConnection.open(server.imap, server.credentials).use { connection -> connection.edit(path).use(block) }

    @Test
    fun marksAreSetAndCleared() {
        val plan = server.deliver("plan", monday)
        val breakfast = server.deliver("breakfast", thursday, flags = Flags(Flags.Flag.SEEN))

        edit { editor ->
            assertTrue(editor.uidValidity > 0)
            editor.mark(listOf(plan), Mark.Seen, true)
            editor.mark(listOf(plan, breakfast), Mark.Flagged, true)
            editor.mark(listOf(breakfast), Mark.Seen, false)
        }

        assertTrue(server.flags(plan)!!.contains(Flags.Flag.SEEN))
        assertTrue(server.flags(plan)!!.contains(Flags.Flag.FLAGGED))
        assertFalse(server.flags(breakfast)!!.contains(Flags.Flag.SEEN))
        assertTrue(server.flags(breakfast)!!.contains(Flags.Flag.FLAGGED))
    }

    @Test
    fun aMessageIsMovedAndTheServerSaysWhereItWent() {
        server.deliver("newsletter", monday, folder = "Arşiv")
        val plan = server.deliver("plan", monday, flags = Flags(Flags.Flag.SEEN))
        val breakfast = server.deliver("breakfast", thursday)

        val moved = edit { it.move(listOf(plan), "Arşiv") }

        assertEquals(listOf(breakfast), server.uids())
        val arrived = server.uids("Arşiv").last()
        assertEquals(plan, moved.single().uid)
        assertEquals(arrived, moved.single().newUid)
        // the stamp of the folder it went to, as that folder itself reports it
        assertEquals(edit("Arşiv") { it.uidValidity }, moved.single().newUidValidity)
        assertEquals("Re: Proje planı (v3)", server.subject(arrived, "Arşiv"))
        // it took its marks along
        assertTrue(server.flags(arrived, "Arşiv")!!.contains(Flags.Flag.SEEN))
    }

    @Test
    fun severalMessagesMoveAtOnce() {
        val plan = server.deliver("plan", monday)
        val breakfast = server.deliver("breakfast", thursday)
        val kept = server.deliver("newsletter", thursday)

        val moved = edit { it.move(listOf(plan, breakfast), "Arşiv") }.associate { it.uid to it.newUid }

        assertEquals(listOf(kept), server.uids())
        assertEquals(setOf(plan, breakfast), moved.keys)
        assertEquals("Re: Proje planı (v3)", server.subject(moved.getValue(plan), "Arşiv"))
        assertEquals("Cumartesi kahvaltı?", server.subject(moved.getValue(breakfast), "Arşiv"))
    }

    @Test
    fun onlyWhatIsNamedIsRemoved() {
        val plan = server.deliver("plan", monday)
        val breakfast = server.deliver("breakfast", thursday)
        // another mail program marked this one as deleted and has not removed it yet
        val theirs = server.deliver("newsletter", thursday, flags = Flags(Flags.Flag.DELETED))

        edit { it.expunge(listOf(plan)) }

        assertEquals(listOf(breakfast, theirs), server.uids())
    }

    @Test
    fun aUidTheFolderDoesNotHaveIsPassedOver() {
        val plan = server.deliver("plan", monday)

        edit { editor ->
            editor.mark(listOf(999), Mark.Seen, true)
            editor.expunge(listOf(999))
            assertTrue(editor.move(listOf(999), "Arşiv").isEmpty())
            assertEquals(listOf(plan), editor.move(listOf(999, plan), "Arşiv").map { it.uid })
        }

        assertTrue(server.uids().isEmpty())
        assertEquals(1, server.uids("Arşiv").size)
    }

    @Test
    fun movingToAFolderThatIsNotThereIsTheServersNo() {
        val plan = server.deliver("plan", monday)
        try {
            edit { it.move(listOf(plan), "Yok") }
            fail("a message was moved to a folder that is not there")
        } catch (e: MailException) {
            assertEquals(MailException.Kind.Server, e.kind)
        }
        // and the message is where it was
        assertEquals(listOf(plan), server.uids())
        assertNull(server.flags(plan)!!.takeIf { it.contains(Flags.Flag.DELETED) })
    }
}
