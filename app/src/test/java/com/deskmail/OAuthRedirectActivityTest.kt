package com.deskmail

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.oauth.AuthorizationAnswer
import com.deskmail.core.oauth.AuthorizationRequest
import com.deskmail.core.oauth.OAuthConfig
import com.deskmail.data.BrowserSignIn
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** The browser comes back to the app: what it brought reaches the sign-in, and the app's window comes back over the browser. */
@RunWith(AndroidJUnit4::class)
class OAuthRedirectActivityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val config = OAuthConfig("https://login.example/authorize", "https://login.example/token", "client", "com.deskmail://oauth2redirect", listOf("offline_access"))

    private fun cameBack(uri: String) =
        Robolectric.buildActivity(OAuthRedirectActivity::class.java, Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setClass(context, OAuthRedirectActivity::class.java)).create().get()

    @Test
    fun theAnswerReachesTheSignInAndTheWindowComesBack() = runBlocking {
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { context.deskMail.signIn.ask(AuthorizationRequest(config, state = "s1")) { true } }

        val activity = cameBack("com.deskmail://oauth2redirect?code=M.C507-code&state=s1")

        val answer = (waiting.await() as BrowserSignIn.Outcome.Answered).answer
        assertEquals("M.C507-code", (answer as AuthorizationAnswer.Code).code)
        // the window the sign-in began in, with the browser's tab over it gone; and nothing of its own
        val window = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, window.component?.className)
        assertTrue(window.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(window.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun anAnswerNothingWaitsForStillBringsTheWindowBack() {
        val activity = cameBack("com.deskmail://oauth2redirect?code=M.C507-code&state=nobody")

        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun theAddressIsTheOneRegisteredWithMicrosoft() {
        // the manifest takes the browser's way back to the app there, and nowhere else
        val back = Intent(Intent.ACTION_VIEW, Uri.parse("com.deskmail://oauth2redirect?code=c&state=s"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName)
        val found = context.packageManager.queryIntentActivities(back, 0).map { it.activityInfo.name }
        assertEquals(listOf(OAuthRedirectActivity::class.java.name), found)
    }
}
