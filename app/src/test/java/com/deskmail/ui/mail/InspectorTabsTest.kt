package com.deskmail.ui.mail

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TestApp
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.Routes
import desktopkit.theme.DensityPreference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The inspector's "Security" and "Raw headers": what the user's provider found of where a message
 * came from, and its header lines as they arrived. (Reading the provider's header: AuthResultsTest.)
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr-w1440dp-h900dp-mdpi")
class InspectorTabsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val ui = MailUiState()

    private fun launch(subject: String) {
        openMessage(mail, ui, Routes.INBOX, mail.messages.first { it.subject.startsWith(subject) })
        ui.inspector.updateCollapsed(false)
        val prefs = testPrefs(density = DensityPreference.Pointer)
        compose.setContent { TestApp(mail, prefs, testSettings(), ui) }
        compose.waitForIdle()
    }

    private fun tab(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    @Test
    fun aVerifiedSenderIsSaidSoInTheProvidersWords() {
        launch("Re: Proje planı (v3)")
        tab("Güvenlik")

        compose.onNodeWithText("Gönderici doğrulandı.").assertIsDisplayed()
        // DKIM signed for the domain, and DMARC found the sender's domain behind it
        compose.onAllNodesWithText("Geçti\nisyeri.example").assertCountEquals(2)
        compose.onNodeWithText("Geçti\nmehmet.demir@isyeri.example").assertExists()
        compose.onNodeWithText("Bu sonuçları sağlayıcın bildiriyor (mx.posta.example); DeskMail doğrulamayı kendisi yapmaz.").assertExists()
    }

    @Test
    fun aMessageThatFailedIsSaidToBeSuspect() {
        // Spam is a folder of the Outlook account's
        val junk = mail.messages.first { it.subject.startsWith("TEBRİKLER!") }
        openMessage(mail, ui, Routes.folder(junk.folderId), junk)
        ui.inspector.updateCollapsed(false)
        val prefs = testPrefs(density = DensityPreference.Pointer)
        compose.setContent { TestApp(mail, prefs, testSettings(), ui, startRoute = Routes.folder(junk.folderId)) }
        compose.waitForIdle()
        tab("Güvenlik")

        compose.onNodeWithText("Gönderici doğrulanamadı: ileti göründüğü yerden gelmemiş olabilir.").assertIsDisplayed()
        compose.onNodeWithText("Zayıf ret\nkazandiniz@odul.example").assertExists()
        compose.onNodeWithText("Geçmedi\nbanka.example").assertExists()
    }

    @Test
    fun theHeaderLinesAreShownAsTheyArrived() {
        launch("Re: Proje planı (v3)")
        tab("Ham başlıklar")

        compose.onNodeWithTag(MailTags.HEADERS).assertIsDisplayed()
        compose.onNodeWithText("Authentication-Results: mx.posta.example;", substring = true).assertExists()
        compose.onNodeWithText("Kopyala").assertIsDisplayed()
    }
}
