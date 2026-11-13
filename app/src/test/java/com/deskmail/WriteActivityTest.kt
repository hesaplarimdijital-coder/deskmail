package com.deskmail

import android.content.Intent
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.ui.DesktopWindow
import com.deskmail.ui.compose.ComposeActions
import com.deskmail.ui.compose.WriteWindows
import com.deskmail.ui.mail.MailUiState
import desktopkit.components.ToastState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * The window of its own that writes a message, as the system starts it, and what the app keeps of
 * such windows. (Its screen: WriteWindowTest; the window it is opened from: WriteApartTest.)
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr-w1280dp-h800dp-mdpi")
class WriteActivityTest {

    private val app: DeskMailApplication = ApplicationProvider.getApplicationContext()

    @Test
    fun aWindowWithoutAMessageGoesAtOnce() {
        val activity = Robolectric.buildActivity(WriteActivity::class.java, Intent(app, WriteActivity::class.java)).setup().get()
        assertTrue(activity.isFinishing)
    }

    /**
     * A window the system makes anew after it ended the app's process reads its message as it was
     * kept, with what was typed into it: that is no message begun just now, to be thrown away when
     * it is closed as it was read.
     */
    @Test
    fun aWindowMadeAnewKeepsItsMessage() {
        assertTrue(WriteActivity.fresh(restored = false, begunFresh = true))
        assertFalse(WriteActivity.fresh(restored = true, begunFresh = true))
        assertFalse(WriteActivity.fresh(restored = false, begunFresh = false))
    }

    @Test
    fun aMessageIsWrittenInOneWindowAtMost() {
        val windows = WriteWindows()
        windows.opened(draftId = 5, task = 31)
        assertEquals(31, windows.taskOf(5))

        // a window that is gone does not take the message along from the one that has it now
        windows.opened(draftId = 5, task = 32)
        windows.closed(draftId = 5, task = 31)
        assertEquals(32, windows.taskOf(5))
        windows.closed(draftId = 5, task = 32)
        assertNull(windows.taskOf(5))
    }

    @Test
    fun whatAWindowOfItsOwnDidIsToldWhereItCameFromOrElsewhere() {
        val windows = WriteWindows()
        val first = actions()
        val second = actions()
        assertNull(windows.homeFor(opener = 1))

        windows.home(task = 1, actions = first)
        windows.home(task = 2, actions = second)
        assertSame(first, windows.homeFor(opener = 1))
        assertSame(second, windows.homeFor(opener = 2))

        // the window it came from is gone: another window with the mail is told
        windows.leave(task = 1, actions = first)
        assertSame(second, windows.homeFor(opener = 1))

        // a window that was drawn anew before the old one went keeps its place
        val again = actions()
        windows.home(task = 2, actions = again)
        windows.leave(task = 2, actions = second)
        assertSame(again, windows.homeFor(opener = 2))
    }

    @Test
    fun deXIsOnlyWhatSamsungSaysIsDeX() {
        assertFalse(DesktopWindow.samsungDex(Configuration()))
    }

    private fun actions(): ComposeActions {
        val mail = testMail()
        return ComposeActions(testComposer(mail), mail, MailUiState(), CoroutineScope(Dispatchers.Unconfined), ToastState(), app.resources)
    }
}
