package com.deskmail.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TEST_NOW
import com.deskmail.TestApp
import com.deskmail.notify.OpenRequest
import com.deskmail.pressNewMessage
import com.deskmail.testComposer
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.compose.ComposeTags
import com.deskmail.ui.mail.MailTags
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.openMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * "Await reply", on a phone: chosen while writing and on a message under Sent, the strip that
 * says until when, "Stop waiting", and the list of what is waited for.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class AwaitingUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val composer = testComposer(mail)
    private val ui = MailUiState()

    private fun launch(startRoute: String? = null) {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(mail, prefs, settings, ui, startRoute = startRoute, composer = composer) }
        compose.waitForIdle()
    }

    private fun press(label: String) {
        compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun shows(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** The book that was asked to be put aside, under Outlook's Sent: the demo's mail waits for its answer. */
    private val asked get() = mail.messages.first { it.subject == "Kitap ayırtma" }

    @Test
    fun howLongAnAnswerIsWaitedForIsChosenWhileWriting() {
        launch()
        compose.pressNewMessage()

        click("Yanıt bekle")
        click("3 gün")

        val draft = ui.writing!!.draftId
        compose.waitUntil(5_000) { composer.draft(draft)?.awaitDays == 3 }
        // the button says what was chosen; "None" takes it back
        compose.onNodeWithTag(ComposeTags.AWAIT).assertExists()
        click("Yanıt bekle: 3 gün")
        click("Yok")
        compose.waitUntil(5_000) { composer.draft(draft)?.awaitDays == 0 }
    }

    @Test
    fun anAnswerToASentMessageIsWaitedForUntilTheUserStops() {
        val plan = mail.messages.first { it.subject == "Re: Proje planı (v2)" }
        val sent = Routes.folder(plan.folderId)
        openMessage(mail, ui, sent, plan)
        launch(startRoute = sent)

        click("Yanıt bekle")
        click("2 gün")

        compose.waitUntil(5_000) { mail.awaitedOf(plan) != null }
        assertEquals(TEST_NOW_PLUS_TWO_DAYS, mail.awaitedOf(plan)!!.dueAt)
        shows("Yanıt bekleniyor; gelmezse hatırlatma:")

        click("Beklemeyi bırak")
        compose.waitUntil(5_000) { mail.awaitedOf(plan) == null }
        compose.onNodeWithTag(MailTags.AWAITING).assertDoesNotExist()
        compose.onNodeWithText("Yanıt bekle").assertExists()
    }

    /** Only the user's own messages under Sent can wait for an answer. */
    @Test
    fun aReceivedMessageOffersNoWait() {
        val breakfast = mail.messages.first { it.subject == "Cumartesi kahvaltı?" }
        openMessage(mail, ui, Routes.INBOX, breakfast)
        launch()

        compose.onNodeWithTag(MailTags.READER).assertExists()
        compose.onNodeWithTag(MailTags.AWAIT).assertDoesNotExist()
    }

    /** The list of what is waited for: the demo's book, whose time passed without an answer. */
    @Test
    fun theAwaitedAreListedAndTheOverdueSaySo() {
        launch(startRoute = Routes.AWAITING)
        shows("Kitap ayırtma")

        openMessage(mail, ui, Routes.AWAITING, asked)
        compose.waitForIdle()

        shows("Yanıt gelmedi; bekleme süresi doldu:")
    }

    @Test
    fun withNothingAwaitedTheListSaysHowSomethingComesToBe() {
        runBlocking { mail.stopAwaiting(mail.awaited.single().id) }
        launch(startRoute = Routes.AWAITING)

        shows("Yanıt beklenen ileti yok")
    }

    /** A reminder that was tapped opens its message among those waited for. */
    @Test
    fun aTappedReminderOpensTheAwaitingList() {
        launch()
        ui.openRequest = OpenRequest(asked.id, OpenRequest.Place.Awaiting)
        compose.waitForIdle()

        shows("Yanıt gelmedi; bekleme süresi doldu:")
        assertEquals(asked.id, ui.openedId(Routes.AWAITING))
    }

    private companion object {
        val TEST_NOW_PLUS_TWO_DAYS: java.time.Instant = TEST_NOW.plus(Duration.ofDays(2))
    }
}
