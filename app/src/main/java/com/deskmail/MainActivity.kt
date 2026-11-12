package com.deskmail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.deskmail.demo.DemoMail
import com.deskmail.ui.DeskMailApp
import desktopkit.shell.DeskWindows

/**
 * One window of the app. There can be several (DeX, a tablet's split screen): each is an activity
 * of its own with its own shell, opened at the route the other window asked for.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeskMailApp(mail = DemoMail.shared, startRoute = DeskWindows.startRoute(intent))
        }
    }
}
