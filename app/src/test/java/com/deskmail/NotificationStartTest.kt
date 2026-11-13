package com.deskmail

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deskmail.notify.OpenRequest
import com.deskmail.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The real activity as a tapped notification reaches it: started by it, and asked again while it
 * is there. (On the app's own database, which has no mail: what is looked at is what the window
 * was asked to open. What opening a message shows is NotifyUiTest's.)
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class NotificationStartTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** What the app's own notifications are known by. */
    private val token: String get() = context.deskMail.notifyStore.token

    @Test
    fun aTappedNotificationReachesTheWindowAtItsStartAndWhileItIsThere() {
        ActivityScenario.launch<MainActivity>(OpenRequest.intent(context, 5, token)).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val window = ViewModelProvider(activity)[WindowState::class.java]
                assertNull(window.mail.openRequest)
                assertEquals(5L, window.mail.openedId(Routes.INBOX))
            }

            // another one, tapped while the window is there
            scenario.onActivity { activity ->
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, OpenRequest.intent(context, 8, token))
            }
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val window = ViewModelProvider(activity)[WindowState::class.java]
                assertNull(window.mail.openRequest)
                assertEquals(8L, window.mail.openedId(Routes.INBOX))
            }
        }
    }

    /** Started from the launcher, the window was asked for nothing. */
    @Test
    fun anOrdinaryStartAsksForNothing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val window = ViewModelProvider(activity)[WindowState::class.java]
                assertNull(window.mail.openRequest)
                assertNull(window.mail.openedId(Routes.INBOX))
            }
        }
    }

    /** Any app can start the window; one that asks it for a message without what the app's notifications carry is not heard. */
    @Test
    fun anotherAppsRequestToOpenAMessageIsNotHeard() {
        val forged = OpenRequest.intent(context, 5, "guessed")

        ActivityScenario.launch<MainActivity>(forged).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val window = ViewModelProvider(activity)[WindowState::class.java]
                assertNull(window.mail.openRequest)
                assertNull(window.mail.openedId(Routes.INBOX))
            }
        }
    }
}
