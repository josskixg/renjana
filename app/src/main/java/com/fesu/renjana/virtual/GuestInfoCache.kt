package com.fesu.renjana.virtual

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import com.fesu.renjana.utils.RenjanaLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache for ApplicationInfo / PackageInfo / Resources per guest instance + package.
 *
 * The guest app's PackageInfo and ApplicationInfo must remain consistent across every
 * Context call inside the container. Re-parsing the APK on every hook hit is far too
 * expensive, so we parse once (on instance launch) and hand out the cached objects.
 *
 * Resources are built from the APK via [AssetManager.addAssetPath] (the same technique
 * used by VirtualApp / BlackDex) so that the guest sees its own strings/drawables rather
 * than the host's.
 *
 * Keys are compound "$instanceId:$packageName" so multiple apps in the same instance
 * never overwrite each other's cached data.
 */
object GuestInfoCache {
    private const val TAG = "GuestInfoCache"

    /** Application context — set once at startup via [initialize]. */
    private var appContext: Context? = null

    /** "$instanceId:$packageName" -> parsed PackageInfo (carries ApplicationInfo inside it). */
    private val packageInfos = ConcurrentHashMap<String, PackageInfo>()

    /** "$instanceId:$packageName" -> Resources built from the guest APK. */
    private val resourcesCache = ConcurrentHashMap<String, Resources>()

    /**
     * Bind the cache to the application context. Must be called once before [cache].
     */
    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            RenjanaLog.i(TAG, "GuestInfoCache initialized")
        }
    }

    /**
     * Parse the APK at [apkPath] and cache its PackageInfo / ApplicationInfo / Resources
     * under the compound key "$instanceId:$packageName". Safe to call multiple times
     * for the same (instance, package) pair (re-parses).
     *
     * @return true if the APK was parsed successfully.
     */
    fun cache(instanceId: String, packageName: String, apkPath: String): Boolean {
        val context = appContext
        if (context == null) {
            RenjanaLog.w(TAG, "cache() called before initialize(); skipping $instanceId:$packageName")
            return false
        }

        val key = "$instanceId:$packageName"
        return try {
            val pm = context.packageManager
            val fullFlags = PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PROVIDERS or
                    PackageManager.GET_PERMISSIONS or
                    PackageManager.GET_META_DATA or
                    PackageManager.GET_SIGNING_CERTIFICATES

            val pkgInfo = pm.getPackageArchiveInfo(apkPath, fullFlags)
            if (pkgInfo == null) {
                RenjanaLog.e(TAG, "getPackageArchiveInfo returned null for $apkPath")
                return false
            }

            // getPackageArchiveInfo leaves sourceDir / publicSourceDir pointing at the
            // archive path. Set the native library dir too so the guest can load .so files.
            val appInfo = pkgInfo.applicationInfo
            appInfo.sourceDir = apkPath
            appInfo.publicSourceDir = apkPath
            val nativeLibDir = java.io.File(apkPath).parentFile?.let { parent ->
                java.io.File(parent, "lib").takeIf { it.exists() }?.absolutePath
            }
            if (nativeLibDir != null) {
                appInfo.nativeLibraryDir = nativeLibDir
            }

            packageInfos[key] = pkgInfo

            // Build Resources from the APK.
            val res = createResourcesForApk(context, apkPath)
            if (res != null) {
                resourcesCache[key] = res
            }

            RenjanaLog.i(TAG, "Cached guest info for instance=$instanceId pkg=$packageName")
            true
        } catch (e: Throwable) {
            RenjanaLog.e(TAG, "Failed to cache guest info for $key: ${e.message}", e)
            false
        }
    }

    /** Cached ApplicationInfo for the (instance, package) pair, or null if not cached. */
    fun getApplicationInfo(instanceId: String, packageName: String): ApplicationInfo? {
        return packageInfos["$instanceId:$packageName"]?.applicationInfo
    }

    /** Backward-compat overload — looks up by instanceId only (returns first match). */
    fun getApplicationInfo(instanceId: String): ApplicationInfo? {
        return packageInfos.entries.firstOrNull { it.key.startsWith("$instanceId:") }?.value?.applicationInfo
    }

    /** Cached PackageInfo for the (instance, package) pair, or null if not cached. */
    fun getPackageInfo(instanceId: String, packageName: String): PackageInfo? {
        return packageInfos["$instanceId:$packageName"]
    }

    /** Backward-compat overload — looks up by instanceId only (returns first match). */
    fun getPackageInfo(instanceId: String): PackageInfo? {
        return packageInfos.entries.firstOrNull { it.key.startsWith("$instanceId:") }?.value
    }

    /** Cached Resources for the (instance, package) pair, or null if not cached. */
    fun getResources(instanceId: String, packageName: String): Resources? {
        return resourcesCache["$instanceId:$packageName"]
    }

    /** Backward-compat overload — looks up by instanceId only (returns first match). */
    fun getResources(instanceId: String): Resources? {
        return resourcesCache.entries.firstOrNull { it.key.startsWith("$instanceId:") }?.value
    }

    /** Drop everything cached for the (instance, package) pair. */
    fun clear(instanceId: String, packageName: String) {
        val key = "$instanceId:$packageName"
        packageInfos.remove(key)
        resourcesCache.remove(key)
        RenjanaLog.d(TAG, "Cleared guest info cache for instance=$instanceId pkg=$packageName")
    }

    /** Backward-compat overload — drops all entries for instanceId across all packages. */
    fun clear(instanceId: String) {
        val prefix = "$instanceId:"
        packageInfos.keys.filter { it.startsWith(prefix) }.forEach { packageInfos.remove(it) }
        resourcesCache.keys.filter { it.startsWith(prefix) }.forEach { resourcesCache.remove(it) }
        RenjanaLog.d(TAG, "Cleared guest info cache for all packages in instance=$instanceId")
    }

    /** Clear the entire cache (container shutdown). */
    fun clearAll() {
        packageInfos.clear()
        resourcesCache.clear()
        RenjanaLog.i(TAG, "GuestInfoCache cleared")
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /**
     * Build a [Resources] instance backed by [apkPath].
     *
     * Strategy (mirrors VirtualApp / BlackDex):
     *  1. Create a fresh [AssetManager] and call the hidden `addAssetPath(String)` on it.
     *  2. Pair it with the host's current DisplayMetrics + Configuration.
     *
     * Falls back to [PackageManager.getResourcesForApplication] if the reflection path
     * is blocked (some OEM ROMs harden the hidden API).
     */
    private fun createResourcesForApk(context: Context, apkPath: String): Resources? {
        // Reflection path first — the proven virtualization technique.
        try {
            val assetManager = AssetManager::class.java.getDeclaredConstructor().apply {
                isAccessible = true
            }.newInstance()
            val addAssetPath = AssetManager::class.java.getDeclaredMethod(
                "addAssetPath", String::class.java
            ).apply { isAccessible = true }
            val added = addAssetPath.invoke(assetManager, apkPath) as? Int ?: 0
            if (added == 0) {
                RenjanaLog.w(TAG, "addAssetPath returned 0 for $apkPath; trying fallback")
            } else {
                val hostRes = context.resources
                return Resources(
                    assetManager,
                    hostRes.displayMetrics,
                    hostRes.configuration
                )
            }
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "AssetManager reflection failed: ${e.message}; trying fallback")
        }

        // Fallback: let the system resolve Resources from a parsed ApplicationInfo.
        return try {
            val pkgInfo = context.packageManager.getPackageArchiveInfo(
                apkPath, 0
            )
            val ai = pkgInfo?.applicationInfo
            if (ai != null) {
                ai.sourceDir = apkPath
                ai.publicSourceDir = apkPath
                context.packageManager.getResourcesForApplication(ai)
            } else {
                null
            }
        } catch (e: Throwable) {
            RenjanaLog.e(TAG, "Resources fallback failed for $apkPath: ${e.message}")
            null
        }
    }
}
