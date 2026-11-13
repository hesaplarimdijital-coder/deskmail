package com.deskmail

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The real activity as a mailto link in another app starts it. On the app's own database, which
 * has no account: there is nobody to write from, and the window says so. (What is begun where
 * there is an account is ComposeTest's, what an intent asks for ComposeRequestTest's.)
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class ShareStartTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aMailtoLinkReachesTheWindowWhichHasNoAccountToWriteFrom() {
        val link = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:ayse@example.org?subject=Merhaba")).setClass(context, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(link).use { scenario ->
            compose.waitForIdle()

            scenario.onActivity { activity ->
                val window = ViewModelProvider(activity)[WindowState::class.java]
                // taken up, and nothing begun
                assertNull(window.mail.composeRequest)
                assertNull(window.mail.writing)
            }
            compose.onNodeWithText("Önce bir hesap ekle.").assertExists()
        }
    }
}
