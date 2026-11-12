package com.deskmail.ui.mail

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TestApp
import com.deskmail.core.mail.FolderRole
import com.deskmail.mail.FetchedFile
import com.deskmail.mail.MailMessage
import com.deskmail.mail.MailUndo
import com.deskmail.mail.MailView
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * What can be done to a message from the screens of a phone: the reader's buttons, a row's menu,
 * the notice with its way back, and the files a message carries.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class MailActionsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val ui = MailUiState()

    private fun launch() {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(mail, prefs, settings, ui) }
        compose.waitForIdle()
    }

    private fun message(subject: String) = mail.messages.first { it.subject == subject }

    private fun role(message: MailMessage?): FolderRole? = message?.let { mail.folder(it.folderId)?.role }

    private fun open(message: MailMessage) {
        compose.runOnUiThread { openMessage(mail, ui, Routes.INBOX, message) }
        compose.waitForIdle()
    }

    @Test
    fun archivingClosesTheReaderAndOffersTheWayBack() {
        val breakfast = message("Cumartesi kahvaltı?")
        launch()
        open(breakfast)

        compose.onNodeWithContentDescription("Arşivle").performClick()
        compose.waitForIdle()

        // off the inbox, the reader closed, and a word on what happened
        assertTrue(role(mail.message(breakfast.id)) != FolderRole.Inbox)
        assertNull(ui.openedId(Routes.INBOX))
        compose.onNodeWithText("Arşivlendi").assertIsDisplayed()
        assertTrue(mail.committed.isEmpty())

        compose.onNodeWithText("Geri al").performClick()
        compose.waitForIdle()
        assertEquals(FolderRole.Inbox, role(mail.message(breakfast.id)))
        // taken back: there was nothing to let stand
        assertTrue(mail.committed.isEmpty())
        compose.onNodeWithText("Arşivlendi").assertDoesNotExist()
    }

    @Test
    fun whatIsNotTakenBackStandsOnceItsNoticeIsGone() {
        val breakfast = message("Cumartesi kahvaltı?")
        launch()
        open(breakfast)

        compose.onNodeWithContentDescription("Sil").performClick()
        compose.waitForIdle()
        assertEquals(FolderRole.Trash, role(mail.message(breakfast.id)))
        compose.onNodeWithText("Çöpe taşındı").assertIsDisplayed()
        assertTrue(mail.committed.isEmpty())

        // nobody takes it back
        compose.mainClock.advanceTimeBy(30_000)
        compose.waitForIdle()
        compose.onNodeWithText("Çöpe taşındı").assertDoesNotExist()
        assertEquals(listOf(MailUndo.Action.Deleted), mail.committed.map { it.action })
        assertEquals(FolderRole.Trash, role(mail.message(breakfast.id)))
    }

    /** The next thing done takes the notice's place: what the first notice was about then stands. */
    @Test
    fun aNewerNoticeLetsTheOlderOneStand() {
        val inbox = mail.messages(MailView.Inbox)
        launch()

        open(inbox[0])
        compose.onNodeWithContentDescription("Sil").performClick()
        compose.waitForIdle()
        open(inbox[1])
        compose.onNodeWithContentDescription("Sil").performClick()
        compose.waitForIdle()

        assertEquals(listOf(listOf(inbox[0].id)), mail.committed.map { it.messages })
        // the way back is the newer one's
        compose.onNodeWithText("Geri al").performClick()
        compose.waitForIdle()
        assertEquals(FolderRole.Inbox, role(mail.message(inbox[1].id)))
        assertEquals(FolderRole.Trash, role(mail.message(inbox[0].id)))
    }

    @Test
    fun theStarIsBesideTheSender() {
        val breakfast = message("Cumartesi kahvaltı?")
        assertFalse(breakfast.starred)
        launch()
        open(breakfast)

        compose.onNodeWithContentDescription("Yıldızla").performClick()
        compose.waitForIdle()
        assertTrue(mail.message(breakfast.id)!!.starred)

        compose.onNodeWithContentDescription("Yıldızı kaldır").performClick()
        compose.waitForIdle()
        assertFalse(mail.message(breakfast.id)!!.starred)
    }

    @Test
    fun aRowsMenuActsOnItsMessageWithoutOpeningIt() {
        val breakfast = message("Cumartesi kahvaltı?")
        assertTrue(breakfast.unread)
        launch()

        compose.onNodeWithText("Cumartesi kahvaltı?", substring = true).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Okundu say").performClick()
        compose.waitForIdle()
        assertFalse(mail.message(breakfast.id)!!.unread)
        assertNull(ui.openedId(Routes.INBOX))

        compose.onNodeWithText("Cumartesi kahvaltı?", substring = true).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Sil").performClick()
        compose.waitForIdle()
        assertEquals(FolderRole.Trash, role(mail.message(breakfast.id)))
        compose.onNodeWithText("Çöpe taşındı").assertIsDisplayed()
    }

    @Test
    fun aFolderIsChosenForAMessageThatIsMoved() {
        val breakfast = message("Cumartesi kahvaltı?")
        val bills = mail.folders.first { it.accountId == breakfast.accountId && it.role == FolderRole.Other }
        launch()
        open(breakfast)

        compose.runOnUiThread { ui.moving = breakfast }
        compose.waitForIdle()
        compose.onNodeWithText("Klasöre taşı").assertIsDisplayed()
        compose.onNodeWithText(bills.name).performClick()
        compose.waitForIdle()

        assertEquals(bills.id, mail.message(breakfast.id)!!.folderId)
        assertNull(ui.moving)
        assertNull(ui.openedId(Routes.INBOX))
        compose.onNodeWithText("Taşındı: " + bills.name).assertIsDisplayed()
    }

    @Test
    fun aFileIsFetchedAndHandedToTheAppThatOpensIt() {
        val plan = message("Re: Proje planı (v3)")
        // where the app keeps the files it fetched, and may hand them on from
        val file = File(compose.activity.cacheDir, "files/7/Proje planı v3.pdf").apply {
            parentFile!!.mkdirs()
            writeText("%PDF-1.4")
        }
        mail.fetched = { FetchedFile.Ready(file) }
        launch()
        open(plan)

        compose.onNodeWithText("Proje planı v3.pdf").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Aç").performClick()
        compose.waitForIdle()

        assertEquals(listOf("Proje planı v3.pdf"), mail.fetches.map { it.name })
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, started.action)
        assertEquals("application/pdf", started.type)
        assertTrue(started.data.toString().startsWith("content://" + compose.activity.packageName + ".files/"))
        assertTrue(started.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun aFileThatCannotBeFetchedSaysSo() {
        val plan = message("Re: Proje planı (v3)")
        launch()
        open(plan)

        compose.onNodeWithText("Proje planı v3.pdf").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Aç").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Bu dosya artık sunucuda yok.").assertIsDisplayed()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
    }
}
