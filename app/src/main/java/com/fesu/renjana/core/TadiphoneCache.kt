package com.fesu.renjana.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

private const val TAG = "TadiphoneCache"
private const val TTL_MS = 7L * 24 * 60 * 60 * 1000   // 7 days
private const val CACHE_DIR = "tadiphone_cache"

/**
 * File-based JSON cache for TadiphoneClient results.
 *
 * Files are stored at [context.filesDir]/tadiphone_cache/<brand>.json
 * Each file holds: { "timestamp": Long, "profiles": [ {...DeviceProfile fields...} ] }
 *
 * All methods are synchronous (no suspend). Callers must dispatch on IO.
 */
class TadiphoneCache(context: Context) {

    private val cacheDir: File = File(context.filesDir, CACHE_DIR).also { it.mkdirs() }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    fun saveProfiles(brand: String, profiles: List<DeviceProfile>) {
        try {
            val arr = JSONArray()
            for (p in profiles) arr.put(profileToJson(p))
            val root = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("profiles", arr)
            }
            cacheFile(brand).writeText(root.toString())
            Log.d(TAG, "Saved ${profiles.size} profiles for '$brand'")
        } catch (e: Exception) {
            Log.e(TAG, "saveProfiles($brand) failed: ${e.message}", e)
        }
    }

    /** Returns null if the cache entry doesn't exist or is older than TTL. */
    fun loadProfiles(brand: String): List<DeviceProfile>? {
        val file = cacheFile(brand)
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val timestamp = root.optLong("timestamp", 0L)
            if (System.currentTimeMillis() - timestamp > TTL_MS) {
                Log.d(TAG, "Cache expired for '$brand'")
                return null
            }
            val arr = root.optJSONArray("profiles") ?: return null
            val result = mutableListOf<DeviceProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                jsonToProfile(obj)?.let { result += it }
            }
            Log.d(TAG, "Loaded ${result.size} cached profiles for '$brand'")
            result.ifEmpty { null }
        } catch (e: FileNotFoundException) {
            null
        } catch (e: JSONException) {
            Log.e(TAG, "loadProfiles($brand) parse error: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "loadProfiles($brand) failed: ${e.message}", e)
            null
        }
    }

    /** True only if a non-expired cache file exists for [brand]. */
    fun isCached(brand: String): Boolean = loadProfiles(brand) != null

    /** Delete all cache files under the cache directory. */
    fun clearAll() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
            Log.d(TAG, "Cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "clearAll failed: ${e.message}", e)
        }
    }

    // -----------------------------------------------------------------------
    // Serialization helpers
    // -----------------------------------------------------------------------

    private fun profileToJson(p: DeviceProfile): JSONObject {
        val prefixes = JSONArray()
        p.imeiPrefixes.forEach { prefixes.put(it) }
        return JSONObject().apply {
            put("brand", p.brand)
            put("manufacturer", p.manufacturer)
            put("model", p.model)
            put("androidVersion", p.androidVersion)
            put("buildFingerprint", p.buildFingerprint)
            put("imeiPrefixes", prefixes)
            put("displaySize", p.displaySize)
            put("resolution", p.resolution)
            put("dpi", p.dpi)
            put("ramGb", p.ramGb)
        }
    }

    private fun jsonToProfile(obj: JSONObject): DeviceProfile? {
        return try {
            val brand = obj.optString("brand").takeIf { it.isNotBlank() } ?: return null
            val model = obj.optString("model").takeIf { it.isNotBlank() } ?: return null
            val prefixArr = obj.optJSONArray("imeiPrefixes")
            val prefixes = if (prefixArr != null) {
                (0 until prefixArr.length()).mapNotNull { prefixArr.optString(it).takeIf { s -> s.isNotBlank() } }
            } else listOf("86000000")

            DeviceProfile(
                brand = brand,
                manufacturer = obj.optString("manufacturer", brand),
                model = model,
                androidVersion = obj.optString("androidVersion", "14"),
                buildFingerprint = obj.optString("buildFingerprint", ""),
                imeiPrefixes = prefixes.ifEmpty { listOf("86000000") },
                displaySize = obj.optString("displaySize", "6.5\""),
                resolution = obj.optString("resolution", "1080x2400"),
                dpi = obj.optInt("dpi", 420),
                ramGb = obj.optInt("ramGb", 8)
            )
        } catch (e: Exception) {
            Log.e(TAG, "jsonToProfile failed: ${e.message}", e)
            null
        }
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private fun cacheFile(brand: String): File =
        File(cacheDir, "${brand.lowercase().replace(Regex("[^a-z0-9_]"), "_")}.json")
}
