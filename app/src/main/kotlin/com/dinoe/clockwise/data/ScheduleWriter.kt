package com.dinoe.clockwise.data

import android.content.Context
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.alarm.AlarmScheduler
import java.time.LocalDate
import java.time.LocalTime

data class AlarmCreation(
    val alarm: Alarm,
    val scheduled: Boolean,
)

data class EventCreation(
    val events: List<Event>,
    val alarms: List<AlarmCreation>,
)

data class EventMutation(
    val affectedEvents: Int = 0,
    val alarmsChanged: Int = 0,
    val scheduledAlarms: Int = 0,
    val unscheduledAlarms: Int = 0,
    val deletedAlarms: Int = 0,
) {
    operator fun plus(other: EventMutation): EventMutation = EventMutation(
        affectedEvents = affectedEvents + other.affectedEvents,
        alarmsChanged = alarmsChanged + other.alarmsChanged,
        scheduledAlarms = scheduledAlarms + other.scheduledAlarms,
        unscheduledAlarms = unscheduledAlarms + other.unscheduledAlarms,
        deletedAlarms = deletedAlarms + other.deletedAlarms,
    )
}

/**
 * Single place that turns a validated alarm/event request into Room rows and scheduled alarms.
 * The calendar and alarm screens delegate here so AI-created items behave exactly like manually
 * created ones.
 */
class ScheduleWriter(context: Context) {
    private val app = context.applicationContext as ClockwiseApp
    private val eventDao = app.database.eventDao()
    private val alarmDao = app.database.alarmDao()
    private val tagDao = app.database.tagDao()

    suspend fun addAlarm(
        time: Int,
        fireDate: Long? = null,
        eventId: Long? = null,
        name: String = "",
        note: String = "",
        enabled: Boolean = true,
        tags: List<Tag> = emptyList(),
    ): AlarmCreation {
        val selectedTags = tags.take(MAX_TAGS_PER_ITEM)
        val id = alarmDao.insert(
            Alarm(
                time = time,
                fireDate = fireDate,
                eventId = eventId,
                name = name,
                note = note,
                enabled = enabled,
            ),
        )
        val alarm = Alarm(
            id = id,
            time = time,
            fireDate = fireDate,
            eventId = eventId,
            name = name,
            note = note,
            enabled = enabled,
        )
        if (selectedTags.isNotEmpty()) {
            tagDao.insertAlarmTags(selectedTags.map { AlarmTagCrossRef(id, it.id) })
        }
        val scheduled = AlarmScheduler.schedule(app, alarm)
        return AlarmCreation(alarm = alarm, scheduled = scheduled)
    }

    suspend fun addEvent(
        title: String,
        note: String,
        date: LocalDate,
        start: LocalTime,
        end: LocalTime,
        withAlarm: Boolean,
        tags: List<Tag>,
        repeat: RecurrenceRule = RecurrenceRule(Event.REPEAT_NONE),
    ): EventCreation {
        val selectedTags = tags.take(MAX_TAGS_PER_ITEM)
        val repeatDays = if (repeat.type == Event.REPEAT_WEEKLY) {
            repeat.days.sorted().joinToString(",")
        } else {
            ""
        }
        val baseEvent = Event(
            title = title,
            note = note,
            date = date.toEpochDay(),
            start = start.toSecondOfDay(),
            end = end.toSecondOfDay(),
            repeatType = repeat.type,
            repeatDays = repeatDays,
        )
        val occurrences = if (repeat.type == Event.REPEAT_NONE) {
            val eventId = eventDao.insert(baseEvent)
            listOf(baseEvent.copy(id = eventId))
        } else {
            val sourceId = eventDao.insert(baseEvent)
            val source = baseEvent.copy(id = sourceId, seriesId = sourceId)
            eventDao.update(source)
            val following = recurrenceDates(date, repeat).mapIndexed { index, occurrenceDate ->
                baseEvent.copy(
                    date = occurrenceDate.toEpochDay(),
                    seriesId = sourceId,
                    occurrenceIndex = index + 1,
                )
            }
            val followingIds = eventDao.insertAll(following)
            listOf(source) + following.mapIndexed { index, event ->
                event.copy(id = followingIds[index])
            }
        }

        occurrences.forEach { occurrence ->
            if (selectedTags.isNotEmpty()) {
                tagDao.insertEventTags(
                    selectedTags.map { EventTagCrossRef(occurrence.id, it.id) },
                )
            }
        }

        val alarmCreations = if (withAlarm) {
            val alarms = occurrences.map { occurrence ->
                Alarm(
                    time = occurrence.start,
                    fireDate = occurrence.date,
                    eventId = occurrence.id,
                    name = title,
                    note = note,
                )
            }
            val alarmIds = alarmDao.insertAll(alarms)
            alarms.mapIndexed { index, alarm ->
                val created = alarm.copy(id = alarmIds[index])
                if (selectedTags.isNotEmpty()) {
                    tagDao.insertAlarmTags(
                        selectedTags.map { AlarmTagCrossRef(created.id, it.id) },
                    )
                }
                AlarmCreation(
                    alarm = created,
                    scheduled = AlarmScheduler.schedule(app, created),
                )
            }
        } else {
            emptyList()
        }

        return EventCreation(events = occurrences, alarms = alarmCreations)
    }

    suspend fun updateAlarm(
        alarm: Alarm,
        time: Int,
        fireDate: Long?,
        name: String,
        note: String,
        enabled: Boolean,
        tags: List<Tag>,
    ): AlarmCreation {
        val selectedTags = tags.take(MAX_TAGS_PER_ITEM)
        val updated = alarm.copy(
            time = time,
            fireDate = fireDate,
            name = name,
            note = note,
            enabled = enabled,
        )
        alarmDao.update(updated)
        tagDao.clearAlarmTags(alarm.id)
        if (selectedTags.isNotEmpty()) {
            tagDao.insertAlarmTags(selectedTags.map { AlarmTagCrossRef(alarm.id, it.id) })
        }
        val scheduled = if (enabled) {
            AlarmScheduler.schedule(app, updated)
        } else {
            AlarmScheduler.cancel(app, updated)
            false
        }
        return AlarmCreation(alarm = updated, scheduled = scheduled)
    }

    suspend fun setAlarmEnabled(alarm: Alarm, enabled: Boolean): AlarmCreation {
        val updated = alarm.copy(enabled = enabled)
        alarmDao.update(updated)
        val scheduled = if (enabled) {
            AlarmScheduler.schedule(app, updated)
        } else {
            AlarmScheduler.cancel(app, updated)
            false
        }
        return AlarmCreation(alarm = updated, scheduled = scheduled)
    }

    suspend fun deleteAlarm(alarm: Alarm) {
        AlarmScheduler.cancel(app, alarm)
        tagDao.clearAlarmTags(alarm.id)
        alarmDao.delete(alarm)
    }

    suspend fun updateEventScoped(
        event: Event,
        scope: EventScope,
        title: String,
        note: String,
        date: LocalDate,
        start: LocalTime,
        end: LocalTime,
        withAlarm: Boolean,
        tags: List<Tag>,
    ): EventMutation {
        if (scope == EventScope.THIS_EVENT) {
            return syncSingleEvent(
                event = event,
                title = title,
                note = note,
                date = date,
                start = start,
                end = end,
                withAlarm = withAlarm,
                tags = tags,
            )
        }
        val targets = scopeTargets(event, scope)
        val deltaDays = date.toEpochDay() - event.date
        var result = EventMutation()
        targets.forEach { target ->
            val targetDate = if (scope == EventScope.THIS_AND_SUBSEQUENT) {
                LocalDate.ofEpochDay(target.date + deltaDays)
            } else {
                LocalDate.ofEpochDay(target.date)
            }
            result += syncSingleEvent(
                event = target,
                title = title,
                note = note,
                date = targetDate,
                start = start,
                end = end,
                withAlarm = withAlarm,
                tags = tags,
            )
        }
        return result
    }

    suspend fun deleteEventScoped(
        event: Event,
        scope: EventScope,
        deleteAlarms: Boolean,
    ): EventMutation {
        var result = EventMutation()
        scopeTargets(event, scope).forEach { target ->
            result += deleteSingleEvent(target, deleteAlarms)
        }
        return result
    }

    private suspend fun scopeTargets(event: Event, scope: EventScope): List<Event> {
        if (event.seriesId == 0L || scope == EventScope.THIS_EVENT) {
            return listOf(event)
        }
        val series = eventDao.getBySeries(event.seriesId)
        return when (scope) {
            EventScope.THIS_EVENT -> listOf(event)
            EventScope.THIS_AND_SUBSEQUENT ->
                series.filter { it.occurrenceIndex >= event.occurrenceIndex }

            EventScope.ENTIRE_SERIES -> series
        }.sortedBy { it.occurrenceIndex }
    }

    private suspend fun syncSingleEvent(
        event: Event,
        title: String,
        note: String,
        date: LocalDate,
        start: LocalTime,
        end: LocalTime,
        withAlarm: Boolean,
        tags: List<Tag>,
    ): EventMutation {
        val selectedTags = tags.take(MAX_TAGS_PER_ITEM)
        val updated = event.copy(
            title = title,
            note = note,
            date = date.toEpochDay(),
            start = start.toSecondOfDay(),
            end = end.toSecondOfDay(),
        )
        eventDao.update(updated)

        tagDao.clearEventTags(event.id)
        if (selectedTags.isNotEmpty()) {
            tagDao.insertEventTags(selectedTags.map { EventTagCrossRef(event.id, it.id) })
        }

        var mutation = EventMutation(affectedEvents = 1)
        val linkedAlarm = alarmDao.getByEventId(event.id)
        when {
            withAlarm && linkedAlarm == null -> {
                val alarmId = alarmDao.insert(
                    Alarm(
                        time = updated.start,
                        fireDate = updated.date,
                        eventId = event.id,
                        name = title,
                        note = note,
                    ),
                )
                if (selectedTags.isNotEmpty()) {
                    tagDao.insertAlarmTags(
                        selectedTags.map { AlarmTagCrossRef(alarmId, it.id) },
                    )
                }
                val created = Alarm(
                    id = alarmId,
                    time = updated.start,
                    fireDate = updated.date,
                    eventId = event.id,
                    name = title,
                    note = note,
                )
                val scheduled = AlarmScheduler.schedule(app, created)
                mutation = mutation.copy(
                    alarmsChanged = 1,
                    scheduledAlarms = if (scheduled) 1 else 0,
                    unscheduledAlarms = if (scheduled) 0 else 1,
                )
            }

            withAlarm && linkedAlarm != null -> {
                val synced = linkedAlarm.copy(
                    time = updated.start,
                    fireDate = updated.date,
                    name = title,
                    note = note,
                    enabled = true,
                )
                alarmDao.update(synced)
                tagDao.clearAlarmTags(synced.id)
                if (selectedTags.isNotEmpty()) {
                    tagDao.insertAlarmTags(
                        selectedTags.map { AlarmTagCrossRef(synced.id, it.id) },
                    )
                }
                val scheduled = AlarmScheduler.schedule(app, synced)
                mutation = mutation.copy(
                    alarmsChanged = 1,
                    scheduledAlarms = if (scheduled) 1 else 0,
                    unscheduledAlarms = if (scheduled) 0 else 1,
                )
            }

            !withAlarm && linkedAlarm != null -> {
                AlarmScheduler.cancel(app, linkedAlarm)
                tagDao.clearAlarmTags(linkedAlarm.id)
                alarmDao.delete(linkedAlarm)
                mutation = mutation.copy(deletedAlarms = 1)
            }
        }
        return mutation
    }

    private suspend fun deleteSingleEvent(event: Event, deleteAlarms: Boolean): EventMutation {
        var mutation = EventMutation(affectedEvents = 1)
        if (deleteAlarms) {
            alarmDao.getByEventId(event.id)?.let { alarm ->
                AlarmScheduler.cancel(app, alarm)
                tagDao.clearAlarmTags(alarm.id)
                alarmDao.delete(alarm)
                mutation = mutation.copy(deletedAlarms = 1)
            }
        }
        tagDao.clearEventTags(event.id)
        eventDao.delete(event)
        return mutation
    }
}
