package com.dinoe.clockwise.data

import java.time.LocalDate

data class RecurrenceRule(
    // Event.REPEAT_NONE / REPEAT_DAILY / REPEAT_WEEKLY
    val type: Int,
    // ISO weekdays (1=Mon..7=Sun); used when type == REPEAT_WEEKLY.
    val days: Set<Int> = emptySet(),
    // Inclusive end of the generated range; ignored when type == REPEAT_NONE.
    val untilEpochDay: Long = 0,
)

/**
 * The generated occurrences after the first event plus whether the requested range had more
 * matching days than [MAX_OCCURRENCES_PER_SERIES]. Callers must refuse a truncated series instead
 * of silently creating a partial one.
 */
data class RecurrenceDates(
    val dates: List<LocalDate>,
    val limitReached: Boolean,
) {
    val totalOccurrences: Int get() = dates.size + 1
}

fun planRecurrence(baseDate: LocalDate, rule: RecurrenceRule): RecurrenceDates {
    if (rule.type == Event.REPEAT_NONE) return RecurrenceDates(emptyList(), false)
    if (rule.type == Event.REPEAT_WEEKLY && rule.days.isEmpty()) {
        return RecurrenceDates(emptyList(), false)
    }

    val until = LocalDate.ofEpochDay(rule.untilEpochDay)
    if (!until.isAfter(baseDate)) return RecurrenceDates(emptyList(), false)

    val maxFollowing = MAX_OCCURRENCES_PER_SERIES - 1
    val dates = mutableListOf<LocalDate>()
    var date = baseDate.plusDays(1)
    while (!date.isAfter(until)) {
        val matches = when (rule.type) {
            Event.REPEAT_DAILY -> true
            Event.REPEAT_WEEKLY -> date.dayOfWeek.value in rule.days
            else -> false
        }
        if (matches) {
            if (dates.size >= maxFollowing) {
                return RecurrenceDates(dates, limitReached = true)
            }
            dates += date
        }
        date = date.plusDays(1)
    }
    return RecurrenceDates(dates, limitReached = false)
}

fun recurrenceDates(baseDate: LocalDate, rule: RecurrenceRule): List<LocalDate> =
    planRecurrence(baseDate, rule).dates
