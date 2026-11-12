package com.deskmail

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.demo.DemoMail
import com.deskmail.mail.MailView
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The real activity, as the launcher starts it on a phone, with its keys routed through the
 * shortcut table. (What the commands do to the mail is tested on the app without the activity,
 * where the reader needs no WebView: CommandsTest.)
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

    @Test
    fun startsAtTheUnifiedInbox() {
        compose.onNode(hasText("Gelen") and isSelectable()).assertIsSelected()
    }

    /** A chord reaches its command whatever has the focus: Ctrl+, opens Settings. */
    @Test
    fun ctrlCommaOpensSettings() {
        press(KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON)
        // the phone's top bar names the screen
        compose.onNodeWithText("Ayarlar").assertExists()
    }

    /** Ctrl+K opens the command palette, and while it is open the single keys are its own. */
    @Test
    fun ctrlKOpensThePaletteWhichKeepsTheKeys() {
        val newest = DemoMail.shared.messages(MailView.Inbox).first()
        assertTrue(newest.unread)
        press(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
        compose.onNode(hasSetTextAction()).assertExists()
        // J would open the newest message, and an opened message is read: it was not opened
        press(KeyEvent.KEYCODE_J)
        assertTrue(DemoMail.shared.message(newest.id)!!.unread)
    }
}
