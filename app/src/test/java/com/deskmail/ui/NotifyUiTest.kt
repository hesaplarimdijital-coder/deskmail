package com.deskmail.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TestApp
import com.deskmail.demo.DemoMail
import com.deskmail.mail.MailView
import com.deskmail.noMail
import com.deskmail.notify.NotifyGate
import com.deskmail.notify.OpenRequest
import com.deskmail.settings.AppSettings
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.mail.MailTags
import com.deskmail.ui.mail.MailUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a window has to do with the notifications of new mail: it opens the message of one that
 * was tapped, says which list it shows (mail that arrives there needs no telling), and asks once
 * for the leave to notify.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class NotifyUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ui = MailUiState()

    /** How often the window asked for the leave to notify. */
    private var asks = 0

    /** How often it led to the system's battery question, and how often that was put off. */
    private var batteryAsks = 0
    private var batterySkips = 0

    // led to the battery question before, unless a test says otherwise
    private val gate: NotifyGate = NotifyGate(
        ask = { asked() },
        openSystemSettings = {},
        askBattery = {
            batteryAsks++
            led()
        },
        skipBattery = {
            batterySkips++
            led()
        },
    ).apply { batteryAsked = true }

    private fun asked() {
        asks++
        gate.asked = true
    }

    private fun led() {
        gate.batteryAsked = true
    }

    private fun launch(
        mail: DemoMail = testMail(),
        startRoute: String? = null,
        settings: AppSettings = testSettings(),
        onFrontList: ((MailView?) -> Unit)? = null,
    ) {
        val prefs = testPrefs()
        compose.setContent { TestApp(mail, prefs, settings, ui, startRoute = startRoute, notifications = gate, onFrontList = onFrontList) }
        compose.waitForIdle()
    }

    @Test
    fun aTappedNotificationOpensItsMessageInTheInbox() {
        val mail = testMail()
        val message = mail.messages(MailView.Inbox).first { it.unread }
        gate.asked = true
        launch(mail, startRoute = Routes.SETTINGS)

        compose.runOnUiThread { ui.openRequest = OpenRequest(message.id) }
        compose.waitForIdle()

        // taken up once
        assertNull(ui.openRequest)
        compose.onNode(hasText("Gelen") and isSelectable()).assertIsSelected()
        assertEquals(message.id, ui.openedId(Routes.INBOX))
        assertFalse(mail.message(message.id)!!.unread)
        // the message itself, over the list
        compose.onNodeWithTag(MailTags.READER).assertExists()
    }

    /** The account's line of a group of notifications asks for no message: the inbox is where its mail is. */
    @Test
    fun anAccountsLineOpensTheInbox() {
        gate.asked = true
        launch(startRoute = Routes.SETTINGS)

        compose.runOnUiThread { ui.openRequest = OpenRequest(0) }
        compose.waitForIdle()

        assertNull(ui.openRequest)
        compose.onNode(hasText("Gelen") and isSelectable()).assertIsSelected()
        assertNull(ui.openedId(Routes.INBOX))
    }

    @Test
    fun theWindowSaysWhichListOfMailItShows() {
        val shown = ArrayList<MailView?>()
        gate.asked = true
        launch(onFrontList = { shown += it })
        assertEquals(MailView.Inbox, shown.last())

        // the list of folders is no list of mail
        compose.onNode(hasText("Klasörler") and isSelectable()).performClick()
        compose.waitForIdle()
        assertNull(shown.last())
    }

    @Test
    fun theLeaveToNotifyIsAskedForOnceWhenThereIsAnAccount() {
        launch()
        assertEquals(1, asks)

        // the window is drawn anew (another route): nobody is asked twice
        compose.onNode(hasText("Klasörler") and isSelectable()).performClick()
        compose.waitForIdle()
        assertEquals(1, asks)
    }

    @Test
    fun withoutAnAccountNobodyIsAsked() {
        gate.batteryAsked = false
        launch(noMail())
        assertEquals(0, asks)
        compose.onNodeWithTag(BATTERY_QUESTION_TAG).assertDoesNotExist()
    }

    /**
     * Mail is told of as it comes only where the system leaves the app alone in the background:
     * the user is led to that setting once, with the question about notifications behind them.
     */
    @Test
    fun theUserIsLedToTheBatterySettingOnce() {
        gate.batteryAsked = false
        launch()
        assertEquals(1, asks)
        compose.onNodeWithText("Anlık bildirim için pil ayarı").assertExists()
        // what Samsung wants besides is said too
        compose.onNodeWithText("Hiç uyumayan uygulamalar", substring = true).assertExists()

        compose.onNodeWithText("Kısıtlamayı kaldır").performClick()
        compose.waitForIdle()

        // the question itself is the system's; this one is not brought up again
        assertEquals(1, batteryAsks)
        assertEquals(0, batterySkips)
        compose.onNodeWithText("Anlık bildirim için pil ayarı").assertDoesNotExist()
    }

    @Test
    fun theBatterySettingCanBePutOff() {
        gate.batteryAsked = false
        gate.asked = true
        launch()

        compose.onNodeWithText("Şimdi değil").performClick()
        compose.waitForIdle()

        assertEquals(1, batterySkips)
        assertEquals(0, batteryAsks)
        compose.onNodeWithText("Anlık bildirim için pil ayarı").assertDoesNotExist()
    }

    /** Nobody is led there who has no use for it: the restriction is lifted already, or mail is only looked for every so often. */
    @Test
    fun nobodyIsLedToTheBatterySettingWithoutAReason() {
        gate.batteryAsked = false
        gate.asked = true
        gate.unrestricted = true
        launch()
        compose.onNodeWithTag(BATTERY_QUESTION_TAG).assertDoesNotExist()
    }

    @Test
    fun withAnIntervalChosenNobodyIsLedToTheBatterySetting() {
        gate.batteryAsked = false
        gate.asked = true
        launch(settings = testSettings().also { it.updateCheckMinutes(30) })
        compose.onNodeWithTag(BATTERY_QUESTION_TAG).assertDoesNotExist()
    }

    @Test
    fun whoAllowedItAlreadyIsNotAsked() {
        gate.allowed = true
        launch()
        assertEquals(0, asks)
    }
}
