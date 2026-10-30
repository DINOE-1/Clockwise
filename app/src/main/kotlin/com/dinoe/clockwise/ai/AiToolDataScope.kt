package com.dinoe.clockwise.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Row ids a batch of write tool calls refers to, collected before any row is loaded. */
data class ReferencedIds(
    val alarmIds: Set<Long>,
    val eventIds: Set<Long>,
) {
    val isEmpty: Boolean get() = alarmIds.isEmpty() && eventIds.isEmpty()
}

/**
 * Collects the ids referenced by write tool calls so the caller can load only those rows.
 * Malformed arguments are ignored here: [AiToolParser] reports the real validation error later.
 */
object AiToolReferences {
    fun collect(calls: List<ChatToolCall>): ReferencedIds {
        val alarmIds = LinkedHashSet<Long>()
        val eventIds = LinkedHashSet<Long>()
        for (call in calls) {
            val root = parseObject(call.arguments) ?: continue
            when (call.name) {
                AiTools.CREATE_ALARMS, AiTools.CREATE_ALARM_STACKS, AiTools.CREATE_EVENTS -> Unit
                AiTools.UPDATE_ALARMS -> objectIds(root["alarms"]).forEach(alarmIds::add)
                AiTools.DELETE_ALARMS -> primitiveIds(root["alarmIds"]).forEach(alarmIds::add)
                AiTools.UPDATE_EVENTS, AiTools.DELETE_EVENTS ->
                    objectIds(root["events"]).forEach(eventIds::add)
            }
        }
        return ReferencedIds(alarmIds = alarmIds, eventIds = eventIds)
    }

    private fun parseObject(arguments: String): JsonObject? =
        runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()

    private fun objectIds(element: JsonElement?): List<Long> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item -> (item as? JsonObject)?.get("id").asId() }
    }

    private fun primitiveIds(element: JsonElement?): List<Long> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item -> item.asId() }
    }

    private fun JsonElement?.asId(): Long? =
        (this as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }
}

/**
 * SQLite bounds the number of parameters of one statement, so `IN (...)` lookups are split into
 * chunks. An empty input yields no chunk, which keeps callers from running `IN ()`.
 */
object AiIdChunks {
    const val MAX_PARAMS = 500

    fun of(ids: Collection<Long>, size: Int = MAX_PARAMS): List<List<Long>> {
        require(size > 0) { "chunk size must be positive" }
        if (ids.isEmpty()) return emptyList()
        return ids.distinct().chunked(size)
    }
}

/**
 * Deterministic token-budget trimming: results are dropped from the end of a payload, so the
 * first rows always survive and repeated runs keep the same subset. [minCount] keeps a payload
 * useful when the remaining budget is smaller than all items.
 */
object AiToolPayload {
    fun <T> fittingPrefix(
        items: List<T>,
        maxTokens: Int,
        minCount: Int = 1,
        estimatedTokens: (List<T>) -> Int,
    ): List<T> {
        if (items.isEmpty()) return items
        if (estimatedTokens(items) <= maxTokens) return items
        val floor = minCount.coerceIn(1, items.size)
        for (count in items.size - 1 downTo floor) {
            val candidate = items.subList(0, count)
            if (estimatedTokens(candidate) <= maxTokens) return candidate.toList()
        }
        return items.subList(0, floor).toList()
    }
}
