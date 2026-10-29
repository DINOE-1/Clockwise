package com.dinoe.clockwise.ai

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptTest {
    private val now = ZonedDateTime.of(2026, 10, 29, 14, 30, 0, 0, ZoneId.of("Asia/Taipei"))

    @Test
    fun dynamicContextCarriesResolvedDates() {
        val context = AiPrompt.buildDynamicContext(
            now = now,
            tags = listOf("工作"),
            actions = listOf("建立活動"),
        )

        assertTrue(context.contains("2026-10-29 14:30"))
        assertTrue(context.contains("Today: 2026-10-29 週四"))
        assertTrue(context.contains("Tomorrow: 2026-10-30 週五"))
        assertTrue(context.contains("Day after tomorrow: 2026-10-31 週六"))
        assertTrue(context.contains("This week (Mon-Sun): 10/26 週一"))
        assertTrue(context.contains("Next week (Mon-Sun): 11/2 週一"))
        assertTrue(context.contains("never ask for the current date"))
    }

    @Test
    fun dynamicContextStaysWithinBudget() {
        val context = AiPrompt.buildDynamicContext(
            now = now,
            tags = (1..30).map { "標籤$it" },
            actions = (1..6).map { "建立活動 $it" },
        )

        assertTrue(
            "dynamic context used ${AiTokenBudget.estimateTokens(context)} tokens",
            AiTokenBudget.estimateTokens(context) <= AiTokenBudget.MAX_DYNAMIC_TOKENS,
        )
    }
}
