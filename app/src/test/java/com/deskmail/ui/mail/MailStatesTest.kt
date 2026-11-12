package com.deskmail.ui.mail

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.FakeAccounts
import com.deskmail.R
import com.deskmail.TestApp
import com.deskmail.core.mail.MailException
import com.deskmail.demo.DemoMail
import com.deskmail.mail.AccountState
import com.deskmail.mail.MailContent
import com.deskmail.mail.MailSource
import com.deskmail.mail.MailView
import com.deskmail.noMail
import com.deskmail.settings.AppSettings
import com.deskmail.settings.WebLayer
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.Routes
import com.deskmail.ui.settings.AccountTags
import com.deskmail.ui.settings.SettingsPage
import com.deskmail.ui.settings.SettingsUiState
import desktopkit.theme.GlassMode
import desktopkit.theme.LocalGlassMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the screens show when the mail is not simply there: no account yet, an account in trouble,
 * a text that is missing, images that were kept from loading, and mail that can only be read.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class MailStatesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val ui = MailUiState()
    private val settingsUi = SettingsUiState()
    private val accounts = FakeAccounts()

    private fun launch(source: MailSource = mail) {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(source, prefs, settings, ui, accounts = accounts, settingsUi = settingsUi) }
        compose.waitForIdle()
    }

    private fun message(subject: String) = mail.messages.first { it.subject == subject }

    @Test
    fun withoutAnAccountTheInboxOffersToAddOne() {
        launch(noMail())
        compose.onNodeWithText("Henüz hesap yok").assertIsDisplayed()
        // no chips over nothing
        compose.onNodeWithText("Tümü").assertDoesNotExist()

        compose.onNodeWithText("Hesap ekle").performClick()
        compose.waitForIdle()
        // Settings, with "Add an account" open at the address
        assertEquals(SettingsPage.AddAccount, settingsUi.page)
        compose.onNodeWithTag(AccountTags.ADDRESS).assertExists()
    }

    /** The glass the shell hands to what is inside it, as a message's body sees it; [tall] is what the body says of itself. */
    private fun launchSeeingTheGlass(seen: MutableList<GlassMode?>, settings: AppSettings, tall: Boolean = false) {
        val prefs = testPrefs()
        compose.setContent {
            TestApp(mail, prefs, settings, ui, accounts = accounts, settingsUi = settingsUi, messageBody = { content, _, modifier ->
                // what the app puts beside its WebView; the body itself is plain text here
                WebBodyPresence(ui, tall)
                val mode = LocalGlassMode.current
                SideEffect { seen += mode }
                PlainMessageBody(content, modifier)
            })
        }
        compose.waitForIdle()
    }

    @Test
    fun theGlassStaysOverAMessage() {
        val seen = ArrayList<GlassMode?>()
        val settings = testSettings()
        assertTrue(settings.glass && settings.glassOverMessages)
        launchSeeingTheGlass(seen, settings)

        compose.runOnUiThread { openMessage(mail, ui, Routes.INBOX, message("Cumartesi kahvaltı?")) }
        compose.waitForIdle()
        assertEquals(1, ui.webBodies)
        assertEquals(0, ui.tallWebBodies)
        // what the device allows (a test's device: translucent), not taken away by the message
        assertEquals(listOf<GlassMode?>(GlassMode.Translucent), seen.distinct())
    }

    @Test
    fun aMessageTooTallForALayerTakesTheGlassAway() {
        val seen = ArrayList<GlassMode?>()
        launchSeeingTheGlass(seen, testSettings(), tall = true)

        compose.runOnUiThread { openMessage(mail, ui, Routes.INBOX, message("Cumartesi kahvaltı?")) }
        compose.waitForIdle()
        assertEquals(1, ui.tallWebBodies)
        // solid: nothing of the screen is drawn a second time for a blur
        assertEquals(GlassMode.Solid, seen.last())

        // the message is closed and its body gone: the glass is back
        compose.runOnUiThread { ui.close(Routes.INBOX) }
        compose.waitForIdle()
        assertEquals(0, ui.webBodies)
        assertEquals(0, ui.tallWebBodies)
    }

    @Test
    fun withTheExperimentOffNoMessageIsUnderGlass() {
        val seen = ArrayList<GlassMode?>()
        val settings = testSettings().apply { updateGlassOverMessages(false) }
        launchSeeingTheGlass(seen, settings)

        compose.runOnUiThread { openMessage(mail, ui, Routes.INBOX, message("Cumartesi kahvaltı?")) }
        compose.waitForIdle()
        assertEquals(1, ui.webBodies)
        // solid from the first time the body was drawn
        assertEquals(listOf<GlassMode?>(GlassMode.Solid), seen.distinct())
    }

    @Test
    fun whenTheShellsGlassIsOn() {
        fun glass(wanted: Boolean = true, overMessages: Boolean = true, messageOpen: Boolean = false, webBodies: Int = 0, tallBodies: Int = 0) =
            shellGlass(wanted, overMessages, messageOpen, webBodies, tallBodies)
        assertTrue(glass())
        // over a message too: its WebView draws into a layer of its own
        assertTrue(glass(messageOpen = true, webBodies = 1))
        // but not over one too tall for such a layer, for as long as it is on screen
        assertFalse(glass(messageOpen = true, webBodies = 1, tallBodies = 1))
        assertFalse(glass(webBodies = 1, tallBodies = 1))
        // the experiment off: no glass from the tap that opens a message until its WebView has left
        assertFalse(glass(overMessages = false, messageOpen = true))
        assertFalse(glass(overMessages = false, webBodies = 1))
        assertTrue(glass(overMessages = false))
        // glass the user turned off stays off
        assertFalse(glass(wanted = false))
        assertFalse(glass(wanted = false, overMessages = false))
    }

    @Test
    fun whatAMessagesWebViewDrawsInto() {
        // the app's choice: a layer of its own under blurred glass, none where nothing blurs or the message is too tall
        assertEquals(View.LAYER_TYPE_HARDWARE, webLayerFor(WebLayer.Default, blurred = true, tall = false))
        assertEquals(View.LAYER_TYPE_NONE, webLayerFor(WebLayer.Default, blurred = false, tall = false))
        assertEquals(View.LAYER_TYPE_NONE, webLayerFor(WebLayer.Default, blurred = true, tall = true))
        // an experiment's choice holds whatever the glass does
        for (blurred in listOf(true, false)) {
            assertEquals(View.LAYER_TYPE_NONE, webLayerFor(WebLayer.None, blurred, tall = false))
            assertEquals(View.LAYER_TYPE_HARDWARE, webLayerFor(WebLayer.Hardware, blurred, tall = true))
            assertEquals(View.LAYER_TYPE_SOFTWARE, webLayerFor(WebLayer.Software, blurred, tall = false))
        }
    }

    @Test
    fun howTallAMessageMayBeForALayerOfItsOwn() {
        // not laid out yet, a screen, a few screens
        assertTrue(LayerLimits.fits(0, 0, maxSide = 16384))
        assertTrue(LayerLimits.fits(1080, 2340, maxSide = 16384))
        assertTrue(LayerLimits.fits(1080, 12_000, maxSide = 16384))
        // more than the layer may take in memory (64 MB: 15 534 pixels at this width)
        assertTrue(LayerLimits.fits(1080, 15_534, maxSide = 16384))
        assertFalse(LayerLimits.fits(1080, 15_535, maxSide = 16384))
        // a wider pane reaches that sooner
        assertFalse(LayerLimits.fits(1500, 12_000, maxSide = 16384))
        // longer than a texture can be on the device, whatever the memory
        assertFalse(LayerLimits.fits(1080, 9_000, maxSide = 8192))
        assertFalse(LayerLimits.fits(9_000, 100, maxSide = 8192))
        // until a hardware canvas has said more, the limit is what every device can do
        assertEquals(LayerLimits.SAFE_SIDE, LayerLimits.maxSide)
        // a body that goes is counted out once, and never below nothing
        val window = MailUiState()
        window.webBodyShown(tall = true)
        window.webBodyGone(tall = true)
        window.webBodyGone(tall = true)
        assertEquals(0, window.webBodies)
        assertEquals(0, window.tallWebBodies)
    }

    @Test
    fun aListWhoseMailIsOnItsWayDoesNotSayItIsEmpty() {
        // accounts that were just added: their folders are there, their mail is being fetched
        mail.clearMessages()
        mail.syncing = true
        launch()
        compose.onNodeWithContentDescription("Eşitleniyor").assertExists()
        compose.onNodeWithText("Burada ileti yok").assertDoesNotExist()

        // the sync is over, and there really is nothing
        mail.syncing = false
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Eşitleniyor").assertDoesNotExist()
        compose.onNodeWithText("Burada ileti yok").assertIsDisplayed()
    }

    @Test
    fun aScreenThatComesToTheFrontAsksForItsMail() {
        launch()
        assertEquals(setOf<MailView>(MailView.Inbox), mail.shown.toSet())
    }

    @Test
    fun anAccountThatHasToSignInAgainSaysSoAboveTheList() {
        mail.setAccountState(DemoMail.GMAIL, AccountState.NeedsLogin, "[AUTHENTICATIONFAILED] Invalid credentials")
        launch()
        compose.onNodeWithText("Gmail hesabının yeniden giriş yapması gerekiyor.").assertIsDisplayed()

        compose.onNodeWithText("Yeniden bağlan").performClick()
        compose.waitForIdle()
        // the account's page in Settings, at the password
        assertEquals(SettingsPage.Account(DemoMail.GMAIL), settingsUi.page)
        compose.onNodeWithTag(AccountTags.PASSWORD).assertExists()
    }

    @Test
    fun anAccountThatCouldNotBeReachedOffersToTryAgain() {
        mail.setAccountState(DemoMail.OUTLOOK, AccountState.Unreachable, "Connection refused")
        launch()
        compose.onNodeWithText("Outlook: sunucuya ulaşılamadı.").assertIsDisplayed()

        compose.onNodeWithText("Yeniden dene").performClick()
        compose.waitForIdle()
        assertEquals(listOf<MailView?>(MailView.Inbox), mail.refreshed)

        // all well again: the strip goes
        compose.runOnUiThread { mail.setAccountState(DemoMail.OUTLOOK, AccountState.Ok) }
        compose.waitForIdle()
        compose.onNodeWithText("Outlook: sunucuya ulaşılamadı.").assertDoesNotExist()
    }

    @Test
    fun aTextThatCouldNotBeFetchedOffersToTryAgain() {
        val breakfast = message("Cumartesi kahvaltı?")
        mail.setContent(breakfast.id, MailContent.Failed(MailException.Kind.Network))
        openMessage(mail, ui, Routes.INBOX, breakfast)
        launch()
        // the header is there: it is the list's
        compose.onNodeWithText("Cumartesi kahvaltı?").assertIsDisplayed()
        compose.onNodeWithText("İleti alınamadı: sunucuya ulaşılamıyor.").assertIsDisplayed()

        compose.onNodeWithText("Yeniden dene").performClick()
        compose.waitForIdle()
        assertEquals(listOf(breakfast.id), mail.retried)

        // fetched after all
        compose.runOnUiThread { mail.setContent(breakfast.id, null) }
        compose.waitForIdle()
        compose.onNodeWithText("İleti alınamadı: sunucuya ulaşılamıyor.").assertDoesNotExist()
        compose.onNodeWithText(mail.content(breakfast.id).text).assertExists()
    }

    @Test
    fun aMessageWhoseTextIsNotOnTheDeviceSaysSo() {
        val breakfast = message("Cumartesi kahvaltı?")
        mail.setContent(breakfast.id, MailContent.Unavailable)
        openMessage(mail, ui, Routes.INBOX, breakfast)
        launch()
        compose.onNodeWithText("Bu iletinin metni cihazda yok", substring = true).assertIsDisplayed()
    }

    @Test
    fun remoteImagesStayOutUntilTheyAreAskedFor() {
        val newsletter = mail.messages.first { it.subject.startsWith("Kasım bülteni") }
        openMessage(mail, ui, Routes.INBOX, newsletter)
        launch()
        compose.onNodeWithText("Uzak görseller yüklenmedi.").assertIsDisplayed()
        assertFalse(ui.showsImages(newsletter.id))

        compose.onNodeWithText("Göster").performClick()
        compose.waitForIdle()
        assertTrue(ui.showsImages(newsletter.id))
        compose.onNodeWithText("Uzak görseller yüklenmedi.").assertDoesNotExist()
        // for this message only
        assertFalse(ui.showsImages(message("Cumartesi kahvaltı?").id))
    }

    /** Mail that can only be read, as an account's is in this pre-release. */
    private class ReadOnly(private val demo: DemoMail) : MailSource by demo {
        override val canMark: Boolean get() = false

        override fun markRead(id: Long) = Unit
    }

    @Test
    fun mailThatCanOnlyBeReadKeepsItsMarks() {
        val breakfast = message("Cumartesi kahvaltı?")
        assertTrue(breakfast.unread)
        launch(ReadOnly(mail))

        compose.onNodeWithText("Cumartesi kahvaltı?", substring = true).performClick()
        compose.waitForIdle()
        // opened, and still unread: the marks are the server's
        compose.onNodeWithText("Cumartesi kahvaltı?").assertIsDisplayed()
        assertTrue(mail.message(breakfast.id)!!.unread)

        compose.onNodeWithTag(MailTags.READER).assertExists()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_star)).performClick()
        compose.waitForIdle()
        assertFalse(mail.message(breakfast.id)!!.starred)
        compose.onNodeWithText("Bu ön sürümde henüz yok", substring = true).assertExists()
    }
}
