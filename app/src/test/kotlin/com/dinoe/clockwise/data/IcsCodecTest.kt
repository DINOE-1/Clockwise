package com.dinoe.clockwise.data

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsCodecTest {
    private val zone = ZoneId.of("Asia/Taipei")

    @Test
    fun exportsTimedEventAsFloatingLocalTime() {
        val text = export(event())

        assertTrue(text.contains("BEGIN:VCALENDAR"))
        assertTrue(text.contains("SUMMARY:Meeting"))
        assertTrue(text.contains("DTSTART:20261030T100000"))
        assertTrue(text.contains("DTEND:20261030T110000"))
    }

    @Test
    fun exportsAllDayEventWithDateValues() {
        val text = export(event(allDay = true, start = 0, end = 0))

        assertTrue(text.contains("DTSTART;VALUE=DATE:20261030"))
        assertTrue(text.contains("DTEND;VALUE=DATE:20261031"))
    }

    @Test
    fun exportsMultiDayTimedEventWithLaterEndDate() {
        val text = export(
            event(start = 23 * 3600, end = 7 * 3600 + Event.SECONDS_PER_DAY),
        )

        assertTrue(text.contains("DTSTART:20261030T230000"))
        assertTrue(text.contains("DTEND:20261031T070000"))
    }

    @Test
    fun collapsesMatchingDailySeriesIntoOneEventWithRrule() {
        val base = LocalDate.of(2026, 10, 26)
        val events = (0..2).map { index ->
            event(
                id = 10L + index,
                date = base.plusDays(index.toLong()).toEpochDay(),
                seriesId = 10,
                occurrenceIndex = index,
                repeatType = Event.REPEAT_DAILY,
            )
        }

        val text = export(events)

        assertEquals(1, countOccurrences(text, "BEGIN:VEVENT"))
        assertTrue(text.contains("RRULE:FREQ=DAILY;UNTIL=20261028"))
    }

    @Test
    fun collapsesWeeklySeriesWithByDay() {
        val base = LocalDate.of(2026, 10, 26)
        val dates = listOf(base, base.plusDays(2), base.plusDays(4))
        val events = dates.mapIndexed { index, date ->
            event(
                id = 20L + index,
                date = date.toEpochDay(),
                seriesId = 20,
                occurrenceIndex = index,
                repeatType = Event.REPEAT_WEEKLY,
                repeatDays = "1,3,5",
            )
        }

        val text = export(events)

        assertTrue(text.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;UNTIL=20261030"))
        assertEquals(1, countOccurrences(text, "BEGIN:VEVENT"))
    }

    @Test
    fun exportsEditedOccurrenceAsSeparateEvents() {
        val base = LocalDate.of(2026, 10, 26)
        val events = (0..1).map { index ->
            event(
                id = 30L + index,
                title = if (index == 1) "Changed" else "Meeting",
                date = base.plusDays(index.toLong()).toEpochDay(),
                seriesId = 30,
                occurrenceIndex = index,
                repeatType = Event.REPEAT_DAILY,
            )
        }

        val text = export(events)

        assertEquals(2, countOccurrences(text, "BEGIN:VEVENT"))
        assertFalse(text.contains("RRULE:"))
    }

    @Test
    fun exportsReminderAsValarm() {
        val text = export(event(reminderMinutes = 15))

        assertTrue(text.contains("BEGIN:VALARM"))
        assertTrue(text.contains("ACTION:DISPLAY"))
        assertTrue(text.contains("TRIGGER:-PT15M"))
    }

    @Test
    fun parsesTimedEvent() {
        val draft = parseIcs(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Standup
            DTSTART:20261030T090000
            DTEND:20261030T093000
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
            zone,
        ).single()

        assertEquals("Standup", draft.title)
        assertEquals(LocalDate.of(2026, 10, 30), draft.date)
        assertEquals(9 * 3600, draft.start.toSecondOfDay())
        assertEquals(9 * 3600 + 30 * 60, draft.end.toSecondOfDay())
        assertFalse(draft.allDay)
    }

    @Test
    fun parsesAllDayEvent() {
        val draft = parseIcs(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Holiday
            DTSTART;VALUE=DATE:20261030
            DTEND;VALUE=DATE:20261031
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
            zone,
        ).single()

        assertTrue(draft.allDay)
        assertEquals(LocalDate.of(2026, 10, 30), draft.date)
        assertEquals(Event.REMINDER_OFF, draft.reminderMinutes)
    }

    @Test
    fun parsesMultiDayAllDayEventAsTimedSpan() {
        val draft = parseIcs(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Trip
            DTSTART;VALUE=DATE:20261030
            DTEND;VALUE=DATE:20261102
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
            zone,
        ).single()

        assertFalse(draft.allDay)
        assertEquals(3, draft.endDayOffset)
        assertEquals(0, draft.start.toSecondOfDay())
        assertEquals(0, draft.end.toSecondOfDay())
    }

    @Test
    fun convertsUtcStartToTheLocalZone() {
        val draft = parseIcs(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Call
            DTSTART:20261030T020000Z
            DTEND:20261030T030000Z
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
            zone,
        ).single()

        assertEquals(10 * 3600, draft.start.toSecondOfDay())
        assertEquals(11 * 3600, draft.end.toSecondOfDay())
    }

    @Test
    fun parsesDailyRruleUntil() {
        val draft = parseIcs(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Daily
            DTSTART:20261030T090000
            DTEND:20261030T100000
            RRULE:FREQ=DAILY;UNTIL=20261105
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
            zone,
        ).single()

        assertEquals(Event.REPEAT_DAILY, draft.repeat.type)
        assertEquals(LocalDate.of(2026, 11, 5).toEpochDay(), draft.repeat.untilEpochDay)
    }

    @Test
    fun parsesWeeklyRruleWithByDay() {
        val draft = parseIcs(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Class
            DTSTART:20261026T090000
            DTEND:20261026T100000
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261106
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
            zone,
        ).single()

        assertEquals(Event.REPEAT_WEEKLY, draft.repeat.type)
        assertEquals(setOf(1, 3), draft.repeat.days)
    }

    @Test
    fun capsUnboundedRruleToTheSeriesLimit() {
        val draft = parseIcs(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Forever
            DTSTART:20260101T090000
            DTEND:20260101T100000
            RRULE:FREQ=DAILY
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
            zone,
        ).single()

        val plan = planRecurrence(draft.date, draft.repeat)
        assertFalse(plan.limitReached)
        assertEquals(MAX_OCCURRENCES_PER_SERIES, plan.totalOccurrences)
    }

    @Test
    fun parsesValarmTriggerMinutes() {
        val draft = parseIcs(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Meeting
            DTSTART:20261030T090000
            DTEND:20261030T100000
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
            zone,
        ).single()

        assertEquals(30, draft.reminderMinutes)
    }

    @Test
    fun unescapesTextAndJoinsFoldedLines() {
        val text = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "SUMMARY:Team\\, sync\\; Q4",
            "DESCRIPTION:Line one\\nLine two",
            "DTSTART:20261030T090000",
            "DTEND:20261030T100000",
            "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n")

        val draft = parseIcs(text, zone).single()

        assertEquals("Team, sync; Q4", draft.title)
        assertEquals("Line one\nLine two", draft.note)
    }

    @Test
    fun joinsFoldedSummary() {
        val text = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "SUMMARY:This summary is folded across two content lines because it is longer than",
            " seventy-five octets",
            "DTSTART:20261030T090000",
            "DTEND:20261030T100000",
            "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n")

        val draft = parseIcs(text, zone).single()

        assertEquals(
            "This summary is folded across two content lines because it is longer than" +
                "seventy-five octets",
            draft.title,
        )
    }

    @Test
    fun roundTripsTimedEventWithReminderAndNote() {
        val original = event(title = "Team, sync", note = "Bring notes", reminderMinutes = 10)
        val text = export(listOf(original))

        val draft = parseIcs(text, zone).single()

        assertEquals("Team, sync", draft.title)
        assertEquals("Bring notes", draft.note)
        assertEquals(LocalDate.ofEpochDay(original.date), draft.date)
        assertEquals(original.start, draft.start.toSecondOfDay())
        assertEquals(original.end, draft.end.toSecondOfDay())
        assertEquals(10, draft.reminderMinutes)
    }

    @Test
    fun rejectsFileWithoutEvents() {
        val error = runCatching {
            parseIcs("BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR", zone)
        }.exceptionOrNull()

        assertTrue(error is IcsException)
    }

    private fun export(event: Event): String = export(listOf(event))

    private fun export(events: List<Event>): String {
        val output = ByteArrayOutputStream()
        writeIcs(output, events)
        return output.toString(Charsets.UTF_8.name())
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun event(
        id: Long = 1,
        title: String = "Meeting",
        note: String = "",
        date: Long = LocalDate.of(2026, 10, 30).toEpochDay(),
        start: Int = 10 * 3600,
        end: Int = 11 * 3600,
        seriesId: Long = 0,
        occurrenceIndex: Int = 0,
        repeatType: Int = Event.REPEAT_NONE,
        repeatDays: String = "",
        reminderMinutes: Int = Event.REMINDER_OFF,
        allDay: Boolean = false,
    ): Event = Event(
        id = id,
        title = title,
        note = note,
        date = date,
        start = start,
        end = end,
        seriesId = seriesId,
        occurrenceIndex = occurrenceIndex,
        repeatType = repeatType,
        repeatDays = repeatDays,
        reminderMinutes = reminderMinutes,
        allDay = allDay,
    )
}
