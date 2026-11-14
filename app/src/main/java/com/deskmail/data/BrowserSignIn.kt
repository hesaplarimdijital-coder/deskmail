package com.deskmail.data

import com.deskmail.core.log.AppLog
import com.deskmail.core.oauth.AuthorizationAnswer
import com.deskmail.core.oauth.AuthorizationRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * The sign-in under way in the browser, and the way back from it. The app opens the provider's
 * page; the browser comes back to the app at the address registered with the provider
 * ([com.deskmail.OAuthRedirectActivity]), and what it brought is handed to the request it answers.
 *
 * One sign-in at a time: a new one gives up the one before. Nothing of it outlives the process:
 * should the app be closed while the browser is open, the sign-in is begun anew.
 *
 * @param waitMs how long the browser is waited for before the sign-in is given up.
 */
class BrowserSignIn(private val log: AppLog? = null, private val waitMs: Long = WAIT_MS) {

    /** What became of opening the page and waiting for the browser. */
    sealed interface Outcome {
        data class Answered(val answer: AuthorizationAnswer) : Outcome

        /** No app on the device could show the page. */
        data object NoBrowser : Outcome

        /** The browser did not come back in time. */
        data object TimedOut : Outcome
    }

    private class Waiting(val request: AuthorizationRequest, val answer: CompletableDeferred<AuthorizationAnswer> = CompletableDeferred())

    private val waiting = AtomicReference<Waiting?>(null)

    /**
     * Opens [request]'s page with [open] and waits until the browser comes back with its answer.
     * Cancelling the call gives the sign-in up; an answer that comes after that is not taken.
     *
     * @param open shows the page; false when nothing could.
     */
    suspend fun ask(request: AuthorizationRequest, open: suspend (url: String) -> Boolean): Outcome {
        val mine = Waiting(request)
        waiting.getAndSet(mine)?.answer?.cancel()
        try {
            if (!open(request.url())) return Outcome.NoBrowser
            val answer = withTimeoutOrNull(waitMs) { mine.answer.await() }
            if (answer == null) log?.info(TAG, "the browser did not come back in ${waitMs / 60_000} minutes; the sign-in was given up")
            return answer?.let { Outcome.Answered(it) } ?: Outcome.TimedOut
        } finally {
            waiting.compareAndSet(mine, null)
        }
    }

    /**
     * The browser came back to the app at [uri]: the answer goes to the sign-in that waits for it.
     * False when none does (it was given up, or the answer is to another request); the address is
     * not written down, since it carries the code.
     */
    fun cameBack(uri: String): Boolean {
        val current = waiting.get()
        val answer = current?.request?.answer(uri)
        if (current == null || answer == null) {
            log?.warn(TAG, "the browser came back from a sign-in that nothing waits for")
            return false
        }
        return current.answer.complete(answer)
    }

    companion object {
        private const val TAG = "sign-in"

        /** Long enough to sign in with a second factor, and to look for the password first. */
        const val WAIT_MS = 15 * 60_000L
    }
}
