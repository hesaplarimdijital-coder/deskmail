package com.deskmail.settings

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deskmail.core.log.AppLog

/** How the reader's WebView is drawn into the window; see [AppSettings.webLayer]. */
enum class WebLayer { Default, Hardware, Software }

/**
 * The app's own settings, stored in shared preferences and observable from Compose. One instance
 * serves every window, so a switch flipped in one window changes the others at once. (Theme,
 * accent and density are the kit's and live in its own file.) A changed experiment is noted in
 * the [log], so a shared log says what was switched when.
 */
@Stable
class AppSettings(private val prefs: SharedPreferences, private val log: AppLog? = null) {

    // ------------------------------------------------------------------ experiments
    // What the first pre-releases are for: each of these is a question to answer on the devices.

    /** The kit's frosted glass on bars, sheets and menus; off draws them as solid surfaces. */
    var glass by mutableStateOf(prefs.getBoolean(KEY_GLASS, true))
        private set

    /** On a phone the open message scrolls beneath the bars; off keeps it between them. */
    var readerUnderBars by mutableStateOf(prefs.getBoolean(KEY_READER_UNDER_BARS, true))
        private set

    /** On the dark themes a message is darkened; off shows it on white, as its sender made it. */
    var darkenMessages by mutableStateOf(prefs.getBoolean(KEY_DARKEN_MESSAGES, true))
        private set

    /**
     * The layer the reader's WebView draws into: the view's own choice, a hardware layer, or a
     * software one. For finding out what the glass over a message needs in order to blur it.
     */
    var webLayer by mutableStateOf(prefs.getString(KEY_WEB_LAYER, null).let { stored -> WebLayer.entries.firstOrNull { it.name == stored } } ?: WebLayer.Default)
        private set

    fun updateGlass(value: Boolean) {
        log?.info("settings", "glass = $value")
        glass = value
        prefs.edit().putBoolean(KEY_GLASS, value).apply()
    }

    fun updateReaderUnderBars(value: Boolean) {
        log?.info("settings", "readerUnderBars = $value")
        readerUnderBars = value
        prefs.edit().putBoolean(KEY_READER_UNDER_BARS, value).apply()
    }

    fun updateDarkenMessages(value: Boolean) {
        log?.info("settings", "darkenMessages = $value")
        darkenMessages = value
        prefs.edit().putBoolean(KEY_DARKEN_MESSAGES, value).apply()
    }

    fun updateWebLayer(value: WebLayer) {
        log?.info("settings", "webLayer = $value")
        webLayer = value
        prefs.edit().putString(KEY_WEB_LAYER, value.name).apply()
    }

    // ------------------------------------------------------------------ the panes of a large window

    /** The inspector beside the reader starts closed: it leaves little room on anything but a monitor. */
    var inspectorCollapsed by mutableStateOf(prefs.getBoolean(KEY_INSPECTOR_COLLAPSED, true))
        private set

    var inspectorWidth by mutableFloatStateOf(prefs.getFloat(KEY_INSPECTOR_WIDTH, 320f))
        private set

    /**
     * The message list's share of the room it has with the reader, 0 to 1: what the divider between
     * them was dragged to. A share and not a width, so a window of another size (the tablet upright,
     * a monitor) divides its room the same way.
     */
    var listShare by mutableFloatStateOf(prefs.getFloat(KEY_LIST_SHARE, DEFAULT_LIST_SHARE).coerceIn(MIN_LIST_SHARE, MAX_LIST_SHARE))
        private set

    fun updateInspector(width: Float, collapsed: Boolean) {
        inspectorWidth = width
        inspectorCollapsed = collapsed
        prefs.edit().putFloat(KEY_INSPECTOR_WIDTH, width).putBoolean(KEY_INSPECTOR_COLLAPSED, collapsed).apply()
    }

    fun updateListShare(share: Float) {
        listShare = share.coerceIn(MIN_LIST_SHARE, MAX_LIST_SHARE)
        prefs.edit().putFloat(KEY_LIST_SHARE, listShare).apply()
    }

    companion object {
        /** The file the app's settings are kept in. */
        const val FILE = "deskmail"

        /** The list takes a little under half of the room until the divider is dragged. */
        const val DEFAULT_LIST_SHARE = 0.45f

        /** No stored share leaves the list or the reader without room. */
        const val MIN_LIST_SHARE = 0.15f
        const val MAX_LIST_SHARE = 0.85f

        private const val KEY_GLASS = "experiment_glass"
        private const val KEY_READER_UNDER_BARS = "experiment_reader_under_bars"
        private const val KEY_DARKEN_MESSAGES = "experiment_darken_messages"
        private const val KEY_WEB_LAYER = "experiment_web_layer"
        private const val KEY_INSPECTOR_COLLAPSED = "inspector_collapsed"
        private const val KEY_INSPECTOR_WIDTH = "inspector_width"
        private const val KEY_LIST_SHARE = "list_share"
    }
}
