package com.dinoe.clockwise.data

import android.content.Context
import androidx.room.withTransaction
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.alarm.AlarmScheduler
import com.dinoe.clockwise.alarm.ReminderScheduler
import com.dinoe.clockwise.alarm.ScheduleResult
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

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

    /**
     * Creates several plain alarms that share one stack id, so the alarm list shows them as a
     * single expandable card. The fields that are not per-time (name, note, one-time, tags) are
     * shared by every alarm in the stack.
     */
    suspend fun addAlarmStack(
        times: List<Int>,
        name: String = "",
        note: String = "",
        enabled: Boolean = true,
        oneTime: Boolean = false,
        tags: List<Tag> = emptyList(),
    ): List<AlarmCreation> {
        require(times.size >= 2) { "An alarm stack needs at least two times" }
        require(times.all { it in 0..86_399 }) { "Invalid time of day: $times" }
        val orderedTimes = times.distinct().sorted()
        require(orderedTimes.size == times.size) { "Alarm stack times must be unique" }
        val selectedTags = tags.take(MAX_TAGS_PER_ITEM)
        val stackId = Random.nextLong(1, Long.MAX_VALUE)
        val persisted = database.withTransaction {
            if (enabled) ensureAlarmCapacity(orderedTimes.size)
            val drafts = orderedTimes.map { time ->
                Alarm(
                    time = time,
                    name = name,
                    note = note,
                    enabled = enabled,
                    oneTime = oneTime,
                    stackId = stackId,
                )
            }
            val ids = alarmDao.insertAll(drafts)
            val alarms = drafts.mapIndexed { index, alarm -> alarm.copy(id = ids[index]) }
            if (selectedTags.isNotEmpty()) {
                alarms.forEach { alarm ->
                    tagDao.insertAlarmTags(
                        selectedTags.map { AlarmTagCrossRef(alarm.id, it.id) },
                    )
                }
            }
            alarms
        }
        return persisted.map { alarm -> finalizeAlarm(alarm) }
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
        reminderMinutes: Int = Event.REMINDER_OFF,
    ): EventCreation {
        require(start < end) { "Event end must be after its start" }
        require(isValidReminder(reminderMinutes)) { "Invalid reminder: $reminderMinutes" }
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
            reminderMinutes = reminderMinutes,
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
        persisted.first.forEach { event -> ReminderScheduler.schedule(app, event) }
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
        reminderMinutes: Int? = null,
    ): EventMutation {
        require(start < end) { "Event end must be after its start" }
        if (reminderMinutes != null) {
            require(isValidReminder(reminderMinutes)) { "Invalid reminder: $reminderMinutes" }
        }
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
                    reminderMinutes = reminderMinutes,
                    effects = effects,
                )
            }
        }

        val deltaDays = date.toEpochDay() - event.date
        val effects = mutableListOf<SchedulerEffect>()
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
                    reminderMinutes = reminderMinutes,
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
        val effects = mutableListOf<SchedulerEffect>()
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
        reminderMinutes: Int?,
        effects: MutableList<SchedulerEffect>,
    ): EventMutation {
        val selectedTags = tags.take(MAX_TAGS_PER_ITEM)
        val updatedReminder = reminderMinutes ?: event.reminderMinutes
        val updated = event.copy(
            title = title,
            note = note,
            date = date.toEpochDay(),
            start = start.toSecondOfDay(),
            end = end.toSecondOfDay(),
            reminderMinutes = updatedReminder,
        )
        eventDao.update(updated)
        effects += SchedulerEffect.CancelReminder(event)
        if (updatedReminder != Event.REMINDER_OFF) {
            effects += SchedulerEffect.ScheduleReminder(updated)
        }

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
                effects += SchedulerEffect.Schedule(created)
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
                effects += SchedulerEffect.Schedule(synced)
                mutation = mutation.copy(alarmsChanged = 1)
            }

            !withAlarm && linkedAlarm != null -> {
                effects += SchedulerEffect.Cancel(linkedAlarm)
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
        effects: MutableList<SchedulerEffect>,
    ): EventMutation {
        var mutation = EventMutation(affectedEvents = 1)
        effects += SchedulerEffect.CancelReminder(event)
        alarmDao.getByEventId(event.id)?.let { alarm ->
            if (deleteAlarms) {
                effects += SchedulerEffect.Cancel(alarm)
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
        block: suspend (MutableList<SchedulerEffect>) -> EventMutation,
    ): EventMutation {
        val effects = mutableListOf<SchedulerEffect>()
        return applyMutationMutation(effects) { block(effects) }
    }

    private suspend fun applyMutationMutation(
        effects: MutableList<SchedulerEffect>,
        block: suspend () -> EventMutation,
    ): EventMutation {
        val mutation = database.withTransaction { block() }
        var scheduled = 0
        var unscheduled = 0
        effects.forEach { effect ->
            when (effect) {
                is SchedulerEffect.Cancel -> AlarmScheduler.cancel(app, effect.alarm)
                is SchedulerEffect.Schedule -> {
                    val result = AlarmScheduler.schedule(app, effect.alarm)
                    if (result is ScheduleResult.Scheduled) {
                        scheduled++
                    } else {
                        unscheduled++
                        alarmDao.update(effect.alarm.copy(enabled = false, snoozedUntil = null))
                    }
                }

                is SchedulerEffect.ScheduleReminder -> ReminderScheduler.schedule(app, effect.event)
                is SchedulerEffect.CancelReminder -> ReminderScheduler.cancel(app, effect.event.id)
            }
        }
        return mutation.copy(
            scheduledAlarms = mutation.scheduledAlarms + scheduled,
            unscheduledAlarms = mutation.unscheduledAlarms + unscheduled,
        )
    }

    private fun isValidReminder(reminderMinutes: Int): Boolean =
        reminderMinutes == Event.REMINDER_OFF ||
            reminderMinutes in Event.REMINDER_MINUTE_OPTIONS

    private sealed interface SchedulerEffect {
        data class Schedule(val alarm: Alarm) : SchedulerEffect

        data class Cancel(val alarm: Alarm) : SchedulerEffect

        data class ScheduleReminder(val event: Event) : SchedulerEffect

        data class CancelReminder(val event: Event) : SchedulerEffect
    }
}
