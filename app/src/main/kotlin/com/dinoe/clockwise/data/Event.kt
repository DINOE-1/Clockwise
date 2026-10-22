package com.dinoe.clockwise.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
) {
    val isRecurring: Boolean get() = repeatType != REPEAT_NONE

    companion object {
        const val REPEAT_NONE = 0
        const val REPEAT_DAILY = 1
        const val REPEAT_WEEKLY = 2
    }
}
