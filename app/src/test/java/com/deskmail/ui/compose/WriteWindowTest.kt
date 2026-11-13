package com.deskmail.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.demo.DemoMail
import com.deskmail.mail.Draft
import com.deskmail.mail.DraftKind
import com.deskmail.mail.DraftState
import com.deskmail.testComposer
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.ui.Routes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The window of its own that writes one message (DeX): the compose screen alone, with its actions
 * on the page however narrow the window is made, and an end for every way it is left. (What the
 * window with the mail makes of that end: WriteApartTest.)
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class WriteWindowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val composer = testComposer(mail)
    private val ends = ArrayList<WriteEnd>()
    private val titles = ArrayList<String>()

    private fun launch(writing: Writing) {
        val prefs = testPrefs()
        compose.setContent {
            WriteWindow(writing, composer, mail, remember { ComposeHost() }, onEnd = { ends += it }, prefs = prefs, onTitle = { titles += it })
        }
        compose.waitForIdle()
    }

    private fun begun(): Writing = Writing(runBlocking { composer.start(DraftKind.New, DemoMail.OUTLOOK) }!!, Routes.OUTBOX, fresh = true)

    private fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).performTextInput(text)
        compose.waitForIdle()
    }

    @Test
    fun theActionsAreOnThePageEvenWhenTheWindowIsNarrow() {
        launch(begun())

        compose.onNodeWithText("Gönder").assertIsDisplayed()
        compose.onNodeWithText("Dosya ekle").assertIsDisplayed()
        compose.onNodeWithContentDescription("Taslağı sil").assertIsDisplayed()
        compose.onNodeWithContentDescription("Kapat (taslak kalır)").assertIsDisplayed()
        // the window is named for what it writes: "New message" until there is a subject
        assertEquals("Yeni ileti", titles.last())
        type(ComposeTags.SUBJECT, "Cumartesi")
        assertEquals("Cumartesi", titles.last())
    }

    @Test
    fun aMessageBegunForTheWindowAndLeftEmptyIsNotKept() {
        val writing = begun()
        launch(writing)

        compose.onNodeWithContentDescription("Kapat (taslak kalır)").performClick()
        compose.waitForIdle()

        assertEquals(listOf<WriteEnd>(WriteEnd.Untouched), ends)
        // nothing of it is kept on the way out
        assertTrue(writing.fields.thrownAway)
    }

    @Test
    fun whatWasWrittenIsKeptWhenTheWindowCloses() {
        launch(begun())
        type(ComposeTags.SUBJECT, "Yarım kalan")

        compose.onNodeWithContentDescription("Kapat (taslak kalır)").performClick()
        compose.waitForIdle()

        assertEquals(listOf<WriteEnd>(WriteEnd.Kept), ends)
    }

    @Test
    fun aDraftSomebodyWroteIsKeptEvenUntouched() {
        val id = composer.put(Draft(0, DemoMail.OUTLOOK, to = "ayse.yilmaz@posta.example", subject = "Yarım kalan", body = "Devamı gelecek."))
        val writing = Writing(id, Routes.OUTBOX)
        launch(writing)

        assertEquals("Yarım kalan", writing.fields.subject)
        compose.onNodeWithContentDescription("Kapat (taslak kalır)").performClick()
        compose.waitForIdle()

        assertEquals(listOf<WriteEnd>(WriteEnd.Kept), ends)
    }

    @Test
    fun throwingTheMessageAwayEndsTheWindow() {
        val writing = begun()
        launch(writing)
        type(ComposeTags.SUBJECT, "Gereksiz")

        compose.onNodeWithContentDescription("Taslağı sil").performClick()
        compose.waitForIdle()

        assertEquals(listOf<WriteEnd>(WriteEnd.Discarded), ends)
        assertTrue(writing.fields.thrownAway)
    }

    @Test
    fun sendHandsTheMessageOverAndEndsTheWindow() {
        launch(begun())
        type(ComposeTags.TO, "ayse.yilmaz@posta.example")
        type(ComposeTags.SUBJECT, "Cumartesi")

        compose.onNodeWithText("Gönder").performClick()
        compose.waitForIdle()

        val sent = ends.single() as WriteEnd.Sent
        assertEquals(composer.undoMs, sent.check.undoMs)
        assertEquals(DraftState.Waiting, composer.outbox.single().state)
        assertEquals("Cumartesi", composer.outbox.single().subject)
    }

    @Test
    fun whatIsWrongWithTheRecipientsIsSaidInTheWindow() {
        launch(begun())
        type(ComposeTags.SUBJECT, "Alıcısız")

        compose.onNodeWithText("Gönder").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Kime gideceğini yaz.").assertExists()
        assertTrue(ends.isEmpty())
    }
}
