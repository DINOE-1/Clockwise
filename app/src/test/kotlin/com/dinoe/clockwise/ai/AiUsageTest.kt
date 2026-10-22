package com.dinoe.clockwise.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiUsageTest {
    @Test
    fun computesCacheHitRateWhenReported() {
        val usage = AiUsage(
            promptTokens = 1_000,
            cachedTokens = 250,
            cacheReported = true,
        )
        assertEquals(0.25, usage.cacheHitRate!!, 0.0001)
    }

    @Test
    fun returnsNullWhenProviderDoesNotReportCache() {
        val usage = AiUsage(promptTokens = 1_000, cachedTokens = 0, cacheReported = false)
        assertNull(usage.cacheHitRate)
    }

    @Test
    fun returnsNullWithoutPromptTokens() {
        assertNull(AiUsage(cacheReported = true).cacheHitRate)
    }

    @Test
    fun clampsUnexpectedOverReports() {
        val usage = AiUsage(
            promptTokens = 100,
            cachedTokens = 150,
            cacheReported = true,
        )
        assertEquals(1.0, usage.cacheHitRate!!, 0.0001)
    }

    @Test
    fun sessionTotalsAggregateUsageAndCacheFlag() {
        val total = AiUsage(promptTokens = 100, cachedTokens = 20, cacheReported = true) +
            AiUsage(promptTokens = 50, cachedTokens = 10, cacheReported = false)
        assertEquals(150L, total.promptTokens)
        assertEquals(30L, total.cachedTokens)
        assertEquals(0.2, total.cacheHitRate!!, 0.0001)
    }
}
