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
        assertTrue(settings.readerUnderBars)
        assertTrue(settings.darkenMessages)
        assertEquals(WebLayer.Default, settings.webLayer)
        assertTrue(settings.inspectorCollapsed)
        assertEquals(440f, settings.listWidth, 0f)
    }

    @Test
    fun whatIsChangedIsThereAfterARestart() {
        AppSettings(prefs).apply {
            updateGlass(false)
            updateReaderUnderBars(false)
            updateDarkenMessages(false)
            updateWebLayer(WebLayer.Software)
            updateInspector(width = 380f, collapsed = false)
            updateListWidth(512f)
        }
        // the process starts again: a new object over the same file
        val restarted = AppSettings(prefs)
        assertFalse(restarted.glass)
        assertFalse(restarted.readerUnderBars)
        assertFalse(restarted.darkenMessages)
        assertEquals(WebLayer.Software, restarted.webLayer)
        assertFalse(restarted.inspectorCollapsed)
        assertEquals(380f, restarted.inspectorWidth, 0f)
        assertEquals(512f, restarted.listWidth, 0f)
    }

    @Test
    fun aValueFromANewerVersionFallsBackToTheDefault() {
        prefs.edit().putString("experiment_web_layer", "Vulkan").commit()
        assertEquals(WebLayer.Default, AppSettings(prefs).webLayer)
    }
}
