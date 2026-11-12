package com.deskmail.core.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

/**
 * Whose certificate is it? Checked against real certificates made for this test
 * (src/test/resources/certs; self-signed, their keys thrown away).
 */
class ServerIdentityTest {

    private fun certificate(name: String): X509Certificate =
        javaClass.getResourceAsStream("/certs/$name.pem").use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    /** Names: imap.posta.example, *.sunucu.example, POSTA.Example; addresses 192.0.2.7 and 2001:db8::7. */
    private val names = certificate("names")

    @Test
    fun aNameTheCertificateListsIsItsHost() {
        assertTrue(ServerIdentity.matches("imap.posta.example", names))
        // capitals and a closing dot make no other host
        assertTrue(ServerIdentity.matches("IMAP.Posta.Example.", names))
        assertTrue(ServerIdentity.matches("posta.example", names))

        assertFalse(ServerIdentity.matches("smtp.posta.example", names))
        assertFalse(ServerIdentity.matches("imap.posta.example.evil.example", names))
        assertFalse(ServerIdentity.matches("posta.example.org", names))
        assertFalse(ServerIdentity.matches("", names))
    }

    @Test
    fun aWildcardStandsForOneLabel() {
        assertTrue(ServerIdentity.matches("imap.sunucu.example", names))
        assertTrue(ServerIdentity.matches("smtp.sunucu.example", names))
        // not for none, and not for two
        assertFalse(ServerIdentity.matches("sunucu.example", names))
        assertFalse(ServerIdentity.matches("a.b.sunucu.example", names))
        assertFalse(ServerIdentity.matches(".sunucu.example", names))
    }

    @Test
    fun wildcardsThatCoverTooMuchCoverNothing() {
        // *.example, mail*.example.org, *.*.example.org, *
        val greedy = certificate("wildcards")
        assertFalse(ServerIdentity.matches("posta.example", greedy))
        assertFalse(ServerIdentity.matches("mail1.example.org", greedy))
        assertFalse(ServerIdentity.matches("a.b.example.org", greedy))
        assertFalse(ServerIdentity.matches("example", greedy))
        assertFalse(ServerIdentity.matches("imap.gmail.com", greedy))
    }

    @Test
    fun aHostGivenAsAnAddressMatchesAnAddressOnly() {
        assertTrue(ServerIdentity.matches("192.0.2.7", names))
        assertTrue(ServerIdentity.matches("2001:db8::7", names))
        // the same address, written out
        assertTrue(ServerIdentity.matches("2001:0db8:0:0:0:0:0:7", names))
        assertFalse(ServerIdentity.matches("192.0.2.8", names))
        assertFalse(ServerIdentity.matches("2001:db8::8", names))
    }

    @Test
    fun theCommonNameAloneNamesNoHost() {
        // CN=eski.example and no alternative names: as browsers and Android have it, that is no name
        val old = certificate("cn-only")
        assertFalse(ServerIdentity.matches("eski.example", old))
        // and the names certificate's own common name counts only because it is also listed
        assertTrue(ServerIdentity.matches("imap.posta.example", names))
    }

    /** A finished handshake, as far as the check looks at it: the certificates the server showed. */
    private fun session(certificates: () -> Array<X509Certificate>): SSLSession =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SSLSession::class.java)) { _, method, _ ->
            if (method.name == "getPeerCertificates") certificates() else throw UnsupportedOperationException(method.name)
        } as SSLSession

    @Test
    fun afterTheHandshakeTheServersOwnCertificateIsWhatCounts() {
        val shown = session { arrayOf(names, certificate("wildcards")) }
        assertTrue(ServerIdentity.verify("imap.posta.example", shown))
        assertFalse(ServerIdentity.verify("imap.gmail.com", shown))
        // the first certificate is the server's; what follows only vouches for it
        assertFalse(ServerIdentity.verify("imap.posta.example", session { arrayOf(certificate("wildcards"), names) }))
    }

    @Test
    fun nothingToCheckIsNoMatch() {
        assertFalse(ServerIdentity.verify(null, null))
        assertFalse(ServerIdentity.verify("imap.posta.example", null))
        assertFalse(ServerIdentity.verify("imap.posta.example", session { emptyArray() }))
        // a server that showed no certificate at all
        assertFalse(ServerIdentity.verify("imap.posta.example", session { throw SSLPeerUnverifiedException("peer not authenticated") }))
        assertTrue(ServerIdentity.matchesName("imap.example.org", "IMAP.example.org."))
        assertFalse(ServerIdentity.matchesName("imap.example.org", ""))
    }
}
