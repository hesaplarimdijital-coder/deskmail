package com.deskmail.core.compose

/**
 * How a kept text ("quick text") goes into a message: where the cursor is, in place of what is
 * selected (desktop-kit 2.8.0 tells the app where the cursor is; docs/KIT-ISTEKLERI.md, 23). And
 * where what the user wrote ends, before what the app put under it: what "Keep as a template" keeps.
 */
object Templates {

    /**
     * [body] with [text] put in at the cursor, in place of what is selected from [start] to [end],
     * and where the cursor stands after it. Put in at the cursor, the text is a paragraph of its
     * own: a blank line stands between it and what is written before and after it. In place of a
     * selection it is put in as it is.
     */
    fun insertAt(body: String, start: Int, end: Int, text: String): Pair<String, Int> {
        val added = text.replace("\r\n", "\n").trim('\n', ' ')
        val from = minOf(start, end).coerceIn(0, body.length)
        val to = maxOf(start, end).coerceIn(from, body.length)
        if (added.isEmpty()) return body to to
        val head = body.substring(0, from)
        val tail = body.substring(to)
        val before = if (from != to || head.isEmpty()) "" else "\n".repeat(PARAGRAPH - head.takeLastWhile { it == '\n' }.length.coerceAtMost(PARAGRAPH))
        val after = if (from != to || tail.isEmpty()) "" else "\n".repeat(PARAGRAPH - tail.takeWhile { it == '\n' }.length.coerceAtMost(PARAGRAPH))
        return head + before + added + after + tail to from + before.length + added.length
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

    /** The line breaks between two paragraphs: a blank line. */
    private const val PARAGRAPH = 2

    private val SIGNATURE = Regex("\n-- \n")
    private val QUOTE = Regex("\n[^\n]+\n>")
    private val BANNER = Regex("\n-{5,}[^\n]*-{5,}\n")
}
