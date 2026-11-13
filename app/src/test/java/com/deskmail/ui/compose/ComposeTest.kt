package com.deskmail.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TestApp
import com.deskmail.demo.DemoMail
import com.deskmail.mail.Draft
import com.deskmail.mail.DraftSeed
import com.deskmail.mail.DraftState
import com.deskmail.noMail
import com.deskmail.testComposer
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.Routes
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.openMessage
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
 * Writing on a phone: a new message, a reply, "Send" with its way back, what closing the screen
 * keeps, and the outbox. Messages are written and "sent" in memory.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class ComposeTest {

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

    /** Presses the icon named [label]. By its semantics action: a page may lie under the phone's bars. */
    private fun press(label: String) {
        compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).performTextInput(text)
        compose.waitForIdle()
    }

    /** Begins a message from the list and writes it. */
    private fun write(to: String = "ayse.yilmaz@posta.example", subject: String = "Cumartesi", body: String = "Olur, geliyorum.") {
        press("Yeni ileti")
        type(ComposeTags.TO, to)
        type(ComposeTags.SUBJECT, subject)
        type(ComposeTags.BODY, body)
    }

    /** The system's Back. */
    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun aNewMessageIsWrittenHandedOverAndSentWhenItsTimeIsUp() {
        launch()
        write()
        // from the first account, as it is begun from the inboxes of all of them
        assertEquals(DemoMail.OUTLOOK, ui.writing!!.fields.accountId)

        press("Gönder")

        // the screen is gone, the message waits as it was written, and there is a way back
        assertNull(ui.writing)
        val waiting = composer.outbox.single()
        assertEquals(DraftState.Waiting, waiting.state)
        assertEquals("ayse.yilmaz@posta.example", waiting.to)
        assertEquals("Cumartesi", waiting.subject)
        assertEquals("Olur, geliyorum.", waiting.body)
        compose.onNodeWithText("Gönderiliyor…").assertIsDisplayed()
        compose.onNodeWithText("Geri al").assertIsDisplayed()
        assertTrue(composer.sent.isEmpty())

        compose.mainClock.advanceTimeBy(composer.undoMs + 1_000)
        compose.waitForIdle()

        assertEquals(listOf("Cumartesi"), composer.sent.map { it.subject })
        assertTrue(composer.outbox.isEmpty())
        compose.onNodeWithText("Gönderiliyor…").assertDoesNotExist()
    }

    @Test
    fun undoBringsTheMessageBackToBeWrittenOn() {
        launch()
        write()
        press("Gönder")

        compose.onNodeWithText("Geri al").performClick()
        compose.waitForIdle()

        // on the screen again, as it was written
        val writing = ui.writing!!
        assertEquals(Routes.INBOX, writing.route)
        assertEquals("Cumartesi", writing.fields.subject)
        assertEquals("Olur, geliyorum.", writing.fields.body)
        assertEquals(DraftState.Writing, composer.outbox.single().state)
        // and nothing leaves, however long it is left alone
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitForIdle()
        assertTrue(composer.sent.isEmpty())
    }

    @Test
    fun aMessageThatHasLeftCannotBeTakenBack() {
        launch()
        write()
        press("Gönder")
        composer.tooLate = true

        compose.onNodeWithText("Geri al").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Geri alınamadı: ileti çoktan gitti.").assertIsDisplayed()
        assertNull(ui.writing)
    }

    @Test
    fun sendSaysWhatIsWrongWithWhoItGoesTo() {
        launch()
        press("Yeni ileti")
        type(ComposeTags.SUBJECT, "Alıcısız")

        press("Gönder")
        compose.onNodeWithText("Kime gideceğini yaz.").assertExists()
        assertNotNull(ui.writing)

        // typing takes the complaint away; what is typed is looked at again
        type(ComposeTags.TO, "ayse@")
        compose.onNodeWithText("Kime gideceğini yaz.").assertDoesNotExist()
        press("Gönder")
        compose.onNodeWithText("Adres değil: ayse@").assertExists()
        assertEquals(DraftState.Writing, composer.outbox.single().state)
        assertTrue(composer.sent.isEmpty())
    }

    @Test
    fun closingKeepsWhatWasWrittenAndSaysWhereItIs() {
        launch()
        write(subject = "Yarım kalan")

        back()

        assertNull(ui.writing)
        val kept = composer.outbox.single()
        assertEquals(DraftState.Writing, kept.state)
        assertEquals("Yarım kalan", kept.subject)
        assertEquals("Olur, geliyorum.", kept.body)
        // kept by the screen as it went, not only by the pause in the typing
        assertTrue(kept.id in composer.keptOnTheWayOut)
        compose.onNodeWithText("Taslak Giden kutusu'nda duruyor.").assertIsDisplayed()
        // the list is back
        compose.onNodeWithContentDescription("Yeni ileti").assertExists()
    }

    @Test
    fun aMessageThatWasBegunAndLeftEmptyIsNotKept() {
        launch()
        press("Yeni ileti")
        assertNotNull(ui.writing)
        assertEquals(1, composer.outbox.size)

        back()

        assertNull(ui.writing)
        assertTrue(composer.outbox.isEmpty())
        compose.onNodeWithText("Taslak Giden kutusu'nda duruyor.").assertDoesNotExist()
    }

    @Test
    fun theDraftIsThrownAwayFromTheScreenItIsWrittenOn() {
        launch()
        write(subject = "Vazgeçtim")

        press("Taslağı sil")

        assertNull(ui.writing)
        assertTrue(composer.outbox.isEmpty())
        // nothing of it was kept on the way out
        assertTrue(composer.keptOnTheWayOut.isEmpty())
        compose.onNodeWithText("Taslak silindi.").assertIsDisplayed()
    }

    @Test
    fun aReplyBeginsWithTheSenderTheSubjectAndTheQuotedMessage() {
        val breakfast = mail.messages.first { it.subject == "Cumartesi kahvaltı?" }
        launch()
        compose.runOnUiThread { openMessage(mail, ui, Routes.INBOX, breakfast) }
        compose.waitForIdle()

        // beside the sender
        press("Yanıtla")

        val writing = ui.writing!!
        assertEquals(Routes.INBOX, writing.route)
        assertEquals("Ayşe Yılmaz <ayse.yilmaz@posta.example>", writing.fields.to)
        assertEquals("Re: Cumartesi kahvaltı?", writing.fields.subject)
        assertTrue(writing.fields.body, writing.fields.body.startsWith("\n\nAyşe Yılmaz yazdı:\n> Selam Deniz,\n>\n> Cumartesi sabahı"))
        // from the account the message came to
        assertEquals(breakfast.accountId, writing.fields.accountId)

        // closed with nothing written, it leaves nothing; the message it answers is still open behind it
        back()
        assertTrue(composer.outbox.isEmpty())
        assertEquals(breakfast.id, ui.openedId(Routes.INBOX))
        compose.onNodeWithText("Cumartesi kahvaltı?").assertExists()
    }

    @Test
    fun theButtonsUnderAMessageAnswerEverybodyOrSendItOn() {
        val plan = mail.messages.first { it.subject == "Re: Proje planı (v3)" }
        launch()
        compose.runOnUiThread { openMessage(mail, ui, Routes.INBOX, plan) }
        compose.waitForIdle()

        compose.onNodeWithText("Tümünü yanıtla").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        val all = ui.writing!!
        assertEquals("Mehmet Demir <mehmet.demir@isyeri.example>", all.fields.to)
        // whoever else it went to gets a copy, and the field for them is shown
        assertEquals("Elif Şahin <elif.sahin@isyeri.example>", all.fields.cc)
        assertTrue(all.fields.moreRecipients)
        assertEquals("Re: Proje planı (v3)", all.fields.subject)
        back()

        compose.onNodeWithText("İlet").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        val onward = ui.writing!!
        assertEquals("", onward.fields.to)
        assertEquals("Fwd: Re: Proje planı (v3)", onward.fields.subject)
        assertTrue(onward.fields.body, onward.fields.body.contains("---------- İletilen ileti ----------\nKimden: Mehmet Demir <mehmet.demir@isyeri.example>"))
        // its file goes along
        compose.onNodeWithText("Proje planı v3.pdf").assertExists()
    }

    @Test
    fun theOutboxListsWhatHasNotLeftAndWhereItStands() {
        composer.put(Draft(0, DemoMail.OUTLOOK, to = "ayse.yilmaz@posta.example", subject = "Yarım kalan", body = "Devamı gelecek."))
        composer.put(Draft(0, DemoMail.OUTLOOK, to = "Elif Şahin <elif.sahin@isyeri.example>", subject = "Gidecek", body = "Yolda.", state = DraftState.Waiting))
        composer.put(Draft(0, DemoMail.GMAIL, to = "yok@posta.example", subject = "Dönen", body = "", state = DraftState.Failed, error = "550 5.1.1 No such user"))
        launch(startRoute = Routes.OUTBOX)

        compose.onNodeWithText("Taslak").assertIsDisplayed()
        compose.onNodeWithText("Gönderilecek").assertIsDisplayed()
        compose.onNodeWithText("Gönderilemedi").assertIsDisplayed()
        // why it did not go, in the server's words
        compose.onNodeWithText("550 5.1.1 No such user").assertIsDisplayed()

        // a draft opens to be written on, over the outbox
        compose.onNodeWithText("Taslak").performClick()
        compose.waitForIdle()
        val writing = ui.writing!!
        assertEquals(Routes.OUTBOX, writing.route)
        assertEquals("Yarım kalan", writing.fields.subject)
        // closed as it was found, it stays what it was: a draft somebody wrote
        back()
        assertEquals(3, composer.outbox.size)
    }

    @Test
    fun aWaitingMessageIsSentNowOrTakenBackFromTheOutbox() {
        val first = composer.put(Draft(0, DemoMail.OUTLOOK, to = "elif.sahin@isyeri.example", subject = "Gidecek", body = "Yolda.", state = DraftState.Waiting))
        launch(startRoute = Routes.OUTBOX)

        // a tap asks what to do with it
        compose.onNodeWithText("Gönderilecek").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Şimdi gönder").performClick()
        compose.waitForIdle()
        assertEquals(listOf(first), composer.sent.map { it.id })
        compose.onNodeWithText("Giden kutusu boş").assertIsDisplayed()

        val second = composer.put(Draft(0, DemoMail.OUTLOOK, to = "elif.sahin@isyeri.example", subject = "Bekleyen", body = "Dur.", state = DraftState.Waiting))
        compose.waitForIdle()
        compose.onNodeWithText("Gönderilecek").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Geri al ve düzenle").performClick()
        compose.waitForIdle()
        assertEquals(second, ui.writing!!.draftId)
        assertEquals(DraftState.Writing, composer.draft(second)!!.state)
    }

    @Test
    fun aFailedMessageIsTriedAgainOrThrownAway() {
        val failed = composer.put(Draft(0, DemoMail.GMAIL, to = "ayse.yilmaz@posta.example", subject = "Dönen", body = "", state = DraftState.Failed, error = "451 Try again later"))
        launch(startRoute = Routes.OUTBOX)

        // still refused: it stays, with what the server says now
        composer.refusal = "452 Mailbox full"
        compose.onNodeWithText("Yeniden dene").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("452 Mailbox full").assertIsDisplayed()

        composer.refusal = null
        compose.onNodeWithText("Yeniden dene").performClick()
        compose.waitForIdle()
        assertEquals(listOf(failed), composer.sent.map { it.id })

        val other = composer.put(Draft(0, DemoMail.GMAIL, to = "ayse.yilmaz@posta.example", subject = "Gereksiz", body = ""))
        compose.waitForIdle()
        compose.onNodeWithText("Taslak").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Sil").performClick()
        compose.waitForIdle()
        assertNull(composer.draft(other))
        compose.onNodeWithText("Taslak silindi.").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ asked for by another app

    /** A mailto link, or something shared with the app: a new message begins with it over the screen in front. */
    @Test
    fun whatAnotherAppAsksForBeginsAMessageWithIt() {
        launch()
        val seed = DraftSeed(to = "ayse.yilmaz@posta.example", cc = "elif@posta.example", subject = "Rapor", body = "Ekte.")
        val file = SharedFile("rapor.pdf", "application/pdf") { ByteArray(12).inputStream() }

        compose.runOnUiThread { ui.composeRequest = ComposeRequest(seed, listOf(file)) }
        compose.waitForIdle()

        // taken up once, over the inbox
        assertNull(ui.composeRequest)
        val writing = ui.writingOver(Routes.INBOX)!!
        val draft = composer.draft(writing.draftId)!!
        assertEquals("ayse.yilmaz@posta.example", draft.to)
        assertEquals("elif@posta.example", draft.cc)
        assertEquals("Rapor", draft.subject)
        assertTrue(draft.body.startsWith("Ekte."))
        assertEquals(listOf("rapor.pdf"), draft.files.map { it.name })
        // it came with something to say: closed untouched, it is kept
        assertFalse(writing.fresh)
        compose.onNodeWithTag(ComposeTags.SUBJECT).assertExists()
        compose.onNodeWithTag(ComposeTags.CC).assertExists()
    }

    /** A link that only names who to write to begins a message that is not worth keeping empty. */
    @Test
    fun aMessageThatOnlyHasItsRecipientIsNotKeptWhenItIsLeftUntouched() {
        launch()

        compose.runOnUiThread { ui.composeRequest = ComposeRequest(DraftSeed(to = "ayse.yilmaz@posta.example")) }
        compose.waitForIdle()

        val writing = ui.writingOver(Routes.INBOX)!!
        assertTrue(writing.fresh)
        assertEquals("ayse.yilmaz@posta.example", composer.draft(writing.draftId)!!.to)
    }

    @Test
    fun withoutAnAccountWhatAnotherAppAsksForIsAnsweredWithANotice() {
        val nobody = noMail()
        val nobodysComposer = testComposer(nobody)
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(nobody, prefs, settings, ui, composer = nobodysComposer) }
        compose.waitForIdle()

        compose.runOnUiThread { ui.composeRequest = ComposeRequest(DraftSeed(to = "ayse.yilmaz@posta.example")) }
        compose.waitForIdle()

        assertNull(ui.composeRequest)
        assertNull(ui.writing)
        compose.onNodeWithText("Önce bir hesap ekle.").assertExists()
    }
}
