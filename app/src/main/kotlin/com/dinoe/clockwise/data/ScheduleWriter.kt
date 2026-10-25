package com.dinoe.clockwise.data

import android.content.Context
import androidx.room.withTransaction
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.alarm.AlarmScheduler
import com.dinoe.clockwise.alarm.ScheduleResult
import java.time.LocalDate
import java.time.LocalTime

data class AlarmCreation(
    val alarm: Alarm,
    val scheduleResult: ScheduleResult,
) {
    val scheduled: Boolean get() = scheduleResult is ScheduleResult.Scheduled
}

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

enum class ScheduleLimit {
    OCCURRENCES,
    ACTIVE_ALARMS,
}

class ScheduleLimitException(
    val kind: ScheduleLimit,
    val requested: Int,
    val maximum: Int,
) : Exception("Schedule limit exceeded: $kind ($requested > $maximum)")

/**
 * Single place that turns a validated alarm/event request into Room rows and scheduled alarms.
 * All multi-row persistence is transactional. AlarmManager side effects are applied after the
 * database commit so a rolled-back transaction cannot leave orphaned system alarms.
 */
class ScheduleWriter(context: Context) {
    private val app = context.applicationContext as ClockwiseApp
    private val database = app.database
    private val eventDao = database.eventDao()
    private val alarmDao = database.alarmDao()
    private val tagDao = database.tagDao()

    suspend fun addAlarm(
        time: Int,
        fireDate: Long? = null,
        eventId: Long? = null,
        name: String = "",
        note: String = "",
        enabled: Boolean = true,
        oneTime: Boolean = false,
        tags: List<Tag> = emptyList(),
    ): AlarmCreation {
        require(time in 0..86_399) { "Invalid time of day: $time" }
        val selectedTags = tags.take(MAX_TAGS_PER_ITEM)
        val alarm = database.withTransaction {
            if (enabled) ensureAlarmCapacity(1)
            val id = alarmDao.insert(
                Alarm(
                    time = time,
                    fireDate = fireDate,
                    eventId = eventId,
                    name = name,
                    note = note,
                    enabled = enabled,
                    oneTime = oneTime,
                ),
            )
            if (selectedTags.isNotEmpty()) {
                tagDao.insertAlarmTags(selectedTags.map { AlarmTagCrossRef(id, it.id) })
            }
            Alarm(
                id = id,
                time = time,
                fireDate = fireDate,
                eventId = eventId,
                name = name,
                note = note,
                enabled = enabled,
                oneTime = oneTime,
            )
        }
        return finalizeAlarm(alarm)
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
        require(start < end) { "Event end must be after its start" }
        val selectedTags = tags.take(MAX_TAGS_PER_ITEM)
        val repeatPlan = planRecurrence(date, repeat)
        if (repeatPlan.limitReached) {
            throw ScheduleLimitException(
                kind = ScheduleLimit.OCCURRENCES,
                requested = MAX_OCCURRENCES_PER_SERIES + 1,
                maximum = MAX_OCCURRENCES_PER_SERIES,
            )
        }
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
        val occurrenceCount = 1 + repeatPlan.dates.size

        val persisted = database.withTransaction {
            if (withAlarm) ensureAlarmCapacity(occurrenceCount)
            val occurrences = if (repeat.type == Event.REPEAT_NONE) {
                val eventId = eventDao.insert(baseEvent)
                listOf(baseEvent.copy(id = eventId))
            } else {
                val sourceId = eventDao.insert(baseEvent)
                val source = baseEvent.copy(id = sourceId, seriesId = sourceId)
                eventDao.update(source)
                val following = repeatPlan.dates.mapIndexed { index, occurrenceDate ->
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

            val alarms = if (withAlarm) {
                val drafts = occurrences.map { occurrence ->
                    Alarm(
                        time = occurrence.start,
                        fireDate = occurrence.date,
                        eventId = occurrence.id,
                        name = title,
                        note = note,
                    )
                }
                val alarmIds = alarmDao.insertAll(drafts)
                drafts.mapIndexed { index, alarm ->
                    val created = alarm.copy(id = alarmIds[index])
                    if (selectedTags.isNotEmpty()) {
                        tagDao.insertAlarmTags(
                            selectedTags.map { AlarmTagCrossRef(created.id, it.id) },
                        )
                    }
                    created
                }
            } else {
                emptyList()
            }
            occurrences to alarms
        }

        val alarmCreations = persisted.second.map { alarm -> finalizeAlarm(alarm) }
        return EventCreation(events = persisted.first, alarms = alarmCreations)
    }

    suspend fun updateAlarm(
        alarm: Alarm,
        time: Int,
        fireDate: Long?,
        name: String,
        note: String,
        enabled: Boolean,
        oneTime: Boolean = alarm.oneTime,
        tags: List<Tag>,
    ): AlarmCreation {
        require(time in 0..86_399) { "Invalid time of day: $time" }
        val selectedTags = tags.take(MAX_TAGS_PER_ITEM)
        val updated = database.withTransaction {
            if (enabled && !alarm.enabled) ensureAlarmCapacity(1)
            val row = alarm.copy(
                time = time,
                fireDate = fireDate,
                name = name,
                note = note,
                enabled = enabled,
                oneTime = oneTime,
                // Editing the alarm invalidates any pending snooze for the old settings.
                snoozedUntil = null,
            )
            alarmDao.update(row)
            tagDao.clearAlarmTags(alarm.id)
            if (selectedTags.isNotEmpty()) {
                tagDao.insertAlarmTags(selectedTags.map { AlarmTagCrossRef(alarm.id, it.id) })
            }
            row
        }
        return finalizeAlarm(updated)
    }

    suspend fun setAlarmEnabled(alarm: Alarm, enabled: Boolean): AlarmCreation {
        val updated = database.withTransaction {
            if (enabled && !alarm.enabled) ensureAlarmCapacity(1)
            val row = alarm.copy(enabled = enabled, snoozedUntil = null)
            alarmDao.update(row)
            row
        }
        return finalizeAlarm(updated)
    }

    suspend fun deleteAlarm(alarm: Alarm) {
        database.withTransaction {
            tagDao.clearAlarmTags(alarm.id)
            alarmDao.delete(alarm)
        }
        AlarmScheduler.cancel(app, alarm)
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
        require(start < end) { "Event end must be after its start" }
        if (scope == EventScope.THIS_EVENT) {
            return applyMutation { effects ->
                syncSingleEvent(
                    event = event,
                    title = title,
                    note = note,
                    date = date,
                    start = start,
                    end = end,
                    withAlarm = withAlarm,
                    tags = tags,
                    effects = effects,
                )
            }
        }

        val deltaDays = date.toEpochDay() - event.date
        val effects = mutableListOf<AlarmEffect>()
        val mutation = applyMutationMutation(effects) {
            val targets = scopeTargets(event, scope)
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
                    effects = effects,
                )
            }
            result
        }
        return mutation
    }

    suspend fun deleteEventScoped(
        event: Event,
        scope: EventScope,
        deleteAlarms: Boolean,
    ): EventMutation {
        val effects = mutableListOf<AlarmEffect>()
        return applyMutationMutation(effects) {
            var result = EventMutation()
            scopeTargets(event, scope).forEach { target ->
                result += deleteSingleEvent(target, deleteAlarms, effects)
            }
            result
        }
    }

    /**
     * Cleans alarms whose linked event no longer exists. This is a safety net for databases
     * written by older versions; normal deletes detach or remove their alarms in the same
     * transaction.
     */
    suspend fun repairOrphanedAlarms(): Int {
        val orphaned = alarmDao.getOrphanedEventAlarms()
        if (orphaned.isEmpty()) return 0
        orphaned.forEach { alarm ->
            AlarmScheduler.cancel(app, alarm)
            tagDao.clearAlarmTags(alarm.id)
        }
        return database.withTransaction {
            alarmDao.deleteOrphanedEventAlarms()
        }
    }

    private suspend fun finalizeAlarm(alarm: Alarm): AlarmCreation {
        if (!alarm.enabled) {
            AlarmScheduler.cancel(app, alarm)
            return AlarmCreation(alarm, ScheduleResult.Disabled)
        }
        val result = AlarmScheduler.schedule(app, alarm)
        if (result is ScheduleResult.Scheduled) {
            return AlarmCreation(alarm, result)
        }
        // Do not leave an enabled row that has no corresponding system alarm.
        val disabled = alarm.copy(enabled = false, snoozedUntil = null)
        AlarmScheduler.cancel(app, disabled)
        alarmDao.update(disabled)
        return AlarmCreation(disabled, result)
    }

    private suspend fun ensureAlarmCapacity(additional: Int) {
        val current = alarmDao.countEnabled()
        if (current + additional > MAX_ACTIVE_ALARMS) {
            throw ScheduleLimitException(
                kind = ScheduleLimit.ACTIVE_ALARMS,
                requested = current + additional,
                maximum = MAX_ACTIVE_ALARMS,
            )
        }
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
        effects: MutableList<AlarmEffect>,
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
                ensureAlarmCapacity(1)
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
                effects += AlarmEffect.Schedule(created)
                mutation = mutation.copy(alarmsChanged = 1)
            }

            withAlarm && linkedAlarm != null -> {
                if (!linkedAlarm.enabled) ensureAlarmCapacity(1)
                val synced = linkedAlarm.copy(
                    time = updated.start,
                    fireDate = updated.date,
                    name = title,
                    note = note,
                    enabled = true,
                    snoozedUntil = null,
                )
                alarmDao.update(synced)
                tagDao.clearAlarmTags(synced.id)
                if (selectedTags.isNotEmpty()) {
                    tagDao.insertAlarmTags(
                        selectedTags.map { AlarmTagCrossRef(synced.id, it.id) },
                    )
                }
                effects += AlarmEffect.Schedule(synced)
                mutation = mutation.copy(alarmsChanged = 1)
            }

            !withAlarm && linkedAlarm != null -> {
                effects += AlarmEffect.Cancel(linkedAlarm)
                tagDao.clearAlarmTags(linkedAlarm.id)
                alarmDao.delete(linkedAlarm)
                mutation = mutation.copy(deletedAlarms = 1)
            }
        }
        return mutation
    }

    private suspend fun deleteSingleEvent(
        event: Event,
        deleteAlarms: Boolean,
        effects: MutableList<AlarmEffect>,
    ): EventMutation {
        var mutation = EventMutation(affectedEvents = 1)
        alarmDao.getByEventId(event.id)?.let { alarm ->
            if (deleteAlarms) {
                effects += AlarmEffect.Cancel(alarm)
                tagDao.clearAlarmTags(alarm.id)
                alarmDao.delete(alarm)
                mutation = mutation.copy(deletedAlarms = 1)
            } else {
                // Keep the alarm as a standalone alarm rather than an orphan pointing at a
                // deleted event.
                alarmDao.update(alarm.copy(eventId = null))
            }
        }
        tagDao.clearEventTags(event.id)
        eventDao.delete(event)
        return mutation
    }

    private suspend fun applyMutation(
        block: suspend (MutableList<AlarmEffect>) -> EventMutation,
    ): EventMutation {
        val effects = mutableListOf<AlarmEffect>()
        return applyMutationMutation(effects) { block(effects) }
    }

    private suspend fun applyMutationMutation(
        effects: MutableList<AlarmEffect>,
        block: suspend () -> EventMutation,
    ): EventMutation {
        val mutation = database.withTransaction { block() }
        var scheduled = 0
        var unscheduled = 0
        effects.forEach { effect ->
            when (effect) {
                is AlarmEffect.Cancel -> AlarmScheduler.cancel(app, effect.alarm)
                is AlarmEffect.Schedule -> {
                    val result = AlarmScheduler.schedule(app, effect.alarm)
                    if (result is ScheduleResult.Scheduled) {
                        scheduled++
                    } else {
                        unscheduled++
                        alarmDao.update(effect.alarm.copy(enabled = false, snoozedUntil = null))
                    }
                }
            }
        }
        return mutation.copy(
            scheduledAlarms = mutation.scheduledAlarms + scheduled,
            unscheduledAlarms = mutation.unscheduledAlarms + unscheduled,
        )
    }

    private sealed interface AlarmEffect {
        data class Schedule(val alarm: Alarm) : AlarmEffect

        data class Cancel(val alarm: Alarm) : AlarmEffect
    }
}
