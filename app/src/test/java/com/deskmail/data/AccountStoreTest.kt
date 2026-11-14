package com.deskmail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.MailException
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.oauth.HttpForm
import com.deskmail.core.oauth.OAuthConfig
import com.deskmail.core.oauth.TokenClient
import com.deskmail.core.oauth.Tokens
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.ProviderTable
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.testFolder
import com.deskmail.mail.AccountState
import com.deskmail.mail.PortableAccount
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The accounts and their passwords: stored sealed, opened only for signing in. */
@RunWith(AndroidJUnit4::class)
class AccountStoreTest {

    private lateinit var db: DeskMailDatabase
    private lateinit var accounts: AccountStore
    private val log = AppLog()
    private val gmail = ProviderTable.Gmail.settingsFor("deniz@gmail.com")

    @Before
    fun open() {
        db = DeskMailDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        accounts = AccountStore(db, testBox(), log, now = { 5_000 })
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun anAccountIsKeptWithItsServersAndItsSealedPassword() = runBlocking {
        val id = accounts.add("deniz@gmail.com", "Gmail", "gmail", gmail, "abcdefghijklmnop")

        val stored = accounts.byId(id)!!
        assertEquals("deniz@gmail.com", stored.address)
        assertEquals("Gmail", stored.name)
        assertEquals("gmail", stored.provider)
        assertEquals(ServerAddress("imap.gmail.com", 993, Security.Tls), stored.imapServer())
        assertEquals(ServerAddress("smtp.gmail.com", 465, Security.Tls), stored.smtpServer())
        assertEquals(AuthKind.AppPassword, stored.auth())
        assertEquals(AccountState.Ok, stored.accountState())
        assertEquals(30, stored.syncDays)
        assertEquals(5_000, stored.createdAt)

        // what is stored is not the password
        val credential = db.accounts().credential(id)!!
        assertEquals(AccountStore.KIND_PASSWORD, credential.kind)
        assertFalse(String(credential.secret, Charsets.ISO_8859_1).contains("abcdefghijklmnop"))
        // and it opens for signing in
        val credentials = accounts.credentials(stored) as Credentials.Password
        assertEquals("deniz@gmail.com", credentials.username)
        assertEquals("abcdefghijklmnop", credentials.password)
        // nothing of it went into the log
        assertTrue(log.lines().none { it.contains("abcdefghijklmnop") })
        assertTrue(log.lines().any { it.contains("account $id added: deniz@gmail.com at imap.gmail.com") })
    }

    @Test
    fun accountsStandInTheOrderTheyWereAdded() = runBlocking {
        val first = accounts.add("deniz@gmail.com", "Gmail", "gmail", gmail, "bir")
        val second = accounts.add("deniz@example.org", "example.org", "", gmail.copy(username = "deniz"), "iki")
        assertEquals(listOf(first, second), accounts.all().map { it.id })
        assertEquals(listOf(0, 1), accounts.all().map { it.position })
        // an address is one account, however it is written
        assertEquals(first, accounts.byAddress(" Deniz@Gmail.com ")?.id)
        assertNull(accounts.byAddress("baska@gmail.com"))
    }

    @Test
    fun aNewPasswordTakesTheOldOnesPlace() = runBlocking {
        val id = accounts.add("deniz@gmail.com", "Gmail", "gmail", gmail, "eski")
        accounts.savePassword(id, "yeni")
        assertEquals("yeni", (accounts.credentials(accounts.byId(id)!!) as Credentials.Password).password)
    }

    @Test
    fun aPasswordThisDeviceCannotOpenIsNone() = runBlocking {
        val id = accounts.add("deniz@gmail.com", "Gmail", "gmail", gmail, "parola")
        val other = accounts.add("deniz@example.org", "example.org", "", gmail, "baska")
        val account = accounts.byId(id)!!

        // the keystore's key is gone (another box), or the row belongs to another account
        assertNull(AccountStore(db, testBox(seed = 9), log).credentials(account))
        val moved = db.accounts().credential(other)!!
        db.accounts().saveCredential(com.deskmail.db.CredentialEntity(id, moved.kind, moved.iv, moved.secret, moved.updatedAt))
        assertNull(accounts.credentials(account))
        // why is in the log, without anything secret
        assertTrue(log.lines().any { it.contains("account $id: the stored sign-in could not be opened") })
    }

    @Test
    fun howAnAccountIsDoingIsWrittenOnIt() = runBlocking {
        val id = accounts.add("deniz@gmail.com", "Gmail", "gmail", gmail, "parola")
        accounts.setState(id, AccountState.NeedsLogin, "[AUTHENTICATIONFAILED] Invalid credentials")
        val stored = accounts.byId(id)!!
        assertEquals(AccountState.NeedsLogin, stored.accountState())
        assertEquals("[AUTHENTICATIONFAILED] Invalid credentials", stored.stateDetail)
        accounts.setState(id, AccountState.Ok)
        assertEquals("", accounts.byId(id)!!.stateDetail)
    }

    @Test
    fun anAccountLeavesWithEverythingOfIts() = runBlocking {
        val id = accounts.add("deniz@gmail.com", "Gmail", "gmail", gmail, "parola")
        val kept = accounts.add("deniz@example.org", "example.org", "", gmail, "baska")
        db.folders().insert(testFolder(id, "INBOX", role = "Inbox"))

        accounts.remove(id)

        assertNull(accounts.byId(id))
        assertNull(db.accounts().credential(id))
        assertTrue(db.folders().ofAccount(id).isEmpty())
        assertNotNull(accounts.byId(kept))
        assertNotNull(accounts.credentials(accounts.byId(kept)!!))
    }

    /** An account from a settings file: there with its servers and its name, and nothing to sign in with until it is given its password. */
    @Test
    fun anAccountFromAnotherDeviceWaitsForItsPassword() = runBlocking {
        val first = accounts.add("deniz@example.org", "example.org", "", gmail, "parola")
        val portable = PortableAccount(
            address = " deniz@gmail.com ",
            name = "Gmail\nkişisel",
            senderName = "Deniz Aksoy",
            provider = "gmail",
            settings = gmail,
            syncDays = 90,
            notifyMode = "push",
            signature = "Deniz\r\nİstanbul",
        )

        val id = accounts.addWaiting(portable)!!

        val stored = accounts.byId(id)!!
        assertEquals("deniz@gmail.com", stored.address)
        assertEquals("Gmail kişisel", stored.name)
        assertEquals("Deniz Aksoy", stored.senderName)
        assertEquals(ServerAddress("imap.gmail.com", 993, Security.Tls), stored.imapServer())
        assertEquals(AuthKind.AppPassword, stored.auth())
        assertEquals(90, stored.syncDays)
        assertEquals("Deniz\nİstanbul", stored.signature)
        // it waits for its password; it comes after the accounts there were
        assertEquals(AccountState.NeedsLogin, stored.accountState())
        assertNull(accounts.credentials(stored))
        assertTrue(stored.position > accounts.byId(first)!!.position)

        // the device has it now: a second file adds nothing, whatever capitals it writes the address in
        assertNull(accounts.addWaiting(portable.copy(address = "Deniz@Gmail.com")))
        // nor does what is no address, or has no servers
        assertNull(accounts.addWaiting(portable.copy(address = "deniz")))
        assertNull(accounts.addWaiting(portable.copy(address = "ayse@posta.example", settings = gmail.copy(imap = ServerAddress(" ", 993, Security.Tls)))))
        assertEquals(2, accounts.all().size)
    }

    /** A sign-in at a provider in the browser, as a test makes it up: what its token endpoint is asked, and what it answers. */
    private val signIn = OAuthConfig("https://login.example/authorize", "https://login.example/token", "client", "com.deskmail://oauth2redirect", listOf("offline_access"))
    private val outlook = ProviderTable.Outlook.settingsFor("deniz@outlook.com")
    private val asked = ArrayList<Map<String, String>>()
    private val answers = ArrayDeque<HttpForm.Answer>()
    private var clock = 5_000L

    private fun signedInStore(signIns: (AuthKind) -> OAuthConfig? = { if (it == AuthKind.MicrosoftOAuth) signIn else null }) = AccountStore(
        db, testBox(), log, now = { clock }, signIns = signIns,
        tokenClient = { config ->
            TokenClient(config) { _, fields ->
                asked += fields
                answers.removeFirst()
            }
        },
    )

    private fun granted(access: String, refresh: String?): HttpForm.Answer {
        val refreshField = if (refresh != null) ",\"refresh_token\":\"$refresh\"" else ""
        return HttpForm.Answer(200, "{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\"$access\"$refreshField}")
    }

    @Test
    fun aSignInKeepsItsRefreshTokenSealedAndUsesItsAccessTokenAtOnce() = runBlocking<Unit> {
        val store = signedInStore()
        val id = store.addSignedIn("deniz@outlook.com", "Outlook", "outlook", outlook, Tokens("EwB-first", "M.C5-first", 3600))

        val stored = store.byId(id)!!
        assertEquals(AuthKind.MicrosoftOAuth, stored.auth())
        val credential = db.accounts().credential(id)!!
        assertEquals(AccountStore.KIND_OAUTH, credential.kind)
        assertFalse(String(credential.secret, Charsets.ISO_8859_1).contains("M.C5-first"))

        // the access token that came with the sign-in: the provider is not asked
        val credentials = store.credentials(stored) as Credentials.OAuth
        assertEquals("deniz@outlook.com", credentials.username)
        assertEquals("EwB-first", credentials.accessToken)
        assertTrue(asked.isEmpty())
        assertTrue(log.lines().none { it.contains("EwB-first") || it.contains("M.C5-first") })

        // a sign-in without a refresh token cannot be kept
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.addSignedIn("ayse@outlook.com", "Outlook", "outlook", outlook, Tokens("EwB", null, 3600)) }
        }
    }

    @Test
    fun anAccessTokenNearItsEndIsRenewedAndTheNewRefreshTokenKept() = runBlocking {
        val store = signedInStore()
        val id = store.addSignedIn("deniz@outlook.com", "Outlook", "outlook", outlook, Tokens("EwB-first", "M.C5-first", 3600))
        val account = store.byId(id)!!

        // fifty-six minutes on, a sync could outlast the token: a new one is asked for
        clock += 56 * 60_000L
        answers += granted("EwB-second", "M.C5-second")
        assertEquals("EwB-second", (store.credentials(account) as Credentials.OAuth).accessToken)
        assertEquals("refresh_token", asked.single()["grant_type"])
        assertEquals("M.C5-first", asked.single()["refresh_token"])

        // the next renewal goes with the refresh token that came last; until then nobody is asked
        assertEquals("EwB-second", (store.credentials(account) as Credentials.OAuth).accessToken)
        assertEquals(1, asked.size)
        clock += 60 * 60_000L
        answers += granted("EwB-third", null)
        assertEquals("EwB-third", (store.credentials(account) as Credentials.OAuth).accessToken)
        assertEquals("M.C5-second", asked.last()["refresh_token"])

        // an answer without a refresh token leaves the last one in its place
        clock += 60 * 60_000L
        answers += granted("EwB-fourth", "M.C5-fourth")
        store.credentials(account)
        assertEquals("M.C5-second", asked.last()["refresh_token"])
        assertTrue(log.lines().any { it.contains("account $id: a new access token, good for 60 minutes") })
        assertTrue(log.lines().none { "EwB-" in it || "M.C5-" in it })
    }

    @Test
    fun aRefreshTokenTheProviderNoLongerTakesMeansSigningInAgain() = runBlocking {
        val store = signedInStore()
        val id = store.addSignedIn("deniz@outlook.com", "Outlook", "outlook", outlook, Tokens("EwB-first", "M.C5-first", 3600))
        clock += 2 * 60 * 60_000L
        answers += HttpForm.Answer(400, """{"error":"invalid_grant","error_description":"AADSTS70000: The grant is expired."}""")

        val error = assertThrows(MailException::class.java) { runBlocking { store.credentials(store.byId(id)!!) } }

        assertEquals(MailException.Kind.Authentication, error.kind)
        assertEquals("invalid_grant: AADSTS70000: The grant is expired.", error.message)
        assertTrue(log.lines().any { it.contains("account $id: no new access token (Authentication)") })

        // signed in again: the new sign-in is used, the old refresh token is gone
        store.saveTokens(id, Tokens("EwB-again", "M.C5-again", 3600))
        assertEquals("EwB-again", (store.credentials(store.byId(id)!!) as Credentials.OAuth).accessToken)
        clock += 2 * 60 * 60_000L
        answers += granted("EwB-later", "M.C5-later")
        store.credentials(store.byId(id)!!)
        assertEquals("M.C5-again", asked.last()["refresh_token"])
    }

    @Test
    fun withoutAWayToSignInThereIsNothingToSignInWith() = runBlocking {
        val id = signedInStore().addSignedIn("deniz@outlook.com", "Outlook", "outlook", outlook, Tokens("EwB-first", "M.C5-first", 3600))
        clock += 2 * 60 * 60_000L

        // a version that has no registration at the provider
        assertNull(signedInStore(signIns = { null }).credentials(db.accounts().byId(id)!!))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun anAccountLeavesWithItsAccessToken() = runBlocking {
        val store = signedInStore()
        val id = store.addSignedIn("deniz@outlook.com", "Outlook", "outlook", outlook, Tokens("EwB-first", "M.C5-first", 3600))
        val account = store.byId(id)!!
        store.remove(id)

        assertNull(store.credentials(account))
        assertNull(db.accounts().credential(id))
    }
}
