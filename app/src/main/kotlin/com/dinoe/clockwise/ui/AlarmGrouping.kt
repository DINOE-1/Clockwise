package com.dinoe.clockwise.ui

import com.dinoe.clockwise.data.Alarm
import com.dinoe.clockwise.data.Event
import java.time.LocalDateTime

data class AlarmGroup(
    // "stack:<stackId>" for a manual stack, "series:<seriesId>" for repeating events, and
    // "alarm:<alarmId>" for everything else.
    val key: String,
    // Members sorted by firing time; alarms that already passed are removed.
    val alarms: List<Alarm>,
) {
    // The card shown on top is always the one that rings next.
    val front: Alarm get() = alarms.firstOrNull { it.enabled } ?: alarms.first()

    val members: List<Alarm> get() = alarms.filter { it.id != front.id }

    val isStack: Boolean get() = alarms.size > 1
}

fun buildAlarmGroups(
    alarms: List<Alarm>,
    events: List<Event>,
    now: LocalDateTime = LocalDateTime.now(),
): List<AlarmGroup> {
    val seriesByEventId = events.associate { it.id to it.seriesId }
    return alarms
        .filterNot { it.isPast(now) }
        .groupBy { alarm ->
            val stackId = alarm.stackId
            val seriesId = alarm.eventId?.let(seriesByEventId::get)?.takeIf { it != 0L }
            when {
                stackId != null -> "stack:$stackId"
                seriesId != null -> "series:$seriesId"
                else -> "alarm:${alarm.id}"
            }
        }
        .map { (key, groupAlarms) ->
            AlarmGroup(
                key = key,
                alarms = groupAlarms.sortedWith(
                    compareBy({ it.nextFireDateTime(now) }, { it.id }),
                ),
            )
        }
        .sortedWith(
            compareBy(
                { !it.front.enabled },
                { it.front.nextFireDateTime(now) },
                { it.front.id },
            ),
        )
}

// The enabled alarm that rings soonest, ignoring dated alarms that already passed.
fun List<AlarmGroup>.nextRingAlarm(now: LocalDateTime = LocalDateTime.now()): Alarm? =
    asSequence()
        .flatMap { it.alarms.asSequence() }
        .filter { it.enabled && !it.nextFireDateTime(now).isBefore(now) }
        .minByOrNull { it.nextFireDateTime(now) }

// Dated alarms only ring once; plain alarms keep ringing every day and never expire.
private fun Alarm.isPast(now: LocalDateTime): Boolean =
    fireDate != null && nextFireDateTime(now).isBefore(now)
