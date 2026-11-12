package com.deskmail.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AppLogTest {

    private var time = Instant.parse("2026-11-12T11:32:05.123Z")
    private fun log(capacity: Int = 100) = AppLog(capacity, zone = { ZoneId.of("Europe/Istanbul") }, now = { time })

    @Test
    fun aLineCarriesLocalTimeLevelAndSource() {
        val log = log()
        log.info("sync", "3 new messages")
        log.warn("idle", "connection lost")
        assertEquals(
            listOf("2026-11-12 14:32:05.123 I sync: 3 new messages", "2026-11-12 14:32:05.123 W idle: connection lost"),
            log.lines(),
        )
        assertEquals('I', AppLog.levelOf(log.lines()[0]))
        assertEquals('W', AppLog.levelOf(log.lines()[1]))
    }

    @Test
    fun secretsAreMaskedBeforeAnythingKeepsThem() {
        val log = log()
        val written = ArrayList<String>()
        log.sink = { written += it }
        log.info("imap", "C: a1 LOGIN deniz@gmail.example abcd-efgh-ijkl-mnop")
        log.error("oauth", "token request failed", IllegalStateException("refresh_token=M.C5_BL2.0.U.-Cg rejected"))
        val everything = (log.lines() + written).joinToString("\n")
        assertFalse(everything.contains("abcd-efgh-ijkl-mnop"))
        assertFalse(everything.contains("M.C5_BL2"))
        assertTrue(everything.contains("LOGIN deniz@gmail.example ***"))
        assertTrue(everything.contains("refresh_token=***"))
        assertEquals(log.lines(), written)
    }

    @Test
    fun anExceptionIsWrittenWithItsCauseAsIndentedLines() {
        val log = log()
        log.error("smtp", "sending failed", RuntimeException("no route", IllegalArgumentException("bad port")))
        val lines = log.lines()
        assertEquals("2026-11-12 14:32:05.123 E smtp: sending failed", lines[0])
        assertEquals("    java.lang.RuntimeException: no route", lines[1])
        assertTrue(lines.any { it.startsWith("      at ") })
        assertTrue(lines.any { it == "    caused by: java.lang.IllegalArgumentException: bad port" })
        assertEquals('E', AppLog.levelOf(lines[0]))
        assertNull(AppLog.levelOf(lines[1]))
    }

    @Test
    fun onlyTheLatestLinesAreKeptInMemory() {
        val log = log(capacity = 3)
        (1..5).forEach { log.info("n", "line $it") }
        assertEquals(listOf("line 3", "line 4", "line 5"), log.lines().map { it.substringAfter("n: ") })
    }

    @Test
    fun earlierRunsComeBeforeThisOneAndNeverPushItOut() {
        val log = log(capacity = 4)
        log.info("app", "started")
        log.info("app", "window opened")
        log.preload(listOf("old 1", "old 2", "old 3"))
        assertEquals(listOf("old 2", "old 3"), log.lines().take(2))
        assertTrue(log.lines()[2].endsWith("app: started"))
        assertTrue(log.lines()[3].endsWith("app: window opened"))
    }

    @Test
    fun listenersHearOfEveryChangeUntilRemoved() {
        val log = log()
        var heard = 0
        val remove = log.addListener { heard++ }
        log.info("a", "one")
        log.clear()
        assertEquals(2, heard)
        assertTrue(log.lines().isEmpty())
        remove()
        log.info("a", "two")
        assertEquals(2, heard)
    }
}
