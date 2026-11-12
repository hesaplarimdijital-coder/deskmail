package com.deskmail.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TestApp
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.Routes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings on a phone: the kit's sections, then the experiments, whose switches change the app's settings. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class SettingsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Flips the switch row named [title]. By its semantics action, not by a tap at its place: the
     * list lies under the phone's bottom bar, and a row scrolled into view may rest beneath it.
     */
    private fun toggle(title: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun theExperimentSwitchesChangeTheSettings() {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent { TestApp(testMail(), prefs, settings, startRoute = Routes.SETTINGS) }
        assertTrue(settings.glass)
        toggle("Cam çubuklar")
        assertFalse(settings.glass)

        assertTrue(settings.darkenMessages)
        toggle("Koyu temada iletiyi karart")
        assertFalse(settings.darkenMessages)
    }
}
