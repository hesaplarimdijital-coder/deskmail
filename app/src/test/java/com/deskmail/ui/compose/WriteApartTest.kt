package com.deskmail.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TestApp
import com.deskmail.demo.DemoMail
import com.deskmail.mail.Draft
import com.deskmail.mail.DraftState
import com.deskmail.mail.SendCheck
import com.deskmail.testComposer
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.Routes
import com.deskmail.ui.mail.MailUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where windows are a desktop's (DeX), a message is written in a window of its own: what is begun
 * or taken up opens there, and what that window did with its message is said in the window with
 * the mail. (The window of its own: WriteWindowTest.)
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class WriteApartTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val composer = testComposer(mail)
    private val ui = MailUiState()
    private val apart = FakeApart()

    /** Whether the window with the mail is there at all. */
    private var shown by mutableStateOf(true)

    private fun launch(startRoute: String? = null) {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent {
            if (shown) TestApp(mail, prefs, settings, ui, startRoute = startRoute, composer = composer, writeApart = apart)
        }
        compose.waitForIdle()
    }

    private fun press(label: String) {
        compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun aNewMessageOpensInAWindowOfItsOwn() {
        launch()
        press("Yeni ileti")

        // begun, and handed to a window of its own: this one goes on showing the mail
        val draft = composer.outbox.single()
        assertEquals(listOf(draft.id to true), apart.opened)
        assertNull(ui.writing)
        compose.onNodeWithTag(ComposeTags.TO).assertDoesNotExist()
        compose.onNodeWithContentDescription("Yeni ileti").assertExists()
    }

    @Test
    fun aDraftOfTheOutboxOpensInAWindowOfItsOwn() {
        val kept = composer.put(Draft(0, DemoMail.OUTLOOK, to = "ayse.yilmaz@posta.example", subject = "Yarım kalan", body = "Devamı gelecek."))
        launch(startRoute = Routes.OUTBOX)

        compose.onNodeWithText("Taslak").performClick()
        compose.waitForIdle()

        // somebody wrote it: closed untouched, it stays
        assertEquals(listOf(kept to false), apart.opened)
        assertNull(ui.writing)
    }

    @Test
    fun whatAWindowOfItsOwnSentCanBeTakenBackHere() {
        val id = composer.put(Draft(0, DemoMail.OUTLOOK, to = "ayse.yilmaz@posta.example", subject = "Cumartesi", body = "Olur."))
        launch()
        val home = apart.home!!
        val check = runBlocking { composer.send(id) } as SendCheck.Queued

        // the window of its own handed the message over and went: the notice is this window's
        compose.runOnUiThread { home.sentApart(id, check) }
        compose.waitForIdle()
        compose.onNodeWithText("Gönderiliyor…").assertIsDisplayed()
        compose.onNodeWithText("Geri al").performClick()
        compose.waitForIdle()

        // taken back, and open to be written on again in a window of its own
        assertEquals(DraftState.Writing, composer.outbox.single().state)
        assertEquals(listOf(id to false), apart.opened)
        assertNull(ui.writing)
        assertTrue(composer.sent.isEmpty())
    }

    @Test
    fun whatAWindowOfItsOwnSentLeavesWhenItsTimeIsUp() {
        val id = composer.put(Draft(0, DemoMail.OUTLOOK, to = "ayse.yilmaz@posta.example", subject = "Cumartesi", body = "Olur."))
        launch()
        val check = runBlocking { composer.send(id) } as SendCheck.Queued

        compose.runOnUiThread { apart.home!!.sentApart(id, check) }
        compose.mainClock.advanceTimeBy(composer.undoMs + 1_000)
        compose.waitForIdle()

        assertEquals(listOf("Cumartesi"), composer.sent.map { it.subject })
        assertTrue(apart.opened.isEmpty())
    }

    @Test
    fun whatAWindowOfItsOwnHasToSayIsSaidHere() {
        launch()
        compose.runOnUiThread { apart.home!!.say("Taslak Giden kutusu'nda duruyor.") }
        compose.waitForIdle()
        compose.onNodeWithText("Taslak Giden kutusu'nda duruyor.").assertIsDisplayed()
    }

    @Test
    fun awayFromADesktopTheMessageIsWrittenInThisWindow() {
        apart.desktop = false
        launch()
        press("Yeni ileti")

        assertTrue(apart.opened.isEmpty())
        assertNotNull(ui.writing)
        compose.onNodeWithTag(ComposeTags.TO).assertExists()
    }

    @Test
    fun theWindowSaysWhereItsWritingIsOnlyWhileItIsThere() {
        launch()
        assertNotNull(apart.home)
        shown = false
        compose.waitForIdle()
        assertNull(apart.home)
    }

    /** Windows of their own as a test sees them: what was opened in one, and the window's writing. */
    private class FakeApart(var desktop: Boolean = true) : WriteApart {
        val opened = ArrayList<Pair<Long, Boolean>>()
        var home: ComposeActions? = null

        override fun now(): Boolean = desktop

        override fun open(draftId: Long, fresh: Boolean) {
            opened += draftId to fresh
        }

        override fun home(actions: ComposeActions?) {
            home = actions
        }
    }
}
