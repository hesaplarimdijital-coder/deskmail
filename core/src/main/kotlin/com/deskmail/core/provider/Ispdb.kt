package com.deskmail.core.provider

import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads a mail provider's settings from the format Thunderbird's database of providers (the ISPDB)
 * and many providers' own "autoconfig" pages use.
 */
object Ispdb {

    /** Where the database answers for a domain. */
    fun url(domain: String): String = "https://autoconfig.thunderbird.net/v1.1/$domain"

    /**
     * The first IMAP server and the first SMTP server of [xml] that the app can use: reached over
     * TLS or STARTTLS, signed in to with a password. Null when the document names none (a provider
     * with POP3 only, or with sign-in the app does not have), or is not such a document at all.
     */
    fun parse(xml: String, address: String): MailSettings? {
        val document = try {
            val factory = DocumentBuilderFactory.newInstance()
            // nothing outside the document is read; a parser that does not know a switch is left as it is
            for (feature in listOf("http://apache.org/xml/features/disallow-doctype-decl")) {
                runCatching { factory.setFeature(feature, true) }
            }
            runCatching { factory.isExpandEntityReferences = false }
            val builder = factory.newDocumentBuilder()
            // what is no such document is no answer; nothing about it is printed
            builder.setErrorHandler(Quiet)
            builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            return null
        }
        val provider = document.getElementsByTagName("emailProvider").item(0) as? Element ?: return null
        val imap = servers(provider, "incomingServer", "imap").firstNotNullOfOrNull { usable(it, address) } ?: return null
        val smtp = servers(provider, "outgoingServer", "smtp").firstNotNullOfOrNull { usable(it, address) } ?: return null
        return MailSettings(imap.first, smtp.first, username = imap.second, auth = AuthKind.Password)
    }

    private object Quiet : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit

        override fun error(exception: SAXParseException) = throw exception

        override fun fatalError(exception: SAXParseException) = throw exception
    }

    private fun servers(provider: Element, tag: String, type: String): List<Element> {
        val nodes = provider.getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.getAttribute("type").equals(type, ignoreCase = true) }
    }

    /** The server's address and the user name to give it, or null for a server the app will not talk to. */
    private fun usable(server: Element, address: String): Pair<ServerAddress, String>? {
        val host = text(server, "hostname")?.lowercase() ?: return null
        val port = text(server, "port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val security = when (text(server, "socketType")?.uppercase()) {
            "SSL" -> Security.Tls
            "STARTTLS" -> Security.StartTls
            // no encryption: not offered
            else -> return null
        }
        val authentication = all(server, "authentication").map { it.lowercase() }
        if (authentication.none { it == "password-cleartext" || it == "plain" }) return null
        val username = (text(server, "username") ?: "%EMAILADDRESS%")
            .replace("%EMAILADDRESS%", address)
            .replace("%EMAILLOCALPART%", address.substringBeforeLast('@'))
            .replace("%EMAILDOMAIN%", address.substringAfterLast('@'))
        return ServerAddress(host, port, security) to username
    }

    private fun all(parent: Element, tag: String): List<String> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it).textContent?.trim()?.takeIf { text -> text.isNotEmpty() } }
    }

    private fun text(parent: Element, tag: String): String? = all(parent, tag).firstOrNull()
}
