package com.deskmail.core.compose

/**
 * Where a kept text ("quick text") goes into a message: at the end of what the user wrote, before
 * what the app put under it (the signature, the quoted message of a reply, the forwarded one).
 * The text field gives no place of the cursor, so the place is worked out from the text alone.
 */
object Templates {

    /** [body] with [text] put in at the end of what the user wrote in it. */
    fun insert(body: String, text: String): String {
        val added = text.replace("\r\n", "\n").trim('\n', ' ')
        if (added.isEmpty()) return body
        val end = ownEnd(body)
        val own = body.substring(0, end).trimEnd()
        val rest = body.substring(end).trimStart('\n')
        val written = if (own.isEmpty()) added else own + "\n\n" + added
        return if (rest.isEmpty()) written else written + "\n\n" + rest
    }

    /**
     * Where what the user wrote ends: before the signature ("-- " on a line of its own), the
     * quoted message (a line followed by one that begins with ">"), or the forwarded one (its
     * banner of dashes), whichever comes first; the end of [body] when there is none.
     */
    fun ownEnd(body: String): Int {
        val text = "\n" + body
        val starts = listOfNotNull(
            SIGNATURE.find(text)?.range?.first,
            QUOTE.find(text)?.range?.first,
            BANNER.find(text)?.range?.first,
        )
        // each mark is found with the line break before it, in the body with a line break put in
        // front: where that line break stands there, the mark itself begins in [body]
        return starts.minOrNull()?.coerceAtMost(body.length) ?: body.length
    }

    private val SIGNATURE = Regex("\n-- \n")
    private val QUOTE = Regex("\n[^\n]+\n>")
    private val BANNER = Regex("\n-{5,}[^\n]*-{5,}\n")
}
