package com.deskmail.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TEST_CLOCK
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderRole
import com.deskmail.demo.DemoMail
import com.deskmail.mail.DraftState
import com.deskmail.mail.MailView
import com.deskmail.testComposer
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.PlainMessageBody
import desktopkit.components.CommandPaletteState
import desktopkit.shell.ShellTransitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the commands behind the keys do to the screen in front: J, K, S, U, E, Delete and Esc on a
 * list and its open message; R, A and F from the open message; Ctrl+N, Ctrl+Enter and Esc on a
 * message that is being written.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class CommandsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val composer = testComposer(mail)
    private val ui = MailUiState()
    private val commands = CommandHost()

    private fun launch(startRoute: String? = null) {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent {
            DeskMailApp(
                mail = mail,
                settings = settings,
                log = AppLog(),
                composer = composer,
                ui = ui,
                startRoute = startRoute,
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
    fun archiveAndDeleteActOnTheOpenMessageAndUndoBringsItBack() {
        launch()
        // nothing is open, and nothing was done yet
        assertFalse(perform(Commands.ARCHIVE))
        assertFalse(perform(Commands.DELETE))
        assertFalse(perform(Commands.MOVE))
        assertFalse(perform(Commands.UNDO))

        perform(Commands.NEXT)
        val id = ui.openedId(Routes.INBOX)!!
        val inbox = mail.message(id)!!.folderId
        assertTrue(perform(Commands.ARCHIVE))
        // in the archive, and no reader shows it any more
        assertTrue(mail.message(id)!!.folderId != inbox)
        assertNull(ui.openedId(Routes.INBOX))
        compose.onNodeWithText("Arşivlendi").assertExists()

        // Ctrl+Z, while the notice is up: the same as its "Undo"
        assertTrue(perform(Commands.UNDO))
        assertEquals(inbox, mail.message(id)!!.folderId)
        assertTrue(mail.committed.isEmpty())
        // once is enough
        assertFalse(perform(Commands.UNDO))
    }

    @Test
    fun undoStillWorksWhenTheNoticeIsGone() {
        launch()
        perform(Commands.NEXT)
        val id = ui.openedId(Routes.INBOX)!!
        val inbox = mail.message(id)!!.folderId
        assertTrue(perform(Commands.DELETE))
        compose.mainClock.advanceTimeBy(30_000)
        compose.waitForIdle()
        // the notice is gone and what was done stands
        assertEquals(1, mail.committed.size)
        assertTrue(mail.message(id)!!.folderId != inbox)

        assertTrue(perform(Commands.UNDO))
        // this made-up mail lets nothing be brought back that stands: the screen says so
        compose.onNodeWithText("Geri alınamadı", substring = true).assertExists()
    }

    @Test
    fun replyReplyAllAndForwardBeginAMessageFromTheOpenOne() {
        launch()
        // nothing is open: nothing to answer
        assertFalse(perform(Commands.REPLY))
        assertFalse(perform(Commands.REPLY_ALL))
        assertFalse(perform(Commands.FORWARD))
        assertNull(ui.writing)

        perform(Commands.NEXT)
        val opened = mail.message(ui.openedId(Routes.INBOX)!!)!!
        assertTrue(perform(Commands.REPLY))
        assertEquals("Re: " + opened.subject, composer.outbox.single().subject)
        assertEquals(Routes.INBOX, ui.writing!!.route)

        // Esc puts it away; begun and left as it was, it is not kept
        assertTrue(perform(Commands.CLOSE))
        assertNull(ui.writing)
        assertTrue(composer.outbox.isEmpty())
        // the message behind it is still open, and Esc now closes that
        assertEquals(opened.id, ui.openedId(Routes.INBOX))

        assertTrue(perform(Commands.FORWARD))
        assertEquals("Fwd: " + opened.subject, composer.outbox.single().subject)
    }

    @Test
    fun whileAMessageIsBeingWrittenTheKeysOfTheListDoNothing() {
        launch()
        perform(Commands.NEXT)
        val opened = ui.openedId(Routes.INBOX)!!
        val starred = mail.message(opened)!!.starred

        assertTrue(perform(Commands.NEW_MESSAGE))
        assertNotNull(ui.writing)

        // the list and the open message are behind the screen: out of reach
        assertFalse(perform(Commands.NEXT))
        assertFalse(perform(Commands.STAR))
        assertFalse(perform(Commands.DELETE))
        assertFalse(perform(Commands.ARCHIVE))
        assertFalse(perform(Commands.REPLY))
        assertEquals(opened, ui.openedId(Routes.INBOX))
        assertEquals(starred, mail.message(opened)!!.starred)
        assertNotNull(mail.message(opened))
    }

    @Test
    fun ctrlEnterSendsWhatIsBeingWrittenAndCtrlZTakesItBack() {
        launch()
        // nothing is being written
        assertFalse(perform(Commands.SEND))

        assertTrue(perform(Commands.NEW_MESSAGE))
        compose.runOnUiThread {
            ui.writing!!.fields.to = "ayse.yilmaz@posta.example"
            ui.writing!!.fields.subject = "Klavyeden"
        }
        compose.waitForIdle()
        assertTrue(perform(Commands.SEND))
        assertNull(ui.writing)
        assertEquals(DraftState.Waiting, composer.outbox.single().state)
        assertEquals("Klavyeden", composer.outbox.single().subject)

        // while its notice is up: the same as the notice's own "Undo"
        assertTrue(perform(Commands.UNDO))
        assertEquals(DraftState.Writing, composer.outbox.single().state)
        assertEquals("Klavyeden", ui.writing!!.fields.subject)
        assertTrue(composer.sent.isEmpty())
    }

    @Test
    fun aNewMessageBegunInAFolderIsFromThatFoldersAccount() {
        // a folder of the second account in front
        val folder = mail.folders.first { it.accountId == DemoMail.GMAIL && it.role == FolderRole.Inbox }
        launch(startRoute = Routes.folder(folder.id))

        assertTrue(perform(Commands.NEW_MESSAGE))

        assertEquals(DemoMail.GMAIL, composer.outbox.single().accountId)
        assertEquals(Routes.folder(folder.id), ui.writing!!.route)
    }

    @Test
    fun moveAsksForAFolder() {
        launch()
        perform(Commands.NEXT)
        val id = ui.openedId(Routes.INBOX)!!
        assertTrue(perform(Commands.MOVE))
        assertEquals(id, ui.moving?.id)
        compose.onNodeWithText("Klasöre taşı").assertExists()
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
