package com.deskmail.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSettingsTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("settings-test", Context.MODE_PRIVATE)

    @Test
    fun aFreshInstallStartsWithGlassAndAClosedInspector() {
        val settings = AppSettings(prefs)
        assertTrue(settings.glass)
        assertTrue(settings.glassOverMessages)
        assertTrue(settings.readerUnderBars)
        assertTrue(settings.darkenMessages)
        assertEquals(WebLayer.Default, settings.webLayer)
        assertEquals(10, settings.undoSendSeconds)
        // mail is told of as it comes, unless something else is chosen
        assertEquals(AppSettings.CHECK_INSTANT, settings.checkMinutes)
        assertTrue(settings.inspectorCollapsed)
        assertEquals(0.45f, settings.listShare, 0f)
    }

    @Test
    fun whatIsChangedIsThereAfterARestart() {
        AppSettings(prefs).apply {
            updateGlass(false)
            updateGlassOverMessages(false)
            updateReaderUnderBars(false)
            updateDarkenMessages(false)
            updateWebLayer(WebLayer.Software)
            updateUndoSendSeconds(15)
            updateCheckMinutes(60)
            updateInspector(width = 380f, collapsed = false)
            updateListShare(0.6f)
        }
        // the process starts again: a new object over the same file
        val restarted = AppSettings(prefs)
        assertFalse(restarted.glass)
        assertFalse(restarted.glassOverMessages)
        assertFalse(restarted.readerUnderBars)
        assertFalse(restarted.darkenMessages)
        assertEquals(WebLayer.Software, restarted.webLayer)
        assertEquals(15, restarted.undoSendSeconds)
        assertEquals(60, restarted.checkMinutes)
        assertFalse(restarted.inspectorCollapsed)
        assertEquals(380f, restarted.inspectorWidth, 0f)
        assertEquals(0.6f, restarted.listShare, 0f)
    }

    /** Whatever a share was stored as, both the list and the reader keep some room. */
    @Test
    fun aStoredShareStaysWithinBounds() {
        prefs.edit().putFloat("list_share", 7f).commit()
        assertEquals(0.85f, AppSettings(prefs).listShare, 0f)
        AppSettings(prefs).updateListShare(0.01f)
        assertEquals(0.15f, AppSettings(prefs).listShare, 0f)
    }

    @Test
    fun aValueFromANewerVersionFallsBackToTheDefault() {
        prefs.edit().putString("experiment_web_layer", "Vulkan").commit()
        assertEquals(WebLayer.Default, AppSettings(prefs).webLayer)
    }

    /** "Send" can be taken back for one of the times there are to choose from, whatever was stored or is asked for. */
    @Test
    fun theTimeToTakeAMessageBackIsOneOfTheChoices() {
        prefs.edit().putInt("undo_send_seconds", 3600).commit()
        val settings = AppSettings(prefs)
        assertEquals(10, settings.undoSendSeconds)
        settings.updateUndoSendSeconds(5)
        assertEquals(5, AppSettings(prefs).undoSendSeconds)
        settings.updateUndoSendSeconds(0)
        assertEquals(10, AppSettings(prefs).undoSendSeconds)
    }

    /** How the mail is looked for is one of the choices: as it comes, every so often, or "by hand" (0). */
    @Test
    fun howTheMailIsLookedForIsOneOfTheChoices() {
        val settings = AppSettings(prefs)
        settings.updateCheckMinutes(0)
        assertEquals(0, AppSettings(prefs).checkMinutes)
        settings.updateCheckMinutes(30)
        assertEquals(30, AppSettings(prefs).checkMinutes)
        settings.updateCheckMinutes(AppSettings.CHECK_INSTANT)
        assertEquals(AppSettings.CHECK_INSTANT, AppSettings(prefs).checkMinutes)

        // what is none of the choices (the system allows nothing shorter than a quarter of an hour) is the choice a fresh install starts with
        settings.updateCheckMinutes(5)
        assertEquals(AppSettings.CHECK_INSTANT, settings.checkMinutes)
        prefs.edit().putInt("check_minutes", 7).commit()
        assertEquals(AppSettings.CHECK_INSTANT, AppSettings(prefs).checkMinutes)
    }
}
