package com.fesu.renjana.core

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import com.fesu.renjana.utils.ResourceCache
import com.fesu.renjana.utils.RenjanaLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Central resource management for virtual container instances.
 *
 * Responsibilities:
 * - Load Resources objects from guest APKs via AssetManager reflection
 * - Cache Resources per instance to avoid repeated reflection overhead
 * - Provide resource fallback chain: override → guest → host → system
 * - Handle configuration changes (locale, orientation, density)
 * - Manage memory via LRU eviction and explicit cleanup
 *
 * Thread-safety: ConcurrentHashMap for instance tracking, synchronized blocks
 * for Resources creation (AssetManager.addAssetPath is not thread-safe).
 */
class ResourceManager(private val hostContext: Context) {
    companion object {
        private const val TAG = "ResourceManager"
    }

    // ── Per-instance state ──────────────────────────────────────────────
    private data class InstanceResources(
        val apkPath: String,
        val instanceId: String,
        val resources: Resources,
        val assetManager: AssetManager,
        val overrides: ResourceOverride,
        var lastConfig: Configuration
    )

    private val instances = ConcurrentHashMap<String, InstanceResources>()
    private val resourceCache = ResourceCache()

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Load or retrieve cached Resources for a virtual instance.
     *
     * @param instanceId Unique instance identifier
     * @param apkPath Path to the guest APK
     * @param dataPath Instance data directory (for override storage)
     * @return Resources object for the guest APK, or null if loading fails
     */
    fun getResources(instanceId: String, apkPath: String, dataPath: String): Resources? {
        // Fast path: return cached instance
        instances[instanceId]?.let {
            return it.resources
        }

        // Slow path: create new instance
        synchronized(this) {
            // Double-check after acquiring lock
            instances[instanceId]?.let {
                return it.resources
            }

            return try {
                val instanceRes = createInstanceResources(instanceId, apkPath, dataPath)
                instances[instanceId] = instanceRes
                RenjanaLog.i(TAG, "Loaded resources for instance $instanceId from $apkPath")
                instanceRes.resources
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to load resources for instance $instanceId", e)
                null
            }
        }
    }

    /**
     * Get the ResourceOverride manager for an instance.
     */
    fun getOverrides(instanceId: String): ResourceOverride? {
        return instances[instanceId]?.overrides
    }

    /**
     * Resolve a string resource with fallback chain:
     * override → guest → host → system
     *
     * @param instanceId Virtual instance ID
     * @param resKey Resource key (e.g., "string/app_name" or numeric ID)
     * @return Resolved string, or null if not found
     */
    fun resolveString(instanceId: String, resKey: String): String? {
        val instance = instances[instanceId] ?: return resolveFallbackString(resKey)

        // 1. Check overrides
        instance.overrides.getStringOverride(resKey)?.let {
            return it
        }

        // 2. Try guest resources
        try {
            val resId = instance.resources.getIdentifier(resKey, null, null)
            if (resId != 0) {
                val value = instance.resources.getString(resId)
                // Cache for future lookups
                resourceCache.put(instanceId, "string", resKey, value)
                return value
            }
        } catch (e: Exception) {
            RenjanaLog.v(TAG, "Guest resource not found: $resKey")
        }

        // 3. Check cache (might have been loaded before)
        resourceCache.get(instanceId, "string", resKey)?.let {
            return it as? String
        }

        // 4. Fallback to host/system
        return resolveFallbackString(resKey)
    }

    /**
     * Resolve an integer resource (colors, dimensions, etc.) with fallback.
     */
    fun resolveInt(instanceId: String, resKey: String): Int? {
        val instance = instances[instanceId] ?: return resolveFallbackInt(resKey)

        // 1. Check overrides
        instance.overrides.getColorOverride(resKey)?.let { return it }
        instance.overrides.getIntOverride(resKey)?.let { return it }

        // 2. Try guest resources
        try {
            val resId = instance.resources.getIdentifier(resKey, null, null)
            if (resId != 0) {
                val value = instance.resources.getInteger(resId)
                resourceCache.put(instanceId, "integer", resKey, value)
                return value
            }
        } catch (e: Exception) {
            RenjanaLog.v(TAG, "Guest integer resource not found: $resKey")
        }

        // 3. Check cache
        resourceCache.get(instanceId, "integer", resKey)?.let {
            return it as? Int
        }

        // 4. Fallback
        return resolveFallbackInt(resKey)
    }

    /**
     * Resolve a drawable resource with fallback.
     * [resKey] can be a numeric resource ID string or a "type/name" qualified name.
     */
    fun resolveDrawable(
        instanceId: String,
        resKey: String,
        theme: Resources.Theme? = null
    ): android.graphics.drawable.Drawable? {
        val instance = instances[instanceId] ?: return resolveFallbackDrawable(resKey, theme)

        // 1. Check overrides (in-memory or path-based)
        instance.overrides.getDrawableOverride(resKey, hostContext.resources)?.let {
            return it
        }

        // 2. Try guest resources — handle both numeric IDs and name-based keys
        try {
            val resId = resKey.toIntOrNull()
                ?: instance.resources.getIdentifier(resKey, null, null)
            if (resId != 0) {
                val drawable = if (theme != null) {
                    instance.resources.getDrawable(resId, theme)
                } else {
                    instance.resources.getDrawable(resId, null)
                }
                resourceCache.put(instanceId, "drawable", resKey, drawable)
                return drawable
            }
        } catch (e: Exception) {
            RenjanaLog.v(TAG, "Guest drawable not found: $resKey")
        }

        // 3. Check cache
        resourceCache.get(instanceId, "drawable", resKey)?.let {
            return it as? android.graphics.drawable.Drawable
        }

        // 4. Fallback
        return resolveFallbackDrawable(resKey, theme)
    }

    /**
     * Update configuration for an instance (e.g., after locale change).
     * This invalidates the cached Resources and reloads with new config.
     */
    fun updateConfiguration(instanceId: String, newConfig: Configuration) {
        val instance = instances[instanceId] ?: return

        synchronized(this) {
            try {
                // Update the AssetManager's configuration
                instance.resources.updateConfiguration(newConfig, instance.resources.displayMetrics)
                instance.lastConfig = newConfig

                // Invalidate cached resources for this instance (config-dependent)
                resourceCache.invalidateInstance(instanceId)

                RenjanaLog.d(TAG, "Updated configuration for instance $instanceId")
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to update configuration for $instanceId", e)
            }
        }
    }

    /**
     * Invalidate all cached resources for an instance.
     * Call this when the APK is updated or overrides change.
     */
    fun invalidateInstance(instanceId: String) {
        resourceCache.invalidateInstance(instanceId)
        RenjanaLog.d(TAG, "Invalidated resource cache for instance $instanceId")
    }

    /**
     * Destroy an instance and release all its resources.
     * Call this when a virtual instance is stopped/removed.
     */
    fun destroyInstance(instanceId: String) {
        val instance = instances.remove(instanceId) ?: return

        synchronized(this) {
            try {
                // Clear cache
                resourceCache.invalidateInstance(instanceId)

                // Destroy overrides
                instance.overrides.destroy()

                // Close AssetManager (API 29+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    instance.assetManager.close()
                }

                RenjanaLog.i(TAG, "Destroyed instance $instanceId")
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Error destroying instance $instanceId", e)
            }
        }
    }

    /**
     * Clear all instances and caches. Call on container shutdown.
     */
    fun destroyAll() {
        synchronized(this) {
            instances.keys.toList().forEach { destroyInstance(it) }
            resourceCache.clearAll()
            RenjanaLog.i(TAG, "ResourceManager destroyed")
        }
    }

    /**
     * Get memory usage statistics.
     */
    fun getStats(): ResourceManagerStats {
        val cacheSnapshot = resourceCache.snapshot()
        return ResourceManagerStats(
            instanceCount = instances.size,
            cacheStats = cacheSnapshot,
            totalMemoryBytes = cacheSnapshot.totalSize.toLong()
        )
    }

    // ════════════════════════════════════════════════════════════════════
    //  Internals
    // ════════════════════════════════════════════════════════════════════

    private fun createInstanceResources(
        instanceId: String,
        apkPath: String,
        dataPath: String
    ): InstanceResources {
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            throw IllegalArgumentException("APK not found: $apkPath")
        }

        // 1. Create AssetManager via reflection
        val assetManager = createAssetManager(apkPath)

        // 2. Create Resources with host's display metrics and config
        val hostRes = hostContext.resources
        val resources = Resources(
            assetManager,
            hostRes.displayMetrics,
            hostRes.configuration
        )

        // 3. Create override manager
        val overrides = ResourceOverride(instanceId, dataPath)

        return InstanceResources(
            apkPath = apkPath,
            instanceId = instanceId,
            resources = resources,
            assetManager = assetManager,
            overrides = overrides,
            lastConfig = hostRes.configuration
        )
    }

    /**
     * Create an AssetManager with the guest APK's assets loaded.
     * Uses reflection because AssetManager.addAssetPath() is hidden.
     */
    private fun createAssetManager(apkPath: String): AssetManager {
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()

        // addAssetPath is @hide but accessible via reflection
        val addAssetPathMethod = AssetManager::class.java.getDeclaredMethod(
            "addAssetPath",
            String::class.java
        )
        addAssetPathMethod.isAccessible = true

        val cookie = addAssetPathMethod.invoke(assetManager, apkPath) as? Int
        if (cookie == null || cookie == 0) {
            throw RuntimeException("addAssetPath failed for $apkPath")
        }

        return assetManager
    }

    // ── Fallback resolution ─────────────────────────────────────────────

    private fun resolveFallbackString(resKey: String): String? {
        return try {
            val hostRes = hostContext.resources
            val resId = hostRes.getIdentifier(resKey, null, hostContext.packageName)
            if (resId != 0) hostRes.getString(resId) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveFallbackInt(resKey: String): Int? {
        return try {
            val hostRes = hostContext.resources
            val resId = hostRes.getIdentifier(resKey, null, hostContext.packageName)
            if (resId != 0) hostRes.getInteger(resId) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveFallbackDrawable(
        resKey: String,
        theme: Resources.Theme?
    ): android.graphics.drawable.Drawable? {
        return try {
            val hostRes = hostContext.resources
            val resId = hostRes.getIdentifier(resKey, null, hostContext.packageName)
            if (resId != 0) {
                if (theme != null) hostRes.getDrawable(resId, theme)
                else hostRes.getDrawable(resId, null)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // ── Stats ───────────────────────────────────────────────────────────

    data class ResourceManagerStats(
        val instanceCount: Int,
        val cacheStats: ResourceCache.StatsSnapshot,
        val totalMemoryBytes: Long
    ) {
        override fun toString(): String {
            return "ResourceManager[instances=$instanceCount, " +
                "memory=${totalMemoryBytes / 1024}KB, $cacheStats]"
        }
    }
}
