package com.deskmail.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.FakeAccounts
import com.deskmail.TestApp
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.MailSettings
import com.deskmail.mail.MessageTemplate
import com.deskmail.mail.PortableAccount
import com.deskmail.mail.SavedSearch
import com.deskmail.mail.SenderMarks
import com.deskmail.notify.NotifyGate
import com.deskmail.settings.AppSettings
import com.deskmail.settings.SettingsFile
import com.deskmail.testComposer
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.Routes
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

/** Settings on a phone: the kit's sections, then the experiments, whose switches change the app's settings. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class SettingsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Flips the switch row named [title]. By its semantics action, not by a tap at its place: the
     * list lies under the phone's bottom bar, and a row scrolled into view may rest beneath it.
     */
    private fun toggle(title: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun theExperimentSwitchesChangeTheSettings() {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(testMail(), prefs, settings, startRoute = Routes.SETTINGS) }
        assertTrue(settings.glass)
        toggle("Cam çubuklar")
        assertFalse(settings.glass)

        assertTrue(settings.darkenMessages)
        toggle("Koyu temada iletiyi karart")
        assertFalse(settings.darkenMessages)
    }

    /** How long "Send" can be taken back is chosen among a few times. */
    @Test
    fun theTimeToTakeAMessageBackIsChosen() {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(testMail(), prefs, settings, startRoute = Routes.SETTINGS) }
        assertEquals(10, settings.undoSendSeconds)

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("15 sn"))
        compose.onNodeWithText("15 sn").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertEquals(15, settings.undoSendSeconds)
    }

    /** How the mail is looked for while the app is closed is chosen here, and whoever sets that up hears of it. */
    @Test
    fun howTheMailIsLookedForIsChosen() {
        val prefs = testPrefs()
        val settings = testSettings()
        var chosen = 0
        compose.setContent { TestApp(testMail(), prefs, settings, startRoute = Routes.SETTINGS, onCheckChosen = { chosen++ }) }
        assertEquals(AppSettings.CHECK_INSTANT, settings.checkMinutes)

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Elle"))
        compose.onNodeWithText("Elle").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(0, settings.checkMinutes)
        assertEquals(1, chosen)

        compose.onNodeWithText("60 dk").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(60, settings.checkMinutes)
        assertEquals(2, chosen)

        compose.onNodeWithText("Anlık").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(AppSettings.CHECK_INSTANT, settings.checkMinutes)
        assertEquals(3, chosen)
    }

    /**
     * Mail is told of as it comes only where the system leaves the app alone in the background:
     * while that is what is chosen, the settings say how the system has it, with the way there.
     */
    @Test
    fun theBatterySettingIsShownWhileMailIsToldOfAsItComes() {
        val prefs = testPrefs()
        val settings = testSettings()
        var asked = 0
        var opened = 0
        val gate = NotifyGate(ask = {}, openSystemSettings = {}, askBattery = { asked++ }, openBatterySettings = { opened++ }).apply {
            this.asked = true
            allowed = true
            // led to the question before: it is not brought up unasked
            batteryAsked = true
        }
        compose.setContent { TestApp(testMail(), prefs, settings, startRoute = Routes.SETTINGS, notifications = gate) }
        compose.waitForIdle()

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Pil kısıtlaması açık"))
        compose.onNodeWithText("Kısıtlamayı kaldır").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, asked)

        // lifted, by the system's question or in its settings
        compose.runOnUiThread { gate.unrestricted = true }
        compose.waitForIdle()
        compose.onNodeWithText("Pil kısıtlaması yok").assertExists()
        compose.onNodeWithText("Kısıtlamayı kaldır").assertDoesNotExist()
        compose.onNodeWithText("Uygulama ayarları").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, opened)

        // looked for every so often instead: the battery is the system's own business then
        compose.onNodeWithText("30 dk").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Pil kısıtlaması yok").assertDoesNotExist()
        compose.onNodeWithText("Pil kısıtlaması açık").assertDoesNotExist()
    }

    /** Whether the system lets the app tell of new mail is said, with the way to change it. */
    @Test
    fun theLeaveToNotifyIsShownAndAskedFor() {
        val prefs = testPrefs()
        val settings = testSettings()
        var asked = 0
        var opened = 0
        // asked before: the app does not ask by itself again
        val gate = NotifyGate(ask = { asked++ }, openSystemSettings = { opened++ }).apply {
            this.asked = true
            batteryAsked = true
        }
        compose.setContent { TestApp(testMail(), prefs, settings, startRoute = Routes.SETTINGS, notifications = gate) }
        compose.waitForIdle()
        assertEquals(0, asked)

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Yeni iletiler bildirilmiyor"))
        compose.onNodeWithText("İzin ver").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, asked)

        // allowed: by the system's question, or in its settings
        compose.runOnUiThread { gate.allowed = true }
        compose.waitForIdle()
        compose.onNodeWithText("Yeni iletiler bildiriliyor").assertExists()
        compose.onNodeWithText("İzin ver").assertDoesNotExist()
        compose.onNodeWithText("Sistem ayarları").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    /** Where there is nobody to ask the system (no gate), only the interval is offered. */
    @Test
    fun withoutAGateOnlyTheIntervalIsOffered() {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(testMail(), prefs, settings, startRoute = Routes.SETTINGS) }

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Elle"))
        compose.onNodeWithText("İzin ver").assertDoesNotExist()
        compose.onNodeWithText("Sistem ayarları").assertDoesNotExist()
    }

    /** The settings go to a file the user picks a place for; nothing that signs in goes with them. */
    @Test
    fun theSettingsAreWrittenToAFileToTakeToAnotherDevice() {
        val prefs = testPrefs()
        val settings = testSettings()
        val accounts = FakeAccounts().apply { portableAccounts = listOf(ayse) }
        compose.setContent { TestApp(testMail(), prefs, settings, startRoute = Routes.SETTINGS, accounts = accounts) }
        settings.updateUndoSendSeconds(15)

        press("Dışa aktar")
        val asked = shadowOf(compose.activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, asked.action)
        assertEquals("deskmail-ayarlar.json", asked.getStringExtra(Intent.EXTRA_TITLE))
        // the place picked: a document of the system's, as the app is handed it
        val picked = Uri.parse("content://com.deskmail.test/moved.json")
        val file = ByteArrayOutputStream()
        shadowOf(compose.activity.contentResolver).registerOutputStream(picked, file)
        compose.runOnUiThread { shadowOf(compose.activity).receiveResult(asked, Activity.RESULT_OK, Intent().setData(picked)) }

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Ayarlar dosyaya yazıldı.").fetchSemanticsNodes().isNotEmpty() }
        val written = SettingsFile.read(file.toString(Charsets.UTF_8))!!
        assertEquals(listOf(ayse), written.accounts)
        assertEquals(15, written.settings["undo_send_seconds"])
        assertEquals(prefs.themeMode.name, written.look!!.theme)
    }

    /** A file from another device: its accounts are added (to wait for their passwords), its choices taken. */
    @Test
    fun theSettingsOfAnotherDeviceAreTakenFromItsFile() {
        val prefs = testPrefs()
        val settings = testSettings()
        val accounts = FakeAccounts()
        val mail = testMail()
        val composer = testComposer(mail)
        var chosen = 0
        compose.setContent { TestApp(mail, prefs, settings, startRoute = Routes.SETTINGS, accounts = accounts, composer = composer, onCheckChosen = { chosen++ }) }
        val text = SettingsFile.write(
            SettingsFile.Contents(
                accounts = listOf(ayse),
                settings = mapOf("check_minutes" to 60),
                look = SettingsFile.Look("Oled", prefs.accent.name, "Pointer"),
                templates = listOf(MessageTemplate(0, "Teşekkür", "Çok teşekkürler.")),
                searches = listOf(SavedSearch(0, "Faturalar", "fatura")),
                senders = mapOf("bulten@sahaf.example" to SenderMarks(images = true)),
            ),
            Instant.EPOCH,
        )
        val picked = Uri.parse("content://com.deskmail.test/from-elsewhere.json")
        shadowOf(compose.activity.contentResolver).registerInputStream(picked, ByteArrayInputStream(text.encodeToByteArray()))

        press("İçe aktar")
        val asked = shadowOf(compose.activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, asked.action)
        compose.runOnUiThread { shadowOf(compose.activity).receiveResult(asked, Activity.RESULT_OK, Intent().setData(picked)) }

        compose.waitUntil(5_000) { compose.onAllNodesWithText("1 hesap eklendi; şifresini hesabın sayfasında gir.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(ayse), accounts.adopted)
        assertEquals(60, settings.checkMinutes)
        assertEquals(1, chosen)
        assertEquals(ThemeMode.Oled, prefs.themeMode)
        assertEquals(DensityPreference.Pointer, prefs.density)
        // and the user's own lists
        assertEquals(listOf("Teşekkür"), composer.templates.map { it.name })
        assertEquals(listOf("fatura"), mail.savedSearches.map { it.query })
        assertTrue(mail.sender("bulten@sahaf.example").images)
    }

    @Test
    fun whatIsNoSettingsFileIsSaidSo() {
        val accounts = FakeAccounts()
        compose.setContent { TestApp(testMail(), testPrefs(), testSettings(), startRoute = Routes.SETTINGS, accounts = accounts) }
        val picked = Uri.parse("content://com.deskmail.test/photo.json")
        shadowOf(compose.activity.contentResolver).registerInputStream(picked, ByteArrayInputStream("not settings".encodeToByteArray()))

        press("İçe aktar")
        val asked = shadowOf(compose.activity).nextStartedActivityForResult.intent
        compose.runOnUiThread { shadowOf(compose.activity).receiveResult(asked, Activity.RESULT_OK, Intent().setData(picked)) }

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Bu bir DeskMail ayar dosyası değil.").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(accounts.adopted.isEmpty())
    }

    /** Presses the button named [label], scrolled into view first. */
    private fun press(label: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private val ayse = PortableAccount(
        address = "ayse@posta.example",
        name = "Posta",
        senderName = "Ayşe Yılmaz",
        provider = "",
        settings = MailSettings(ServerAddress("imap.posta.example", 993, Security.Tls), ServerAddress("smtp.posta.example", 465, Security.Tls), "ayse", AuthKind.Password),
        syncDays = 30,
        notifyMode = "push",
        signature = "",
    )
}
