package com.deskmail

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The process: the log starts with it, and what is shared of the log holds no secret. */
@RunWith(AndroidJUnit4::class)
class DeskMailApplicationTest {

    private val app = ApplicationProvider.getApplicationContext<DeskMailApplication>()

    @Test
    fun aRunStartsWithTheVersionAndTheDevice() {
        val first = app.log.lines().first { it.contains(" I app: started: ") }
        assertTrue(first, first.contains("DeskMail 0.1.0"))
        assertTrue(first, first.contains("Android"))
    }

    @Test
    fun theSharedLogIsTheWholeLogWithoutSecrets() {
        app.log.info("imap", "C: a1 LOGIN deniz@gmail.example abcd-efgh-ijkl-mnop")
        app.settings.updateGlass(false)
        val exported = app.exportLog().readLines()
        assertTrue(exported.first().startsWith("DeskMail 0.1.0"))
        assertTrue(exported.any { it.endsWith("app: started: " + app.describe()) })
        assertTrue(exported.any { it.endsWith("imap: C: a1 LOGIN deniz@gmail.example ***") })
        assertTrue(exported.any { it.endsWith("settings: glass = false") })
        assertFalse(exported.any { it.contains("abcd-efgh-ijkl-mnop") })
        // only what was put under cache/share may leave the app
        assertTrue(app.exportLog().path.startsWith(app.cacheDir.path + "/" + DeskMailApplication.SHARE_DIRECTORY + "/"))
    }

    @Test
    fun clearingEmptiesTheLogInMemoryAndOnDisk() {
        app.log.info("test", "a line")
        app.clearLog()
        assertTrue(app.log.lines().isEmpty())
        assertTrue(app.exportLog().readLines().none { it.contains("a line") })
    }
}
