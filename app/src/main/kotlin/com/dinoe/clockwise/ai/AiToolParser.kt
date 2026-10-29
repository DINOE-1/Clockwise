package com.dinoe.clockwise.ai

import com.dinoe.clockwise.data.Alarm
import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.data.EventScope
import com.dinoe.clockwise.data.MAX_OCCURRENCES_PER_SERIES
import com.dinoe.clockwise.data.RecurrenceRule
import com.dinoe.clockwise.data.planRecurrence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class TagRef(
    val id: Long,
    val name: String,
)

data class AlarmDraft(
    val time: LocalTime,
    val date: LocalDate?,
    val name: String,
    val note: String,
    val enabled: Boolean,
    val oneTime: Boolean,
    val tags: List<TagRef>,
)

data class AlarmStackDraft(
    val times: List<LocalTime>,
    val name: String,
    val note: String,
    val enabled: Boolean,
    val oneTime: Boolean,
    val tags: List<TagRef>,
)

data class EventDraft(
    val title: String,
    val note: String,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val withAlarm: Boolean,
    val repeatType: Int,
    val repeatDays: Set<Int>,
    val repeatUntil: LocalDate?,
    val occurrenceCount: Int,
    val reminderMinutes: Int,
    val tags: List<TagRef>,
    val endDayOffset: Int = 0,
    val allDay: Boolean = false,
) {
    fun recurrenceRule(): RecurrenceRule = RecurrenceRule(
        type = repeatType,
        days = repeatDays,
        untilEpochDay = repeatUntil?.toEpochDay() ?: 0L,
    )
}

data class AlarmUpdateDraft(
    val alarmId: Long,
    val time: LocalTime,
    val fireDate: LocalDate?,
    val name: String,
    val note: String,
    val enabled: Boolean,
    val oneTime: Boolean,
    val tags: List<TagRef>,
    val previousTime: LocalTime,
    val previousFireDate: LocalDate?,
    val previousName: String,
    val previousEnabled: Boolean,
    val previousOneTime: Boolean,
)

data class AlarmDeleteDraft(
    val alarmId: Long,
    val time: LocalTime,
    val fireDate: LocalDate?,
    val name: String,
    val isAuto: Boolean,
)

data class EventUpdateDraft(
    val eventId: Long,
    val scope: EventScope,
    val title: String,
    val note: String,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val withAlarm: Boolean,
    val reminderMinutes: Int?,
    val tags: List<TagRef>,
    val affectedCount: Int,
    val previousTitle: String,
    val previousDate: LocalDate,
    val previousStart: LocalTime,
    val previousEnd: LocalTime,
    val previousWithAlarm: Boolean,
    val previousReminderMinutes: Int,
    val isSeries: Boolean,
    val endDayOffset: Int = 0,
    val allDay: Boolean = false,
    val previousEndDayOffset: Int = 0,
    val previousAllDay: Boolean = false,
)

data class EventDeleteDraft(
    val eventId: Long,
    val scope: EventScope,
    val title: String,
    val date: LocalDate,
    val affectedCount: Int,
    val keepAlarms: Boolean,
    val linkedAlarms: Int,
    val isSeries: Boolean,
)

class AiToolParseException(
    val code: String,
    override val message: String,
) : Exception(message)

/**
 * Parses and validates tool arguments before anything reaches the database. Pure Kotlin with an
 * injected clock so the rules can be unit-tested deterministically.
 */
class AiToolParser(
    private val now: LocalDateTime,
    private val availableTags: Map<String, Long>,
    private val alarmsById: Map<Long, Alarm> = emptyMap(),
    private val eventsById: Map<Long, Event> = emptyMap(),
    private val alarmTagsById: Map<Long, List<TagRef>> = emptyMap(),
    private val eventTagsById: Map<Long, List<TagRef>> = emptyMap(),
) {
    fun parseAlarms(arguments: String): List<AlarmDraft> {
        val root = parseRoot(arguments)
        val items = root.array("alarms")
        if (items.isEmpty()) throw error("empty", "alarms must contain at least one item")
        if (items.size > AiTokenBudget.MAX_BATCH_ITEMS) {
            throw error(
                "too_many_items",
                "create at most ${AiTokenBudget.MAX_BATCH_ITEMS} alarms per call",
            )
        }
        return items.mapIndexed { index, item -> parseAlarm(item, index) }
    }

    fun parseAlarmStacks(arguments: String): List<AlarmStackDraft> {
        val root = parseRoot(arguments)
        val items = root.array("stacks")
        if (items.isEmpty()) throw error("empty", "stacks must contain at least one item")
        if (items.size > AiTokenBudget.MAX_BATCH_ITEMS) {
            throw error(
                "too_many_items",
                "create at most ${AiTokenBudget.MAX_BATCH_ITEMS} alarm stacks per call",
            )
        }
        return items.mapIndexed { index, item -> parseAlarmStack(item, index) }
    }

    fun parseEvents(arguments: String): List<EventDraft> {
        val root = parseRoot(arguments)
        val items = root.array("events")
        if (items.isEmpty()) throw error("empty", "events must contain at least one item")
        if (items.size > AiTokenBudget.MAX_BATCH_ITEMS) {
            throw error(
                "too_many_items",
                "create at most ${AiTokenBudget.MAX_BATCH_ITEMS} events per call",
            )
        }
        return items.mapIndexed { index, item -> parseEvent(item, index) }
    }

    fun parseAlarmUpdates(arguments: String): List<AlarmUpdateDraft> {
        val root = parseRoot(arguments)
        val items = root.array("alarms")
        if (items.isEmpty()) {
            throw error("empty", "alarms must contain at least one item")
        }
        if (items.size > AiTokenBudget.MAX_BATCH_ITEMS) {
            throw error(
                "too_many_items",
                "update at most ${AiTokenBudget.MAX_BATCH_ITEMS} alarms per call",
            )
        }
        return items.mapIndexed { index, item -> parseAlarmUpdate(item, index) }
    }

    fun parseAlarmDeletes(arguments: String): List<AlarmDeleteDraft> {
        val root = parseRoot(arguments)
        val items = root.array("alarmIds")
        if (items.isEmpty()) throw error("empty", "alarmIds must contain at least one id")
        if (items.size > AiTokenBudget.MAX_BATCH_ITEMS) {
            throw error(
                "too_many_items",
                "delete at most ${AiTokenBudget.MAX_BATCH_ITEMS} alarms per call",
            )
        }
        return items.mapIndexed { index, item ->
            val id = (item as? JsonPrimitive)?.longOrNull
                ?: throw error("invalid_id", "alarmIds[$index] must be an integer")
            val alarm = alarmsById[id]
                ?: throw error(
                    "alarm_not_found",
                    "alarm $id was not found; call list_alarms first",
                )
            AlarmDeleteDraft(
                alarmId = alarm.id,
                time = LocalTime.ofSecondOfDay(alarm.time.toLong()),
                fireDate = alarm.fireDate?.let(LocalDate::ofEpochDay),
                name = alarm.name,
                isAuto = alarm.eventId != null,
            )
        }.distinctBy { it.alarmId }
    }

    fun parseEventUpdates(arguments: String): List<EventUpdateDraft> {
        val root = parseRoot(arguments)
        val items = root.array("events")
        if (items.isEmpty()) {
            throw error("empty", "events must contain at least one item")
        }
        if (items.size > AiTokenBudget.MAX_BATCH_ITEMS) {
            throw error(
                "too_many_items",
                "update at most ${AiTokenBudget.MAX_BATCH_ITEMS} events per call",
            )
        }
        return items.mapIndexed { index, item -> parseEventUpdate(item, index) }
    }

    fun parseEventDeletes(arguments: String): List<EventDeleteDraft> {
        val root = parseRoot(arguments)
        val items = root.array("events")
        if (items.isEmpty()) {
            throw error("empty", "events must contain at least one item")
        }
        if (items.size > AiTokenBudget.MAX_BATCH_ITEMS) {
            throw error(
                "too_many_items",
                "delete at most ${AiTokenBudget.MAX_BATCH_ITEMS} events per call",
            )
        }
        return items.mapIndexed { index, item -> parseEventDelete(item, index) }
    }

    private fun parseAlarmUpdate(element: JsonElement, index: Int): AlarmUpdateDraft {
        val item = element as? JsonObject
            ?: throw error("invalid_item", "alarms[$index] must be an object")
        val id = parseId(item, "alarms[$index].id")
        val alarm = alarmsById[id]
            ?: throw error(
                "alarm_not_found",
                "alarm $id was not found; call list_alarms first",
            )
        if (alarm.eventId != null) {
            val allowed = setOf("id", "enabled")
            val invalid = item.keys - allowed
            if (invalid.isNotEmpty()) {
                throw error(
                    "auto_alarm",
                    "alarm $id was created from event ${alarm.eventId}; only enabled can be " +
                        "changed here, update that event for time or details",
                )
            }
        }
        val time = if (item.containsKey("time")) {
            parseTime(item, "time", "alarms[$index].time")
        } else {
            LocalTime.ofSecondOfDay(alarm.time.toLong())
        }
        val previousFireDate = alarm.fireDate?.let(LocalDate::ofEpochDay)
        val clearDate = item.booleanOrNull("clearDate") ?: false
        val dateRaw = item.stringOrNull("date")?.trim()?.takeIf { it.isNotEmpty() }
        if (clearDate && dateRaw != null) {
            throw error("invalid_date", "alarms[$index] cannot set both date and clearDate")
        }
        val fireDate = when {
            clearDate -> null
            dateRaw != null -> parseDate(dateRaw, "alarms[$index].date")
            else -> previousFireDate
        }
        val name = if (item.containsKey("name")) {
            item.stringOrNull("name")?.trim().orEmpty().take(100)
        } else {
            alarm.name
        }
        val note = if (item.containsKey("note")) {
            item.stringOrNull("note")?.trim().orEmpty().take(500)
        } else {
            alarm.note
        }
        val enabled = item.booleanOrNull("enabled") ?: alarm.enabled
        val oneTime = item.booleanOrNull("oneTime") ?: alarm.oneTime
        val tags = if (item.containsKey("tags")) {
            parseTags(item["tags"], "alarms[$index].tags")
        } else {
            alarmTagsById[id].orEmpty()
        }
        return AlarmUpdateDraft(
            alarmId = id,
            time = time,
            fireDate = fireDate,
            name = name,
            note = note,
            enabled = enabled,
            oneTime = oneTime,
            tags = tags,
            previousTime = LocalTime.ofSecondOfDay(alarm.time.toLong()),
            previousFireDate = previousFireDate,
            previousName = alarm.name,
            previousEnabled = alarm.enabled,
            previousOneTime = alarm.oneTime,
        )
    }

    private fun parseEventUpdate(element: JsonElement, index: Int): EventUpdateDraft {
        val item = element as? JsonObject
            ?: throw error("invalid_item", "events[$index] must be an object")
        val id = parseId(item, "events[$index].id")
        val event = eventsById[id]
            ?: throw error(
                "event_not_found",
                "event $id was not found; call list_events first",
            )
        val isSeries = event.seriesId != 0L
        val scope = parseScope(item, isSeries, "events[$index]")
        val previousDate = LocalDate.ofEpochDay(event.date)
        val date = item.stringOrNull("date")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parseDate(it, "events[$index].date")
        } ?: previousDate
        if (scope == EventScope.ENTIRE_SERIES && date != previousDate) {
            throw error(
                "invalid_date",
                "events[$index].date cannot change when scope is entire_series",
            )
        }
        val title = if (item.containsKey("title")) {
            item.stringOrNull("title")?.trim().orEmpty().take(120)
        } else {
            event.title
        }
        if (title.isBlank()) throw error("invalid_title", "events[$index].title must not be blank")
        val note = if (item.containsKey("note")) {
            item.stringOrNull("note")?.trim().orEmpty().take(500)
        } else {
            event.note
        }
        val allDay = item.booleanOrNull("allDay") ?: event.allDay
        val start = if (allDay) {
            LocalTime.MIDNIGHT
        } else if (item.containsKey("start")) {
            parseTime(item, "start", "events[$index].start")
        } else {
            LocalTime.ofSecondOfDay(event.start.toLong())
        }
        val end = if (allDay) {
            LocalTime.MIDNIGHT
        } else if (item.containsKey("end")) {
            parseTime(item, "end", "events[$index].end")
        } else {
            LocalTime.ofSecondOfDay(event.endTimeOfDay.toLong())
        }
        val endDateRaw = item.stringOrNull("endDate")?.trim()?.takeIf { it.isNotEmpty() }
        val endDayOffset = when {
            allDay -> 0
            endDateRaw != null -> {
                val endDate = parseDate(endDateRaw, "events[$index].endDate")
                if (endDate.isBefore(date)) {
                    throw error(
                        "invalid_end_date",
                        "events[$index].endDate must not be before date",
                    )
                }
                (endDate.toEpochDay() - date.toEpochDay()).toInt()
            }

            else -> event.endDayOffset
        }
        if (endDayOffset > Event.MAX_EVENT_DAY_SPAN) {
            throw error(
                "invalid_end_date",
                "events[$index].endDate spans more than ${Event.MAX_EVENT_DAY_SPAN} days",
            )
        }
        if (!allDay && endDayOffset == 0 && end.isBefore(start)) {
            throw error(
                "invalid_time_range",
                "events[$index].end must not be before start unless endDate is a later day",
            )
        }
        val targets = eventTargets(event, scope)
        val targetIds = targets.map { it.id }.toSet()
        val withAlarm = (item.booleanOrNull("withAlarm")
            ?: alarmsById.values.any { it.eventId == event.id }) && !allDay
        val reminderMinutes = if (allDay) {
            Event.REMINDER_OFF
        } else {
            parseReminderMinutes(item, "events[$index].reminderMinutes")
        }
        val tags = if (item.containsKey("tags")) {
            parseTags(item["tags"], "events[$index].tags")
        } else {
            eventTagsById[id].orEmpty()
        }
        return EventUpdateDraft(
            eventId = id,
            scope = scope,
            title = title,
            note = note,
            date = date,
            start = start,
            end = end,
            withAlarm = withAlarm,
            reminderMinutes = reminderMinutes,
            tags = tags,
            affectedCount = targets.size,
            previousTitle = event.title,
            previousDate = previousDate,
            previousStart = LocalTime.ofSecondOfDay(event.start.toLong()),
            previousEnd = LocalTime.ofSecondOfDay(event.endTimeOfDay.toLong()),
            previousWithAlarm = alarmsById.values.any { it.eventId in targetIds },
            previousReminderMinutes = event.reminderMinutes,
            isSeries = isSeries,
            endDayOffset = endDayOffset,
            allDay = allDay,
            previousEndDayOffset = event.endDayOffset,
            previousAllDay = event.allDay,
        )
    }

    private fun parseEventDelete(element: JsonElement, index: Int): EventDeleteDraft {
        val item = element as? JsonObject
            ?: throw error("invalid_item", "events[$index] must be an object")
        val id = parseId(item, "events[$index].id")
        val event = eventsById[id]
            ?: throw error(
                "event_not_found",
                "event $id was not found; call list_events first",
            )
        val isSeries = event.seriesId != 0L
        val scope = parseScope(item, isSeries, "events[$index]")
        val targets = eventTargets(event, scope)
        val targetIds = targets.map { it.id }.toSet()
        val linkedAlarms = alarmsById.values.count { it.eventId in targetIds }
        return EventDeleteDraft(
            eventId = id,
            scope = scope,
            title = event.title,
            date = LocalDate.ofEpochDay(event.date),
            affectedCount = targets.size,
            keepAlarms = item.booleanOrNull("keepAlarms") ?: false,
            linkedAlarms = linkedAlarms,
            isSeries = isSeries,
        )
    }

    private fun parseId(item: JsonObject, label: String): Long {
        val id = (item["id"] as? JsonPrimitive)?.longOrNull
        if (id == null || id <= 0L) throw error("invalid_id", "$label must be a positive integer")
        return id
    }

    private fun parseScope(
        item: JsonObject,
        isSeries: Boolean,
        label: String,
    ): EventScope {
        if (!isSeries) return EventScope.THIS_EVENT
        val raw = item.stringOrNull("scope")?.trim()?.lowercase()?.replace('-', '_')
        return when (raw) {
            null, "", "this", "this_event" -> EventScope.THIS_EVENT
            "this_and_subsequent", "subsequent", "following" ->
                EventScope.THIS_AND_SUBSEQUENT

            "entire_series", "series", "all" -> EventScope.ENTIRE_SERIES
            else -> throw error(
                "invalid_scope",
                "$label.scope must be this, this_and_subsequent or entire_series",
            )
        }
    }

    private fun eventTargets(event: Event, scope: EventScope): List<Event> {
        if (event.seriesId == 0L || scope == EventScope.THIS_EVENT) return listOf(event)
        val series = eventsById.values
            .filter { it.seriesId == event.seriesId }
            .sortedBy { it.occurrenceIndex }
        val targets = when (scope) {
            EventScope.THIS_EVENT -> listOf(event)
            EventScope.THIS_AND_SUBSEQUENT ->
                series.filter { it.occurrenceIndex >= event.occurrenceIndex }

            EventScope.ENTIRE_SERIES -> series
        }
        return targets.ifEmpty { listOf(event) }
    }

    private fun parseAlarm(element: JsonElement, index: Int): AlarmDraft {
        val item = element as? JsonObject
            ?: throw error("invalid_item", "alarms[$index] must be an object")
        val time = parseTime(item, "time", "alarms[$index].time")
        val date = item.stringOrNull("date")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { parseDate(it, "alarms[$index].date") }
        if (date != null && !date.atTime(time).isAfter(now)) {
            throw error(
                "alarm_in_past",
                "alarms[$index] must be in the future; now is ${now.withNano(0)}",
            )
        }
        val name = item.stringOrNull("name")?.trim().orEmpty().take(100)
        val note = item.stringOrNull("note")?.trim().orEmpty().take(500)
        val enabled = item.booleanOrNull("enabled") ?: true
        val oneTime = item.booleanOrNull("oneTime") ?: false
        val tags = parseTags(item["tags"], "alarms[$index].tags")
        return AlarmDraft(
            time = time,
            date = date,
            name = name,
            note = note,
            enabled = enabled,
            oneTime = oneTime,
            tags = tags,
        )
    }

    private fun parseAlarmStack(element: JsonElement, index: Int): AlarmStackDraft {
        val item = element as? JsonObject
            ?: throw error("invalid_item", "stacks[$index] must be an object")
        val values = item.arrayOrNull("times")
            ?: throw error(
                "invalid_time",
                "stacks[$index].times must be an array of HH:mm strings",
            )
        if (values.size < 2) {
            throw error("invalid_time", "stacks[$index].times needs at least two times")
        }
        if (values.size > AiTokenBudget.MAX_BATCH_ITEMS) {
            throw error(
                "too_many_items",
                "stacks[$index].times allows at most ${AiTokenBudget.MAX_BATCH_ITEMS} times",
            )
        }
        val times = values.mapIndexed { timeIndex, value ->
            val raw = (value as? JsonPrimitive)?.contentOrNull?.trim()
                ?: throw error(
                    "invalid_time",
                    "stacks[$index].times[$timeIndex] must be HH:mm",
                )
            val match = TIME_PATTERN.matchEntire(raw)
                ?: throw error(
                    "invalid_time",
                    "stacks[$index].times[$timeIndex] must be HH:mm, got '$raw'",
                )
            LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }
        if (times.distinct().size != times.size) {
            throw error("duplicate_time", "stacks[$index].times must not contain duplicates")
        }
        val name = item.stringOrNull("name")?.trim().orEmpty().take(100)
        val note = item.stringOrNull("note")?.trim().orEmpty().take(500)
        val enabled = item.booleanOrNull("enabled") ?: true
        val oneTime = item.booleanOrNull("oneTime") ?: false
        val tags = parseTags(item["tags"], "stacks[$index].tags")
        return AlarmStackDraft(
            times = times.sorted(),
            name = name,
            note = note,
            enabled = enabled,
            oneTime = oneTime,
            tags = tags,
        )
    }

    private fun parseEvent(element: JsonElement, index: Int): EventDraft {
        val item = element as? JsonObject
            ?: throw error("invalid_item", "events[$index] must be an object")
        val title = item.stringOrNull("title")?.trim().orEmpty()
        if (title.isBlank()) throw error("invalid_title", "events[$index].title is required")
        if (title.length > 120) throw error("invalid_title", "event titles must be 120 chars or fewer")
        val note = item.stringOrNull("note")?.trim().orEmpty().take(500)
        val date = item.stringOrNull("date")?.trim()
            ?.let { parseDate(it, "events[$index].date") }
            ?: throw error("invalid_date", "events[$index].date is required (YYYY-MM-DD)")
        val allDay = item.booleanOrNull("allDay") ?: false
        val start: LocalTime
        val end: LocalTime
        val endDayOffset: Int
        if (allDay) {
            start = LocalTime.MIDNIGHT
            end = LocalTime.MIDNIGHT
            endDayOffset = 0
        } else {
            start = parseTime(item, "start", "events[$index].start")
            end = if (item.containsKey("end")) {
                parseTime(item, "end", "events[$index].end")
            } else {
                // Omitting end creates an event with only a start time.
                start
            }
            val endDateRaw = item.stringOrNull("endDate")?.trim()?.takeIf { it.isNotEmpty() }
            endDayOffset = if (endDateRaw != null) {
                val endDate = parseDate(endDateRaw, "events[$index].endDate")
                if (endDate.isBefore(date)) {
                    throw error(
                        "invalid_end_date",
                        "events[$index].endDate must not be before date",
                    )
                }
                (endDate.toEpochDay() - date.toEpochDay()).toInt()
            } else {
                0
            }
            if (endDayOffset > Event.MAX_EVENT_DAY_SPAN) {
                throw error(
                    "invalid_end_date",
                    "events[$index].endDate spans more than ${Event.MAX_EVENT_DAY_SPAN} days",
                )
            }
            if (endDayOffset == 0 && end.isBefore(start)) {
                throw error(
                    "invalid_time_range",
                    "events[$index].end must not be before start unless endDate is a later day",
                )
            }
        }
        val withAlarm = (item.booleanOrNull("withAlarm") ?: false) && !allDay
        val reminderMinutes = if (allDay) {
            Event.REMINDER_OFF
        } else {
            parseReminderMinutes(item, "events[$index].reminderMinutes") ?: Event.REMINDER_OFF
        }
        val repeat = item["repeat"]
        val repeatType: Int
        val repeatDays: Set<Int>
        val repeatUntil: LocalDate?
        val occurrences: Int
        if (repeat == null || repeat is JsonNull) {
            repeatType = Event.REPEAT_NONE
            repeatDays = emptySet()
            repeatUntil = null
            occurrences = 1
        } else {
            val repeatObject = repeat as? JsonObject
                ?: throw error("invalid_repeat", "events[$index].repeat must be an object")
            val type = repeatObject.stringOrNull("type")?.trim()?.lowercase() ?: "none"
            when (type) {
                "none" -> {
                    repeatType = Event.REPEAT_NONE
                    repeatDays = emptySet()
                    repeatUntil = null
                    occurrences = 1
                }

                "daily", "weekly" -> {
                    val untilRaw = repeatObject.stringOrNull("until")?.trim()
                        ?: throw error(
                            "missing_until",
                            "events[$index].repeat.until is required for recurring events",
                        )
                    val until = parseDate(untilRaw, "events[$index].repeat.until")
                    if (!until.isAfter(date)) {
                        throw error(
                            "invalid_until",
                            "events[$index].repeat.until must be later than the event date",
                        )
                    }
                    repeatType = if (type == "daily") {
                        Event.REPEAT_DAILY
                    } else {
                        Event.REPEAT_WEEKLY
                    }
                    repeatUntil = until
                    repeatDays = if (type == "weekly") {
                        val days = repeatObject.arrayOrNull("days")
                            ?: throw error(
                                "missing_days",
                                "events[$index].repeat.days is required for weekly events",
                            )
                        val parsed = days.mapIndexedNotNull { dayIndex, value ->
                            val day = (value as? JsonPrimitive)?.intOrNull
                                ?: throw error(
                                    "invalid_days",
                                    "events[$index].repeat.days[$dayIndex] must be an integer",
                                )
                            if (day !in 1..7) {
                                throw error(
                                    "invalid_days",
                                    "weekdays must be 1=Mon..7=Sun",
                                )
                            }
                            day
                        }.toSet()
                        if (parsed.isEmpty()) {
                            throw error(
                                "missing_days",
                                "events[$index].repeat.days must not be empty",
                            )
                        }
                        if (date.dayOfWeek.value !in parsed) {
                            throw error(
                                "invalid_days",
                                "weekly repeat.days must include the event weekday " +
                                    "(${date.dayOfWeek.value})",
                            )
                        }
                        parsed
                    } else {
                        emptySet()
                    }
                    val rule = RecurrenceRule(
                        type = repeatType,
                        days = repeatDays,
                        untilEpochDay = until.toEpochDay(),
                    )
                    val plan = planRecurrence(date, rule)
                    occurrences = plan.totalOccurrences
                    if (plan.limitReached || occurrences > MAX_OCCURRENCES_PER_SERIES) {
                        throw error(
                            "too_many_occurrences",
                            "events[$index] would create more than " +
                                "$MAX_OCCURRENCES_PER_SERIES occurrences",
                        )
                    }
                }

                else -> throw error(
                    "invalid_repeat",
                    "repeat.type must be none, daily or weekly",
                )
            }
        }
        if (withAlarm && !date.atTime(start).isAfter(now)) {
            throw error(
                "alarm_in_past",
                "events[$index] requests an alarm in the past; " +
                    "clear withAlarm or move the event to the future",
            )
        }
        val tags = parseTags(item["tags"], "events[$index].tags")
        return EventDraft(
            title = title,
            note = note,
            date = date,
            start = start,
            end = end,
            withAlarm = withAlarm,
            repeatType = repeatType,
            repeatDays = repeatDays,
            repeatUntil = repeatUntil,
            occurrenceCount = occurrences,
            reminderMinutes = reminderMinutes,
            tags = tags,
            endDayOffset = endDayOffset,
            allDay = allDay,
        )
    }

    private fun parseReminderMinutes(item: JsonObject, label: String): Int? {
        if (!item.containsKey("reminderMinutes")) return null
        val value = (item["reminderMinutes"] as? JsonPrimitive)?.intOrNull
        if (value == null ||
            (value != Event.REMINDER_OFF && value !in Event.REMINDER_MINUTE_OPTIONS)
        ) {
            throw error("invalid_reminder", "$label must be 0, 5, 10 or 30")
        }
        return value
    }

    private fun parseTags(element: JsonElement?, field: String): List<TagRef> {
        if (element == null || element is JsonNull) return emptyList()
        val array = element as? JsonArray
            ?: throw error("invalid_tags", "$field must be an array of existing tag names")
        if (array.size > 5) throw error("too_many_tags", "$field allows at most 5 tags")
        val names = array.mapIndexedNotNull { index, item ->
            val name = (item as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (name.isBlank()) {
                throw error("invalid_tags", "$field[$index] must be a non-empty tag name")
            }
            name
        }.distinct()
        val unknown = names.filterNot { it in availableTags }
        if (unknown.isNotEmpty()) {
            val available = availableTags.keys.sorted().take(15).joinToString(", ")
                .ifBlank { "(none)" }
            throw error(
                "unknown_tag",
                "unknown tag(s): ${unknown.joinToString(", ")}; available tags: $available",
            )
        }
        return names.map { TagRef(id = availableTags.getValue(it), name = it) }
    }

    private fun parseTime(item: JsonObject, field: String, label: String): LocalTime {
        val raw = item.stringOrNull(field)?.trim()
            ?: throw error("invalid_time", "$label is required (HH:mm)")
        val match = TIME_PATTERN.matchEntire(raw)
            ?: throw error("invalid_time", "$label must be HH:mm, got '$raw'")
        return LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    private fun parseDate(raw: String, label: String): LocalDate =
        try {
            LocalDate.parse(raw)
        } catch (_: Exception) {
            throw error("invalid_date", "$label must be YYYY-MM-DD, got '$raw'")
        }

    private fun parseRoot(arguments: String): JsonObject {
        val element = try {
            Json.parseToJsonElement(arguments)
        } catch (_: Exception) {
            throw error("invalid_arguments", "tool arguments must be valid JSON")
        }
        return element as? JsonObject
            ?: throw error("invalid_arguments", "tool arguments must be a JSON object")
    }

    private fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.array(key: String): JsonArray =
        arrayOrNull(key) ?: throw error("invalid_arguments", "$key must be an array")

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun error(code: String, message: String): AiToolParseException =
        AiToolParseException(code, message)

    companion object {
        private val TIME_PATTERN = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")
    }
}
