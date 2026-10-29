package com.dinoe.clockwise.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Entity(tableName = "events", indices = [Index("date"), Index("seriesId")])
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String,
    // LocalDate.toEpochDay() of the day the event belongs to.
    val date: Long,
    // LocalTime.toSecondOfDay()
    val start: Int,
    val end: Int,
    // 0 for standalone events; otherwise the row id of the source (first) event.
    val seriesId: Long = 0,
    // 0-based position in the series (source = 0, subsequent = 1, 2, ...).
    val occurrenceIndex: Int = 0,
    // 0 = none, 1 = daily, 2 = weekly.
    val repeatType: Int = 0,
    // ISO weekdays (1=Mon..7=Sun) joined by ','; only populated when repeatType == 2.
    val repeatDays: String = "",
    // Minutes before the event start to show a notification; 0 disables the reminder.
    @ColumnInfo(defaultValue = "0") val reminderMinutes: Int = REMINDER_OFF,
    // All-day events have no time of day and cover only [date].
    @ColumnInfo(defaultValue = "0") val allDay: Boolean = false,
) {
    val isRecurring: Boolean get() = repeatType != REPEAT_NONE

    // An event with end == start has no duration; the UI shows only its start time.
    val hasEndTime: Boolean get() = !allDay && end > start

    // [end] counts seconds from the start date's midnight and may pass 86_400 for events that
    // finish on a later day (for example 23:00 to 07:00 the next morning).
    val endDayOffset: Int get() = if (!hasEndTime) 0 else end / SECONDS_PER_DAY

    val endTimeOfDay: Int get() = if (!hasEndTime) start else end % SECONDS_PER_DAY

    val endDateEpochDay: Long get() = date + endDayOffset

    // Last day the event occupies; an event ending exactly at midnight does not occupy the next
    // day because it has no time left on it.
    val lastCoveredEpochDay: Long
        get() = if (!hasEndTime) date else date + (end - 1) / SECONDS_PER_DAY

    fun occursOn(epochDay: Long): Boolean =
        if (allDay) date == epochDay else epochDay in date..lastCoveredEpochDay

    fun reminderTriggerMillis(zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (reminderMinutes !in REMINDER_MINUTE_OPTIONS) return null
        return LocalDate.ofEpochDay(date)
            .atTime(LocalTime.ofSecondOfDay(start.toLong()))
            .minusMinutes(reminderMinutes.toLong())
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    companion object {
        const val SECONDS_PER_DAY = 86_400

        // Upper bound for how many days a single event may span, so rendering and backup
        // validation stay bounded.
        const val MAX_EVENT_DAY_SPAN = 366

        const val REPEAT_NONE = 0
        const val REPEAT_DAILY = 1
        const val REPEAT_WEEKLY = 2

        const val REMINDER_OFF = 0
        val REMINDER_MINUTE_OPTIONS = listOf(5, 10, 30)
    }
}
