package com.deskmail.ui.mail

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TestApp
import com.deskmail.core.mail.FolderRole
import com.deskmail.mail.MailMessage
import com.deskmail.mail.MailView
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.Routes
import desktopkit.theme.DensityPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Several messages at once, on a phone: rows are picked from a row's menu ("Select") and by
 * tapping, a bar says how many and acts on them all, and Back ends it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class SelectionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val ui = MailUiState()

    private fun launch() {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(mail, prefs, settings, ui) }
        compose.waitForIdle()
    }

    /** Two messages of one account's inbox, as the list shows them. */
    private fun two(): Pair<MailMessage, MailMessage> {
        val inbox = mail.messages(MailView.Inbox)
        val first = inbox.first()
        return first to inbox.first { it.id != first.id && it.accountId == first.accountId }
    }

    private fun role(id: Long): FolderRole? = mail.message(id)?.let { mail.folder(it.folderId)?.role }

    /** Begins to pick rows with this message's row: a long press, then "Select". */
    private fun pick(message: MailMessage) {
        compose.onNodeWithText(message.subject, substring = true).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Seç").performClick()
        compose.waitForIdle()
    }

    private fun tap(message: MailMessage) {
        compose.onNodeWithText(message.subject, substring = true).performClick()
        compose.waitForIdle()
    }

    @Test
    fun rowsArePickedAndWhatIsDoneIsDoneToAllOfThem() {
        val (first, second) = two()
        launch()

        pick(first)
        assertTrue(ui.picking(Routes.INBOX))
        assertEquals(setOf(first.id), ui.selection(Routes.INBOX))
        compose.onNodeWithText("1 ileti seçili").assertIsDisplayed()

        // a tap now adds a row instead of opening its message
        tap(second)
        assertEquals(setOf(first.id, second.id), ui.selection(Routes.INBOX))
        assertNull(ui.openedId(Routes.INBOX))
        compose.onNodeWithText("2 ileti seçili").assertIsDisplayed()

        // read, both of them, without having been opened
        compose.runOnUiThread {
            mail.setUnread(first.id, true)
            mail.setUnread(second.id, false)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Okundu say").performClick()
        compose.waitForIdle()
        assertFalse(mail.message(first.id)!!.unread)
        assertFalse(mail.message(second.id)!!.unread)
        // all read: the same button now marks them unread
        compose.onNodeWithContentDescription("Okunmadı say").performClick()
        compose.waitForIdle()
        assertTrue(mail.message(first.id)!!.unread && mail.message(second.id)!!.unread)

        compose.onNodeWithTag(MailTags.SELECTION).assertExists()
        compose.onNodeWithContentDescription("Sil").performClick()
        compose.waitForIdle()

        assertEquals(FolderRole.Trash, role(first.id))
        assertEquals(FolderRole.Trash, role(second.id))
        compose.onNodeWithText("2 ileti çöpe taşındı").assertIsDisplayed()
        // they left the list: nothing is selected, and the picking is over
        assertTrue(ui.selection(Routes.INBOX).isEmpty())
        assertFalse(ui.picking(Routes.INBOX))
        compose.onNodeWithTag(MailTags.SELECTION).assertDoesNotExist()
    }

    @Test
    fun aTapTakesAPickedRowOutAndTheLastOneEndsThePicking() {
        val (first, second) = two()
        launch()
        pick(first)
        tap(second)

        tap(first)
        assertEquals(setOf(second.id), ui.selection(Routes.INBOX))
        assertTrue(ui.picking(Routes.INBOX))

        tap(second)
        assertTrue(ui.selection(Routes.INBOX).isEmpty())
        assertFalse(ui.picking(Routes.INBOX))
        compose.onNodeWithTag(MailTags.SELECTION).assertDoesNotExist()

        // and a tap opens a message again
        tap(first)
        assertEquals(first.id, ui.openedId(Routes.INBOX))
    }

    @Test
    fun backAndTheBarsCrossEndThePicking() {
        val (first, second) = two()
        launch()
        pick(first)
        tap(second)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(ui.selection(Routes.INBOX).isEmpty())
        assertFalse(ui.picking(Routes.INBOX))

        pick(first)
        compose.onNodeWithContentDescription("Seçimi kaldır").performClick()
        compose.waitForIdle()
        assertTrue(ui.selection(Routes.INBOX).isEmpty())
        // nothing was done to what was picked
        assertEquals(FolderRole.Inbox, role(first.id))
    }

    @Test
    fun starringAndArchivingActOnTheWholeSelection() {
        val (first, second) = two()
        launch()
        pick(first)
        tap(second)

        compose.onNodeWithContentDescription("Yıldızla").performClick()
        compose.waitForIdle()
        assertTrue(mail.message(first.id)!!.starred && mail.message(second.id)!!.starred)

        compose.onNodeWithContentDescription("Arşivle").performClick()
        compose.waitForIdle()
        assertTrue(role(first.id) != FolderRole.Inbox && role(second.id) != FolderRole.Inbox)
        compose.onNodeWithText("2 ileti arşivlendi").assertIsDisplayed()

        // one way back for both
        compose.onNodeWithText("Geri al").performClick()
        compose.waitForIdle()
        assertEquals(FolderRole.Inbox, role(first.id))
        assertEquals(FolderRole.Inbox, role(second.id))
    }

    @Test
    fun aFolderIsChosenForTheWholeSelection() {
        val (first, second) = two()
        val target = mail.folders.first { it.accountId == first.accountId && it.role == FolderRole.Other }
        launch()
        pick(first)
        tap(second)

        // on a phone the bar has moving under "More options", to leave room for the count
        compose.onNode(hasContentDescription("Diğer seçenekler") and hasAnyAncestor(hasTestTag(MailTags.SELECTION))).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Taşı…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Klasöre taşı").assertIsDisplayed()
        compose.onNodeWithText(target.name).performClick()
        compose.waitForIdle()

        assertEquals(target.id, mail.message(first.id)!!.folderId)
        assertEquals(target.id, mail.message(second.id)!!.folderId)
        assertNull(ui.moving)
        assertTrue(ui.selection(Routes.INBOX).isEmpty())
    }

    /**
     * A large window with a mouse: a click selects its row and opens the message; several selected
     * rows get the bar under the table, and what is done there is done to all of them.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    @Config(qualifiers = "tr-w1440dp-h900dp-mdpi")
    fun inALargeWindowTheTablesSelectedRowsAreActedOnTogether() {
        val (first, second) = two()
        val prefs = testPrefs(density = DensityPreference.Pointer)
        val settings = testSettings()
        compose.setContent { TestApp(mail, prefs, settings, ui) }
        compose.waitForIdle()
        compose.onNodeWithText("Kimden").assertIsDisplayed()

        compose.onNodeWithText(first.subject, substring = true).performMouseInput { click() }
        compose.waitForIdle()
        assertEquals(first.id, ui.openedId(Routes.INBOX))
        assertEquals(setOf(first.id), ui.selection(Routes.INBOX))
        // one selected row is no selection to act on
        compose.onNodeWithTag(MailTags.SELECTION).assertDoesNotExist()

        // a second row is added (Ctrl+click, Shift+click or Ctrl+A in the table)
        compose.runOnUiThread { ui.select(Routes.INBOX, setOf(first.id, second.id)) }
        compose.waitForIdle()
        compose.onNodeWithText("2 ileti seçili").assertIsDisplayed()

        // the bar's own "Delete": the reader beside the list has one for the open message
        compose.onNode(hasContentDescription("Sil") and hasAnyAncestor(hasTestTag(MailTags.SELECTION))).performClick()
        compose.waitForIdle()

        assertEquals(FolderRole.Trash, role(first.id))
        assertEquals(FolderRole.Trash, role(second.id))
        assertTrue(ui.selection(Routes.INBOX).isEmpty())
        assertNull(ui.openedId(Routes.INBOX))
        compose.onNodeWithTag(MailTags.SELECTION).assertDoesNotExist()
    }

    /** What the window keeps of a selection, apart from any screen. */
    @Test
    fun theWindowKnowsWhatAnActionIsDoneTo() {
        val state = MailUiState()
        assertTrue(state.targets(Routes.INBOX).isEmpty())

        // one clicked row is the open message's row: the open message is what is acted on
        state.open(Routes.INBOX, 7)
        state.select(Routes.INBOX, setOf(7))
        assertEquals(listOf(7L), state.targets(Routes.INBOX))

        // several rows: all of them, whatever is open
        state.select(Routes.INBOX, setOf(3, 5))
        assertEquals(setOf(3L, 5L), state.targets(Routes.INBOX).toSet())

        // a message that left its list is selected no longer
        state.closeMessages(listOf(5L))
        assertEquals(setOf(3L), state.selection(Routes.INBOX))

        // opened by other means than a click, the open message's row takes the place of the one selected row
        state.open(Routes.INBOX, 9)
        assertEquals(setOf(9L), state.selection(Routes.INBOX))
        assertEquals(listOf(9L), state.targets(Routes.INBOX))

        // picked with a finger, one row is a selection already; the open message is not picked along
        state.pick(Routes.INBOX, 4)
        assertEquals(listOf(4L), state.targets(Routes.INBOX))
        state.togglePicked(Routes.INBOX, 4)
        assertFalse(state.picking(Routes.INBOX))
        assertTrue(state.selection(Routes.INBOX).isEmpty())

        // closed, the message leaves no row selected behind
        state.open(Routes.INBOX, 9)
        state.close(Routes.INBOX)
        assertTrue(state.selection(Routes.INBOX).isEmpty())
        assertTrue(state.targets(Routes.INBOX).isEmpty())
    }
}
