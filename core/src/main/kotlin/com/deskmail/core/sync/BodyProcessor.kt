package com.deskmail.core.sync

import com.deskmail.core.html.HtmlSanitizer
import com.deskmail.core.mail.RemoteBody

/** Turns a message's parts, as they came from the server, into what is kept of them. */
object BodyProcessor {

    /** How long a list's preview of a message is. */
    const val PREVIEW_LENGTH = 200

    /**
     * The message's content ready to keep: its HTML cleaned, its plain text (read off the HTML where
     * the mail brought none), and the first words for the list. Null when the server kept back all of
     * its text for being too large and it has no files either.
     */
    fun process(body: RemoteBody): StoredBody? {
        val clean = body.html?.let { HtmlSanitizer.clean(it) }
        val own = body.text?.trim().orEmpty()
        val text = own.ifEmpty { body.html?.let { HtmlSanitizer.text(it) }.orEmpty() }
        if (body.tooLarge && text.isEmpty() && clean == null && body.attachments.isEmpty()) return null
        return StoredBody(
            text = text,
            html = clean?.html,
            remoteImages = clean?.remoteImages ?: false,
            preview = preview(text),
            attachments = body.attachments,
            headers = body.headers,
        )
    }

    /** The first words of [text] on one line. */
    fun preview(text: String): String {
        val out = StringBuilder(PREVIEW_LENGTH)
        var space = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                space = out.isNotEmpty()
            } else {
                if (space) out.append(' ')
                space = false
                out.append(ch)
            }
            if (out.length >= PREVIEW_LENGTH) break
        }
        return out.toString()
    }
}
