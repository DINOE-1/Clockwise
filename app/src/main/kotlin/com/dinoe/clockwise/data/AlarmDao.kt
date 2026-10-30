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

    @Update
    suspend fun updateAll(alarms: List<Alarm>)

    @Delete
    suspend fun delete(alarm: Alarm)

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getById(id: Long): Alarm?

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun getEnabled(): List<Alarm>

    @Query("SELECT COUNT(*) FROM alarms WHERE enabled = 1")
    suspend fun countEnabled(): Int

    @Query("SELECT COUNT(*) FROM alarms")
    suspend fun count(): Int

    // First page of the AI read tool: ordering and the row cap apply in SQL so the full table is
    // never materialized. Null fireDate sorts last, matching time-then-date ordering.
    @Query("SELECT * FROM alarms ORDER BY time, fireDate IS NULL, fireDate, id LIMIT :limit")
    suspend fun getOrderedPage(limit: Int): List<Alarm>

    @Query("SELECT * FROM alarms WHERE id IN (:ids) ORDER BY id")
    suspend fun getByIds(ids: List<Long>): List<Alarm>

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

    @Query("SELECT * FROM alarms WHERE eventId IN (:eventIds) ORDER BY id")
    suspend fun getByEventIds(eventIds: List<Long>): List<Alarm>

    // Re-points every alarm of the given events at the events' new time and date in one statement.
    @Query(
        "UPDATE alarms SET time = :time, name = :name, note = :note, enabled = 1, " +
            "snoozedUntil = NULL, " +
            "fireDate = (SELECT date FROM events WHERE events.id = alarms.eventId) " +
            "WHERE eventId IN (:eventIds)",
    )
    suspend fun syncEventAlarms(eventIds: List<Long>, time: Int, name: String, note: String)

    @Query("UPDATE alarms SET eventId = NULL WHERE id IN (:ids)")
    suspend fun detachFromEvents(ids: List<Long>)

    @Query("DELETE FROM alarms WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT DISTINCT eventId FROM alarms WHERE eventId IS NOT NULL")
    suspend fun getLinkedEventIds(): List<Long>

    @Query(
        "SELECT DISTINCT eventId FROM alarms " +
            "WHERE eventId IS NOT NULL AND eventId IN (:eventIds)",
    )
    suspend fun getLinkedEventIdsFor(eventIds: List<Long>): List<Long>

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
