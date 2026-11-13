package com.deskmail.core.mail

/**
 * What the user's provider found when it took a message in: the results of SPF, DKIM and DMARC,
 * as its Authentication-Results header (RFC 8601) says them. The app checks nothing itself.
 *
 * Only the topmost such header is read: the provider puts its own on top, as the last to handle
 * the message. One further down came with the message, from wherever it was written, and says
 * nothing that can be taken on trust. Where the provider adds none, the topmost may have come with
 * the message too: it is the provider's word only where it names the provider's server
 * ([isProviders]).
 */
object AuthResults {

    /** One check: [method] ("spf", "dkim", "dmarc"), what it came to ([result], in lower case), and for whom ([subject]: the domain or address it is about). */
    data class Check(val method: String, val result: String, val subject: String)

    /** The provider's word: who says it ([server]; empty where the header names nobody, as Microsoft's does), and its checks. */
    data class Results(val server: String, val checks: List<Check>)

    /** The checks that are shown, in this order; each may be there more than once (a message may carry several DKIM signatures). */
    val METHODS = listOf("spf", "dkim", "dmarc")

    /**
     * Whether [results] are the provider's own word: they name the server its own header names
     * ([authServId], from [com.deskmail.core.provider.Provider]; empty for Microsoft's, which names
     * none). Nothing is known of a provider without one ([authServId] null): its results may be
     * anybody's.
     */
    fun isProviders(results: Results, authServId: String?): Boolean =
        authServId != null && results.server.equals(authServId, ignoreCase = true)

    /** The topmost Authentication-Results of [headers] (header lines as they travelled); null where there is none. */
    fun read(headers: String): Results? {
        val value = topmost(headers) ?: return null
        val pieces = withoutComments(value).split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (pieces.isEmpty()) return Results("", emptyList())
        // the first piece names the server, unless it is a result already (Microsoft's header names none)
        val named = !pieces.first().contains('=')
        val server = if (named) pieces.first().split(WHITESPACE).first() else ""
        val checks = pieces.drop(if (named) 1 else 0).mapNotNull { check(it) }.filter { it.method in METHODS }
        return Results(server, checks)
    }

    /** One result: "dkim=pass header.d=example.com header.s=s1"; null for what is none ("none" alone, or not a result). */
    private fun check(piece: String): Check? {
        val words = piece.split(WHITESPACE).filter { it.isNotEmpty() }
        val first = words.firstOrNull() ?: return null
        val equals = first.indexOf('=')
        if (equals <= 0) return null
        val method = first.substring(0, equals).lowercase()
        val result = first.substring(equals + 1).lowercase()
        if (result.isEmpty()) return null
        val properties = words.drop(1).mapNotNull { word ->
            val at = word.indexOf('=')
            if (at <= 0) null else word.substring(0, at).lowercase() to word.substring(at + 1).trim('"')
        }.toMap()
        val subject = when (method) {
            "spf" -> properties["smtp.mailfrom"] ?: properties["smtp.helo"]
            "dkim" -> properties["header.d"] ?: properties["header.i"]?.substringAfter('@')
            "dmarc" -> properties["header.from"]
            else -> null
        }.orEmpty()
        return Check(method, result, subject)
    }

    /** The value of the first Authentication-Results header line, with its folded lines joined. */
    private fun topmost(headers: String): String? {
        val lines = headers.replace("\r\n", "\n").split('\n')
        val start = lines.indexOfFirst { it.startsWith(NAME, ignoreCase = true) }
        if (start < 0) return null
        val value = StringBuilder(lines[start].substring(NAME.length))
        var next = start + 1
        // a line that begins with a space or a tab goes on with the one before it
        while (next < lines.size && lines[next].isNotEmpty() && lines[next][0].isWhitespace()) {
            value.append(' ').append(lines[next].trim())
            next++
        }
        return value.toString().trim()
    }

    /** [value] without what stands in parentheses: a comment, which may hold anything, ";" and "=" included. */
    private fun withoutComments(value: String): String {
        val out = StringBuilder()
        var depth = 0
        var quoted = false
        for (ch in value) {
            when {
                ch == '"' && depth == 0 -> {
                    quoted = !quoted
                    out.append(ch)
                }
                ch == '(' && !quoted -> depth++
                ch == ')' && !quoted && depth > 0 -> depth--
                depth == 0 -> out.append(ch)
            }
        }
        return out.toString()
    }

    private const val NAME = "Authentication-Results:"
    private val WHITESPACE = Regex("\\s+")
}
