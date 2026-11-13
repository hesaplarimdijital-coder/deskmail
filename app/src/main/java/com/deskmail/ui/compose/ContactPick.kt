package com.deskmail.ui.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import com.deskmail.core.mail.AddressList
import com.deskmail.core.mail.MailAddress

/**
 * Choosing recipients from the device's contacts, without leave to read them (no READ_CONTACTS):
 * the system's own picker hands over what was chosen, and only that.
 *
 * Android 17 has a picker of its own for this ([ANDROID_17]: several people at once, their email
 * addresses alone); before that the contacts app's picker gives one address at a time.
 */
object ContactPick {

    /** What asks the system for addresses. */
    fun intent(sdk: Int = Build.VERSION.SDK_INT): Intent =
        if (sdk >= ANDROID_17) {
            Intent(ACTION_PICK_CONTACTS).apply {
                putStringArrayListExtra(EXTRA_REQUESTED_FIELDS, arrayListOf(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                putExtra(EXTRA_SELECTION_LIMIT, SELECTION_LIMIT)
            }
        } else {
            Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Email.CONTENT_TYPE)
        }

    /** The addresses behind [uri], what the picker answered with; read once, while the system lets it be read. */
    fun read(context: Context, uri: Uri): List<MailAddress> {
        val rows = ArrayList<Row>()
        context.contentResolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            val name = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val type = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
            val data = cursor.getColumnIndex(ContactsContract.Data.DATA1)
            while (cursor.moveToNext()) {
                rows += Row(
                    name = if (name >= 0) cursor.getString(name).orEmpty() else "",
                    mimeType = if (type >= 0) cursor.getString(type) else null,
                    data = if (data >= 0) cursor.getString(data).orEmpty() else "",
                )
            }
        }
        return addresses(rows)
    }

    /** One row of what the picker answered: a person's name, what kind of data it is (null where the picker does not say), the data. */
    data class Row(val name: String, val mimeType: String?, val data: String)

    /** The email addresses among [rows], each once. */
    fun addresses(rows: List<Row>): List<MailAddress> =
        rows.asSequence()
            .filter { it.mimeType == null || it.mimeType == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE }
            .map { MailAddress(it.name.trim(), it.data.trim()) }
            .filter { AddressList.isAddress(it.address) }
            .distinctBy { it.address.lowercase() }
            .toList()

    /** [typed] with [picked] added at its end: one comma between them, nobody twice. */
    fun append(typed: String, picked: List<MailAddress>): String {
        val there = AddressList.parse(typed).addresses.mapTo(HashSet()) { it.address.lowercase() }
        val adding = picked.filter { it.address.lowercase() !in there }
        if (adding.isEmpty()) return typed
        val head = typed.trimEnd().trimEnd(',', ';').trimEnd()
        val tail = AddressList.format(adding)
        return if (head.isEmpty()) tail else "$head, $tail"
    }

    /** Android 17, where the system's own contact picker arrived. */
    private const val ANDROID_17 = 37

    /** The picker's names (android.provider.ContactsPickerSessionContract, API 37), written out: the app is built for Android 16 as well. */
    private const val ACTION_PICK_CONTACTS = "android.provider.action.PICK_CONTACTS"
    private const val EXTRA_REQUESTED_FIELDS = "android.provider.extra.PICK_CONTACTS_REQUESTED_DATA_FIELDS"
    private const val EXTRA_SELECTION_LIMIT = "android.provider.extra.PICK_CONTACTS_SELECTION_LIMIT"
    private const val SELECTION_LIMIT = 20

    private val PROJECTION = arrayOf(
        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        ContactsContract.Data.MIMETYPE,
        ContactsContract.Data.DATA1,
    )
}
