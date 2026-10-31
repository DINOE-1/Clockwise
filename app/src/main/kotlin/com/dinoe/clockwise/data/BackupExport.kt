package com.dinoe.clockwise.data

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val WRITE_BUFFER_CHARS = 8 * 1024

/**
 * Writes the backup document straight to [output] as compact JSON instead of materializing the whole
 * element tree, one string for it, and its UTF-8 bytes. Each row is encoded on its own, so only a
 * single row is held in memory at a time. The framing reproduces the compact encoding of the
 * previously built element tree, including field order.
 */
internal fun writeBackupJson(
    output: OutputStream,
    schemaVersion: Long,
    exportedAt: Long,
    events: List<Event>,
    alarms: List<Alarm>,
    tags: List<Tag>,
    eventTags: List<EventTagCrossRef>,
    alarmTags: List<AlarmTagCrossRef>,
) {
    val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8), WRITE_BUFFER_CHARS)
    writer.write("{\"schemaVersion\":")
    writer.write(schemaVersion.toString())
    writer.write(",\"exportedAt\":")
    writer.write(exportedAt.toString())
    writeArray(writer, "events", events, ::eventJson)
    writeArray(writer, "alarms", alarms, ::alarmJson)
    writeArray(writer, "tags", tags, ::tagJson)
    writeArray(writer, "eventTags", eventTags, ::eventTagJson)
    writeArray(writer, "alarmTags", alarmTags, ::alarmTagJson)
    writer.write("}")
    writer.flush()
}

private inline fun <T> writeArray(
    writer: Writer,
    key: String,
    rows: List<T>,
    rowJson: (T) -> JsonObject,
) {
    // Keys are plain ASCII identifiers, so they need no escaping.
    writer.write(",\"")
    writer.write(key)
    writer.write("\":[")
    rows.forEachIndexed { index, row ->
        if (index > 0) writer.write(",")
        writer.write(rowJson(row).toString())
    }
    writer.write("]")
}

private fun eventJson(event: Event): JsonObject = buildJsonObject {
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
}

private fun alarmJson(alarm: Alarm): JsonObject = buildJsonObject {
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
    put("ringtone", alarm.ringtone)
}

private fun tagJson(tag: Tag): JsonObject = buildJsonObject {
    put("id", tag.id)
    put("name", tag.name)
    put("color", tag.color)
}

private fun eventTagJson(ref: EventTagCrossRef): JsonObject = buildJsonObject {
    put("eventId", ref.eventId)
    put("tagId", ref.tagId)
}

private fun alarmTagJson(ref: AlarmTagCrossRef): JsonObject = buildJsonObject {
    put("alarmId", ref.alarmId)
    put("tagId", ref.tagId)
}
