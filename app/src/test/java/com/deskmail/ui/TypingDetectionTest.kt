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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import desktopkit.components.DeskButton
import desktopkit.components.DeskTextField
import desktopkit.theme.DesktopKitTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How the activity knows that text is being typed, so that J, K, S and the other single keys are
 * left to the text field. Robolectric's window never names a focused view (its currentFocus stays
 * null whatever Compose focuses), so what is tested here is the answer that does not need one:
 * Compose's own description of the screen.
 */
@RunWith(AndroidJUnit4::class)
class TypingDetectionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun typing(): Boolean {
        compose.waitForIdle()
        return compose.runOnUiThread<Boolean> { isTyping(compose.activity.window.decorView, compose.activity.currentFocus) }
    }

    @Test
    fun textIsBeingTypedExactlyWhileATextFieldHasTheFocus() {
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
        compose.runOnUiThread { button.requestFocus() }
        assertFalse(typing())

        compose.runOnUiThread { field.requestFocus() }
        assertTrue(typing())

        compose.runOnUiThread { button.requestFocus() }
        assertFalse(typing())

        // a finger or a mouse puts the focus there as well
        compose.onNodeWithTag("field").performClick()
        assertTrue(typing())
    }
}
