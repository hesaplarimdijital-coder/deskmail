package com.deskmail.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.deskmail.deskMail
import kotlinx.coroutines.launch

/**
 * The device was switched on, or the app was updated: whatever looks for mail in the background
 * is set up again, as the settings say. Without this the watch would begin only when the app is
 * next opened.
 */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.deskMail
        app.log.info("watch", if (intent.action == Intent.ACTION_BOOT_COMPLETED) "the device was switched on" else "the app was updated")
        // the system lets a foreground service be started while this broadcast is being answered
        val answering: PendingResult? = goAsync()
        app.background.launch {
            try {
                app.arrangeBackground(restart = false)
            } finally {
                answering?.finish()
            }
        }
    }
}
