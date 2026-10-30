package com.dinoe.clockwise.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToolDataScopeTest {
    @Test
    fun collectsIdsFromUpdateAndDeleteCalls() {
        val calls = listOf(
            ChatToolCall(
                id = "1",
                name = AiTools.UPDATE_ALARMS,
                arguments = """{"alarms":[{"id":4,"time":"07:00"},{"id":9}]}""",
            ),
            ChatToolCall(
                id = "2",
                name = AiTools.DELETE_ALARMS,
                arguments = """{"alarmIds":[4,12]}""",
            ),
            ChatToolCall(
                id = "3",
                name = AiTools.UPDATE_EVENTS,
                arguments = """{"events":[{"id":30,"scope":"this"}]}""",
            ),
            ChatToolCall(
                id = "4",
                name = AiTools.DELETE_EVENTS,
                arguments = """{"events":[{"id":31},{"id":30}]}""",
            ),
        )
        val references = AiToolReferences.collect(calls)
        assertEquals(setOf(4L, 9L, 12L), references.alarmIds)
        assertEquals(setOf(30L, 31L), references.eventIds)
    }

    @Test
    fun createCallsAndMalformedArgumentsReferenceNothing() {
        val references = AiToolReferences.collect(
            listOf(
                ChatToolCall("1", AiTools.CREATE_ALARMS, """{"alarms":[{"time":"07:00"}]}"""),
                ChatToolCall("2", AiTools.CREATE_ALARM_STACKS, """{"stacks":[{"times":["07:00"]}]}"""),
                ChatToolCall("3", AiTools.CREATE_EVENTS, """{"events":[{"title":"x"}]}"""),
                ChatToolCall("4", AiTools.UPDATE_EVENTS, "not json"),
                ChatToolCall("5", AiTools.DELETE_EVENTS, """{"events":"nope"}"""),
                ChatToolCall("6", AiTools.DELETE_ALARMS, """{"alarmIds":["x",-3,0]}"""),
            ),
        )
        assertTrue(references.isEmpty)
    }

    @Test
    fun chunksInLookupsAboveTheStatementParameterLimit() {
        val ids = (1L..1_201L).toList()
        val chunks = AiIdChunks.of(ids)
        assertEquals(listOf(500, 500, 201), chunks.map { it.size })
        assertEquals(ids, chunks.flatten())
        assertTrue(chunks.all { it.size <= AiIdChunks.MAX_PARAMS })
    }

    @Test
    fun emptyOrDuplicateIdsProduceNoEmptyChunk() {
        assertTrue(AiIdChunks.of(emptyList()).isEmpty())
        assertEquals(listOf(listOf(1L, 2L)), AiIdChunks.of(listOf(1L, 2L, 1L)))
    }
}
