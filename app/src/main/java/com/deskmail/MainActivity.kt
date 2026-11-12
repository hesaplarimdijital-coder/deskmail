package com.deskmail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.res.stringResource
import desktopkit.DesktopKitRoot
import desktopkit.components.DeskIcons
import desktopkit.screens.MessageScreen
import desktopkit.settings.rememberDeskPreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = rememberDeskPreferences()
            DesktopKitRoot(prefs) {
                // the shell and the screens come with the next steps; this proves the kit resolves and draws
                MessageScreen(
                    title = stringResource(R.string.app_name),
                    icon = DeskIcons.Info,
                    description = stringResource(R.string.skeleton_description),
                )
            }
        }
    }
}
