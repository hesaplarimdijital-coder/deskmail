package com.deskmail.ui.mail

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TestApp
import com.deskmail.mail.MailView
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The search screen on a phone: what is typed is looked for, and what is found is mail like any other. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class SearchTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val ui = MailUiState()

    private fun launch() {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(mail, prefs, settings, ui, startRoute = Routes.SEARCH) }
        compose.waitForIdle()
    }

    /** Types into the search field and lets the pause pass after which it is looked for. */
    private fun type(text: String) {
        compose.onNodeWithTag(MailTags.SEARCH).performTextReplacement(text)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /**
     * The search tab, as a thumb reaches it from the inbox: the field, and under it what to type.
     * (That "/" puts the cursor into the field is CommandsTest's. Whether the field also takes the
     * focus by itself when the screen is reached this way is not looked at here: under Robolectric
     * it does not, and what a device does is to be seen on a device.)
     */
    @Test
    fun theScreenOpensWithItsFieldAndSaysWhatToType() {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(mail, prefs, settings, ui) }
        compose.waitForIdle()

        compose.onNode(hasText("Ara") and isSelectable()).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MailTags.SEARCH).assertIsDisplayed()
        compose.onNodeWithText("Ne arıyorsun?").assertIsDisplayed()
        assertTrue(ui.searchQuery.isEmpty())
    }

    @Test
    fun whatIsTypedIsLookedForOnceTheTypingPauses() {
        launch()
        compose.onNodeWithTag(MailTags.SEARCH).performTextInput("kapadokya")
        compose.waitForIdle()
        assertEquals("kapadokya", ui.searchText)

        // a pause, and it is looked for
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        assertEquals("kapadokya", ui.searchQuery)
        compose.onNodeWithText("Ne arıyorsun?").assertDoesNotExist()
        compose.onNodeWithText("Kapadokya gezi notları", substring = true).assertIsDisplayed()
    }

    @Test
    fun whatIsFoundOpensAndIsReadLikeAnyOtherMail() {
        val notes = mail.messages.first { it.subject == "Kapadokya gezi notları" }
        mail.setUnread(notes.id, true)
        launch()
        type("kapadokya")

        compose.onNodeWithText("Kapadokya gezi notları", substring = true).performClick()
        compose.waitForIdle()

        assertEquals(notes.id, ui.openedId(Routes.SEARCH))
        assertFalse(mail.message(notes.id)!!.unread)
        // the reader, over what was found; Back returns to it with what was typed
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertNull(ui.openedId(Routes.SEARCH))
        assertEquals("kapadokya", ui.searchText)
    }

    @Test
    fun everyWordNarrowsAndTurkishLettersNeedNotBeTyped() {
        launch()
        type("fatura")
        val invoices = mail.messages(MailView.Search("fatura"))
        assertEquals(3, invoices.size)
        // two of them from the same sender
        compose.onAllNodesWithText("Elektrik Dağıtım", substring = true).assertCountEquals(2)

        type("fatura dogalgaz")
        assertEquals(listOf("Aralık 2025 dönemi faturanız"), mail.messages(MailView.Search(ui.searchQuery)).map { it.subject })
        compose.onNodeWithText("Aralık 2025 dönemi faturanız", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Kasım 2026 faturanız hazır", substring = true).assertDoesNotExist()
    }

    @Test
    fun whenNothingIsFoundTheScreenSaysWhereItLooked() {
        launch()
        type("zzzyok")
        compose.onNodeWithText("Bulunamadı").assertIsDisplayed()
        compose.onNodeWithText("bu cihazdaki iletilerde", substring = true).assertExists()

        // cleared: back to what to type
        type("")
        compose.onNodeWithText("Ne arıyorsun?").assertIsDisplayed()
        assertTrue(ui.searchQuery.isEmpty())
    }
}
