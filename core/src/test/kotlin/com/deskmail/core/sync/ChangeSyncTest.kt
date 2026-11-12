package com.deskmail.core.sync

import com.deskmail.core.mail.MailException
import com.deskmail.core.mail.MessageFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** How the server is told what was done on the device, against a made-up server. */
class ChangeSyncTest {

    private val now: Instant = Instant.parse("2026-11-12T09:30:00Z")
    private val inbox = FakeFolder("INBOX")
    private val archive = FakeFolder("Arşiv")
    private val trash = FakeFolder("Çöp")
    private val account = FakeAccount(inbox, archive, trash)
    private val outcomes = LinkedHashMap<Long, ChangeOutcome>()
    private var nextId = 1L

    private fun change(uid: Long, kind: PendingChange.Kind, value: Boolean = false, target: String = "", folder: FakeFolder = inbox, uidValidity: Long = folder.uidValidity) =
        PendingChange(nextId++, folder.path, uidValidity, uid, kind, value, target)

    private fun push(vararg changes: PendingChange) = runBlocking {
        ChangeSync().push(account, changes.toList()) { change, outcome -> outcomes[change.id] = outcome }
    }

    @Test
    fun theSameMarkOnSeveralMessagesIsOneCommand() {
        val first = inbox.deliver("bir", now)
        val second = inbox.deliver("iki", now)
        val third = inbox.deliver("üç", now, flags = MessageFlags(seen = true, flagged = true))

        push(
            change(first, PendingChange.Kind.Seen, value = true),
            change(second, PendingChange.Kind.Seen, value = true),
            change(third, PendingChange.Kind.Seen, value = false),
            change(first, PendingChange.Kind.Flagged, value = true),
        )

        assertEquals(MessageFlags(seen = true, flagged = true), inbox.flags(first))
        assertEquals(MessageFlags(seen = true), inbox.flags(second))
        assertEquals(MessageFlags(seen = false, flagged = true), inbox.flags(third))
        assertEquals(listOf("mark Seen=true [1, 2]", "mark Seen=false [3]", "mark Flagged=true [1]"), inbox.asked)
        assertEquals(List(4) { ChangeOutcome.Done() }, outcomes.values.toList())
        // one folder, opened once
        assertEquals(1, account.opened)
    }

    @Test
    fun aMessageThatWasAnsweredOrForwardedIsMarkedSo() {
        val answered = inbox.deliver("soru", now)
        val forwarded = inbox.deliver("plan", now)

        push(change(answered, PendingChange.Kind.Answered, value = true), change(forwarded, PendingChange.Kind.Forwarded, value = true))

        assertEquals(MessageFlags(answered = true), inbox.flags(answered))
        assertEquals(MessageFlags(forwarded = true), inbox.flags(forwarded))
    }

    @Test
    fun aMoveSaysWhereTheMessageWent() {
        archive.deliver("eski", now)
        val uid = inbox.deliver("plan", now)

        push(change(uid, PendingChange.Kind.Move, target = "Arşiv"))

        assertTrue(inbox.subjects().isEmpty())
        assertEquals(listOf("eski", "plan"), archive.subjects())
        // the UID it has in the archive
        assertEquals(ChangeOutcome.Done(newUid = 2, newUidValidity = archive.uidValidity), outcomes.values.single())
    }

    @Test
    fun aServerThatDoesNotTellStillMoves() {
        account.uidPlus = false
        val uid = inbox.deliver("plan", now)

        push(change(uid, PendingChange.Kind.Move, target = "Arşiv"))

        assertEquals(listOf("plan"), archive.subjects())
        assertEquals(ChangeOutcome.Done(), outcomes.values.single())
    }

    @Test
    fun aMessageTakesItsMarksAlongWhenItMoves() {
        val uid = inbox.deliver("plan", now)

        // queued the other way round: the marks still go first
        push(change(uid, PendingChange.Kind.Move, target = "Arşiv"), change(uid, PendingChange.Kind.Seen, value = true))

        assertEquals(listOf("mark Seen=true [1]", "move [1] to Arşiv"), inbox.asked)
        val arrived = archive.open().uids(null).single()
        assertEquals(MessageFlags(seen = true), archive.flags(arrived))
    }

    @Test
    fun messagesGoToTheirOwnTargetsAndTheRemovedAreGone() {
        val first = inbox.deliver("arşive", now)
        val second = inbox.deliver("çöpe", now)
        val third = inbox.deliver("yok olacak", now)
        val kept = inbox.deliver("kalan", now)

        push(
            change(first, PendingChange.Kind.Move, target = "Arşiv"),
            change(second, PendingChange.Kind.Move, target = "Çöp"),
            change(third, PendingChange.Kind.Expunge),
        )

        assertEquals(listOf("kalan"), inbox.subjects())
        assertEquals(listOf("arşive"), archive.subjects())
        assertEquals(listOf("çöpe"), trash.subjects())
        assertEquals(listOf("move [1] to Arşiv", "move [2] to Çöp", "expunge [3]"), inbox.asked)
        assertEquals(MessageFlags(), inbox.flags(kept))
    }

    /** Another device got there first: there is nothing left to do it to. */
    @Test
    fun aMessageThatIsNoLongerThereIsGone() {
        val uid = inbox.deliver("plan", now)
        val other = inbox.deliver("öteki", now)
        inbox.expunge(uid)

        push(change(uid, PendingChange.Kind.Move, target = "Arşiv"), change(other, PendingChange.Kind.Move, target = "Arşiv"))

        assertEquals(listOf(ChangeOutcome.Gone, ChangeOutcome.Done(newUid = 1, newUidValidity = archive.uidValidity)), outcomes.values.toList())
        assertEquals(listOf("öteki"), archive.subjects())
    }

    /** The server renumbered the folder: the UID now names another message, which must not be touched. */
    @Test
    fun aChangeForUidsThatMeanSomethingElseNowIsNotCarriedOut() {
        val uid = inbox.deliver("plan", now)

        push(change(uid, PendingChange.Kind.Expunge, uidValidity = inbox.uidValidity - 1))

        assertEquals(ChangeOutcome.Gone, outcomes.values.single())
        assertEquals(listOf("plan"), inbox.subjects())
        assertTrue(inbox.asked.isEmpty())
    }

    @Test
    fun aTargetThatIsNotThereIsTheServersNo() {
        val uid = inbox.deliver("plan", now)
        val other = inbox.deliver("öteki", now)

        push(change(uid, PendingChange.Kind.Move, target = "Yok"), change(other, PendingChange.Kind.Seen, value = true))

        assertTrue(outcomes.getValue(1) is ChangeOutcome.Refused)
        // the rest is done all the same
        assertEquals(ChangeOutcome.Done(), outcomes.getValue(2))
        assertEquals(listOf("plan", "öteki"), inbox.subjects())
    }

    @Test
    fun aFolderThatCannotBeChangedRefusesWhatWasMeantForIt() {
        account.readOnly += "INBOX"
        val uid = inbox.deliver("plan", now)
        val archived = archive.deliver("eski", now)

        push(change(uid, PendingChange.Kind.Seen, value = true), change(archived, PendingChange.Kind.Flagged, value = true, folder = archive))

        assertTrue(outcomes.getValue(1) is ChangeOutcome.Refused)
        assertEquals(ChangeOutcome.Done(), outcomes.getValue(2))
        assertEquals(MessageFlags(flagged = true), archive.flags(archived))
    }

    /** What the server answered for is settled; the rest, and only the rest, waits for the next time. */
    @Test
    fun aConnectionThatBreaksLeavesTheRestForLater() {
        val first = inbox.deliver("bir", now)
        val second = inbox.deliver("iki", now)
        val marked = change(first, PendingChange.Kind.Seen, value = true)
        val moved = change(second, PendingChange.Kind.Move, target = "Arşiv")
        // the first question is answered, the second one breaks the connection
        runBlocking {
            try {
                ChangeSync().push(account, listOf(marked, moved)) { change, outcome ->
                    outcomes[change.id] = outcome
                    inbox.failNext = MailException(MailException.Kind.Network, "connection reset")
                }
                fail("a broken connection went unnoticed")
            } catch (e: MailException) {
                assertEquals(MailException.Kind.Network, e.kind)
            }
        }

        assertEquals(mapOf(marked.id to ChangeOutcome.Done()), outcomes)
        assertNull(outcomes[moved.id])
        assertEquals(listOf("bir", "iki"), inbox.subjects())
    }
}
