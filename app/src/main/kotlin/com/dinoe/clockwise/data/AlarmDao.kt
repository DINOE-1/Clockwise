package com.dinoe.clockwise.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY time, id")
    fun observeAll(): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms")
    suspend fun getAll(): List<Alarm>

    @Insert
    suspend fun insert(alarm: Alarm): Long

    @Insert
    suspend fun insertAll(alarms: List<Alarm>): List<Long>

    @Update
    suspend fun update(alarm: Alarm)

    @Delete
    suspend fun delete(alarm: Alarm)

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getById(id: Long): Alarm?

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun getEnabled(): List<Alarm>

    @Query("SELECT COUNT(*) FROM alarms WHERE enabled = 1")
    suspend fun countEnabled(): Int

    @Query("UPDATE alarms SET snoozedUntil = :until WHERE id = :id")
    suspend fun setSnoozedUntil(id: Long, until: Long)

    @Query("UPDATE alarms SET snoozedUntil = NULL WHERE id = :id")
    suspend fun clearSnooze(id: Long)

    // Conditional so a snooze requested at the same moment as the one-shot disable wins.
    @Query(
        "UPDATE alarms SET enabled = 0, snoozedUntil = NULL " +
            "WHERE id = :id AND snoozedUntil IS NULL",
    )
    suspend fun disableIfNotSnoozed(id: Long): Int

    @Query("SELECT * FROM alarms WHERE eventId = :eventId LIMIT 1")
    suspend fun getByEventId(eventId: Long): Alarm?

    @Query("SELECT DISTINCT eventId FROM alarms WHERE eventId IS NOT NULL")
    suspend fun getLinkedEventIds(): List<Long>

    @Query(
        "SELECT * FROM alarms WHERE eventId IS NOT NULL " +
            "AND eventId NOT IN (SELECT id FROM events)",
    )
    suspend fun getOrphanedEventAlarms(): List<Alarm>

    @Query(
        "DELETE FROM alarms WHERE eventId IS NOT NULL " +
            "AND eventId NOT IN (SELECT id FROM events)",
    )
    suspend fun deleteOrphanedEventAlarms(): Int

    @Query("DELETE FROM alarms")
    suspend fun deleteAllAlarms()
}
