package com.dinoe.clockwise.ai

import com.dinoe.clockwise.data.AI_CHAT_SESSION_DEFAULT_TITLE
import com.dinoe.clockwise.data.AI_CHAT_SESSION_MAX_COUNT
import com.dinoe.clockwise.data.AI_CHAT_SESSION_RETENTION_MILLIS
import com.dinoe.clockwise.data.AiChatSession
import com.dinoe.clockwise.data.AiChatSessionDao
import com.dinoe.clockwise.data.AiChatSessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Immutable view of one chat taken on the Main thread. It only copies references to already
 * immutable models, so converting it to [PersistedChat] and encoding it never touches mutable
 * Compose state off the Main thread.
 */
data class ChatPersistenceSnapshot(
    val items: List<AiChatItem> = emptyList(),
    val turns: List<CompletedTurn> = emptyList(),
    val actions: List<String> = emptyList(),
    val usage: AiUsage = AiUsage(),
    val requestCount: Int = 0,
    val sessionId: Long? = null,
    val title: String = AI_CHAT_SESSION_DEFAULT_TITLE,
    val createdAt: Long = 0L,
)

/** Decoded session plus the metadata needed to publish it into Compose state in one update. */
data class RestoredChatSession(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val messages: List<PersistedChatMessage>,
    val turns: List<CompletedTurn>,
    val actions: List<String>,
    val usage: AiUsage,
    val requestCount: Int,
    val corrupt: Boolean,
) {
    val endsWithUserMessage: Boolean get() = messages.lastOrNull()?.role == "user"
}

sealed interface SessionOpenResult {
    data class Opened(val session: RestoredChatSession) : SessionOpenResult

    /** The row no longer exists or outlived the retention window and has been removed. */
    data object Unavailable : SessionOpenResult
}

/**
 * Owns chat session persistence: snapshot conversion, payload codec execution, Room writes,
 * retention cleanup and session switching. Everything heavier than a state copy runs off Main;
 * [sessionMutex] serializes each read-modify-write sequence so a stale write cannot resurrect or
 * overwrite the session that is currently open.
 */
class AiChatSessionStore(private val dao: AiChatSessionDao) {
    private val sessionMutex = Mutex()

    /** Session picker rows; the JSON payload column is never observed here. */
    val summaries: Flow<List<AiChatSessionSummary>> get() = dao.observeSummaries()

    suspend fun touchAndClean(activeSessionId: Long?, now: Long = System.currentTimeMillis()) =
        sessionMutex.withLock {
            withContext(Dispatchers.IO) {
                activeSessionId?.let { dao.touch(it, now) }
                prune(now)
            }
        }

    suspend fun rename(id: Long, title: String) {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            dao.rename(id, title, now)
            dao.trimTo(AI_CHAT_SESSION_MAX_COUNT)
        }
    }

    /**
     * Persists [snapshotOf] under the session lock. The supplier runs on the caller's (Main)
     * dispatcher so Compose state is copied exactly once, before any background work starts.
     * Returns the id of the written session, or null when there was nothing to store.
     */
    suspend fun persist(snapshotOf: () -> ChatPersistenceSnapshot): Long? = sessionMutex.withLock {
        val snapshot = snapshotOf()
        val prepared = withContext(Dispatchers.Default) {
            val chat = chatOf(snapshot)
            if (chat.messages.isEmpty() && chat.turns.isEmpty()) {
                null
            } else {
                AiChatSessionCodec.encode(chat) to previewOf(chat)
            }
        } ?: return@withLock null
        val (payload, preview) = prepared
        val now = System.currentTimeMillis()
        val row = AiChatSession(
            id = snapshot.sessionId ?: 0L,
            title = snapshot.title.ifBlank { AI_CHAT_SESSION_DEFAULT_TITLE },
            createdAt = snapshot.createdAt,
            updatedAt = now,
            preview = preview,
            payload = payload,
        )
        withContext(Dispatchers.IO) {
            val id = if (row.id != 0L) {
                val updated = dao.update(row)
                if (updated > 0) row.id else dao.insert(row.copy(id = 0L))
            } else {
                dao.insert(row)
            }
            prune(now)
            id
        }
    }

    /**
     * Prunes retention, then opens the newest session. Returns null when none is left.
     */
    suspend fun openLatest(now: Long = System.currentTimeMillis()): RestoredChatSession? =
        sessionMutex.withLock {
            withContext(Dispatchers.IO) { prune(now) }
            val row = withContext(Dispatchers.IO) { dao.getLatest() } ?: return@withLock null
            decode(row)
        }

    /**
     * Opens [id] and marks it as the most recently used session. Expired or missing rows are
     * dropped from the table so the picker cannot offer them again.
     */
    suspend fun open(id: Long, now: Long = System.currentTimeMillis()): SessionOpenResult =
        sessionMutex.withLock {
            val row = withContext(Dispatchers.IO) { dao.getById(id) }
            if (row == null || now - row.updatedAt > AI_CHAT_SESSION_RETENTION_MILLIS) {
                withContext(Dispatchers.IO) {
                    dao.deleteById(id)
                    dao.trimTo(AI_CHAT_SESSION_MAX_COUNT)
                }
                return@withLock SessionOpenResult.Unavailable
            }
            val restored = decode(row)
            withContext(Dispatchers.IO) {
                dao.touch(id, now)
                dao.trimTo(AI_CHAT_SESSION_MAX_COUNT)
            }
            SessionOpenResult.Opened(restored)
        }

    /**
     * Deletes [id]. When the deleted session was the active one, the newest remaining session is
     * reopened and returned; null means the caller should start a fresh draft.
     */
    suspend fun deleteAndReopen(
        id: Long,
        wasActive: Boolean,
        now: Long = System.currentTimeMillis(),
    ): RestoredChatSession? = sessionMutex.withLock {
        withContext(Dispatchers.IO) { dao.deleteById(id) }
        if (!wasActive) {
            withContext(Dispatchers.IO) { dao.trimTo(AI_CHAT_SESSION_MAX_COUNT) }
            return@withLock null
        }
        withContext(Dispatchers.IO) { prune(now) }
        val latest = withContext(Dispatchers.IO) { dao.getLatest() } ?: return@withLock null
        val restored = decode(latest)
        withContext(Dispatchers.IO) { dao.touch(latest.id, now) }
        restored
    }

    private suspend fun decode(row: AiChatSession): RestoredChatSession =
        withContext(Dispatchers.Default) {
            val decoded = AiChatSessionCodec.decode(row.payload)
            val chat = decoded ?: PersistedChat()
            RestoredChatSession(
                id = row.id,
                title = row.title.ifBlank { AI_CHAT_SESSION_DEFAULT_TITLE },
                createdAt = row.createdAt,
                messages = transcriptOf(chat),
                turns = chat.turns,
                actions = chat.actions,
                usage = chat.usage,
                requestCount = chat.requestCount,
                corrupt = decoded == null && row.payload.isNotBlank(),
            )
        }

    private suspend fun prune(now: Long) {
        dao.deleteExpired(now - AI_CHAT_SESSION_RETENTION_MILLIS)
        dao.trimTo(AI_CHAT_SESSION_MAX_COUNT)
    }

    companion object {
        const val PREVIEW_CHARS = 120

        /** UI transcript to persisted messages; proposals, notices and errors are not durable. */
        fun messagesOf(items: List<AiChatItem>): List<PersistedChatMessage> =
            items.mapNotNull { item ->
                when (item) {
                    is AiChatItem.User -> PersistedChatMessage("user", item.text, item.image)
                    is AiChatItem.Assistant -> PersistedChatMessage("assistant", item.text)
                    else -> null
                }
            }.takeLast(AiChatSessionCodec.MAX_UI_MESSAGES)

        fun chatOf(snapshot: ChatPersistenceSnapshot): PersistedChat = PersistedChat(
            messages = messagesOf(snapshot.items),
            turns = snapshot.turns,
            actions = snapshot.actions,
            usage = snapshot.usage,
            requestCount = snapshot.requestCount,
        )

        fun previewOf(chat: PersistedChat): String {
            val text = chat.messages.lastOrNull()?.text.orEmpty()
            return AiTokenBudget.truncateChars(text.trim(), PREVIEW_CHARS)
        }

        /**
         * Transcript shown after restore. Sessions written before the UI history was stored
         * separately only carry completed turns, so their history is rebuilt from those.
         */
        fun transcriptOf(chat: PersistedChat): List<PersistedChatMessage> =
            if (chat.messages.isNotEmpty()) {
                chat.messages
            } else {
                chat.turns.flatMap { turn ->
                    listOf(
                        PersistedChatMessage("user", turn.user),
                        PersistedChatMessage("assistant", turn.assistant),
                    )
                }
            }
    }
}
