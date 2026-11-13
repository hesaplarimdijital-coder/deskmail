package com.deskmail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.mail.MailAddress
import com.deskmail.core.mail.MessageFlags
import com.deskmail.core.mail.RemoteFolder
import com.deskmail.core.mail.RemoteMessage
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.testAccount
import com.deskmail.mail.FetchedFile
import com.deskmail.mail.MailView
import com.deskmail.mail.MessageTemplate
import com.deskmail.mail.SavedSearch
import com.deskmail.mail.SenderMarks
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

/**
 * What the user keeps for himself, on the real database: texts to put into a message, searches
 * kept as folders, and what was said of senders (VIPs, whose remote images are shown).
 */
@RunWith(AndroidJUnit4::class)
class ListsTest {

    private val log = AppLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: DeskMailDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var mail: LiveMail
    private lateinit var composer: LiveComposer
    private var account = 0L

    private val words = ComposeWords(
        wrote = { date, who -> "$date tarihinde $who yazdı:" },
        forwarded = "---------- İletilen ileti ----------",
        from = "Kimden",
        sent = "Tarih",
        subject = "Konu",
        to = "Kime",
        cc = "Bilgi",
        date = { it.toString() },
    )

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = DeskMailDatabase.inMemory(context)
        val accounts = AccountStore(db, testBox(), log)
        val sync = MailSync(db, accounts, log, scope)
        store = RoomSyncStore(db)
        mail = LiveMail(db, sync, scope, File(context.cacheDir, "lists-" + System.nanoTime()), paceMs = 0, tellAfterMs = 0)
        composer = LiveComposer(
            db = db,
            sync = sync,
            scope = scope,
            files = File(context.cacheDir, "lists-outbox-" + System.nanoTime()),
            scheduler = {},
            fetchFile = { FetchedFile.Gone },
            words = { words },
        )
        account = runBlocking { db.accounts().insert(testAccount("deniz@example.org")) }
    }

    @After
    fun close() {
        endAndWait(scope)
        db.close()
    }

    /** The account's folders, as the server lists them all at once: their ids, by path. */
    private suspend fun folders(vararg folders: Pair<String, FolderRole>): Map<String, Long> =
        store.saveFolders(account, folders.map { (path, role) -> RemoteFolder(path, '/', emptySet(), selectable = true, role = role) }).associate { it.path to it.id }

    private fun message(uid: Long, subject: String, name: String, address: String) = RemoteMessage(
        uid = uid,
        globalId = "<$uid@example.org>",
        inReplyTo = "",
        references = "",
        subject = subject,
        from = listOf(MailAddress(name, address)),
        to = listOf(MailAddress("Deniz Aksoy", "deniz@example.org")),
        cc = emptyList(),
        bcc = emptyList(),
        replyTo = emptyList(),
        sentAt = Instant.parse("2026-11-10T08:00:00Z").plusSeconds(uid),
        receivedAt = Instant.parse("2026-11-10T08:00:00Z").plusSeconds(uid),
        size = 2_000,
        flags = MessageFlags(),
        attachments = 0,
    )

    @Test
    fun textsAreKeptInTheirOrderChangedAndRemoved() = runBlocking {
        val greeting = composer.saveTemplate(0, "  Selam\n", "Merhaba,\r\n\r\nNasılsın?")
        val thanks = composer.saveTemplate(0, "Teşekkür", "Teşekkürler.")

        eventually("both are there") { composer.templates.size == 2 }
        assertEquals(
            listOf(MessageTemplate(greeting, "Selam", "Merhaba,\n\nNasılsın?"), MessageTemplate(thanks, "Teşekkür", "Teşekkürler.")),
            composer.templates,
        )

        assertEquals(greeting, composer.saveTemplate(greeting, "Selam", "Merhaba!"))
        eventually("it changed in its place") { composer.templates.first().body == "Merhaba!" }

        composer.removeTemplate(greeting)
        eventually("it is gone") { composer.templates.map { it.id } == listOf(thanks) }
    }

    /** A saved search is a folder of its own: what the search finds now, as the search now stands. */
    @Test
    fun aSavedSearchListsWhatItsSearchFinds() = runBlocking {
        val inbox = folders("INBOX" to FolderRole.Inbox).getValue("INBOX")
        store.insert(
            inbox,
            listOf(
                message(1, "Kasım faturası", "Elektrik Dağıtım", "fatura@elektrik.example"),
                message(2, "Cumartesi kahvaltı?", "Ayşe Yılmaz", "ayse@posta.example"),
                message(3, "Ekim faturası", "Doğalgaz", "fatura@gaz.example"),
            ),
        )

        val id = mail.saveSearch(0, "", "fatura")
        eventually("it is there") { mail.savedSearches.isNotEmpty() }
        // without a name it is called by what it looks for
        assertEquals(listOf(SavedSearch(id, "fatura", "fatura")), mail.savedSearches)
        assertEquals(listOf("Ekim faturası", "Kasım faturası"), withTimeout(15_000) { mail.observe(MailView.Saved(id)).first { it.size == 2 } }.map { it.subject }.sorted())

        // changed, the folder follows its search
        mail.saveSearch(id, "Kahvaltı", "kahvaltı")
        assertEquals(listOf("Cumartesi kahvaltı?"), withTimeout(15_000) { mail.observe(MailView.Saved(id)).first { it.size == 1 } }.map { it.subject })
        eventually("renamed") { mail.savedSearches.single().name == "Kahvaltı" }

        // removed, it finds nothing
        mail.removeSearch(id)
        eventually("it is gone") { mail.savedSearches.isEmpty() }
        assertTrue(mail.observe(MailView.Saved(id)).first().isEmpty())
    }

    /** The VIPs' folder: the mail of the senders called VIPs, in the inboxes; whatever capitals their addresses come in. */
    @Test
    fun theVipsMailHasAFolderOfItsOwn() = runBlocking {
        val ids = folders("INBOX" to FolderRole.Inbox, "Archive" to FolderRole.Archive)
        val inbox = ids.getValue("INBOX")
        val archive = ids.getValue("Archive")
        store.insert(
            inbox,
            listOf(
                message(1, "Proje planı", "Mehmet Demir", "Mehmet.Demir@isyeri.example"),
                message(2, "Bülten", "Sahaf", "bulten@sahaf.example"),
                // the address with a "_" in it is not a pattern: "mehmetXdemir" is nobody's VIP
                message(3, "Benzer adres", "Başkası", "mehmet_demir@isyeri.example"),
            ),
        )
        store.insert(archive, listOf(message(4, "Eski plan", "Mehmet Demir", "mehmet.demir@isyeri.example")))

        mail.markSender(" Mehmet.Demir@ISYERI.example ", SenderMarks(vip = true))

        eventually("he is a VIP") { mail.sender("mehmet.demir@isyeri.example").vip }
        assertEquals(mapOf("mehmet.demir@isyeri.example" to SenderMarks(vip = true)), mail.senders)
        assertEquals(listOf("Proje planı"), withTimeout(15_000) { mail.observe(MailView.Vip).first { it.isNotEmpty() } }.map { it.subject })

        // his remote images as well; then no longer a VIP, but his images still shown
        mail.markSender("mehmet.demir@isyeri.example", SenderMarks(vip = true, images = true))
        mail.markSender("mehmet.demir@isyeri.example", SenderMarks(images = true))
        eventually("no longer a VIP") { !mail.sender("mehmet.demir@isyeri.example").vip }
        assertTrue(mail.sender("mehmet.demir@isyeri.example").images)
        assertTrue(withTimeout(15_000) { mail.observe(MailView.Vip).first { it.isEmpty() } }.isEmpty())

        // nothing said of him any more: he is not kept
        mail.markSender("mehmet.demir@isyeri.example", SenderMarks())
        eventually("forgotten") { mail.senders.isEmpty() }
        assertTrue(db.lists().senders().isEmpty())
    }
}
