package com.deskmail.ui.mail

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TestApp
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A conversation on a phone: its messages above the open one, each to be opened from there. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class ConversationUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val ui = MailUiState()

    @Test
    fun theConversationStandsAboveTheOpenMessage() {
        val plan = mail.messages.first { it.subject == "Re: Proje planı (v3)" }
        openMessage(mail, ui, Routes.INBOX, plan)
        compose.setContent { TestApp(mail, testPrefs(), testSettings(), ui) }
        compose.waitForIdle()

        compose.onNodeWithTag(MailTags.CONVERSATION).assertIsDisplayed()
        compose.onNodeWithText("Bu konuşmada 3 ileti").assertIsDisplayed()

        // the first of them, from the archive
        compose.onNodeWithText("İlk taslağı gönderiyorum", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(mail.messages.first { it.subject == "Proje planı (v1)" }.id, ui.openedId(Routes.INBOX))
    }

    @Test
    fun aMessageOfItsOwnHasNoConversationAboveIt() {
        val bill = mail.messages.first { it.subject.startsWith("Kasım 2026 faturanız hazır") }
        openMessage(mail, ui, Routes.INBOX, bill)
        compose.setContent { TestApp(mail, testPrefs(), testSettings(), ui) }
        compose.waitForIdle()

        compose.onNodeWithTag(MailTags.CONVERSATION).assertDoesNotExist()
    }
}
