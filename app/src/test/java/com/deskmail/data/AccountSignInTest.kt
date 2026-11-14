package com.deskmail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.MailException
import com.deskmail.core.net.Credentials
import com.deskmail.core.oauth.HttpForm
import com.deskmail.core.oauth.OAuthConfig
import com.deskmail.core.oauth.Pkce
import com.deskmail.core.oauth.TokenClient
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.Autoconfig
import com.deskmail.core.provider.ProviderTable
import com.deskmail.db.DeskMailDatabase
import com.deskmail.mail.AccountState
import com.deskmail.mail.SetupPlan
import com.deskmail.mail.SetupResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.net.URLDecoder

/**
 * Adding an account that signs in at its provider in the browser: the page, the way back, the
 * tokens, and the server's yes. The provider and the browser are made up; the IMAP server is the
 * test's own, which takes the access token for the password.
 */
@RunWith(AndroidJUnit4::class)
class AccountSignInTest {

    @get:Rule
    val server = TestImapServer()

    private val log = AppLog()
    private val held = HeldDispatcher()
    private val background = CoroutineScope(SupervisorJob() + held)
    private lateinit var db: DeskMailDatabase
    private lateinit var accounts: AccountStore
    private lateinit var sync: MailSync

    private val signIn = OAuthConfig(
        "https://login.example/consumers/oauth2/v2.0/authorize",
        "https://login.example/consumers/oauth2/v2.0/token",
        "client-id",
        "com.deskmail://oauth2redirect",
        listOf("https://outlook.office.com/IMAP.AccessAsUser.All", "offline_access"),
    )

    /** What the token endpoint was sent, and what it answers, in turn. */
    private val posted = ArrayList<Map<String, String>>()
    private val answers = ArrayDeque<HttpForm.Answer>()

    /** The pages the browser was asked to open; the accounts whose mail was sent for. */
    private val opened = ArrayList<String>()
    private val started = ArrayList<Long>()

    /** The way back from the browser of the setup made last. */
    private lateinit var browser: BrowserSignIn

    /** The test's server as Outlook's would be found. */
    private val plan get() = SetupPlan.SignIn(server.settings.copy(auth = AuthKind.MicrosoftOAuth), ProviderTable.Outlook)

    @Before
    fun open() {
        db = DeskMailDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        accounts = AccountStore(db, testBox(), log)
        sync = MailSync(db, accounts, log, background)
    }

    @After
    fun close() {
        background.cancel()
        db.close()
    }

    private fun setup(
        autoconfig: Autoconfig = Autoconfig(fetch = { null }),
        signIns: (AuthKind) -> OAuthConfig? = { if (it == AuthKind.MicrosoftOAuth) signIn else null },
        waitMs: Long = BrowserSignIn.WAIT_MS,
    ): AccountSetup {
        browser = BrowserSignIn(log, waitMs)
        return AccountSetup(
            db, accounts, sync, autoconfig, log, background,
            startSync = { started += it },
            browser = browser,
            signIns = signIns,
            tokenClient = { config ->
                TokenClient(config) { _, fields ->
                    posted += fields
                    answers.removeFirst()
                }
            },
        )
    }

    /** The browser as the test makes it up: it opens the page and comes back to the app at what [back] makes of the page's state. */
    private fun browserComingBack(back: (state: String) -> String?): suspend (String) -> Boolean = { url ->
        opened += url
        back(query(url).getValue("state"))?.let { browser.cameBack(it) }
        true
    }

    /** The user signed in and allowed it. */
    private fun withCode(code: String = "M.C507-code") = browserComingBack { state -> "com.deskmail://oauth2redirect?code=$code&state=$state" }

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }

    /** The token endpoint's yes; the test's server takes the access token for the password. */
    private fun tokens(access: String = TestImapServer.PASSWORD, refresh: String? = "M.C5-refresh"): HttpForm.Answer {
        val refreshField = refresh?.let { ",\"refresh_token\":\"$it\"" }.orEmpty()
        return HttpForm.Answer(200, "{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\"$access\"$refreshField}")
    }

    @Test
    fun anOutlookAddressSignsInInTheBrowserWhereTheAppHasARegistration() = runBlocking {
        assertEquals(SetupPlan.SignIn(ProviderTable.Outlook.settingsFor("deniz@hotmail.com"), ProviderTable.Outlook), setup().find("deniz@hotmail.com"))
        // without one, it cannot be added
        assertEquals(SetupPlan.NotYet(ProviderTable.Outlook), setup(signIns = { null }).find("deniz@hotmail.com"))
        // an address at a company's own domain whose mail Microsoft takes is a work account: not for a registration of personal accounts
        val hosting = Autoconfig(fetch = { null }, mailHosts = { domain -> if (domain == "sirket.example") listOf("sirket-example.mail.protection.outlook.com") else emptyList() })
        assertEquals(SetupPlan.NotYet(ProviderTable.Outlook, hosted = true), setup(autoconfig = hosting).find("deniz@sirket.example"))
        // a password's provider is as it was
        assertTrue(setup().find("deniz@gmail.com") is SetupPlan.Found)
    }

    @Test
    fun theBrowserComesBackWithACodeAndTheAccountIsKept() = runBlocking {
        server.createFolder("Projeler")
        answers += tokens()
        val setup = setup()

        val id = (setup.addSignedIn(" ${TestImapServer.ADDRESS} ", plan, withCode()) as SetupResult.Done).accountId

        // the page: the app's registration, the address filled in, a challenge in the verifier's place
        val page = query(opened.single())
        assertEquals("client-id", page["client_id"])
        assertEquals(TestImapServer.ADDRESS, page["login_hint"])
        assertEquals("S256", page["code_challenge_method"])
        // the code was traded with the verifier the challenge was made of
        val trade = posted.single()
        assertEquals("authorization_code", trade["grant_type"])
        assertEquals("M.C507-code", trade["code"])
        assertEquals(page["code_challenge"], Pkce.challenge(trade.getValue("code_verifier")))

        val stored = accounts.byId(id)!!
        assertEquals(AuthKind.MicrosoftOAuth, stored.auth())
        assertEquals("Outlook", stored.name)
        assertEquals("outlook", stored.provider)
        assertEquals(AccountStore.KIND_OAUTH, db.accounts().credential(id)!!.kind)
        // the folders the server listed at sign-in are there at once; the mail follows
        assertEquals(setOf("INBOX", "Projeler"), db.folders().ofAccount(id).map { it.path }.toSet())
        assertEquals(listOf(id), started)
        // the access token that came signs in from now on
        assertEquals(TestImapServer.PASSWORD, (accounts.credentials(stored) as Credentials.OAuth).accessToken)
        assertTrue(log.lines().none { "M.C507-code" in it || "M.C5-refresh" in it || TestImapServer.PASSWORD in it })

        // the address is there now: the browser is not opened for it again
        assertEquals(SetupResult.Duplicate, setup.addSignedIn(TestImapServer.ADDRESS, plan, withCode()))
        assertEquals(1, opened.size)
    }

    @Test
    fun aSignInThatComesToNothingKeepsNothing() = runBlocking {
        val setup = setup()

        // the user said no at the provider
        val refused = browserComingBack { state ->
            "com.deskmail://oauth2redirect?error=access_denied&error_description=The+user+has+denied+access+to+the+scope+requested+by+the+client+application.&state=$state"
        }
        assertEquals(SetupResult.NotSignedIn("The user has denied access to the scope requested by the client application."), setup.addSignedIn(TestImapServer.ADDRESS, plan, refused))
        // nothing could show the page
        assertEquals(SetupResult.NoBrowser, setup.addSignedIn(TestImapServer.ADDRESS, plan) { false })
        // the code is no longer good when it is traded: signed in for again
        answers += HttpForm.Answer(400, "{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS70008: The provided authorization code has expired.\"}")
        assertEquals(
            SetupResult.NotSignedIn("invalid_grant: AADSTS70008: The provided authorization code has expired."),
            setup.addSignedIn(TestImapServer.ADDRESS, plan, withCode()),
        )
        // the provider cannot be reached to trade it
        answers += HttpForm.Answer(503, "")
        assertEquals(MailException.Kind.Network, (setup.addSignedIn(TestImapServer.ADDRESS, plan, withCode()) as SetupResult.Failed).kind)
        // no refresh token: the account would need signing in to within the hour
        answers += tokens(refresh = null)
        assertEquals(SetupResult.NotSignedIn(""), setup.addSignedIn(TestImapServer.ADDRESS, plan, withCode()))
        // the provider took the sign-in, and the mail server did not (IMAP off at Outlook.com)
        answers += tokens(access = "not-the-password")
        assertEquals(MailException.Kind.Authentication, (setup.addSignedIn(TestImapServer.ADDRESS, plan, withCode()) as SetupResult.Failed).kind)

        assertTrue(accounts.all().isEmpty())
        assertTrue(started.isEmpty())
        assertTrue(log.lines().any { it.contains("the sign-in in the browser came back without a code: access_denied") })
    }

    @Test
    fun anAnswerToAnotherSignInIsNotTaken() = runBlocking {
        // the browser that comes back with another state never came back to this sign-in: it is given up in time
        val setup = setup(waitMs = 300)
        val other = browserComingBack { "com.deskmail://oauth2redirect?code=M.C507-other&state=another" }

        assertEquals(SetupResult.NotSignedIn(""), setup.addSignedIn(TestImapServer.ADDRESS, plan, other))

        assertTrue(posted.isEmpty())
        assertTrue(accounts.all().isEmpty())
        assertTrue(log.lines().any { it.contains("the browser came back from a sign-in that nothing waits for") })
    }

    @Test
    fun anAccountSignsInAgainInTheBrowser() = runBlocking {
        answers += tokens(refresh = "M.C5-first")
        val setup = setup()
        val id = (setup.addSignedIn(TestImapServer.ADDRESS, plan, withCode()) as SetupResult.Done).accountId
        accounts.setState(id, AccountState.NeedsLogin, "invalid_grant")

        // signed in to again, with the account's address filled in
        answers += tokens(refresh = "M.C5-second")
        assertEquals(SetupResult.Done(id), setup.reconnectSignedIn(id, withCode("M.C507-again")))

        assertEquals(TestImapServer.ADDRESS, query(opened.last())["login_hint"])
        assertEquals("M.C507-again", posted.last()["code"])
        assertEquals(AccountState.Ok, accounts.byId(id)!!.accountState())
        assertEquals(listOf(id, id), started)

        // a sign-in the mail server does not take changes nothing
        answers += tokens(access = "not-the-password", refresh = "M.C5-third")
        assertEquals(MailException.Kind.Authentication, (setup.reconnectSignedIn(id, withCode()) as SetupResult.Failed).kind)
        assertEquals(TestImapServer.PASSWORD, (accounts.credentials(accounts.byId(id)!!) as Credentials.OAuth).accessToken)

        // an account with a password has no sign-in in the browser
        val other = accounts.add("baska@example.org", "Posta", "", server.settings, TestImapServer.PASSWORD)
        assertEquals(SetupResult.Failed(MailException.Kind.Server, ""), setup.reconnectSignedIn(other, withCode()))
        assertEquals(listOf(id, id), started)
    }
}
