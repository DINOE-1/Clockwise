package com.dinoe.clockwise.data

/**
 * Rows of one backup document, already validated and remapped to the ids the import will insert
 * them with. Building this plan before touching the database keeps the import a fixed number of
 * bulk statements and removes the second pass that used to rewrite every event to attach series ids.
 */
internal data class BackupImportRows(
    val tags: List<Tag>,
    val events: List<Event>,
    val alarms: List<Alarm>,
    val eventTags: List<EventTagCrossRef>,
    val alarmTags: List<AlarmTagCrossRef>,
)

internal data class BackupEvent(
    val id: Long,
    val title: String,
    val note: String,
    val date: Long,
    val start: Int,
    val end: Int,
    val seriesId: Long,
    val occurrenceIndex: Int,
    val repeatType: Int,
    val repeatDays: String,
    val reminderMinutes: Int,
    val allDay: Boolean,
)

internal data class BackupAlarm(
    val id: Long,
    val time: Int,
    val enabled: Boolean,
    val fireDate: Long?,
    val eventId: Long?,
    val name: String,
    val note: String,
    val oneTime: Boolean,
    val stackId: Long?,
    val ringtone: String = Alarm.DEFAULT_RINGTONE,
)

internal data class BackupTag(val id: Long, val name: String, val color: Int)

internal data class BackupEventTag(val eventId: Long, val tagId: Long)

internal data class BackupAlarmTag(val alarmId: Long, val tagId: Long)

/** Id assigned to the first row of an imported table; 0 is reserved by Room as "auto generate". */
private const val FIRST_IMPORT_ID = 1L

/**
 * Assigns a fresh row id to every imported id, in document order. The mapping is complete before
 * insertion, so series ids and tag references can be translated up front.
 */
internal fun allocateImportIds(
    importedIds: List<Long>,
    firstId: Long = FIRST_IMPORT_ID,
): Map<Long, Long> {
    val ids = HashMap<Long, Long>(importedIds.size * 2)
    importedIds.forEachIndexed { index, importedId -> ids[importedId] = firstId + index }
    return ids
}

/**
 * Resolves every reference in the document against the freshly allocated ids. Throws when a
 * reference points outside the document, exactly as the old in-transaction remapping did.
 */
internal fun buildBackupImportRows(
    tagRows: List<BackupTag>,
    eventRows: List<BackupEvent>,
    alarmRows: List<BackupAlarm>,
    eventTagRows: List<BackupEventTag>,
    alarmTagRows: List<BackupAlarmTag>,
): BackupImportRows {
    val tagIds = allocateImportIds(tagRows.map { it.id })
    val eventIds = allocateImportIds(eventRows.map { it.id })
    val alarmIds = allocateImportIds(alarmRows.map { it.id })

    val tags = tagRows.map { row ->
        Tag(id = tagIds.getValue(row.id), name = row.name, color = row.color)
    }
    val events = eventRows.map { row ->
        Event(
            id = eventIds.getValue(row.id),
            title = row.title,
            note = row.note,
            date = row.date,
            start = row.start,
            end = row.end,
            seriesId = if (row.seriesId == 0L) {
                0L
            } else {
                eventIds[row.seriesId]
                    ?: throw BackupException("Event ${row.id} references a missing series")
            },
            occurrenceIndex = row.occurrenceIndex,
            repeatType = row.repeatType,
            repeatDays = row.repeatDays,
            reminderMinutes = row.reminderMinutes,
            allDay = row.allDay,
        )
    }
    val eventTags = eventTagRows.map { ref ->
        val eventId = eventIds[ref.eventId]
            ?: throw BackupException("Tag reference points to a missing event")
        val tagId = tagIds[ref.tagId]
            ?: throw BackupException("Tag reference points to a missing tag")
        EventTagCrossRef(eventId, tagId)
    }
    val alarms = alarmRows.map { row ->
        Alarm(
            id = alarmIds.getValue(row.id),
            time = row.time,
            enabled = row.enabled,
            fireDate = row.fireDate,
            eventId = row.eventId?.let { old ->
                eventIds[old] ?: throw BackupException("Alarm ${row.id} points to a missing event")
            },
            name = row.name,
            note = row.note,
            snoozedUntil = null,
            oneTime = row.oneTime,
            stackId = row.stackId,
            ringtone = row.ringtone,
        )
    }
    val alarmTags = alarmTagRows.map { ref ->
        val alarmId = alarmIds[ref.alarmId]
            ?: throw BackupException("Tag reference points to a missing alarm")
        val tagId = tagIds[ref.tagId]
            ?: throw BackupException("Tag reference points to a missing tag")
        AlarmTagCrossRef(alarmId, tagId)
    }

    return BackupImportRows(
        tags = tags,
        events = events,
        alarms = alarms,
        eventTags = eventTags,
        alarmTags = alarmTags,
    )
}
