package com.deskmail.core.imap

import com.deskmail.core.mail.MailException
import jakarta.mail.Flags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Waiting on a folder for news of it (IMAP IDLE), against a real IMAP server inside the test. */
class ImapWatchServerTest {

    @get:Rule
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

            server.deliver("plan", monday)

            assertTrue("the new message was told of", told())
        } finally {
            watch.close()
        }
    }

    @Test
    fun aMessageThatIsMarkedOrRemovedSomewhereElseIsToldOfToo() {
        val uid = server.deliver("plan", monday)
        val watch = watch()
        try {
            Waiter(watch)
            // the wait is on once news of it comes
            server.deliver("breakfast", monday.plusSeconds(60))
            assertTrue(told())
            news.drainPermits()

            server.setFlag(uid, Flags.Flag.SEEN, true)
            assertTrue("the mark was told of", told())
            news.drainPermits()

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
            server.deliver("plan", monday)
            assertTrue(told())
            news.drainPermits()

            // what the app does every so often, before the server ends a wait that was left alone
            watch.refresh()
            eventually("the wait was ended to be begun anew") { waiter.rounds.get() >= 1 }
            watch.refresh()
            eventually("and once more") { waiter.rounds.get() >= 2 }
            assertFalse(waiter.ended.isDone)

            server.deliver("breakfast", monday.plusSeconds(60))
            assertTrue("mail is still told of", told())
        } finally {
            watch.close()
        }
    }

    @Test
    fun closingEndsTheWait() {
        val watch = watch()
        val waiter = Waiter(watch)
        server.deliver("plan", monday)
        assertTrue(told())

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
            server.deliver("plan", monday)
            assertTrue(told())

            server.stop()

            // whatever the library makes of the lost line, the one who waits is told
            assertNotNull(waiter.ended.get(WAIT_SECONDS, TimeUnit.SECONDS))
        } finally {
            watch.close()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 20L
    }
}
