package com.deskmail

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.ui.settings.AccountTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The real activity, as the launcher starts it on a phone: on the app's own database, which has
 * no account yet, and with its keys routed through the shortcut table. (What the commands do to
 * the mail is tested on the app without the activity, with made-up mail: CommandsTest.)
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class MainActivityTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun press(keyCode: Int, meta: Int = 0) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            compose.activity.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0, meta))
        }
        compose.waitForIdle()
    }

    /** A first start: the unified inbox, which has nothing to show and says how to change that. */
    @Test
    fun startsAtTheUnifiedInboxAndOffersToAddAnAccount() {
        compose.onNode(hasText("Gelen") and isSelectable()).assertIsSelected()
        // the database is read on a thread of its own; then it is known that there is no account
        compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodesWithText("Henüz hesap yok").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Hesap ekle").performClick()
        compose.waitForIdle()
        // Settings, with "Add an account" open at the address
        compose.onNodeWithTag(AccountTags.ADDRESS).assertExists()
    }

    /** A chord reaches its command whatever has the focus: Ctrl+, opens Settings. */
    @Test
    fun ctrlCommaOpensSettings() {
        press(KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON)
        // the phone's top bar names the screen
        compose.onNodeWithText("Ayarlar").assertExists()
    }

    /** Ctrl+K opens the command palette (while it is open the keys are its own: ShortcutsTest). */
    @Test
    fun ctrlKOpensThePalette() {
        press(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
        compose.onNode(hasSetTextAction()).assertExists()
    }
}
