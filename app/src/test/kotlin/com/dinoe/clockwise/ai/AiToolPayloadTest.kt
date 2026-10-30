package com.dinoe.clockwise.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToolPayloadTest {
    // Each item costs 1 token plus a fixed 10-token envelope, so counts are easy to reason about.
    private fun envelopeCost(items: List<Int>): Int = 10 + items.size

    @Test
    fun keepsEveryItemWhenThePayloadFits() {
        val items = (1..5).toList()
        assertEquals(items, AiToolPayload.fittingPrefix(items, 100, estimatedTokens = ::envelopeCost))
    }

    @Test
    fun dropsItemsFromTheEndDeterministically() {
        val items = (1..10).toList()
        val kept = AiToolPayload.fittingPrefix(items, 15, estimatedTokens = ::envelopeCost)
        assertEquals(listOf(1, 2, 3, 4, 5), kept)
        assertEquals(kept, AiToolPayload.fittingPrefix(items, 15, estimatedTokens = ::envelopeCost))
        assertTrue(envelopeCost(kept) <= 15)
    }

    @Test
    fun keepsOneItemWhenTheBudgetIsSmallerThanAnyItem() {
        val items = listOf("a", "b", "c")
        assertEquals(listOf("a"), AiToolPayload.fittingPrefix(items, 1) { 999 })
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertTrue(AiToolPayload.fittingPrefix(emptyList<String>(), 1) { 999 }.isEmpty())
    }

    @Test
    fun paysForTruncationFlagsInTheEstimate() {
        // A shorter prefix is not automatically cheaper: the payload flags itself as truncated.
        val items = (1..30).toList()
        val kept = AiToolPayload.fittingPrefix(items, 12) { envelopeCost(it) + 1 }
        assertEquals(1, kept.size)
    }
}
