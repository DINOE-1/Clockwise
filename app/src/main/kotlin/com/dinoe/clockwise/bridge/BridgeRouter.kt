package com.dinoe.clockwise.bridge

import com.dinoe.clockwise.data.AlarmDao
import com.dinoe.clockwise.data.EventDao
import com.dinoe.clockwise.data.TagDao
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Maps validated bridge requests to read-only DAO queries and JSON responses. The only entry point
 * is [handle]; everything below runs on the caller's thread with `runBlocking` around suspend DAO
 * calls, so [BridgeServer] can call it from its worker pool.
 */
class BridgeRouter(
    private val eventDao: EventDao,
    private val alarmDao: AlarmDao,
    private val tagDao: TagDao,
    /** Blocking provider so token rotation and the enabled flag apply without a server restart. */
    private val configProvider: () -> BridgeConfig,
    private val appVersionName: String,
    private val dbVersionProvider: () -> Int,
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now() },
) {

    fun handle(request: BridgeRequest): BridgeResponse {
        val config = try {
            configProvider()
        } catch (_: Exception) {
            return errorResponse(500, "Unable to read bridge settings")
        }
        if (!config.enabled || config.token.isEmpty()) {
            return errorResponse(401, "The bridge is disabled. Enable it in Clockwise Settings > Agent Access.")
        }
        if (!BridgeAuth.isAuthorized(request.headers["authorization"], config.token)) {
            return errorResponse(401, "Missing or invalid bearer token")
        }
        return try {
            route(request)
        } catch (e: BridgeHttpException) {
            errorResponse(e.status, e.message ?: "Bad request")
        } catch (e: Exception) {
            errorResponse(500, "Unexpected error while reading the calendar database")
        }
    }

    private fun route(request: BridgeRequest): BridgeResponse = runBlocking(Dispatchers.IO) {
        val path = request.path
        val body: JsonObject = when {
            path == "/v1/meta" -> BridgeJson.meta(appVersionName, dbVersionProvider(), timezone(), nowProvider().toString())
            path == "/v1/events" -> eventsPayload(request.query)
            path.startsWith(EVENTS_PREFIX) -> eventDetail(idAfter(path, EVENTS_PREFIX))
            path == "/v1/alarms" -> alarmsPayload(request.query)
            path.startsWith(ALARMS_PREFIX) -> alarmDetail(idAfter(path, ALARMS_PREFIX))
            path == "/v1/tags" -> tagsPayload()
            else -> throw BridgeHttpException(404, "Unknown route: $path")
        }
        ok(body)
    }

    private fun idAfter(path: String, prefix: String): Long {
        val id = path.removePrefix(prefix).toLongOrNull()
        if (id == null || id <= 0) throw BridgeHttpException(404, "Invalid id in route: $path")
        return id
    }

    private suspend fun eventsPayload(query: Map<String, String>): JsonObject {
        val today = nowProvider().toLocalDate()
        val from = parseDate(query["from"], "from") ?: today
        val to = parseDate(query["to"], "to") ?: from.plusDays(7)
        if (to.isBefore(from)) {
            throw BridgeHttpException(400, "'to' must not be before 'from'")
        }
        val days = Duration.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()).toDays()
        if (days > MAX_RANGE_DAYS) {
            throw BridgeHttpException(400, "The from..to range must be $MAX_RANGE_DAYS days or fewer")
        }
        val limit = parseLimit(query["limit"])

        val fromDay = from.toEpochDay()
        val toDay = to.toEpochDay()
        val total = eventDao.countBetween(fromDay, toDay)
        val page = eventDao.getBetweenPage(fromDay, toDay, limit)
        val pageIds = page.map { it.id }
        val alarmEventIds = if (pageIds.isEmpty()) {
            emptySet()
        } else {
            alarmDao.getByEventIds(pageIds).mapNotNull { it.eventId }.toSet()
        }
        val tagNames = eventTagNames(pageIds)

        return BridgeJson.eventsPayload(
            total = total,
            shown = page,
            alarmEventIds = alarmEventIds,
            tagNames = tagNames,
            timezone = timezone(),
        )
    }

    private suspend fun eventDetail(id: Long): JsonObject {
        val event = eventDao.getById(id)
            ?: throw BridgeHttpException(404, "No event with id $id")
        val alarmId = alarmDao.getByEventId(id)?.id
        val tagNames = eventTagNames(listOf(id))[id].orEmpty()
        return BridgeJson.eventDetail(event, alarmId, tagNames)
    }

    private suspend fun alarmsPayload(query: Map<String, String>): JsonObject {
        val limit = parseLimit(query["limit"])
        val enabledFilter = when (query["enabled"]) {
            null -> null
            "true" -> true
            "false" -> false
            else -> throw BridgeHttpException(400, "'enabled' must be true or false")
        }
        val total = when (enabledFilter) {
            null -> alarmDao.count()
            true -> alarmDao.countEnabled()
            false -> alarmDao.count() - alarmDao.countEnabled()
        }
        val page = alarmDao.getOrderedPage(limit)
        val shown = if (enabledFilter == null) page else page.filter { it.enabled == enabledFilter }
        return BridgeJson.alarmsPayload(total, shown, alarmTagNames(shown.map { it.id }))
    }

    private suspend fun alarmDetail(id: Long): JsonObject {
        val alarm = alarmDao.getById(id)
            ?: throw BridgeHttpException(404, "No alarm with id $id")
        return BridgeJson.alarmDetail(alarm, alarmTagNames(listOf(id))[id].orEmpty())
    }

    private suspend fun tagsPayload(): JsonObject = buildJsonObject {
        val tags = tagDao.getAll()
        putJsonArray("tags") {
            tags.forEach { tag ->
                add(
                    buildJsonObject {
                        put("id", tag.id)
                        put("name", tag.name)
                        put("color", tag.color)
                    },
                )
            }
        }
    }

    private suspend fun eventTagNames(eventIds: List<Long>): Map<Long, List<String>> {
        if (eventIds.isEmpty()) return emptyMap()
        val tagsById = tagDao.getAll().associateBy { it.id }
        return tagDao.getEventTagRefsFor(eventIds)
            .groupBy { it.eventId }
            .mapValues { (_, refs) -> refs.mapNotNull { tagsById[it.tagId]?.name } }
    }

    private suspend fun alarmTagNames(alarmIds: List<Long>): Map<Long, List<String>> {
        if (alarmIds.isEmpty()) return emptyMap()
        val tagsById = tagDao.getAll().associateBy { it.id }
        return tagDao.getAlarmTagRefsFor(alarmIds)
            .groupBy { it.alarmId }
            .mapValues { (_, refs) -> refs.mapNotNull { tagsById[it.tagId]?.name } }
    }

    private fun parseDate(value: String?, name: String): java.time.LocalDate? {
        if (value == null) return null
        return try {
            java.time.LocalDate.parse(value)
        } catch (_: Exception) {
            throw BridgeHttpException(400, "'$name' must be an ISO date like 2026-11-03")
        }
    }

    private fun parseLimit(value: String?): Int {
        if (value == null) return DEFAULT_LIMIT
        val limit = value.toIntOrNull()
            ?: throw BridgeHttpException(400, "'limit' must be an integer")
        if (limit < 1) throw BridgeHttpException(400, "'limit' must be at least 1")
        return limit.coerceAtMost(MAX_LIMIT)
    }

    private fun ok(body: JsonObject) = BridgeResponse(200, body.toString())

    private fun errorResponse(status: Int, message: String) =
        BridgeResponse(status, BridgeJson.error(BridgeJson.errorCodeFor(status), message).toString())

    private fun timezone(): String = ZoneId.systemDefault().id

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 200
        const val MAX_RANGE_DAYS = 366L

        private const val EVENTS_PREFIX = "/v1/events/"
        private const val ALARMS_PREFIX = "/v1/alarms/"
    }
}
