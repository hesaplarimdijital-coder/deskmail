package com.deskmail.ui.mail

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.deskmail.core.text.MessageDates
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The time the lists are dated against. The device's own; tests pin one. */
class MailClock(val zone: ZoneId = ZoneId.systemDefault(), val now: () -> Instant = { Instant.now() })

val LocalMailClock = staticCompositionLocalOf { MailClock() }

/** A message's date as text, in the device's language and its 12 or 24 hour clock. */
@Stable
class DateLabels(private val clock: MailClock, private val locale: Locale, private val hour24: Boolean) {
    /** For a list: "14:32", "3 Kas", "3 Kas 2025". */
    fun short(sentAt: Instant): String = MessageDates.short(sentAt, clock.now(), clock.zone, locale, hour24)

    /** For the message itself: "12 Kasım 2026 Perşembe 14:32". */
    fun full(sentAt: Instant): String = MessageDates.full(sentAt, clock.zone, locale, hour24)
}

@Composable
fun rememberDateLabels(): DateLabels {
    val clock = LocalMailClock.current
    val locale = LocalConfiguration.current.locales[0]
    val hour24 = DateFormat.is24HourFormat(LocalContext.current)
    return remember(clock, locale, hour24) { DateLabels(clock, locale, hour24) }
}
