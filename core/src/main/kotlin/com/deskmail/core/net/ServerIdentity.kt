package com.deskmail.core.net

import java.net.InetAddress
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession

/**
 * Whether a server's certificate was issued for the host the app meant to reach.
 *
 * That the certificate is signed by an authority the device trusts is the platform's check and
 * happens during the handshake. That it names this host is checked here, the same way on every
 * platform: the mail library would hand that check to the platform under a name Java's TLS knows
 * and Android's does not, and there it would silently not happen.
 *
 * The rules (RFC 6125, as browsers apply them): only the certificate's subject alternative names
 * count, never its common name; a name matches whole, without regard to case; a "*" stands for
 * exactly one label, the leftmost, of a name with at least two more ("*.example.org" is
 * "imap.example.org", not "example.org" and not "a.b.example.org"); a host given as an IP address
 * matches an address the certificate lists.
 */
object ServerIdentity : HostnameVerifier {

    /** Called by the mail library after the handshake; false closes the connection. Never throws. */
    override fun verify(host: String?, session: SSLSession?): Boolean {
        if (host == null || session == null) return false
        val certificate = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull() ?: return false
        return matches(host, certificate)
    }

    fun matches(host: String, certificate: X509Certificate): Boolean {
        val names = runCatching { certificate.subjectAlternativeNames }.getOrNull() ?: return false
        val wanted = host.trim().trimEnd('.').lowercase()
        if (wanted.isEmpty()) return false
        if (isAddress(wanted)) {
            val address = address(wanted) ?: return false
            return names.any { it.size >= 2 && it[0] == IP_ADDRESS && address((it[1] as? String).orEmpty()) == address }
        }
        return names.any { it.size >= 2 && it[0] == DNS_NAME && matchesName(wanted, (it[1] as? String).orEmpty()) }
    }

    /** Whether [host] (lower case, no closing dot) is the name [pattern] of a certificate, or covered by its wildcard. */
    internal fun matchesName(host: String, pattern: String): Boolean {
        val name = pattern.trim().trimEnd('.').lowercase()
        if (name.isEmpty() || host.isEmpty()) return false
        if (!name.contains('*')) return name == host
        // the one place a wildcard may stand: alone, as the leftmost label
        if (!name.startsWith("*.") || name.indexOf('*', 1) >= 0) return false
        val rest = name.substring(1)
        // "*.com" would be every site there is
        if (rest.count { it == '.' } < 2) return false
        if (!host.endsWith(rest)) return false
        val label = host.substring(0, host.length - rest.length)
        return label.isNotEmpty() && !label.contains('.')
    }

    /** An IPv4 or IPv6 address written out, as opposed to a name. */
    private fun isAddress(host: String): Boolean = host.contains(':') || IPV4.matches(host)

    /** The address in one canonical spelling; null for what is none. No name is looked up: [text] is an address already. */
    private fun address(text: String): String? {
        if (!isAddress(text)) return null
        return runCatching { InetAddress.getByName(text).hostAddress }.getOrNull()
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    // the kinds of subject alternative name, as X.509 numbers them
    private const val DNS_NAME = 2
    private const val IP_ADDRESS = 7
}
