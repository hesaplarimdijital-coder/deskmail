package com.deskmail.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LogFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun tail(file: LogFile, maxLines: Int = 1000): List<String> {
        val done = CountDownLatch(1)
        var lines = emptyList<String>()
        file.loadTail(maxLines) {
            lines = it
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return lines
    }

    @Test
    fun whatIsAppendedIsThereForTheNextRun() {
        val directory = File(folder.root, "log")
        LogFile(directory).apply {
            append("one")
            append("two")
            flush()
        }
        // the process starts again: a new object over the same directory adds to the file
        val next = LogFile(directory)
        next.append("three")
        assertEquals(listOf("one", "two", "three"), tail(next))
        assertEquals(listOf("two", "three"), tail(next, maxLines = 2))
    }

    @Test
    fun aFullFileBecomesThePreviousOneAndTheOneBeforeIsDropped() {
        val directory = File(folder.root, "log")
        val file = LogFile(directory, maxBytes = 40)
        // 11 characters with the line break: a file is full after four lines, and 14 lines leave two in a third file
        (1..14).forEach { file.append("line %05d".format(it)) }
        file.flush()
        val names = directory.list()!!.sorted()
        assertEquals(listOf("deskmail.1.log", "deskmail.log"), names)
        val kept = tail(file)
        // the newest lines are all there, in order, and the oldest are gone
        assertEquals(listOf("line 00009", "line 00010", "line 00011", "line 00012", "line 00013", "line 00014"), kept)
        assertEquals(kept.sorted(), kept)
        assertFalse(kept.contains("line 00001"))
        assertTrue(File(directory, "deskmail.1.log").length() <= 60)
    }

    @Test
    fun theExportIsOneFileWithItsHeaderFirst() {
        val file = LogFile(File(folder.root, "log"), maxBytes = 40)
        (1..6).forEach { file.append("line %05d".format(it)) }
        val target = File(folder.root, "share/deskmail-log.txt")
        file.exportTo(target, header = listOf("DeskMail 0.1.0", ""))
        val exported = target.readLines()
        assertEquals(listOf("DeskMail 0.1.0", ""), exported.take(2))
        assertEquals(tail(file), exported.drop(2))
        assertEquals("line 00006", exported.last())
    }

    @Test
    fun clearingLeavesNothingOnDisk() {
        val directory = File(folder.root, "log")
        val file = LogFile(directory, maxBytes = 40)
        (1..6).forEach { file.append("line %05d".format(it)) }
        file.clear()
        assertEquals(emptyList<String>(), tail(file))
        file.append("after")
        assertEquals(listOf("after"), tail(file))
    }
}
