package com.dinoe.clockwise.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): Flow<List<Tag>>

    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun getAll(): List<Tag>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Tag?

    @Insert
    suspend fun insert(tag: Tag): Long

    @Update
    suspend fun update(tag: Tag)

    @Delete
    suspend fun delete(tag: Tag)

    @Query("SELECT * FROM event_tags")
    fun observeEventTagRefs(): Flow<List<EventTagCrossRef>>

    @Query("SELECT * FROM alarm_tags")
    fun observeAlarmTagRefs(): Flow<List<AlarmTagCrossRef>>

    @Query("SELECT * FROM event_tags")
    suspend fun getEventTagRefs(): List<EventTagCrossRef>

    @Query("SELECT * FROM alarm_tags")
    suspend fun getAlarmTagRefs(): List<AlarmTagCrossRef>

    @Query("DELETE FROM event_tags WHERE eventId = :eventId")
    suspend fun clearEventTags(eventId: Long)

    @Query("DELETE FROM alarm_tags WHERE alarmId = :alarmId")
    suspend fun clearAlarmTags(alarmId: Long)

    @Insert
    suspend fun insertEventTags(refs: List<EventTagCrossRef>)

    @Insert
    suspend fun insertAlarmTags(refs: List<AlarmTagCrossRef>)

    @Query("DELETE FROM event_tags WHERE tagId = :tagId")
    suspend fun deleteEventTagRefs(tagId: Long)

    @Query("DELETE FROM alarm_tags WHERE tagId = :tagId")
    suspend fun deleteAlarmTagRefs(tagId: Long)
}
