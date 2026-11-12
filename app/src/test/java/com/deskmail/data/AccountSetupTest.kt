package com.deskmail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.MailException
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.Autoconfig
import com.deskmail.core.provider.ProviderTable
import com.deskmail.db.DeskMailDatabase
import com.deskmail.mail.AccountDraft
import com.deskmail.mail.SetupPlan
import com.deskmail.mail.SetupResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.ProviderException
import java.time.Instant

/** Adding an account: where its servers are, whether the server takes the sign-in, and what is kept then. */
@RunWith(AndroidJUnit4::class)
class AccountSetupTest {

    @get:Rule
    val server = TestImapServer()

    private val log = AppLog()
    private val held = HeldDispatcher()
    private val background = CoroutineScope(SupervisorJob() + held)
    private val asked = ArrayList<String>()
    private lateinit var db: DeskMailDatabase
    private lateinit var accounts: AccountStore
    private lateinit var sync: MailSync
    private lateinit var setup: AccountSetup

    /** A provider database that knows one domain. */
    private val database = """
        <clientConfig version="1.1"><emailProvider id="posta.example">
          <incomingServer type="imap"><hostname>imap.posta.example</hostname><port>993</port><socketType>SSL</socketType>
            <username>%EMAILLOCALPART%</username><authentication>password-cleartext</authentication></incomingServer>
          <outgoingServer type="smtp"><hostname>smtp.posta.example</hostname><port>587</port><socketType>STARTTLS</socketType>
            <username>%EMAILLOCALPART%</username><authentication>password-cleartext</authentication></outgoingServer>
        </emailProvider></clientConfig>
    """.trimIndent()

    @Before
    fun open() {
        db = DeskMailDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        accounts = AccountStore(db, testBox(), log)
        sync = MailSync(db, accounts, log, background)
        val autoconfig = Autoconfig(fetch = { url -> asked += url; if (url.endsWith("/posta.example")) database else null })
        setup = AccountSetup(db, accounts, sync, autoconfig, log, background)
    }

    @After
    fun close() {
        background.cancel()
        db.close()
    }

    private fun draft(password: String = TestImapServer.PASSWORD, auth: AuthKind = AuthKind.Password) =
        AccountDraft(TestImapServer.ADDRESS, server.settings.copy(auth = auth), provider = null, password = password)

    @Test
    fun anAddressLeadsToItsServers() = runBlocking {
        assertEquals(SetupPlan.NotAnAddress, setup.find("deniz"))
        assertEquals(SetupPlan.NotAnAddress, setup.find("deniz aksoy@gmail.com"))
        assertEquals(SetupPlan.NotAnAddress, setup.find(""))

        // the providers the app knows by heart: nobody is asked
        val gmail = setup.find("  deniz@gmail.com ") as SetupPlan.Found
        assertSame(ProviderTable.Gmail, gmail.provider)
        assertEquals(AuthKind.AppPassword, gmail.settings.auth)
        assertEquals("deniz@gmail.com", gmail.settings.username)
        assertEquals(SetupPlan.NotYet(ProviderTable.Outlook), setup.find("deniz@hotmail.com"))
        assertTrue(asked.isEmpty())

        // the provider database, by the domain alone
        val found = setup.find("deniz@posta.example") as SetupPlan.Found
        assertNull(found.provider)
        assertEquals(ServerAddress("imap.posta.example", 993, Security.Tls), found.settings.imap)
        assertEquals(ServerAddress("smtp.posta.example", 587, Security.StartTls), found.settings.smtp)
        assertEquals("deniz", found.settings.username)
        assertEquals(AuthKind.Password, found.settings.auth)
        assertTrue(asked.none { it.contains("deniz") })

        // nobody knows it: by hand
        assertEquals(SetupPlan.Manual, setup.find("deniz@bilinmeyen.example"))
    }

    @Test
    fun anAccountIsKeptOnlyOnceItsServerTakesTheSignIn() = runBlocking {
        server.createFolder("Projeler")
        server.deliver("ilk", "Birinci ileti.", Instant.parse("2026-11-12T08:00:00Z"))

        val refused = setup.add(draft(password = "yanlis"))
        assertEquals(MailException.Kind.Authentication, (refused as SetupResult.Failed).kind)
        assertTrue(accounts.all().isEmpty())

        val id = (setup.add(draft()) as SetupResult.Done).accountId

        val stored = accounts.byId(id)!!
        assertEquals(TestImapServer.ADDRESS, stored.address)
        // named by its domain until the user says otherwise
        assertEquals("example.org", stored.name)
        assertEquals("", stored.provider)
        // the folders the server listed at sign-in are there at once; the mail follows
        assertEquals(setOf("INBOX", "Projeler"), db.folders().ofAccount(id).map { it.path }.toSet())
        assertEquals(0, db.messages().count())
        assertEquals(1, held.waiting)
        assertTrue(log.lines().none { it.contains(TestImapServer.PASSWORD) || it.contains("yanlis") })

        // the same address is not added twice, and is recognised before anything is asked
        assertEquals(SetupResult.Duplicate, setup.add(draft()))
        assertEquals(SetupPlan.Duplicate, setup.find(TestImapServer.ADDRESS.uppercase()))
        assertEquals(1, accounts.all().size)
    }

    @Test
    fun theFirstSyncIsStartedByWhoeverKeepsTrackOfTheSyncs() = runBlocking {
        val started = ArrayList<Long>()
        val tracked = AccountSetup(db, accounts, sync, Autoconfig(fetch = { null }), log, background, startSync = { started += it })

        val id = (tracked.add(draft()) as SetupResult.Done).accountId
        assertEquals(listOf(id), started)
        // and by nobody else, behind its back
        assertEquals(0, held.waiting)

        // a sign-in that failed starts nothing; one that worked, the same as a new account
        assertTrue(tracked.reconnect(id, "yanlis") is SetupResult.Failed)
        assertEquals(listOf(id), started)
        assertTrue(tracked.reconnect(id, TestImapServer.PASSWORD) is SetupResult.Done)
        assertEquals(listOf(id, id), started)
        assertEquals(0, held.waiting)
    }

    @Test
    fun aSignInTheDeviceCannotKeepIsSaidAndLeavesNothingBehind() = runBlocking {
        // the server says yes, and the device's keystore will not seal the password
        val refusing = object : SecretBox {
            override fun seal(plain: ByteArray, purpose: String): Sealed = throw ProviderException("Keystore operation failed")

            override fun open(sealed: Sealed, purpose: String): ByteArray = throw ProviderException("Keystore operation failed")
        }
        val unkept = AccountStore(db, refusing, log)
        val unlucky = AccountSetup(db, unkept, sync, Autoconfig(fetch = { null }), log, background)

        val result = unlucky.add(draft()) as SetupResult.Failed
        assertEquals(MailException.Kind.Server, result.kind)
        assertTrue(result.detail, result.detail.contains("ProviderException: Keystore operation failed"))
        assertTrue(unkept.all().isEmpty())
        assertEquals(0, held.waiting)
        assertTrue(log.lines().any { it.contains("the sign-in could not be kept on this device") })
        assertTrue(log.lines().none { it.contains(TestImapServer.PASSWORD) })

        // an account that is there keeps the password it had
        val id = (setup.add(draft()) as SetupResult.Done).accountId
        assertTrue(unlucky.reconnect(id, TestImapServer.PASSWORD) is SetupResult.Failed)
        assertEquals(TestImapServer.PASSWORD, (accounts.credentials(accounts.byId(id)!!) as Credentials.Password).password)
    }

    @Test
    fun anAppPasswordIsTakenWithTheSpacesItIsShownWith() = runBlocking {
        val result = setup.add(draft(password = " a-test -pass word ", auth = AuthKind.AppPassword))
        assertTrue(result is SetupResult.Done)
        // a password of the account's own is sent as it was typed
        accounts.remove((result as SetupResult.Done).accountId)
        assertTrue(setup.add(draft(password = " a-test -pass word ")) is SetupResult.Failed)
    }

    @Test
    fun aSecondAccountAtTheSameProviderIsNamedByItsAddress() = runBlocking {
        val first = (setup.add(draft()) as SetupResult.Done).accountId
        db.accounts().update(accounts.byId(first)!!.copy(address = "baska@example.org"))

        val second = (setup.add(draft()) as SetupResult.Done).accountId

        assertEquals(TestImapServer.ADDRESS, accounts.byId(second)!!.name)
    }

    @Test
    fun theNameAndTheSignatureAnAccountWritesWithAreKeptTidy() = runBlocking {
        val id = (setup.add(draft()) as SetupResult.Done).accountId

        setup.saveWriting(id, "  Deniz\r\nAksoy ", "\nDeniz Aksoy\r\nİstanbul\n\n")

        val kept = accounts.byId(id)!!
        // a name is one line of a header; a signature has no space around it, and lines as the app writes them
        assertEquals("Deniz Aksoy", kept.senderName)
        assertEquals("Deniz Aksoy\nİstanbul", kept.signature)
    }

    @Test
    fun aRemovedAccountLeavesNothingBehind() = runBlocking {
        server.deliver("ilk", "Aranacak sözcük.", Instant.parse("2026-11-12T08:00:00Z"))
        val id = (setup.add(draft()) as SetupResult.Done).accountId
        // without a clock of the test's own the window is counted from today: all of it
        db.accounts().update(accounts.byId(id)!!.copy(syncDays = 0))
        assertEquals(MailSync.Outcome.Done, sync.account(id))
        assertEquals(1, db.messages().search("aranacak").size)

        setup.remove(id)

        assertTrue(accounts.all().isEmpty())
        assertTrue(db.folders().ofAccount(id).isEmpty())
        assertEquals(0, db.messages().count())
        assertTrue(db.messages().search("aranacak").isEmpty())
    }
}
