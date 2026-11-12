package com.deskmail.ui.mail

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.R
import com.deskmail.TestApp
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.Routes
import desktopkit.theme.DensityPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The list and the reader in the three layouts: list → message, two panes, and the workstation of a large window. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MailScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val subject = "Cumartesi kahvaltı?"

    private fun breakfast() = mail.messages.first { it.subject == subject }

    /** A phone: the message slides over the list, is read from then on, and Back returns to the list. */
    @Test
    @Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
    fun aPhoneOpensAMessageOverTheList() {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(mail, prefs, settings) }
        assertTrue(breakfast().unread)
        // the row: the subject, then the first words
        compose.onNodeWithText(subject, substring = true).performClick()
        compose.waitForIdle()
        // the reader's heading is the subject alone
        compose.onNodeWithText(subject).assertIsDisplayed()
        assertFalse(breakfast().unread)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText(subject).assertDoesNotExist()
        compose.onNodeWithText(subject, substring = true).assertIsDisplayed()
    }

    /**
     * On a phone the list and an open message lie under the bars: each fills the window, and its own
     * padding keeps the chips and the subject clear of the top bar, by the bar's height and no more.
     */
    @Test
    @Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
    fun aPhonesListAndReaderLieUnderTheBars() {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(mail, prefs, settings) }
        val window = compose.onRoot().getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag(MailTags.LIST).getUnclippedBoundsInRoot()
        assertEquals(0f, list.top.value, 0.5f)
        assertEquals(window.bottom.value, list.bottom.value, 0.5f)
        val chip = compose.onNodeWithText("Tümü").getUnclippedBoundsInRoot()
        assertTrue("the chips start at ${chip.top}", chip.top.value in 48f..100f)

        compose.onNodeWithText(subject, substring = true).performClick()
        compose.waitForIdle()
        val reader = compose.onNodeWithTag(MailTags.READER).getUnclippedBoundsInRoot()
        assertEquals(0f, reader.top.value, 0.5f)
        assertEquals(window.bottom.value, reader.bottom.value, 0.5f)
        val heading = compose.onNodeWithText(subject).getUnclippedBoundsInRoot()
        assertTrue("the subject starts at ${heading.top}", heading.top.value in 48f..100f)
    }

    /** The chips narrow the list: "Unread" leaves the read messages out. */
    @Test
    @Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
    fun theUnreadChipLeavesReadMessagesOut() {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(mail, prefs, settings) }
        compose.onNodeWithText("Re: Proje planı (v3)", substring = true).assertExists()
        compose.onNodeWithText("Okunmamış").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Re: Proje planı (v3)", substring = true).assertDoesNotExist()
        compose.onNodeWithText(subject, substring = true).assertExists()
    }

    /** Beside a rail with room for both, the list stays where it is and the message opens next to it. */
    @Test
    @Config(qualifiers = "tr-w1000dp-h800dp-mdpi")
    fun aWideWindowBesideARailShowsListAndReaderSideBySide() {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(mail, prefs, settings) }
        compose.onNodeWithText("Açık ileti yok").assertIsDisplayed()
        compose.onNodeWithText(subject, substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(subject).assertIsDisplayed()
        // the list is still there
        compose.onNodeWithText("İade tarihi yaklaşıyor", substring = true).assertIsDisplayed()
    }

    /** A large window with a mouse: the table, the reader, and the inspector once it is opened. */
    @Test
    @Config(qualifiers = "tr-w1440dp-h900dp-mdpi")
    fun aLargeWindowShowsTheTableTheReaderAndTheInspector() {
        val prefs = testPrefs(density = DensityPreference.Pointer)
        val settings = testSettings()
        compose.setContent { TestApp(mail, prefs, settings) }
        compose.onNodeWithText("Kimden").assertIsDisplayed()
        compose.onNodeWithText(subject, substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(subject).assertIsDisplayed()
        assertFalse(breakfast().unread)

        // closed to begin with
        compose.onNodeWithText("Hesap").assertDoesNotExist()
        val show = compose.activity.getString(desktopkit.R.string.dk_inspector_show)
        compose.onNodeWithContentDescription(show).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Hesap").assertIsDisplayed()
    }

    /** The table is for a mouse: a finger gets the rows in a large window too, beside the reader. */
    @Test
    @Config(qualifiers = "tr-w1480dp-h924dp-mdpi")
    fun aLargeWindowListsRowsForAFinger() {
        val prefs = testPrefs(density = DensityPreference.Touch)
        val settings = testSettings()
        compose.setContent { TestApp(mail, prefs, settings) }
        compose.onNodeWithText("14 ileti").assertIsDisplayed()
        compose.onNodeWithText("Kimden").assertDoesNotExist()
        compose.onNodeWithText("Açık ileti yok").assertIsDisplayed()
        compose.onNodeWithText(subject, substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(subject).assertIsDisplayed()
        // the list is still there
        compose.onNodeWithText("İade tarihi yaklaşıyor", substring = true).assertIsDisplayed()
    }

    /**
     * The list has its share of the room beside the sidebar, and the divider changes the share: a
     * list too narrow for columns shows the rows, and what was dragged is there for the next window.
     */
    @Test
    @Config(qualifiers = "tr-w1440dp-h900dp-mdpi")
    fun theDividerSetsTheListsShareAndANarrowListShowsRows() {
        val prefs = testPrefs(density = DensityPreference.Pointer)
        val settings = testSettings()
        val ui = MailUiState(settings)
        compose.setContent { TestApp(mail, prefs, settings, ui) }
        // 1440 dp less the sidebar's 240: 45 % of 1200
        assertEquals(540f, ui.listSplit.startWidthDp, 0.5f)
        compose.onNodeWithText("Kimden").assertIsDisplayed()

        val divider = compose.activity.getString(R.string.list_resize)
        compose.onNodeWithContentDescription(divider).performSemanticsAction(SemanticsActions.SetProgress) { it(420f) }
        compose.waitForIdle()
        compose.onNodeWithText("Kimden").assertDoesNotExist()
        compose.onNodeWithText(subject, substring = true).assertIsDisplayed()

        // stored once the drag has rested
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertEquals(0.35f, settings.listShare, 0.001f)
    }

    /** However wide the reader, a plain-text message keeps lines of a length that reads well. */
    @Test
    @Config(qualifiers = "tr-w1920dp-h1080dp-mdpi")
    fun aWideReaderKeepsPlainTextToAReadableLine() {
        val prefs = testPrefs(density = DensityPreference.Pointer)
        val settings = testSettings()
        val ui = MailUiState(settings)
        val newsletter = mail.messages.first { it.subject.startsWith("Kasım bülteni") }
        openMessage(mail, ui, Routes.INBOX, newsletter)
        compose.setContent { TestApp(mail, prefs, settings, ui) }
        val reader = compose.onNodeWithTag(MailTags.READER).getUnclippedBoundsInRoot()
        val readerWidth = (reader.right - reader.left).value
        assertTrue("the reader is $readerWidth dp wide", readerWidth > 800f)
        val text = compose.onNode(hasText(newsletter.text) and hasAnyAncestor(hasTestTag(MailTags.READER))).getUnclippedBoundsInRoot()
        // 42 em of the body's size (14 sp here: 588 dp) and the page's padding; far from the reader's width
        val textWidth = (text.right - text.left).value
        assertTrue("the text is $textWidth dp wide", textWidth in 480f..700f)
    }

    /** A window with a sidebar but little room: the rows, even with a mouse. */
    @Test
    @Config(qualifiers = "tr-w1240dp-h800dp-mdpi")
    fun aListTooNarrowForATableShowsRows() {
        val prefs = testPrefs(density = DensityPreference.Pointer)
        val settings = testSettings()
        compose.setContent { TestApp(mail, prefs, settings) }
        // 45 % of 1000 dp
        compose.onNodeWithText("14 ileti").assertIsDisplayed()
        compose.onNodeWithText("Kimden").assertDoesNotExist()
        compose.onNodeWithText(subject, substring = true).assertIsDisplayed()
    }
}
