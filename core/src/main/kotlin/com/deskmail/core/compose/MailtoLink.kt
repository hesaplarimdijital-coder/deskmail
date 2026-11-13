package com.deskmail.core.compose

import java.io.ByteArrayOutputStream

/**
 * What a `mailto:` link asks a new message to start with (RFC 6068): who it goes to, its subject
 * and its text. Only these are taken. Any other header a link names is left out, so a link
 * cannot set what the user does not see in the form they send from.
 *
 * The recipients are text for the form's fields, several of them separated by commas; whether
 * each is an address is looked at where everything typed there is looked at, before sending.
 */
data class MailtoLink(
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
) {
    companion object {
        private const val SCHEME = "mailto:"

        /** Reads [link]; null when it is not a mailto link. */
        fun parse(link: String): MailtoLink? {
            val text = link.trim()
            if (!text.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) return null
            // a "#" is text as well: links are written with "Fatura #12" for a subject, and a mailto link has no fragment
            val rest = text.substring(SCHEME.length)
            val mark = rest.indexOf('?')
            val path = if (mark < 0) rest else rest.substring(0, mark)
            val query = if (mark < 0) "" else rest.substring(mark + 1)

            val to = ArrayList<String>()
            val cc = ArrayList<String>()
            val bcc = ArrayList<String>()
            var subject = ""
            var body = ""
            // split while still encoded: a comma, "&" or "=" that was encoded is part of a value
            path.split(',').forEach { add(to, decode(it)) }
            for (field in query.split('&')) {
                val equals = field.indexOf('=')
                if (equals <= 0) continue
                val value = decode(field.substring(equals + 1))
                when (decode(field.substring(0, equals)).lowercase()) {
                    "to" -> add(to, value)
                    "cc" -> add(cc, value)
                    "bcc" -> add(bcc, value)
                    "subject" -> subject = oneLine(value)
                    "body" -> body = value.replace("\r\n", "\n").replace('\r', '\n')
                }
            }
            return MailtoLink(to.joinToString(", "), cc.joinToString(", "), bcc.joinToString(", "), subject, body)
        }

        private fun add(list: MutableList<String>, value: String) {
            val line = oneLine(value)
            if (line.isNotEmpty()) list += line
        }

        /** A header's value is one line: whatever a link put there to break it is a space. */
        private fun oneLine(value: String): String =
            value.map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(Regex(" {2,}"), " ").trim()

        /**
         * Percent-decodes [text] as UTF-8. A "+" is a plus sign here, not a space, and a "%" that
         * no two hex digits follow stands for itself.
         */
        private fun decode(text: String): String {
            if ('%' !in text) return text
            val bytes = ByteArrayOutputStream(text.length)
            var index = 0
            while (index < text.length) {
                val char = text[index]
                val high = if (char == '%' && index + 2 < text.length) Character.digit(text[index + 1], 16) else -1
                val low = if (high >= 0) Character.digit(text[index + 2], 16) else -1
                if (high >= 0 && low >= 0) {
                    bytes.write(high * 16 + low)
                    index += 3
                } else {
                    val point = text.codePointAt(index)
                    bytes.write(String(Character.toChars(point)).toByteArray(Charsets.UTF_8))
                    index += Character.charCount(point)
                }
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }
}
