package com.dinoe.clockwise.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class PersistedChatMessage(
    val role: String,
    val text: String,
    // Base64 thumbnail of an attached photo; large payloads are dropped on encode.
    val image: String? = null,
)

/**
 * Durable part of a chat. The UI history and the compact API memory are stored separately so a
 * long visible transcript does not inflate the model context after restore.
 */
data class PersistedChat(
    val messages: List<PersistedChatMessage> = emptyList(),
    val turns: List<CompletedTurn> = emptyList(),
    val actions: List<String> = emptyList(),
    val usage: AiUsage = AiUsage(),
    val requestCount: Int = 0,
)

object AiChatSessionCodec {
    const val VERSION = 1
    const val MAX_UI_MESSAGES = 100
    const val MAX_MESSAGE_CHARS = 4_000
    const val MAX_MESSAGE_IMAGE_CHARS = 160_000

    private val json = Json

    fun encode(chat: PersistedChat): String {
        val messages = chat.messages.takeLast(MAX_UI_MESSAGES)
        val turns = chat.turns.takeLast(AiTokenBudget.MAX_COMPLETED_TURNS)
        val actions = chat.actions.takeLast(AiTokenBudget.MAX_ACTIONS)
        return buildJsonObject {
            put("version", VERSION)
            putJsonArray("messages") {
                messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", message.role)
                            put(
                                "text",
                                AiTokenBudget.truncateChars(message.text, MAX_MESSAGE_CHARS),
                            )
                            message.image
                                ?.takeIf { it.length <= MAX_MESSAGE_IMAGE_CHARS }
                                ?.let { put("image", it) }
                        },
                    )
                }
            }
            putJsonArray("turns") {
                turns.forEach { turn ->
                    add(
                        buildJsonObject {
                            put("user", turn.user)
                            put("assistant", turn.assistant)
                            turn.reasoningContent?.let { put("reasoning", it) }
                        },
                    )
                }
            }
            putJsonArray("actions") {
                actions.forEach { add(it) }
            }
            putJsonObject("usage") {
                put("prompt", chat.usage.promptTokens)
                put("completion", chat.usage.completionTokens)
                put("cached", chat.usage.cachedTokens)
                put("reasoning", chat.usage.reasoningTokens)
                put("cacheReported", chat.usage.cacheReported)
            }
            put("requestCount", chat.requestCount)
        }.toString()
    }

    fun decode(raw: String?): PersistedChat? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
            val messages = root["messages"].arrayOrNull()?.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val role = item.stringOrNull("role") ?: return@mapNotNull null
                val text = item.stringOrNull("text") ?: return@mapNotNull null
                PersistedChatMessage(
                    role = role,
                    text = text,
                    image = item.stringOrNull("image"),
                )
            }.orEmpty()
            val turns = root["turns"].arrayOrNull()?.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val user = item.stringOrNull("user") ?: return@mapNotNull null
                val assistant = item.stringOrNull("assistant") ?: return@mapNotNull null
                CompletedTurn(
                    user = user,
                    assistant = assistant,
                    reasoningContent = item.stringOrNull("reasoning"),
                )
            }.orEmpty()
            val actions = root["actions"].arrayOrNull()?.mapNotNull { element ->
                (element as? JsonPrimitive)?.contentOrNull
            }.orEmpty()
            val usageObject = root["usage"] as? JsonObject
            val usage = AiUsage(
                promptTokens = usageObject?.longValue("prompt") ?: 0L,
                completionTokens = usageObject?.longValue("completion") ?: 0L,
                cachedTokens = usageObject?.longValue("cached") ?: 0L,
                reasoningTokens = usageObject?.longValue("reasoning") ?: 0L,
                cacheReported = (usageObject?.get("cacheReported") as? JsonPrimitive)
                    ?.booleanOrNull
                    ?: false,
            )
            PersistedChat(
                messages = messages,
                turns = turns,
                actions = actions,
                usage = usage,
                requestCount = (root["requestCount"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }.getOrNull()
    }

    private fun kotlinx.serialization.json.JsonElement?.arrayOrNull(): JsonArray? =
        this as? JsonArray

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.longValue(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
}
