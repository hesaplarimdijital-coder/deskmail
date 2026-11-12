package com.deskmail.core.sync

import com.deskmail.core.mail.MailException
import com.deskmail.core.mail.MessageFlags
import com.deskmail.core.mail.RemoteBody
import com.deskmail.core.mail.RemoteFolder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The sync's decisions, against a made-up server and a store in memory. */
class FolderSyncTest {

    private val now: Instant = Instant.parse("2026-11-12T09:30:00Z")
    private val store = MemoryStore()
    private val sync = FolderSync(store)

    private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    private fun run(folder: FakeFolder, days: Int = 30, at: Instant = now, bodyLimit: Int = FolderSync.BODIES_PER_RUN): FolderSync.Result = runBlocking {
        if (store.folders.values.none { it.remote.path == folder.path }) {
            store.saveFolders(1, listOf(RemoteFolder(folder.path, '/', emptySet(), selectable = true)))
        }
        sync.sync(folder.open(), store.stored(folder.path), days, at, bodyLimit)
    }

    @Test
    fun theFirstSyncBringsTheWindowNewestFirstWithItsText() {
        val inbox = FakeFolder("INBOX")
        inbox.deliver("eski", daysAgo(90))
        inbox.deliver("geçen hafta", daysAgo(7), text = "Planın   üçüncü\nsürümü ekte.")
        inbox.deliver("bugün", daysAgo(0), text = null, html = "<p>Merhaba <b>Deniz</b></p><img src=\"https://example.org/p.gif\">")

        val result = run(inbox)

        assertEquals(FolderSync.Result(added = 2, removed = 0, flagsChanged = 0, bodies = 2, bodiesLeft = false), result)
        assertEquals(listOf("bugün", "geçen hafta"), store.subjects("INBOX"))
        // the newest is asked for first
        assertTrue(inbox.asked.contains("headers [3, 2]"))
        val folder = store.folder("INBOX")
        assertEquals(FolderState(uidValidity = 7, uidNext = 4, highestModSeq = 0), folder.state)
        assertEquals(now, folder.syncedAt)
        assertEquals(3, folder.total)
        assertEquals(3, folder.unread)

        val plain = folder.messages.getValue(2).body!!
        assertEquals("Planın üçüncü sürümü ekte.", plain.preview)
        assertNull(plain.html)
        val html = folder.messages.getValue(3).body!!
        assertEquals("Merhaba Deniz", html.text)
        assertTrue(html.remoteImages)
        assertTrue(html.html!!.contains("<b>Deniz</b>"))
    }

    @Test
    fun aSecondSyncWithNothingNewFetchesNothing() {
        val inbox = FakeFolder("INBOX")
        inbox.deliver("bir", daysAgo(1))
        run(inbox)
        inbox.asked.clear()

        val result = run(inbox)

        assertEquals(FolderSync.Result(0, 0, 0, 0, false), result)
        assertTrue(inbox.asked.none { it.startsWith("headers") || it.startsWith("body") })
    }

    @Test
    fun newMailIsFetchedAndMailThatIsGoneIsRemoved() {
        val inbox = FakeFolder("INBOX")
        val first = inbox.deliver("bir", daysAgo(3))
        inbox.deliver("iki", daysAgo(2))
        run(inbox)

        inbox.expunge(first)
        inbox.deliver("üç", daysAgo(0))
        inbox.asked.clear()
        val result = run(inbox)

        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertEquals(listOf("üç", "iki"), store.subjects("INBOX"))
        // only the new message was asked for
        assertEquals(listOf("headers [3]"), inbox.asked.filter { it.startsWith("headers") })
        assertEquals(listOf("body 3"), inbox.asked.filter { it.startsWith("body") })
    }

    @Test
    fun whatWasReadOrStarredElsewhereShowsHere() {
        val inbox = FakeFolder("INBOX")
        val first = inbox.deliver("bir", daysAgo(3))
        val second = inbox.deliver("iki", daysAgo(2))
        run(inbox)

        inbox.setFlags(first, MessageFlags(seen = true, flagged = true))
        run(inbox)

        val messages = store.folder("INBOX").messages
        assertEquals(MessageFlags(seen = true, flagged = true), messages.getValue(first).header.flags)
        assertEquals(MessageFlags(), messages.getValue(second).header.flags)
        // a server without a change counter is asked for every flag, and only what differs is written
        assertEquals(1, store.flagWrites)
        assertEquals(1, store.folder("INBOX").unread)
    }

    @Test
    fun aServerThatCountsChangesIsAskedOnlyForThem() {
        val inbox = FakeFolder("INBOX", condstore = true)
        val first = inbox.deliver("bir", daysAgo(3))
        inbox.deliver("iki", daysAgo(2))
        run(inbox)
        assertTrue(store.folder("INBOX").state.highestModSeq > 0)

        // nothing changed: the counter says so, and no flags are asked for
        inbox.asked.clear()
        assertEquals(0, run(inbox).flagsChanged)
        assertTrue(inbox.asked.none { it.startsWith("flags") })

        inbox.setFlags(first, MessageFlags(seen = true))
        inbox.asked.clear()
        val result = run(inbox)

        assertEquals(1, result.flagsChanged)
        assertEquals(1, inbox.asked.count { it.startsWith("flags changed since") })
        assertTrue(inbox.asked.none { it.startsWith("flags of") })
        assertTrue(store.folder("INBOX").messages.getValue(first).header.flags.seen)
    }

    @Test
    fun aMessageMarkedDeletedIsAsGoodAsGone() {
        val inbox = FakeFolder("INBOX")
        val first = inbox.deliver("bir", daysAgo(3))
        inbox.deliver("silinmiş gelen", daysAgo(1), flags = MessageFlags(deleted = true))
        run(inbox)
        assertEquals(listOf("bir"), store.subjects("INBOX"))

        inbox.setFlags(first, MessageFlags(deleted = true))
        val result = run(inbox)

        assertEquals(1, result.removed)
        assertTrue(store.subjects("INBOX").isEmpty())
    }

    @Test
    fun aRenumberedFolderStartsOver() {
        val inbox = FakeFolder("INBOX")
        inbox.deliver("bir", daysAgo(3))
        inbox.deliver("iki", daysAgo(2))
        run(inbox)

        inbox.renumber()
        val result = run(inbox)

        assertEquals(2, result.added)
        assertEquals(2, result.removed)
        val folder = store.folder("INBOX")
        assertEquals(setOf(100L, 101L), folder.messages.keys)
        assertEquals(8, folder.state.uidValidity)
        // the text was fetched again under the new numbers
        assertNotNull(folder.messages.getValue(100).body)
    }

    @Test
    fun mailOlderThanTheWindowLeavesTheDevice() {
        val inbox = FakeFolder("INBOX")
        inbox.deliver("eskiyecek", daysAgo(20))
        inbox.deliver("taze", daysAgo(1))
        run(inbox, days = 30)
        assertEquals(listOf("taze", "eskiyecek"), store.subjects("INBOX"))

        // three weeks later
        val later = now.plus(Duration.ofDays(21))
        val result = run(inbox, days = 30, at = later)

        assertEquals(1, result.removed)
        assertEquals(listOf("taze"), store.subjects("INBOX"))
        // the whole folder with a window of "all"
        run(inbox, days = 0, at = later)
        assertEquals(listOf("taze", "eskiyecek"), store.subjects("INBOX"))
    }

    @Test
    fun textIsFetchedANumberAtATimeNewestFirst() {
        val inbox = FakeFolder("INBOX")
        repeat(5) { inbox.deliver("ileti ${it + 1}", daysAgo(5L - it)) }

        val first = run(inbox, bodyLimit = 2)
        assertEquals(5, first.added)
        assertEquals(2, first.bodies)
        assertTrue(first.bodiesLeft)
        assertEquals(listOf("body 5", "body 4"), inbox.asked.filter { it.startsWith("body") })

        val second = run(inbox, bodyLimit = 2)
        assertEquals(2, second.bodies)
        assertTrue(second.bodiesLeft)

        val third = run(inbox, bodyLimit = 2)
        assertEquals(1, third.bodies)
        assertFalse(third.bodiesLeft)
    }

    @Test
    fun aTextTheServerKeepsBackIsNotAskedForAgain() {
        val inbox = FakeFolder("INBOX")
        val large = inbox.deliver("çok büyük", daysAgo(2))
        val refused = inbox.deliver("sunucu vermiyor", daysAgo(1))
        inbox.setBody(large, RemoteBody(text = null, html = null, attachments = emptyList(), tooLarge = true))
        inbox.refused += refused

        val result = run(inbox)
        assertEquals(0, result.bodies)
        assertFalse(result.bodiesLeft)
        assertEquals(2, store.folder("INBOX").messages.getValue(large).bodyState)
        assertEquals(2, store.folder("INBOX").messages.getValue(refused).bodyState)

        inbox.asked.clear()
        run(inbox)
        assertTrue(inbox.asked.none { it.startsWith("body") })
    }

    @Test
    fun aSyncThatIsCutOffIsPickedUpByTheNext() {
        val inbox = FakeFolder("INBOX")
        repeat(3) { inbox.deliver("ileti ${it + 1}", daysAgo(3L - it)) }
        run(inbox)
        inbox.deliver("yeni", daysAgo(0))

        inbox.failNext = MailException(MailException.Kind.Network, "Connection reset")
        try {
            run(inbox)
            fail("the sync went on without a connection")
        } catch (e: MailException) {
            assertEquals(MailException.Kind.Network, e.kind)
        }
        // nothing was lost
        assertEquals(3, store.folder("INBOX").messages.size)

        val result = run(inbox)
        assertEquals(1, result.added)
        assertEquals(listOf("yeni", "ileti 3", "ileti 2", "ileti 1"), store.subjects("INBOX"))
    }
}
