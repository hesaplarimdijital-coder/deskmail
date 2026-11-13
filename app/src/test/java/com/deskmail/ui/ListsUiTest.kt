package com.deskmail.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TestApp
import com.deskmail.mail.MailView
import com.deskmail.testComposer
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.compose.ComposeTags
import com.deskmail.ui.compose.TemplateTags
import com.deskmail.ui.mail.MailTags
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.openMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the user keeps for himself, on a phone: texts put into a message ("Template"), searches
 * kept as folders, VIPs and the senders whose pictures are always shown.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class ListsUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val composer = testComposer(mail)
    private val ui = MailUiState()

    private fun launch(startRoute: String? = null) {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(mail, prefs, settings, ui, startRoute = startRoute, composer = composer) }
        compose.waitForIdle()
    }

    /** Presses what is named [label]: by its semantics action, as a page may lie under the phone's bars. */
    private fun press(label: String) {
        compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun shows(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aKeptTextGoesIntoTheMessageAboveTheSignature() {
        runBlocking { composer.saveTemplate(0, "Toplantı", "Perşembe 14.00'te görüşelim.") }
        launch()
        press("Yeni ileti")
        compose.onNodeWithTag(ComposeTags.BODY).performTextInput("Selam,")

        compose.onNodeWithTag(ComposeTags.TEMPLATES).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        click("Toplantı")

        val body = ui.writing!!.fields.body
        assertTrue(body, body.startsWith("Selam,\n\nPerşembe 14.00'te görüşelim."))
    }

    @Test
    fun whatWasWrittenIsKeptAsATemplate() {
        launch()
        press("Yeni ileti")
        compose.onNodeWithTag(ComposeTags.BODY).performTextInput("Toplantı notları ekte.\nSorusu olan yazsın.")

        compose.onNodeWithTag(ComposeTags.TEMPLATES).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        click("Yazdığını şablon olarak kaydet")
        // its first line is offered as its name
        compose.onNodeWithTag(TemplateTags.NAME).performTextReplacement("Notlar")
        click("Kaydet")

        shows("Şablon kaydedildi.")
        assertEquals("Notlar", composer.templates.single().name)
        assertEquals("Toplantı notları ekte.\nSorusu olan yazsın.", composer.templates.single().body)
    }

    @Test
    fun theTemplatesAreWrittenChangedAndDeletedInSettings() {
        launch(startRoute = Routes.SETTINGS)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Yeni şablon"))
        click("Yeni şablon")
        compose.onNodeWithTag(TemplateTags.NAME).performTextInput("Teşekkür")
        compose.onNodeWithTag(TemplateTags.BODY).performTextInput("Çok teşekkürler.")
        click("Kaydet")
        compose.waitUntil(5_000) { composer.templates.isNotEmpty() }
        assertEquals("Çok teşekkürler.", composer.templates.single().body)

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Teşekkür"))
        compose.onAllNodesWithText("Düzenle")[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        click("Sil")
        shows("Şablon silindi.")
        assertTrue(composer.templates.isEmpty())
    }

    /** A search kept as a folder: offered under what is found, and named when it is kept. */
    @Test
    fun aSearchIsKeptAsAFolderOfItsOwn() {
        ui.search("fatura")
        launch(startRoute = Routes.SEARCH)

        compose.onNodeWithText("Kayıtlı aramalara ekle").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag(MailTags.SAVE_SEARCH_NAME).performTextReplacement("Faturalar")
        click("Kaydet")

        shows("Arama kaydedildi.")
        val saved = mail.savedSearches.single()
        assertEquals("Faturalar", saved.name)
        assertEquals("fatura", saved.query)
        assertEquals(mail.messages(MailView.Search("fatura")), mail.messages(MailView.Saved(saved.id)))
    }

    /** The folder of a saved search lists what its search finds, under the name it was given. */
    @Test
    fun aSavedSearchListsWhatItFinds() {
        val id = runBlocking { mail.saveSearch(0, "Faturalar", "fatura") }
        launch(startRoute = Routes.saved(id))

        compose.onNodeWithText("Faturalar").assertExists()
        compose.onNodeWithText("Kasım 2026 faturanız hazır", substring = true).assertExists()
    }

    @Test
    fun aSenderBecomesAVipFromTheirMessage() {
        val plan = mail.messages.first { it.subject == "Re: Proje planı (v3)" }
        openMessage(mail, ui, Routes.INBOX, plan)
        launch()

        press("Göndereni VIP yap")

        assertTrue(mail.sender("mehmet.demir@isyeri.example").vip)
        assertTrue(plan in mail.messages(MailView.Vip))
        // and back
        press("Göndereni VIP'lerden çıkar")
        assertFalse(mail.sender("mehmet.demir@isyeri.example").vip)
    }

    @Test
    fun theVipFolderSaysHowSomebodyBecomesOne() {
        launch(startRoute = Routes.VIP)
        shows("Henüz VIP yok")
    }

    /** A sender's pictures every time: said once, under the notice that they were kept out. */
    @Test
    fun aSendersPicturesCanAlwaysBeShown() {
        val newsletter = mail.messages.first { it.subject.startsWith("Kasım bülteni") }
        openMessage(mail, ui, Routes.INBOX, newsletter)
        launch()
        compose.onNodeWithText("Uzak görseller yüklenmedi.").assertIsDisplayed()

        click("Bu göndericinin görsellerini hep göster")

        assertTrue(mail.sender("bulten@sahaf.example").images)
        compose.onNodeWithText("Uzak görseller yüklenmedi.").assertDoesNotExist()
        // another of the sender's messages shows them as well, without being asked
        assertFalse(ui.showsImages(newsletter.id))
    }
}
