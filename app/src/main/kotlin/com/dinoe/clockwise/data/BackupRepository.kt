package com.dinoe.clockwise.data

import android.content.Context
import androidx.room.withTransaction
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.alarm.AlarmScheduler
import com.dinoe.clockwise.alarm.ReminderScheduler
import com.dinoe.clockwise.alarm.ScheduleResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate

data class BackupImportResult(
    val events: Int,
    val alarms: Int,
    val tags: Int,
    val scheduledAlarms: Int,
    val unscheduledAlarms: Int,
)

class BackupException(message: String) : Exception(message)

/**
 * Versioned JSON export/import for user-visible data. AI chat sessions and the API key are never
 * included. Import replaces all events, alarms, and tags in one transaction.
 */
class BackupRepository(context: Context) {
    private val app = context.applicationContext as ClockwiseApp
    private val database = app.database
    private val eventDao = database.eventDao()
    private val alarmDao = database.alarmDao()
    private val tagDao = database.tagDao()
    private val json = Json { prettyPrint = true }

    suspend fun exportJson(): String {
        val events = eventDao.getAll()
        val alarms = alarmDao.getAll()
        val tags = tagDao.getAll()
        val eventTags = tagDao.getEventTagRefs()
        val alarmTags = tagDao.getAlarmTagRefs()
        return buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("exportedAt", System.currentTimeMillis())
            putJsonArray("events") {
                events.forEach { event ->
                    add(
                        buildJsonObject {
                            put("id", event.id)
                            put("title", event.title)
                            put("note", event.note)
                            put("date", event.date)
                            put("start", event.start)
                            put("end", event.end)
                            put("seriesId", event.seriesId)
                            put("occurrenceIndex", event.occurrenceIndex)
                            put("repeatType", event.repeatType)
                            put("repeatDays", event.repeatDays)
                            put("reminderMinutes", event.reminderMinutes)
                            put("allDay", event.allDay)
                        },
                    )
                }
            }
            putJsonArray("alarms") {
                alarms.forEach { alarm ->
                    add(
                        buildJsonObject {
                            put("id", alarm.id)
                            put("time", alarm.time)
                            put("enabled", alarm.enabled)
                            alarm.fireDate?.let { put("fireDate", it) }
                            alarm.eventId?.let { put("eventId", it) }
                            put("name", alarm.name)
                            put("note", alarm.note)
                            put("oneTime", alarm.oneTime)
                            alarm.snoozedUntil?.let { put("snoozedUntil", it) }
                            alarm.stackId?.let { put("stackId", it) }
                        },
                    )
                }
            }
            putJsonArray("tags") {
                tags.forEach { tag ->
                    add(
                        buildJsonObject {
                            put("id", tag.id)
                            put("name", tag.name)
                            put("color", tag.color)
                        },
                    )
                }
            }
            putJsonArray("eventTags") {
                eventTags.forEach { ref ->
                    add(
                        buildJsonObject {
                            put("eventId", ref.eventId)
                            put("tagId", ref.tagId)
                        },
                    )
                }
            }
            putJsonArray("alarmTags") {
                alarmTags.forEach { ref ->
                    add(
                        buildJsonObject {
                            put("alarmId", ref.alarmId)
                            put("tagId", ref.tagId)
                        },
                    )
                }
            }
        }.toString()
    }

    suspend fun importJson(text: String): BackupImportResult {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw BackupException("The selected file is not valid JSON")
        }
        val version = root.optionalLong("schemaVersion")
            ?: throw BackupException("Missing schemaVersion")
        if (version != SCHEMA_VERSION) {
            throw BackupException("Unsupported backup version: $version")
        }
        val eventRows = root.array("events").map { parseEvent(it) }
        val alarmRows = root.array("alarms").map { parseAlarm(it) }
        val tagRows = root.array("tags").map { parseTag(it) }
        val eventTagRows = root.array("eventTags")
            .map { parseEventTag(it) }
            .distinctBy { it.eventId to it.tagId }
        val alarmTagRows = root.array("alarmTags")
            .map { parseAlarmTag(it) }
            .distinctBy { it.alarmId to it.tagId }

        if (eventRows.size > MAX_BACKUP_EVENTS) {
            throw BackupException("The backup contains too many events")
        }
        if (alarmRows.count { it.enabled } > MAX_ACTIVE_ALARMS) {
            throw BackupException("The backup contains too many enabled alarms")
        }
        validateUniqueIds(eventRows.map { it.id }, "event")
        validateUniqueIds(alarmRows.map { it.id }, "alarm")
        validateUniqueIds(tagRows.map { it.id }, "tag")
        validateUniqueIds(tagRows.map { it.name }, "tag name")

        val oldAlarms = alarmDao.getAll()
        val oldEvents = eventDao.getAll()

        val imported = database.withTransaction {
            tagDao.deleteAllEventTags()
            tagDao.deleteAllAlarmTags()
            alarmDao.deleteAllAlarms()
            eventDao.deleteAllEvents()
            tagDao.deleteAllTags()

            val tagIdMap = mutableMapOf<Long, Long>()
            tagRows.forEach { row ->
                val newId = tagDao.insert(Tag(name = row.name, color = row.color))
                tagIdMap[row.id] = newId
            }

            val eventIdMap = mutableMapOf<Long, Long>()
            eventRows.forEach { row ->
                val newId = eventDao.insert(
                    Event(
                        title = row.title,
                        note = row.note,
                        date = row.date,
                        start = row.start,
                        end = row.end,
                        occurrenceIndex = row.occurrenceIndex,
                        repeatType = row.repeatType,
                        repeatDays = row.repeatDays,
                        reminderMinutes = row.reminderMinutes,
                        allDay = row.allDay,
                    ),
                )
                eventIdMap[row.id] = newId
            }
            eventRows.forEach { row ->
                val newId = eventIdMap.getValue(row.id)
                val newSeriesId = if (row.seriesId == 0L) 0L else {
                    eventIdMap[row.seriesId]
                        ?: throw BackupException("Event ${row.id} references a missing series")
                }
                eventDao.update(
                    Event(
                        id = newId,
                        title = row.title,
                        note = row.note,
                        date = row.date,
                        start = row.start,
                        end = row.end,
                        seriesId = newSeriesId,
                        occurrenceIndex = row.occurrenceIndex,
                        repeatType = row.repeatType,
                        repeatDays = row.repeatDays,
                        reminderMinutes = row.reminderMinutes,
                        allDay = row.allDay,
                    ),
                )
            }

            eventTagRows.forEach { ref ->
                val eventId = eventIdMap[ref.eventId]
                    ?: throw BackupException("Tag reference points to a missing event")
                val tagId = tagIdMap[ref.tagId]
                    ?: throw BackupException("Tag reference points to a missing tag")
                tagDao.insertEventTags(listOf(EventTagCrossRef(eventId, tagId)))
            }

            val alarmIdMap = mutableMapOf<Long, Long>()
            alarmRows.forEach { row ->
                val eventId = row.eventId?.let { old ->
                    eventIdMap[old]
                        ?: throw BackupException("Alarm ${row.id} points to a missing event")
                }
                val newId = alarmDao.insert(
                    Alarm(
                        time = row.time,
                        enabled = row.enabled,
                        fireDate = row.fireDate,
                        eventId = eventId,
                        name = row.name,
                        note = row.note,
                        snoozedUntil = null,
                        oneTime = row.oneTime,
                        stackId = row.stackId,
                    ),
                )
                alarmIdMap[row.id] = newId
            }
            alarmTagRows.forEach { ref ->
                val alarmId = alarmIdMap[ref.alarmId]
                    ?: throw BackupException("Tag reference points to a missing alarm")
                val tagId = tagIdMap[ref.tagId]
                    ?: throw BackupException("Tag reference points to a missing tag")
                tagDao.insertAlarmTags(listOf(AlarmTagCrossRef(alarmId, tagId)))
            }

            Triple(eventRows.size, alarmRows.size, tagRows.size)
        }

        // Only cancel the old system alarms after the database replacement committed; a failed
        // import leaves the previous data and its scheduled alarms untouched.
        oldAlarms.forEach { alarm -> AlarmScheduler.cancel(app, alarm) }
        oldEvents.forEach { event -> ReminderScheduler.cancel(app, event.id) }

        var scheduled = 0
        var unscheduled = 0
        alarmDao.getEnabled().forEach { alarm ->
            when (AlarmScheduler.schedule(app, alarm)) {
                is ScheduleResult.Scheduled -> scheduled++
                else -> {
                    unscheduled++
                    alarmDao.update(alarm.copy(enabled = false, snoozedUntil = null))
                }
            }
        }
        eventDao.getAll().forEach { event -> ReminderScheduler.schedule(app, event) }
        return BackupImportResult(
            events = imported.first,
            alarms = imported.second,
            tags = imported.third,
            scheduledAlarms = scheduled,
            unscheduledAlarms = unscheduled,
        )
    }

    private fun parseEvent(element: kotlinx.serialization.json.JsonElement): BackupEvent {
        val obj = element.jsonObject
        val id = obj.requireLong("id", "event")
        val date = obj.requireLong("date", "event $id")
        if (date < MIN_EPOCH_DAY || date > MAX_EPOCH_DAY) {
            throw BackupException("Event $id has an invalid date")
        }
        val start = obj.requireInt("start", "event $id")
        val end = obj.requireInt("end", "event $id")
        val allDay = obj.optionalBoolean("allDay") ?: false
        if (start !in 0..86_399 || end !in 0..MAX_EVENT_SECONDS) {
            throw BackupException("Event $id has an invalid time range")
        }
        if (allDay) {
            if (start != 0 || end != 0) {
                throw BackupException("Event $id has an invalid all-day time range")
            }
        } else if (start > end) {
            throw BackupException("Event $id has an invalid time range")
        }
        val repeatType = obj.optionalInt("repeatType") ?: Event.REPEAT_NONE
        if (repeatType !in Event.REPEAT_NONE..Event.REPEAT_WEEKLY) {
            throw BackupException("Event $id has an invalid repeat type")
        }
        val reminderMinutes = obj.optionalInt("reminderMinutes") ?: Event.REMINDER_OFF
        if (reminderMinutes != Event.REMINDER_OFF &&
            reminderMinutes !in Event.REMINDER_MINUTE_OPTIONS
        ) {
            throw BackupException("Event $id has an invalid reminder")
        }
        return BackupEvent(
            id = id,
            title = obj.optionalString("title").orEmpty(),
            note = obj.optionalString("note").orEmpty(),
            date = date,
            start = start,
            end = end,
            seriesId = obj.optionalLong("seriesId") ?: 0L,
            occurrenceIndex = obj.optionalInt("occurrenceIndex") ?: 0,
            repeatType = repeatType,
            repeatDays = obj.optionalString("repeatDays").orEmpty(),
            reminderMinutes = reminderMinutes,
            allDay = allDay,
        )
    }

    private fun parseAlarm(element: kotlinx.serialization.json.JsonElement): BackupAlarm {
        val obj = element.jsonObject
        val id = obj.requireLong("id", "alarm")
        val time = obj.requireInt("time", "alarm $id")
        if (time !in 0..86_399) throw BackupException("Alarm $id has an invalid time")
        val fireDate = obj.optionalLong("fireDate")
        if (fireDate != null && (fireDate < MIN_EPOCH_DAY || fireDate > MAX_EPOCH_DAY)) {
            throw BackupException("Alarm $id has an invalid date")
        }
        return BackupAlarm(
            id = id,
            time = time,
            enabled = obj.optionalBoolean("enabled") ?: true,
            fireDate = fireDate,
            eventId = obj.optionalLong("eventId"),
            name = obj.optionalString("name").orEmpty(),
            note = obj.optionalString("note").orEmpty(),
            oneTime = obj.optionalBoolean("oneTime") ?: false,
            stackId = obj.optionalLong("stackId"),
        )
    }

    private fun parseTag(element: kotlinx.serialization.json.JsonElement): BackupTag {
        val obj = element.jsonObject
        val id = obj.requireLong("id", "tag")
        val name = obj.optionalString("name")?.trim().orEmpty()
        if (name.isBlank()) throw BackupException("Tag $id has an empty name")
        return BackupTag(
            id = id,
            name = name.take(80),
            color = obj.optionalInt("color") ?: 0,
        )
    }

    private fun parseEventTag(element: kotlinx.serialization.json.JsonElement): BackupEventTag {
        val obj = element.jsonObject
        return BackupEventTag(
            eventId = obj.requireLong("eventId", "event tag"),
            tagId = obj.requireLong("tagId", "event tag"),
        )
    }

    private fun parseAlarmTag(element: kotlinx.serialization.json.JsonElement): BackupAlarmTag {
        val obj = element.jsonObject
        return BackupAlarmTag(
            alarmId = obj.requireLong("alarmId", "alarm tag"),
            tagId = obj.requireLong("tagId", "alarm tag"),
        )
    }

    private fun JsonObject.array(key: String): JsonArray =
        (this[key] as? JsonArray) ?: throw BackupException("Missing $key array")

    private fun JsonObject.optionalString(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.optionalLong(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.optionalInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.optionalBoolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.requireLong(key: String, owner: String): Long =
        optionalLong(key) ?: throw BackupException("$owner is missing $key")

    private fun JsonObject.requireInt(key: String, owner: String): Int =
        optionalInt(key) ?: throw BackupException("$owner is missing $key")

    private fun validateUniqueIds(ids: List<Any>, kind: String) {
        if (ids.size != ids.distinct().size) {
            throw BackupException("The backup contains duplicate $kind ids")
        }
    }

    private data class BackupEvent(
        val id: Long,
        val title: String,
        val note: String,
        val date: Long,
        val start: Int,
        val end: Int,
        val seriesId: Long,
        val occurrenceIndex: Int,
        val repeatType: Int,
        val repeatDays: String,
        val reminderMinutes: Int,
        val allDay: Boolean,
    )

    private data class BackupAlarm(
        val id: Long,
        val time: Int,
        val enabled: Boolean,
        val fireDate: Long?,
        val eventId: Long?,
        val name: String,
        val note: String,
        val oneTime: Boolean,
        val stackId: Long?,
    )

    private data class BackupTag(val id: Long, val name: String, val color: Int)

    private data class BackupEventTag(val eventId: Long, val tagId: Long)

    private data class BackupAlarmTag(val alarmId: Long, val tagId: Long)

    private companion object {
        const val SCHEMA_VERSION = 1L
        const val MAX_BACKUP_EVENTS = 10_000
        const val MAX_EVENT_SECONDS = Event.MAX_EVENT_DAY_SPAN * Event.SECONDS_PER_DAY
        val MIN_EPOCH_DAY: Long = LocalDate.of(1900, 1, 1).toEpochDay()
        val MAX_EPOCH_DAY: Long = LocalDate.of(2200, 12, 31).toEpochDay()
    }
}
