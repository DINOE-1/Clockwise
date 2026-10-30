package com.dinoe.clockwise.ai

import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.data.MAX_OCCURRENCES_PER_SERIES
import com.dinoe.clockwise.data.RecurrenceDates
import com.dinoe.clockwise.data.RecurrenceRule
import com.dinoe.clockwise.data.planRecurrence
import java.time.LocalDate

enum class RecurrenceDraftIssue {
    UNTIL_INVALID,
    WEEKDAYS_REQUIRED,
    WEEKDAY_MISSING,
    TOO_MANY_OCCURRENCES,
}

/**
 * Recurrence planning and validation shared by the calendar event editor and the AI proposal
 * editors, so both apply the same rules to the same plan.
 */
object EventRecurrencePlanner {
    fun plan(date: LocalDate, type: Int, days: Set<Int>, untilEpochDay: Long): RecurrenceDates =
        planRecurrence(
            date,
            RecurrenceRule(type = type, days = days, untilEpochDay = untilEpochDay),
        )

    fun planOrNull(
        date: LocalDate,
        type: Int,
        days: Set<Int>,
        untilEpochDay: Long,
    ): RecurrenceDates? = if (type == Event.REPEAT_NONE) null else plan(date, type, days, untilEpochDay)

    /**
     * [planned] is the already computed [plan] for the same rule, passed by callers that keep it in
     * composition state so validation does not regenerate the series.
     */
    fun issue(
        date: LocalDate,
        type: Int,
        days: Set<Int>,
        untilEpochDay: Long,
        requireWeekdayMatch: Boolean,
        planned: RecurrenceDates? = null,
    ): RecurrenceDraftIssue? {
        if (type == Event.REPEAT_NONE) return null
        if (untilEpochDay <= date.toEpochDay()) return RecurrenceDraftIssue.UNTIL_INVALID
        if (type == Event.REPEAT_WEEKLY) {
            if (days.isEmpty()) return RecurrenceDraftIssue.WEEKDAYS_REQUIRED
            if (requireWeekdayMatch && date.dayOfWeek.value !in days) {
                return RecurrenceDraftIssue.WEEKDAY_MISSING
            }
        }
        val series = planned ?: plan(date, type, days, untilEpochDay)
        return if (series.limitReached || series.totalOccurrences > MAX_OCCURRENCES_PER_SERIES) {
            RecurrenceDraftIssue.TOO_MANY_OCCURRENCES
        } else {
            null
        }
    }
}
