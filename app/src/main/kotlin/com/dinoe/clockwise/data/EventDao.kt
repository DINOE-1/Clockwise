package com.dinoe.clockwise.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY date, start")
    fun observeAll(): Flow<List<Event>>

    @Query("SELECT * FROM events ORDER BY date, start")
    suspend fun getAll(): List<Event>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): Event?

    // Includes multi-day events that started before :from but still overlap the range.
    @Query(
        "SELECT * FROM events WHERE date <= :to AND date + (end / 86400) >= :from " +
            "ORDER BY date, start",
    )
    suspend fun getBetween(from: Long, to: Long): List<Event>

    // Observable variant of [getBetween] for the visible calendar range.
    @Query(
        "SELECT * FROM events WHERE date <= :to AND date + (end / 86400) >= :from " +
            "ORDER BY date, start",
    )
    fun observeBetween(from: Long, to: Long): Flow<List<Event>>

    // First page of the AI read tool: the range filter, the ordering and the row cap all apply in
    // SQL, and the total comes from [countBetween] instead of a full read.
    @Query(
        "SELECT * FROM events WHERE date <= :to AND date + (end / 86400) >= :from " +
            "ORDER BY date, start, id LIMIT :limit",
    )
    suspend fun getBetweenPage(from: Long, to: Long, limit: Int): List<Event>

    @Query("SELECT COUNT(*) FROM events WHERE date <= :to AND date + (end / 86400) >= :from")
    suspend fun countBetween(from: Long, to: Long): Int

    @Query("SELECT * FROM events WHERE id IN (:ids) ORDER BY date, start, id")
    suspend fun getByIds(ids: List<Long>): List<Event>

    @Query("SELECT * FROM events WHERE seriesId IN (:seriesIds) ORDER BY seriesId, occurrenceIndex")
    suspend fun getBySeriesIds(seriesIds: List<Long>): List<Event>

    @Query("SELECT * FROM events WHERE seriesId = :seriesId ORDER BY occurrenceIndex")
    suspend fun getBySeries(seriesId: Long): List<Event>

    @Insert
    suspend fun insert(event: Event): Long

    @Insert
    suspend fun insertAll(events: List<Event>): List<Long>

    @Update
    suspend fun update(event: Event)

    @Update
    suspend fun updateAll(events: List<Event>)

    // Bulk field update for one or more occurrences; [dateShift] moves every selected row by the
    // same number of days, which is all a series edit needs.
    @Query(
        "UPDATE events SET title = :title, note = :note, start = :start, end = :end, " +
            "reminderMinutes = :reminderMinutes, allDay = :allDay, date = date + :dateShift " +
            "WHERE id IN (:ids)",
    )
    suspend fun updateEventFields(
        ids: List<Long>,
        title: String,
        note: String,
        start: Int,
        end: Int,
        reminderMinutes: Int,
        allDay: Boolean,
        dateShift: Long,
    )

    @Query("DELETE FROM events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Delete
    suspend fun delete(event: Event)

    @Delete
    suspend fun deleteAll(events: List<Event>)

    @Query("DELETE FROM events")
    suspend fun deleteAllEvents()
}
