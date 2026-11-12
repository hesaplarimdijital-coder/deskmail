package com.deskmail.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import desktopkit.components.DeskButton
import desktopkit.components.DeskTextField
import desktopkit.theme.DesktopKitTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How the activity knows that text is being typed, so that J, K, S and the other single keys are
 * left to the text field: it asks the window's focused view whether it is a text editor
 * (View.onCheckIsTextEditor). That is Android's own contract, the one the system uses to decide
 * whether a keyboard belongs on screen, and Compose answers it for its text fields.
 *
 * What holds everywhere is asserted. What Robolectric's test window answers once a text field has
 * the focus is only reported for now (the test's output), step by step: its window does not take
 * and give the focus the way a device's does, and a claim made blind would test the test window.
 */
@RunWith(AndroidJUnit4::class)
class TypingDetectionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun typing(): Boolean = compose.activity.currentFocus?.onCheckIsTextEditor() == true

    private fun report(step: String) {
        compose.waitForIdle()
        val focus = compose.activity.currentFocus
        val decor = compose.activity.window.decorView
        val field = compose.onNode(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused)
        println(
            "typing probe [$step]: viewFocus=${focus?.javaClass?.simpleName} touchMode=${decor.isInTouchMode} " +
                "windowFocus=${decor.hasWindowFocus()} fieldFocused=$field textEditor=${focus?.onCheckIsTextEditor()}",
        )
    }

    @Test
    fun aButtonInFocusIsNoTyping() {
        var text by mutableStateOf("")
        val button = FocusRequester()
        val field = FocusRequester()
        compose.setContent {
            DesktopKitTheme {
                Column {
                    DeskButton("Düğme", {}, Modifier.focusRequester(button).testTag("button"))
                    DeskTextField(text, { text = it }, Modifier.focusRequester(field).testTag("field"))
                }
            }
        }
        report("start")
        compose.runOnUiThread { button.requestFocus() }
        report("button asked for the focus")
        assertFalse(typing())

        compose.runOnUiThread { field.requestFocus() }
        report("field asked for the focus")
        compose.onNodeWithTag("field").performClick()
        report("field clicked")
        compose.runOnUiThread { button.requestFocus() }
        report("button asked for the focus again")
        assertFalse(typing())
    }
}
