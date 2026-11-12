package com.deskmail.ui.mail

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.deskmail.R
import com.deskmail.core.mail.MailException
import com.deskmail.mail.FetchedFile
import com.deskmail.mail.MailAttachment
import com.deskmail.mail.MailSource
import desktopkit.appResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What can be done with a file a message carries. */
enum class AttachmentAction { Open, Save, Share }

/**
 * Opens, saves and shares the files of messages. A file is fetched from the server the first
 * time it is asked for and kept on the device for a while; [busy] says that one is on its way.
 * Opening hands the file to the app the system picks for its kind, with leave to read that one
 * file; saving asks the system where to (the Storage Access Framework), so the app needs no
 * permission to the device's files.
 */
@Stable
class AttachmentHandler internal constructor(
    private val mail: MailSource,
    private val scope: CoroutineScope,
    private val context: () -> Context,
    private val resources: () -> Resources,
    private val notify: (String) -> Unit,
) {
    /** Asks the system where a file is to be saved; the window that shows the handler's files sets it. */
    internal var askWhereToSave: (Intent) -> Unit = {}

    private var fetching by mutableIntStateOf(0)

    /** A file is being fetched. */
    val busy: Boolean get() = fetching > 0

    /** The file that is waiting for the user to say where it is to be saved. */
    private var saving: File? = null

    fun handle(attachment: MailAttachment, action: AttachmentAction) {
        scope.launch {
            val file = fetch(attachment) ?: return@launch
            val type = mimeType(attachment)
            when (action) {
                AttachmentAction.Open -> open(file, type)
                AttachmentAction.Share -> share(file, type, attachment.name)
                AttachmentAction.Save -> {
                    saving = file
                    val where = Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(type)
                        .putExtra(Intent.EXTRA_TITLE, file.name)
                    try {
                        askWhereToSave(where)
                    } catch (e: ActivityNotFoundException) {
                        saving = null
                        notify(resources().getString(R.string.attachment_save_failed))
                    }
                }
            }
        }
    }

    /** The user said where to save (or did not: [target] is null). */
    internal fun saveTo(target: Uri?) {
        val file = saving ?: return
        saving = null
        if (target == null) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val out = context().contentResolver.openOutputStream(target) ?: error("no stream for the chosen place")
                    out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
                }.isSuccess
            }
            notify(if (saved) resources().getString(R.string.attachment_saved, file.name) else resources().getString(R.string.attachment_save_failed))
        }
    }

    /** The file on the device, fetched if it has to be; null (and a word to the user) when it cannot be had. */
    private suspend fun fetch(attachment: MailAttachment): File? {
        fetching++
        val fetched = try {
            mail.fetch(attachment)
        } finally {
            fetching--
        }
        val words = resources()
        return when (fetched) {
            is FetchedFile.Ready -> fetched.file
            is FetchedFile.Failed -> {
                val network = fetched.kind == MailException.Kind.Network
                notify(words.getString(if (network) R.string.attachment_failed_network else R.string.attachment_failed))
                null
            }
            FetchedFile.Gone -> {
                notify(words.getString(R.string.attachment_gone))
                null
            }
        }
    }

    private fun open(file: File, type: String) {
        val uri = uriFor(file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(view)
    }

    private fun share(file: File, type: String, title: String) {
        val uri = uriFor(file)
        val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // the grant travels with the clip data; Android no longer adds it by itself
        send.clipData = ClipData.newRawUri(title, uri)
        start(Intent.createChooser(send, title))
    }

    private fun start(intent: Intent) {
        val from = context()
        // from anything but an activity, a new task has to be asked for
        if (from !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            from.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            notify(resources().getString(R.string.attachment_no_app))
        }
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(context(), context().packageName + ".files", file)

    companion object {
        /**
         * What kind of file it is: what its message says, or, where that says nothing
         * ("application/octet-stream"), what the name's ending suggests.
         */
        fun mimeType(attachment: MailAttachment): String {
            val said = attachment.mimeType.lowercase()
            if (said.isNotBlank() && said != UNKNOWN) return said
            val ending = attachment.name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ending) ?: UNKNOWN
        }

        private const val UNKNOWN = "application/octet-stream"
    }
}

/**
 * The window's [AttachmentHandler].
 *
 * @param onNotify shows a short notice: what could not be fetched, what was saved.
 */
@Composable
fun rememberAttachmentHandler(mail: MailSource, onNotify: (String) -> Unit): AttachmentHandler {
    val scope = rememberCoroutineScope()
    val latestNotify by rememberUpdatedState(onNotify)
    // the context starts activities; the words come from the app's resources, in the kit's language
    val latestContext by rememberUpdatedState(LocalContext.current)
    val latestResources by rememberUpdatedState(appResources())
    val handler = remember(mail) { AttachmentHandler(mail, scope, { latestContext }, { latestResources }, { latestNotify(it) }) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handler.saveTo(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
    }
    SideEffect { handler.askWhereToSave = { where -> launcher.launch(where) } }
    return handler
}
