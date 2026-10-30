package com.dinoe.clockwise.ui

/**
 * Access-ordered LRU map. Not thread safe: the owner confines every call to one thread.
 * Inserting past [maxEntries] drops the least recently used entry.
 */
class BoundedLruCache<K : Any, V : Any>(val maxEntries: Int) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val entries = object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxEntries
    }

    val size: Int get() = entries.size

    fun get(key: K): V? = entries[key]

    fun put(key: K, value: V): V? = entries.put(key, value)

    fun remove(key: K): V? = entries.remove(key)

    /** Keys in least-to-most recently used order. */
    fun keys(): List<K> = entries.keys.toList()

    /** Drops every entry whose key is absent from [keys], releasing its value. */
    fun retainOnly(keys: Collection<K>) {
        if (entries.isEmpty()) return
        val keep = keys.toHashSet()
        entries.keys.retainAll(keep)
    }

    fun clear() = entries.clear()
}
