package com.dinoe.clockwise.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecurrenceTest {
    private val base = LocalDate.of(2026, 1, 5) // Monday

    @Test
    fun noRepeatProducesNoFollowingDates() {
        val plan = planRecurrence(base, RecurrenceRule(Event.REPEAT_NONE))
        assertTrue(plan.dates.isEmpty())
        assertFalse(plan.limitReached)
        assertEquals(1, plan.totalOccurrences)
    }

    @Test
    fun dailyPlanStopsAtTheSeriesLimit() {
        val rule = RecurrenceRule(
            type = Event.REPEAT_DAILY,
            untilEpochDay = base.plusDays(365).toEpochDay(),
        )
        val plan = planRecurrence(base, rule)
        assertTrue(plan.limitReached)
        assertEquals(MAX_OCCURRENCES_PER_SERIES, plan.totalOccurrences)
        assertEquals(MAX_OCCURRENCES_PER_SERIES - 1, plan.dates.size)
    }

    @Test
    fun exactlyTheMaximumIsNotTruncated() {
        val rule = RecurrenceRule(
            type = Event.REPEAT_DAILY,
            untilEpochDay = base.plusDays((MAX_OCCURRENCES_PER_SERIES - 1).toLong()).toEpochDay(),
        )
        val plan = planRecurrence(base, rule)
        assertFalse(plan.limitReached)
        assertEquals(MAX_OCCURRENCES_PER_SERIES, plan.totalOccurrences)
    }

    @Test
    fun weeklyPlanOnlyIncludesSelectedWeekdays() {
        val rule = RecurrenceRule(
            type = Event.REPEAT_WEEKLY,
            days = setOf(1, 3, 5),
            untilEpochDay = base.plusWeeks(2).toEpochDay(),
        )
        val plan = planRecurrence(base, rule)
        assertFalse(plan.limitReached)
        assertTrue(plan.dates.all { it.dayOfWeek.value in setOf(1, 3, 5) })
        assertEquals(6, plan.dates.size)
    }

    @Test
    fun emptyWeeklyRuleProducesNothing() {
        val plan = planRecurrence(base, RecurrenceRule(Event.REPEAT_WEEKLY))
        assertTrue(plan.dates.isEmpty())
        assertFalse(plan.limitReached)
    }
}
