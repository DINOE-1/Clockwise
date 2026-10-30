package com.dinoe.clockwise.ai

import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.data.MAX_OCCURRENCES_PER_SERIES
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventRecurrencePlannerTest {
    private val date = LocalDate.of(2026, 3, 11)

    @Test
    fun untilMustBeAfterTheEventDate() {
        assertEquals(
            RecurrenceDraftIssue.UNTIL_INVALID,
            EventRecurrencePlanner.issue(
                date = date,
                type = Event.REPEAT_DAILY,
                days = emptySet(),
                untilEpochDay = date.toEpochDay(),
                requireWeekdayMatch = false,
            ),
        )
        assertEquals(
            RecurrenceDraftIssue.UNTIL_INVALID,
            EventRecurrencePlanner.issue(
                date = date,
                type = Event.REPEAT_DAILY,
                days = emptySet(),
                untilEpochDay = date.minusDays(3).toEpochDay(),
                requireWeekdayMatch = true,
            ),
        )
    }

    @Test
    fun weeklyRepeatRequiresAtLeastOneDay() {
        assertEquals(
            RecurrenceDraftIssue.WEEKDAYS_REQUIRED,
            EventRecurrencePlanner.issue(
                date = date,
                type = Event.REPEAT_WEEKLY,
                days = emptySet(),
                untilEpochDay = date.plusMonths(1).toEpochDay(),
                requireWeekdayMatch = false,
            ),
        )
    }

    @Test
    fun weekdayMatchIsRequiredOnlyWhenRequested() {
        val days = setOf(setOf(1, 2, 3, 4, 5, 6, 7).first { it != date.dayOfWeek.value })
        assertEquals(
            RecurrenceDraftIssue.WEEKDAY_MISSING,
            EventRecurrencePlanner.issue(
                date = date,
                type = Event.REPEAT_WEEKLY,
                days = days,
                untilEpochDay = date.plusMonths(1).toEpochDay(),
                requireWeekdayMatch = true,
            ),
        )
        assertNull(
            EventRecurrencePlanner.issue(
                date = date,
                type = Event.REPEAT_WEEKLY,
                days = days,
                untilEpochDay = date.plusMonths(1).toEpochDay(),
                requireWeekdayMatch = false,
            ),
        )
    }

    @Test
    fun overLimitSeriesIsRejected() {
        assertEquals(
            RecurrenceDraftIssue.TOO_MANY_OCCURRENCES,
            EventRecurrencePlanner.issue(
                date = date,
                type = Event.REPEAT_DAILY,
                days = emptySet(),
                untilEpochDay = date.plusDays(MAX_OCCURRENCES_PER_SERIES.toLong()).toEpochDay(),
                requireWeekdayMatch = false,
            ),
        )
    }

    @Test
    fun validWeeklyPlanHasNoIssue() {
        val days = setOf(date.dayOfWeek.value)
        val until = date.plusWeeks(4)
        assertNull(
            EventRecurrencePlanner.issue(
                date = date,
                type = Event.REPEAT_WEEKLY,
                days = days,
                untilEpochDay = until.toEpochDay(),
                requireWeekdayMatch = true,
            ),
        )
        val plan = EventRecurrencePlanner.plan(
            date = date,
            type = Event.REPEAT_WEEKLY,
            days = days,
            untilEpochDay = until.toEpochDay(),
        )
        assertEquals(5, plan.totalOccurrences)
        assertEquals(false, plan.limitReached)
    }

    @Test
    fun noRepeatHasNoPlanAndNoIssue() {
        assertNull(
            EventRecurrencePlanner.planOrNull(
                date = date,
                type = Event.REPEAT_NONE,
                days = emptySet(),
                untilEpochDay = 0L,
            ),
        )
        assertNull(
            EventRecurrencePlanner.issue(
                date = date,
                type = Event.REPEAT_NONE,
                days = emptySet(),
                untilEpochDay = 0L,
                requireWeekdayMatch = true,
            ),
        )
    }
}
