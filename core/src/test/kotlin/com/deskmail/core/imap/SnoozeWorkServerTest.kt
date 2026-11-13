package com.deskmail.core.imap

import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailAddress
import com.deskmail.core.sync.Snooze
import com.deskmail.core.sync.SnoozeList
import com.deskmail.core.sync.SnoozeWork
import jakarta.mail.Flags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneOffset

/** Snoozing on a real IMAP server inside the test: the folder, the list in it, and a message brought back. */
class SnoozeWorkServerTest {

    @get:Rule
    val server = TestMailServer()

    @get:Rule
    val files = TemporaryFolder()

    private val monday: Instant = Instant.parse("2026-11-09T07:00:00Z")
    private val own = MailAddress("Deniz Aksoy", TestMailServer.ADDRESS)
    private val plan = Snooze("<plan-3@example.org>", Instant.parse("2026-11-16T05:00:00Z"), "INBOX", "Proje planı")

    private fun <T> connected(block: (ImapConnection) -> T): T = ImapConnection.open(server.imap, server.credentials).use(block)

    /** Puts a list saying [snoozes] into the Snoozed folder in the place of [old]; its UID. */
    private fun publish(connection: ImapConnection, snoozes: List<Snooze>, old: List<Long> = emptyList(), trash: String? = null): Long {
        val file = files.newFile()
        SnoozeWork.writeList(snoozes, own, monday, ZoneOffset.UTC, file)
        return SnoozeWork.replace(connection, SnoozeList.FOLDER, file, old, trash)
    }

    @Test
    fun theFolderIsMadeOnceAndKnownForWhatItIs() = connected { connection ->
        connection.create(SnoozeList.FOLDER)
        // there already: left as it is
        connection.create(SnoozeList.FOLDER)

        assertEquals(FolderRole.Snoozed, connection.folders().single { it.path == SnoozeList.FOLDER }.role)
    }

    @Test
    fun aMessageIsSnoozedListedAndBroughtBackUnread() = connected { connection ->
        connection.create(SnoozeList.FOLDER)
        val uid = server.deliver("plan", monday, flags = Flags(Flags.Flag.SEEN))
        connection.edit("INBOX").use { it.move(listOf(uid), SnoozeList.FOLDER) }
        val first = publish(connection, listOf(plan))

        val listed = SnoozeWork.read(connection, SnoozeList.FOLDER)
        assertEquals(first, listed.uid)
        assertEquals(listOf(plan), listed.snoozes)
        assertEquals(setOf("<plan-3@example.org>"), listed.held)
        assertTrue(listed.stale.isEmpty())
        // the list is read: it is no new mail to anybody
        assertTrue(server.flags(first, SnoozeList.FOLDER)!!.contains(Flags.Flag.SEEN))

        assertTrue(SnoozeWork.wake(connection, SnoozeList.FOLDER, plan.messageId, "INBOX"))

        val back = server.uids("INBOX").single()
        assertEquals("Re: Proje planı (v3)", server.subject(back))
        assertFalse(server.flags(back)!!.contains(Flags.Flag.SEEN))
        assertTrue(SnoozeWork.read(connection, SnoozeList.FOLDER).held.isEmpty())
        // brought back already, by this device or another: nothing to do
        assertFalse(SnoozeWork.wake(connection, SnoozeList.FOLDER, plan.messageId, "INBOX"))
    }

    @Test
    fun aNewListTakesThePlaceOfTheOld() = connected { connection ->
        connection.create(SnoozeList.FOLDER)
        connection.create("Trash")
        val first = publish(connection, listOf(plan))
        val later = plan.copy(wakeAt = Instant.parse("2026-11-20T05:00:00Z"))

        val second = publish(connection, listOf(later), old = listOf(first))

        assertEquals(listOf(second), server.uids(SnoozeList.FOLDER))
        assertEquals(listOf(later), SnoozeWork.read(connection, SnoozeList.FOLDER).snoozes)

        // where taking a list out of its folder would only archive it, it goes to the trash
        val third = publish(connection, emptyList(), old = listOf(second), trash = "Trash")
        assertEquals(listOf(third), server.uids(SnoozeList.FOLDER))
        assertEquals(1, server.uids("Trash").size)
        assertTrue(SnoozeWork.read(connection, SnoozeList.FOLDER).snoozes.isEmpty())
    }

    /** Two lists in the folder (two devices wrote at once): the newer stands, the older is to go. */
    @Test
    fun anOlderListIsLeftOver() = connected { connection ->
        connection.create(SnoozeList.FOLDER)
        val first = publish(connection, listOf(plan))
        val second = publish(connection, emptyList())

        val listed = SnoozeWork.read(connection, SnoozeList.FOLDER)

        assertEquals(second, listed.uid)
        assertTrue(listed.snoozes.isEmpty())
        assertEquals(listOf(first), listed.stale)
    }
}
