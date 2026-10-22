package com.dinoe.clockwise.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTokenBudgetTest {
    @Test
    fun estimatesCjkAndAscii() {
        assertEquals(3, AiTokenBudget.estimateTokens("你好嗎"))
        assertEquals(2, AiTokenBudget.estimateTokens("abcdefgh"))
        assertEquals(3, AiTokenBudget.estimateTokens("你好abcd"))
    }

    @Test
    fun truncatesByEstimatedTokens() {
        val text = "你好你好你好你好"
        val truncated = AiTokenBudget.truncateToTokens(text, 4)
        assertEquals("你好你好", truncated)
    }

    @Test
    fun truncatesByCharacters() {
        assertEquals("abc", AiTokenBudget.truncateChars("abcdef", 3))
    }

    @Test
    fun userCapIsApplied() {
        val toolDefinitionsTokens = AiTokenBudget.estimateTokens(AiTools.definitions.toString())
        val staticTokens = AiTokenBudget.estimateTokens(AiPrompt.STATIC_SYSTEM_PROMPT)
        assertTrue(staticTokens + toolDefinitionsTokens < AiTokenBudget.HARD_INPUT_TOKENS)
    }
}
