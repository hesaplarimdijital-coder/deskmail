package com.deskmail.watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderWatch
import com.deskmail.core.mail.MailException
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.MailSettings
import com.deskmail.data.AccountStore
import com.deskmail.data.StuckGuard
import com.deskmail.data.accountState
import com.deskmail.data.endAndWait
import com.deskmail.data.eventually
import com.deskmail.data.testBox
import com.deskmail.db.DeskMailDatabase
import com.deskmail.mail.AccountState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Watching the accounts' inboxes, with lines the test holds in its hand: when a line is made,
 * what follows news, what happens when a line is lost, cannot be made or is refused, and how the
 * watching follows the accounts. (That a real server's news arrives is WatchDeliveryTest's.)
 */
@RunWith(AndroidJUnit4::class)
class MailWatchTest {

    /** A line that waits until the test says otherwise. */
    private class Line(val path: String, val news: () -> Unit) : FolderWatch {
        /** What ends a wait: null begins it anew, an exception ends it for good. */
        private val words = LinkedBlockingQueue<Result<Unit>>()

        @Volatile
        var closed = false
        val refreshes = AtomicInteger()

        /** The server no longer answers when the wait is begun anew. */
        @Volatile
        var silent = false

        override fun await() {
            if (closed) throw lost("closed")
            words.take().getOrThrow()
        }

        override fun refresh() {
            if (silent) throw lost("no answer")
            refreshes.incrementAndGet()
            words.put(Result.success(Unit))
        }

        override fun close() {
            closed = true
            words.put(Result.failure(lost("closed")))
        }

        /** The line goes dead, as the one who waits finds out. */
        fun lose() {
            words.put(Result.failure(lost("the line was lost")))
        }

        private fun lost(text: String) = MailException(MailException.Kind.Network, text)
    }

    @get:Rule
    val guard = StuckGuard()

    private val log = AppLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: DeskMailDatabase
    private lateinit var accounts: AccountStore

    /** Every line that was made, and when each try at making one began. */
    private val lines = CopyOnWriteArrayList<Line>()
    private val tries = CopyOnWriteArrayList<Long>()

    /** The accounts that were synced because of news, in order; and how often a server stirred. */
    private val synced = CopyOnWriteArrayList<Long>()
    private val stirs = AtomicInteger()

    /** What trying to make a line comes to, while it is set. */
    @Volatile
    private var failure: MailException? = null

    /** The server has no way of being waited on. */
    @Volatile
    private var deaf = false

    private var watch: MailWatch? = null

    @Before
    fun open() {
        db = DeskMailDatabase.inMemory(context)
        accounts = AccountStore(db, testBox(), log)
    }

    @After
    fun close() {
        // the lines first: a wait ends only when its line does
        watch?.let { runBlocking { it.stop() } }
        lines.forEach { it.close() }
        endAndWait(scope)
        db.close()
    }

    private fun watch(firstRetryMs: Long = 40, longestRetryMs: Long = 160): MailWatch =
        MailWatch(
            db, accounts, log, scope,
            onNews = { synced += it },
            onStir = { stirs.incrementAndGet() },
            open = { _, _, path, news ->
                tries += System.nanoTime()
                failure?.let { throw it }
                if (deaf) null else Line(path, news).also { lines += it }
            },
            firstRetryMs = firstRetryMs,
            longestRetryMs = longestRetryMs,
        ).also { watch = it }

    private suspend fun account(address: String = "deniz@example.org"): Long {
        val server = ServerAddress("mail.example.org", 993, Security.Tls)
        return accounts.add(address, "Posta", "", MailSettings(server, server.copy(port = 465), "deniz", AuthKind.Password), "a-test-password")
    }

    private fun failure(kind: MailException.Kind) = MailException(kind, "the test says no")

    @Test
    fun anAccountsInboxIsWaitedOnAndLookedAtOnceTheLineIsMade() = runBlocking {
        val id = account()
        val watch = watch()

        watch.arrange()

        eventually("the inbox is waited on") { watch.watched.value == setOf(id) }
        assertEquals("INBOX", lines.single().path)
        // what came while nobody waited is fetched now
        eventually("the account is synced once") { synced.toList() == listOf(id) }
        assertTrue(log.lines().any { it.contains("account $id: waiting on INBOX") })
    }

    @Test
    fun newsIsOneMoreSyncAndKeepsTheDeviceAwakeFromTheMomentItComes() = runBlocking {
        val id = account()
        val watch = watch()
        watch.arrange()
        eventually("the first look") { synced.size == 1 }
        assertEquals(0, stirs.get())

        lines.single().news()

        eventually("the news is followed") { synced.toList() == listOf(id, id) }
        assertEquals(1, stirs.get())
    }

    @Test
    fun aWaitIsBegunAnewWithoutLookingAtTheMailAgain() = runBlocking {
        account()
        val watch = watch()
        watch.arrange()
        eventually("the first look") { synced.size == 1 }

        watch.refresh()
        watch.refresh()

        assertEquals(2, lines.single().refreshes.get())
        // the same line goes on, and nothing was fetched for it
        assertFalse(lines.single().closed)
        assertEquals(1, synced.size)
        assertTrue(log.lines().count { it.contains("the wait was begun anew") } == 2)
    }

    @Test
    fun aLineThatIsLostIsMadeAnewAfterAPause() = runBlocking {
        val id = account()
        val watch = watch()
        watch.arrange()
        eventually("the first line") { lines.size == 1 && watch.watched.value == setOf(id) }
        // looked at once the line was made (a line that is lost before that takes its look with it: the next line's will do)
        eventually("the first look") { synced.size == 1 }

        lines[0].lose()

        eventually("a new line") { lines.size == 2 && watch.watched.value == setOf(id) }
        assertTrue(lines[0].closed)
        // and the inbox is looked at again: mail may have come in between
        eventually("the second look") { synced.size == 2 }
        assertTrue(log.lines().any { it.contains("the wait on INBOX ended: the line was lost") })
    }

    @Test
    fun aLineThatCannotBeMadeIsTriedAgainWithGrowingPauses() = runBlocking {
        account()
        failure = failure(MailException.Kind.Network)
        val watch = watch(firstRetryMs = 60, longestRetryMs = 120)

        watch.arrange()

        eventually("four tries") { tries.size >= 4 }
        val gaps = tries.take(4).zipWithNext { a, b -> (b - a) / 1_000_000 }
        // 60 ms, then twice that, then no longer than the longest
        assertTrue("$gaps", gaps[0] >= 55 && gaps[1] >= 115 && gaps[2] >= 115)
        assertTrue(watch.watched.value.isEmpty())

        // the server is back: the next try holds
        failure = null
        eventually("the line is made") { lines.size == 1 }
    }

    @Test
    fun aRefusedSignInEndsTheWatchingUntilTheAccountIsReconnected() = runBlocking {
        val id = account()
        failure = failure(MailException.Kind.Authentication)
        val watch = watch()

        watch.arrange()

        eventually("the account needs its owner") { runBlocking { accounts.byId(id)!!.accountState() } == AccountState.NeedsLogin }
        // it is not tried again by itself
        Thread.sleep(300)
        assertEquals(1, tries.size)
        // nor because the watching is looked over
        watch.arrange()
        Thread.sleep(100)
        assertEquals(1, tries.size)

        // reconnected: watched again
        failure = null
        accounts.setState(id, AccountState.Ok)
        watch.arrange()
        eventually("the inbox is waited on") { watch.watched.value == setOf(id) }
    }

    @Test
    fun aServerThatCannotBeWaitedOnIsLeftAlone() = runBlocking {
        val id = account()
        deaf = true
        val watch = watch()

        watch.arrange()

        eventually("it was tried") { tries.size == 1 }
        Thread.sleep(300)
        assertEquals(1, tries.size)
        assertTrue(watch.watched.value.isEmpty())
        assertTrue(log.lines().any { it.contains("account $id: its server cannot be waited on") })
        // the account itself is in order: its mail is looked for from time to time
        assertEquals(AccountState.Ok, accounts.byId(id)!!.accountState())
    }

    @Test
    fun aServerThatNoLongerAnswersGetsANewLineAtOnce() = runBlocking {
        val id = account()
        // a pause nobody would wait out in a test
        val watch = watch(firstRetryMs = 60_000, longestRetryMs = 60_000)
        watch.arrange()
        eventually("the first line") { watch.watched.value == setOf(id) }

        lines[0].silent = true
        watch.refresh()

        eventually("a new line") { lines.size == 2 && watch.watched.value == setOf(id) }
        assertTrue(lines[0].closed)
    }

    @Test
    fun aChangeOfNetworkMakesTheLinesAnewAtOnce() = runBlocking {
        val id = account()
        val watch = watch(firstRetryMs = 60_000, longestRetryMs = 60_000)
        watch.arrange()
        eventually("the first line") { watch.watched.value == setOf(id) }

        watch.networkChanged()

        eventually("a new line") { lines.size == 2 && watch.watched.value == setOf(id) }
        assertTrue(lines[0].closed)
        assertTrue(log.lines().any { it.contains("the network changed") })
    }

    @Test
    fun aChangeOfNetworkEndsThePauseBeforeTheNextTry() = runBlocking {
        account()
        failure = failure(MailException.Kind.Network)
        val watch = watch(firstRetryMs = 60_000, longestRetryMs = 60_000)
        watch.arrange()
        eventually("the first try") { tries.size == 1 }

        failure = null
        watch.networkChanged()

        eventually("tried again, and it holds") { lines.size == 1 }
    }

    @Test
    fun theWatchingFollowsTheAccounts() = runBlocking {
        val first = account()
        val watch = watch()
        watch.arrange()
        eventually("the first account") { watch.watched.value == setOf(first) }

        // another account is added
        val second = account("ikinci@example.org")
        watch.arrange()
        eventually("both") { watch.watched.value == setOf(first, second) }
        // the line that was there is left alone
        assertFalse(lines[0].closed)

        // the first is removed
        accounts.remove(first)
        watch.arrange()
        eventually("the second only") { watch.watched.value == setOf(second) }
        assertTrue(lines[0].closed)
    }

    @Test
    fun stoppingEndsEveryWaitAndNothingIsTriedAfterwards() = runBlocking {
        account()
        val watch = watch()
        watch.arrange()
        eventually("the line") { watch.watched.value.size == 1 }

        watch.stop()

        eventually("nothing is waited on") { watch.watched.value.isEmpty() && lines.single().closed }
        Thread.sleep(200)
        assertEquals(1, tries.size)
    }
}
