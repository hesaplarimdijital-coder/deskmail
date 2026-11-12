package com.deskmail.core.html

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * A message's HTML made safe to keep and to show.
 *
 * @param html the cleaned HTML: the message's style sheets, then its body, without a document around it.
 * @param remoteImages whether it asks for pictures (or anything else) from the network. The reader
 *   loads none of it unless the user says so; this is what the "images were not loaded" strip is shown for.
 */
data class CleanHtml(val html: String, val remoteImages: Boolean)

/**
 * Cleans the HTML of a message before it is stored.
 *
 * The reader's WebView runs no scripts, loads nothing from the network and follows no link by
 * itself; this is the second lock on the same door, and it keeps what is stored free of what will
 * never be shown. Taken out:
 * - everything that runs or embeds: scripts, frames, objects, forms and their fields, SVG and MathML,
 *   audio and video;
 * - what steers the page from outside: `<base>`, `<meta>`, `<link>`;
 * - every event handler, and every address that is not a web address, a mail or phone link, a
 *   picture inside the message (`cid:`) or a picture written into it (`data:image/…`).
 *
 * The message's own styles stay (mail is laid out with them), without `@import`.
 */
object HtmlSanitizer {

    fun clean(html: String): CleanHtml {
        val document = Jsoup.parse(html)
        document.outputSettings().prettyPrint(false)
        document.select(REMOVED).remove()
        // a form's words stay, the form does not
        document.select("form").unwrap()

        var remote = false
        for (element in document.allElements) {
            val drop = ArrayList<String>()
            for (attribute in element.attributes()) {
                val name = attribute.key.lowercase()
                val value = attribute.value
                when {
                    name.startsWith("on") -> drop += attribute.key
                    name == "srcset" || name == "ping" || name == "formaction" -> drop += attribute.key
                    name in ADDRESSES -> {
                        if (!allowed(name, value, element)) drop += attribute.key else if (isRemote(value) && name != "href") remote = true
                    }
                    name == "style" -> if (STYLE_URL.containsMatchIn(value)) remote = true
                }
            }
            drop.forEach { element.removeAttr(it) }
        }
        val styles = StringBuilder()
        for (style in document.select("style")) {
            val css = IMPORT.replace(style.data(), "")
            if (STYLE_URL.containsMatchIn(css)) remote = true
            if (css.isNotBlank()) styles.append("<style>").append(css.replace("</", "<\\/")).append("</style>")
        }
        document.select("style").remove()

        val body = document.body()
        val wrapper = bodyAttributes(body)
        val content = body.html()
        val out = if (wrapper.isEmpty()) content else "<div$wrapper>$content</div>"
        return CleanHtml(styles.toString() + out, remote)
    }

    /** What the message set on its `<body>` (its colours, its margins), carried over to a `<div>` around the content. */
    private fun bodyAttributes(body: Element): String {
        val out = StringBuilder()
        val style = StringBuilder(body.attr("style").trim().trimEnd(';'))
        body.attr("bgcolor").takeIf { it.isNotBlank() }?.let { style.append(if (style.isEmpty()) "" else ";").append("background-color:").append(it) }
        body.attr("text").takeIf { it.isNotBlank() }?.let { style.append(if (style.isEmpty()) "" else ";").append("color:").append(it) }
        if (style.isNotEmpty()) out.append(" style=\"").append(escape(style.toString())).append('"')
        body.attr("dir").takeIf { it.isNotBlank() }?.let { out.append(" dir=\"").append(escape(it)).append('"') }
        body.attr("class").takeIf { it.isNotBlank() }?.let { out.append(" class=\"").append(escape(it)).append('"') }
        return out.toString()
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    /** Whether [value] may stay as the [name] of [element]. */
    private fun allowed(name: String, value: String, element: Element): Boolean {
        val address = value.trim()
        // an address without a scheme points nowhere outside a web page, and does no harm
        val scheme = SCHEME.find(address)?.groupValues?.get(1)?.lowercase() ?: return true
        return when (scheme) {
            "http", "https" -> true
            "mailto", "tel" -> name == "href"
            "cid" -> name == "src" || name == "background"
            "data" -> name == "src" && element.normalName() == "img" && DATA_IMAGE.containsMatchIn(address)
            else -> false
        }
    }

    private fun isRemote(value: String): Boolean = REMOTE.containsMatchIn(value.trim())

    /**
     * The HTML as the text a person would read off it: for the list's preview, the search and the
     * plain-text view. Paragraphs, headings, lists and tables stand apart by an empty line; a table's
     * row is a line, a list's item a line with a bullet.
     */
    fun text(html: String): String {
        val document = Jsoup.parse(html)
        document.select("script, style, head, title").remove()
        val writer = TextWriter()
        NodeTraversor.traverse(writer, document.body())
        return writer.result()
    }

    private class TextWriter : NodeVisitor {
        private val out = StringBuilder()

        /** Line breaks owed before the next words. */
        private var breaks = 0
        private var bullet = false

        override fun head(node: Node, depth: Int) {
            when (node) {
                is TextNode -> words(node.wholeText)
                is Element -> when {
                    node.normalName() == "br" -> breaks = (breaks + 1).coerceAtMost(2)
                    node.normalName() == "li" -> {
                        owe(1)
                        bullet = true
                    }
                    // a table's cells stand side by side on their row's line
                    node.normalName() == "td" || node.normalName() == "th" -> Unit
                    node.normalName() in PARAGRAPHS -> owe(2)
                    node.isBlock || node.normalName() == "tr" -> owe(1)
                }
            }
        }

        override fun tail(node: Node, depth: Int) {
            if (node !is Element) return
            when {
                node.normalName() == "td" || node.normalName() == "th" -> if (breaks == 0 && out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                node.normalName() in PARAGRAPHS -> owe(2)
                node.isBlock || node.normalName() == "tr" -> owe(1)
            }
        }

        private fun owe(count: Int) {
            if (breaks < count) breaks = count
        }

        private fun words(text: String) {
            // HTML reads any run of spaces and line ends as one space
            var piece = text.replace(SPACES, " ")
            if (piece.isBlank()) {
                if (breaks == 0 && out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                return
            }
            if (breaks > 0 || out.isEmpty()) piece = piece.trimStart()
            if (breaks > 0 && out.isNotEmpty()) {
                while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
                repeat(breaks) { out.append('\n') }
            }
            breaks = 0
            if (bullet) {
                out.append("• ")
                bullet = false
            }
            out.append(piece)
        }

        fun result(): String = out.toString().trim()
    }

    /** Elements after which a reader expects an empty line. */
    private val PARAGRAPHS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "table", "blockquote", "pre", "hr")

    /** Elements that go, with all they hold. */
    private const val REMOVED =
        "script, noscript, iframe, frame, frameset, object, embed, applet, base, meta, link, title, " +
            "input, button, select, textarea, option, svg, math, audio, video, source, track, canvas, template, slot, portal"

    /** Attributes that hold an address. */
    private val ADDRESSES = setOf("href", "src", "background", "action", "poster", "data", "cite", "longdesc", "usemap", "xlink:href", "lowsrc", "dynsrc")

    private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*):""")
    private val REMOTE = Regex("""(?i)^(https?:)?//""")
    private val DATA_IMAGE = Regex("""(?i)^data:image/(png|jpe?g|gif|webp|bmp);""")
    private val STYLE_URL = Regex("""(?i)url\(\s*['"]?\s*(https?:)?//""")
    private val IMPORT = Regex("""(?i)@import[^;]*;?""")
    private val SPACES = Regex("""[ \t\r\n\u00A0]+""")
}
