package com.deskmail.ui.mail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The device's WebView is got going once a run, before the first message is opened. */
@RunWith(AndroidJUnit4::class)
class WebViewWarmUpTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val log = AppLog()

    private fun warmUps() = log.lines().count { it.contains("WebView warmed up in") }

    @Test
    fun theWebViewIsWarmedUpOnceARun() {
        val warmUp = WebViewWarmUp(log)
        assertTrue(warmUp.run(context))
        assertEquals(1, warmUps())
        // once is enough
        assertFalse(warmUp.run(context))
        assertEquals(1, warmUps())
    }

    @Test
    fun aMessageThatWasOpenedFirstHasDoneTheWork() {
        val warmUp = WebViewWarmUp(log)
        warmUp.made()
        assertFalse(warmUp.run(context))
        warmUp.schedule(context)
        assertTrue(log.lines().isEmpty())
    }
}
