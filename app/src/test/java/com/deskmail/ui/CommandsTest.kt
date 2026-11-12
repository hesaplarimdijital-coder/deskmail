package com.deskmail.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TEST_CLOCK
import com.deskmail.core.log.AppLog
import com.deskmail.mail.MailView
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.PlainMessageBody
import desktopkit.components.CommandPaletteState
import desktopkit.shell.ShellTransitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the commands behind the single keys do to the list in front: J, K, S, U, Esc. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class CommandsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val ui = MailUiState()
    private val commands = CommandHost()

    private fun launch() {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent {
            DeskMailApp(
                mail = mail,
                settings = settings,
                log = AppLog(),
                ui = ui,
                prefs = prefs,
                clock = TEST_CLOCK,
                transitions = ShellTransitions.None,
                palette = CommandPaletteState(),
                commands = commands,
                messageBody = { content, _, modifier -> PlainMessageBody(content, modifier) },
            )
        }
        compose.waitForIdle()
    }

    private fun perform(id: String): Boolean {
        var done = false
        compose.runOnUiThread { done = commands.perform(id) }
        compose.waitForIdle()
        return done
    }

    @Test
    fun nextAndPreviousWalkTheListAndReadWhatTheyOpen() {
        launch()
        val inbox = mail.messages(MailView.Inbox)
        assertTrue(inbox[0].unread)

        assertTrue(perform(Commands.NEXT))
        assertEquals(inbox[0].id, ui.openedId(Routes.INBOX))
        assertFalse(mail.message(inbox[0].id)!!.unread)
        compose.onNodeWithText(inbox[0].subject).assertExists()

        assertTrue(perform(Commands.NEXT))
        assertEquals(inbox[1].id, ui.openedId(Routes.INBOX))
        compose.onNodeWithText(inbox[1].subject).assertExists()

        assertTrue(perform(Commands.PREVIOUS))
        assertEquals(inbox[0].id, ui.openedId(Routes.INBOX))
        // above the first message there is nothing: the key is not taken
        assertFalse(perform(Commands.PREVIOUS))
        assertEquals(inbox[0].id, ui.openedId(Routes.INBOX))
    }

    @Test
    fun starAndReadActOnTheOpenMessageOnly() {
        launch()
        // nothing is open yet
        assertFalse(perform(Commands.STAR))
        assertFalse(perform(Commands.TOGGLE_READ))
        assertFalse(perform(Commands.CLOSE))

        perform(Commands.NEXT)
        val id = ui.openedId(Routes.INBOX)!!
        val starred = mail.message(id)!!.starred
        assertTrue(perform(Commands.STAR))
        assertEquals(!starred, mail.message(id)!!.starred)

        assertFalse(mail.message(id)!!.unread)
        assertTrue(perform(Commands.TOGGLE_READ))
        assertTrue(mail.message(id)!!.unread)

        assertTrue(perform(Commands.CLOSE))
        assertNull(ui.openedId(Routes.INBOX))
    }

    @Test
    fun syncAsksTheServersWithTheScreenInFront() {
        launch()
        assertTrue(perform(Commands.REFRESH))
        assertEquals(listOf<MailView?>(MailView.Inbox), mail.refreshed)
    }

    @Test
    fun theKeysWalkTheListAsItIsFiltered() {
        launch()
        // only what is unread: the chip
        compose.onNodeWithText("Okunmamış").assertExists()
        compose.runOnUiThread { ui.toggleFilter(Routes.INBOX, com.deskmail.ui.mail.MailFilter.Unread) }
        compose.waitForIdle()
        val unread = mail.messages(MailView.Inbox).filter { it.unread }
        perform(Commands.NEXT)
        assertEquals(unread[0].id, ui.openedId(Routes.INBOX))
        // the opened message is read now and leaves the filtered list; the next key starts at its top again
        perform(Commands.NEXT)
        assertEquals(unread[1].id, ui.openedId(Routes.INBOX))
    }
}
