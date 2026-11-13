package com.deskmail.settings

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deskmail.core.log.AppLog

/**
 * What the reader's WebView draws into; see [AppSettings.webLayer].
 * - [Default]: the app decides: a hardware layer of the view's own while the shell's glass blurs
 *   what is under it, none otherwise.
 * - [None]: straight into the window, whatever the glass does.
 * - [Hardware], [Software]: always a layer of that kind.
 */
enum class WebLayer { Default, None, Hardware, Software }

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

    /**
     * The glass stays while a message is open: the message's WebView draws into a layer of its
     * own then, which the glass can blur. Off turns the glass off for as long as a message is on
     * screen, as 0.1.0-alpha.2 did.
     */
    var glassOverMessages by mutableStateOf(prefs.getBoolean(KEY_GLASS_OVER_MESSAGES, true))
        private set

    /** On a phone the open message scrolls beneath the bars; off keeps it between them. */
    var readerUnderBars by mutableStateOf(prefs.getBoolean(KEY_READER_UNDER_BARS, true))
        private set

    /** On the dark themes a message is darkened; off shows it on white, as its sender made it. */
    var darkenMessages by mutableStateOf(prefs.getBoolean(KEY_DARKEN_MESSAGES, true))
        private set

    /**
     * What the reader's WebView draws into ([WebLayer]): the app's choice unless an experiment says
     * otherwise. For finding out on a device what the glass over a message needs.
     */
    var webLayer by mutableStateOf(prefs.getString(KEY_WEB_LAYER, null).let { stored -> WebLayer.entries.firstOrNull { it.name == stored } } ?: WebLayer.Default)
        private set

    fun updateGlass(value: Boolean) {
        log?.info("settings", "glass = $value")
        glass = value
        prefs.edit().putBoolean(KEY_GLASS, value).apply()
    }

    fun updateGlassOverMessages(value: Boolean) {
        log?.info("settings", "glassOverMessages = $value")
        glassOverMessages = value
        prefs.edit().putBoolean(KEY_GLASS_OVER_MESSAGES, value).apply()
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

    // ------------------------------------------------------------------ writing

    /**
     * How many seconds a message waits after "Send" before it leaves: the time to take it back.
     * One of [UNDO_SEND_CHOICES].
     */
    var undoSendSeconds by mutableIntStateOf(prefs.getInt(KEY_UNDO_SEND, DEFAULT_UNDO_SEND).let { stored -> if (stored in UNDO_SEND_CHOICES) stored else DEFAULT_UNDO_SEND })
        private set

    fun updateUndoSendSeconds(value: Int) {
        undoSendSeconds = if (value in UNDO_SEND_CHOICES) value else DEFAULT_UNDO_SEND
        prefs.edit().putInt(KEY_UNDO_SEND, undoSendSeconds).apply()
    }

    // ------------------------------------------------------------------ new mail

    /**
     * How the mail is looked for while no window of the app is open: [CHECK_INSTANT] to be told
     * as it comes, a number of minutes to look that often, 0 for never (only by hand). One of
     * [CHECK_CHOICES].
     */
    var checkMinutes by mutableIntStateOf(prefs.getInt(KEY_CHECK_MINUTES, DEFAULT_CHECK_MINUTES).let { stored -> if (stored in CHECK_CHOICES) stored else DEFAULT_CHECK_MINUTES })
        private set

    fun updateCheckMinutes(value: Int) {
        checkMinutes = if (value in CHECK_CHOICES) value else DEFAULT_CHECK_MINUTES
        log?.info("settings", "checkMinutes = $checkMinutes")
        prefs.edit().putInt(KEY_CHECK_MINUTES, checkMinutes).apply()
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

    // ------------------------------------------------------------------ to another device

    /**
     * The settings that go along to another device ("Move settings"), by name: what the user chose,
     * not how the panes of this device's windows happen to be laid out.
     */
    fun portable(): Map<String, Any> = linkedMapOf(
        KEY_UNDO_SEND to undoSendSeconds,
        KEY_CHECK_MINUTES to checkMinutes,
        KEY_DARKEN_MESSAGES to darkenMessages,
        KEY_READER_UNDER_BARS to readerUnderBars,
        KEY_GLASS to glass,
        KEY_GLASS_OVER_MESSAGES to glassOverMessages,
        KEY_WEB_LAYER to webLayer.name,
    )

    /**
     * Takes what another device's [portable] gave: each setting this version knows, when it is of
     * the right kind; anything else is passed over. True when how often the mail is looked for
     * changed, which the background work has to hear of.
     */
    fun adopt(values: Map<String, Any?>): Boolean {
        val check = checkMinutes
        (values[KEY_UNDO_SEND] as? Number)?.let { updateUndoSendSeconds(it.toInt()) }
        (values[KEY_CHECK_MINUTES] as? Number)?.let { updateCheckMinutes(it.toInt()) }
        (values[KEY_DARKEN_MESSAGES] as? Boolean)?.let { updateDarkenMessages(it) }
        (values[KEY_READER_UNDER_BARS] as? Boolean)?.let { updateReaderUnderBars(it) }
        (values[KEY_GLASS] as? Boolean)?.let { updateGlass(it) }
        (values[KEY_GLASS_OVER_MESSAGES] as? Boolean)?.let { updateGlassOverMessages(it) }
        (values[KEY_WEB_LAYER] as? String)?.let { name -> WebLayer.entries.firstOrNull { it.name == name } }?.let { updateWebLayer(it) }
        return checkMinutes != check
    }

    companion object {
        /** The file the app's settings are kept in. */
        const val FILE = "deskmail"

        /** How long "Send" can be taken back, in seconds: what there is to choose from, and what holds until something is chosen. */
        val UNDO_SEND_CHOICES = listOf(5, 10, 15)
        const val DEFAULT_UNDO_SEND = 10

        /** Mail is told of as it comes: its servers are waited on (IMAP IDLE) rather than asked every so often. */
        const val CHECK_INSTANT = -1

        /**
         * How the mail is looked for in the background: as it comes, every so many minutes, or not at
         * all (0, "by hand"). The system allows no interval shorter than a quarter of an hour.
         */
        val CHECK_CHOICES = listOf(CHECK_INSTANT, 15, 30, 60, 0)
        const val DEFAULT_CHECK_MINUTES = CHECK_INSTANT

        /** The list takes a little under half of the room until the divider is dragged. */
        const val DEFAULT_LIST_SHARE = 0.45f

        /** No stored share leaves the list or the reader without room. */
        const val MIN_LIST_SHARE = 0.15f
        const val MAX_LIST_SHARE = 0.85f

        private const val KEY_GLASS = "experiment_glass"
        private const val KEY_GLASS_OVER_MESSAGES = "experiment_glass_over_messages"
        private const val KEY_READER_UNDER_BARS = "experiment_reader_under_bars"
        private const val KEY_DARKEN_MESSAGES = "experiment_darken_messages"
        private const val KEY_WEB_LAYER = "experiment_web_layer"
        private const val KEY_UNDO_SEND = "undo_send_seconds"
        private const val KEY_CHECK_MINUTES = "check_minutes"
        private const val KEY_INSPECTOR_COLLAPSED = "inspector_collapsed"
        private const val KEY_INSPECTOR_WIDTH = "inspector_width"
        private const val KEY_LIST_SHARE = "list_share"
    }
}
