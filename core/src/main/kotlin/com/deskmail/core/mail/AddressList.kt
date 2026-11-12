package com.deskmail.core.mail

/**
 * What was typed into a recipients field, read as addresses: "Ayşe Yılmaz <ayse@example.org>,
 * deniz@example.org; elif@example.org". Addresses are separated by commas, semicolons or line
 * breaks; a name may stand before an address in angle brackets, quoted or not.
 */
object AddressList {

    /**
     * @param addresses what could be read, in the order it was typed, each address once.
     * @param invalid the pieces that are no address, as they were typed.
     */
    data class Parsed(val addresses: List<MailAddress>, val invalid: List<String>) {
        val isValid: Boolean get() = invalid.isEmpty()
    }

    fun parse(text: String): Parsed {
        val addresses = ArrayList<MailAddress>()
        val invalid = ArrayList<String>()
        val seen = HashSet<String>()
        for (piece in split(text)) {
            val address = read(piece)
            if (address == null) {
                invalid += piece
            } else if (seen.add(address.address.lowercase())) {
                addresses += address
            }
        }
        return Parsed(addresses, invalid)
    }

    /** The addresses as a field shows them again: "Ayşe Yılmaz <ayse@example.org>, deniz@example.org". */
    fun format(addresses: List<MailAddress>): String = addresses.joinToString(", ") { address ->
        when {
            address.name.isBlank() || address.name == address.address -> address.address
            address.name.any { it in SPECIALS } -> "\"" + address.name.replace("\\", "\\\\").replace("\"", "\\\"") + "\" <" + address.address + ">"
            else -> address.name + " <" + address.address + ">"
        }
    }

    /** Whether [text] is one e-mail address and nothing else. */
    fun isAddress(text: String): Boolean = ADDRESS.matches(text)

    /** The pieces between the separators; a separator inside quotes or angle brackets does not count. */
    private fun split(text: String): List<String> {
        val pieces = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var bracketed = false
        var escaped = false
        for (char in text) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                quoted && char == '\\' -> {
                    current.append(char)
                    escaped = true
                }
                char == '"' -> {
                    quoted = !quoted
                    current.append(char)
                }
                !quoted && char == '<' -> {
                    bracketed = true
                    current.append(char)
                }
                !quoted && char == '>' -> {
                    bracketed = false
                    current.append(char)
                }
                !quoted && !bracketed && (char == ',' || char == ';' || char == '\n' || char == '\r') -> {
                    pieces += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
        }
        pieces += current.toString()
        return pieces.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun read(piece: String): MailAddress? {
        val open = piece.lastIndexOf('<')
        if (open >= 0 && piece.endsWith(">")) {
            val address = piece.substring(open + 1, piece.length - 1).trim()
            if (!isAddress(address)) return null
            return MailAddress(unquote(piece.substring(0, open).trim()), address)
        }
        return if (isAddress(piece)) MailAddress("", piece) else null
    }

    private fun unquote(name: String): String {
        if (name.length < 2 || !name.startsWith("\"") || !name.endsWith("\"")) return name
        return name.substring(1, name.length - 1).replace("\\\"", "\"").replace("\\\\", "\\").trim()
    }

    /** What a name must be quoted for when it is written before an address. */
    private const val SPECIALS = "()<>[]:;@\\,.\""

    /** Something before an @, a domain with a dot after it, and nothing that cannot be in either. */
    private val ADDRESS = Regex("""[^\s@<>(),;:\\"\[\]]+@[^\s@<>(),;:\\"\[\]]+\.[^\s@<>(),;:\\"\[\].]+""")
}
