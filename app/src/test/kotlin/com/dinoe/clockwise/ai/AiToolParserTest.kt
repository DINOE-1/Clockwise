package com.dinoe.clockwise.ai

import com.dinoe.clockwise.data.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime

class AiToolParserTest {
    private val now = LocalDateTime.of(2026, 10, 22, 19, 0)
    private val tags = mapOf("work" to 1L, "personal" to 2L)
    private val parser = AiToolParser(now = now, availableTags = tags)

    @Test
    fun parsesPlainAndDatedAlarms() {
        val alarms = parser.parseAlarms(
            """
            {"alarms":[
              {"time":"07:00","name":"Wake","tags":["personal"]},
              {"time":"08:30","date":"2026-10-23","name":"Train"}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, alarms.size)
        assertEquals("07:00", alarms[0].time.toString())
        assertEquals(null, alarms[0].date)
        assertEquals("Wake", alarms[0].name)
        assertEquals(listOf("personal"), alarms[0].tags.map { it.name })
        assertEquals("2026-10-23", alarms[1].date.toString())
        assertEquals("Train", alarms[1].name)
    }

    @Test
    fun parsesAlarmStackWithSharedFields() {
        val stack = parser.parseAlarmStacks(
            """
            {"stacks":[{
              "times":["07:20","07:00","07:10"],
              "name":"Wake up",
              "note":"Three chances",
              "oneTime":true,
              "tags":["personal"]
            }]}
            """.trimIndent(),
        ).single()
        assertEquals(listOf("07:00", "07:10", "07:20"), stack.times.map { it.toString() })
        assertEquals("Wake up", stack.name)
        assertEquals("Three chances", stack.note)
        assertTrue(stack.enabled)
        assertTrue(stack.oneTime)
        assertEquals(listOf("personal"), stack.tags.map { it.name })
    }

    @Test
    fun rejectsAlarmStackWithFewerThanTwoTimes() {
        val error = expectFailure {
            parser.parseAlarmStacks("""{"stacks":[{"times":["07:00"]}]}""")
        }
        assertEquals("invalid_time", error.code)
    }

    @Test
    fun rejectsAlarmStackWithDuplicateTimes() {
        val error = expectFailure {
            parser.parseAlarmStacks("""{"stacks":[{"times":["07:00","07:00"]}]}""")
        }
        assertEquals("duplicate_time", error.code)
    }

    @Test
    fun parsesDisabledAndOneTimeAlarms() {
        val alarm = parser.parseAlarms(
            """{"alarms":[{"time":"07:00","enabled":false,"oneTime":true}]}""",
        ).single()
        assertFalse(alarm.enabled)
        assertTrue(alarm.oneTime)
    }

    @Test
    fun parsesEventReminderMinutes() {
        val event = parser.parseEvents(
            """
            {"events":[{
              "title":"Meeting",
              "date":"2026-10-25",
              "start":"10:00",
              "end":"11:00",
              "reminderMinutes":10
            }]}
            """.trimIndent(),
        ).single()
        assertEquals(10, event.reminderMinutes)
    }

    @Test
    fun rejectsUnsupportedReminderMinutes() {
        val error = expectFailure {
            parser.parseEvents(
                """
                {"events":[{
                  "title":"Meeting",
                  "date":"2026-10-25",
                  "start":"10:00",
                  "end":"11:00",
                  "reminderMinutes":15
                }]}
                """.trimIndent(),
            )
        }
        assertEquals("invalid_reminder", error.code)
    }

    @Test
    fun rejectsInvalidTime() {
        val error = expectFailure {
            parser.parseAlarms("""{"alarms":[{"time":"24:00"}]}""")
        }
        assertEquals("invalid_time", error.code)
    }

    @Test
    fun rejectsDatedAlarmInThePast() {
        val error = expectFailure {
            parser.parseAlarms("""{"alarms":[{"time":"10:00","date":"2026-10-22"}]}""")
        }
        assertEquals("alarm_in_past", error.code)
    }

    @Test
    fun rejectsUnknownTagsAndListsAvailableOnes() {
        val error = expectFailure {
            parser.parseAlarms("""{"alarms":[{"time":"07:00","tags":["gym"]}]}""")
        }
        assertEquals("unknown_tag", error.code)
        assertTrue(error.message.contains("work"))
    }

    @Test
    fun rejectsTooManyAlarms() {
        val items = (1..31).joinToString(",") { """{"time":"07:00","name":"a$it"}""" }
        val error = expectFailure { parser.parseAlarms("""{"alarms":[$items]}""") }
        assertEquals("too_many_items", error.code)
    }

    @Test
    fun parsesOneOffEventWithAlarm() {
        val events = parser.parseEvents(
            """
            {"events":[{
              "title":"Meeting",
              "date":"2026-10-25",
              "start":"10:00",
              "end":"11:00",
              "withAlarm":true,
              "tags":["work"]
            }]}
            """.trimIndent(),
        )
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(1, event.occurrenceCount)
        assertTrue(event.withAlarm)
        assertEquals(Event.REPEAT_NONE, event.repeatType)
        assertEquals(listOf("work"), event.tags.map { it.name })
    }

    @Test
    fun rejectsEndBeforeStart() {
        val error = expectFailure {
            parser.parseEvents(
                """{"events":[{"title":"x","date":"2026-10-25","start":"11:00","end":"10:00"}]}""",
            )
        }
        assertEquals("invalid_time_range", error.code)
    }

    @Test
    fun parsesEventWithoutEndTime() {
        val events = parser.parseEvents(
            """{"events":[{"title":"吃藥","date":"2026-10-25","start":"14:00"}]}""",
        )
        val event = events.single()
        assertEquals(event.start, event.end)
    }

    @Test
    fun parsesAllDayEventWithoutTimes() {
        val event = parser.parseEvents(
            """
            {"events":[{
              "title":"Trip",
              "date":"2026-10-25",
              "allDay":true,
              "withAlarm":true
            }]}
            """.trimIndent(),
        ).single()
        assertTrue(event.allDay)
        assertFalse(event.withAlarm)
        assertEquals(Event.REMINDER_OFF, event.reminderMinutes)
        assertEquals(0, event.endDayOffset)
        assertEquals(event.start, event.end)
    }

    @Test
    fun parsesOvernightEventWithEndDate() {
        val event = parser.parseEvents(
            """
            {"events":[{
              "title":"Night shift",
              "date":"2026-10-25",
              "start":"23:00",
              "end":"07:00",
              "endDate":"2026-10-26"
            }]}
            """.trimIndent(),
        ).single()
        assertEquals(1, event.endDayOffset)
        assertFalse(event.allDay)
    }

    @Test
    fun rejectsEndDateBeforeStartDate() {
        val error = expectFailure {
            parser.parseEvents(
                """
                {"events":[{
                  "title":"Overnight",
                  "date":"2026-10-25",
                  "start":"23:00",
                  "end":"07:00",
                  "endDate":"2026-10-24"
                }]}
                """.trimIndent(),
            )
        }
        assertEquals("invalid_end_date", error.code)
    }

    @Test
    fun parsesDailyRepeatInclusiveUntil() {
        val events = parser.parseEvents(
            """
            {"events":[{
              "title":"Standup",
              "date":"2026-10-26",
              "start":"09:00",
              "end":"09:15",
              "repeat":{"type":"daily","until":"2026-10-30"}
            }]}
            """.trimIndent(),
        )
        assertEquals(5, events.single().occurrenceCount)
    }

    @Test
    fun parsesWeeklyRepeat() {
        val events = parser.parseEvents(
            """
            {"events":[{
              "title":"Gym",
              "date":"2026-10-26",
              "start":"18:00",
              "end":"19:00",
              "repeat":{"type":"weekly","days":[1,3,5],"until":"2026-10-30"}
            }]}
            """.trimIndent(),
        )
        assertEquals(3, events.single().occurrenceCount)
    }

    @Test
    fun rejectsWeeklyRepeatWithoutBaseWeekday() {
        val error = expectFailure {
            parser.parseEvents(
                """
                {"events":[{
                  "title":"Gym",
                  "date":"2026-10-26",
                  "start":"18:00",
                  "end":"19:00",
                  "repeat":{"type":"weekly","days":[2,4],"until":"2026-10-30"}
                }]}
                """.trimIndent(),
            )
        }
        assertEquals("invalid_days", error.code)
    }

    @Test
    fun rejectsRepeatWithoutUntil() {
        val error = expectFailure {
            parser.parseEvents(
                """
                {"events":[{
                  "title":"Gym",
                  "date":"2026-10-26",
                  "start":"18:00",
                  "end":"19:00",
                  "repeat":{"type":"daily"}
                }]}
                """.trimIndent(),
            )
        }
        assertEquals("missing_until", error.code)
    }

    @Test
    fun rejectsAlarmOnPastEvent() {
        val error = expectFailure {
            parser.parseEvents(
                """
                {"events":[{
                  "title":"Past",
                  "date":"2026-10-21",
                  "start":"10:00",
                  "end":"11:00",
                  "withAlarm":true
                }]}
                """.trimIndent(),
            )
        }
        assertEquals("alarm_in_past", error.code)
    }

    @Test
    fun rejectsTooManyOccurrences() {
        val error = expectFailure {
            parser.parseEvents(
                """
                {"events":[{
                  "title":"Daily",
                  "date":"2026-10-26",
                  "start":"09:00",
                  "end":"09:15",
                  "repeat":{"type":"daily","until":"2027-10-01"}
                }]}
                """.trimIndent(),
            )
        }
        assertEquals("too_many_occurrences", error.code)
    }

    @Test
    fun rejectsMoreThanFiveTags() {
        val error = expectFailure {
            parser.parseAlarms(
                """{"alarms":[{"time":"07:00","tags":["work","personal","a","b","c","d"]}]}""",
            )
        }
        assertEquals("too_many_tags", error.code)
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
