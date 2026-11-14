package com.deskmail.data

import com.deskmail.core.log.AppLog
import com.deskmail.core.oauth.AuthorizationAnswer
import com.deskmail.core.oauth.AuthorizationRequest
import com.deskmail.core.oauth.OAuthConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The way back from the browser: only the answer to the sign-in under way is taken. */
class BrowserSignInTest {

    private val log = AppLog()
    private val config = OAuthConfig("https://login.example/authorize", "https://login.example/token", "client", "com.deskmail://oauth2redirect", listOf("offline_access"))

    private fun request(state: String) = AuthorizationRequest(config, state = state)

    @Test
    fun theAnswerGoesToTheSignInItBelongsTo() = runBlocking {
        val browser = BrowserSignIn(log)
        val opened = CompletableDeferred<String>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            browser.ask(request("s1")) { url ->
                opened.complete(url)
                true
            }
        }
        assertTrue(opened.await().contains("state=s1"))

        // another sign-in's answer, or none, is not this one's
        assertFalse(browser.cameBack("com.deskmail://oauth2redirect?code=x&state=s2"))
        assertFalse(browser.cameBack("com.deskmail://elsewhere?code=x&state=s1"))
        assertTrue(browser.cameBack("com.deskmail://oauth2redirect?code=c1&state=s1"))

        val answer = (waiting.await() as BrowserSignIn.Outcome.Answered).answer
        assertEquals("c1", (answer as AuthorizationAnswer.Code).code)
        // done with: a second answer finds nobody waiting
        assertFalse(browser.cameBack("com.deskmail://oauth2redirect?code=c1&state=s1"))
        assertTrue(log.lines().any { it.contains("the browser came back from a sign-in that nothing waits for") })
        // the address with its code is not written down
        assertTrue(log.lines().none { "c1" in it || "code=x" in it })
    }

    @Test
    fun aNewSignInGivesUpTheOneBefore() = runBlocking {
        val browser = BrowserSignIn(log)
        val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { browser.ask(request("s1")) { true } } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { browser.ask(request("s2")) { true } }

        // the first is given up; an answer to it is no longer taken
        assertTrue(first.await().exceptionOrNull() is CancellationException)
        assertFalse(browser.cameBack("com.deskmail://oauth2redirect?code=c1&state=s1"))
        assertTrue(browser.cameBack("com.deskmail://oauth2redirect?error=access_denied&state=s2"))
        assertEquals(BrowserSignIn.Outcome.Answered(AuthorizationAnswer.Refused("access_denied", "")), second.await())
    }

    @Test
    fun withoutABrowserOrAnAnswerThereIsNoSignIn() = runBlocking {
        val browser = BrowserSignIn(log, waitMs = 100)

        assertEquals(BrowserSignIn.Outcome.NoBrowser, browser.ask(request("s1")) { false })
        // nothing waits any more
        assertFalse(browser.cameBack("com.deskmail://oauth2redirect?code=c1&state=s1"))

        assertEquals(BrowserSignIn.Outcome.TimedOut, browser.ask(request("s2")) { true })
        assertFalse(browser.cameBack("com.deskmail://oauth2redirect?code=c2&state=s2"))
    }
}
