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
) {
    val isRecurring: Boolean get() = repeatType != REPEAT_NONE

    // An event with end == start has no duration; the UI shows only its start time.
    val hasEndTime: Boolean get() = end > start

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
        const val REPEAT_NONE = 0
        const val REPEAT_DAILY = 1
        const val REPEAT_WEEKLY = 2

        const val REMINDER_OFF = 0
        val REMINDER_MINUTE_OPTIONS = listOf(5, 10, 30)
    }
}
