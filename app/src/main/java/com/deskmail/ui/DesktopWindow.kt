package com.deskmail.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.WindowInsets

/**
 * Whether a window is one of a desktop's: Samsung DeX, or Android's desktop windowing, where every
 * window can be moved and sized freely and has a caption bar of its own. There a message is written
 * in a window of its own ([com.deskmail.ui.compose.WriteApart]).
 */
object DesktopWindow {

    fun isDesktop(activity: Activity): Boolean = samsungDex(activity.resources.configuration) || freeform(activity)

    /**
     * DeX, as Samsung tells apps of it: a field of its own in the configuration, compared with the
     * constant that stands for "on" (Samsung's guide for DeX apps does the same). False elsewhere.
     */
    fun samsungDex(configuration: Configuration): Boolean =
        try {
            val type = configuration.javaClass
            type.getField("SEM_DESKTOP_MODE_ENABLED").getInt(null) == type.getField("semDesktopModeEnabled").getInt(configuration)
        } catch (e: ReflectiveOperationException) {
            false
        } catch (e: RuntimeException) {
            false
        }

    /**
     * One of Android's desktop windows (Android 15 and later): a window beside others with a caption
     * bar of its own. A full-screen window or one half of a split screen has none.
     */
    private fun freeform(activity: Activity): Boolean {
        if (!activity.isInMultiWindowMode || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
        val insets = activity.window?.decorView?.rootWindowInsets ?: return false
        return insets.getInsets(WindowInsets.Type.captionBar()).top > 0
    }
}
