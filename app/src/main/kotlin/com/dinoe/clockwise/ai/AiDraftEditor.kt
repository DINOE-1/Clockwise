package com.dinoe.clockwise.ai

import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.data.RecurrenceDates
import java.time.LocalDateTime
import java.time.LocalTime

enum class AiDraftIssue {
    ALARM_IN_PAST,
    TITLE_REQUIRED,
    INVALID_TIME_RANGE,
    REPEAT_UNTIL_INVALID,
    REPEAT_WEEKDAYS_REQUIRED,
    REPEAT_WEEKDAY_MISSING,
    TOO_MANY_OCCURRENCES,
    STACK_TIMES_INVALID,
}

/**
 * Validation and normalization for hand-edited create drafts. The checks mirror [AiToolParser] so a
 * manually edited proposal cannot reach the database in a state the parser would have rejected.
 */
object AiDraftEditor {
    const val MAX_EVENT_TITLE_CHARS = 120
    const val MAX_ALARM_NAME_CHARS = 100
    const val MAX_NOTE_CHARS = 500

    fun alarmIssue(draft: AlarmDraft, now: LocalDateTime): AiDraftIssue? =
        if (draft.date != null && !draft.date.atTime(draft.time).isAfter(now)) {
            AiDraftIssue.ALARM_IN_PAST
        } else {
            null
        }

    fun stackIssue(draft: AlarmStackDraft): AiDraftIssue? =
        if (draft.times.size < 2 || draft.times.toSet().size != draft.times.size) {
            AiDraftIssue.STACK_TIMES_INVALID
        } else {
            null
        }

    fun eventIssue(
        draft: EventDraft,
        now: LocalDateTime,
        planned: RecurrenceDates? = null,
    ): AiDraftIssue? {
        if (draft.title.isBlank()) return AiDraftIssue.TITLE_REQUIRED
        if (!draft.allDay) {
            val startDateTime = draft.date.atTime(draft.start)
            val endDateTime = draft.date.plusDays(draft.endDayOffset.toLong()).atTime(draft.end)
            if (endDateTime.isBefore(startDateTime)) return AiDraftIssue.INVALID_TIME_RANGE
            if (draft.withAlarm && !startDateTime.isAfter(now)) {
                return AiDraftIssue.ALARM_IN_PAST
            }
        }
        if (draft.repeatType == Event.REPEAT_NONE) return null
        val until = draft.repeatUntil ?: return AiDraftIssue.REPEAT_UNTIL_INVALID
        return when (
            EventRecurrencePlanner.issue(
                date = draft.date,
                type = draft.repeatType,
                days = draft.repeatDays,
                untilEpochDay = until.toEpochDay(),
                requireWeekdayMatch = true,
                planned = planned,
            )
        ) {
            null -> null
            RecurrenceDraftIssue.UNTIL_INVALID -> AiDraftIssue.REPEAT_UNTIL_INVALID
            RecurrenceDraftIssue.WEEKDAYS_REQUIRED -> AiDraftIssue.REPEAT_WEEKDAYS_REQUIRED
            RecurrenceDraftIssue.WEEKDAY_MISSING -> AiDraftIssue.REPEAT_WEEKDAY_MISSING
            RecurrenceDraftIssue.TOO_MANY_OCCURRENCES -> AiDraftIssue.TOO_MANY_OCCURRENCES
        }
    }

    fun normalizeAlarm(draft: AlarmDraft): AlarmDraft = draft.copy(
        name = draft.name.trim().take(MAX_ALARM_NAME_CHARS),
        note = draft.note.trim().take(MAX_NOTE_CHARS),
    )

    fun normalizeStack(draft: AlarmStackDraft): AlarmStackDraft = draft.copy(
        times = draft.times.sorted(),
        name = draft.name.trim().take(MAX_ALARM_NAME_CHARS),
        note = draft.note.trim().take(MAX_NOTE_CHARS),
    )

    fun normalizeEvent(draft: EventDraft): EventDraft {
        val title = draft.title.trim().take(MAX_EVENT_TITLE_CHARS)
        val note = draft.note.trim().take(MAX_NOTE_CHARS)
        val normalized = draft.copy(
            title = title,
            note = note,
            start = if (draft.allDay) LocalTime.MIDNIGHT else draft.start,
            end = if (draft.allDay) LocalTime.MIDNIGHT else draft.end,
            withAlarm = draft.withAlarm && !draft.allDay,
            reminderMinutes = if (draft.allDay) Event.REMINDER_OFF else draft.reminderMinutes,
            endDayOffset = if (draft.allDay) 0 else draft.endDayOffset,
        )
        if (normalized.repeatType == Event.REPEAT_NONE) {
            return normalized.copy(
                repeatDays = emptySet(),
                repeatUntil = null,
                occurrenceCount = 1,
            )
        }
        return normalized.copy(
            occurrenceCount = EventRecurrencePlanner.plan(
                date = normalized.date,
                type = normalized.repeatType,
                days = normalized.repeatDays,
                untilEpochDay = normalized.repeatUntil?.toEpochDay() ?: 0L,
            ).totalOccurrences,
        )
    }
}
