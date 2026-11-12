package com.deskmail

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import com.deskmail.demo.DemoMail
import com.deskmail.ui.DeskMailApp
import com.deskmail.ui.mail.MailClock
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.PlainMessageBody
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

/** The moment the tests' mail is dated back from: 12 November 2026, 12:30 in Istanbul. */
val TEST_NOW: Instant = Instant.parse("2026-11-12T09:30:00Z")

val TEST_CLOCK = MailClock(ZoneId.of("Europe/Istanbul")) { TEST_NOW }

/** The demo mail dated back from [TEST_NOW], so every run lists the same dates. */
fun testMail() = DemoMail(TEST_NOW)

/**
 * The app as the tests draw it: no screen transitions, the pinned clock, and a message's plain text
 * where the app has a WebView (Robolectric does not draw one).
 */
@Composable
fun TestApp(mail: DemoMail, prefs: DeskPreferences, ui: MailUiState = MailUiState(), startRoute: String? = null) {
    DeskMailApp(
        mail = mail,
        ui = ui,
        startRoute = startRoute,
        prefs = prefs,
        clock = TEST_CLOCK,
        transitions = ShellTransitions.None,
        messageBody = { message, modifier -> PlainMessageBody(message, modifier) },
    )
}
