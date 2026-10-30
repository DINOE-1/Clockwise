package com.dinoe.clockwise.data

import android.content.Context
import com.dinoe.clockwise.ClockwiseApp
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId

data class IcsImportResult(
    val imported: Int,
    val skipped: Int,
)

/**
 * Reads and writes iCalendar files. Export covers calendar events only; alarms and tags are not
 * calendar data. Import appends events through [ScheduleWriter] so recurrence and reminders follow
 * the same rules as events created in the UI.
 */
class IcsRepository(context: Context) {
    private val app = context.applicationContext as ClockwiseApp
    private val eventDao = app.database.eventDao()
    private val scheduleWriter = app.scheduleWriter

    suspend fun exportIcs(output: OutputStream) {
        writeIcs(output, eventDao.getAll())
    }

    fun readImportText(input: InputStream, declaredSizeBytes: Long? = null): String {
        if (declaredSizeBytes != null && declaredSizeBytes > MAX_ICS_BYTES) {
            throw icsTooLargeError()
        }
        return readBoundedText(input, MAX_ICS_BYTES) { icsTooLargeError() }
    }

    suspend fun importIcs(text: String): IcsImportResult {
        if (text.length.toLong() > MAX_ICS_BYTES) throw icsTooLargeError()
        val drafts = parseIcs(text, ZoneId.systemDefault())
        var imported = 0
        var skipped = 0
        for (draft in drafts) {
            try {
                scheduleWriter.addEvent(
                    title = draft.title,
                    note = draft.note,
                    date = draft.date,
                    start = draft.start,
                    end = draft.end,
                    withAlarm = false,
                    tags = emptyList(),
                    repeat = draft.repeat,
                    reminderMinutes = draft.reminderMinutes,
                    endDayOffset = draft.endDayOffset,
                    allDay = draft.allDay,
                )
                imported++
            } catch (error: ScheduleLimitException) {
                skipped++
            } catch (error: IllegalArgumentException) {
                skipped++
            }
        }
        if (imported == 0) {
            throw IcsException("No events could be imported")
        }
        return IcsImportResult(imported = imported, skipped = skipped)
    }

    internal companion object {
        /** Upper bound for one calendar document; a hostile file must not be decoded unbounded. */
        const val MAX_ICS_BYTES: Long = 16L * 1024 * 1024
    }
}

private fun icsTooLargeError(): IcsException = IcsException(
    "The calendar file is larger than ${IcsRepository.MAX_ICS_BYTES / (1024L * 1024L)} MB",
)
