package com.dinoe.clockwise.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedLruCacheTest {
    @Test
    fun keepsValuesUnderTheSameKeyAndMissesUnknownKeys() {
        val cache = BoundedLruCache<String, Int>(maxEntries = 4)
        cache.put("attachment-a", 1)
        cache.put("attachment-b", 2)

        assertEquals(1, cache.get("attachment-a"))
        assertEquals(2, cache.get("attachment-b"))
        assertNull(cache.get("attachment-c"))
        assertEquals(2, cache.size)
    }

    @Test
    fun replacingAValueKeepsTheEntryCount() {
        val cache = BoundedLruCache<String, Int>(maxEntries = 4)
        cache.put("attachment-a", 1)
        cache.put("attachment-a", 2)

        assertEquals(1, cache.size)
        assertEquals(2, cache.get("attachment-a"))
    }

    @Test
    fun dropsTheLeastRecentlyUsedEntryWhenFull() {
        val cache = BoundedLruCache<String, Int>(maxEntries = 3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        cache.put("d", 4)

        assertEquals(3, cache.size)
        assertNull(cache.get("a"))
        assertEquals(listOf("b", "c", "d"), cache.keys())
    }

    @Test
    fun readingAnEntryProtectsItFromEviction() {
        val cache = BoundedLruCache<String, Int>(maxEntries = 3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        assertEquals(1, cache.get("a"))

        cache.put("d", 4)

        assertNull(cache.get("b"))
        assertEquals(listOf("c", "a", "d"), cache.keys())
    }

    @Test
    fun staysBoundedAcrossManyInsertions() {
        val cache = BoundedLruCache<Int, String>(maxEntries = 5)
        repeat(50) { index -> cache.put(index, "value-$index") }

        assertEquals(5, cache.size)
        assertEquals(listOf(45, 46, 47, 48, 49), cache.keys())
    }

    @Test
    fun retainOnlyReleasesUnreferencedEntries() {
        val cache = BoundedLruCache<String, Int>(maxEntries = 8)
        cache.put("kept-1", 1)
        cache.put("kept-2", 2)
        cache.put("dropped", 3)

        cache.retainOnly(listOf("kept-1", "kept-2"))

        assertEquals(2, cache.size)
        assertEquals(1, cache.get("kept-1"))
        assertEquals(2, cache.get("kept-2"))
        assertNull(cache.get("dropped"))
    }

    @Test
    fun retainOnlyWithNoKeysEmptiesTheCache() {
        val cache = BoundedLruCache<String, Int>(maxEntries = 8)
        cache.put("a", 1)

        cache.retainOnly(emptyList())

        assertTrue(cache.keys().isEmpty())
        assertEquals(0, cache.size)
    }

    @Test
    fun removeAndClearReleaseEntries() {
        val cache = BoundedLruCache<String, Int>(maxEntries = 8)
        cache.put("a", 1)
        cache.put("b", 2)

        assertEquals(1, cache.remove("a"))
        assertNull(cache.remove("a"))
        assertEquals(1, cache.size)

        cache.clear()

        assertEquals(0, cache.size)
        assertNull(cache.get("b"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveCapacity() {
        BoundedLruCache<String, Int>(maxEntries = 0)
    }
}
