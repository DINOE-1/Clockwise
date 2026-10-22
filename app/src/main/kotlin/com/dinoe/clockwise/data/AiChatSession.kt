package com.dinoe.clockwise.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val AI_CHAT_SESSION_RETENTION_MILLIS = 24L * 60 * 60 * 1000
const val AI_CHAT_SESSION_MAX_COUNT = 3
const val AI_CHAT_SESSION_DEFAULT_TITLE = "未命名對話"

@Entity(tableName = "ai_chat_sessions", indices = [Index("updatedAt")])
data class AiChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = AI_CHAT_SESSION_DEFAULT_TITLE,
    val createdAt: Long,
    val updatedAt: Long,
    val preview: String = "",
    val payload: String = "",
)

/** Lightweight row for the session picker; avoids carrying the JSON payload into the UI. */
data class AiChatSessionSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val preview: String,
)
