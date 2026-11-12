package com.deskmail.ui.mail

/**
 * The page the reader's WebView is given for a message: the message's HTML (or its plain text,
 * escaped) inside a document of the app's own, with a policy that lets nothing in from the network
 * and styles that keep wide content from pushing the page sideways. Plain text gets lines of a
 * readable length in a wide reader; an HTML message is laid out as its sender made it.
 *
 * The page is always written as a light one (dark text). On the dark themes the WebView darkens
 * it itself (algorithmic darkening), which also turns a sender's white boxes dark.
 */
internal object MessageDocument {

    /**
     * @param html the message's HTML part, or null when it has none; [text] is shown then.
     * @param remoteImages whether images may be fetched from the network. Scripts, frames, fonts and
     *   everything else stay blocked either way.
     */
    fun build(html: String?, text: String, remoteImages: Boolean = false): String {
        val images = if (remoteImages) "data: cid: https:" else "data: cid:"
        val body = html ?: ("<div class=\"plain\">" + escape(text) + "</div>")
        return buildString(body.length + 1024) {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src ")
            append(images)
            append("\">")
            append("<style>")
            append(STYLE)
            append("</style></head><body>")
            append(body)
            append("</body></html>")
        }
    }

    /** [text] with the characters HTML reads as markup written out. */
    fun escape(text: String): String = buildString(text.length + 16) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    private const val STYLE =
        "html,body{margin:0;padding:0;background:transparent}" +
            "body{padding:4px 16px 24px;color:#1F2328;font:16px/1.55 sans-serif;overflow-wrap:anywhere;-webkit-text-size-adjust:100%}" +
            "img,video{max-width:100%;height:auto}" +
            "table{max-width:100%}" +
            "pre{overflow-x:auto}" +
            "blockquote{margin:8px 0 8px 2px;padding-left:12px;border-left:3px solid #C9CED6;color:#57606A}" +
            "a{color:#1A5FD1}" +
            ".plain{white-space:pre-wrap;max-width:${PLAIN_TEXT_EMS}em}"
}
