package com.deskmail

import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.deskmail.demo.DemoMail
import com.deskmail.ui.CommandHost
import com.deskmail.ui.DeskMailApp
import com.deskmail.ui.KeyRouter
import com.deskmail.ui.isTyping
import com.deskmail.ui.mail.MailUiState
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
                mail = DemoMail.shared,
                settings = deskMail.settings,
                ui = state.mail,
                startRoute = DeskWindows.startRoute(intent),
                palette = palette,
                commands = commands,
            )
        }
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
}
