package com.deskmail.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Opens a page in the browser, as a tab over the app's own window where the browser offers that
 * (Custom Tabs): the user stays with the app, and is back in it when the page is done. A browser
 * without such tabs opens the page as it would any link.
 */
object BrowserTab {

    /**
     * What a browser looks for to open a Custom Tab. It is there with no session: the app has
     * nothing to say to the browser beyond the page, and needs no library to say that.
     */
    private const val EXTRA_SESSION = "android.support.customtabs.extra.SESSION"

    /** Opens [url]; false when no app on the device can show it. */
    fun open(context: Context, url: String): Boolean {
        val activity = context.activity()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .putExtras(Bundle().apply { putBinder(EXTRA_SESSION, null) })
        // a tab is opened over the window it was opened from; without one it needs a task of its own
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            (activity ?: context).startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
