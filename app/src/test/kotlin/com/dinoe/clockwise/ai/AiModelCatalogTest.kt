package com.dinoe.clockwise.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelCatalogTest {
    @Test
    fun keepsChatCompletionsModels() {
        val model = AiModelInfo(
            id = "deepseek/deepseek-v4.1-flash",
            supportedEndpoints = listOf("/chat/completions", "/responses"),
        )
        assertTrue(AiModelCatalog.isChatCompletionsModel(model))
    }

    @Test
    fun dropsMessagesOnlyClaude() {
        val claude = AiModelInfo(
            id = "claude-sonnet-5",
            supportedEndpoints = listOf("/messages"),
        )
        assertFalse(AiModelCatalog.isChatCompletionsModel(claude))
    }

    @Test
    fun dropsClaudeWhenEndpointsAreMissing() {
        assertFalse(AiModelCatalog.isChatCompletionsModel(AiModelInfo("claude-opus-5")))
    }

    @Test
    fun keepsNonClaudeWhenEndpointsAreMissing() {
        assertTrue(AiModelCatalog.isChatCompletionsModel(AiModelInfo("deepseek-v4-pro")))
    }

    @Test
    fun filtersSortsAndDeduplicates() {
        val models = listOf(
            AiModelInfo("z-model", "Zed", supportedEndpoints = listOf("/chat/completions")),
            AiModelInfo("claude-x", "Claude", supportedEndpoints = listOf("/messages")),
            AiModelInfo("a-model", "Alpha", supportedEndpoints = listOf("/chat/completions")),
            AiModelInfo("a-model", "Alpha duplicate", supportedEndpoints = listOf("/chat/completions")),
        )
        val filtered = AiModelCatalog.chatCompletions(models)
        assertEquals(listOf("a-model", "z-model"), filtered.map { it.id })
    }
}
