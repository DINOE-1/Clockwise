package com.dinoe.clockwise.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ReminderTest {
    private val zone = ZoneId.of("Asia/Taipei")

    @Test
    fun reminderTriggersTheSelectedMinutesBeforeTheStart() {
        val event = event(date = LocalDate.of(2026, 3, 10), start = LocalTime.of(9, 30), reminder = 10)
        val expected = LocalDateTime.of(2026, 3, 10, 9, 20)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, event.reminderTriggerMillis(zone))
    }

    @Test
    fun reminderCanFallOnThePreviousDay() {
        val event = event(date = LocalDate.of(2026, 3, 10), start = LocalTime.of(0, 5), reminder = 10)
        val expected = LocalDateTime.of(2026, 3, 9, 23, 55)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, event.reminderTriggerMillis(zone))
    }

    @Test
    fun disabledReminderHasNoTrigger() {
        val event = event(date = LocalDate.of(2026, 3, 10), start = LocalTime.of(9, 30), reminder = 0)
        assertNull(event.reminderTriggerMillis(zone))
    }

    @Test
    fun unsupportedReminderHasNoTrigger() {
        val event = event(date = LocalDate.of(2026, 3, 10), start = LocalTime.of(9, 30), reminder = 7)
        assertNull(event.reminderTriggerMillis(zone))
    }

    private fun event(date: LocalDate, start: LocalTime, reminder: Int): Event = Event(
        title = "Event",
        note = "",
        date = date.toEpochDay(),
        start = start.toSecondOfDay(),
        end = start.plusHours(1).toSecondOfDay(),
        reminderMinutes = reminder,
    )
}
