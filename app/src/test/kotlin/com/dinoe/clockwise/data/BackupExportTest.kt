package com.dinoe.clockwise.data

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupExportTest {
    private val exportedAt = 1_700_000_000_000L

    private fun exported(
        events: List<Event>,
        alarms: List<Alarm>,
        tags: List<Tag>,
        eventTags: List<EventTagCrossRef>,
        alarmTags: List<AlarmTagCrossRef>,
    ): String {
        val output = ByteArrayOutputStream()
        writeBackupJson(
            output = output,
            schemaVersion = 1L,
            exportedAt = exportedAt,
            events = events,
            alarms = alarms,
            tags = tags,
            eventTags = eventTags,
            alarmTags = alarmTags,
        )
        return output.toString(Charsets.UTF_8.name())
    }

    // The document shape produced by encoding the previously materialized element tree.
    private fun materialized(
        events: List<Event>,
        alarms: List<Alarm>,
        tags: List<Tag>,
        eventTags: List<EventTagCrossRef>,
        alarmTags: List<AlarmTagCrossRef>,
    ): String = buildJsonObject {
        put("schemaVersion", 1L)
        put("exportedAt", exportedAt)
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
                        put("ringtone", alarm.ringtone)
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

    @Test
    fun exportOfEmptyDatabaseMatchesMaterializedDocument() {
        assertEquals(
            materialized(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            exported(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        )
    }

    @Test
    fun exportWithRowsMatchesMaterializedDocument() {
        val events = listOf(
            Event(
                id = 1L,
                title = "Standup \"daily\"",
                note = "line one\nline two\ttab \\ backslash",
                date = 20_000L,
                start = 9 * 3_600,
                end = 9 * 3_600 + 900,
                seriesId = 1L,
                occurrenceIndex = 1,
                repeatType = Event.REPEAT_WEEKLY,
                repeatDays = "1,3,5",
                reminderMinutes = 10,
                allDay = false,
            ),
            Event(
                id = 2L,
                title = "中秋節",
                note = "",
                date = 20_100L,
                start = 0,
                end = 0,
                allDay = true,
            ),
        )
        val alarms = listOf(
            Alarm(
                id = 1L,
                time = 7 * 3_600,
                enabled = true,
                fireDate = 20_000L,
                eventId = 1L,
                name = "Standup \"daily\"",
                note = "",
                snoozedUntil = 1_700_000_500_000L,
                oneTime = false,
                stackId = 42L,
            ),
            Alarm(id = 2L, time = 8 * 3_600, enabled = false, name = "", note = ""),
        )
        val tags = listOf(Tag(id = 1L, name = "Work", color = 0xFF0000))
        val eventTags = listOf(EventTagCrossRef(eventId = 1L, tagId = 1L))
        val alarmTags = listOf(AlarmTagCrossRef(alarmId = 1L, tagId = 1L))

        assertEquals(
            materialized(events, alarms, tags, eventTags, alarmTags),
            exported(events, alarms, tags, eventTags, alarmTags),
        )
    }
}
