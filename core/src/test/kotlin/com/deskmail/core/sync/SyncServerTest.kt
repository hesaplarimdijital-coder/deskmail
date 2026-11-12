package com.deskmail.core.sync

import com.deskmail.core.imap.ImapConnection
import com.deskmail.core.imap.TestMailServer
import com.deskmail.core.log.AppLog
import jakarta.mail.Flags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** The whole way: an account on a real IMAP server inside the test, synced into a store in memory. */
class SyncServerTest {

    @get:Rule
    val server = TestMailServer()

    private val now: Instant = Instant.parse("2026-11-12T09:30:00Z")
    private val store = MemoryStore()
    private val log = AppLog()
    private val sync = AccountSync(store, log)

    private fun run(also: Set<String> = emptySet(), days: Int = 30): AccountSync.Result = runBlocking {
        ImapConnection.open(server.imap, server.credentials, log).use { sync.sync(it, accountId = 1, days = days, now = now, also = also) }
    }

    @Test
    fun anAccountIsBroughtToTheDeviceAndKeptUpToDate() {
        server.createFolder("Sent")
        server.createFolder("Projeler")
        val plan = server.deliver("plan", Instant.parse("2026-11-09T07:00:00Z"))
        val newsletter = server.deliver("newsletter", Instant.parse("2026-11-10T05:00:00Z"))
        server.deliver("breakfast", Instant.parse("2026-10-01T06:00:00Z"), folder = "Projeler")

        val first = run()

        assertEquals(setOf("INBOX", "Sent", "Projeler"), first.folders.map { it.path }.toSet())
        assertEquals(listOf("Kasım bülteni", "Re: Proje planı (v3)"), store.subjects("INBOX"))
        val inbox = store.folder("INBOX")
        assertEquals(2, inbox.total)
        assertEquals(2, inbox.unread)
        assertEquals(1, store.folder("Projeler").total)
        assertTrue(store.folder("Projeler").messages.isEmpty())

        // the text is there, cleaned
        val planBody = inbox.messages.getValue(plan).body!!
        assertEquals("Deniz selam, Planın üçüncü sürümü ekte.", planBody.preview)
        assertFalse(planBody.html!!.contains("script"))
        assertTrue(planBody.remoteImages)
        assertEquals(listOf("", "Proje planı v3.pdf"), planBody.attachments.map { it.name })
        val newsletterBody = inbox.messages.getValue(newsletter).body!!
        assertTrue(newsletterBody.text.startsWith("Kasım bülteni"))
        assertFalse(newsletterBody.remoteImages)

        // elsewhere: one message is read, one deleted, one arrives
        server.setFlag(plan, Flags.Flag.SEEN, true)
        server.expunge(newsletter)
        server.deliver("breakfast", Instant.parse("2026-11-12T06:05:00Z"))

        val second = run()

        val result = second.synced.getValue(inbox.id)
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertEquals(listOf("Cumartesi kahvaltı?", "Re: Proje planı (v3)"), store.subjects("INBOX"))
        assertTrue(inbox.messages.getValue(plan).header.flags.seen)
        assertEquals(1, inbox.unread)
        assertFalse(second.moreToFetch)
    }

    /** A folder the user opened is synced with the inbox; mail older than the window stays on the server. */
    @Test
    fun anOpenedFolderIsSyncedWithinTheWindow() {
        server.createFolder("Projeler")
        server.deliver("plan", Instant.parse("2026-11-09T07:00:00Z"), folder = "Projeler")
        server.deliver("breakfast", Instant.parse("2026-09-01T06:00:00Z"), folder = "Projeler")

        run(also = setOf("Projeler"))
        assertEquals(listOf("Re: Proje planı (v3)"), store.subjects("Projeler"))

        run(also = setOf("Projeler"), days = 0)
        assertEquals(setOf("Re: Proje planı (v3)", "Cumartesi kahvaltı?"), store.subjects("Projeler").toSet())
    }

    /** Nothing that travels as a password reaches the log, and the log says what was done. */
    @Test
    fun theLogTellsTheStoryWithoutTheSecret() {
        server.deliver("plan", Instant.parse("2026-11-09T07:00:00Z"))
        run()
        val lines = log.lines()
        assertTrue(lines.any { it.contains("signed in as deniz") })
        assertTrue(lines.any { it.contains("INBOX: +1") })
        assertFalse(lines.any { it.contains(TestMailServer.PASSWORD) })
    }
}
