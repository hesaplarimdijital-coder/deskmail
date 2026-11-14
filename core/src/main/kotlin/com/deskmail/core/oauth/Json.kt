package com.deskmail.core.oauth

/**
 * Reads the JSON that token endpoints answer with (RFC 8259): objects, arrays, strings, numbers,
 * true, false and null. An object is a [Map] in the order of its members, an array a [List], a
 * number a [Double]. Small on purpose: what is read here are a few lines from one server, and
 * the app has no JSON library to bring in for them.
 */
internal object Json {

    /** The object that [text] is. @throws IllegalArgumentException when it is not one, or not JSON. */
    fun obj(text: String): Map<String, Any?> {
        val reader = Reader(text)
        val value = reader.value(0)
        reader.end()
        require(value is Map<*, *>) { "not a JSON object" }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private class Reader(private val text: String) {
        private var at = 0

        fun value(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "nested too deep" }
            skipSpace()
            require(at < text.length) { "ends where a value was expected" }
            return when (val c = text[at]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw IllegalArgumentException("unexpected '$c' at $at")
            }
        }

        /** Nothing but space may follow the value. */
        fun end() {
            skipSpace()
            require(at == text.length) { "more after the value, at $at" }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            at++
            val members = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') {
                at++
                return members
            }
            while (true) {
                skipSpace()
                require(peek() == '"') { "a name was expected at $at" }
                val name = string()
                skipSpace()
                expect(':')
                members[name] = value(depth + 1)
                skipSpace()
                when (next()) {
                    ',' -> continue
                    '}' -> return members
                    else -> throw IllegalArgumentException("',' or '}' was expected at ${at - 1}")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            at++
            val items = ArrayList<Any?>()
            skipSpace()
            if (peek() == ']') {
                at++
                return items
            }
            while (true) {
                items += value(depth + 1)
                skipSpace()
                when (next()) {
                    ',' -> continue
                    ']' -> return items
                    else -> throw IllegalArgumentException("',' or ']' was expected at ${at - 1}")
                }
            }
        }

        private fun string(): String {
            at++
            val out = StringBuilder()
            while (true) {
                require(at < text.length) { "a string is not closed" }
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(at < text.length) { "a string is not closed" }
                        when (val escaped = text[at++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(at + 4 <= text.length) { "a \\u escape is cut short" }
                                val code = text.substring(at, at + 4).toIntOrNull(16) ?: throw IllegalArgumentException("a \\u escape is no number at $at")
                                // a pair of surrogates comes as two escapes, and is put back together as it is appended
                                out.append(code.toChar())
                                at += 4
                            }
                            else -> throw IllegalArgumentException("unknown escape '\\$escaped' at ${at - 1}")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = at
            if (peek() == '-') at++
            while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
            return text.substring(start, at).toDoubleOrNull() ?: throw IllegalArgumentException("not a number at $start")
        }

        private fun word(word: String, value: Any?): Any? {
            require(text.startsWith(word, at)) { "unexpected text at $at" }
            at += word.length
            return value
        }

        private fun expect(c: Char) {
            require(next() == c) { "'$c' was expected at ${at - 1}" }
        }

        private fun peek(): Char? = text.getOrNull(at)

        private fun next(): Char? = text.getOrNull(at++)

        private fun skipSpace() {
            while (at < text.length && text[at] in " \t\r\n") at++
        }
    }

    /** How deep objects and arrays may lie in one another: far more than an answer needs, and no stack spent on a hostile one. */
    private const val MAX_DEPTH = 32
}

/** A member that is a string; null when there is none, or it is something else. */
internal fun Map<String, Any?>.string(name: String): String? = this[name] as? String

/** A member that is a whole number, written as a number or as a string (as some servers do). */
internal fun Map<String, Any?>.long(name: String): Long? = when (val value = this[name]) {
    is Double -> value.takeIf { it.isFinite() }?.toLong()
    is String -> value.trim().toLongOrNull()
    else -> null
}
