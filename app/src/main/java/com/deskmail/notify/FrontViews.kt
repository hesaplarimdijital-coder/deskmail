package com.deskmail.notify

import com.deskmail.mail.MailView
import java.util.concurrent.ConcurrentHashMap

/**
 * What the app's windows have in front of the user right now: each window that is in front says
 * which list of mail it shows, and takes that back when it leaves the front. New mail that
 * arrives in a list the user is looking at needs no notification: the list shows it.
 */
class FrontViews {
    private val shown = ConcurrentHashMap<Any, MailView>()

    /** [window] shows [view] in front; null when it shows no list of mail, or is not in front any more. */
    fun show(window: Any, view: MailView?) {
        if (view == null) shown.remove(window) else shown[window] = view
    }

    /** Whether a window in front lists the mail of the inbox [folderId]. */
    fun shows(folderId: Long): Boolean = shown.values.any { view ->
        view == MailView.Inbox || view == MailView.Unread || (view is MailView.Folder && view.id == folderId)
    }
}
