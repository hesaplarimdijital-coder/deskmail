package com.deskmail.ui.mail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** How the list and the reader divide a window's room, without drawing them. */
@RunWith(AndroidJUnit4::class)
class MailLayoutTest {

    private fun settings() =
        AppSettings(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("layout-test", Context.MODE_PRIVATE))

    /** The windows the app is made for: a little under half of the room, to begin with. */
    @Test
    fun theListTakesItsShareOfTheRoom() {
        val share = AppSettings.DEFAULT_LIST_SHARE
        // beside a rail, as soon as two panes fit
        assertEquals(396f, MailLayout.listWidth(880f, share), 0.01f)
        // the tablet on its side: 1480 dp less the sidebar (280 for a finger, 240 for a mouse)
        assertEquals(540f, MailLayout.listWidth(1200f, share), 0.01f)
        assertEquals(558f, MailLayout.listWidth(1240f, share), 0.01f)
        // a DeX window on a monitor
        assertEquals(756f, MailLayout.listWidth(1680f, share), 0.01f)
    }

    /** Neither pane is squeezed out, whatever the share. */
    @Test
    fun bothPanesKeepTheirLeastWidth() {
        assertEquals(MailLayout.MIN_LIST, MailLayout.listWidth(1000f, 0.15f), 0.01f)
        // 1000 dp less the reader's 360 and the divider
        assertEquals(624f, MailLayout.listWidth(1000f, 0.85f), 0.01f)
        // too little room for both: the list keeps its least width, as the split pane does
        assertEquals(MailLayout.MIN_LIST, MailLayout.listWidth(500f, 0.45f), 0.01f)
    }

    /** A drag that ran on past the panes' limits counts as the width that was shown. */
    @Test
    fun aShareIsThatOfTheWidthThatWasShown() {
        assertEquals(0.5f, MailLayout.listShare(1200f, 600f), 0.0001f)
        assertEquals(824f / 1200f, MailLayout.listShare(1200f, 5000f), 0.0001f)
        assertEquals(320f / 1200f, MailLayout.listShare(1200f, -40f), 0.0001f)
    }

    @Test
    fun aWindowThatIsResizedKeepsTheShare() {
        val ui = MailUiState()
        ui.fitList(1200f)
        assertEquals(540f, ui.listSplit.startWidthDp, 0.01f)
        ui.fitList(1680f)
        assertEquals(756f, ui.listSplit.startWidthDp, 0.01f)
        // nothing was dragged
        ui.noteDraggedList()
        assertEquals(AppSettings.DEFAULT_LIST_SHARE, ui.listShare, 0f)
    }

    /** The divider is dragged: the width becomes the share, is stored, and the next window starts with it. */
    @Test
    fun aDraggedDividerBecomesTheStoredShare() {
        val settings = settings()
        val ui = MailUiState(settings)
        ui.fitList(1200f)
        ui.listSplit.startWidthDp = 600f
        ui.noteDraggedList()
        assertEquals(0.5f, ui.listShare, 0.0001f)
        assertEquals(0.5f, settings.listShare, 0.0001f)
        // the same share of a wider window
        ui.fitList(1600f)
        assertEquals(800f, ui.listSplit.startWidthDp, 0.01f)

        val next = MailUiState(settings())
        next.fitList(1200f)
        assertEquals(600f, next.listSplit.startWidthDp, 0.01f)
    }

    /** A window laid out again before the drag was noted takes the drag with it. */
    @Test
    fun aDragIsNotLostToANewLayout() {
        val settings = settings()
        val ui = MailUiState(settings)
        ui.fitList(1200f)
        ui.listSplit.startWidthDp = 480f
        ui.fitList(1000f)
        assertEquals(0.4f, settings.listShare, 0.0001f)
        assertEquals(400f, ui.listSplit.startWidthDp, 0.01f)
    }

    /** Fitting the list is no drag: only the divider changes the share. */
    @Test
    fun fittingAloneStoresNothing() {
        val settings = settings()
        val ui = MailUiState(settings)
        ui.fitList(900f)
        ui.fitList(1700f)
        ui.noteDraggedList()
        ui.fitList(1100f)
        assertEquals(AppSettings.DEFAULT_LIST_SHARE, settings.listShare, 0f)
        assertEquals(AppSettings.DEFAULT_LIST_SHARE, ui.listShare, 0f)
    }
}
