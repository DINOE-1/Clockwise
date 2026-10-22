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

private const val MAX_GENERATED_OCCURRENCES = 1_000

fun recurrenceDates(baseDate: LocalDate, rule: RecurrenceRule): List<LocalDate> {
    if (rule.type == Event.REPEAT_NONE) return emptyList()
    if (rule.type == Event.REPEAT_WEEKLY && rule.days.isEmpty()) return emptyList()

    val until = LocalDate.ofEpochDay(rule.untilEpochDay)
    if (!until.isAfter(baseDate)) return emptyList()

    val dates = mutableListOf<LocalDate>()
    var date = baseDate.plusDays(1)
    while (!date.isAfter(until) && dates.size < MAX_GENERATED_OCCURRENCES) {
        val matches = when (rule.type) {
            Event.REPEAT_DAILY -> true
            Event.REPEAT_WEEKLY -> date.dayOfWeek.value in rule.days
            else -> false
        }
        if (matches) dates += date
        date = date.plusDays(1)
    }
    return dates
}
