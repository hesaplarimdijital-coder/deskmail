package com.deskmail.core.provider

import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTest {

    @Test
    fun theProvidersTheAppKnowsAreFoundByTheAddress() {
        assertSame(ProviderTable.Gmail, ProviderTable.forAddress("deniz@gmail.com"))
        assertSame(ProviderTable.Gmail, ProviderTable.forAddress("Deniz.Aksoy@GoogleMail.com"))
        assertSame(ProviderTable.Outlook, ProviderTable.forAddress("deniz@outlook.com"))
        assertSame(ProviderTable.Outlook, ProviderTable.forAddress("deniz@hotmail.com.tr"))
        assertSame(ProviderTable.Outlook, ProviderTable.forAddress("deniz@windowslive.com"))
        assertNull(ProviderTable.forAddress("deniz@example.org"))
        assertNull(ProviderTable.forAddress("no address"))
        assertNull(ProviderTable.forAddress("deniz@"))
        assertNull(ProviderTable.forAddress("@gmail.com"))
        assertSame(ProviderTable.Outlook, ProviderTable.byId("outlook"))
    }

    @Test
    fun everyKnownProviderIsReachedOverTls() {
        for (provider in ProviderTable.all) {
            assertTrue(provider.imap.security != Security.None)
            assertTrue(provider.smtp.security != Security.None)
            assertEquals(provider.id, provider.id.lowercase())
        }
        val gmail = ProviderTable.Gmail.settingsFor("deniz@gmail.com")
        assertEquals(ServerAddress("imap.gmail.com", 993, Security.Tls), gmail.imap)
        assertEquals("deniz@gmail.com", gmail.username)
        assertEquals(AuthKind.AppPassword, gmail.auth)
        assertEquals(AuthKind.MicrosoftOAuth, ProviderTable.Outlook.auth)
        // who signs in with an app password is told where to make one, over https
        for (provider in ProviderTable.all) {
            assertEquals(provider.auth == AuthKind.AppPassword, provider.appPasswordPage != null)
            assertTrue(provider.appPasswordPage?.startsWith("https://") ?: true)
        }
    }

    private val posteo = """
        <?xml version="1.0" encoding="UTF-8"?>
        <clientConfig version="1.1">
          <emailProvider id="posteo.de">
            <domain>posteo.de</domain>
            <displayName>Posteo</displayName>
            <incomingServer type="pop3">
              <hostname>posteo.de</hostname><port>995</port><socketType>SSL</socketType>
              <username>%EMAILADDRESS%</username><authentication>password-cleartext</authentication>
            </incomingServer>
            <incomingServer type="imap">
              <hostname>Posteo.de</hostname><port>143</port><socketType>plain</socketType>
              <username>%EMAILADDRESS%</username><authentication>password-cleartext</authentication>
            </incomingServer>
            <incomingServer type="imap">
              <hostname>posteo.de</hostname><port>993</port><socketType>SSL</socketType>
              <username>%EMAILLOCALPART%</username>
              <authentication>password-encrypted</authentication>
              <authentication>password-cleartext</authentication>
            </incomingServer>
            <outgoingServer type="smtp">
              <hostname>posteo.de</hostname><port>587</port><socketType>STARTTLS</socketType>
              <username>%EMAILADDRESS%</username><authentication>password-cleartext</authentication>
            </outgoingServer>
          </emailProvider>
        </clientConfig>
    """.trimIndent()

    @Test
    fun aProvidersSettingsAreReadFromTheDatabasesFormat() {
        val settings = Ispdb.parse(posteo, "deniz@posteo.de")!!
        // the IMAP server without encryption is passed over, POP3 is not looked at
        assertEquals(ServerAddress("posteo.de", 993, Security.Tls), settings.imap)
        assertEquals(ServerAddress("posteo.de", 587, Security.StartTls), settings.smtp)
        assertEquals("deniz", settings.username)
        assertEquals(AuthKind.Password, settings.auth)
    }

    @Test
    fun whatTheAppCannotUseIsNoAnswer() {
        assertNull(Ispdb.parse("<html><body>404</body></html>", "a@example.org"))
        assertNull(Ispdb.parse("not xml at all", "a@example.org"))
        assertNull(Ispdb.parse("", "a@example.org"))
        // sign-in the app does not have
        assertNull(Ispdb.parse(posteo.replace("password-cleartext", "OAuth2").replace("password-encrypted", "GSSAPI"), "a@posteo.de"))
        // no SMTP server
        assertNull(Ispdb.parse(posteo.replace("outgoingServer", "sendingServer"), "a@posteo.de"))
        // a document that pulls in something from outside is not read
        assertNull(Ispdb.parse("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>$posteo", "a@posteo.de"))
    }

    @Test
    fun theKnownProvidersAreNotLookedUp() {
        val asked = ArrayList<String>()
        val autoconfig = Autoconfig(fetch = { asked += it; null })
        val found = autoconfig.find("deniz@gmail.com")!!
        assertEquals(Autoconfig.Source.Table, found.source)
        assertSame(ProviderTable.Gmail, found.provider)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun anUnknownDomainIsLookedUpByDomainOnly() {
        val asked = ArrayList<String>()
        val autoconfig = Autoconfig(fetch = { url -> asked += url; if (url.endsWith("/posteo.de")) posteo else null })
        val found = autoconfig.find("deniz.aksoy@posteo.de")!!
        assertEquals(Autoconfig.Source.Database, found.source)
        assertEquals("posteo.de", found.settings.imap.host)
        assertNull(found.provider)
        assertEquals(listOf("https://autoconfig.thunderbird.net/v1.1/posteo.de"), asked)
        // the address itself went nowhere
        assertTrue(asked.none { it.contains("deniz") })
    }

    @Test
    fun aDomainHostedElsewhereIsFoundThroughItsMailHost() {
        val asked = ArrayList<String>()
        val autoconfig = Autoconfig(
            fetch = { url -> asked += url; if (url.endsWith("/posteo.de")) posteo else null },
            mailHosts = { domain ->
                when (domain) {
                    "sirket.com.tr" -> listOf("aspmx.l.google.com", "alt1.aspmx.l.google.com")
                    "dernek.org" -> listOf("mx01.posteo.de.")
                    "kendi.example" -> listOf("mail.kendi.example")
                    else -> emptyList()
                }
            },
        )
        val google = autoconfig.find("deniz@sirket.com.tr")!!
        assertEquals(Autoconfig.Source.MailHost, google.source)
        assertSame(ProviderTable.Gmail, google.provider)
        assertEquals("deniz@sirket.com.tr", google.settings.username)

        val posteoHosted = autoconfig.find("deniz@dernek.org")!!
        assertEquals(Autoconfig.Source.MailHost, posteoHosted.source)
        assertEquals("posteo.de", posteoHosted.settings.imap.host)

        // a domain that takes its own mail and that nobody knows: by hand
        assertNull(autoconfig.find("deniz@kendi.example"))
        assertNull(autoconfig.find("deniz@bilinmeyen.example"))
        assertNull(autoconfig.find("not an address"))
    }

    @Test
    fun aFailingLookupIsNoAnswerNotAFailure() {
        val autoconfig = Autoconfig(fetch = { throw java.io.IOException("no network") })
        assertNull(autoconfig.find("deniz@example.org"))
    }

    @Test
    fun aMailHostsProviderIsReadOffItsName() {
        assertEquals("google.com", Autoconfig.registrable("aspmx.l.google.com"))
        assertEquals("example.com.tr", Autoconfig.registrable("mx.example.com.tr."))
        assertEquals("posteo.de", Autoconfig.registrable("MX01.Posteo.de"))
        assertEquals("example.org", Autoconfig.registrable("example.org"))
        assertNull(Autoconfig.registrable("localhost"))
    }
}
