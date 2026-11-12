package com.deskmail

import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.deskmail.ui.CommandHost
import com.deskmail.ui.DeskMailApp
import com.deskmail.ui.KeyRouter
import com.deskmail.ui.isTyping
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.settings.SettingsUiState
import com.deskmail.ui.shortcuts
import desktopkit.components.CommandPaletteState
import desktopkit.shell.DeskWindows

/**
 * One window of the app. There can be several (DeX, a tablet's split screen): each is an activity
 * of its own with its own shell, opened at the route the other window asked for.
 */
class MainActivity : ComponentActivity() {

    private val state: WindowState by lazy { ViewModelProvider(this)[WindowState::class.java] }

    /** Ctrl+K: the command palette. Kept here, so the keys can be left alone while it is open. */
    private val palette = CommandPaletteState()

    /** The composition says what performs the window's commands. */
    private val commands = CommandHost()

    /** How often the window came to the front; the screens read it. */
    private var resumes by mutableIntStateOf(0)

    private val keys = KeyRouter(
        registry = shortcuts,
        paletteOpen = { palette.isOpen },
        typing = { isTyping(window.decorView, currentFocus) },
        perform = { id -> commands.perform(id) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeskMailApp(
                mail = deskMail.mail,
                settings = deskMail.settings,
                log = deskMail.log,
                accounts = deskMail.accounts,
                ui = state.mail,
                settingsUi = state.settings,
                resumes = resumes,
                startRoute = DeskWindows.startRoute(intent),
                palette = palette,
                commands = commands,
            )
        }
        // the first message is to open like every other one
        deskMail.webWarmUp.schedule(this)
    }

    /** Back in front: the screen that shows mail looks for what is new, unless it just did. */
    override fun onResume() {
        super.onResume()
        resumes++
    }

    /** Every key passes here first, whatever has the focus: the shortcut table decides. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keys.route(event) || super.dispatchKeyEvent(event)

    /** Meta+/ (the system's list of keyboard shortcuts) shows the same table. */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        data.addAll(shortcuts.keyboardShortcutGroups(resources))
    }
}

/** What this window shows, kept when something recreates its activity. */
class WindowState(application: Application) : AndroidViewModel(application) {
    val mail = MailUiState(application.deskMail.settings)
    val settings = SettingsUiState()
}
