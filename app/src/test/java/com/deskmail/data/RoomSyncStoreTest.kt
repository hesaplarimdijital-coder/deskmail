package com.deskmail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailAddress
import com.deskmail.core.mail.MessageFlags
import com.deskmail.core.mail.RemoteAttachment
import com.deskmail.core.mail.RemoteFlags
import com.deskmail.core.mail.RemoteFolder
import com.deskmail.core.mail.RemoteMessage
import com.deskmail.core.sync.ChangeOutcome
import com.deskmail.core.sync.FolderState
import com.deskmail.core.sync.StoredBody
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.MessageEntity
import com.deskmail.db.testAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** The sync's store on the real database: what the sync writes is what the screens will read. */
@RunWith(AndroidJUnit4::class)
class RoomSyncStoreTest {

    private lateinit var db: DeskMailDatabase
    private lateinit var store: RoomSyncStore
    private var account = 0L

    @Before
    fun open() {
        db = DeskMailDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        store = RoomSyncStore(db)
        account = runBlocking { db.accounts().insert(testAccount("deniz@gmail.example")) }
    }

    @After
    fun close() {
        db.close()
    }

    private fun remote(path: String, role: FolderRole = FolderRole.Other) = RemoteFolder(path, '/', emptySet(), selectable = true, role = role)

    private fun message(uid: Long, subject: String, from: String = "Ayşe Yılmaz", flags: MessageFlags = MessageFlags(), attachments: Int = 0) = RemoteMessage(
        uid = uid,
        globalId = "<$uid@example.org>",
        inReplyTo = "",
        references = "",
        subject = subject,
        from = listOf(MailAddress(from, "ayse@posta.example")),
        to = listOf(MailAddress("Deniz Aksoy", "deniz@gmail.example")),
        cc = listOf(MailAddress("", "elif@isyeri.example")),
        bcc = emptyList(),
        replyTo = emptyList(),
        sentAt = Instant.parse("2026-11-10T08:00:00Z"),
        receivedAt = Instant.parse("2026-11-10T08:00:00Z").plusSeconds(uid),
        size = 2_000,
        flags = flags,
        attachments = attachments,
    )

    private suspend fun inbox(): Long = store.saveFolders(account, listOf(remote("INBOX", FolderRole.Inbox))).single().id

    @Test
    fun theFoldersBecomeThoseTheServerLists() = runBlocking {
        val first = store.saveFolders(account, listOf(remote("INBOX", FolderRole.Inbox), remote("Sent", FolderRole.Sent), remote("Eski")))
        assertEquals(listOf("INBOX", "Sent", "Eski"), first.map { it.path })
        assertEquals(listOf(FolderRole.Inbox, FolderRole.Sent, FolderRole.Other), first.map { it.role })
        val inbox = first[0]
        store.insert(inbox.id, listOf(message(1, "bir")))
        store.saveState(inbox.id, FolderState(uidValidity = 7, uidNext = 2, highestModSeq = 11), Instant.parse("2026-11-12T09:30:00Z"))
        store.saveCounts(inbox.id, total = 40, unread = 3)
        val old = first[2]
        store.insert(old.id, listOf(message(1, "eskide kalan")))

        // on the server one folder is gone, one is new, and the order is another
        val second = store.saveFolders(account, listOf(remote("Yeni"), remote("INBOX", FolderRole.Inbox), remote("Sent", FolderRole.Sent)))

        assertEquals(listOf("Yeni", "INBOX", "Sent"), second.map { it.path })
        // a folder that stays keeps its id and what was known of it
        val kept = second[1]
        assertEquals(inbox.id, kept.id)
        assertEquals(FolderState(7, 2, 11), kept.state)
        assertEquals(listOf(1L), store.uids(inbox.id))
        val row = db.folders().byId(inbox.id)!!
        assertEquals(40, row.total)
        assertEquals(3, row.unread)
        assertEquals(Instant.parse("2026-11-12T09:30:00Z").toEpochMilli(), row.syncedAt)
        // the one that is gone took its messages along, out of the index too
        assertNull(db.folders().byId(old.id))
        assertTrue(db.messages().search("eskide").isEmpty())
        assertEquals(1, db.messages().count())
    }

    @Test
    fun aRoleTheUserGaveAFolderStays() = runBlocking {
        val folder = store.saveFolders(account, listOf(remote("Posta Arşivi"))).single()
        db.folders().update(db.folders().byId(folder.id)!!.copy(role = "Archive", roleFixed = true))

        val again = store.saveFolders(account, listOf(remote("Posta Arşivi")))

        assertEquals(FolderRole.Archive, again.single().role)
    }

    @Test
    fun messagesAreKeptOnceAndFoundByTheirWords() = runBlocking {
        val inbox = inbox()
        store.insert(inbox, listOf(message(1, "Şarkı sözleri ve ISPARTA gülü"), message(2, "Kasım bülteni", from = "Sahaf Kitabevi")))
        // the same message named again by a sync that was cut off
        store.insert(inbox, listOf(message(2, "Kasım bülteni", from = "Sahaf Kitabevi"), message(3, "Toplantı")))

        assertEquals(listOf(1L, 2L, 3L), store.uids(inbox))
        val first = db.messages().byUid(inbox, 1)!!
        assertEquals(account, first.accountId)
        assertEquals("Şarkı sözleri ve ISPARTA gülü", first.subject)
        assertEquals(listOf(MailAddress("Ayşe Yılmaz", "ayse@posta.example")), MailAddress.decode(first.fromList))
        assertEquals(MessageEntity.BODY_MISSING, first.bodyState)
        assertEquals(listOf(3L, 2L, 1L), store.uidsWithoutBody(inbox, 10))

        // folded: no capitals, no Turkish letters needed
        assertEquals(listOf(first.id), db.messages().search("sarki"))
        assertEquals(listOf(first.id), db.messages().search("isparta"))
        assertEquals(listOf(first.id), db.messages().search("gulu"))
        // the people on it
        assertEquals(listOf(db.messages().byUid(inbox, 2)!!.id), db.messages().search("sahaf"))
        assertEquals(3, db.messages().search("people:elif").size)
    }

    @Test
    fun aTextIsKeptWithItsFilesAndFound() = runBlocking {
        val inbox = inbox()
        store.insert(inbox, listOf(message(1, "Proje planı", attachments = 1), message(2, "çok büyük")))
        val body = StoredBody(
            text = "Planın üçüncü sürümü ekte. Perşembe günü üstünden geçelim.",
            html = "<p>Planın <b>üçüncü</b> sürümü ekte.</p>",
            remoteImages = true,
            preview = "Planın üçüncü sürümü ekte.",
            attachments = listOf(
                RemoteAttachment("2", "", "image/png", 900, "logo@example.org", inline = true),
                RemoteAttachment("3", "Proje planı v3.pdf", "application/pdf", 284_000, "", inline = false),
            ),
        )

        store.saveBody(inbox, 1, body)
        store.saveBody(inbox, 2, null)
        // a message that left the folder meanwhile
        store.saveBody(inbox, 99, body)

        val message = db.messages().byUid(inbox, 1)!!
        assertEquals(MessageEntity.BODY_KEPT, message.bodyState)
        assertEquals("Planın üçüncü sürümü ekte.", message.preview)
        val kept = db.messages().observeBody(message.id).first()!!
        assertEquals(body.text, kept.text)
        assertEquals(body.html, kept.html)
        assertTrue(kept.remoteImages)
        assertEquals(listOf("", "Proje planı v3.pdf"), db.messages().observeAttachments(message.id).first().map { it.name })
        assertEquals(listOf(message.id), db.messages().search("persembe"))
        // found by its subject as before
        assertEquals(listOf(message.id), db.messages().search("subject:proje"))

        assertEquals(MessageEntity.BODY_NONE, db.messages().byUid(inbox, 2)!!.bodyState)
        assertTrue(store.uidsWithoutBody(inbox, 10).isEmpty())

        // kept again (the user opened it while the backlog was fetching it): still one of each
        store.saveBody(inbox, 1, body)
        assertEquals(2, db.messages().observeAttachments(message.id).first().size)
        assertEquals(listOf(message.id), db.messages().search("persembe"))
    }

    @Test
    fun marksAreBroughtUpToDate() = runBlocking {
        val inbox = inbox()
        store.insert(inbox, listOf(message(1, "bir"), message(2, "iki", flags = MessageFlags(seen = true)), message(3, "üç")))

        store.updateFlags(
            inbox,
            listOf(
                RemoteFlags(1, MessageFlags(seen = true, flagged = true)),
                RemoteFlags(2, MessageFlags(seen = true)),
                RemoteFlags(3, MessageFlags(answered = true)),
                // not on the device
                RemoteFlags(9, MessageFlags(seen = true)),
            ),
        )

        val first = db.messages().byUid(inbox, 1)!!
        assertTrue(first.seen && first.flagged)
        assertTrue(db.messages().byUid(inbox, 2)!!.seen)
        val third = db.messages().byUid(inbox, 3)!!
        assertTrue(third.answered)
        assertFalse(third.seen)
        assertEquals(listOf(third.id), db.messages().observeUnread().first().map { it.id })
        assertEquals(listOf(first.id), db.messages().observeStarred().first().map { it.id })
    }

    /** The user's word on a mark is newer than the server's until the server has been told. */
    @Test
    fun aMarkThatWaitsForTheServerIsNotOverwritten() = runBlocking {
        val inbox = inbox()
        store.insert(inbox, listOf(message(1, "bir"), message(2, "iki", flags = MessageFlags(flagged = true))))
        val first = db.messages().byUid(inbox, 1)!!.id
        val second = db.messages().byUid(inbox, 2)!!.id
        db.pending().mark(first, seen = true, flagged = null, now = 100)
        db.pending().mark(second, seen = null, flagged = false, now = 100)

        // a sync reads the marks before the server was told: it still has the old ones
        store.updateFlags(inbox, listOf(RemoteFlags(1, MessageFlags(answered = true)), RemoteFlags(2, MessageFlags(seen = true, flagged = true))))

        val rows = db.messages().observeFolder(inbox).first().associateBy { it.uid }
        // the user's marks stand, what else the server knows comes in
        assertTrue(rows.getValue(1).seen && rows.getValue(1).answered)
        assertTrue(rows.getValue(2).seen && !rows.getValue(2).flagged)

        // once the server has it, the server's word counts again
        db.pending().all().forEach { db.pending().settle(it.id, ChangeOutcome.Done()) }
        store.updateFlags(inbox, listOf(RemoteFlags(1, MessageFlags())))
        assertFalse(db.messages().byUid(inbox, 1)!!.seen)
    }

    /** A reply that was just sent marks its original before the server knows of it: a sync in between leaves the mark. */
    @Test
    fun anAnsweredMarkThatWaitsForTheServerIsNotOverwritten() = runBlocking {
        val inbox = inbox()
        store.insert(inbox, listOf(message(1, "bir"), message(2, "iki")))
        val answered = db.messages().byUid(inbox, 1)!!.id
        val sentOn = db.messages().byUid(inbox, 2)!!.id
        db.pending().markAnswered(answered, forwarded = false, now = 100)
        db.pending().markAnswered(sentOn, forwarded = true, now = 100)

        store.updateFlags(inbox, listOf(RemoteFlags(1, MessageFlags()), RemoteFlags(2, MessageFlags())))

        assertTrue(db.messages().byUid(inbox, 1)!!.answered)
        assertTrue(db.messages().byUid(inbox, 2)!!.forwarded)
    }

    @Test
    fun whatLeavesTheDeviceLeavesTheIndex() = runBlocking {
        val inbox = inbox()
        store.insert(inbox, listOf(message(1, "kalacak"), message(2, "gidecek"), message(3, "o da gidecek")))
        store.saveBody(inbox, 2, StoredBody("Gövdesi de gider", null, false, "Gövdesi de gider", emptyList()))

        store.delete(inbox, listOf(2L, 3L, 404L))

        assertEquals(listOf(1L), store.uids(inbox))
        assertTrue(db.messages().search("gidecek").isEmpty())
        assertTrue(db.messages().search("govdesi").isEmpty())
        assertEquals(1, db.messages().search("kalacak").size)

        // the server gave the folder's UIDs a new meaning
        store.clear(inbox)
        assertTrue(store.uids(inbox).isEmpty())
        assertTrue(db.messages().search("kalacak").isEmpty())
    }

    @Test
    fun theUnifiedViewsLeaveOutWhatDoesNotBelong() = runBlocking {
        val folders = store.saveFolders(
            account,
            listOf(remote("INBOX", FolderRole.Inbox), remote("[Gmail]/All Mail", FolderRole.All), remote("[Gmail]/Spam", FolderRole.Junk), remote("Seyahat")),
        )
        val (inbox, all, spam, travel) = folders.map { it.id }
        store.insert(inbox, listOf(message(1, "gelen", flags = MessageFlags(flagged = true))))
        store.insert(all, listOf(message(1, "gelen", flags = MessageFlags(flagged = true)), message(2, "arşivdeki")))
        store.insert(spam, listOf(message(1, "kazandınız")))
        store.insert(travel, listOf(message(1, "bilet")))

        assertEquals(listOf("gelen"), db.messages().observeInboxes().first().map { it.subject })
        // every unread message once: not from "All Mail", not from Spam
        assertEquals(setOf("gelen", "bilet"), db.messages().observeUnread().first().map { it.subject }.toSet())
        assertEquals(1, db.messages().observeStarred().first().size)
        assertEquals(listOf("arşivdeki", "gelen"), db.messages().observeFolder(all).first().map { it.subject })
        assertEquals(3, db.messages().observeNewest(10).first().size)
        assertEquals(listOf(inbox, all, spam, travel).sorted(), db.messages().foldersWithoutBodies(account).sorted())
    }
}
