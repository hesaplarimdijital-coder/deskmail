package com.deskmail.core.text

/**
 * What is typed into the search field, as the search index is asked for it.
 *
 * The index holds folded text ([TurkishFold]) cut into words wherever something that is neither a
 * letter nor a digit stands (SQLite's unicode61 tokenizer). What is typed is folded and cut the
 * same way, so "Şarkı" is looked for as "sarki" and "ayse@posta.example" as three words. Every
 * word must be in a message, each as the beginning of one of the message's words: "fat kas" finds
 * "Kasım faturası".
 *
 * Nothing of what is typed reaches the index as syntax: only letters and digits are passed on,
 * in lower case, so neither a quote nor "OR" nor a minus sign means anything.
 */
object SearchQuery {

    /** The words to look for, in the order they were typed, each once. */
    fun words(text: String): List<String> = cut(text).distinct().take(MAX_WORDS).toList()

    /**
     * The index's query (FTS4 MATCH) for what was typed; null when there is nothing in it to look
     * for: no letter or digit, or a single letter, which nearly every message has.
     */
    fun match(text: String): String? {
        val words = words(text)
        if (words.isEmpty() || (words.size == 1 && words[0].length < MIN_ONLY_WORD)) return null
        return words.joinToString(" ") { "$it*" }
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

    /** More words than this are not looked for: a pasted paragraph is not a query. */
    private const val MAX_WORDS = 8

    /** A word that stands alone is looked for from this length on. */
    private const val MIN_ONLY_WORD = 2
}
