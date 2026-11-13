package com.deskmail.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
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
import com.deskmail.FakeAccounts
import com.deskmail.TEST_CLOCK
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.MailException
import com.deskmail.core.provider.ProviderTable
import com.deskmail.demo.DemoMail
import com.deskmail.mail.AccountActions
import com.deskmail.mail.AccountState
import com.deskmail.mail.Draft
import com.deskmail.mail.DraftKind
import com.deskmail.mail.DraftState
import com.deskmail.mail.MailContent
import com.deskmail.mail.MailMessage
import com.deskmail.mail.MailSource
import com.deskmail.mail.MailView
import com.deskmail.mail.SetupPlan
import com.deskmail.noMail
import com.deskmail.notify.NotifyGate
import com.deskmail.settings.AppSettings
import com.deskmail.testComposer
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.DeskMailApp
import com.deskmail.ui.Routes
import com.deskmail.ui.compose.ComposeHost
import com.deskmail.ui.compose.WriteWindow
import com.deskmail.ui.compose.Writing
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.PlainMessageBody
import com.deskmail.ui.mail.openMessage
import com.deskmail.ui.settings.SettingsPage
import com.deskmail.ui.settings.SettingsUiState
import desktopkit.components.CommandPaletteState
import desktopkit.shell.ShellTransitions
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
import kotlinx.coroutines.runBlocking
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
    private val composer = testComposer(mail)
    private val ui = MailUiState()
    private val settingsUi = SettingsUiState()
    private val palette = CommandPaletteState()
    private val log = AppLog(zone = { ZoneId.of("Europe/Istanbul") }, now = { com.deskmail.TEST_NOW })
    private lateinit var settings: AppSettings

    private fun message(subject: String): MailMessage = mail.messages.first { it.subject == subject }

    /** Opens [subject] in the reader of [route] before the app is drawn. */
    private fun open(route: String, subject: String) = openMessage(mail, ui, route, message(subject))

    private fun launch(
        theme: ThemeMode,
        density: DensityPreference = DensityPreference.Touch,
        startRoute: String? = null,
        source: MailSource = mail,
        accounts: AccountActions = FakeAccounts(),
        notifications: NotifyGate? = null,
    ) {
        val prefs = testPrefs(theme, density)
        settings = testSettings()
        // the test activity's theme has an action bar; the app's has none
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            DeskMailApp(
                mail = source,
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
                palette = palette,
                messageBody = { content, _, modifier -> PlainMessageBody(content, modifier) },
                notifications = notifications,
            )
        }
        compose.waitForIdle()
    }

    /** The leave to show notifications as the system has it; nobody is asked while a picture is taken. */
    private fun gate(allowed: Boolean) = NotifyGate(ask = {}, openSystemSettings = {}).apply {
        this.allowed = allowed
        asked = true
        batteryAsked = true
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

    /** Rows picked with a finger: the bar over the list's end says how many, and acts on them all. */
    @Test @Config(qualifiers = PHONE)
    fun phoneSelectionLight() {
        val inbox = mail.messages(MailView.Inbox)
        ui.pick(Routes.INBOX, inbox[0].id)
        ui.togglePicked(Routes.INBOX, inbox[2].id)
        launch(ThemeMode.Light)
        capture("phone-selection-light")
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

    /** A message of the user's whose answer did not come in time, among those waited for. */
    @Test @Config(qualifiers = PHONE)
    fun phoneAwaitingLight() {
        open(Routes.AWAITING, "Kitap ayırtma")
        launch(ThemeMode.Light, startRoute = Routes.AWAITING)
        capture("phone-awaiting-light")
    }

    /** A snoozed message: when it comes back, and the way to have it back now. */
    @Test @Config(qualifiers = PHONE)
    fun phoneSnoozedDark() {
        val bill = message("Kasım 2026 faturanız hazır")
        kotlinx.coroutines.runBlocking { mail.snooze(listOf(bill.id), com.deskmail.TEST_NOW.plusSeconds(20 * 3600)) }
        openMessage(mail, ui, Routes.SNOOZED, mail.message(bill.id)!!)
        launch(ThemeMode.Dark, startRoute = Routes.SNOOZED)
        capture("phone-snoozed-dark")
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
        launch(ThemeMode.Light, startRoute = Routes.SETTINGS, notifications = gate(allowed = true))
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

    /** Before the first account: the inbox says how to get one. */
    @Test @Config(qualifiers = PHONE)
    fun phoneWelcomeLight() {
        launch(ThemeMode.Light, source = noMail())
        capture("phone-welcome-light")
    }

    /** An account that has to sign in again says so above the list. */
    @Test @Config(qualifiers = PHONE)
    fun phoneAccountNoticeDark() {
        mail.setAccountState(DemoMail.GMAIL, AccountState.NeedsLogin)
        launch(ThemeMode.Dark)
        capture("phone-account-notice-dark")
    }

    /** "Add an account" at the step Gmail calls for: an app password. */
    @Test @Config(qualifiers = PHONE)
    fun phoneAddAccountDark() {
        val gmail = FakeAccounts(plan = SetupPlan.Found(ProviderTable.Gmail.settingsFor("deniz.aksoy@gmail.com"), ProviderTable.Gmail))
        settingsUi.addAccount()
        settingsUi.setup.address = "deniz.aksoy@gmail.com"
        runBlocking { settingsUi.setup.lookUp(gmail) }
        launch(ThemeMode.Dark, startRoute = Routes.SETTINGS, accounts = gmail)
        capture("phone-add-account-dark")
    }

    /** A message whose text could not be fetched: the header is there, and a way to try again. */
    @Test @Config(qualifiers = PHONE)
    fun phoneReaderFailedLight() {
        val breakfast = message("Cumartesi kahvaltı?")
        mail.setContent(breakfast.id, MailContent.Failed(MailException.Kind.Network))
        open(Routes.INBOX, "Cumartesi kahvaltı?")
        launch(ThemeMode.Light)
        capture("phone-reader-failed-light")
    }

    /** A reply as it is begun on a phone: who it goes to, the subject, and the message quoted under where the answer goes. */
    @Test @Config(qualifiers = PHONE)
    fun phoneComposeDark() {
        val id = runBlocking { composer.start(DraftKind.Reply, sourceId = message("Cumartesi kahvaltı?").id) }!!
        ui.writing = Writing(id, Routes.INBOX)
        launch(ThemeMode.Dark)
        capture("phone-compose-dark")
    }

    /** The outbox: a message that waits to be sent, one the server would not take, and a draft. */
    @Test @Config(qualifiers = PHONE)
    fun phoneOutboxLight() {
        composer.put(
            Draft(
                0, DemoMail.OUTLOOK,
                to = "Zeynep Kaya <zeynep.kaya@posta.example>",
                subject = "Kapadokya fotoğrafları",
                body = "Zeynep selam,\n\nFotoğrafların hepsini bir klasöre koydum, akşam bağlantısını gönderirim.",
            ),
        )
        composer.put(
            Draft(
                0, DemoMail.GMAIL,
                to = "Elif Şahin <elif.sahin@isyeri.example>",
                subject = "Toplantı kaydı",
                body = "Elif merhaba, kayıt ekte.",
                state = DraftState.Failed,
                error = "552 5.3.4 Message size exceeds fixed limit",
            ),
        )
        composer.put(
            Draft(
                0, DemoMail.OUTLOOK,
                to = "Ayşe Yılmaz <ayse.yilmaz@posta.example>",
                subject = "Re: Cumartesi kahvaltı?",
                body = "Olur, geliyorum. Saat 10 iyi.",
                state = DraftState.Waiting,
            ),
        )
        launch(ThemeMode.Light, startRoute = Routes.OUTBOX)
        capture("phone-outbox-light")
    }

    /** The search with something found: three invoices, from two folders. */
    @Test @Config(qualifiers = PHONE)
    fun phoneSearchDark() {
        ui.search("fatura")
        launch(ThemeMode.Dark, startRoute = Routes.SEARCH)
        capture("phone-search-dark")
    }

    // ------------------------------------------------------------------ tablet

    /** Upright: the rail, and the list alone (a message would slide over it). */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitInboxDark() {
        launch(ThemeMode.Dark)
        capture("tablet-portrait-inbox-dark")
    }

    /** "Add an account" for an address nobody knows: the servers by hand. */
    @Test @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitServersLight() {
        settingsUi.addAccount()
        settingsUi.setup.address = "deniz@kendi.example"
        runBlocking { settingsUi.setup.lookUp(FakeAccounts(plan = SetupPlan.Manual)) }
        launch(ThemeMode.Light, startRoute = Routes.SETTINGS)
        capture("tablet-portrait-servers-light")
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

    /** Several rows of the table selected with the mouse: the bar under the list. */
    @Test @Config(qualifiers = DEX)
    fun dexSelectionDark() {
        val inbox = mail.messages(MailView.Inbox)
        open(Routes.INBOX, inbox[0].subject)
        ui.select(Routes.INBOX, setOf(inbox[0].id, inbox[1].id, inbox[3].id))
        launch(ThemeMode.Dark, DensityPreference.Pointer)
        capture("dex-selection-dark")
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
        // notifications not allowed yet: Settings says so and offers to ask
        launch(ThemeMode.Dark, DensityPreference.Pointer, startRoute = Routes.SETTINGS, notifications = gate(allowed = false))
        capture("dex-settings-dark")
    }

    /** The search in a DeX window: what is found as a table, one of them open beside it. */
    @Test @Config(qualifiers = DEX)
    fun dexSearchLight() {
        ui.search("fatura")
        open(Routes.SEARCH, "Kasım 2026 faturanız hazır")
        launch(ThemeMode.Light, DensityPreference.Pointer, startRoute = Routes.SEARCH)
        capture("dex-search-light")
    }

    /** A new message in a DeX window: two accounts to send from, a copy to somebody, and a file. */
    @Test @Config(qualifiers = DEX)
    fun dexComposeLight() {
        ui.writing = Writing(projectPlan(), Routes.INBOX)
        launch(ThemeMode.Light, DensityPreference.Pointer)
        capture("dex-compose-light")
    }

    /** The same message in a window of its own, as DeX opens it beside the window with the mail. */
    @Test @Config(qualifiers = WRITE_WINDOW)
    fun dexWriteWindowDark() {
        val id = projectPlan()
        val prefs = testPrefs(ThemeMode.Dark, DensityPreference.Pointer)
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent { WriteWindow(Writing(id, Routes.OUTBOX), composer, mail, remember { ComposeHost() }, onEnd = {}, prefs = prefs) }
        capture("dex-write-window-dark")
    }

    /** A message that is being written, with a copy to somebody and a file: the draft's id. */
    private fun projectPlan(): Long =
        runBlocking {
            val id = composer.start(accountId = DemoMail.OUTLOOK)!!
            composer.save(
                id, DemoMail.OUTLOOK,
                to = "Mehmet Demir <mehmet.demir@isyeri.example>",
                cc = "Elif Şahin <elif.sahin@isyeri.example>",
                bcc = "",
                subject = "Proje planı (v4)",
                body = "Mehmet selam,\n\nPlanın dördüncü sürümü ekte. Senkron işini iki haftada bıraktım, bildirimleri bir hafta daha öne aldım.\n\n" +
                    "Perşembe 14.00 uygunsa üstünden geçelim.\n\nDeniz",
            )
            composer.attach(id, "Proje planı v4.pdf", "application/pdf") { ByteArray(312_000).inputStream() }
            id
        }

    /** The inspector's "Security" on DeX: the provider's word on where the open message came from. */
    @Test @Config(qualifiers = DEX)
    fun dexSecurityLight() {
        open(Routes.INBOX, "Re: Proje planı (v3)")
        ui.inspector.updateCollapsed(false)
        ui.inspector.selectedTab = 2
        launch(ThemeMode.Light, DensityPreference.Pointer)
        capture("dex-security-light")
    }

    /** An account's page, for one the server no longer lets in. */
    @Test @Config(qualifiers = DEX)
    fun dexAccountLight() {
        mail.setAccountState(DemoMail.GMAIL, AccountState.NeedsLogin, "[AUTHENTICATIONFAILED] Invalid credentials (Failure)")
        settingsUi.page = SettingsPage.Account(DemoMail.GMAIL)
        launch(ThemeMode.Light, DensityPreference.Pointer, startRoute = Routes.SETTINGS)
        capture("dex-account-light")
    }

    companion object {
        private const val PHONE = "tr-w411dp-h891dp-xhdpi"
        private const val TABLET_PORTRAIT = "tr-w924dp-h1480dp-hdpi"
        private const val TABLET_LANDSCAPE = "tr-w1480dp-h924dp-hdpi"
        private const val DEX = "tr-w1920dp-h1080dp-mdpi"

        /** A window of its own for writing, at the size it opens at on DeX. */
        private const val WRITE_WINDOW = "tr-w720dp-h760dp-mdpi"

        private val directory = File(System.getProperty("deskmail.screenshotDir") ?: "build/outputs/screenshots")
    }
}
