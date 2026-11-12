package com.deskmail.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import desktopkit.components.DeskTextField
import desktopkit.input.CommonShortcuts
import desktopkit.input.KeyChord
import desktopkit.theme.DesktopKitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** The shortcut table, and which keys reach it when. */
@RunWith(AndroidJUnit4::class)
class ShortcutsTest {

    // Robolectric's window starts in keyboard mode, which hands the focus to the first focusable (the
    // text field) by itself; a device's window that a finger has touched does not
    @get:Rule(order = 0)
    val touchMode = object : ExternalResource() {
        override fun before() = InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var paletteOpen = false
    private var typing = false
    private var nothingToDo = false
    private val performed = ArrayList<String>()
    private val router = KeyRouter(shortcuts, { paletteOpen }, { typing }) { id ->
        performed += id
        !nothingToDo
    }

    private fun down(keyCode: Int, meta: Int = 0, repeat: Int = 0) = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, repeat, meta)

    @Test
    fun noTwoShortcutsShareAKey() {
        assertEquals(emptyList<Any>(), shortcuts.conflicts())
    }

    @Test
    fun ctrlNWritesAMessageAndCtrlShiftNOpensAWindow() {
        assertEquals(KeyChord(Key.N, ctrl = true), shortcuts[Commands.NEW_MESSAGE].chord)
        assertEquals(KeyChord(Key.N, ctrl = true, shift = true), shortcuts[CommonShortcuts.NEW_WINDOW].chord)
        assertTrue(router.route(down(KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON)))
        assertTrue(router.route(down(KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)))
        assertEquals(listOf(Commands.NEW_MESSAGE, CommonShortcuts.NEW_WINDOW), performed)
    }

    @Test
    fun singleKeysWorkOnlyWhileNothingIsBeingTyped() {
        assertTrue(router.route(down(KeyEvent.KEYCODE_J)))
        assertTrue(router.route(down(KeyEvent.KEYCODE_S)))
        assertEquals(listOf(Commands.NEXT, Commands.STAR), performed)

        typing = true
        performed.clear()
        assertFalse(router.route(down(KeyEvent.KEYCODE_J)))
        assertFalse(router.route(down(KeyEvent.KEYCODE_SLASH)))
        assertFalse(router.route(down(KeyEvent.KEYCODE_ESCAPE)))
        assertEquals(emptyList<String>(), performed)
        // a chord still works in a text field
        assertTrue(router.route(down(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)))
        assertEquals(listOf(CommonShortcuts.SEARCH), performed)
    }

    @Test
    fun theOpenPaletteKeepsEveryKey() {
        paletteOpen = true
        assertFalse(router.route(down(KeyEvent.KEYCODE_J)))
        assertFalse(router.route(down(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)))
        assertFalse(router.route(down(KeyEvent.KEYCODE_ESCAPE)))
        assertEquals(emptyList<String>(), performed)
    }

    @Test
    fun aHeldKeyWalksTheListButFlipsNothing() {
        assertTrue(router.route(down(KeyEvent.KEYCODE_J, repeat = 1)))
        assertTrue(router.route(down(KeyEvent.KEYCODE_K, repeat = 3)))
        // taken, so that it does not reach the focused view either, but not performed
        assertTrue(router.route(down(KeyEvent.KEYCODE_S, repeat = 1)))
        assertTrue(router.route(down(KeyEvent.KEYCODE_U, repeat = 2)))
        assertEquals(listOf(Commands.NEXT, Commands.PREVIOUS), performed)
    }

    @Test
    fun modifiersMakeAnotherKey() {
        assertFalse(router.route(down(KeyEvent.KEYCODE_J, KeyEvent.META_SHIFT_ON)))
        assertFalse(router.route(down(KeyEvent.KEYCODE_S, KeyEvent.META_ALT_ON)))
        assertFalse(router.route(down(KeyEvent.KEYCODE_Q)))
        assertFalse(router.route(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_J)))
        assertEquals(emptyList<String>(), performed)
    }

    @Test
    fun aCommandWithNothingToDoLeavesTheKeyToTheFocusedView() {
        nothingToDo = true
        // Esc with no message open goes on to the system, which reads it as Back
        assertFalse(router.route(down(KeyEvent.KEYCODE_ESCAPE)))
        assertEquals(listOf(Commands.CLOSE), performed)
    }

    /**
     * What the activity asks to know whether text is being typed: the focused view, which for
     * Compose is the one view of the window, says so while one of its text fields has the focus.
     */
    @Test
    fun aFocusedTextFieldIsKnownToTheActivity() {
        var text by mutableStateOf("")
        compose.setContent {
            DesktopKitTheme {
                Column {
                    Text("Liste", Modifier.testTag("other"))
                    DeskTextField(text, { text = it }, Modifier.testTag("field"))
                }
            }
        }
        compose.waitForIdle()
        assertFalse(compose.activity.currentFocus?.onCheckIsTextEditor() == true)
        compose.onNodeWithTag("field").performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.currentFocus?.onCheckIsTextEditor() == true)
    }
}
