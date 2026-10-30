package com.dinoe.clockwise.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun customReminderTriggersTheRequestedMinutesBeforeTheStart() {
        val event = event(date = LocalDate.of(2026, 3, 10), start = LocalTime.of(9, 30), reminder = 7)
        val expected = LocalDateTime.of(2026, 3, 10, 9, 23)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, event.reminderTriggerMillis(zone))
    }

    @Test
    fun reminderBeyondTheLimitHasNoTrigger() {
        val event = event(
            date = LocalDate.of(2026, 3, 10),
            start = LocalTime.of(9, 30),
            reminder = Event.MAX_REMINDER_MINUTES + 1,
        )
        assertNull(event.reminderTriggerMillis(zone))
    }

    @Test
    fun reminderValidationAcceptsTheSupportedRangeOnly() {
        assertTrue(Event.isValidReminderMinutes(Event.REMINDER_OFF))
        assertTrue(Event.isValidReminderMinutes(Event.MIN_REMINDER_MINUTES))
        assertTrue(Event.isValidReminderMinutes(45))
        assertTrue(Event.isValidReminderMinutes(Event.MAX_REMINDER_MINUTES))
        assertFalse(Event.isValidReminderMinutes(-1))
        assertFalse(Event.isValidReminderMinutes(Event.MAX_REMINDER_MINUTES + 1))
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
