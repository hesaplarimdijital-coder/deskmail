package com.deskmail.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where a message of the user's own stands: being written, handed over, on its way, failed. Each
 * step is taken only from the state it belongs to, whoever else is at the same message.
 */
@RunWith(AndroidJUnit4::class)
class OutboxDaoTest {

    private lateinit var db: DeskMailDatabase
    private var account = 0L

    @Before
    fun open() = runBlocking {
        db = DeskMailDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        account = db.accounts().insert(testAccount("deniz@example.org"))
    }

    @After
    fun close() {
        db.close()
    }

    private val outbox get() = db.outbox()

    private suspend fun draft(subject: String = "konu", updatedAt: Long = 1): Long = outbox.insert(testOutbox(account, subject, updatedAt))

    private suspend fun stored(id: Long): OutboxEntity? = outbox.byId(id)

    private suspend fun write(id: Long, subject: String, now: Long): Int =
        outbox.write(id, account, "elif@example.org", "", "", subject, "yeni metin", "<taslak@example.org>", now)

    @Test
    fun aDraftIsHandedOverTakenBackAndHandedOverAgain() = runBlocking {
        val id = draft()

        assertEquals(1, outbox.queue(id, sendAfter = 110, now = 100))
        assertEquals(OutboxEntity.QUEUED, stored(id)!!.state)
        // handed over twice is handed over once: its time does not start again
        assertEquals(0, outbox.queue(id, sendAfter = 500, now = 101))
        assertEquals(110L, stored(id)!!.sendAfter)
        assertTrue(outbox.due(now = 109).isEmpty())
        assertEquals(110L, outbox.nextDue(now = 109))
        assertEquals(110L, outbox.earliest())

        assertEquals(1, outbox.unqueue(id, now = 105))
        assertEquals(OutboxEntity.DRAFT, stored(id)!!.state)
        assertNull(outbox.earliest())
        // there is nothing to take back a second time
        assertEquals(0, outbox.unqueue(id, now = 106))

        assertEquals(1, outbox.queue(id, sendAfter = 120, now = 110))
        assertEquals(listOf(id), outbox.due(now = 120).map { it.id })
        assertNull(outbox.nextDue(now = 120))
    }

    @Test
    fun aMessageOnItsWayCanNeitherBeTakenBackNorChangedNorThrownAway() = runBlocking {
        val id = draft()
        outbox.queue(id, sendAfter = 10, now = 0)

        assertEquals(1, outbox.claim(id, now = 10))
        assertEquals(OutboxEntity.SENDING, stored(id)!!.state)
        // one sender at a time
        assertEquals(0, outbox.claim(id, now = 10))
        assertEquals(0, outbox.unqueue(id, now = 11))
        assertEquals(0, write(id, "sonradan", now = 11))
        assertEquals(0, outbox.discard(id))
        assertEquals("konu", stored(id)!!.subject)
        assertTrue(outbox.due(now = 99).isEmpty())

        // the server took it
        outbox.delete(id)
        assertNull(stored(id))
    }

    @Test
    fun whatIsTypedIsKeptOnlyWhileTheMessageIsBeingWrittenOn() = runBlocking {
        val id = draft()

        assertEquals(1, write(id, "yeni konu", now = 50))
        val written = stored(id)!!
        assertEquals("yeni konu", written.subject)
        assertEquals("elif@example.org", written.toText)
        assertEquals("yeni metin", written.body)
        assertEquals(50L, written.updatedAt)
        assertEquals(OutboxEntity.DRAFT, written.state)

        // handed over: it goes as it was then
        outbox.queue(id, sendAfter = 60, now = 55)
        assertEquals(0, write(id, "geç kalan", now = 56))
        assertEquals("yeni konu", stored(id)!!.subject)
        assertEquals(OutboxEntity.QUEUED, stored(id)!!.state)

        // one the server would not take is written on, and is failed until it is handed over again
        outbox.claim(id, now = 60)
        outbox.setState(id, OutboxEntity.FAILED, 0, "550 no such user", now = 61)
        assertEquals(1, write(id, "düzeltildi", now = 70))
        assertEquals(OutboxEntity.FAILED, stored(id)!!.state)
        assertEquals("550 no such user", stored(id)!!.error)
        assertEquals(1, outbox.queue(id, sendAfter = 80, now = 80))
        assertEquals(OutboxEntity.QUEUED, stored(id)!!.state)
        assertEquals("", stored(id)!!.error)
        assertEquals("düzeltildi", stored(id)!!.subject)
    }

    @Test
    fun whatAnEarlierRunWasSendingIsKeptAsFailed() = runBlocking {
        val written = draft("taslak")
        val waiting = draft("bekleyen")
        val cutShort = draft("yarım kalan")
        outbox.queue(waiting, sendAfter = 10, now = 0)
        outbox.queue(cutShort, sendAfter = 10, now = 0)
        outbox.claim(cutShort, now = 10)

        assertEquals(1, outbox.failInterrupted("interrupted", now = 99))

        assertEquals(OutboxEntity.DRAFT, stored(written)!!.state)
        assertEquals(OutboxEntity.QUEUED, stored(waiting)!!.state)
        assertEquals(OutboxEntity.FAILED, stored(cutShort)!!.state)
        assertEquals("interrupted", stored(cutShort)!!.error)
        // nothing was being sent this time
        assertEquals(0, outbox.failInterrupted("interrupted", now = 100))
    }

    @Test
    fun theEarliestWaitingMessageSaysWhenToLook() = runBlocking {
        val later = draft("sonraki")
        val sooner = draft("önceki")
        draft("taslak")
        assertNull(outbox.earliest())
        outbox.queue(later, sendAfter = 300, now = 0)
        outbox.queue(sooner, sendAfter = 200, now = 0)

        assertEquals(200L, outbox.earliest())
        assertEquals(listOf(sooner), outbox.due(now = 250).map { it.id })
        assertEquals(300L, outbox.nextDue(now = 250))

        // "send now": it no longer waits out its time
        outbox.bringForward(later, time = 100)
        assertEquals(100L, outbox.earliest())
        assertEquals(listOf(later, sooner), outbox.due(now = 250).map { it.id })
        // never the other way round
        outbox.bringForward(sooner, time = 900)
        assertEquals(200L, stored(sooner)!!.sendAfter)
    }

    @Test
    fun filesBelongToTheirMessageAndGoWithIt() = runBlocking {
        val id = draft()
        val other = draft("başkası")
        val first = outbox.addFile(OutboxFileEntity(outboxId = id, name = "plan.pdf", mimeType = "application/pdf", size = 3000, path = "/outbox/1/a/plan.pdf"))
        val second = outbox.addFile(OutboxFileEntity(outboxId = id, name = "not.txt", mimeType = "text/plain", size = 12, path = "/outbox/1/b/not.txt"))
        outbox.addFile(OutboxFileEntity(outboxId = other, name = "resim.jpg", mimeType = "image/jpeg", size = 9, path = "/outbox/2/c/resim.jpg"))

        assertEquals(listOf("plan.pdf", "not.txt"), outbox.files(id).map { it.name })
        assertEquals("/outbox/1/b/not.txt", outbox.file(second)!!.path)
        assertEquals(3, outbox.observeFiles().first().size)

        outbox.removeFile(first)
        assertEquals(listOf("not.txt"), outbox.files(id).map { it.name })
        assertNull(outbox.file(first))

        // thrown away, and its files with it; the other message keeps its own
        assertEquals(1, outbox.discard(id))
        assertNull(stored(id))
        assertTrue(outbox.files(id).isEmpty())
        assertEquals(listOf("resim.jpg"), outbox.observeFiles().first().map { it.name })
    }

    @Test
    fun theOutboxIsListedTheLatestTouchedFirst() = runBlocking {
        val old = draft("eski", updatedAt = 1)
        val new = draft("yeni", updatedAt = 5)
        assertEquals(listOf(new, old), outbox.observeAll().first().map { it.id })

        write(old, "eski, yeniden", now = 9)

        assertEquals(listOf(old, new), outbox.observeAll().first().map { it.id })
        assertEquals("eski, yeniden", outbox.observe(old).first()!!.subject)
        assertNull(outbox.observe(404).first())
    }

    @Test
    fun anAccountThatIsRemovedTakesItsOutboxAlong() = runBlocking {
        val id = draft()
        outbox.addFile(OutboxFileEntity(outboxId = id, name = "ek.txt", mimeType = "text/plain", size = 2, path = "/outbox/1/a/ek.txt"))

        db.accounts().delete(account)

        assertNull(stored(id))
        assertTrue(outbox.observeFiles().first().isEmpty())
    }
}

/** A message as it stands when it has just been begun: a draft to one recipient. */
fun testOutbox(accountId: Long, subject: String = "konu", updatedAt: Long = 1) = OutboxEntity(
    accountId = accountId,
    state = OutboxEntity.DRAFT,
    toText = "ayse@example.org",
    ccText = "",
    bccText = "",
    subject = subject,
    body = "metin",
    messageId = "<taslak@example.org>",
    inReplyTo = "",
    refs = "",
    sourceId = 0,
    sourceKind = "",
    draftFolderId = 0,
    draftUid = 0,
    sendAfter = 0,
    error = "",
    createdAt = 1,
    updatedAt = updatedAt,
)
