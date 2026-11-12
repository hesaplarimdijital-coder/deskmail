package com.deskmail.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.sync.ChangeOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device's half of what is done to a message: it shows at once, it waits for the server in
 * its queue, and the device's copy follows what the server made of it.
 */
@RunWith(AndroidJUnit4::class)
class PendingDaoTest {

    private lateinit var db: DeskMailDatabase
    private var account = 0L
    private var inbox = 0L
    private var archive = 0L
    private var other = 0L

    @Before
    fun open() = runBlocking {
        db = DeskMailDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        account = db.accounts().insert(testAccount("deniz@example.org"))
        inbox = db.folders().insert(testFolder(account, "INBOX", role = "Inbox").copy(uidValidity = 7, unread = 2, total = 3))
        archive = db.folders().insert(testFolder(account, "Arşiv", role = "Archive", position = 1))
        // a folder of another account
        other = db.folders().insert(testFolder(db.accounts().insert(testAccount("deniz@gmail.example")), "INBOX", role = "Inbox"))
    }

    @After
    fun close() {
        db.close()
    }

    private val pending get() = db.pending()

    private suspend fun message(uid: Long, seen: Boolean = false, flagged: Boolean = false, folder: Long = inbox): Long =
        db.messages().insert(listOf(testMessage(account, folder, uid).copy(seen = seen, flagged = flagged))).single()

    private suspend fun stored(id: Long): MessageEntity? = db.messages().byId(id)

    private suspend fun listed(folder: Long = inbox): List<Long> = db.messages().observeFolder(folder).first().map { it.uid }

    private suspend fun counts(folder: Long): Pair<Int, Int> = db.folders().byId(folder)!!.let { it.unread to it.total }

    /** A reply was sent, or the message was sent on: its mark shows at once, and the server is told once. */
    @Test
    fun aMessageThatWasAnsweredOrSentOnSaysSoAtOnce() = runBlocking {
        val id = message(uid = 1)

        assertEquals(account, pending.markAnswered(id, forwarded = false, now = 10))
        assertTrue(stored(id)!!.answered)
        assertFalse(stored(id)!!.forwarded)
        assertEquals(listOf("Answered"), pending.due(account, now = 10).map { it.kind })
        // answered already: there is nothing more to tell
        assertNull(pending.markAnswered(id, forwarded = false, now = 11))

        assertEquals(account, pending.markAnswered(id, forwarded = true, now = 12))
        assertTrue(stored(id)!!.forwarded)
        assertEquals(listOf("Answered", "Forwarded"), pending.due(account, now = 12).map { it.kind })
        // a message that is gone has nothing to mark
        assertNull(pending.markAnswered(404, forwarded = false, now = 13))
    }

    @Test
    fun aMarkShowsAtOnceAndWaitsForTheServer() = runBlocking {
        val id = message(uid = 1)

        assertEquals(account, pending.mark(id, seen = true, flagged = true, now = 100))

        assertTrue(stored(id)!!.seen && stored(id)!!.flagged)
        // one unread message fewer in its folder
        assertEquals(1 to 3, counts(inbox))
        val due = pending.due(account, now = 100)
        assertEquals(listOf("Seen" to true, "Flagged" to true), due.map { it.kind to it.value })
        assertEquals(listOf("INBOX", "INBOX"), due.map { it.folderPath })
        assertEquals(listOf(7L, 7L), due.map { it.uidValidity })
        assertEquals(listOf(1L, 1L), due.map { it.uid })
        // the other account has nothing to tell
        assertEquals(listOf(account), pending.accounts())
    }

    @Test
    fun whatIsAlreadySoIsNoChange() = runBlocking {
        val id = message(uid = 1, seen = true)
        assertNull(pending.mark(id, seen = true, flagged = false, now = 100))
        assertNull(pending.mark(404, seen = true, flagged = null, now = 100))
        assertTrue(pending.all().isEmpty())
        assertEquals(2 to 3, counts(inbox))
    }

    @Test
    fun theLastWordOnAMarkCounts() = runBlocking {
        val id = message(uid = 1)

        pending.toggle(id, seen = true, now = 100)
        pending.toggle(id, seen = true, now = 101)
        pending.toggle(id, seen = false, now = 102)

        assertFalse(stored(id)!!.seen)
        assertTrue(stored(id)!!.flagged)
        assertEquals(2 to 3, counts(inbox))
        // one word for each mark, the latest
        assertEquals(listOf("Seen" to false, "Flagged" to true), pending.due(account, 200).map { it.kind to it.value })
    }

    @Test
    fun aMarkThatWasReplacedWhileItWasSentLeavesTheNewOneWaiting() = runBlocking {
        val id = message(uid = 1)
        pending.mark(id, seen = true, flagged = null, now = 100)
        val sent = pending.due(account, 100).single()
        // the user changes his mind while the first word is on its way
        pending.mark(id, seen = false, flagged = null, now = 101)

        pending.settle(sent.id, ChangeOutcome.Done())

        assertEquals(listOf("Seen" to false), pending.due(account, 200).map { it.kind to it.value })
        assertFalse(stored(id)!!.seen)
    }

    @Test
    fun aMessageThatIsMovedIsOffEveryListAtOnce() = runBlocking {
        val first = message(uid = 1)
        val second = message(uid = 2, seen = true)
        val third = message(uid = 3)

        val queued = pending.leave(listOf(first, second), archive, notBefore = 500, now = 100)

        assertEquals(listOf(first, second), queued.map { it.messageId })
        assertEquals(listOf(3L), listed())
        assertEquals(listOf(third), db.messages().observeInboxes().first().map { it.id })
        assertEquals(listOf(third), db.messages().observeUnread().first().map { it.id })
        assertEquals(listOf(third), db.messages().observeNewest(10).first().map { it.id })
        // counted where they are going
        assertEquals(1 to 1, counts(inbox))
        assertEquals(1 to 2, counts(archive))
        // the server is not told before the time to take it back has passed
        assertTrue(pending.due(account, now = 499).isEmpty())
        assertEquals(500L, pending.nextDue(account, now = 100))
        val due = pending.due(account, now = 500)
        assertEquals(listOf("Move", "Move"), due.map { it.kind })
        assertEquals(listOf("Arşiv", "Arşiv"), due.map { it.targetPath })
    }

    @Test
    fun whatCannotBeMovedIsPassedOver() = runBlocking {
        val here = message(uid = 1)
        val there = message(uid = 2, folder = archive)

        // in the target already, on its way already, another account's folder, a folder that is not there
        assertTrue(pending.leave(listOf(there), archive, notBefore = 0, now = 0).isEmpty())
        assertEquals(1, pending.leave(listOf(here), archive, notBefore = 0, now = 0).size)
        assertTrue(pending.leave(listOf(here), archive, notBefore = 0, now = 0).isEmpty())
        assertTrue(pending.leave(listOf(there), other, notBefore = 0, now = 0).isEmpty())
        assertTrue(pending.leave(listOf(there), 404, notBefore = 0, now = 0).isEmpty())
        assertEquals(1, pending.all().size)
    }

    @Test
    fun aMoveThatIsTakenBackLeavesNothingBehind() = runBlocking {
        val id = message(uid = 1)
        val op = pending.leave(listOf(id), archive, notBefore = 500, now = 100).single()

        assertTrue(pending.takeBack(op.id))

        assertEquals(listOf(1L), listed())
        assertEquals(2 to 3, counts(inbox))
        assertEquals(0 to 0, counts(archive))
        assertTrue(pending.all().isEmpty())
        // once is enough
        assertFalse(pending.takeBack(op.id))
    }

    @Test
    fun aMovedMessageArrivesWhereTheServerSaysItIs() = runBlocking {
        val id = message(uid = 1, seen = true)
        db.messages().keepBody(BodyEntity(id, "Merhaba", null, false), emptyList(), "Merhaba", MessageFts(id, "konu", "kisi", "merhaba"))
        val op = pending.leave(listOf(id), archive, notBefore = 0, now = 0).single()

        pending.settle(op.id, ChangeOutcome.Done(newUid = 41, newUidValidity = 9))

        val moved = stored(id)!!
        assertEquals(archive to 41L, moved.folderId to moved.uid)
        // with its text, which is not fetched again
        assertEquals(MessageEntity.BODY_KEPT, moved.bodyState)
        assertNotNull(db.messages().observeBody(id).first())
        assertEquals(listOf(41L), listed(archive))
        assertTrue(listed().isEmpty())
        // the folder it went to was never synced: it learns the stamp its new UID belongs to
        assertEquals(9L, db.folders().byId(archive)!!.uidValidity)
        assertTrue(pending.all().isEmpty())
    }

    @Test
    fun aMoveTheServerSaysNothingMoreOfTakesTheMessageOffTheDevice() = runBlocking {
        val told = message(uid = 1)
        val gone = message(uid = 2)
        val ops = pending.leave(listOf(told, gone), archive, notBefore = 0, now = 0)

        // moved, but the server did not say where to; and one that another device had moved already
        pending.settle(ops[0].id, ChangeOutcome.Done())
        pending.settle(ops[1].id, ChangeOutcome.Gone)

        assertNull(stored(told))
        assertNull(stored(gone))
        assertTrue(db.messages().search("konu").isEmpty())
    }

    @Test
    fun aMessageDoesNotArriveInAFolderWhoseUidsMeanSomethingElse() = runBlocking {
        val id = message(uid = 1)
        db.folders().setState(archive, uidValidity = 5, uidNext = 10, highestModSeq = 0)
        val op = pending.leave(listOf(id), archive, notBefore = 0, now = 0).single()

        pending.settle(op.id, ChangeOutcome.Done(newUid = 41, newUidValidity = 9))

        // the folder's next sync starts over and brings it
        assertNull(stored(id))
        assertEquals(5L, db.folders().byId(archive)!!.uidValidity)
    }

    @Test
    fun aMessageASyncAlreadyBroughtIsNotThereTwice() = runBlocking {
        val id = message(uid = 1)
        db.folders().setState(archive, uidValidity = 9, uidNext = 42, highestModSeq = 0)
        val synced = message(uid = 41, folder = archive)
        val op = pending.leave(listOf(id), archive, notBefore = 0, now = 0).single()

        pending.settle(op.id, ChangeOutcome.Done(newUid = 41, newUidValidity = 9))

        assertNull(stored(id))
        assertEquals(listOf(synced), db.messages().observeFolder(archive).first().map { it.id })
    }

    @Test
    fun aRefusedMoveBringsTheMessageBack() = runBlocking {
        val id = message(uid = 1)
        val op = pending.leave(listOf(id), archive, notBefore = 0, now = 0).single()

        pending.settle(op.id, ChangeOutcome.Refused("NO [TRYCREATE]"))

        assertEquals(listOf(1L), listed())
        assertEquals(2 to 3, counts(inbox))
        assertEquals(0 to 0, counts(archive))
    }

    @Test
    fun aRemovalWaitsAndThenTakesTheMessageAway() = runBlocking {
        val id = message(uid = 1)

        val op = pending.leave(listOf(id), targetFolderId = 0, notBefore = 500, now = 100).single()

        assertEquals("Expunge", op.kind)
        assertTrue(listed().isEmpty())
        assertEquals(1 to 2, counts(inbox))
        pending.bringForward(listOf(op.id), time = 200)
        assertEquals(listOf("Expunge"), pending.due(account, now = 200).map { it.kind })

        pending.settle(op.id, ChangeOutcome.Done())
        assertNull(stored(id))
    }

    @Test
    fun aMessageThatGoesTakesWhatWaitedForItAlong() = runBlocking {
        val id = message(uid = 1)
        pending.mark(id, seen = true, flagged = null, now = 100)
        pending.leave(listOf(id), archive, notBefore = 500, now = 100)

        // a sync finds it gone from the server: another device was quicker
        db.messages().delete(inbox, listOf(1L))

        assertTrue(pending.all().isEmpty())
    }

    @Test
    fun aMarkOnAMessageThatIsLeavingDoesNotCountTwice() = runBlocking {
        val id = message(uid = 1)
        pending.leave(listOf(id), archive, notBefore = 500, now = 100)
        assertEquals(1 to 2, counts(inbox))

        pending.mark(id, seen = true, flagged = null, now = 100)

        assertEquals(1 to 2, counts(inbox))
    }

    @Test
    fun theMarksThatWaitAreKnownByUid() = runBlocking {
        val first = message(uid = 1)
        val second = message(uid = 2, seen = true)
        pending.mark(first, seen = true, flagged = null, now = 100)
        pending.mark(second, seen = null, flagged = true, now = 100)
        pending.leave(listOf(second), archive, notBefore = 500, now = 100)

        val marks = pending.marksOf(inbox).map { Triple(it.uid, it.kind, it.value) }.toSet()

        assertEquals(setOf(Triple(1L, "Seen", true), Triple(2L, "Flagged", true)), marks)
    }
}

/** A message as a sync would have stored it. */
fun testMessage(accountId: Long, folderId: Long, uid: Long, subject: String = "konu $uid") = MessageEntity(
    accountId = accountId,
    folderId = folderId,
    uid = uid,
    globalId = "<$uid@example.org>",
    inReplyTo = "",
    refs = "",
    subject = subject,
    fromList = "Ayşe Yılmaz\tayse@example.org",
    toList = "Deniz Aksoy\tdeniz@example.org",
    ccList = "",
    bccList = "",
    replyToList = "",
    sentAt = 1_000 * uid,
    date = 1_000 * uid,
    size = 1_000,
    seen = false,
    flagged = false,
    answered = false,
    forwarded = false,
    draft = false,
    attachments = 0,
    preview = "",
    bodyState = MessageEntity.BODY_MISSING,
)
