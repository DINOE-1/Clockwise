package com.dinoe.clockwise.ai

import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.data.MAX_OCCURRENCES_PER_SERIES
import com.dinoe.clockwise.data.planRecurrence
import java.time.LocalDateTime

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

    fun eventIssue(draft: EventDraft, now: LocalDateTime): AiDraftIssue? {
        if (draft.title.isBlank()) return AiDraftIssue.TITLE_REQUIRED
        if (!draft.end.isAfter(draft.start)) return AiDraftIssue.INVALID_TIME_RANGE
        if (draft.withAlarm && !draft.date.atTime(draft.start).isAfter(now)) {
            return AiDraftIssue.ALARM_IN_PAST
        }
        if (draft.repeatType == Event.REPEAT_NONE) return null
        val until = draft.repeatUntil ?: return AiDraftIssue.REPEAT_UNTIL_INVALID
        if (!until.isAfter(draft.date)) return AiDraftIssue.REPEAT_UNTIL_INVALID
        if (draft.repeatType == Event.REPEAT_WEEKLY) {
            if (draft.repeatDays.isEmpty()) return AiDraftIssue.REPEAT_WEEKDAYS_REQUIRED
            if (draft.date.dayOfWeek.value !in draft.repeatDays) {
                return AiDraftIssue.REPEAT_WEEKDAY_MISSING
            }
        }
        val plan = planRecurrence(draft.date, draft.recurrenceRule())
        if (plan.limitReached || plan.totalOccurrences > MAX_OCCURRENCES_PER_SERIES) {
            return AiDraftIssue.TOO_MANY_OCCURRENCES
        }
        return null
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
        if (draft.repeatType == Event.REPEAT_NONE) {
            return draft.copy(
                title = title,
                note = note,
                repeatDays = emptySet(),
                repeatUntil = null,
                occurrenceCount = 1,
            )
        }
        return draft.copy(
            title = title,
            note = note,
            occurrenceCount = planRecurrence(draft.date, draft.recurrenceRule()).totalOccurrences,
        )
    }
}
