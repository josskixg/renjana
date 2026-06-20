package com.fesu.renjana.utils

import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe LRU cache for virtual container resources.
 *
 * Tracks memory usage per instance and across the entire cache.
 * Automatic eviction when memory thresholds are exceeded.
 * Provides cache statistics for monitoring.
 */
class ResourceCache(
    private val maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES
) {
    companion object {
        private const val TAG = "ResourceCache"
        /** 48 MB default cap — conservative to leave headroom for guest app */
        private const val DEFAULT_MAX_MEMORY_BYTES: Long = 48L * 1024 * 1024

        /** Key separator: "instanceId::resourceType::resourceId" */
        private const val KEY_SEP = "::"

        /**
         * Rough heap-size estimation for common Android resource types.
         */
        fun estimateSize(value: Any): Int {
            return when (value) {
                is String -> value.length * 2 + 40
                is Int -> 16
                is Long -> 24
                is Float -> 16
                is Boolean -> 16
                is IntArray -> value.size * 4 + 16
                is CharSequence -> value.length * 2 + 40
                is android.graphics.Bitmap -> value.byteCount
                is android.graphics.drawable.Drawable -> {
                    if (value is android.graphics.drawable.BitmapDrawable) {
                        value.bitmap?.byteCount ?: 256
                    } else {
                        256 // generic drawable estimate
                    }
                }
                is android.content.res.ColorStateList -> 64
                is android.content.res.XmlResourceParser -> 128
                else -> 64 // conservative fallback
            }
        }
    }

    // ── Inner LRU that tracks entry sizes ──────────────────────────────
    private val lruCache = object : LruCache<String, CacheEntry>(maxMemoryBytes.toInt()) {
        override fun sizeOf(key: String, value: CacheEntry): Int {
            return value.estimatedBytes
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: CacheEntry,
            newValue: CacheEntry?
        ) {
            if (evicted) {
                stats.evictionCount.incrementAndGet()
            }
            // Remove from the instance→keys index
            val instanceId = key.substringBefore(KEY_SEP, "")
            if (instanceId.isNotEmpty()) {
                instanceKeys[instanceId]?.remove(key)
            }
            super.entryRemoved(evicted, key, oldValue, newValue)
        }
    }

    /** Fast lookup: instanceId → set of cache keys belonging to that instance */
    private val instanceKeys = ConcurrentHashMap<String, MutableSet<String>>()

    // ── Statistics ──────────────────────────────────────────────────────
    val stats = CacheStats()

    data class CacheEntry(
        val value: Any,
        val estimatedBytes: Int,
        val instanceId: String,
        val resourceType: String,
        val resourceId: String,
        val timestamp: Long = System.nanoTime()
    )

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Store a resource in the cache.
     *
     * @param instanceId  Virtual instance ID owning this resource
     * @param resourceType  Type category (e.g. "string", "drawable", "layout")
     * @param resourceId  Unique resource identifier (e.g. "com.pkg:string/app_name" or hex id)
     * @param value  The resolved resource value
     * @param estimatedBytes  Approximate heap footprint of [value]
     */
    fun put(
        instanceId: String,
        resourceType: String,
        resourceId: String,
        value: Any,
        estimatedBytes: Int = estimateSize(value)
    ) {
        val key = buildKey(instanceId, resourceType, resourceId)
        val entry = CacheEntry(
            value = value,
            estimatedBytes = estimatedBytes.coerceAtLeast(1),
            instanceId = instanceId,
            resourceType = resourceType,
            resourceId = resourceId
        )
        lruCache.put(key, entry)

        // Track key under its instance
        instanceKeys.getOrPut(instanceId) {
            ConcurrentHashMap.newKeySet()
        }.add(key)

        stats.putCount.incrementAndGet()
        RenjanaLog.v(TAG, "put $key (${estimatedBytes}B) — size=${lruCache.size()}")
    }

    /**
     * Retrieve a cached resource, or null if absent / evicted.
     */
    fun get(instanceId: String, resourceType: String, resourceId: String): Any? {
        val key = buildKey(instanceId, resourceType, resourceId)
        val entry = lruCache.get(key)
        if (entry != null) {
            stats.hitCount.incrementAndGet()
            return entry.value
        }
        stats.missCount.incrementAndGet()
        return null
    }

    /**
     * Remove a single cached resource.
     */
    fun remove(instanceId: String, resourceType: String, resourceId: String) {
        val key = buildKey(instanceId, resourceType, resourceId)
        lruCache.remove(key)
    }

    /**
     * Invalidate all cached entries belonging to [instanceId].
     * Called when an instance is destroyed or its APK is updated.
     *
     * @return number of entries removed
     */
    fun invalidateInstance(instanceId: String): Int {
        val keys = instanceKeys.remove(instanceId) ?: return 0
        var removed = 0
        for (key in keys) {
            if (lruCache.remove(key) != null) removed++
        }
        RenjanaLog.d(TAG, "Invalidated $removed entries for instance $instanceId")
        return removed
    }

    /**
     * Clear the entire cache. Called on container shutdown.
     */
    fun clearAll() {
        lruCache.evictAll()
        instanceKeys.clear()
        stats.reset()
        RenjanaLog.i(TAG, "Cache cleared")
    }

    /**
     * Trim cache to [sizeBytes] by evicting least-recently-used entries.
     */
    fun trimToSize(sizeBytes: Int) {
        lruCache.trimToSize(sizeBytes)
    }

    // ── Snapshot accessors (thread-safe reads) ──────────────────────────

    fun size(): Int = lruCache.size()
    fun maxSize(): Int = lruCache.maxSize()
    fun instanceCount(): Int = instanceKeys.size

    fun instanceCacheSize(instanceId: String): Int {
        val keys = instanceKeys[instanceId] ?: return 0
        return keys.sumOf { key -> lruCache.snapshot()[key]?.estimatedBytes ?: 0 }
    }

    /**
     * Return a point-in-time snapshot of cache statistics.
     */
    fun snapshot(): StatsSnapshot {
        return StatsSnapshot(
            totalSize = lruCache.size(),
            maxSize = lruCache.maxSize(),
            hitCount = stats.hitCount.get(),
            missCount = stats.missCount.get(),
            putCount = stats.putCount.get(),
            evictionCount = stats.evictionCount.get(),
            instanceCount = instanceKeys.size,
            hitRate = stats.hitRate()
        )
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun buildKey(instanceId: String, resourceType: String, resourceId: String): String {
        return "$instanceId$KEY_SEP$resourceType$KEY_SEP$resourceId"
    }

    // ── Statistics helpers ──────────────────────────────────────────────

    class CacheStats {
        val hitCount = AtomicLong(0)
        val missCount = AtomicLong(0)
        val putCount = AtomicLong(0)
        val evictionCount = AtomicLong(0)

        fun hitRate(): Double {
            val hits = hitCount.get()
            val misses = missCount.get()
            val total = hits + misses
            return if (total == 0L) 0.0 else hits.toDouble() / total
        }

        fun reset() {
            hitCount.set(0)
            missCount.set(0)
            putCount.set(0)
            evictionCount.set(0)
        }
    }

    data class StatsSnapshot(
        val totalSize: Int,
        val maxSize: Int,
        val hitCount: Long,
        val missCount: Long,
        val putCount: Long,
        val evictionCount: Long,
        val instanceCount: Int,
        val hitRate: Double
    ) {
        override fun toString(): String {
            return "ResourceCache[size=$totalSize/$maxSize, hits=$hitCount, " +
                "misses=$missCount, evictions=$evictionCount, " +
                "instances=$instanceCount, hitRate=${"%.1f".format(hitRate * 100)}%]"
        }
    }
}
