package com.deskmail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.core.sync.SnoozeList
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.SnoozeEntity
import com.deskmail.mail.MailUndo
import com.deskmail.mail.MailView
import jakarta.mail.Flags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Snoozing against a mail server inside the test: the Snoozed folder made on the server, the list
 * that every device reads, a second device that learns of it, and the message brought back.
 */
@RunWith(AndroidJUnit4::class)
class SnoozeSyncTest {

    @get:Rule
    val server = TestImapServer()

    private val start: Instant = Instant.parse("2026-11-13T09:00:00Z")
    private val clock = AtomicLong(start.toEpochMilli())
    private val log = AppLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scheduled = CopyOnWriteArrayList<Long>()

    /** One of the user's devices: its own database and sync against the same server. */
    private inner class Device(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db: DeskMailDatabase = DeskMailDatabase.inMemory(context)
        val accounts = AccountStore(db, testBox(), log)
        val sync = MailSync(db, accounts, log, scope, clock = { Instant.ofEpochMilli(clock.get()) })
        val root = File(context.cacheDir, "snooze-$name-" + System.nanoTime())
        val mail = LiveMail(db, sync, scope, File(root, "files"), now = { clock.get() }, paceMs = 0, tellAfterMs = 0)
        val snoozes = SnoozeSync(db, sync, log, File(root, "work"), schedule = { scheduled += it }, now = { clock.get() })
        var account = 0L

        suspend fun signIn() {
            account = accounts.add(TestImapServer.ADDRESS, "Posta", "", server.settings, TestImapServer.PASSWORD)
            assertEquals(MailSync.Outcome.Done, sync.account(account))
        }

        suspend fun message(subject: String): Long =
            withTimeout(15_000) { mail.observe(MailView.Inbox).first { list -> list.any { it.subject == subject } } }.first { it.subject == subject }.id

        fun close() = db.close()
    }

    private lateinit var phone: Device

    @Before
    fun open() {
        phone = Device("phone")
    }

    @After
    fun close() {
        endAndWait(scope)
        phone.close()
    }

    /** What the server's list says, by Message-ID: when each comes back. */
    private fun serverList(): Map<String, Instant> {
        val list = server.uids(SnoozeList.FOLDER).mapNotNull { server.message(it, SnoozeList.FOLDER) }.single { it.messageID.startsWith(SnoozeList.ID_PREFIX) }
        return SnoozeList.read(list.sentText()).associate { it.messageId to it.wakeAt }
    }

    @Test
    fun aSnoozedMessageWaitsOnTheServerAndComesBackUnread() = runBlocking {
        server.deliver("Kasım faturası", "Ödenecek tutar: 893,52 TL", start.minusSeconds(3600), seen = true)
        phone.signIn()
        val bill = phone.message("Kasım faturası")
        val wakeAt = start.plusSeconds(3 * 3600)

        val undo = phone.mail.snooze(listOf(bill), wakeAt)

        assertNotNull(undo)
        assertEquals(MailUndo.Action.Snoozed, undo!!.action)
        // the folder was made on the server, and the account knows it for what it is
        assertTrue(server.uids(SnoozeList.FOLDER).isEmpty())
        // it stands once the time to take it back is over: the server is told, and the list follows
        phone.mail.commit(undo)
        eventually("moved on the server") { server.uids(SnoozeList.FOLDER).size == 1 }
        assertEquals(0, phone.snoozes.run(phone.account))
        assertEquals(mapOf("<test-1@example.org>" to wakeAt), serverList())
        assertTrue(server.uids("INBOX").isEmpty())
        val row = phone.db.snoozes().ofAccount(phone.account).single()
        assertEquals(SnoozeEntity.LISTED, row.state)
        assertEquals("INBOX", row.folder)
        // the next look is arranged for its time
        assertTrue(wakeAt.toEpochMilli() in scheduled)

        // its time comes: back in the inbox, unread, and off the list
        clock.set(wakeAt.plusSeconds(60).toEpochMilli())
        assertEquals(1, phone.snoozes.run(phone.account, force = true))
        val back = server.uids("INBOX").single()
        assertFalse(server.has(back, Flags.Flag.SEEN))
        assertTrue(serverList().isEmpty())
        assertTrue(phone.db.snoozes().ofAccount(phone.account).isEmpty())
    }

    /** The list is the server's: another device learns of the snooze, and brings the message back when it is first. */
    @Test
    fun anotherDeviceLearnsOfTheSnoozeFromTheServer() = runBlocking {
        server.deliver("Proje planı", "Üçüncü sürüm ekte.", start.minusSeconds(3600))
        phone.signIn()
        val wakeAt = start.plusSeconds(24 * 3600)
        val undo = phone.mail.snooze(listOf(phone.message("Proje planı")), wakeAt)!!
        phone.mail.commit(undo)
        eventually("moved on the server") { server.uids(SnoozeList.FOLDER).size == 1 }
        phone.snoozes.run(phone.account)

        val tablet = Device("tablet")
        try {
            tablet.signIn()
            assertEquals(0, tablet.snoozes.run(tablet.account))
            assertEquals(wakeAt, tablet.mail.snoozes.values.singleOrNull() ?: eventuallyValue { tablet.mail.snoozes.values.singleOrNull() })

            // the tablet looks first when the time has come
            clock.set(wakeAt.plusSeconds(60).toEpochMilli())
            assertEquals(1, tablet.snoozes.run(tablet.account, force = true))
            // the phone finds nothing left to do, and forgets it
            assertEquals(0, phone.snoozes.run(phone.account, force = true))
            assertTrue(phone.db.snoozes().ofAccount(phone.account).isEmpty())
            assertEquals(1, server.uids("INBOX").size)
        } finally {
            tablet.close()
        }
    }

    /** Brought back by hand before its time: it goes home, and off the list. */
    @Test
    fun aSnoozedMessageCanBeBroughtBackNow() = runBlocking {
        server.createFolder("Faturalar")
        server.deliver("Ekim faturası", "Ödendi.", start.minusSeconds(7200), folder = "Faturalar")
        phone.signIn()
        val folder = phone.db.folders().ofAccount(phone.account).first { it.path == "Faturalar" }
        assertEquals(MailSync.Outcome.Done, phone.sync.folder(folder.id))
        val bill = withTimeout(15_000) { phone.mail.observe(MailView.Folder(folder.id)).first { it.isNotEmpty() } }.single().id
        phone.mail.commit(phone.mail.snooze(listOf(bill), start.plusSeconds(3600))!!)
        eventually("moved on the server") { server.uids(SnoozeList.FOLDER).size == 1 }
        phone.snoozes.run(phone.account)
        val snoozed = phone.db.folders().ofAccount(phone.account).first { it.path == SnoozeList.FOLDER }
        assertEquals(MailSync.Outcome.Done, phone.sync.folder(snoozed.id))
        val there = withTimeout(15_000) { phone.mail.observe(MailView.Snoozed).first { it.isNotEmpty() } }.single()

        phone.mail.commit(phone.mail.unsnooze(listOf(there.id))!!)

        eventually("home again") { server.uids("Faturalar").size == 1 }
        assertEquals(0, phone.snoozes.run(phone.account))
        assertTrue(serverList().isEmpty())
        assertTrue(phone.db.snoozes().ofAccount(phone.account).isEmpty())
    }

    private fun <T : Any> eventuallyValue(read: () -> T?): T {
        var value: T? = null
        eventually("it is there") { read().also { value = it } != null }
        return value!!
    }
}
