package com.deskmail.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
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
import com.deskmail.core.mail.FolderRole
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
import kotlinx.coroutines.CompletableDeferred
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

    /**
     * A mailto link, or something shared with the app: a new message begins with it over the
     * screen in front. At once: a file that came along is copied into the message behind the
     * screen, which says so and does not send before the file is in.
     */
    @Test
    fun whatAnotherAppAsksForBeginsAMessageWithItAtOnce() {
        launch()
        val seed = DraftSeed(to = "ayse.yilmaz@posta.example", cc = "elif@posta.example", subject = "Rapor", body = "Ekte.")
        val file = SharedFile("tatil.mp4", "video/mp4") { ByteArray(12).inputStream() }
        // a video takes its time on its way into the message
        val held = CompletableDeferred<Unit>()
        composer.filesHeld = held

        compose.runOnUiThread { ui.composeRequest = ComposeRequest(seed, listOf(file)) }
        compose.waitForIdle()

        // taken up once, over the inbox: the screen is there before the file is
        assertNull(ui.composeRequest)
        val writing = ui.writingOver(Routes.INBOX)!!
        compose.onNodeWithTag(ComposeTags.SUBJECT).assertExists()
        compose.onNodeWithTag(ComposeTags.CC).assertExists()
        val draft = composer.draft(writing.draftId)!!
        assertEquals("ayse.yilmaz@posta.example", draft.to)
        assertEquals("elif@posta.example", draft.cc)
        assertEquals("Rapor", draft.subject)
        assertTrue(draft.body.startsWith("Ekte."))
        assertTrue(draft.files.isEmpty())
        compose.onNodeWithText("Dosya ekleniyor…").assertExists()
        compose.onNodeWithContentDescription("Gönder").assertIsNotEnabled()
        // it came with something to say: closed untouched, it is kept
        assertFalse(writing.fresh)

        held.complete(Unit)
        compose.waitForIdle()

        assertEquals(listOf("tatil.mp4"), composer.draft(writing.draftId)!!.files.map { it.name })
        compose.onNodeWithTag(ComposeTags.ATTACHING).assertDoesNotExist()
        compose.onNodeWithContentDescription("Gönder").assertIsEnabled()
        compose.onNodeWithText("tatil.mp4").assertExists()
    }

    /** Thrown away while its file was still on its way: nothing is said about the file. */
    @Test
    fun aMessageThatIsThrownAwayWhileItsFileIsOnItsWaySaysNothingOfTheFile() {
        launch()
        val held = CompletableDeferred<Unit>()
        composer.filesHeld = held
        compose.runOnUiThread { ui.composeRequest = ComposeRequest(DraftSeed(subject = "Video"), listOf(SharedFile("tatil.mp4", "video/mp4") { ByteArray(12).inputStream() })) }
        compose.waitForIdle()
        val writing = ui.writing!!

        press("Taslağı sil")
        held.complete(Unit)
        compose.waitForIdle()

        assertNull(ui.writing)
        assertNull(composer.draft(writing.draftId))
        compose.onNodeWithText("Dosya eklenemedi.").assertDoesNotExist()
    }

    /**
     * Put away while its file was still on its way, and taken up again from the outbox: the screen
     * it is written on now knows of the file too, and does not send the message without it.
     */
    @Test
    fun aMessageThatIsTakenUpAgainWhileItsFileIsOnItsWayStillWaitsForIt() {
        launch(startRoute = Routes.OUTBOX)
        val held = CompletableDeferred<Unit>()
        composer.filesHeld = held
        val seed = DraftSeed(to = "ayse.yilmaz@posta.example", subject = "Video")
        compose.runOnUiThread { ui.composeRequest = ComposeRequest(seed, listOf(SharedFile("tatil.mp4", "video/mp4") { ByteArray(12).inputStream() })) }
        compose.waitForIdle()
        val first = ui.writing!!

        // put away: it came with something, so it is kept, and its file goes on being copied
        back()
        assertNull(ui.writing)
        compose.onNodeWithText("Taslak").performClick()
        compose.waitForIdle()

        val again = ui.writing!!
        assertEquals(first.draftId, again.draftId)
        assertTrue(first !== again)
        compose.onNodeWithTag(ComposeTags.ATTACHING).assertExists()
        compose.onNodeWithContentDescription("Gönder").assertIsNotEnabled()

        held.complete(Unit)
        compose.waitForIdle()

        compose.onNodeWithTag(ComposeTags.ATTACHING).assertDoesNotExist()
        compose.onNodeWithContentDescription("Gönder").assertIsEnabled()
        compose.onNodeWithText("tatil.mp4").assertExists()
    }

    /**
     * Another screen is looked at while a message is being written: back on its own screen, the
     * message is still being written, with what was typed. (The screen is drawn anew then, and
     * has not heard of its message yet: that is not the message being gone.)
     */
    @Test
    fun aMessageBeingWrittenIsStillThereAfterAnotherScreenWasLookedAt() {
        launch()
        write(subject = "Yarım kalan")

        compose.onNode(hasText("Klasörler") and isSelectable()).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ComposeTags.SUBJECT).assertDoesNotExist()
        compose.onNode(hasText("Gelen") and isSelectable()).performClick()
        compose.waitForIdle()

        val writing = ui.writingOver(Routes.INBOX)
        assertNotNull(writing)
        assertEquals("Yarım kalan", writing!!.fields.subject)
        compose.onNodeWithTag(ComposeTags.SUBJECT).assertExists()
        compose.onNodeWithText("Taslak Giden kutusu'nda duruyor.").assertDoesNotExist()
        // and it is sent from there as if nothing had happened
        press("Gönder")
        assertEquals(DraftState.Waiting, composer.outbox.single().state)
    }

    /** The contacts are offered beside "To": the system's picker, without leave to read them. */
    @Test
    fun recipientsCanBeTakenFromTheContacts() {
        launch()
        press("Yeni ileti")
        compose.onNodeWithContentDescription("Rehberden ekle").assertExists()
    }

    /** A draft that lies in its account's Drafts folder is gone on with, not answered. */
    @Test
    fun aDraftInItsFolderIsTakenUpToBeWrittenOn() {
        val draft = mail.messages.first { it.subject == "Re: Cumartesi kahvaltı?" && mail.folder(it.folderId)?.role == FolderRole.Drafts }
        val route = Routes.folder(draft.folderId)
        launch(startRoute = route)
        compose.runOnUiThread { openMessage(mail, ui, route, draft) }
        compose.waitForIdle()
        // there is nothing to answer here
        compose.onNodeWithContentDescription("Yanıtla").assertDoesNotExist()
        compose.onNodeWithText("Yazmaya devam et").assertExists()

        press("Düzenle")

        val writing = ui.writingOver(route)!!
        assertEquals("Re: Cumartesi kahvaltı?", writing.fields.subject)
        compose.onNodeWithTag(ComposeTags.SUBJECT).assertExists()
        // it was worth keeping before it was taken up: closed untouched, it stays
        assertFalse(writing.fresh)
        back()
        assertEquals(1, composer.outbox.size)
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
