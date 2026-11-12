package com.deskmail.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TestApp
import com.deskmail.core.log.AppLog
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The log screen: opened from Settings, it shows the log as it is kept, masked, and follows it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class LogScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theLogOpensFromSettingsShowsMaskedLinesAndFollowsNewOnes() {
        val log = AppLog()
        log.info("imap", "C: a1 LOGIN deniz@gmail.example abcd-efgh-ijkl-mnop")
        log.warn("idle", "connection lost, trying again in 30 s")
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(testMail(), prefs, settings, startRoute = Routes.SETTINGS, log = log) }

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Günlüğü aç"))
        compose.onNodeWithText("Günlüğü aç").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        compose.onNodeWithText("imap: C: a1 LOGIN deniz@gmail.example ***", substring = true).assertExists()
        compose.onNodeWithText("idle: connection lost", substring = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText("abcd-efgh-ijkl-mnop", substring = true).fetchSemanticsNodes().size)

        // written while the screen is open, from another thread as the sync will
        Thread { log.error("smtp", "sending failed") }.apply { start(); join() }
        compose.waitForIdle()
        compose.onNodeWithText("smtp: sending failed", substring = true).assertExists()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("smtp: sending failed", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Günlüğü aç").assertExists()
    }
}
