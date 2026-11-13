package com.deskmail.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TEST_CLOCK
import com.deskmail.core.log.AppLog
import com.deskmail.testComposer
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.mail.MailTags
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.PlainMessageBody
import desktopkit.components.CommandPaletteState
import desktopkit.shell.ShellTransitions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An experiment, not a test to keep: by which ways into the search screen its field ends up with
 * the focus under Robolectric. Each way is a test; which of them fail is the answer.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class SearchFocusProbeTest {

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

    private fun perform(id: String) {
        compose.runOnUiThread { commands.perform(id) }
        compose.waitForIdle()
    }

    private fun tab() {
        compose.onNode(hasText("Ara") and isSelectable()).performClick()
        compose.waitForIdle()
    }

    private fun raise() {
        compose.runOnUiThread { ui.searchFocusAsked = true }
        compose.waitForIdle()
    }

    private fun assertFocused() = compose.onNodeWithTag(MailTags.SEARCH).assertIsFocused()

    @Test
    fun a_slashWithNothingBefore() {
        launch()
        perform(Commands.FIND)
        assertFocused()
    }

    @Test
    fun b_slashAfterAMessageWasOpened() {
        launch()
        perform(Commands.NEXT)
        perform(Commands.FIND)
        assertFocused()
    }

    @Test
    fun c_theTabAlone() {
        launch()
        tab()
        assertFocused()
    }

    @Test
    fun d_theTabWithTheFlagRaisedFirst() {
        launch()
        raise()
        tab()
        assertFocused()
    }

    @Test
    fun e_theTabThenAskedAgain() {
        launch()
        tab()
        raise()
        assertFocused()
    }

    @Test
    fun f_startedAtTheSearchWithTheFlag() {
        ui.searchFocusAsked = true
        launch(Routes.SEARCH)
        assertFocused()
    }

    @Test
    fun g_startedAtTheSearchThenAsked() {
        launch(Routes.SEARCH)
        raise()
        assertFocused()
    }

    @Test
    fun h_theTabAfterAMessageWasOpened() {
        launch()
        perform(Commands.NEXT)
        perform(Commands.CLOSE)
        tab()
        assertFocused()
    }

    @Test
    fun i_slashThenAwayAndBackByTheTab() {
        launch()
        perform(Commands.FIND)
        compose.onNode(hasText("Gelen") and isSelectable()).performClick()
        compose.waitForIdle()
        tab()
        assertFocused()
    }
}
