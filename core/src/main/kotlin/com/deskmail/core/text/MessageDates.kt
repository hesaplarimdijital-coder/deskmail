package com.deskmail.core.text

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a message's date is written. */
object MessageDates {

    /**
     * For a list, as short as it can be and still tell the messages apart: the time for a message
     * of today ("14:32"), the day and the month for one of this year ("3 Kas"), the whole date for
     * an older one ("3 Kas 2025").
     */
    fun short(sentAt: Instant, now: Instant, zone: ZoneId, locale: Locale, hour24: Boolean = true): String {
        val sent = sentAt.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val pattern = when {
            sent.toLocalDate() == today -> timePattern(hour24)
            sent.year == today.year -> "d MMM"
            else -> "d MMM yyyy"
        }
        return DateTimeFormatter.ofPattern(pattern, locale).format(sent)
    }

    /** For the message itself: "12 Kasım 2026 Perşembe 14:32". */
    fun full(sentAt: Instant, zone: ZoneId, locale: Locale, hour24: Boolean = true): String =
        DateTimeFormatter.ofPattern("d MMMM yyyy EEEE ${timePattern(hour24)}", locale).format(sentAt.atZone(zone))

    private fun timePattern(hour24: Boolean) = if (hour24) "HH:mm" else "h:mm a"
}
