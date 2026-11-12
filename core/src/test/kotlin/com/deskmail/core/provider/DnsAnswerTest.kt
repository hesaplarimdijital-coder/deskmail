package com.deskmail.core.provider

import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsAnswerTest {

    /** A DNS message as a resolver would hand it over: one question, then the given records. */
    private class Message(question: String, type: Int) {
        private val out = ByteArrayOutputStream()
        private var answers = 0

        /** Where the question's name starts: a record may point at it instead of repeating it. */
        val questionAt = 12

        init {
            // id, flags (a response), counts filled in at the end
            repeat(12) { out.write(0) }
            name(question)
            u16(type)
            u16(1)
        }

        fun u16(value: Int) {
            out.write(value shr 8 and 0xFF)
            out.write(value and 0xFF)
        }

        fun name(name: String) {
            for (label in name.trimEnd('.').split('.').filter { it.isNotEmpty() }) {
                out.write(label.length)
                out.write(label.toByteArray(Charsets.ISO_8859_1))
            }
            out.write(0)
        }

        fun pointer(to: Int) {
            out.write(0xC0 or (to shr 8))
            out.write(to and 0xFF)
        }

        /** A record that answers the question, its name written as a pointer to the question's. */
        fun record(type: Int, data: Message.() -> Unit) {
            answers++
            pointer(questionAt)
            u16(type)
            u16(1)
            repeat(4) { out.write(0) }
            val body = Message("", 0).also { it.out.reset() }.apply(data).out.toByteArray()
            u16(body.size)
            out.write(body)
        }

        fun bytes(): ByteArray = out.toByteArray().also {
            it[2] = 0x81.toByte()
            it[3] = 0x80.toByte()
            it[5] = 1
            it[7] = answers.toByte()
        }
    }

    @Test
    fun theHostsThatTakeADomainsMailComeBestFirst() {
        val answer = Message("sirket.com.tr", DnsAnswer.TYPE_MX).apply {
            record(DnsAnswer.TYPE_MX) { u16(20); name("ALT1.aspmx.l.google.com.") }
            record(DnsAnswer.TYPE_MX) { u16(10); name("aspmx.l.google.com") }
            // some other record in the same answer is passed over
            record(16) { name("v=spf1") }
            record(DnsAnswer.TYPE_MX) { u16(30); name("aspmx.l.google.com") }
        }.bytes()
        assertEquals(listOf("aspmx.l.google.com", "alt1.aspmx.l.google.com"), DnsAnswer.mailHosts(answer))
        assertTrue(DnsAnswer.services(answer).isEmpty())
    }

    @Test
    fun aNameMayPointBackIntoTheMessage() {
        // the record's data is written by hand: preference 5, the label "mail", then a pointer to the
        // question's name instead of the rest
        val bytes = Message("example.org", DnsAnswer.TYPE_MX).bytes().toMutableList()
        bytes.addAll(
            listOf(
                0xC0, 12, 0, 15, 0, 1, 0, 0, 0, 0,
                0, 9, 0, 5, 4, 'm'.code, 'a'.code, 'i'.code, 'l'.code, 0xC0, 12,
            ).map { it.toByte() },
        )
        bytes[7] = 1
        assertEquals(listOf("mail.example.org"), DnsAnswer.mailHosts(bytes.toByteArray()))
    }

    @Test
    fun aServiceIsReadWithItsPort() {
        val answer = Message("_imaps._tcp.example.org", DnsAnswer.TYPE_SRV).apply {
            record(DnsAnswer.TYPE_SRV) { u16(10); u16(1); u16(993); name("yedek.example.org") }
            record(DnsAnswer.TYPE_SRV) { u16(0); u16(1); u16(993); name("imap.example.org.") }
            record(DnsAnswer.TYPE_SRV) { u16(0); u16(5); u16(994); name("imap2.example.org") }
        }.bytes()
        assertEquals(
            listOf(
                ServiceHost("imap2.example.org", 994, priority = 0, weight = 5),
                ServiceHost("imap.example.org", 993, priority = 0, weight = 1),
                ServiceHost("yedek.example.org", 993, priority = 10, weight = 1),
            ),
            DnsAnswer.services(answer),
        )
    }

    @Test
    fun aDomainThatSaysNoHasNothing() {
        val noService = Message("_imap._tcp.example.org", DnsAnswer.TYPE_SRV).apply {
            record(DnsAnswer.TYPE_SRV) { u16(0); u16(0); u16(0); name(".") }
        }.bytes()
        assertTrue(DnsAnswer.services(noService).isEmpty())
        val noMail = Message("example.org", DnsAnswer.TYPE_MX).apply {
            record(DnsAnswer.TYPE_MX) { u16(0); name(".") }
        }.bytes()
        assertTrue(DnsAnswer.mailHosts(noMail).isEmpty())
    }

    @Test
    fun whatIsNoAnswerReadsAsNothing() {
        assertTrue(DnsAnswer.mailHosts(ByteArray(0)).isEmpty())
        assertTrue(DnsAnswer.mailHosts(ByteArray(5)).isEmpty())
        val whole = Message("example.org", DnsAnswer.TYPE_MX).apply {
            record(DnsAnswer.TYPE_MX) { u16(10); name("mail.example.org") }
        }.bytes()
        // cut anywhere, it never throws
        for (length in whole.indices) DnsAnswer.mailHosts(whole.copyOf(length))
        assertTrue(DnsAnswer.mailHosts(whole.copyOf(whole.size - 3)).isEmpty())
        // a name that points at itself
        val circle = Message("example.org", DnsAnswer.TYPE_MX).bytes().toMutableList()
        val at = circle.size + 14
        circle.addAll(listOf(0xC0, 12, 0, 15, 0, 1, 0, 0, 0, 0, 0, 4, 0, 5, 0xC0 or (at shr 8), at and 0xFF).map { it.toByte() })
        circle[7] = 1
        assertTrue(DnsAnswer.mailHosts(circle.toByteArray()).isEmpty())
    }

    @Test
    fun aDomainsOwnServiceRecordsAreTheLastThingAsked() {
        val asked = ArrayList<String>()
        val records = mapOf(
            "_imaps._tcp.kendi.example" to listOf(ServiceHost("posta.kendi.example", 993)),
            "_submission._tcp.kendi.example" to listOf(ServiceHost("posta.kendi.example", 587)),
            // only half of what is needed
            "_imap._tcp.yarim.example" to listOf(ServiceHost("imap.yarim.example", 143)),
        )
        val autoconfig = Autoconfig(
            fetch = { null },
            services = { name -> asked += name; records[name].orEmpty() },
        )
        val found = autoconfig.find("deniz@kendi.example")!!
        assertEquals(Autoconfig.Source.Service, found.source)
        assertEquals(ServerAddress("posta.kendi.example", 993, Security.Tls), found.settings.imap)
        assertEquals(ServerAddress("posta.kendi.example", 587, Security.StartTls), found.settings.smtp)
        assertEquals("deniz@kendi.example", found.settings.username)
        assertEquals(AuthKind.Password, found.settings.auth)
        // TLS is asked for first
        assertEquals("_imaps._tcp.kendi.example", asked.first())

        assertNull(autoconfig.find("deniz@yarim.example"))
        assertNull(Autoconfig(fetch = { null }, services = { error("no resolver") }).find("deniz@kendi.example"))
        assertNull(Autoconfig(fetch = { null }, mailHosts = { error("no resolver") }).find("deniz@kendi.example"))
    }
}
