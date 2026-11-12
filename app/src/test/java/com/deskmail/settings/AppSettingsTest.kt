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
}
