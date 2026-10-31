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
        ringtone: String = Alarm.DEFAULT_RINGTONE,
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
                    ringtone = ringtone,
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
                ringtone = ringtone,
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
        ringtone: String = Alarm.DEFAULT_RINGTONE,
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
                    ringtone = ringtone,
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
        endDayOffset: Int = 0,
        allDay: Boolean = false,
    ): EventCreation {
        require(endDayOffset in 0..Event.MAX_EVENT_DAY_SPAN) {
            "Event end day offset is out of range"
        }
        require(allDay || endDayOffset > 0 || start <= end) {
            "Event end must not be before its start"
        }
        val alarmRequested = withAlarm && !allDay
        val effectiveReminder = if (allDay) Event.REMINDER_OFF else reminderMinutes
        require(isValidReminder(effectiveReminder)) { "Invalid reminder: $reminderMinutes" }
        val startSeconds = if (allDay) 0 else start.toSecondOfDay()
        val endSeconds = if (allDay) {
            0
        } else {
            end.toSecondOfDay() + endDayOffset * Event.SECONDS_PER_DAY
        }
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
            start = startSeconds,
            end = endSeconds,
            repeatType = repeat.type,
            repeatDays = repeatDays,
            reminderMinutes = effectiveReminder,
            allDay = allDay,
        )
        val occurrenceCount = 1 + repeatPlan.dates.size

        val persisted = database.withTransaction {
            if (alarmRequested) ensureAlarmCapacity(occurrenceCount)
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

            if (selectedTags.isNotEmpty()) {
                tagDao.insertEventTags(
                    occurrences.flatMap { occurrence ->
                        selectedTags.map { EventTagCrossRef(occurrence.id, it.id) }
                    },
                )
            }

            val alarms = if (alarmRequested) {
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
                val created = drafts.mapIndexed { index, alarm ->
                    alarm.copy(id = alarmIds[index])
                }
                if (selectedTags.isNotEmpty()) {
                    tagDao.insertAlarmTags(
                        created.flatMap { alarm ->
                            selectedTags.map { AlarmTagCrossRef(alarm.id, it.id) }
                        },
                    )
                }
                created
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
        ringtone: String = alarm.ringtone,
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
                ringtone = ringtone,
                // Editing the alarm invalidates any pending snooze for the old settings.
                snoozedUntil = null,
            )
            alarmDao.update(row)
            // A stack rings with one shared sound, so the changed ringtone applies to every
            // member. Only the edited row loses its snooze; siblings keep theirs.
            alarm.stackId?.let { stackId -> alarmDao.setStackRingtone(stackId, ringtone) }
            tagDao.clearAlarmTags(alarm.id)
            if (selectedTags.isNotEmpty()) {
                tagDao.insertAlarmTags(selectedTags.map { AlarmTagCrossRef(alarm.id, it.id) })
            }
            row
        }
        return finalizeAlarm(updated)
    }

    /**
     * Changes only the sound, so editing an auto-created alarm's ringtone does not touch its
     * time, name, snooze, or event link. A stack still shares one ringtone.
     */
    suspend fun updateAlarmRingtone(alarm: Alarm, ringtone: String) {
        database.withTransaction {
            alarmDao.update(alarm.copy(ringtone = ringtone))
            alarm.stackId?.let { stackId -> alarmDao.setStackRingtone(stackId, ringtone) }
        }
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
        endDayOffset: Int = event.endDayOffset,
        allDay: Boolean = event.allDay,
    ): EventMutation {
        require(endDayOffset in 0..Event.MAX_EVENT_DAY_SPAN) {
            "Event end day offset is out of range"
        }
        require(allDay || endDayOffset > 0 || start <= end) {
            "Event end must not be before its start"
        }
        if (reminderMinutes != null) {
            require(isValidReminder(reminderMinutes)) { "Invalid reminder: $reminderMinutes" }
        }
        // Every covered occurrence moves by the same number of days, so the whole series edit is
        // one relative date update.
        val dateShift = if (scope == EventScope.ENTIRE_SERIES) {
            0L
        } else {
            date.toEpochDay() - event.date
        }
        val effects = mutableListOf<SchedulerEffect>()
        return applyMutationMutation(effects) {
            syncEvents(
                targets = scopeTargets(event, scope),
                dateShift = dateShift,
                title = title,
                note = note,
                start = start,
                end = end,
                withAlarm = withAlarm,
                tags = tags,
                reminderMinutes = reminderMinutes,
                endDayOffset = endDayOffset,
                allDay = allDay,
                effects = effects,
            )
        }
    }

    suspend fun deleteEventScoped(
        event: Event,
        scope: EventScope,
        deleteAlarms: Boolean,
    ): EventMutation {
        val effects = mutableListOf<SchedulerEffect>()
        return applyMutationMutation(effects) {
            val targets = scopeTargets(event, scope)
            if (targets.isEmpty()) return@applyMutationMutation EventMutation()
            val ids = targets.map { it.id }
            val linkedAlarms = linkedAlarmsByEventId(ids)
            val linked = targets.mapNotNull { linkedAlarms[it.id] }
            if (deleteAlarms) {
                if (linked.isNotEmpty()) {
                    tagDao.clearAlarmTagsByIds(linked.map { it.id })
                    alarmDao.deleteByIds(linked.map { it.id })
                }
            } else if (linked.isNotEmpty()) {
                // Keep the alarms as standalone alarms rather than orphans pointing at a deleted
                // event.
                alarmDao.detachFromEvents(linked.map { it.id })
            }
            tagDao.clearEventTagsByIds(ids)
            eventDao.deleteByIds(ids)
            targets.forEach { target ->
                effects += SchedulerEffect.CancelReminder(target)
                if (deleteAlarms) {
                    linkedAlarms[target.id]?.let { alarm ->
                        effects += SchedulerEffect.Cancel(alarm)
                    }
                }
            }
            EventMutation(
                affectedEvents = targets.size,
                deletedAlarms = if (deleteAlarms) linked.size else 0,
            )
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

    /** First alarm linked to each of [eventIds]; the same single alarm the per-event lookup used. */
    private suspend fun linkedAlarmsByEventId(eventIds: List<Long>): Map<Long, Alarm> {
        if (eventIds.isEmpty()) return emptyMap()
        return alarmDao.getByEventIds(eventIds)
            .groupBy { it.eventId }
            .mapNotNull { (eventId, alarms) -> eventId?.let { it to alarms.first() } }
            .toMap()
    }

    /**
     * Applies one edit to every occurrence in [targets]. The rows are written with a few bulk
     * statements, and the scheduler effects stay in occurrence order so results stay deterministic.
     */
    private suspend fun syncEvents(
        targets: List<Event>,
        dateShift: Long,
        title: String,
        note: String,
        start: LocalTime,
        end: LocalTime,
        withAlarm: Boolean,
        tags: List<Tag>,
        reminderMinutes: Int?,
        endDayOffset: Int,
        allDay: Boolean,
        effects: MutableList<SchedulerEffect>,
    ): EventMutation {
        if (targets.isEmpty()) return EventMutation()
        val selectedTags = tags.take(MAX_TAGS_PER_ITEM)
        val alarmRequested = withAlarm && !allDay
        val startSeconds = if (allDay) 0 else start.toSecondOfDay()
        val endSeconds = if (allDay) {
            0
        } else {
            end.toSecondOfDay() + endDayOffset * Event.SECONDS_PER_DAY
        }
        val targetIds = targets.map { it.id }
        val linkedAlarms = linkedAlarmsByEventId(targetIds)

        if (alarmRequested) {
            val newlyEnabled = targets.count { linkedAlarms[it.id]?.enabled != true }
            if (newlyEnabled > 0) ensureAlarmCapacity(newlyEnabled)
        }

        // One statement per distinct reminder value; normally a single statement per series.
        targets.groupBy { updatedReminder(it, reminderMinutes, allDay) }
            .forEach { (reminder, group) ->
                eventDao.updateEventFields(
                    ids = group.map { it.id },
                    title = title,
                    note = note,
                    start = startSeconds,
                    end = endSeconds,
                    reminderMinutes = reminder,
                    allDay = allDay,
                    dateShift = dateShift,
                )
            }

        tagDao.clearEventTagsByIds(targetIds)
        if (selectedTags.isNotEmpty()) {
            tagDao.insertEventTags(eventTagRefs(targetIds, selectedTags))
        }

        val syncedAlarms = mutableMapOf<Long, Alarm>()
        val createdAlarms = mutableMapOf<Long, Alarm>()
        val removedAlarms = mutableMapOf<Long, Alarm>()
        if (alarmRequested) {
            val withAlarmTargets = targets.filter { linkedAlarms[it.id] != null }
            if (withAlarmTargets.isNotEmpty()) {
                alarmDao.syncEventAlarms(
                    eventIds = withAlarmTargets.map { it.id },
                    time = startSeconds,
                    name = title,
                    note = note,
                )
                withAlarmTargets.forEach { target ->
                    syncedAlarms[target.id] = linkedAlarms.getValue(target.id).copy(
                        time = startSeconds,
                        fireDate = target.date + dateShift,
                        name = title,
                        note = note,
                        enabled = true,
                        snoozedUntil = null,
                    )
                }
            }
            val missingTargets = targets.filter { linkedAlarms[it.id] == null }
            if (missingTargets.isNotEmpty()) {
                val drafts = missingTargets.map { target ->
                    Alarm(
                        time = startSeconds,
                        fireDate = target.date + dateShift,
                        eventId = target.id,
                        name = title,
                        note = note,
                    )
                }
                val alarmIds = alarmDao.insertAll(drafts)
                drafts.forEachIndexed { index, draft ->
                    createdAlarms[draft.eventId ?: 0L] = draft.copy(id = alarmIds[index])
                }
            }
            if (syncedAlarms.isNotEmpty()) {
                tagDao.clearAlarmTagsByIds(syncedAlarms.values.map { it.id })
            }
            if (selectedTags.isNotEmpty()) {
                val alarmIds = syncedAlarms.values.map { it.id } +
                    createdAlarms.values.map { it.id }
                tagDao.insertAlarmTags(alarmTagRefs(alarmIds, selectedTags))
            }
        } else {
            val removed = targets.mapNotNull { linkedAlarms[it.id] }
            if (removed.isNotEmpty()) {
                tagDao.clearAlarmTagsByIds(removed.map { it.id })
                alarmDao.deleteByIds(removed.map { it.id })
                removed.forEach { alarm -> alarm.eventId?.let { removedAlarms[it] = alarm } }
            }
        }

        targets.forEach { target ->
            effects += SchedulerEffect.CancelReminder(target)
            val reminder = updatedReminder(target, reminderMinutes, allDay)
            if (reminder != Event.REMINDER_OFF) {
                effects += SchedulerEffect.ScheduleReminder(
                    target.copy(
                        title = title,
                        note = note,
                        date = target.date + dateShift,
                        start = startSeconds,
                        end = endSeconds,
                        reminderMinutes = reminder,
                        allDay = allDay,
                    ),
                )
            }
            (syncedAlarms[target.id] ?: createdAlarms[target.id])?.let { alarm ->
                effects += SchedulerEffect.Schedule(alarm)
            }
            removedAlarms[target.id]?.let { alarm -> effects += SchedulerEffect.Cancel(alarm) }
        }

        return EventMutation(
            affectedEvents = targets.size,
            alarmsChanged = syncedAlarms.size + createdAlarms.size,
            deletedAlarms = removedAlarms.size,
        )
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

    private fun updatedReminder(event: Event, reminderMinutes: Int?, allDay: Boolean): Int =
        if (allDay) Event.REMINDER_OFF else reminderMinutes ?: event.reminderMinutes

    private fun eventTagRefs(eventIds: List<Long>, tags: List<Tag>): List<EventTagCrossRef> =
        eventIds.flatMap { eventId -> tags.map { EventTagCrossRef(eventId, it.id) } }

    private fun alarmTagRefs(alarmIds: List<Long>, tags: List<Tag>): List<AlarmTagCrossRef> =
        alarmIds.flatMap { alarmId -> tags.map { AlarmTagCrossRef(alarmId, it.id) } }

    private fun isValidReminder(reminderMinutes: Int): Boolean =
        Event.isValidReminderMinutes(reminderMinutes)

    private sealed interface SchedulerEffect {
        data class Schedule(val alarm: Alarm) : SchedulerEffect

        data class Cancel(val alarm: Alarm) : SchedulerEffect

        data class ScheduleReminder(val event: Event) : SchedulerEffect

        data class CancelReminder(val event: Event) : SchedulerEffect
    }
}
