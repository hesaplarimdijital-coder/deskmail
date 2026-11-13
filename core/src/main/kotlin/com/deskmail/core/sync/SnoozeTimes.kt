package com.deskmail.core.sync

import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** A time a message can be snoozed until, as the snooze menu offers it: what it is called, and when that is. */
data class SnoozeChoice(val kind: Kind, val at: ZonedDateTime) {
    enum class Kind { ThisEvening, TomorrowMorning, ThisWeekend, NextWeek }
}

/**
 * The times a message is offered to be snoozed until, from [choices]' "now", in the user's own time
 * zone: this evening at six (while that is more than an hour away), tomorrow morning at eight, the
 * coming Saturday at nine (on working days), the coming Monday at eight. Two that fall at the same
 * moment are offered once, by the first name.
 */
object SnoozeTimes {
    const val EVENING_HOUR = 18
    const val MORNING_HOUR = 8
    const val WEEKEND_HOUR = 9

    fun choices(now: ZonedDateTime): List<SnoozeChoice> {
        val today = now.toLocalDate()
        val zone = now.zone
        val out = ArrayList<SnoozeChoice>()
        val evening = today.atTime(EVENING_HOUR, 0).atZone(zone)
        if (!now.plusHours(1).isAfter(evening)) out += SnoozeChoice(SnoozeChoice.Kind.ThisEvening, evening)
        out += SnoozeChoice(SnoozeChoice.Kind.TomorrowMorning, today.plusDays(1).atTime(MORNING_HOUR, 0).atZone(zone))
        if (now.dayOfWeek != DayOfWeek.SATURDAY && now.dayOfWeek != DayOfWeek.SUNDAY) {
            val saturday = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
            out += SnoozeChoice(SnoozeChoice.Kind.ThisWeekend, saturday.atTime(WEEKEND_HOUR, 0).atZone(zone))
        }
        val monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        out += SnoozeChoice(SnoozeChoice.Kind.NextWeek, monday.atTime(MORNING_HOUR, 0).atZone(zone))
        // on a Sunday, tomorrow morning is next week's beginning: said once
        return out.distinctBy { it.at.toInstant() }
    }
}
