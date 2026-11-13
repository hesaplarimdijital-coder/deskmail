package com.deskmail.core.text

/** Where in a message a piece of what was typed is looked for. */
enum class SearchField {
    /** Anywhere: the subject, the people on it, its text. */
    Any,

    /** Who it is from. */
    From,

    /** Whom it went to, openly ("To" and "Cc"). */
    To,

    /** Its subject. */
    Subject,
}

/**
 * One piece of what was typed: [typed] as it was typed (what a server is asked for), [words] as
 * the device's index holds it (folded and cut into words), and where to look ([field]).
 */
data class SearchTerm(val field: SearchField, val typed: String, val words: List<String>)

/**
 * What is typed into the search field, as the search index is asked for it.
 *
 * The index holds folded text ([TurkishFold]) cut into words wherever something that is neither a
 * letter nor a digit stands (SQLite's unicode61 tokenizer). What is typed is folded and cut the
 * same way, so "Şarkı" is looked for as "sarki" and "ayse@posta.example" as three words. Every
 * word must be in a message, each as the beginning of one of the message's words: "fat kas" finds
 * "Kasım faturası".
 *
 * A piece can be told where to be looked for: "kimden:ayşe" (or "from:"), "kime:elif" ("to:"),
 * "konu:fatura" ("subject:"); a space after the colon is allowed. Those words are the app's own:
 * they choose a column of the index, and are never passed on.
 *
 * Nothing else of what is typed reaches the index as syntax: only letters and digits are passed
 * on, in lower case, so neither a quote nor "OR" nor a minus sign means anything.
 */
object SearchQuery {

    /** The pieces of what was typed, each with where it is looked for; nothing for a piece without a letter or digit. */
    fun terms(text: String): List<SearchTerm> {
        val terms = ArrayList<SearchTerm>()
        var waiting: SearchField? = null
        for (token in tokens(text)) {
            val folded = TurkishFold.fold(token)
            val prefix = PREFIXES.entries.firstOrNull { folded.startsWith(it.key) }
            val (field, rest) = if (prefix != null) {
                // the fold keeps a character for every character: the rest is cut where the prefix ends
                prefix.value to token.substring(prefix.key.length)
            } else {
                (waiting ?: SearchField.Any) to token
            }
            waiting = null
            val words = cut(rest).toList()
            if (words.isEmpty()) {
                // "kimden: ayşe": the next piece is what the prefix is about
                if (prefix != null) waiting = field
                continue
            }
            terms += SearchTerm(field, rest.trim(*QUOTES), words)
        }
        return terms
    }

    /** What was typed, cut where a space stands; what stands in quotes stays together, quotes and all. */
    private fun tokens(text: String): List<String> {
        val tokens = ArrayList<String>()
        val token = StringBuilder()
        var quoted = false
        for (ch in text) {
            when {
                ch in QUOTES -> {
                    quoted = !quoted
                    token.append(ch)
                }
                ch.isWhitespace() && !quoted -> {
                    if (token.isNotEmpty()) tokens += token.toString()
                    token.setLength(0)
                }
                else -> token.append(ch)
            }
        }
        if (token.isNotEmpty()) tokens += token.toString()
        return tokens
    }

    /** The words to look for, in the order they were typed, each once. */
    fun words(text: String): List<String> = terms(text).flatMap { it.words }.distinct().take(MAX_WORDS)

    /**
     * The index's query (FTS4 MATCH) for what was typed; null when there is nothing in it to look
     * for: no letter or digit, or a single letter, which nearly every message has.
     */
    fun match(text: String): String? {
        val wanted = indexed(terms(text))
        if (wanted.isEmpty() || (wanted.size == 1 && wanted[0].second.length < MIN_ONLY_WORD)) return null
        return wanted.joinToString(" ") { (column, word) -> if (column == null) "$word*" else "$column:$word*" }
    }

    /**
     * What was typed in one form for every way of typing it: the name the server's findings for it
     * are kept under. Null when there is nothing to look for ([match]).
     */
    fun key(text: String): String? {
        if (match(text) == null) return null
        return terms(text).joinToString(" ") { term -> term.field.name.lowercase() + ":" + term.words.joinToString(".") }
    }

    /**
     * Whether [words] (as [words] gives them) are all in [text], each as the beginning of one of
     * its words: what the index would answer, for text that is not in it.
     */
    fun matches(words: List<String>, text: String): Boolean {
        if (words.isEmpty()) return false
        val there = cut(text).toList()
        return words.all { wanted -> there.any { it.startsWith(wanted) } }
    }

    /** Every word to look for, each once, with the column of the index it is looked for in (null: any). */
    private fun indexed(terms: List<SearchTerm>): List<Pair<String?, String>> =
        terms.flatMap { term -> term.words.map { COLUMNS[term.field] to it } }.distinct().take(MAX_WORDS)

    /** [text] folded and cut into words as the index cuts it. */
    private fun cut(text: String): Sequence<String> = sequence {
        val word = StringBuilder()
        for (ch in TurkishFold.fold(text)) {
            if (Character.isLetterOrDigit(ch)) {
                word.append(ch)
            } else if (word.isNotEmpty()) {
                yield(word.toString())
                word.setLength(0)
            }
        }
        if (word.isNotEmpty()) yield(word.toString())
    }

    private val QUOTES = charArrayOf('"', '\'', '“', '”', '«', '»')

    /** What tells a piece where it is looked for, as it is after folding. */
    private val PREFIXES = linkedMapOf(
        "kimden:" to SearchField.From,
        "from:" to SearchField.From,
        "kime:" to SearchField.To,
        "to:" to SearchField.To,
        "konu:" to SearchField.Subject,
        "subject:" to SearchField.Subject,
    )

    /** The index's column for each field; "people" holds everybody on a message, the sender among them. */
    private val COLUMNS = mapOf(
        SearchField.Any to null,
        SearchField.From to "people",
        SearchField.To to "people",
        SearchField.Subject to "subject",
    )

    /** More words than this are not looked for: a pasted paragraph is not a query. */
    private const val MAX_WORDS = 8

    /** A word that stands alone is looked for from this length on. */
    private const val MIN_ONLY_WORD = 2
}
