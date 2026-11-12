package com.deskmail.core.sync

import com.deskmail.core.mail.FolderRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AccountSyncTest {

    private val now: Instant = Instant.parse("2026-11-12T09:30:00Z")
    private val store = MemoryStore()
    private val sync = AccountSync(store)

    private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    @Test
    fun theInboxIsSyncedAndTheOtherFoldersAreCounted() = runBlocking {
        val inbox = FakeFolder("INBOX").apply { deliver("gelen", daysAgo(1)) }
        val sent = FakeFolder("Sent").apply { deliver("giden", daysAgo(1)) }
        val projects = FakeFolder("Projeler").apply {
            deliver("plan", daysAgo(2))
            deliver("tutanak", daysAgo(1))
        }
        val account = FakeAccount(inbox, sent, projects)

        val result = sync.sync(account, accountId = 1, days = 30, now = now)

        assertEquals(listOf("INBOX", "Sent", "Projeler"), result.folders.map { it.path })
        assertEquals(listOf(FolderRole.Inbox, FolderRole.Sent, FolderRole.Other), result.folders.map { it.role })
        assertEquals(setOf(store.folder("INBOX").id), result.synced.keys)
        assertFalse(result.moreToFetch)
        assertEquals(listOf("gelen"), store.subjects("INBOX"))
        // the others were not opened; their counts are the server's
        assertEquals(1, account.opened)
        assertTrue(store.folder("Projeler").messages.isEmpty())
        assertEquals(2, store.folder("Projeler").total)
        assertEquals(2, store.folder("Projeler").unread)
        assertEquals(1, store.folder("Sent").total)
    }

    @Test
    fun aFolderTheUserOpenedIsSyncedToo() = runBlocking {
        val inbox = FakeFolder("INBOX").apply { deliver("gelen", daysAgo(1)) }
        val projects = FakeFolder("Projeler").apply { deliver("plan", daysAgo(2)) }
        val account = FakeAccount(inbox, projects)

        val result = sync.sync(account, accountId = 1, days = 30, now = now, also = setOf("Projeler"))

        assertEquals(2, result.synced.size)
        assertEquals(listOf("plan"), store.subjects("Projeler"))
    }

    @Test
    fun aViewOverTheOtherFoldersIsNotKept() = runBlocking {
        val inbox = FakeFolder("INBOX").apply { deliver("gelen", daysAgo(1)) }
        val all = FakeFolder("[Gmail]/All Mail").apply { deliver("gelen", daysAgo(1)) }
        val starred = FakeFolder("[Gmail]/Starred").apply { deliver("gelen", daysAgo(1)) }
        val important = FakeFolder("[Gmail]/Important")
        val account = FakeAccount(inbox, all, starred, important)
        account.attributes["[Gmail]/All Mail"] = setOf("\\HasNoChildren", "\\All")
        account.attributes["[Gmail]/Starred"] = setOf("\\HasNoChildren", "\\Flagged")
        account.attributes["[Gmail]/Important"] = setOf("\\HasNoChildren", "\\Important")

        val result = sync.sync(account, accountId = 1, days = 30, now = now, also = setOf("[Gmail]/Starred"))

        // "All Mail" is a place (what is archived is nowhere else); "Starred" and "Important" only show what is elsewhere
        assertEquals(listOf("INBOX", "[Gmail]/All Mail"), result.folders.map { it.path })
        assertEquals(listOf(FolderRole.Inbox, FolderRole.All), result.folders.map { it.role })
        assertEquals(1, account.opened)
    }

    @Test
    fun aFolderThatIsGoneOnTheServerLeavesTheDeviceWithItsMail() = runBlocking {
        val inbox = FakeFolder("INBOX")
        val old = FakeFolder("Eski").apply { deliver("bir", daysAgo(1)) }
        val account = FakeAccount(inbox, old)
        sync.sync(account, 1, 30, now, also = setOf("Eski"))
        assertEquals(listOf("bir"), store.subjects("Eski"))

        account.folders.remove(old)
        account.folders.add(FakeFolder("Yeni"))
        val result = sync.sync(account, 1, 30, now)

        assertEquals(listOf("INBOX", "Yeni"), result.folders.map { it.path })
        assertTrue(store.folders.values.none { it.remote.path == "Eski" })
    }

    @Test
    fun whatIsKnownOfAFolderOutlastsANewList() = runBlocking {
        val inbox = FakeFolder("INBOX").apply { deliver("gelen", daysAgo(1)) }
        val account = FakeAccount(inbox)
        sync.sync(account, 1, 30, now)
        val before = store.stored("INBOX")
        assertEquals(7, before.state.uidValidity)

        account.folders.add(FakeFolder("Yeni"))
        val after = sync.sync(account, 1, 30, now).folders.first { it.path == "INBOX" }

        assertEquals(before.id, after.id)
        assertEquals(before.state, after.state)
        assertEquals(listOf("gelen"), store.subjects("INBOX"))
    }
}
