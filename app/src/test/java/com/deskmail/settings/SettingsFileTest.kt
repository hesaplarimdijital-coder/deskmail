package com.deskmail.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.MailSettings
import com.deskmail.mail.MessageTemplate
import com.deskmail.mail.PortableAccount
import com.deskmail.mail.SavedSearch
import com.deskmail.mail.SenderMarks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** The settings as a file to take to another device: what goes in, what comes out, and what is no such file. */
@RunWith(AndroidJUnit4::class)
class SettingsFileTest {

    private val gmail = PortableAccount(
        address = "deniz@gmail.com",
        name = "Gmail",
        senderName = "Deniz Aksoy",
        provider = "gmail",
        settings = MailSettings(ServerAddress("imap.gmail.com", 993, Security.Tls), ServerAddress("smtp.gmail.com", 465, Security.Tls), "deniz@gmail.com", AuthKind.AppPassword),
        syncDays = 30,
        notifyMode = "push",
        signature = "Deniz\nİstanbul",
    )
    private val work = PortableAccount(
        address = "deniz@isyeri.example",
        name = "İş",
        senderName = "",
        provider = "",
        settings = MailSettings(ServerAddress("imap.isyeri.example", 143, Security.StartTls), ServerAddress("smtp.isyeri.example", 587, Security.StartTls), "deniz", AuthKind.Password),
        syncDays = 90,
        notifyMode = "push",
        signature = "",
    )
    private val contents = SettingsFile.Contents(
        accounts = listOf(gmail, work),
        settings = mapOf("undo_send_seconds" to 20, "experiment_glass" to false, "experiment_web_layer" to "Software"),
        look = SettingsFile.Look("Oled", "Blue", "Pointer"),
        // the ids are each device's own: they do not travel
        templates = listOf(MessageTemplate(0, "Teşekkür", "Çok teşekkürler,\nDeniz")),
        searches = listOf(SavedSearch(0, "Faturalar", "konu:fatura")),
        senders = mapOf("mehmet.demir@isyeri.example" to SenderMarks(vip = true), "bulten@sahaf.example" to SenderMarks(images = true)),
    )

    @Test
    fun whatIsWrittenIsReadBackAsItWas() {
        val read = SettingsFile.read(SettingsFile.write(contents, Instant.parse("2026-11-13T09:00:00Z")))
        assertEquals(contents, read)
    }

    @Test
    fun thereIsNoPlaceForWhatAnAccountSignsInWith() {
        val written = JSONObject(SettingsFile.write(contents, Instant.EPOCH))
        assertEquals(SettingsFile.KIND, written.getString("kind"))
        assertEquals(SettingsFile.FORMAT, written.getInt("format"))
        val account = written.getJSONArray("accounts").getJSONObject(0)
        assertEquals(
            setOf("address", "name", "senderName", "provider", "imap", "smtp", "username", "auth", "syncDays", "notify", "signature"),
            account.keys().asSequence().toSet(),
        )
        assertEquals(setOf("host", "port", "security"), account.getJSONObject("imap").keys().asSequence().toSet())
    }

    @Test
    fun whatIsNoSettingsFileOfTheAppsIsNotRead() {
        assertNull(SettingsFile.read("Merhaba"))
        assertNull(SettingsFile.read("{}"))
        assertNull(SettingsFile.read("""{"kind": "another-app", "accounts": []}"""))
        assertNull(SettingsFile.read("[1, 2, 3]"))
        // a file far larger than settings ever are is not read whole
        assertNull(SettingsFile.read(ByteArray(SettingsFile.MAX_BYTES + 1) { ' '.code.toByte() }.inputStream()))
    }

    /** A later version may write more; what this one does not know, or what would not do, is passed over. */
    @Test
    fun whatIsNotKnownOrWouldNotDoIsPassedOver() {
        val text = """
            {"kind": "deskmail-settings", "format": 7, "templates": [{"name": "x"}],
             "accounts": [
               {"address": "ayse@posta.example", "imap": {"host": "imap.posta.example", "port": 993, "security": "Quantum"},
                "smtp": {"host": "smtp.posta.example", "port": 465, "security": "Tls"}, "auth": "Telepathy", "colour": "red"},
               {"address": "", "imap": {"host": "imap.example", "port": 993}, "smtp": {"host": "smtp.example", "port": 465}},
               {"address": "elif@posta.example", "imap": {"host": "imap.posta.example", "port": 0}, "smtp": {"host": "smtp.posta.example", "port": 465}},
               {"address": "can@posta.example", "smtp": {"host": "smtp.posta.example", "port": 465}},
               "not an account"
             ]}
        """.trimIndent()

        val read = SettingsFile.read(text)!!

        val account = read.accounts.single()
        assertEquals("ayse@posta.example", account.address)
        // never less than TLS for a security this version does not know; the password for a way of signing in it does not
        assertEquals(Security.Tls, account.settings.imap.security)
        assertEquals(AuthKind.Password, account.settings.auth)
        // the address signs in where nothing else is said
        assertEquals("ayse@posta.example", account.settings.username)
        assertTrue(read.settings.isEmpty())
        assertNull(read.look)
    }
}
