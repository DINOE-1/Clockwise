package com.dinoe.clockwise.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class AlarmTest {
    @Test
    fun plainAlarmUsesTodayWhenTheTimeIsStillAhead() {
        val alarm = Alarm(time = 9 * 3600)
        val now = LocalDateTime.of(2026, 1, 5, 8, 0)
        assertEquals(LocalDateTime.of(2026, 1, 5, 9, 0), alarm.nextFireDateTime(now))
    }

    @Test
    fun plainAlarmRollsToTomorrowWhenTheTimeHasPassed() {
        val alarm = Alarm(time = 9 * 3600)
        val now = LocalDateTime.of(2026, 1, 5, 10, 0)
        assertEquals(LocalDateTime.of(2026, 1, 6, 9, 0), alarm.nextFireDateTime(now))
    }

    @Test
    fun datedAlarmReturnsItsDateEvenWhenItIsInThePast() {
        val date = LocalDate.of(2026, 1, 1)
        val alarm = Alarm(time = 9 * 3600, fireDate = date.toEpochDay())
        val now = LocalDateTime.of(2026, 1, 5, 10, 0)
        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 0), alarm.nextFireDateTime(now))
    }

    @Test
    fun datedAlarmUsesTheSelectedDate() {
        val date = LocalDate.of(2026, 2, 14)
        val alarm = Alarm(time = 18 * 3600, fireDate = date.toEpochDay())
        val now = LocalDateTime.of(2026, 1, 5, 10, 0)
        assertEquals(LocalDateTime.of(2026, 2, 14, 18, 0), alarm.nextFireDateTime(now))
    }

    @Test
    fun liveSnoozeWinsOverTheNormalNextOccurrence() {
        val zone = ZoneId.of("Asia/Taipei")
        val now = LocalDateTime.of(2026, 1, 5, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val snooze = now + 10 * 60_000L
        val alarm = Alarm(time = 9 * 3600, snoozedUntil = snooze)
        assertEquals(snooze, alarm.effectiveTriggerMillis(now, zone))
    }

    @Test
    fun expiredSnoozeFallsBackToTheNextOccurrence() {
        val zone = ZoneId.of("Asia/Taipei")
        val now = LocalDateTime.of(2026, 1, 5, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val alarm = Alarm(time = 9 * 3600, snoozedUntil = now - 1_000L)
        val expected = LocalDateTime.of(2026, 1, 6, 9, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, alarm.effectiveTriggerMillis(now, zone))
    }

    @Test
    fun pastDatedAlarmHasNoTrigger() {
        val zone = ZoneId.of("Asia/Taipei")
        val now = LocalDateTime.of(2026, 1, 5, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val alarm = Alarm(
            time = 9 * 3600,
            fireDate = LocalDate.of(2026, 1, 1).toEpochDay(),
        )
        assertNull(alarm.effectiveTriggerMillis(now, zone))
    }
}
