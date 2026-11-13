package com.deskmail.ui.compose

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.deskmail.core.compose.MailtoLink
import com.deskmail.mail.DraftSeed
import java.io.InputStream

/** A file another app hands over to be sent along: its name, its kind, and how it is read. */
class SharedFile(val name: String, val mimeType: String, val open: () -> InputStream?)

/**
 * What another app asks to be written: a `mailto:` link that was tapped, or something shared with
 * the app (a text, files). A window begins a new message with it; nothing is sent until the user
 * says so there.
 *
 * Only what the form shows is taken: recipients, subject, text, files. Files are taken from other
 * apps' content providers only: a path into the file system, or into this app's own files, is not
 * something another app may have attached.
 */
class ComposeRequest(val seed: DraftSeed, val files: List<SharedFile> = emptyList()) {

    /** Whether there is more to it than who the message goes to: a message closed untouched is worth keeping then. */
    val hasContent: Boolean get() = files.isNotEmpty() || seed.subject.isNotBlank() || seed.body.isNotBlank()

    companion object {
        /** What [intent] asks for, or null when it does not ask for a message to be written. */
        fun from(context: Context, intent: Intent?): ComposeRequest? {
            if (intent == null) return null
            // a window brought back from the list of recent apps: what was asked for was done then
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            return when (intent.action) {
                Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                    val link = intent.dataString?.let { MailtoLink.parse(it) } ?: return null
                    ComposeRequest(seed(link, intent))
                }
                Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE ->
                    ComposeRequest(seed(MailtoLink(), intent), streams(intent).filter { allowed(context, it) }.map { shared(context, it) })
                else -> null
            }
        }

        /** What the link says, and what the extras add: some apps name the subject and the text there. */
        private fun seed(link: MailtoLink, intent: Intent): DraftSeed = DraftSeed(
            to = joined(link.to, intent.getStringArrayExtra(Intent.EXTRA_EMAIL)),
            cc = joined(link.cc, intent.getStringArrayExtra(Intent.EXTRA_CC)),
            bcc = joined(link.bcc, intent.getStringArrayExtra(Intent.EXTRA_BCC)),
            subject = link.subject.ifBlank { oneLine(intent.getStringExtra(Intent.EXTRA_SUBJECT)) },
            body = link.body.ifBlank { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty() },
        )

        private fun joined(first: String, more: Array<String>?): String =
            (listOf(first) + more.orEmpty().map { oneLine(it) }).filter { it.isNotBlank() }.joinToString(", ")

        private fun oneLine(text: String?): String = text.orEmpty().map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()

        /** The files an intent hands over: one, several, or only in its clip. */
        private fun streams(intent: Intent): List<Uri> = buildList {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { addAll(it.filterNotNull()) }
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { add(it) }
            }
            val clip = intent.clipData
            if (clip != null) for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let { add(it) }
        }.distinct()

        private fun allowed(context: Context, uri: Uri): Boolean =
            uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority?.startsWith(context.packageName) != true

        private fun shared(context: Context, uri: Uri): SharedFile {
            val (name, type) = describe(context, uri)
            return SharedFile(name, type) { context.contentResolver.openInputStream(uri) }
        }
    }
}
