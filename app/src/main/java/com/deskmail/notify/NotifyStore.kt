package com.deskmail.notify

import android.content.SharedPreferences
import java.util.UUID

/** How far an inbox's mail has been told of: the folder's stamp on the server and the highest UID seen under it. */
data class Watermark(val uidValidity: Long, val uid: Long)

/**
 * What telling of new mail has to remember, in a preferences file of its own: how far each
 * inbox's mail has been told of (a message is "new" once: when its UID is above its inbox's mark
 * the first time it is looked at), whether the user was asked to allow notifications, what was
 * scheduled, and the token the app's notifications are known by.
 */
class NotifyStore(private val prefs: SharedPreferences) {

    fun told(folderId: Long): Watermark? {
        val parts = prefs.getString(key(folderId), null)?.split(':') ?: return null
        val uidValidity = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val uid = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return Watermark(uidValidity, uid)
    }

    fun tell(folderId: Long, mark: Watermark) {
        prefs.edit().putString(key(folderId), "${mark.uidValidity}:${mark.uid}").apply()
    }

    fun forget(folderIds: Collection<Long>) {
        if (folderIds.isEmpty()) return
        val edit = prefs.edit()
        folderIds.forEach { edit.remove(key(it)) }
        edit.apply()
    }

    /** Whether the user was asked to allow notifications: the system asks twice at most, and the app once unasked. */
    var asked: Boolean
        get() = prefs.getBoolean(KEY_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED, value).apply()

    /** How often, in minutes, the background look for mail was last scheduled; 0 while nothing is scheduled. */
    var scheduledMinutes: Int
        get() = prefs.getInt(KEY_SCHEDULED, 0)
        set(value) = prefs.edit().putInt(KEY_SCHEDULED, value).apply()

    /**
     * What the app's own notifications carry to be known by: made up once for this installation.
     * A request to open a message that does not carry it did not come from one of them.
     */
    val token: String
        get() = prefs.getString(KEY_TOKEN, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_TOKEN, it).apply() }

    private fun key(folderId: Long) = "told-$folderId"

    companion object {
        /** The file the marks are kept in. */
        const val FILE = "notify"
        private const val KEY_ASKED = "asked"
        private const val KEY_SCHEDULED = "scheduled-minutes"
        private const val KEY_TOKEN = "token"
    }
}
