package com.deskmail.ui.compose

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.mail.DraftSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** What another app asks to be written, as its intent says it: a mailto link, a text, files. */
@RunWith(AndroidJUnit4::class)
class ComposeRequestTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun request(intent: Intent): ComposeRequest? = ComposeRequest.from(context, intent)

    @Test
    fun aMailtoLinkBeginsAMessageToWhomItNames() {
        val link = Uri.parse("mailto:ayse@example.org?cc=elif@example.org&subject=Cumartesi%20kahvalt%C4%B1%3F&body=Olur%20mu%3F")

        for (action in listOf(Intent.ACTION_VIEW, Intent.ACTION_SENDTO)) {
            val asked = request(Intent(action, link))!!
            assertEquals(DraftSeed(to = "ayse@example.org", cc = "elif@example.org", subject = "Cumartesi kahvaltı?", body = "Olur mu?"), asked.seed)
            assertTrue(asked.files.isEmpty())
            assertTrue(asked.hasContent)
        }
        // only who it goes to: nothing worth keeping if it is closed untouched
        assertFalse(request(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:ayse@example.org")))!!.hasContent)
    }

    /** Some apps name the subject and the text beside the link, not in it. */
    @Test
    fun whatTheLinkLeavesOutIsTakenFromBesideIt() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:ayse@example.org"))
            .putExtra(Intent.EXTRA_SUBJECT, "Rapor\r\nBcc: gizli@example.org")
            .putExtra(Intent.EXTRA_TEXT, "Ekte.")
            .putExtra(Intent.EXTRA_CC, arrayOf("elif@example.org"))
            .putExtra(Intent.EXTRA_BCC, arrayOf("arsiv@example.org"))

        val seed = request(intent)!!.seed

        assertEquals("ayse@example.org", seed.to)
        assertEquals("elif@example.org", seed.cc)
        assertEquals("arsiv@example.org", seed.bcc)
        // one line, whatever it tried to be
        assertEquals("Rapor  Bcc: gizli@example.org", seed.subject)
        assertEquals("Ekte.", seed.body)
    }

    @Test
    fun aSharedTextBeginsAMessageWithIt() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_EMAIL, arrayOf("ayse@example.org", "elif@example.org"))
            .putExtra(Intent.EXTRA_SUBJECT, "Bir bağlantı")
            .putExtra(Intent.EXTRA_TEXT, "https://example.org/yazi")

        val asked = request(intent)!!

        assertEquals(DraftSeed(to = "ayse@example.org, elif@example.org", subject = "Bir bağlantı", body = "https://example.org/yazi"), asked.seed)
        assertTrue(asked.files.isEmpty())
    }

    @Test
    fun sharedFilesComeAlongAndAreReadWhenTheyAreAttached() {
        val photo = Uri.parse("content://media.example/external/images/42/tatil.jpg")
        shadowOf(context.contentResolver).registerInputStream(photo, "resim".byteInputStream())
        val one = request(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, photo))!!

        assertEquals(listOf("tatil.jpg"), one.files.map { it.name })
        assertEquals("resim", one.files.single().open()!!.use { String(it.readBytes()) })
        assertTrue(one.hasContent)

        val notes = Uri.parse("content://files.example/belgeler/notlar.txt")
        val several = request(
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(photo, notes)),
        )!!
        assertEquals(listOf("tatil.jpg", "notlar.txt"), several.files.map { it.name })

        // handed over only in the clip, and named twice: once is enough
        val clipped = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, photo)
        clipped.clipData = ClipData.newRawUri("tatil", photo).apply { addItem(ClipData.Item(notes)) }
        assertEquals(listOf("tatil.jpg", "notlar.txt"), request(clipped)!!.files.map { it.name })
    }

    /** Another app may hand over what a content provider of its own gives out, and nothing else. */
    @Test
    fun aPathIntoTheFileSystemOrIntoTheAppsOwnFilesIsNotAttached() {
        val own = Uri.parse("content://" + context.packageName + ".files/share/deskmail-log.txt")
        val path = Uri.parse("file:///data/data/" + context.packageName + "/databases/deskmail.db")
        val honest = Uri.parse("content://files.example/belgeler/notlar.txt")

        val asked = request(
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(own, path, honest)),
        )!!

        assertEquals(listOf("notlar.txt"), asked.files.map { it.name })
    }

    @Test
    fun whatAsksForNoMessageIsNotTakenForOne() {
        assertNull(ComposeRequest.from(context, null))
        assertNull(request(Intent(Intent.ACTION_MAIN)))
        assertNull(request(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/?subject=x"))))
        assertNull(request(Intent(Intent.ACTION_VIEW)))
        // a window brought back from the list of recent apps: what was asked for was done then
        val again = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:ayse@example.org")).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(request(again))
    }
}
