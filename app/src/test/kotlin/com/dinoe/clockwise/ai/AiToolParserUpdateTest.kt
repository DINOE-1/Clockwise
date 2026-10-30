package com.dinoe.clockwise.ai

import com.dinoe.clockwise.data.Alarm
import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.data.EventScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AiToolParserUpdateTest {
    private val now = LocalDateTime.of(2026, 10, 22, 19, 0)
    private val tagMap = mapOf("work" to 1L, "personal" to 2L)
    private val alarm = Alarm(
        id = 10,
        time = 7 * 3600,
        name = "Wake",
        note = "old note",
        enabled = true,
    )
    private val datedAlarm = Alarm(
        id = 12,
        time = 9 * 3600,
        fireDate = LocalDate.of(2026, 10, 25).toEpochDay(),
        name = "Train",
    )
    private val autoAlarm = Alarm(
        id = 11,
        time = 8 * 3600,
        eventId = 100,
        name = "Meeting",
    )
    private val event = Event(
        id = 100,
        title = "Meeting",
        note = "agenda",
        date = LocalDate.of(2026, 10, 26).toEpochDay(),
        start = 10 * 3600,
        end = 11 * 3600,
    )
    private val series = (0..2).map { index ->
        Event(
            id = 200L + index,
            title = "Standup",
            note = "",
            date = LocalDate.of(2026, 10, 26).toEpochDay() + index,
            start = 9 * 3600,
            end = 9 * 3600 + 900,
            seriesId = 200,
            occurrenceIndex = index,
        )
    }
    private val alarmsById = listOf(alarm, datedAlarm, autoAlarm).associateBy { it.id }
    private val eventsById = (listOf(event) + series).associateBy { it.id }
    private val parser = AiToolParser(
        now = now,
        availableTags = tagMap,
        alarmsById = alarmsById,
        eventsById = eventsById,
        alarmTagsById = mapOf(10L to listOf(TagRef(1, "work"))),
        eventTagsById = mapOf(100L to listOf(TagRef(2, "personal"))),
    )

    @Test
    fun updatesAlarmFieldsAndReplacesTags() {
        val updates = parser.parseAlarmUpdates(
            """
            {"alarms":[{
              "id":10,
              "time":"07:30",
              "name":"Wake up",
              "note":"new note",
              "enabled":false,
              "tags":["personal"]
            }]}
            """.trimIndent(),
        )
        val update = updates.single()
        assertEquals("07:30", update.time.toString())
        assertEquals("Wake up", update.name)
        assertEquals("new note", update.note)
        assertFalse(update.enabled)
        assertEquals(listOf("personal"), update.tags.map { it.name })
        assertEquals("07:00", update.previousTime.toString())
        assertTrue(update.previousEnabled)
    }

    @Test
    fun keepsOmittedAlarmFieldsAndCurrentTags() {
        val update = parser.parseAlarmUpdates("""{"alarms":[{"id":10,"enabled":false}]}""")
            .single()
        assertEquals("07:00", update.time.toString())
        assertEquals("Wake", update.name)
        assertEquals("old note", update.note)
        assertEquals(listOf("work"), update.tags.map { it.name })
        assertFalse(update.enabled)
    }

    @Test
    fun updatesAlarmOneTime() {
        val update = parser.parseAlarmUpdates("""{"alarms":[{"id":10,"oneTime":true}]}""")
            .single()
        assertTrue(update.oneTime)
        assertFalse(update.previousOneTime)
    }

    @Test
    fun updatesEventReminderAndCanDisableIt() {
        val enabled = parser.parseEventUpdates(
            """{"events":[{"id":100,"reminderMinutes":10}]}""",
        ).single()
        assertEquals(10, enabled.reminderMinutes ?: -1)
        assertEquals(0, enabled.previousReminderMinutes)

        val disabled = parser.parseEventUpdates(
            """{"events":[{"id":100,"reminderMinutes":0}]}""",
        ).single()
        assertEquals(0, disabled.reminderMinutes ?: -1)

        val omitted = parser.parseEventUpdates(
            """{"events":[{"id":100,"title":"New"}]}""",
        ).single()
        assertNull(omitted.reminderMinutes)
    }

    @Test
    fun clearDateConvertsDatedAlarmToPlain() {
        val update = parser.parseAlarmUpdates("""{"alarms":[{"id":12,"clearDate":true}]}""")
            .single()
        assertNull(update.fireDate)
        assertEquals(LocalDate.of(2026, 10, 25), update.previousFireDate)
    }

    @Test
    fun rejectsEditingAutoAlarm() {
        val error = expectFailure {
            parser.parseAlarmUpdates("""{"alarms":[{"id":11,"time":"09:00"}]}""")
        }
        assertEquals("auto_alarm", error.code)
    }

    @Test
    fun allowsTogglingAutoAlarmEnabled() {
        val update = parser.parseAlarmUpdates("""{"alarms":[{"id":11,"enabled":false}]}""")
            .single()
        assertEquals(11L, update.alarmId)
        assertEquals("08:00", update.time.toString())
        assertFalse(update.enabled)
    }

    @Test
    fun allowsDeletingAutoAlarmWithWarningData() {
        val deletes = parser.parseAlarmDeletes("""{"alarmIds":[10,11]}""")
        assertEquals(2, deletes.size)
        val auto = deletes.first { it.alarmId == 11L }
        assertTrue(auto.isAuto)
    }

    @Test
    fun updatesEventFieldsForSubsequentScope() {
        val update = parser.parseEventUpdates(
            """
            {"events":[{
              "id":200,
              "scope":"this_and_subsequent",
              "title":"Standup v2",
              "start":"09:30",
              "end":"10:00",
              "withAlarm":false
            }]}
            """.trimIndent(),
        ).single()
        assertEquals(EventScope.THIS_AND_SUBSEQUENT, update.scope)
        assertEquals(3, update.affectedCount)
        assertEquals("Standup v2", update.title)
        assertEquals("09:30", update.start.toString())
        assertFalse(update.withAlarm)
        assertTrue(update.isSeries)
    }

    @Test
    fun rejectsEntireSeriesDateChange() {
        val error = expectFailure {
            parser.parseEventUpdates(
                """{"events":[{"id":200,"scope":"entire_series","date":"2026-10-27"}]}""",
            )
        }
        assertEquals("invalid_date", error.code)
    }

    @Test
    fun keepsEventTagsWhenOmittedAndReplacesWhenProvided() {
        val kept = parser.parseEventUpdates("""{"events":[{"id":100,"title":"New"}]}""")
            .single()
        assertEquals(listOf("personal"), kept.tags.map { it.name })

        val replaced = parser.parseEventUpdates("""{"events":[{"id":100,"tags":[]}]}""")
            .single()
        assertTrue(replaced.tags.isEmpty())
    }

    @Test
    fun parsesEventDeleteScopeAndAlarmCount() {
        val delete = parser.parseEventDeletes(
            """{"events":[{"id":200,"scope":"this_and_subsequent","keepAlarms":true}]}""",
        ).single()
        assertEquals(3, delete.affectedCount)
        assertTrue(delete.keepAlarms)
        assertEquals(0, delete.linkedAlarms)
    }

    @Test
    fun rejectsUnknownEvent() {
        val error = expectFailure {
            parser.parseEventUpdates("""{"events":[{"id":999,"title":"x"}]}""")
        }
        assertEquals("event_not_found", error.code)
    }

    @Test
    fun expandsScopeOverTheLoadedSubsetOnly() {
        // The view model loads referenced events plus their series, never the whole table.
        val subsetParser = AiToolParser(
            now = now,
            availableTags = tagMap,
            eventsById = mapOf(201L to series[1], 202L to series[2]),
        )
        val update = subsetParser.parseEventUpdates(
            """{"events":[{"id":201,"scope":"entire_series","title":"Renamed"}]}""",
        ).single()
        assertEquals(EventScope.ENTIRE_SERIES, update.scope)
        assertEquals(2, update.affectedCount)
        assertFalse(update.previousWithAlarm)
    }

    @Test
    fun countsLinkedAlarmsFromTheLoadedSubsetIndex() {
        val subsetParser = AiToolParser(
            now = now,
            availableTags = tagMap,
            alarmsById = mapOf(500L to Alarm(id = 500, time = 9 * 3600, eventId = 201)),
            eventsById = mapOf(200L to series[0], 201L to series[1]),
        )
        val delete = subsetParser.parseEventDeletes(
            """{"events":[{"id":200,"scope":"this_and_subsequent"}]}""",
        ).single()
        assertEquals(2, delete.affectedCount)
        assertEquals(1, delete.linkedAlarms)
    }

    @Test
    fun rejectsInvalidEventTimeRange() {
        val error = expectFailure {
            parser.parseEventUpdates(
                """{"events":[{"id":100,"start":"12:00","end":"11:00"}]}""",
            )
        }
        assertEquals("invalid_time_range", error.code)
    }

    private fun expectFailure(block: () -> Unit): AiToolParseException {
        try {
            block()
            fail("Expected AiToolParseException")
        } catch (e: AiToolParseException) {
            return e
        }
        error("unreachable")
    }
}
