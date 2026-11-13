package com.deskmail.data

import com.deskmail.core.net.Credentials
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.MailSettings
import com.icegreen.greenmail.store.MailFolder
import com.icegreen.greenmail.user.GreenMailUser
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import jakarta.mail.Flags
import jakarta.mail.Multipart
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.ExternalResource
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.CoroutineContext

/** A box with a key of the test's own: the device's keystore is not there under Robolectric. */
fun testBox(seed: Int = 1): SecretBox {
    val key = SecretKeySpec(ByteArray(32) { (it * 7 + seed).toByte() }, "AES")
    return AesGcmBox { key }
}

/**
 * Holds whatever is launched on it until the test says so: the work the app does in the
 * background (the texts a sync leaves for later) then happens when the test is ready to look.
 */
class HeldDispatcher : CoroutineDispatcher() {
    private val held = ArrayList<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(held) { held += block }
    }

    val waiting: Int get() = synchronized(held) { held.size }

    /** Runs what was launched so far, and what that launches in turn. */
    fun release() {
        while (true) {
            val next = synchronized(held) { if (held.isEmpty()) null else held.removeAt(0) } ?: return
            next.run()
        }
    }
}

/**
 * A mail server for a test (GreenMail) on free local ports, with one account: IMAP to read from
 * and SMTP to send through. Folders nest with a dot. The messages a test reads are written here,
 * as a mail program would send them; what the app sends through it lands in the inbox of whoever
 * it was sent to ([inboxOf]).
 */
class TestImapServer : ExternalResource() {
    private val greenMail = GreenMail(
        arrayOf(
            ServerSetup(0, LOCALHOST, ServerSetup.PROTOCOL_IMAP),
            ServerSetup(0, LOCALHOST, ServerSetup.PROTOCOL_SMTP),
        ),
    )
    private var counter = 0

    lateinit var user: GreenMailUser
        private set

    val imap: ServerAddress get() = ServerAddress(LOCALHOST, greenMail.imap.port, Security.None)

    val smtp: ServerAddress get() = ServerAddress(LOCALHOST, greenMail.smtp.port, Security.None)

    /** The account's settings as the app would have found them. */
    val settings: MailSettings get() = MailSettings(imap, smtp, USER, AuthKind.Password)

    val credentials: Credentials get() = Credentials.Password(USER, PASSWORD)

    override fun before() {
        greenMail.start()
        user = greenMail.setUser(ADDRESS, USER, PASSWORD)
    }

    override fun after() {
        greenMail.stop()
    }

    /** Removes a message for good, as another mail program would. */
    fun expunge(uid: Long, folder: String = "INBOX") {
        setFlag(uid, Flags.Flag.DELETED, true, folder)
        folder(folder).expunge()
    }

    /** Stops the server while the test goes on: nobody answers at its port any more. */
    fun stop() {
        greenMail.stop()
    }

    fun createFolder(path: String) {
        greenMail.managers.imapHostManager.createMailbox(user, path)
    }

    private fun folder(path: String): MailFolder {
        val host = greenMail.managers.imapHostManager
        return if (path.equals("INBOX", ignoreCase = true)) host.getInbox(user) else host.getFolder(user, path)
    }

    /**
     * Puts a message into a folder as if it had arrived at [received]; returns its UID. With
     * [html] it has both a plain and an HTML part.
     */
    fun deliver(
        subject: String,
        text: String,
        received: Instant,
        folder: String = "INBOX",
        html: String? = null,
        from: String = "Ayşe Yılmaz",
        seen: Boolean = false,
    ): Long {
        val id = ++counter
        val head = StringBuilder()
        head.append("From: ").append(word(from)).append(" <ayse@example.org>\r\n")
        head.append("To: ").append(word("Deniz Aksoy")).append(" <").append(ADDRESS).append(">\r\n")
        head.append("Subject: ").append(word(subject)).append("\r\n")
        head.append("Date: ").append(RFC_822.format(received.atOffset(ZoneOffset.UTC))).append("\r\n")
        head.append("Message-ID: <test-").append(id).append("@example.org>\r\n")
        head.append("MIME-Version: 1.0\r\n")
        val body = if (html == null) {
            "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" + encoded(text)
        } else {
            "Content-Type: multipart/alternative; boundary=\"sinir\"\r\n\r\n" +
                "--sinir\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" + encoded(text) +
                "--sinir\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" + encoded(html) +
                "--sinir--\r\n"
        }
        val raw = (head.toString() + body).toByteArray(Charsets.US_ASCII)
        val message = GreenMailUtil.newMimeMessage(ByteArrayInputStream(raw))
        val flags = Flags().apply { if (seen) add(Flags.Flag.SEEN) }
        return folder(folder).appendMessage(message, flags, Date.from(received))
    }

    fun setFlag(uid: Long, flag: Flags.Flag, value: Boolean, folder: String = "INBOX") {
        folder(folder).setFlags(Flags(flag), value, uid, null, false)
    }

    /** What the app sent to [address] through this server: the inbox of somebody the test has no account for. */
    fun inboxOf(address: String): List<MimeMessage> {
        val recipient = greenMail.userManager.getUserByEmail(address) ?: return emptyList()
        return greenMail.managers.imapHostManager.getInbox(recipient).messages.map { it.mimeMessage }
    }

    /** A message as a folder holds it; null for one it does not hold. */
    fun message(uid: Long, folder: String = "INBOX"): MimeMessage? = folder(folder).getMessage(uid)?.mimeMessage

    /** The UIDs of the messages a folder holds, ascending. */
    fun uids(folder: String = "INBOX"): List<Long> = folder(folder).messageUids.sorted()

    /** Whether a message carries a mark on the server; false for a message the folder does not hold. */
    fun has(uid: Long, flag: Flags.Flag, folder: String = "INBOX"): Boolean = folder(folder).getMessage(uid)?.flags?.contains(flag) == true

    /** Puts a message with a file attached into the inbox; returns its UID. */
    fun deliverWithFile(subject: String, text: String, fileName: String, mimeType: String, content: ByteArray, received: Instant): Long {
        val id = ++counter
        val raw = "From: " + word("Ayşe Yılmaz") + " <ayse@example.org>\r\n" +
            "To: " + word("Deniz Aksoy") + " <" + ADDRESS + ">\r\n" +
            "Subject: " + word(subject) + "\r\n" +
            "Date: " + RFC_822.format(received.atOffset(ZoneOffset.UTC)) + "\r\n" +
            "Message-ID: <test-" + id + "@example.org>\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: multipart/mixed; boundary=\"dis\"\r\n\r\n" +
            "--dis\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" + encoded(text) +
            "--dis\r\nContent-Type: " + mimeType + "; name=\"" + fileName + "\"\r\nContent-Transfer-Encoding: base64\r\n" +
            "Content-Disposition: attachment; filename=\"" + fileName + "\"\r\n\r\n" +
            Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(content) + "\r\n" +
            "--dis--\r\n"
        val message = GreenMailUtil.newMimeMessage(ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII)))
        return folder("INBOX").appendMessage(message, Flags(), Date.from(received))
    }

    companion object {
        const val LOCALHOST = "127.0.0.1"
        const val ADDRESS = "deniz@example.org"
        const val USER = "deniz"
        const val PASSWORD = "a-test-password"

        private val RFC_822: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US)

        /** A header's words, in the form headers carry what is not ASCII. */
        private fun word(text: String): String = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)) + "?="

        /** A part's content in base64, in lines as mail has them. */
        private fun encoded(text: String): String = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(text.toByteArray(Charsets.UTF_8)) + "\r\n"
    }
}

/** A message's plain text as it was sent. */
fun MimeMessage.sentText(): String {
    val whole = content
    if (whole !is Multipart) return String(inputStream.readBytes(), Charsets.UTF_8)
    val text = (0 until whole.count).map { whole.getBodyPart(it) }.first { it.fileName == null }
    return String(text.inputStream.readBytes(), Charsets.UTF_8)
}

/** The files a message carries as it was sent: each one's name and what is in it. */
fun MimeMessage.sentFiles(): List<Pair<String, ByteArray>> {
    val whole = content as? Multipart ?: return emptyList()
    return (0 until whole.count).map { whole.getBodyPart(it) }.filter { it.fileName != null }.map { it.fileName to it.inputStream.readBytes() }
}

/**
 * Ends what [scope] runs and waits until it is over, so that nothing of it reaches for a database
 * that is closed right after. (Cut off in the middle of a query it would fail there with nobody
 * left to hear it, and the next test that runs coroutines would be blamed for it.)
 *
 * Not for a scope whose dispatcher holds its work back: what never runs never ends, and never
 * reaches for anything either. Cancelling is enough there.
 */
fun endAndWait(scope: CoroutineScope) {
    val job = scope.coroutineContext[Job] ?: return
    runBlocking { withTimeoutOrNull(10_000) { job.cancelAndJoin() } }
}

/**
 * Has a test that got stuck fail, and say where every thread stood when it did. For tests that
 * wait on lines and threads of their own: a wait that nothing ends would otherwise keep the whole
 * build waiting, and leave nothing to go by.
 *
 * After [seconds] the test's thread is interrupted, which ends most waits. A test that is still
 * stuck half a minute later takes the test run down with it; where it stood is on standard error
 * by then.
 */
class StuckGuard(private val seconds: Long = 120) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val test = Thread.currentThread()
            val done = CountDownLatch(1)
            val stood = AtomicReference<String>()
            val watchdog = Thread {
                if (done.await(seconds, TimeUnit.SECONDS)) return@Thread
                val stacks = Thread.getAllStackTraces().entries.joinToString("\n") { (thread, frames) ->
                    "\"${thread.name}\" ${thread.state}\n" + frames.joinToString("\n") { "    at $it" }
                }
                stood.set(stacks)
                System.err.println("STUCK after $seconds s: ${description.displayName}\n$stacks")
                System.err.flush()
                test.interrupt()
                if (!done.await(30, TimeUnit.SECONDS)) {
                    System.err.println("STILL STUCK: ${description.displayName}; the test run ends here")
                    System.err.flush()
                    Runtime.getRuntime().halt(3)
                }
            }
            watchdog.isDaemon = true
            watchdog.name = "stuck-guard"
            watchdog.start()
            try {
                base.evaluate()
            } catch (e: Throwable) {
                val stacks = stood.get() ?: throw e
                throw AssertionError("the test was stuck after $seconds s: $e\n$stacks", e)
            } finally {
                done.countDown()
                // an interrupt that came too late is not the next test's business
                Thread.interrupted()
            }
        }
    }
}

/** Waits until [check] holds: for what the app does on threads of its own. */
fun eventually(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!check()) {
        if (System.currentTimeMillis() > end) throw AssertionError("did not happen in time: $what")
        Thread.sleep(20)
    }
}
