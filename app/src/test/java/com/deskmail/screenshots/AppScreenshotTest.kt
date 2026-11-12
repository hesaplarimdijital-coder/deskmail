package com.deskmail.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deskmail.TEST_CLOCK
import com.deskmail.core.log.AppLog
import com.deskmail.demo.DemoMail
import com.deskmail.mail.MailMessage
import com.deskmail.settings.AppSettings
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.DeskMailApp
import com.deskmail.ui.Routes
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.PlainMessageBody
import com.deskmail.ui.mail.openMessage
import desktopkit.components.CommandPaletteState
import desktopkit.shell.ShellTransitions
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId

/**
 * The app's screens as pictures, in Turkish, at the sizes of the devices it is made for: the phone
 * (Galaxy S24, 411 x 891 dp), the tablet upright and on its side (Galaxy Tab S11 Ultra, 924 x 1480
 * and 1480 x 924 dp), a window beside a rail with room for two panes, and a DeX window on a
 * monitor (1920 x 1080 dp, mouse and keyboard); in the light, the dark and the OLED theme.
 *
 * The PNGs land in build/outputs/screenshots. CI compares them with src/test/screenshots, reports
 * what changed (tools/screenshot_report.py) and pushes them to ci/screenshots-pr-<number>.
 *
 * Two things differ from a device, by necessity:
 * - a message's body is its plain text: Robolectric draws no WebView (the app shows the same
 *   text where a device has no usable WebView);
 * - glass is drawn in its translucent form: the test renderer cannot blur.
 *
 * The window is drawn into a bitmap directly, as in the kit's screenshot tests.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppScreenshotTest {

    // a window nobody has touched hands its focus to the first control and draws a focus ring there
    @get:Rule(order = 0)
    val touchMode = object : ExternalResource() {
        override fun before() = InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail: DemoMail = testMail()
    private val ui = MailUiState()
    private val palette = CommandPaletteState()
    private val log = AppLog(zone = { ZoneId.of("Europe/Istanbul") }, now = { com.deskmail.TEST_NOW })
    private lateinit var settings: AppSettings

    private fun message(subject: String): MailMessage = mail.messages.first { it.subject == subject }

    /** Opens [subject] in the reader of [route] before the app is drawn. */
    private fun open(route: String, subject: String) = openMessage(mail, ui, route, message(subject))

    private fun launch(theme: ThemeMode, density: DensityPreference = DensityPreference.Touch, startRoute: String? = null) {
        val prefs = testPrefs(theme, density)
        settings = testSettings()
        // the test activity's theme has an action bar; the app's has none
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            DeskMailApp(
                mail = mail,
                settings = settings,
                log = log,
                ui = ui,
                startRoute = startRoute,
                prefs = prefs,
                clock = TEST_CLOCK,
                transitions = ShellTransitions.None,
                palette = palette,
                messageBody = { message, modifier -> PlainMessageBody(message, modifier) },
            )
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val window = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { window.draw(Canvas(bitmap)) }
        directory.mkdirs()
        FileOutputStream(File(directory, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ------------------------------------------------------------------ phone

    @Test @Config(qualifiers = PHONE)
    fun phoneInboxLight() {
        launch(ThemeMode.Light)
        capture("phone-inbox-light")
    }

    @Test @Config(qualifiers = PHONE)
    fun phoneInboxDark() {
        launch(ThemeMode.Dark)
        capture("phone-inbox-dark")
    }

    @Test @Config(qualifiers = PHONE)
    fun phoneInboxOled() {
        launch(ThemeMode.Oled)
        capture("phone-inbox-oled")
    }

    /** A message with attachments and a star, opened over the list. */
    @Test @Config(qualifiers = PHONE)
    fun phoneReaderDark() {
        open(Routes.INBOX, "Re: Proje planı (v3)")
        launch(ThemeMode.Dark)
        capture("phone-reader-dark")
    }

    /** A newsletter: the strip that says its remote images were not loaded. */
    @Test @Config(qualifiers = PHONE)
    fun phoneReaderLight() {
        open(Routes.INBOX, "Kasım bülteni: yeni gelenler ve imza günü")
        launch(ThemeMode.Light)
        capture("phone-reader-light")
    }

    @Test @Config(qualifiers = PHONE)
    fun phoneFoldersDark() {
        launch(ThemeMode.Dark, startRoute = Routes.FOLDERS)
        capture("phone-folders-dark")
    }

    /** The "⋯" sheet: the screens that are no tab, then the experiments. */
    @Test @Config(qualifiers = PHONE)
    fun phoneMoreSheetDark() {
        launch(ThemeMode.Dark)
        compose.onNode(hasText(compose.activity.getString(desktopkit.R.string.dk_more)) and isSelectable()).performClick()
        capture("phone-more-dark")
    }

    @Test @Config(qualifiers = PHONE)
    fun phoneSettingsLight() {
        launch(ThemeMode.Light, startRoute = Routes.SETTINGS)
        capture("phone-settings-light")
    }

    @Test @Config(qualifiers = PHONE)
    fun phoneLogDark() {
        // the app adds the window's line itself when it is drawn
        log.info("app", "started: DeskMail 0.1.0-alpha.1 (1), Android 17 (API 37), samsung SM-S921B, tr-TR, Europe/Istanbul")
        log.info("reader", "WebView: com.google.android.webview 142.0.7444.102")
        log.info("imap", "C: a1 LOGIN deniz@gmail.example abcd-efgh-ijkl-mnop")
        log.warn("idle", "outlook.office365.com: connection lost, trying again in 30 s")
        // an error with the place it was thrown at, as the log writes one (two frames instead of a real stack)
        val refused = IllegalStateException("535 5.7.139 Authentication unsuccessful, SmtpClientAuthentication is disabled for the Mailbox.")
        refused.stackTrace = arrayOf(
            StackTraceElement("com.deskmail.core.smtp.SmtpSession", "authenticate", "SmtpSession.kt", 88),
            StackTraceElement("com.deskmail.core.smtp.SmtpSession", "send", "SmtpSession.kt", 41),
        )
        log.error("smtp", "sending failed", refused)
        launch(ThemeMode.Dark, startRoute = Routes.SETTINGS)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Günlüğü aç"))
        compose.onNodeWithText("Günlüğü aç").performSemanticsAction(SemanticsActions.OnClick)
        capture("phone-log-dark")
    }

    @Test @Config(qualifiers = PHONE)
    fun phonePaletteDark() {
        palette.open("fatura")
        launch(ThemeMode.Dark)
        capture("phone-palette-dark")
    }

    // ------------------------------------------------------------------ tablet

    /** Upright: the rail, and the list alone (a message would slide over it). */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitInboxDark() {
        launch(ThemeMode.Dark)
        capture("tablet-portrait-inbox-dark")
    }

    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitReaderLight() {
        open(Routes.INBOX, "Toplantı tutanağı ve yapılacaklar")
        launch(ThemeMode.Light)
        capture("tablet-portrait-reader-light")
    }

    /** On its side the tablet is a large window: sidebar, list and reader. A finger gets the rows. */
    @Test @Config(qualifiers = TABLET_LANDSCAPE)
    fun tabletLandscapeDark() {
        open(Routes.INBOX, "Re: Proje planı (v3)")
        launch(ThemeMode.Dark)
        capture("tablet-landscape-dark")
    }

    /** The same window with the keyboard cover's touchpad, or as a DeX window on the tablet itself: the table. */
    @Test @Config(qualifiers = TABLET_LANDSCAPE)
    fun tabletLandscapeWithAMouseDark() {
        open(Routes.INBOX, "Re: Proje planı (v3)")
        launch(ThemeMode.Dark, DensityPreference.Pointer)
        capture("tablet-landscape-mouse-dark")
    }

    @Test @Config(qualifiers = TABLET_LANDSCAPE)
    fun tabletLandscapeInspectorLight() {
        open(Routes.INBOX, "Kapadokya gezi notları")
        ui.inspector.updateCollapsed(false)
        launch(ThemeMode.Light)
        capture("tablet-landscape-inspector-light")
    }

    /** A window beside the rail that is wide enough for two panes: list | reader. */
    @Test @Config(qualifiers = "tr-w1000dp-h800dp-hdpi")
    fun twoPanesBesideTheRailDark() {
        open(Routes.INBOX, "Kasım 2026 faturanız hazır")
        launch(ThemeMode.Dark)
        capture("rail-two-panes-dark")
    }

    // ------------------------------------------------------------------ DeX

    @Test @Config(qualifiers = DEX)
    fun dexWorkstationDark() {
        open(Routes.INBOX, "Re: Proje planı (v3)")
        ui.inspector.updateCollapsed(false)
        launch(ThemeMode.Dark, DensityPreference.Pointer)
        capture("dex-workstation-dark")
    }

    @Test @Config(qualifiers = DEX)
    fun dexWorkstationLight() {
        open(Routes.INBOX, "Kasım bülteni: yeni gelenler ve imza günü")
        launch(ThemeMode.Light, DensityPreference.Pointer)
        capture("dex-workstation-light")
    }

    @Test @Config(qualifiers = DEX)
    fun dexFolderOled() {
        val route = Routes.folder(19)
        open(route, "Tablet düzeni için notlar")
        launch(ThemeMode.Oled, DensityPreference.Pointer, startRoute = route)
        capture("dex-folder-oled")
    }

    @Test @Config(qualifiers = DEX)
    fun dexPaletteDark() {
        palette.open("proje")
        launch(ThemeMode.Dark, DensityPreference.Pointer)
        capture("dex-palette-dark")
    }

    @Test @Config(qualifiers = DEX)
    fun dexSettingsDark() {
        launch(ThemeMode.Dark, DensityPreference.Pointer, startRoute = Routes.SETTINGS)
        capture("dex-settings-dark")
    }

    companion object {
        private const val PHONE = "tr-w411dp-h891dp-xhdpi"
        private const val TABLET_PORTRAIT = "tr-w924dp-h1480dp-hdpi"
        private const val TABLET_LANDSCAPE = "tr-w1480dp-h924dp-hdpi"
        private const val DEX = "tr-w1920dp-h1080dp-mdpi"

        private val directory = File(System.getProperty("deskmail.screenshotDir") ?: "build/outputs/screenshots")
    }
}
