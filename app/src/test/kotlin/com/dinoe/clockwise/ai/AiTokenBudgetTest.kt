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
    fun countsImagesAsInputTokens() {
        assertEquals(
            AiTokenBudget.estimateMessageTokens("hi") + AiTokenBudget.IMAGE_INPUT_TOKENS,
            AiTokenBudget.estimateMessageTokens("hi", imageCount = 1),
        )
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

    @Test
    fun unknownModelKeepsTheSafeDefaultBudget() {
        assertEquals(AiTokenBudget.HARD_INPUT_TOKENS, AiTokenBudget.inputBudgetFor(null))
        assertEquals(AiTokenBudget.HARD_INPUT_TOKENS, AiTokenBudget.inputBudgetFor(0))
        assertEquals(AiTokenBudget.HARD_INPUT_TOKENS, AiTokenBudget.inputBudgetFor(-1))
    }

    @Test
    fun knownModelBudgetReservesRoomForOutput() {
        val context = 16_384L
        val budget = AiTokenBudget.inputBudgetFor(context)
        assertEquals(
            (context - AiTokenBudget.OUTPUT_RESERVE_TOKENS -
                AiTokenBudget.CONTEXT_SAFETY_MARGIN_TOKENS).toInt(),
            budget,
        )
        assertTrue(
            "input + output reserve must fit in the model context",
            budget + AiTokenBudget.OUTPUT_RESERVE_TOKENS +
                AiTokenBudget.CONTEXT_SAFETY_MARGIN_TOKENS <= context,
        )
    }

    @Test
    fun hugeModelContextIsCapped() {
        assertEquals(
            AiTokenBudget.MAX_DERIVED_INPUT_TOKENS,
            AiTokenBudget.inputBudgetFor(1_000_000),
        )
        assertEquals(
            AiTokenBudget.MAX_DERIVED_INPUT_TOKENS,
            AiTokenBudget.inputBudgetFor(Long.MAX_VALUE),
        )
    }

    @Test
    fun tinyModelContextIsClampedToTheFloor() {
        val budget = AiTokenBudget.inputBudgetFor(4_096)
        assertEquals(AiTokenBudget.MIN_DERIVED_INPUT_TOKENS, budget)
        assertTrue(
            "a 4k model must still leave room for output",
            budget + AiTokenBudget.CONTEXT_SAFETY_MARGIN_TOKENS < 4_096,
        )
        assertEquals(0, AiTokenBudget.inputBudgetFor(1))
    }

    @Test
    fun toolResultAllowanceStartsAtThePerResultLimit() {
        assertEquals(
            AiTokenBudget.MAX_TOOL_RESULT_TOKENS,
            AiTokenBudget.toolResultAllowance(usedTokens = 0),
        )
    }

    @Test
    fun toolResultAllowanceShrinksWithTheAggregateBudget() {
        val used = AiTokenBudget.MAX_CURRENT_TOOL_TOKENS - 300
        assertEquals(300, AiTokenBudget.toolResultAllowance(used))
    }

    @Test
    fun toolResultAllowanceKeepsTheMinimumAfterTheBudgetIsSpent() {
        assertEquals(
            AiTokenBudget.MIN_TOOL_RESULT_TOKENS,
            AiTokenBudget.toolResultAllowance(AiTokenBudget.MAX_CURRENT_TOOL_TOKENS),
        )
        assertEquals(
            AiTokenBudget.MIN_TOOL_RESULT_TOKENS,
            AiTokenBudget.toolResultAllowance(AiTokenBudget.MAX_CURRENT_TOOL_TOKENS * 2),
        )
        assertTrue(AiTokenBudget.MIN_TOOL_RESULT_TOKENS < AiTokenBudget.MAX_TOOL_RESULT_TOKENS)
    }

    @Test
    fun twoFullPayloadsSpendTheAggregateBudget() {
        var used = 0
        repeat(2) { used += AiTokenBudget.toolResultAllowance(used) }
        assertEquals(AiTokenBudget.MAX_CURRENT_TOOL_TOKENS, used)
        // Once the aggregate budget is spent only the minimum envelope is granted.
        assertEquals(
            AiTokenBudget.MIN_TOOL_RESULT_TOKENS,
            AiTokenBudget.toolResultAllowance(used),
        )
    }
}
