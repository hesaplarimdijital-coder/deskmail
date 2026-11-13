package com.deskmail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.testAccount
import com.deskmail.db.testFolder
import com.deskmail.db.testMessage
import com.deskmail.mail.AwaitOutcome
import com.deskmail.mail.MailView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Waiting for an answer to a message of the user's, asked for on a message under Sent: what is
 * waited for, the list of it, and what cannot be waited for.
 */
@RunWith(AndroidJUnit4::class)
class AwaitingTest {

    private val log = AppLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = 1_800_000_000_000L
    private val changed = AtomicInteger()
    private lateinit var db: DeskMailDatabase
    private lateinit var mail: LiveMail
    private var account = 0L
    private var inbox = 0L
    private var sent = 0L

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = DeskMailDatabase.inMemory(context)
        val sync = MailSync(db, AccountStore(db, testBox(), log), log, scope)
        mail = LiveMail(
            db, sync, scope, File(context.cacheDir, "awaiting-" + System.nanoTime()),
            now = { now }, paceMs = 0, tellAfterMs = 0, awaitingChanged = { changed.incrementAndGet() },
        )
        runBlocking {
            account = db.accounts().insert(testAccount("deniz@example.org"))
            inbox = db.folders().insert(testFolder(account, "INBOX", role = "Inbox"))
            sent = db.folders().insert(testFolder(account, "Sent", role = "Sent", position = 1))
        }
    }

    @After
    fun close() {
        endAndWait(scope)
        db.close()
    }

    /** A message the user sent, under Sent: its id. */
    private suspend fun sentMessage(uid: Long, subject: String, globalId: String = "<$uid@example.org>"): Long =
        db.messages().insert(
            listOf(
                testMessage(account, sent, uid, subject).copy(
                    globalId = globalId,
                    fromList = "Deniz Aksoy\tdeniz@example.org",
                    toList = "Mehmet Demir\tmehmet@example.org",
                    ccList = "Elif Şahin\telif@example.org",
                    seen = true,
                ),
            ),
        ).single()

    @Test
    fun aSentMessageIsWaitedOnAndListedUntilTheUserStops() = runBlocking {
        val plan = sentMessage(1, "Proje planı")

        assertEquals(AwaitOutcome.Waiting, mail.awaitReply(plan, 3))

        eventually("it is waited for") { mail.awaited.isNotEmpty() }
        val waited = mail.awaited.single()
        assertEquals("<1@example.org>", waited.messageId)
        assertEquals("Proje planı", waited.subject)
        assertEquals("Mehmet Demir, Elif Şahin", waited.recipients)
        // from now, whenever it was sent
        assertEquals(Instant.ofEpochMilli(now + 3 * DAY_MS), waited.dueAt)
        assertEquals(1, changed.get())
        // the reader finds it by the message
        val message = withTimeout(15_000) { mail.observeMessage(plan).first { it != null } }!!
        assertEquals(waited, mail.awaitedOf(message))
        assertEquals(listOf("Proje planı"), withTimeout(15_000) { mail.observe(MailView.Awaiting).first { it.isNotEmpty() } }.map { it.subject })

        // asked again: the new time
        mail.awaitReply(plan, 7)
        eventually("the new time") { mail.awaited.singleOrNull()?.dueAt == Instant.ofEpochMilli(now + 7 * DAY_MS) }

        mail.stopAwaiting(waited.id)
        eventually("no longer waited for") { mail.awaited.isEmpty() }
        assertTrue(withTimeout(15_000) { mail.observe(MailView.Awaiting).first { it.isEmpty() } }.isEmpty())
        assertEquals(3, changed.get())
    }

    /** The list is of the copies under Sent, the one due first on top. */
    @Test
    fun theOneDueFirstIsOnTop() = runBlocking {
        val first = sentMessage(1, "Bir hafta")
        val second = sentMessage(2, "Bir gün")
        mail.awaitReply(first, 7)
        mail.awaitReply(second, 1)

        val listed = withTimeout(15_000) { mail.observe(MailView.Awaiting).first { it.size == 2 } }

        assertEquals(listOf("Bir gün", "Bir hafta"), listed.map { it.subject })
    }

    @Test
    fun anAnsweredMessageIsNotWaitedOn() = runBlocking {
        val plan = sentMessage(1, "Proje planı")
        db.messages().insert(
            listOf(
                testMessage(account, inbox, 2, "Re: Proje planı").copy(inReplyTo = "<1@example.org>", fromList = "Mehmet Demir\tmehmet@example.org"),
            ),
        )

        assertEquals(AwaitOutcome.Answered, mail.awaitReply(plan, 3))
        assertTrue(db.awaiting().all().isEmpty())
        assertEquals(0, changed.get())
    }

    /** An answer names the message by its Message-ID: one without it could never be told answered. */
    @Test
    fun aMessageWithoutMessageIdCannotBeWaitedOn() = runBlocking {
        val bare = sentMessage(1, "Kimliksiz", globalId = "")

        assertEquals(AwaitOutcome.Gone, mail.awaitReply(bare, 3))
        assertEquals(AwaitOutcome.Gone, mail.awaitReply(999, 3))
        assertTrue(db.awaiting().all().isEmpty())
    }
}
