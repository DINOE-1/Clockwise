package com.dinoe.clockwise.ai

import com.dinoe.clockwise.data.Event
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiDraftEditorTest {
    private val now = LocalDateTime.of(2026, 3, 10, 12, 0)

    private fun event(
        title: String = "Stand-up",
        date: LocalDate = LocalDate.of(2026, 3, 11),
        start: LocalTime = LocalTime.of(9, 0),
        end: LocalTime = LocalTime.of(9, 30),
        withAlarm: Boolean = false,
        repeatType: Int = Event.REPEAT_NONE,
        repeatDays: Set<Int> = emptySet(),
        repeatUntil: LocalDate? = null,
    ) = EventDraft(
        title = title,
        note = "",
        date = date,
        start = start,
        end = end,
        withAlarm = withAlarm,
        repeatType = repeatType,
        repeatDays = repeatDays,
        repeatUntil = repeatUntil,
        occurrenceCount = 1,
        reminderMinutes = Event.REMINDER_OFF,
        tags = emptyList(),
    )

    @Test
    fun validEventHasNoIssue() {
        assertNull(AiDraftEditor.eventIssue(event(), now))
    }

    @Test
    fun blankTitleIsRejected() {
        assertEquals(
            AiDraftIssue.TITLE_REQUIRED,
            AiDraftEditor.eventIssue(event(title = "  "), now),
        )
    }

    @Test
    fun endBeforeStartIsRejected() {
        assertEquals(
            AiDraftIssue.INVALID_TIME_RANGE,
            AiDraftEditor.eventIssue(
                event(start = LocalTime.of(10, 0), end = LocalTime.of(9, 0)),
                now,
            ),
        )
    }

    @Test
    fun eventWithoutEndTimeHasNoIssue() {
        assertNull(
            AiDraftEditor.eventIssue(
                event(start = LocalTime.of(9, 0), end = LocalTime.of(9, 0)),
                now,
            ),
        )
    }

    @Test
    fun alarmInPastIsRejected() {
        assertEquals(
            AiDraftIssue.ALARM_IN_PAST,
            AiDraftEditor.eventIssue(
                event(
                    date = LocalDate.of(2026, 3, 9),
                    start = LocalTime.of(9, 0),
                    withAlarm = true,
                ),
                now,
            ),
        )
    }

    @Test
    fun weeklyRepeatMustIncludeTheEventWeekday() {
        val date = LocalDate.of(2026, 3, 11)
        assertEquals(
            AiDraftIssue.REPEAT_WEEKDAY_MISSING,
            AiDraftEditor.eventIssue(
                event(
                    date = date,
                    repeatType = Event.REPEAT_WEEKLY,
                    repeatDays = setOf(1),
                    repeatUntil = date.plusMonths(1),
                ),
                now,
            ),
        )
        assertNull(
            AiDraftEditor.eventIssue(
                event(
                    date = date,
                    repeatType = Event.REPEAT_WEEKLY,
                    repeatDays = setOf(date.dayOfWeek.value),
                    repeatUntil = date.plusMonths(1),
                ),
                now,
            ),
        )
    }

    @Test
    fun repeatUntilMustBeAfterTheEventDate() {
        val date = LocalDate.of(2026, 3, 11)
        assertEquals(
            AiDraftIssue.REPEAT_UNTIL_INVALID,
            AiDraftEditor.eventIssue(
                event(
                    date = date,
                    repeatType = Event.REPEAT_DAILY,
                    repeatUntil = date,
                ),
                now,
            ),
        )
    }

    @Test
    fun normalizeEventDropsRepeatDataWhenNotRepeating() {
        val normalized = AiDraftEditor.normalizeEvent(
            event(
                repeatType = Event.REPEAT_NONE,
                repeatDays = setOf(3),
                repeatUntil = LocalDate.of(2026, 4, 1),
            ).copy(occurrenceCount = 9),
        )
        assertEquals(1, normalized.occurrenceCount)
        assertEquals(emptySet<Int>(), normalized.repeatDays)
        assertNull(normalized.repeatUntil)
    }

    @Test
    fun normalizeEventRecomputesOccurrences() {
        val date = LocalDate.of(2026, 3, 11)
        val normalized = AiDraftEditor.normalizeEvent(
            event(
                date = date,
                repeatType = Event.REPEAT_DAILY,
                repeatUntil = date.plusDays(2),
            ),
        )
        assertEquals(3, normalized.occurrenceCount)
    }

    @Test
    fun alarmWithDateMustBeInTheFuture() {
        val draft = AlarmDraft(
            time = LocalTime.of(8, 0),
            date = LocalDate.of(2026, 3, 9),
            name = "",
            note = "",
            enabled = true,
            oneTime = false,
            tags = emptyList(),
        )
        assertEquals(AiDraftIssue.ALARM_IN_PAST, AiDraftEditor.alarmIssue(draft, now))
        assertNull(AiDraftEditor.alarmIssue(draft.copy(date = null), now))
    }

    @Test
    fun stackNeedsTwoDistinctTimes() {
        fun stack(times: List<LocalTime>) = AlarmStackDraft(
            times = times,
            name = "",
            note = "",
            enabled = true,
            oneTime = false,
            tags = emptyList(),
        )
        assertEquals(
            AiDraftIssue.STACK_TIMES_INVALID,
            AiDraftEditor.stackIssue(stack(listOf(LocalTime.of(7, 0), LocalTime.of(7, 0)))),
        )
        assertEquals(
            AiDraftIssue.STACK_TIMES_INVALID,
            AiDraftEditor.stackIssue(stack(listOf(LocalTime.of(7, 0)))),
        )
        assertNull(
            AiDraftEditor.stackIssue(
                stack(listOf(LocalTime.of(7, 0), LocalTime.of(7, 10))),
            ),
        )
    }
}
