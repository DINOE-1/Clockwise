package com.dinoe.clockwise.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventTest {
    private val date = LocalDate.of(2026, 10, 25).toEpochDay()

    private fun event(start: Int, end: Int, allDay: Boolean = false) = Event(
        title = "Test",
        note = "",
        date = date,
        start = start,
        end = end,
        allDay = allDay,
    )

    @Test
    fun sameDayEventHasNoDayOffset() {
        val event = event(start = 9 * 3_600, end = 10 * 3_600)
        assertTrue(event.hasEndTime)
        assertEquals(0, event.endDayOffset)
        assertEquals(10 * 3_600, event.endTimeOfDay)
        assertEquals(date, event.lastCoveredEpochDay)
        assertTrue(event.occursOn(date))
        assertFalse(event.occursOn(date + 1))
    }

    @Test
    fun overnightEventCoversBothDays() {
        val event = event(start = 23 * 3_600, end = 7 * 3_600 + Event.SECONDS_PER_DAY)
        assertTrue(event.hasEndTime)
        assertEquals(1, event.endDayOffset)
        assertEquals(7 * 3_600, event.endTimeOfDay)
        assertEquals(date + 1, event.endDateEpochDay)
        assertTrue(event.occursOn(date))
        assertTrue(event.occursOn(date + 1))
        assertFalse(event.occursOn(date + 2))
    }

    @Test
    fun endingAtMidnightDoesNotCoverTheNextDay() {
        val event = event(start = 23 * 3_600, end = Event.SECONDS_PER_DAY)
        assertEquals(1, event.endDayOffset)
        assertEquals(0, event.endTimeOfDay)
        assertEquals(date, event.lastCoveredEpochDay)
        assertFalse(event.occursOn(date + 1))
    }

    @Test
    fun multiDayEventCoversEveryDayInBetween() {
        val event = event(start = 18 * 3_600, end = 17 * 3_600 + 2 * Event.SECONDS_PER_DAY)
        assertEquals(2, event.endDayOffset)
        assertEquals(date + 2, event.lastCoveredEpochDay)
        assertTrue(event.occursOn(date))
        assertTrue(event.occursOn(date + 1))
        assertTrue(event.occursOn(date + 2))
        assertFalse(event.occursOn(date + 3))
    }

    @Test
    fun allDayEventHasNoTimeAndOnlyCoversItsDate() {
        val event = event(start = 0, end = 0, allDay = true)
        assertFalse(event.hasEndTime)
        assertEquals(0, event.endDayOffset)
        assertEquals(date, event.lastCoveredEpochDay)
        assertTrue(event.occursOn(date))
        assertFalse(event.occursOn(date + 1))
    }
}
