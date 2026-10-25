package com.dinoe.clockwise.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@Entity(tableName = "alarms", indices = [Index("time"), Index("eventId")])
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
    // UTC epoch millis of a pending snooze. This is the source of truth across process death
    // and reboots; AlarmScheduler must prefer it over the normal next occurrence while it is
    // in the future.
    val snoozedUntil: Long? = null,
    // Deletes the alarm row after it finishes ringing, instead of only switching it off.
    @ColumnInfo(defaultValue = "0") val oneTime: Boolean = false,
) {
    // Plain alarms ring at the next occurrence of time (today, or tomorrow when it already
    // passed). Dated alarms return that date, even when it is in the past.
    fun nextFireDateTime(now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val fireTime = LocalTime.ofSecondOfDay(time.toLong())
        val date = fireDate?.let(LocalDate::ofEpochDay)
            ?: now.toLocalDate().let { if (it.atTime(fireTime).isAfter(now)) it else it.plusDays(1) }
        return date.atTime(fireTime)
    }

    /**
     * The instant the alarm should actually wake the device: a live snooze first, then the normal
     * next occurrence. Null means the alarm cannot fire (for example a dated alarm in the past).
     */
    fun effectiveTriggerMillis(
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val snooze = snoozedUntil
        if (snooze != null && snooze > nowMillis) return snooze
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val next = nextFireDateTime(now)
        if (!next.isAfter(now)) return null
        return next.atZone(zone).toInstant().toEpochMilli()
    }
}
