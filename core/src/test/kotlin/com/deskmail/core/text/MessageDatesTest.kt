package com.deskmail.core.text

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class MessageDatesTest {

    private val istanbul = ZoneId.of("Europe/Istanbul")
    private val turkish = Locale.forLanguageTag("tr")
    private val now = Instant.parse("2026-11-12T09:30:00Z") // 12:30 in Istanbul

    private fun short(at: String) = MessageDates.short(Instant.parse(at), now, istanbul, turkish)

    @Test
    fun aMessageOfTodayShowsItsTime() {
        assertEquals("12:05", short("2026-11-12T09:05:00Z"))
    }

    @Test
    fun todayIsTheReadersDayNotTheServers() {
        // 23:30 UTC on the 11th is already 02:30 on the 12th in Istanbul
        assertEquals("02:30", short("2026-11-11T23:30:00Z"))
        // 20:30 UTC on the 11th is 23:30 on the 11th in Istanbul: yesterday
        assertEquals("11 Kas", short("2026-11-11T20:30:00Z"))
    }

    @Test
    fun aMessageOfThisYearShowsDayAndMonth() {
        assertEquals("3 Kas", short("2026-11-03T08:00:00Z"))
        assertEquals("28 Şub", short("2026-02-28T08:00:00Z"))
    }

    @Test
    fun anOlderMessageShowsItsYear() {
        assertEquals("19 Ara 2025", short("2025-12-19T08:00:00Z"))
    }

    @Test
    fun twelveHourClockWhereTheDeviceUsesIt() {
        assertEquals("12:05 PM", MessageDates.short(Instant.parse("2026-11-12T09:05:00Z"), now, istanbul, Locale.US, hour24 = false))
    }

    @Test
    fun theReaderShowsTheWholeDate() {
        assertEquals("12 Kasım 2026 Perşembe 12:05", MessageDates.full(Instant.parse("2026-11-12T09:05:00Z"), istanbul, turkish))
        assertEquals("12 November 2026 Thursday 12:05", MessageDates.full(Instant.parse("2026-11-12T09:05:00Z"), istanbul, Locale.UK))
    }
}
