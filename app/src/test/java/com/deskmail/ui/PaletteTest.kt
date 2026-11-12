package com.deskmail.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TEST_CLOCK
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.PlainMessageBody
import desktopkit.components.CommandPaletteState
import desktopkit.shell.ShellTransitions
import desktopkit.theme.DensityPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The command palette over the app: folders, messages and the commands of the shortcut table. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "tr-w1440dp-h900dp-mdpi")
class PaletteTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val ui = MailUiState()
    private val palette = CommandPaletteState()
    private val commands = CommandHost()

    private fun launch() {
        val prefs = testPrefs(density = DensityPreference.Pointer)
        val settings = testSettings()
        compose.setContent {
            DeskMailApp(
                mail = mail,
                settings = settings,
                ui = ui,
                prefs = prefs,
                clock = TEST_CLOCK,
                transitions = ShellTransitions.None,
                palette = palette,
                commands = commands,
                messageBody = { message, modifier -> PlainMessageBody(message, modifier) },
            )
        }
        compose.waitForIdle()
    }

    private fun search(query: String) {
        compose.runOnUiThread { commands.perform(desktopkit.input.CommonShortcuts.SEARCH) }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput(query)
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
    }

    @Test
    fun aFolderIsFoundByItsNameAndOpened() {
        launch()
        search("seyahat")
        assertFalse(palette.isOpen)
        compose.onNode(hasText("Seyahat") and isSelectable()).assertIsSelected()
    }

    @Test
    fun aMessageIsFoundByItsSubjectAndOpenedInItsFolder() {
        launch()
        val message = mail.messages.first { it.subject == "İade tarihi yaklaşıyor" }
        search("iade tarihi")
        assertFalse(palette.isOpen)
        assertEquals(message.id, ui.openedId(Routes.folder(message.folderId)))
        assertFalse(mail.message(message.id)!!.unread)
        // the reader's heading
        compose.onNodeWithText("İade tarihi yaklaşıyor").assertExists()
    }

    @Test
    fun aCommandOfTheTableRunsFromThePalette() {
        launch()
        search("kısayol")
        // "Klavye kısayolları": the list is a section of Settings
        compose.onNode(hasText("Ayarlar") and isSelectable()).assertIsSelected()
    }
}
