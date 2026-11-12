package com.deskmail.ui.mail

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TestApp
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
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

    /** A large window: the table, the reader, and the inspector once it is opened. */
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
}
