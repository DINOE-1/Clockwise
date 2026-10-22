package com.dinoe.clockwise.ui

import com.dinoe.clockwise.data.Alarm
import java.time.Duration
import java.time.LocalDateTime

// Time left until an alarm rings, split into the units shown to the user.
// Minutes are rounded up so an alarm that is seconds away still reads "1分".
data class RingCountdown(
    val days: Long,
    val hours: Long,
    val minutes: Long,
)

private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

fun ringCountdown(alarm: Alarm, now: LocalDateTime = LocalDateTime.now()): RingCountdown {
    val seconds = Duration.between(now, alarm.nextFireDateTime(now)).seconds
    val totalMinutes = ((seconds + 59) / 60).coerceAtLeast(1)
    return RingCountdown(
        days = totalMinutes / MINUTES_PER_DAY,
        hours = totalMinutes % MINUTES_PER_DAY / MINUTES_PER_HOUR,
        minutes = totalMinutes % MINUTES_PER_HOUR,
    )
}
