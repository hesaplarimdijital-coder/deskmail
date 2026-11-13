package com.deskmail.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TEST_NOW
import com.deskmail.TestApp
import com.deskmail.core.mail.FolderRole
import com.deskmail.mail.MailView
import com.deskmail.testComposer
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.SnoozeTags
import com.deskmail.ui.mail.openMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

/** Snoozing on a phone: until when, the list of what is snoozed, and bringing a message back. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class SnoozeUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val composer = testComposer(mail)
    private val ui = MailUiState()

    private fun launch(startRoute: String? = null) {
        compose.setContent { TestApp(mail, testPrefs(), testSettings(), ui, startRoute = startRoute, composer = composer) }
        compose.waitForIdle()
    }

    private fun press(label: String) {
        compose.onAllNodesWithContentDescription(label)[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun shows(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private val bill get() = mail.messages.first { it.subject == "Kasım 2026 faturanız hazır" }

    @Test
    fun aMessageIsSnoozedUntilTomorrowMorning() {
        openMessage(mail, ui, Routes.INBOX, bill)
        launch()

        // the reader's actions beyond the bar's room are under "More options"
        press("Diğer seçenekler")
        click("Ertele")
        compose.onNodeWithTag(SnoozeTags.DIALOG).assertExists()
        click("Yarın sabah")

        shows("1 ileti ertelendi")
        val snoozed = mail.message(bill.id)!!
        assertEquals(FolderRole.Snoozed, mail.folder(snoozed.folderId)!!.role)
        // tomorrow at eight, in the clock's own zone
        val tomorrow = ZonedDateTime.ofInstant(TEST_NOW, ZoneId.of("Europe/Istanbul")).toLocalDate().plusDays(1).atTime(8, 0).atZone(ZoneId.of("Europe/Istanbul")).toInstant()
        assertEquals(tomorrow, mail.snoozedUntil(snoozed))
        assertFalse(bill.id in mail.messages(MailView.Inbox).map { it.id })
        assertTrue(bill.id in mail.messages(MailView.Snoozed).map { it.id })
    }

    @Test
    fun aSnoozedMessageSaysWhenItComesBackAndComesBackNow() {
        runBlocking { mail.snooze(listOf(bill.id), TEST_NOW.plusSeconds(3600)) }
        openMessage(mail, ui, Routes.SNOOZED, mail.message(bill.id)!!)
        launch(startRoute = Routes.SNOOZED)

        compose.onNodeWithTag(SnoozeTags.STRIP).assertExists()
        shows("Ertelendi; dönüş:")
        click("Şimdi geri getir")

        shows("1 ileti geri getirildi")
        assertEquals(FolderRole.Inbox, mail.folder(mail.message(bill.id)!!.folderId)!!.role)
        assertTrue(mail.messages(MailView.Snoozed).isEmpty())
    }

    @Test
    fun nothingSnoozedSaysWhatSnoozingIs() {
        launch(startRoute = Routes.SNOOZED)
        shows("Ertelenen ileti yok")
    }
}
