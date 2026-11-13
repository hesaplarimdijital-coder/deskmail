package com.deskmail.core.imap

import com.deskmail.core.mail.MailException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Waiting on a folder for news of it (IMAP IDLE), against a real IMAP server inside the test. */
class ImapWatchServerTest {

    /** A wait that nothing ends would keep the whole build waiting: it fails instead. */
    @get:Rule(order = 0)
    val limit: Timeout = Timeout.seconds(120)

    @get:Rule(order = 1)
    val server = TestMailServer()

    private val monday = Instant.parse("2026-11-09T08:00:00Z")

    /** Each piece of news the server told of. */
    private val news = Semaphore(0)

    private fun watch(): ImapWatch {
        val watch = ImapWatch.open(server.imap, server.credentials, "INBOX") { news.release() }
        assertNotNull("the test server can be waited on", watch)
        return watch!!
    }

    /** Waits on a thread of its own, as the app does, and begins anew whenever the wait is ended. */
    private class Waiter(watch: ImapWatch) {
        /** How many waits were ended to be begun anew. */
        val rounds = AtomicInteger()

        /** What ended the waiting for good. */
        val ended = CompletableFuture<MailException>()

        init {
            thread(name = "test-wait", isDaemon = true) {
                try {
                    while (true) {
                        watch.await()
                        rounds.incrementAndGet()
                    }
                } catch (e: MailException) {
                    ended.complete(e)
                } catch (e: Throwable) {
                    ended.completeExceptionally(e)
                }
            }
        }
    }

    private fun told(): Boolean = news.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)

    /**
     * Has mail come until whoever waits is told of it; the wait is on from then.
     *
     * One message may not be enough: this server tells of mail that comes while it is setting a
     * wait up only when that wait ends. (A real server says so at once; and the app looks at an
     * inbox whenever it begins to wait on it.) The next message is told of with it.
     */
    private fun mailComesAndIsToldOf() {
        repeat(TRIES) { n ->
            server.deliver("newsletter", monday.plusSeconds(n.toLong()))
            if (news.tryAcquire(1, TimeUnit.SECONDS)) {
                // whatever else was said of these messages has been said by now
                Thread.sleep(100)
                news.drainPermits()
                return
            }
        }
        fail("mail that came was not told of")
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < until) {
            if (check()) return
            Thread.sleep(20)
        }
        fail(what)
    }

    @Test
    fun aMessageThatComesIsToldOfWhileTheFolderIsWaitedOn() {
        val watch = watch()
        try {
            Waiter(watch)

            mailComesAndIsToldOf()
        } finally {
            watch.close()
        }
    }

    /**
     * A message that leaves the folder is news as well. (So is one that is marked: Gmail and
     * Outlook say so on the line. The server of this test says nothing of marks to whoever waits,
     * so that cannot be shown here.)
     */
    @Test
    fun aMessageThatIsRemovedSomewhereElseIsToldOfToo() {
        val uid = server.deliver("plan", monday)
        val watch = watch()
        try {
            Waiter(watch)
            mailComesAndIsToldOf()

            server.expunge(uid)

            assertTrue("the removal was told of", told())
        } finally {
            watch.close()
        }
    }

    @Test
    fun aWaitThatIsBegunAnewGoesOnTellingOfMail() {
        val watch = watch()
        try {
            val waiter = Waiter(watch)
            mailComesAndIsToldOf()

            // what the app does every so often, before the server ends a wait that was left alone
            watch.refresh()
            eventually("the wait was ended to be begun anew") { waiter.rounds.get() >= 1 }
            watch.refresh()
            eventually("and once more") { waiter.rounds.get() >= 2 }
            assertFalse(waiter.ended.isDone)

            mailComesAndIsToldOf()
        } finally {
            watch.close()
        }
    }

    @Test
    fun closingEndsTheWait() {
        val watch = watch()
        val waiter = Waiter(watch)
        mailComesAndIsToldOf()

        watch.close()

        assertEquals(MailException.Kind.Network, waiter.ended.get(WAIT_SECONDS, TimeUnit.SECONDS).kind)
        // and it stays ended: nothing is asked of a closed watch
        try {
            watch.refresh()
            fail("a closed watch was begun anew")
        } catch (e: MailException) {
            assertEquals(MailException.Kind.Network, e.kind)
        }
        // closing twice is closing once
        watch.close()
    }

    @Test
    fun aServerThatGoesAwayEndsTheWaitWithAFailure() {
        val watch = watch()
        try {
            val waiter = Waiter(watch)
            mailComesAndIsToldOf()

            server.stop()

            // whatever the library makes of the lost line, the one who waits is told
            assertNotNull(waiter.ended.get(WAIT_SECONDS, TimeUnit.SECONDS))
        } finally {
            watch.close()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 20L
        const val TRIES = 10
    }
}
