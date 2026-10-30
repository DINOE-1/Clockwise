package com.dinoe.clockwise.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupImportTest {
    private fun eventRow(
        id: Long,
        seriesId: Long = 0L,
        occurrenceIndex: Int = 0,
        repeatType: Int = Event.REPEAT_NONE,
    ) = BackupEvent(
        id = id,
        title = "Event $id",
        note = "",
        date = 20_000L,
        start = 9 * 3_600,
        end = 10 * 3_600,
        seriesId = seriesId,
        occurrenceIndex = occurrenceIndex,
        repeatType = repeatType,
        repeatDays = "",
        reminderMinutes = Event.REMINDER_OFF,
        allDay = false,
    )

    @Test
    fun allocateImportIdsAssignsFreshIdsInDocumentOrder() {
        assertEquals(mapOf(7L to 1L, 3L to 2L, 9L to 3L), allocateImportIds(listOf(7L, 3L, 9L)))
    }

    @Test
    fun allocateImportIdsKeepsIdsNonZeroSoRoomGeneratesThemExplicitly() {
        val ids = allocateImportIds(listOf(0L, 5L))
        assertEquals(1L, ids.getValue(0L))
        assertEquals(2L, ids.getValue(5L))
        assertTrue(ids.values.none { it == 0L })
    }

    @Test
    fun allocateImportIdsHandlesDocumentWithoutRows() {
        assertEquals(emptyMap<Long, Long>(), allocateImportIds(emptyList()))
    }

    @Test
    fun buildRowsRemapsSeriesIdsAndReferences() {
        val rows = buildBackupImportRows(
            tagRows = listOf(BackupTag(id = 40L, name = "Work", color = 12)),
            eventRows = listOf(
                eventRow(id = 10L, seriesId = 10L, repeatType = Event.REPEAT_DAILY),
                eventRow(id = 20L, seriesId = 10L, occurrenceIndex = 1),
                eventRow(id = 30L),
            ),
            alarmRows = listOf(
                BackupAlarm(
                    id = 50L,
                    time = 9 * 3_600,
                    enabled = true,
                    fireDate = 20_000L,
                    eventId = 20L,
                    name = "Event 20",
                    note = "",
                    oneTime = false,
                    stackId = 77L,
                ),
            ),
            eventTagRows = listOf(BackupEventTag(eventId = 10L, tagId = 40L)),
            alarmTagRows = listOf(BackupAlarmTag(alarmId = 50L, tagId = 40L)),
        )

        assertEquals(listOf(1L, 2L, 3L), rows.events.map { it.id })
        assertEquals(listOf(1L, 1L, 0L), rows.events.map { it.seriesId })
        assertEquals(1L, rows.tags.single().id)
        assertEquals(1L, rows.alarms.single().id)
        assertEquals(2L, rows.alarms.single().eventId)
        assertEquals(77L, rows.alarms.single().stackId)
        assertEquals(EventTagCrossRef(eventId = 1L, tagId = 1L), rows.eventTags.single())
        assertEquals(AlarmTagCrossRef(alarmId = 1L, tagId = 1L), rows.alarmTags.single())
    }

    @Test
    fun buildRowsClearsImportedSnoozes() {
        val rows = buildBackupImportRows(
            tagRows = emptyList(),
            eventRows = emptyList(),
            alarmRows = listOf(
                BackupAlarm(
                    id = 1L,
                    time = 0,
                    enabled = true,
                    fireDate = null,
                    eventId = null,
                    name = "",
                    note = "",
                    oneTime = true,
                    stackId = null,
                ),
            ),
            eventTagRows = emptyList(),
            alarmTagRows = emptyList(),
        )
        assertEquals(null, rows.alarms.single().snoozedUntil)
    }

    @Test
    fun buildRowsRejectsMissingSeries() {
        val error = runCatching {
            buildBackupImportRows(
                tagRows = emptyList(),
                eventRows = listOf(eventRow(id = 10L, seriesId = 99L)),
                alarmRows = emptyList(),
                eventTagRows = emptyList(),
                alarmTagRows = emptyList(),
            )
        }.exceptionOrNull()
        assertEquals("Event 10 references a missing series", error?.message)
    }

    @Test
    fun buildRowsRejectsReferencesOutsideTheDocument() {
        val missingEvent = runCatching {
            buildBackupImportRows(
                tagRows = listOf(BackupTag(id = 1L, name = "A", color = 0)),
                eventRows = emptyList(),
                alarmRows = emptyList(),
                eventTagRows = listOf(BackupEventTag(eventId = 5L, tagId = 1L)),
                alarmTagRows = emptyList(),
            )
        }.exceptionOrNull()
        assertEquals("Tag reference points to a missing event", missingEvent?.message)

        val missingTag = runCatching {
            buildBackupImportRows(
                tagRows = emptyList(),
                eventRows = listOf(eventRow(id = 5L)),
                alarmRows = emptyList(),
                eventTagRows = listOf(BackupEventTag(eventId = 5L, tagId = 1L)),
                alarmTagRows = emptyList(),
            )
        }.exceptionOrNull()
        assertEquals("Tag reference points to a missing tag", missingTag?.message)

        val missingAlarm = runCatching {
            buildBackupImportRows(
                tagRows = listOf(BackupTag(id = 1L, name = "A", color = 0)),
                eventRows = emptyList(),
                alarmRows = emptyList(),
                eventTagRows = emptyList(),
                alarmTagRows = listOf(BackupAlarmTag(alarmId = 5L, tagId = 1L)),
            )
        }.exceptionOrNull()
        assertEquals("Tag reference points to a missing alarm", missingAlarm?.message)

        val missingAlarmEvent = runCatching {
            buildBackupImportRows(
                tagRows = emptyList(),
                eventRows = emptyList(),
                alarmRows = listOf(
                    BackupAlarm(
                        id = 5L,
                        time = 0,
                        enabled = false,
                        fireDate = null,
                        eventId = 9L,
                        name = "",
                        note = "",
                        oneTime = false,
                        stackId = null,
                    ),
                ),
                eventTagRows = emptyList(),
                alarmTagRows = emptyList(),
            )
        }.exceptionOrNull()
        assertEquals("Alarm 5 points to a missing event", missingAlarmEvent?.message)
    }
}
