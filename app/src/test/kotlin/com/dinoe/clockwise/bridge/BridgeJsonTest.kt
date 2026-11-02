package com.dinoe.clockwise.bridge

import com.dinoe.clockwise.data.Alarm
import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.data.Tag
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeJsonTest {

    private val date = LocalDate.of(2026, 11, 3).toEpochDay()

    private fun event(
        start: Int = 9 * 3_600,
        end: Int = 10 * 3_600 + 30 * 60,
        allDay: Boolean = false,
        repeatType: Int = Event.REPEAT_NONE,
        repeatDays: String = "",
    ) = Event(
        title = "會議",
        note = "帶筆記",
        date = date,
        start = start,
        end = end,
        repeatType = repeatType,
        repeatDays = repeatDays,
        reminderMinutes = 10,
        allDay = allDay,
    )

    private fun alarm(
        enabled: Boolean = true,
        fireDate: Long? = null,
        eventId: Long? = null,
        snoozedUntil: Long? = null,
        oneTime: Boolean = false,
    ) = Alarm(
        id = 7,
        time = 7 * 3_600 + 30 * 60,
        enabled = enabled,
        fireDate = fireDate,
        eventId = eventId,
        name = "起床",
        note = "",
        snoozedUntil = snoozedUntil,
        oneTime = oneTime,
    )

    @Test
    fun eventPayloadMirrorsDocumentedShape() {
        val json = BridgeJson.eventsPayload(
            total = 2,
            shown = listOf(event()),
            alarmEventIds = setOf(0),
            tagNames = mapOf(0L to listOf("work")),
            timezone = "Asia/Taipei",
        ).jsonObject

        assertEquals("2", json["total"]!!.jsonPrimitive.content)
        assertEquals("true", json["truncated"]!!.jsonPrimitive.content)
        assertEquals("Asia/Taipei", json["timezone"]!!.jsonPrimitive.content)
        val event = json["events"]!!.jsonArray[0].jsonObject
        assertEquals("2026-11-03", event["date"]!!.jsonPrimitive.content)
        assertEquals("09:00", event["start"]!!.jsonPrimitive.content)
        assertEquals("10:30", event["end"]!!.jsonPrimitive.content)
        assertEquals("會議", event["title"]!!.jsonPrimitive.content)
        assertEquals("none", event["repeat"]!!.jsonPrimitive.content)
        assertEquals("0", event["seriesId"]!!.jsonPrimitive.content)
        assertEquals("0", event["occurrenceIndex"]!!.jsonPrimitive.content)
        assertEquals("true", event["hasAlarm"]!!.jsonPrimitive.content)
        assertEquals("10", event["reminderMinutes"]!!.jsonPrimitive.content)
        assertEquals(listOf("work"), event["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(event.containsKey("endDate"))
        assertTrue(event.containsKey("allDay"))
    }

    @Test
    fun allDayEventOmitsTimesAndOvernightEventHasEndDate() {
        val payload = BridgeJson.eventsPayload(
            total = 2,
            shown = listOf(
                event(allDay = true),
                event(start = 23 * 3_600, end = 7 * 3_600 + Event.SECONDS_PER_DAY),
            ),
            alarmEventIds = emptySet(),
            tagNames = emptyMap(),
            timezone = "Asia/Taipei",
        ).jsonObject["events"]!!.jsonArray

        val allDay = payload[0].jsonObject
        assertEquals("true", allDay["allDay"]!!.jsonPrimitive.content)
        assertFalse(allDay.containsKey("start"))
        assertFalse(allDay.containsKey("end"))

        val overnight = payload[1].jsonObject
        assertEquals("23:00", overnight["start"]!!.jsonPrimitive.content)
        assertEquals("07:00", overnight["end"]!!.jsonPrimitive.content)
        assertEquals("2026-11-04", overnight["endDate"]!!.jsonPrimitive.content)
    }

    @Test
    fun weeklyEventIncludesRepeatDays() {
        val event = event(repeatType = Event.REPEAT_WEEKLY, repeatDays = "1,3,5")
        val json = BridgeJson.eventsPayload(1, listOf(event), emptySet(), emptyMap(), "UTC")
            .jsonObject["events"]!!.jsonArray[0].jsonObject
        assertEquals("weekly", json["repeat"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("1", "3", "5"),
            json["repeatDays"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun eventDetailAddsAlarmIdWhenLinked() {
        val withAlarm = BridgeJson.eventDetail(event(), alarmId = 12L, tagNames = listOf("work")).jsonObject
        assertEquals("12", withAlarm["alarmId"]!!.jsonPrimitive.content)
        assertEquals("true", withAlarm["hasAlarm"]!!.jsonPrimitive.content)

        val withoutAlarm = BridgeJson.eventDetail(event(), alarmId = null, tagNames = emptyList()).jsonObject
        assertFalse(withoutAlarm.containsKey("alarmId"))
        assertEquals("false", withoutAlarm["hasAlarm"]!!.jsonPrimitive.content)
    }

    @Test
    fun alarmPayloadMirrorsDocumentedShape() {
        val snooze = Instant.parse("2026-11-03T22:00:00Z").toEpochMilli()
        val payload = BridgeJson.alarmsPayload(
            total = 3,
            shown = listOf(
                alarm(eventId = 5, snoozedUntil = snooze, oneTime = true),
                alarm(enabled = false),
            ),
            tagNames = mapOf(7L to listOf("morning")),
        ).jsonObject

        assertEquals("3", payload["total"]!!.jsonPrimitive.content)
        assertEquals("true", payload["truncated"]!!.jsonPrimitive.content)
        val alarms = payload["alarms"]!!.jsonArray

        val eventAlarm = alarms[0].jsonObject
        assertEquals("07:30", eventAlarm["time"]!!.jsonPrimitive.content)
        assertEquals("true", eventAlarm["fromEvent"]!!.jsonPrimitive.content)
        assertEquals("5", eventAlarm["eventId"]!!.jsonPrimitive.content)
        assertEquals("true", eventAlarm["oneTime"]!!.jsonPrimitive.content)
        assertEquals("2026-11-03T22:00:00Z", eventAlarm["snoozedUntil"]!!.jsonPrimitive.content)
        assertEquals(listOf("morning"), eventAlarm["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(eventAlarm.containsKey("date"))

        val plainAlarm = alarms[1].jsonObject
        assertEquals("false", plainAlarm["enabled"]!!.jsonPrimitive.content)
        assertFalse(plainAlarm.containsKey("eventId"))
        assertFalse(plainAlarm.containsKey("snoozedUntil"))
        assertFalse(plainAlarm.containsKey("note"))
    }

    @Test
    fun oneTimeAlarmWithFireDateIncludesDate() {
        val json = BridgeJson.alarmDetail(alarm(fireDate = date, oneTime = true), tagNames = emptyList()).jsonObject
        assertEquals("2026-11-03", json["date"]!!.jsonPrimitive.content)
        assertEquals("true", json["oneTime"]!!.jsonPrimitive.content)
    }

    @Test
    fun tagsPayloadListsIdNameColor() {
        val color = 0xFF112233.toInt()
        val json = BridgeJson.tagsPayload(listOf(Tag(id = 3, name = "work", color = color)))
            .jsonObject["tags"]!!.jsonArray[0].jsonObject
        assertEquals("3", json["id"]!!.jsonPrimitive.content)
        assertEquals("work", json["name"]!!.jsonPrimitive.content)
        assertEquals(color, json["color"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun metaContainsAllDocumentedFields() {
        val json = BridgeJson.meta("1.2.1", 14, "Asia/Taipei", "2026-11-02T10:00:00").jsonObject
        assertEquals("clockwise", json["app"]!!.jsonPrimitive.content)
        assertEquals("1.2.1", json["versionName"]!!.jsonPrimitive.content)
        assertEquals("14", json["dbVersion"]!!.jsonPrimitive.content)
        assertEquals("Asia/Taipei", json["timezone"]!!.jsonPrimitive.content)
        assertEquals("1", json["bridgeVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorShapeMatchesDocumentedContract() {
        val json = BridgeJson.error("bad_request", "Bad query").jsonObject["error"]!!.jsonObject
        assertEquals("bad_request", json["code"]!!.jsonPrimitive.content)
        assertEquals("Bad query", json["message"]!!.jsonPrimitive.content)
    }
}
