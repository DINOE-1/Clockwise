package com.dinoe.clockwise.ui

import com.dinoe.clockwise.data.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AlarmGroupingTest {
    private val now = LocalDateTime.of(2026, 1, 5, 6, 0)

    @Test
    fun alarmsSharingAStackIdFormOneStack() {
        val alarms = listOf(
            Alarm(id = 1, time = 7 * 3600, stackId = 99),
            Alarm(id = 2, time = 7 * 3600 + 600, stackId = 99),
        )

        val group = buildAlarmGroups(alarms, emptyList(), now).single()

        assertTrue(group.isStack)
        assertEquals("stack:99", group.key)
        assertEquals(listOf(1L, 2L), group.alarms.map { it.id })
        assertEquals(1L, group.front.id)
        assertEquals(listOf(2L), group.members.map { it.id })
    }

    @Test
    fun differentStackIdsStaySeparate() {
        val alarms = listOf(
            Alarm(id = 1, time = 7 * 3600, stackId = 99),
            Alarm(id = 2, time = 8 * 3600, stackId = 100),
        )

        val groups = buildAlarmGroups(alarms, emptyList(), now)

        assertEquals(2, groups.size)
        assertFalse(groups.any { it.isStack })
    }

    @Test
    fun standaloneAlarmsStaySeparateEvenAtTheSameTime() {
        val alarms = listOf(
            Alarm(id = 1, time = 7 * 3600),
            Alarm(id = 2, time = 7 * 3600),
        )

        val groups = buildAlarmGroups(alarms, emptyList(), now)

        assertEquals(2, groups.size)
        assertEquals(listOf("alarm:1", "alarm:2"), groups.map { it.key }.sorted())
    }

    @Test
    fun stackFrontIsTheNextEnabledAlarm() {
        val alarms = listOf(
            Alarm(id = 1, time = 7 * 3600, enabled = false, stackId = 99),
            Alarm(id = 2, time = 8 * 3600, enabled = true, stackId = 99),
        )

        val group = buildAlarmGroups(alarms, emptyList(), now).single()

        assertEquals(2L, group.front.id)
        assertEquals(1L, group.members.single().id)
    }
}
