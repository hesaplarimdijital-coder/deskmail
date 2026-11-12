package com.deskmail.core.provider

/** A host that offers a service at a port, as a domain's SRV record names it. */
data class ServiceHost(val host: String, val port: Int, val priority: Int = 0, val weight: Int = 0)

/**
 * Reads the two answers of the domain name system the app asks for: which hosts take a domain's
 * mail (MX) and which host offers a service (SRV). The device's resolver gets the answer; this
 * reads the bytes (RFC 1035, RFC 2782). An answer that is cut short or not an answer at all reads
 * as "nothing known", never as a failure.
 */
object DnsAnswer {
    const val TYPE_MX = 15
    const val TYPE_SRV = 33

    /** The hosts that take a domain's mail, best first. A domain that says it takes none (RFC 7505) has none. */
    fun mailHosts(answer: ByteArray): List<String> =
        records(answer, TYPE_MX) { reader, _ ->
            val preference = reader.u16()
            val host = reader.name()
            if (host.isEmpty()) null else preference to host
        }.sortedBy { it.first }.map { it.second }.distinct()

    /** The hosts that offer the service asked for, best first: lowest priority, then highest weight. */
    fun services(answer: ByteArray): List<ServiceHost> =
        records(answer, TYPE_SRV) { reader, _ ->
            val priority = reader.u16()
            val weight = reader.u16()
            val port = reader.u16()
            val host = reader.name()
            // "." as the target: the domain says it does not offer this service
            if (host.isEmpty() || port == 0) null else ServiceHost(host, port, priority, weight)
        }.sortedWith(compareBy<ServiceHost> { it.priority }.thenByDescending { it.weight })

    private fun <T : Any> records(answer: ByteArray, type: Int, read: (Reader, Int) -> T?): List<T> =
        try {
            val reader = Reader(answer)
            reader.skip(4)
            val questions = reader.u16()
            val answers = reader.u16()
            reader.skip(4)
            repeat(questions) {
                reader.name()
                reader.skip(4)
            }
            val found = ArrayList<T>()
            repeat(answers) {
                reader.name()
                val recordType = reader.u16()
                reader.skip(6)
                val length = reader.u16()
                val next = reader.position + length
                if (next > answer.size) throw IndexOutOfBoundsException()
                if (recordType == type) read(reader, length)?.let { found += it }
                reader.position = next
            }
            found
        } catch (e: IndexOutOfBoundsException) {
            emptyList()
        }

    private class Reader(private val bytes: ByteArray) {
        var position = 0

        fun skip(count: Int) {
            position += count
            if (position > bytes.size) throw IndexOutOfBoundsException()
        }

        fun u8(): Int {
            if (position >= bytes.size) throw IndexOutOfBoundsException()
            return bytes[position++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        /**
         * A name, lower case, without the closing dot; empty for the root. A name may end in a
         * pointer to an earlier part of the message; the position ends up after the name as written here.
         */
        fun name(): String {
            val labels = ArrayList<String>()
            var at = position
            var after = -1
            var jumps = 0
            while (true) {
                if (at >= bytes.size) throw IndexOutOfBoundsException()
                val length = bytes[at].toInt() and 0xFF
                when {
                    length == 0 -> {
                        at++
                        break
                    }
                    length and 0xC0 == 0xC0 -> {
                        if (at + 1 >= bytes.size) throw IndexOutOfBoundsException()
                        if (after < 0) after = at + 2
                        // a message that points in circles is not read for ever
                        if (++jumps > MAX_JUMPS) throw IndexOutOfBoundsException()
                        at = ((length and 0x3F) shl 8) or (bytes[at + 1].toInt() and 0xFF)
                    }
                    length and 0xC0 != 0 -> throw IndexOutOfBoundsException()
                    else -> {
                        if (at + 1 + length > bytes.size) throw IndexOutOfBoundsException()
                        labels += String(bytes, at + 1, length, Charsets.ISO_8859_1)
                        at += 1 + length
                    }
                }
            }
            position = if (after >= 0) after else at
            return labels.joinToString(".").lowercase()
        }
    }

    private const val MAX_JUMPS = 16
}
