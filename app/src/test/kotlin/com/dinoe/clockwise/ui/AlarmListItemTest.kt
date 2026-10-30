package com.dinoe.clockwise.ui

import com.dinoe.clockwise.data.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmListItemTest {
    private fun single(id: Long) = AlarmGroup(
        key = "alarm:$id",
        alarms = listOf(Alarm(id = id, time = 7 * 3_600)),
    )

    private fun stack(key: String, vararg ids: Long) = AlarmGroup(
        key = key,
        alarms = ids.mapIndexed { index, id -> Alarm(id = id, time = 7 * 3_600 + index * 600) },
    )

    private val singleGroup = single(1)
    private val stackGroup = stack("stack:99", 2, 3, 4)

    @Test
    fun expandedStackFlattensItsMembersAfterTheCard() {
        val items = buildAlarmListItems(
            groups = listOf(singleGroup, stackGroup),
            expandedGroupKeys = setOf("stack:99"),
            drawerExpansion = false,
        )

        assertEquals(
            listOf("alarm:1", "stack:stack:99", "alarm:3", "alarm:4"),
            items.map { it.key },
        )
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            items.map { item ->
                when (item) {
                    is AlarmListItem.Single -> item.alarm.id
                    is AlarmListItem.Stack -> item.group.front.id
                    is AlarmListItem.Member -> item.alarm.id
                }
            },
        )
        assertTrue(items.filterIsInstance<AlarmListItem.Member>().all { it.groupKey == "stack:99" })
    }

    @Test
    fun collapsedStackListsOnlyTheCardInFadeMode() {
        val items = buildAlarmListItems(
            groups = listOf(singleGroup, stackGroup),
            expandedGroupKeys = emptySet(),
            drawerExpansion = false,
            collapsingGroupKeys = setOf("stack:99"),
        )

        assertEquals(listOf("alarm:1", "stack:stack:99"), items.map { it.key })
    }

    @Test
    fun drawerKeepsRowsListedUntilTheCollapseFinishes() {
        val collapsing = buildAlarmListItems(
            groups = listOf(stackGroup),
            expandedGroupKeys = emptySet(),
            drawerExpansion = true,
            collapsingGroupKeys = setOf("stack:99"),
        )
        assertEquals(
            listOf("stack:stack:99", "alarm:3", "alarm:4"),
            collapsing.map { it.key },
        )

        val finished = buildAlarmListItems(
            groups = listOf(stackGroup),
            expandedGroupKeys = emptySet(),
            drawerExpansion = true,
            collapsingGroupKeys = emptySet(),
        )
        assertEquals(listOf("stack:stack:99"), finished.map { it.key })
    }

    @Test
    fun expandedDrawerStackListsTheSameRowsAsFadeMode() {
        val drawer = buildAlarmListItems(
            groups = listOf(singleGroup, stackGroup),
            expandedGroupKeys = setOf("stack:99"),
            drawerExpansion = true,
        )
        val fade = buildAlarmListItems(
            groups = listOf(singleGroup, stackGroup),
            expandedGroupKeys = setOf("stack:99"),
            drawerExpansion = false,
        )

        assertEquals(fade.map { it.key }, drawer.map { it.key })
    }

    @Test
    fun everyItemHasAUniqueKey() {
        val groups = listOf(singleGroup, stackGroup, stack("stack:100", 5, 6), single(7))
        val items = buildAlarmListItems(
            groups = groups,
            expandedGroupKeys = setOf("stack:99", "stack:100"),
            drawerExpansion = true,
        )

        assertEquals(items.size, items.map { it.key }.distinct().size)
        assertEquals(
            listOf(
                "alarm:1",
                "stack:stack:99",
                "alarm:3",
                "alarm:4",
                "stack:stack:100",
                "alarm:6",
                "alarm:7",
            ),
            items.map { it.key },
        )
    }
}
