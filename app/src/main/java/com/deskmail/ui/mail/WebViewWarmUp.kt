package com.deskmail.ui.mail

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.webkit.WebView
import com.deskmail.R
import com.deskmail.core.log.AppLog

/**
 * Gets the device's WebView going before the first message is opened.
 *
 * The first WebView of a run takes a quarter of a second to make: its engine is loaded and
 * started then, on the main thread, and the process that draws pages is started with the first
 * page. Left to the moment a message is opened, the message's page came late and without its
 * slide, and its text later still (Galaxy S24, 0.1.0-alpha.1: 0.25 s and another 0.25 s; the
 * second message of the same run had neither). So one WebView is made and given an empty page as
 * soon as the app's first screen is drawn and nothing else is going on, and let go of again.
 *
 * Once per run. A message opened before that has done the same work: then there is nothing to do.
 */
class WebViewWarmUp(private val log: AppLog) {
    private var done = false
    private var scheduled = false

    /** A WebView was made: the engine is going. */
    fun made() {
        done = true
    }

    /** Warms the WebView up once the main thread has had [delayMs] and then nothing to do. From the main thread. */
    fun schedule(context: Context, delayMs: Long = DELAY_MS) {
        if (done || scheduled) return
        scheduled = true
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            Looper.myQueue().addIdleHandler {
                run(app)
                false
            }
        }, delayMs)
    }

    /** Makes a WebView, has it load an empty page and lets go of it a little later. Returns whether it did. */
    internal fun run(context: Context): Boolean {
        if (done) return false
        done = true
        val started = SystemClock.elapsedRealtime()
        return runCatching {
            val themed = ContextThemeWrapper(context.applicationContext, R.style.DeskMailWebLight)
            val web = createMessageWebView(themed) {}
            web.loadDataWithBaseURL(null, EMPTY_PAGE, "text/html", "utf-8", null)
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { web.destroy() } }, KEEP_MS)
        }.onSuccess {
            log.info(TAG, "WebView warmed up in ${SystemClock.elapsedRealtime() - started} ms")
        }.onFailure {
            // no usable WebView: the reader finds that out for itself and shows the plain text
            log.warn(TAG, "the WebView could not be warmed up: ${it.javaClass.simpleName}: ${it.message}")
        }.isSuccess
    }

    private companion object {
        const val TAG = "reader"

        /** After the first screen: an app that starts cold has drawn it by then. */
        const val DELAY_MS = 700L

        /** How long the warm-up's WebView is kept: its empty page has long been loaded by then. */
        const val KEEP_MS = 5_000L
        const val EMPTY_PAGE = "<!doctype html><html><body></body></html>"
    }
}
