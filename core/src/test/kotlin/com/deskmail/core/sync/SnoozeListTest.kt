package com.deskmail.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class SnoozeListTest {

    private val plan = Snooze("<plan@example.org>", Instant.parse("2026-11-16T05:00:00Z"), "INBOX", "Proje planı")
    private val bill = Snooze("<bill@example.org>", Instant.parse("2026-11-14T06:00:00Z"), "Faturalar/2026", "Kasım\tfaturası")

    @Test
    fun whatIsWrittenIsReadBackTheFirstToComeBackFirst() {
        val text = SnoozeList.write(listOf(plan, bill))

        // a person reading it in a web mail is told what it is
        assertTrue(text.startsWith("DeskMail bu klasördeki iletileri erteledi"))
        // a tab in the subject would split its line: it is a space there
        assertEquals(listOf(bill.copy(subject = "Kasım faturası"), plan), SnoozeList.read(text))
    }

    @Test
    fun whatIsNotALineOfTheListIsPassedOver() {
        val text = """
            Some words a person added.
            2026-11-16T05:00:00Z	<plan@example.org>	INBOX	Proje planı
            yesterday	<old@example.org>	INBOX	not a time
            2026-11-17T05:00:00Z		INBOX	no ID
            2026-11-17T06:00:00Z	<gw@example.org> (gw)	INBOX	an ID as its message has it
            2026-11-18T05:00:00Z	<empty@example.org>		no folder
            2026-11-19T05:00:00Z	<plan@example.org>	Archive	the same message once more
            2026-11-20T05:00:00Z	<short@example.org>	INBOX
        """.trimIndent().replace("\n", "\r\n")

        assertEquals(
            listOf(
                plan,
                // whatever the message's Message-ID is, comment and all: what is written is read
                Snooze("<gw@example.org> (gw)", Instant.parse("2026-11-17T06:00:00Z"), "INBOX", "an ID as its message has it"),
                Snooze("<short@example.org>", Instant.parse("2026-11-20T05:00:00Z"), "INBOX"),
            ),
            SnoozeList.read(text),
        )
    }

    @Test
    fun theListIsKnownByItsMessageId() {
        val id = SnoozeList.newMessageId("4f1c", "outlook.example")
        assertEquals("<deskmail-snoozes.4f1c@outlook.example>", id)
        assertTrue(SnoozeList.isList(id))
        assertFalse(SnoozeList.isList("<plan@example.org>"))
    }

    private val istanbul = ZoneId.of("Europe/Istanbul")

    private fun at(text: String): ZonedDateTime = ZonedDateTime.of(java.time.LocalDateTime.parse(text), istanbul)

    @Test
    fun aWorkingDayOffersTheEveningTomorrowTheWeekendAndNextWeek() {
        // Friday 13 November 2026, a quarter past two in the afternoon
        val now = at("2026-11-13T14:15")
        assertEquals(DayOfWeek.FRIDAY, now.dayOfWeek)

        val choices = SnoozeTimes.choices(now)

        assertEquals(
            listOf(
                SnoozeChoice.Kind.ThisEvening to at("2026-11-13T18:00"),
                SnoozeChoice.Kind.TomorrowMorning to at("2026-11-14T08:00"),
                SnoozeChoice.Kind.ThisWeekend to at("2026-11-14T09:00"),
                SnoozeChoice.Kind.NextWeek to at("2026-11-16T08:00"),
            ),
            choices.map { it.kind to it.at },
        )
    }

    @Test
    fun theEveningIsOfferedWhileItIsMoreThanAnHourAway() {
        assertEquals(SnoozeChoice.Kind.ThisEvening, SnoozeTimes.choices(at("2026-11-13T17:00")).first().kind)
        assertEquals(SnoozeChoice.Kind.TomorrowMorning, SnoozeTimes.choices(at("2026-11-13T17:01")).first().kind)
    }

    /** On a Sunday tomorrow morning is next week's beginning: offered once; the weekend is not offered on a weekend. */
    @Test
    fun aSundayOffersTomorrowOnce() {
        val choices = SnoozeTimes.choices(at("2026-11-15T20:00"))

        assertEquals(listOf(SnoozeChoice.Kind.TomorrowMorning to at("2026-11-16T08:00")), choices.map { it.kind to it.at })
    }
}
