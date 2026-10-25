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

    @Query("SELECT * FROM events WHERE date BETWEEN :from AND :to ORDER BY date, start")
    suspend fun getBetween(from: Long, to: Long): List<Event>

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

    @Delete
    suspend fun delete(event: Event)

    @Delete
    suspend fun deleteAll(events: List<Event>)

    @Query("DELETE FROM events")
    suspend fun deleteAllEvents()
}
