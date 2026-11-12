package com.deskmail

import android.app.Application
import android.content.Context
import com.deskmail.settings.AppSettings

/**
 * The process: what every window of the app shares. Nothing is created until it is first asked
 * for, so starting the process stays cheap.
 */
class DeskMailApplication : Application() {

    val settings: AppSettings by lazy { AppSettings(getSharedPreferences(AppSettings.FILE, Context.MODE_PRIVATE)) }
}

/** The process this context belongs to. */
val Context.deskMail: DeskMailApplication get() = applicationContext as DeskMailApplication
