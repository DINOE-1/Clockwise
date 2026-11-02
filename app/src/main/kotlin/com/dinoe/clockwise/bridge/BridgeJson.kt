package com.dinoe.clockwise.bridge

import com.dinoe.clockwise.data.Alarm
import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.data.Tag
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Pure JSON payload builders for the bridge. The event/alarm shapes mirror the in-app AI read
 * payloads (AiChatViewModel) so both surfaces stay conceptually aligned.
 */
internal object BridgeJson {
    const val BRIDGE_VERSION = 1

    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

    fun error(code: String, message: String): JsonObject = buildJsonObject {
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    fun errorCodeFor(status: Int): String = when (status) {
        400 -> "bad_request"
        401 -> "unauthorized"
        404 -> "not_found"
        405 -> "method_not_allowed"
        413 -> "payload_too_large"
        else -> "internal"
    }

    fun meta(
        appVersionName: String,
        dbVersion: Int,
        timezone: String,
        now: String,
    ): JsonObject = buildJsonObject {
        put("app", "clockwise")
        put("versionName", appVersionName)
        put("dbVersion", dbVersion)
        put("timezone", timezone)
        put("now", now)
        put("bridgeVersion", BRIDGE_VERSION)
    }

    fun eventsPayload(
        total: Int,
        shown: List<Event>,
        alarmEventIds: Set<Long>,
        tagNames: Map<Long, List<String>>,
        timezone: String,
    ): JsonObject = buildJsonObject {
        put("total", total)
        put("truncated", total > shown.size)
        put("timezone", timezone)
        putJsonArray("events") {
            shown.forEach { event ->
                add(
                    buildJsonObject {
                        putEvent(event, hasAlarm = event.id in alarmEventIds, tagNames[event.id].orEmpty())
                    },
                )
            }
        }
    }

    fun eventDetail(event: Event, alarmId: Long?, tagNames: List<String>): JsonObject =
        buildJsonObject {
            putEvent(event, hasAlarm = alarmId != null, tagNames = tagNames)
            alarmId?.let { put("alarmId", it) }
        }

    fun alarmsPayload(
        total: Int,
        shown: List<Alarm>,
        tagNames: Map<Long, List<String>>,
    ): JsonObject = buildJsonObject {
        put("total", total)
        put("truncated", total > shown.size)
        putJsonArray("alarms") {
            shown.forEach { alarm ->
                add(
                    buildJsonObject {
                        putAlarm(alarm, tagNames[alarm.id].orEmpty())
                    },
                )
            }
        }
    }

    fun alarmDetail(alarm: Alarm, tagNames: List<String>): JsonObject = buildJsonObject {
        putAlarm(alarm, tagNames)
    }

    fun tagsPayload(tags: List<Tag>): JsonObject = buildJsonObject {
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
    }

    private fun JsonObjectBuilder.putEvent(event: Event, hasAlarm: Boolean, tagNames: List<String>) {
        put("id", event.id)
        put("date", LocalDate.ofEpochDay(event.date).toString())
        put("allDay", event.allDay)
        if (!event.allDay) {
            put("start", formatSeconds(event.start))
            if (event.hasEndTime) {
                put("end", formatSeconds(event.endTimeOfDay))
                if (event.endDayOffset > 0) {
                    put("endDate", LocalDate.ofEpochDay(event.endDateEpochDay).toString())
                }
            }
        }
        put("title", event.title)
        if (event.note.isNotBlank()) put("note", event.note)
        put("repeat", repeatLabel(event.repeatType))
        if (event.repeatType == Event.REPEAT_WEEKLY) {
            putJsonArray("repeatDays") {
                event.repeatDays.split(',')
                    .mapNotNull { it.trim().toIntOrNull() }
                    .forEach { add(it) }
            }
        }
        put("seriesId", event.seriesId)
        put("occurrenceIndex", event.occurrenceIndex)
        put("hasAlarm", hasAlarm)
        put("reminderMinutes", event.reminderMinutes)
        putJsonArray("tags") { tagNames.forEach { add(it) } }
    }

    private fun JsonObjectBuilder.putAlarm(alarm: Alarm, tagNames: List<String>) {
        put("id", alarm.id)
        put("time", formatSeconds(alarm.time))
        alarm.fireDate?.let { fireDate -> put("date", LocalDate.ofEpochDay(fireDate).toString()) }
        put("enabled", alarm.enabled)
        put("oneTime", alarm.oneTime)
        alarm.snoozedUntil?.let { snoozedUntil ->
            put("snoozedUntil", Instant.ofEpochMilli(snoozedUntil).toString())
        }
        put("name", alarm.name)
        if (alarm.note.isNotBlank()) put("note", alarm.note)
        put("fromEvent", alarm.eventId != null)
        alarm.eventId?.let { put("eventId", it) }
        putJsonArray("tags") { tagNames.forEach { add(it) } }
    }

    private fun formatSeconds(seconds: Int): String =
        LocalTime.ofSecondOfDay(seconds.toLong()).format(TIME_FORMATTER)

    private fun repeatLabel(repeatType: Int): String = when (repeatType) {
        Event.REPEAT_DAILY -> "daily"
        Event.REPEAT_WEEKLY -> "weekly"
        else -> "none"
    }
}
