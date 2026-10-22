package com.dinoe.clockwise.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Entity(tableName = "alarms", indices = [Index("time")])
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // LocalTime.toSecondOfDay()
    val time: Int,
    val enabled: Boolean = true,
    // LocalDate.toEpochDay() of the single day this alarm rings on; null for plain alarms.
    val fireDate: Long? = null,
    // Id of the event this alarm was auto-created from; null for plain alarms.
    val eventId: Long? = null,
    // Display name, e.g. the event title for auto-created alarms; blank means untitled.
    val name: String = "",
    // Free-form note; blank means none.
    val note: String = "",
) {
    // Plain alarms ring at the next occurrence of time (today, or tomorrow when it already
    // passed). Dated alarms return that date, even when it is in the past.
    fun nextFireDateTime(now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val fireTime = LocalTime.ofSecondOfDay(time.toLong())
        val date = fireDate?.let(LocalDate::ofEpochDay)
            ?: now.toLocalDate().let { if (it.atTime(fireTime).isAfter(now)) it else it.plusDays(1) }
        return date.atTime(fireTime)
    }
}
