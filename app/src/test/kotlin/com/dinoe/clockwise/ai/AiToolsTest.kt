package com.dinoe.clockwise.ai

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToolsTest {
    @Test
    fun definesEveryReadAndWriteTool() {
        val names = AiTools.definitions.map { element ->
            element.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        }
        assertEquals(
            setOf(
                AiTools.LIST_ALARMS,
                AiTools.LIST_EVENTS,
                AiTools.LIST_RINGTONES,
                AiTools.CREATE_ALARMS,
                AiTools.CREATE_ALARM_STACKS,
                AiTools.CREATE_EVENTS,
                AiTools.UPDATE_ALARMS,
                AiTools.DELETE_ALARMS,
                AiTools.UPDATE_EVENTS,
                AiTools.DELETE_EVENTS,
            ),
            names.toSet(),
        )
        assertEquals(10, names.size)
    }

    @Test
    fun everyToolHasAParametersObject() {
        AiTools.definitions.forEach { element ->
            val function = element.jsonObject["function"]!!.jsonObject
            assertTrue(
                "missing parameters for ${function["name"]}",
                function["parameters"] is kotlinx.serialization.json.JsonObject,
            )
        }
    }

    @Test
    fun cachedDefinitionSerializationMatchesTheSchema() {
        assertEquals(AiTools.definitions.toString(), AiTools.definitionsJson)
        assertEquals(
            AiTokenBudget.estimateTokens(AiTools.definitionsJson),
            AiTools.definitionTokens,
        )
    }

    @Test
    fun writeToolSetMatchesDefinitions() {
        assertEquals(
            setOf(
                AiTools.CREATE_ALARMS,
                AiTools.CREATE_ALARM_STACKS,
                AiTools.CREATE_EVENTS,
                AiTools.UPDATE_ALARMS,
                AiTools.DELETE_ALARMS,
                AiTools.UPDATE_EVENTS,
                AiTools.DELETE_EVENTS,
            ),
            AiTools.writeTools,
        )
    }
}
