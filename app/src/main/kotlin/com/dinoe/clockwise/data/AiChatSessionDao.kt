package com.dinoe.clockwise.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AiChatSessionDao {
    @Query("SELECT * FROM ai_chat_sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AiChatSession>>

    @Query("SELECT * FROM ai_chat_sessions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatest(): AiChatSession?

    @Query("SELECT * FROM ai_chat_sessions WHERE id = :id")
    suspend fun getById(id: Long): AiChatSession?

    @Insert
    suspend fun insert(session: AiChatSession): Long

    @Update
    suspend fun update(session: AiChatSession): Int

    @Query("DELETE FROM ai_chat_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM ai_chat_sessions WHERE updatedAt < :cutoff")
    suspend fun deleteExpired(cutoff: Long)

    // Keep only the newest N sessions by last interaction.
    @Query(
        "DELETE FROM ai_chat_sessions WHERE id NOT IN (" +
            "SELECT id FROM ai_chat_sessions ORDER BY updatedAt DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)

    @Query("UPDATE ai_chat_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("UPDATE ai_chat_sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, title: String, updatedAt: Long)
}
