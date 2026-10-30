package com.dinoe.clockwise.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.alarm.AlarmScheduler
import com.dinoe.clockwise.alarm.ReminderRestorationTrigger
import com.dinoe.clockwise.alarm.ReminderScheduler
import com.dinoe.clockwise.alarm.ScheduleResult
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

data class BackupImportResult(
    val events: Int,
    val alarms: Int,
    val tags: Int,
    val scheduledAlarms: Int,
    val unscheduledAlarms: Int,
)

class BackupException(message: String) : Exception(message)

/**
 * A backup input is rejected before it is decoded or parsed once its byte count exceeds the limit.
 * One character is at least one UTF-8 byte, so the character count of an already decoded document is
 * a safe upper bound as well.
 */
internal fun backupInputTooLarge(
    byteCount: Long,
    limitBytes: Long = BackupRepository.MAX_BACKUP_BYTES,
): Boolean = byteCount > limitBytes

internal fun backupTooLargeError(): BackupException = BackupException(
    "The backup file is larger than ${BackupRepository.MAX_BACKUP_BYTES / BYTES_PER_MEGABYTE} MB",
)

/** Buffers [input] as UTF-8 text, refusing to hold more than [limitBytes]. */
internal fun readBoundedBackupText(
    input: InputStream,
    limitBytes: Long = BackupRepository.MAX_BACKUP_BYTES,
): String = readBoundedText(input, limitBytes) { backupTooLargeError() }

/**
 * Buffers [input] as UTF-8 text, refusing to hold more than [limitBytes]. [tooLarge] supplies the
 * error for the caller's file type so the message names the right document.
 */
internal fun readBoundedText(
    input: InputStream,
    limitBytes: Long,
    tooLarge: () -> Exception,
): String {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limitBytes) throw tooLarge()
        bytes.write(buffer, 0, read)
    }
    return bytes.toString(Charsets.UTF_8.name())
}

private const val BYTES_PER_MEGABYTE = 1024L * 1024L
private const val READ_BUFFER_BYTES = 8 * 1024

/**
 * Versioned JSON export/import for user-visible data. AI chat sessions and the API key are never
 * included. Import replaces all events, alarms, and tags in one transaction.
 */
class BackupRepository(context: Context) {
    private val app = context.applicationContext as ClockwiseApp
    private val database = app.database
    private val eventDao = database.eventDao()
    private val alarmDao = database.alarmDao()
    private val tagDao = database.tagDao()
    private val json = Json { prettyPrint = true }

    /** Streams the backup document into [output]; only one row is encoded at a time. */
    suspend fun exportJson(output: OutputStream) {
        val events = eventDao.getAll()
        val alarms = alarmDao.getAll()
        val tags = tagDao.getAll()
        val eventTags = tagDao.getEventTagRefs()
        val alarmTags = tagDao.getAlarmTagRefs()
        writeBackupJson(
            output = output,
            schemaVersion = SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            events = events,
            alarms = alarms,
            tags = tags,
            eventTags = eventTags,
            alarmTags = alarmTags,
        )
    }

    /**
     * Reads a selected backup file, refusing to buffer more than [MAX_BACKUP_BYTES]. The size the
     * provider reports is checked before the first read when it is known, so an oversized file is
     * rejected without decoding anything.
     */
    fun readImportText(input: InputStream, declaredSizeBytes: Long? = null): String {
        if (declaredSizeBytes != null && backupInputTooLarge(declaredSizeBytes)) {
            throw backupTooLargeError()
        }
        return readBoundedBackupText(input)
    }

    suspend fun importJson(text: String): BackupImportResult {
        if (backupInputTooLarge(text.length.toLong())) throw backupTooLargeError()
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw BackupException("The selected file is not valid JSON")
        }
        val version = root.optionalLong("schemaVersion")
            ?: throw BackupException("Missing schemaVersion")
        if (version != SCHEMA_VERSION) {
            throw BackupException("Unsupported backup version: $version")
        }
        val eventRows = root.array("events").map { parseEvent(it) }
        val alarmRows = root.array("alarms").map { parseAlarm(it) }
        val tagRows = root.array("tags").map { parseTag(it) }
        val eventTagRows = root.array("eventTags")
            .map { parseEventTag(it) }
            .distinctBy { it.eventId to it.tagId }
        val alarmTagRows = root.array("alarmTags")
            .map { parseAlarmTag(it) }
            .distinctBy { it.alarmId to it.tagId }

        if (eventRows.size > MAX_BACKUP_EVENTS) {
            throw BackupException("The backup contains too many events")
        }
        if (alarmRows.count { it.enabled } > MAX_ACTIVE_ALARMS) {
            throw BackupException("The backup contains too many enabled alarms")
        }
        validateUniqueIds(eventRows.map { it.id }, "event")
        validateUniqueIds(alarmRows.map { it.id }, "alarm")
        validateUniqueIds(tagRows.map { it.id }, "tag")
        validateUniqueIds(tagRows.map { it.name }, "tag name")

        // Ids, series ids, and tag references are resolved before anything is written, so the rows
        // are inserted once with their final ids instead of being updated again afterwards.
        val rows = buildBackupImportRows(
            tagRows = tagRows,
            eventRows = eventRows,
            alarmRows = alarmRows,
            eventTagRows = eventTagRows,
            alarmTagRows = alarmTagRows,
        )

        val oldAlarms = alarmDao.getAll()
        val oldEvents = eventDao.getAll()

        database.withTransaction {
            tagDao.deleteAllEventTags()
            tagDao.deleteAllAlarmTags()
            alarmDao.deleteAllAlarms()
            eventDao.deleteAllEvents()
            tagDao.deleteAllTags()

            tagDao.insertAll(rows.tags)
            eventDao.insertAll(rows.events)
            alarmDao.insertAll(rows.alarms)
            tagDao.insertEventTags(rows.eventTags)
            tagDao.insertAlarmTags(rows.alarmTags)
        }

        // Only cancel the old system alarms after the database replacement committed; a failed
        // import leaves the previous data and its scheduled alarms untouched.
        oldAlarms.forEach { alarm -> AlarmScheduler.cancel(app, alarm) }
        oldEvents.forEach { event -> ReminderScheduler.cancel(app, event.id) }

        var scheduled = 0
        val disabled = mutableListOf<Alarm>()
        alarmDao.getEnabled().forEach { alarm ->
            when (AlarmScheduler.schedule(app, alarm)) {
                is ScheduleResult.Scheduled -> scheduled++
                else -> disabled += alarm.copy(enabled = false, snoozedUntil = null)
            }
        }
        if (disabled.isNotEmpty()) alarmDao.updateAll(disabled)

        // Reminders are scheduled through the application-scoped coordinator, so the import joins a
        // restoration that is already running instead of scanning the event table a second time.
        app.reminderRestoration.restore(ReminderRestorationTrigger.RECOVERY).onFailure { error ->
            Log.w(TAG, "Unable to restore event reminders after import", error)
        }

        return BackupImportResult(
            events = rows.events.size,
            alarms = rows.alarms.size,
            tags = rows.tags.size,
            scheduledAlarms = scheduled,
            unscheduledAlarms = disabled.size,
        )
    }

    private fun parseEvent(element: kotlinx.serialization.json.JsonElement): BackupEvent {
        val obj = element.jsonObject
        val id = obj.requireLong("id", "event")
        val date = obj.requireLong("date", "event $id")
        if (date < MIN_EPOCH_DAY || date > MAX_EPOCH_DAY) {
            throw BackupException("Event $id has an invalid date")
        }
        val start = obj.requireInt("start", "event $id")
        val end = obj.requireInt("end", "event $id")
        val allDay = obj.optionalBoolean("allDay") ?: false
        if (start !in 0..86_399 || end !in 0..MAX_EVENT_SECONDS) {
            throw BackupException("Event $id has an invalid time range")
        }
        if (allDay) {
            if (start != 0 || end != 0) {
                throw BackupException("Event $id has an invalid all-day time range")
            }
        } else if (start > end) {
            throw BackupException("Event $id has an invalid time range")
        }
        val repeatType = obj.optionalInt("repeatType") ?: Event.REPEAT_NONE
        if (repeatType !in Event.REPEAT_NONE..Event.REPEAT_WEEKLY) {
            throw BackupException("Event $id has an invalid repeat type")
        }
        val reminderMinutes = obj.optionalInt("reminderMinutes") ?: Event.REMINDER_OFF
        if (!Event.isValidReminderMinutes(reminderMinutes)) {
            throw BackupException("Event $id has an invalid reminder")
        }
        return BackupEvent(
            id = id,
            title = obj.optionalString("title").orEmpty(),
            note = obj.optionalString("note").orEmpty(),
            date = date,
            start = start,
            end = end,
            seriesId = obj.optionalLong("seriesId") ?: 0L,
            occurrenceIndex = obj.optionalInt("occurrenceIndex") ?: 0,
            repeatType = repeatType,
            repeatDays = obj.optionalString("repeatDays").orEmpty(),
            reminderMinutes = reminderMinutes,
            allDay = allDay,
        )
    }

    private fun parseAlarm(element: kotlinx.serialization.json.JsonElement): BackupAlarm {
        val obj = element.jsonObject
        val id = obj.requireLong("id", "alarm")
        val time = obj.requireInt("time", "alarm $id")
        if (time !in 0..86_399) throw BackupException("Alarm $id has an invalid time")
        val fireDate = obj.optionalLong("fireDate")
        if (fireDate != null && (fireDate < MIN_EPOCH_DAY || fireDate > MAX_EPOCH_DAY)) {
            throw BackupException("Alarm $id has an invalid date")
        }
        return BackupAlarm(
            id = id,
            time = time,
            enabled = obj.optionalBoolean("enabled") ?: true,
            fireDate = fireDate,
            eventId = obj.optionalLong("eventId"),
            name = obj.optionalString("name").orEmpty(),
            note = obj.optionalString("note").orEmpty(),
            oneTime = obj.optionalBoolean("oneTime") ?: false,
            stackId = obj.optionalLong("stackId"),
        )
    }

    private fun parseTag(element: kotlinx.serialization.json.JsonElement): BackupTag {
        val obj = element.jsonObject
        val id = obj.requireLong("id", "tag")
        val name = obj.optionalString("name")?.trim().orEmpty()
        if (name.isBlank()) throw BackupException("Tag $id has an empty name")
        return BackupTag(
            id = id,
            name = name.take(80),
            color = obj.optionalInt("color") ?: 0,
        )
    }

    private fun parseEventTag(element: kotlinx.serialization.json.JsonElement): BackupEventTag {
        val obj = element.jsonObject
        return BackupEventTag(
            eventId = obj.requireLong("eventId", "event tag"),
            tagId = obj.requireLong("tagId", "event tag"),
        )
    }

    private fun parseAlarmTag(element: kotlinx.serialization.json.JsonElement): BackupAlarmTag {
        val obj = element.jsonObject
        return BackupAlarmTag(
            alarmId = obj.requireLong("alarmId", "alarm tag"),
            tagId = obj.requireLong("tagId", "alarm tag"),
        )
    }

    private fun JsonObject.array(key: String): JsonArray =
        (this[key] as? JsonArray) ?: throw BackupException("Missing $key array")

    private fun JsonObject.optionalString(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.optionalLong(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.optionalInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.optionalBoolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.requireLong(key: String, owner: String): Long =
        optionalLong(key) ?: throw BackupException("$owner is missing $key")

    private fun JsonObject.requireInt(key: String, owner: String): Int =
        optionalInt(key) ?: throw BackupException("$owner is missing $key")

    private fun validateUniqueIds(ids: List<Any>, kind: String) {
        if (ids.size != ids.distinct().size) {
            throw BackupException("The backup contains duplicate $kind ids")
        }
    }

    internal companion object {
        const val SCHEMA_VERSION = 1L
        const val MAX_BACKUP_EVENTS = 10_000

        /**
         * Upper bound for one backup document. A backup with the maximum number of events stays far
         * below it while a hostile or corrupt file cannot make the app decode an unbounded document.
         */
        const val MAX_BACKUP_BYTES: Long = 16L * 1024 * 1024

        private const val TAG = "BackupRepository"
        private const val MAX_EVENT_SECONDS = Event.MAX_EVENT_DAY_SPAN * Event.SECONDS_PER_DAY
        private val MIN_EPOCH_DAY: Long = LocalDate.of(1900, 1, 1).toEpochDay()
        private val MAX_EPOCH_DAY: Long = LocalDate.of(2200, 12, 31).toEpochDay()
    }
}
