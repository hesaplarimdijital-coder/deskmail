package com.deskmail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.deskmail.demo.DemoMail
import com.deskmail.ui.DeskMailApp
import com.deskmail.ui.mail.MailUiState
import desktopkit.shell.DeskWindows

/**
 * One window of the app. There can be several (DeX, a tablet's split screen): each is an activity
 * of its own with its own shell, opened at the route the other window asked for.
 */
class MainActivity : ComponentActivity() {

    private val state: WindowState by lazy { ViewModelProvider(this)[WindowState::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeskMailApp(mail = DemoMail.shared, ui = state.mail, startRoute = DeskWindows.startRoute(intent))
        }
    }
}

/** What this window shows, kept when something recreates its activity. */
class WindowState : ViewModel() {
    val mail = MailUiState()
}
