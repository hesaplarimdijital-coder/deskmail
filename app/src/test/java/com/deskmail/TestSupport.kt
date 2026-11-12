package com.deskmail

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.test.core.app.ApplicationProvider
import com.deskmail.core.log.AppLog
import com.deskmail.demo.DemoMail
import com.deskmail.mail.AccountActions
import com.deskmail.mail.AccountDraft
import com.deskmail.mail.MailSource
import com.deskmail.mail.SetupPlan
import com.deskmail.mail.SetupResult
import com.deskmail.settings.AppSettings
import com.deskmail.ui.DeskMailApp
import com.deskmail.ui.mail.MailClock
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.PlainMessageBody
import com.deskmail.ui.settings.SettingsUiState
import desktopkit.settings.DeskPreferences
import desktopkit.shell.ShellTransitions
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
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
 * The app as the tests draw it: no screen transitions, the pinned clock, and a message's plain text
 * where the app has a WebView (Robolectric does not draw one).
 */
@Composable
fun TestApp(
    mail: MailSource,
    prefs: DeskPreferences,
    settings: AppSettings,
    ui: MailUiState = remember { MailUiState() },
    startRoute: String? = null,
    log: AppLog = remember { AppLog() },
    accounts: AccountActions? = null,
    settingsUi: SettingsUiState = remember { SettingsUiState() },
) {
    DeskMailApp(
        mail = mail,
        settings = settings,
        log = log,
        accounts = accounts,
        ui = ui,
        settingsUi = settingsUi,
        startRoute = startRoute,
        prefs = prefs,
        clock = TEST_CLOCK,
        transitions = ShellTransitions.None,
        messageBody = { content, _, modifier -> PlainMessageBody(content, modifier) },
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

    override suspend fun remove(accountId: Long) {
        removed += accountId
    }
}
