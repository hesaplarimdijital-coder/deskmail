package com.deskmail

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.deskmail.core.log.AppLog
import com.deskmail.demo.DemoComposer
import com.deskmail.demo.DemoMail
import com.deskmail.mail.AccountActions
import com.deskmail.mail.AccountDraft
import com.deskmail.mail.MailComposer
import com.deskmail.mail.MailContent
import com.deskmail.mail.MailSource
import com.deskmail.mail.MailView
import com.deskmail.mail.PortableAccount
import com.deskmail.mail.SetupPlan
import com.deskmail.mail.SetupResult
import com.deskmail.notify.NotifyGate
import com.deskmail.settings.AppSettings
import com.deskmail.ui.DeskMailApp
import com.deskmail.ui.compose.WriteApart
import com.deskmail.ui.mail.MailClock
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.PlainMessageBody
import com.deskmail.ui.settings.SettingsUiState
import desktopkit.settings.DeskPreferences
import desktopkit.shell.ShellTransitions
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant
import java.time.ZoneId

/**
 * The kit's preferences pinned for a test: the theme and the density do not follow the test
 * machine (which has no mouse, and no dark mode of its own).
 */
fun testPrefs(theme: ThemeMode = ThemeMode.Dark, density: DensityPreference = DensityPreference.Touch): DeskPreferences {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return DeskPreferences(context.getSharedPreferences("test-desktopkit", Context.MODE_PRIVATE)).apply {
        updateThemeMode(theme)
        updateDensity(density)
    }
}

/** The app's settings in a file of the test's own; every test starts from the defaults. */
fun testSettings(): AppSettings {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return AppSettings(context.getSharedPreferences("test-deskmail", Context.MODE_PRIVATE))
}

/** The moment the tests' mail is dated back from: 12 November 2026, 12:30 in Istanbul. */
val TEST_NOW: Instant = Instant.parse("2026-11-12T09:30:00Z")

val TEST_CLOCK = MailClock(ZoneId.of("Europe/Istanbul")) { TEST_NOW }

/** The demo mail dated back from [TEST_NOW], so every run lists the same dates. */
fun testMail() = DemoMail(TEST_NOW)

/** The made-up mail before any account was added. */
fun noMail() = DemoMail(TEST_NOW, empty = true)

/**
 * "New message", the shell's main action (desktop-kit 2.8.0): a button with its name on it (a
 * phone's list at its top, the sidebar), or its icon alone, named for TalkBack (the rail, a phone's
 * list scrolled down).
 */
fun newMessageButton(): SemanticsMatcher = (hasText("Yeni ileti") or hasContentDescription("Yeni ileti")) and hasClickAction()

/** Presses "New message" ([newMessageButton]). */
fun ComposeTestRule.pressNewMessage() {
    onNode(newMessageButton()).performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
}

/** Writing and sending made up over [mail], dated as the tests' mail is. */
fun testComposer(mail: DemoMail) = DemoComposer(mail, TEST_NOW)

/**
 * The app as the tests draw it: no screen transitions, the pinned clock, and a message's plain text
 * where the app has a WebView (Robolectric does not draw one). Messages are written and "sent" in
 * memory ([composer]; a test that looks at what was written hands in its own).
 */
@Composable
fun TestApp(
    mail: MailSource,
    prefs: DeskPreferences,
    settings: AppSettings,
    ui: MailUiState = remember { MailUiState() },
    startRoute: String? = null,
    log: AppLog = remember { AppLog() },
    composer: MailComposer? = remember(mail) { (mail as? DemoMail)?.let { testComposer(it) } },
    accounts: AccountActions? = null,
    settingsUi: SettingsUiState = remember { SettingsUiState() },
    messageBody: @Composable (MailContent.Ready, Boolean, Modifier) -> Unit = { content, _, modifier -> PlainMessageBody(content, modifier) },
    notifications: NotifyGate? = null,
    writeApart: WriteApart? = null,
    onFrontList: ((MailView?) -> Unit)? = null,
    onCheckChosen: () -> Unit = {},
) {
    DeskMailApp(
        mail = mail,
        settings = settings,
        log = log,
        composer = composer,
        accounts = accounts,
        ui = ui,
        settingsUi = settingsUi,
        startRoute = startRoute,
        prefs = prefs,
        clock = TEST_CLOCK,
        transitions = ShellTransitions.None,
        messageBody = messageBody,
        notifications = notifications,
        writeApart = writeApart,
        onFrontList = onFrontList,
        onCheckChosen = onCheckChosen,
    )
}

/**
 * The accounts' servers as a test makes them up: [plan] is what the search for an address comes
 * to, [result] what the sign-in does. What was asked is kept.
 */
class FakeAccounts(var plan: SetupPlan = SetupPlan.Manual, var result: SetupResult = SetupResult.Done(1)) : AccountActions {
    val looked = ArrayList<String>()
    val added = ArrayList<AccountDraft>()
    val reconnected = ArrayList<Pair<Long, String>>()
    val removed = ArrayList<Long>()

    /** What was saved of an account's name and signature: the account, the name, the signature. */
    val written = ArrayList<Triple<Long, String, String>>()

    override suspend fun find(address: String): SetupPlan {
        looked += address
        return plan
    }

    override suspend fun add(draft: AccountDraft): SetupResult {
        added += draft
        return result
    }

    override suspend fun reconnect(accountId: Long, password: String): SetupResult {
        reconnected += accountId to password
        return result
    }

    /** The sign-ins in the browser: the address and what it was found to be; the accounts signed in to again. */
    val signedIn = ArrayList<Pair<String, SetupPlan.SignIn>>()
    val signedInAgain = ArrayList<Long>()

    /** The page a sign-in in the browser opens. */
    var signInPage = "https://login.example/authorize?client_id=deskmail"

    /** Until the test completes it, a sign-in in the browser waits here, as for the browser to come back. */
    var browserAway: CompletableDeferred<Unit>? = null

    override suspend fun addSignedIn(address: String, plan: SetupPlan.SignIn, open: suspend (url: String) -> Boolean): SetupResult {
        signedIn += address to plan
        if (!open(signInPage)) return SetupResult.NoBrowser
        browserAway?.await()
        return result
    }

    override suspend fun reconnectSignedIn(accountId: Long, open: suspend (url: String) -> Boolean): SetupResult {
        signedInAgain += accountId
        if (!open(signInPage)) return SetupResult.NoBrowser
        browserAway?.await()
        return result
    }

    override suspend fun saveWriting(accountId: Long, senderName: String, signature: String) {
        written += Triple(accountId, senderName, signature)
    }

    override suspend fun remove(accountId: Long) {
        removed += accountId
    }

    /** What the accounts carry to another device; and what was taken from a settings file. */
    var portableAccounts: List<PortableAccount> = emptyList()
    val adopted = ArrayList<PortableAccount>()

    override suspend fun portable(): List<PortableAccount> = portableAccounts

    override suspend fun adopt(accounts: List<PortableAccount>): Int {
        adopted += accounts
        return accounts.size
    }
}
