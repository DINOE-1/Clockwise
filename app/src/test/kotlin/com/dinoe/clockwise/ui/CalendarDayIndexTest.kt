package com.dinoe.clockwise.ui

import com.dinoe.clockwise.data.Event
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarDayIndexTest {
    private val day = LocalDate.of(2026, 10, 25).toEpochDay()

    private fun event(
        id: Long,
        title: String = "Event",
        start: Int = 9 * 3_600,
        end: Int = 10 * 3_600,
        allDay: Boolean = false,
        date: Long = day,
    ) = Event(
        id = id,
        title = title,
        note = "",
        date = date,
        start = start,
        end = end,
        allDay = allDay,
    )

    @Test
    fun eventIsIndexedOnlyOnItsDay() {
        val event = event(id = 1)

        val byDay = buildEventsByDay(listOf(event), day - 7..day + 7)

        assertEquals(listOf(event), byDay[day])
        assertFalse(byDay.containsKey(day - 1))
        assertFalse(byDay.containsKey(day + 1))
    }

    @Test
    fun multiDayEventCoversEveryOverlappingDayInsideTheRange() {
        val event = event(
            id = 1,
            start = 23 * 3_600,
            end = 6 * 3_600 + 2 * Event.SECONDS_PER_DAY,
        )

        val byDay = buildEventsByDay(listOf(event), day..day + 2)

        assertEquals(listOf(event), byDay[day])
        assertEquals(listOf(event), byDay[day + 1])
        assertEquals(listOf(event), byDay[day + 2])
        assertFalse(byDay.containsKey(day + 3))
        assertFalse(byDay.containsKey(day - 1))
    }

    @Test
    fun daysOutsideTheRangeAreDropped() {
        val event = event(
            id = 1,
            start = 18 * 3_600,
            end = 12 * 3_600 + 4 * Event.SECONDS_PER_DAY,
        )

        val byDay = buildEventsByDay(listOf(event), day + 1..day + 2)

        assertEquals(setOf(day + 1, day + 2), byDay.keys)
    }

    @Test
    fun allDayEventsSortBeforeTimedEvents() {
        val timed = event(id = 1, title = "Timed", start = 0, end = 3_600)
        val allDay = event(id = 2, title = "All day", start = 0, end = 0, allDay = true)

        val byDay = buildEventsByDay(listOf(timed, allDay), day..day)

        assertEquals(listOf(allDay, timed), byDay[day])
    }

    @Test
    fun timedEventsSortByStartThenTitle() {
        val late = event(id = 1, title = "Early alphabetically", start = 15 * 3_600)
        val earlyB = event(id = 2, title = "B", start = 8 * 3_600)
        val earlyA = event(id = 3, title = "A", start = 8 * 3_600)

        val byDay = buildEventsByDay(listOf(late, earlyB, earlyA), day..day)

        assertEquals(listOf(earlyA, earlyB, late), byDay[day])
    }

    @Test
    fun crossDayTailStartsAtMidnight() {
        val overnight = event(id = 1, start = 22 * 3_600, end = 2 * 3_600 + Event.SECONDS_PER_DAY)

        assertEquals(22 * 3_600, eventStartOnDay(overnight, day))
        assertEquals(0, eventStartOnDay(overnight, day + 1))
        assertEquals(0, eventStartOnDay(event(id = 2, start = 0, end = 0, allDay = true), day))
    }

    @Test
    fun comparatorUsesStartTimeOnTheRequestedDay() {
        val overnight = event(id = 1, start = 22 * 3_600, end = 2 * 3_600 + Event.SECONDS_PER_DAY)
        val nextMorning = event(
            id = 2,
            start = 1 * 3_600,
            end = 2 * 3_600,
            date = day + 1,
        )

        val nextDay = listOf(nextMorning, overnight).sortedWith(eventDayComparator(day + 1))

        assertEquals(listOf(overnight, nextMorning), nextDay)
    }

    @Test
    fun gridRangeIsMondayFirstAndCoversWholeMonths() {
        val october = YearMonth.of(2026, 10)
        val range = monthGridRange(october)
        val start = LocalDate.ofEpochDay(range.first)
        val days = (range.last - range.first + 1).toInt()

        assertEquals(LocalDate.of(2026, 9, 28), start)
        assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
        assertEquals(0, days % 7)
        assertTrue(range.first <= october.atDay(1).toEpochDay())
        assertTrue(range.last >= october.atEndOfMonth().toEpochDay())
        assertEquals(LocalDate.of(2026, 11, 1), LocalDate.ofEpochDay(range.last))
    }

    @Test
    fun gridRangeNeedsFiveRowsForMonthsThatFitExactly() {
        // February 2027 starts on a Monday and has 28 days, so the grid is padded to five rows.
        val range = monthGridRange(YearMonth.of(2027, 2))

        assertEquals(LocalDate.of(2027, 2, 1).toEpochDay(), range.first)
        assertEquals(35, (range.last - range.first + 1).toInt())
    }
}
