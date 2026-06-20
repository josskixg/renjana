package com.fesu.renjana.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "TadiphoneClient"
private const val BASE_URL = "https://dumps.tadiphone.dev/api/v4"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000

data class TadiphoneDevice(
    val id: Int,
    val brand: String,
    val model: String,
    val branch: String,
    val namespace: String
)

object TadiphoneClient {

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Search devices by brand name (e.g., "samsung", "xiaomi").
     * Hits GET /projects?search=<brand>&per_page=100 and filters by namespace.name.
     */
    suspend fun searchDevices(brand: String): List<TadiphoneDevice> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(brand, "UTF-8")
            val url = "$BASE_URL/projects?search=$encoded&per_page=100"
            val body = get(url) ?: return@withContext emptyList()
            parseProjectsArray(body, brand)
        } catch (e: Exception) {
            Log.e(TAG, "searchDevices($brand) failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Fetch raw build.prop for a device and return it as a key→value map.
     * Skips blank lines and comment lines starting with '#'.
     */
    suspend fun fetchBuildProp(device: TadiphoneDevice): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val encodedPath = "system%2Fsystem%2Fbuild.prop"
            val encodedBranch = URLEncoder.encode(device.branch, "UTF-8")
            val url = "$BASE_URL/projects/${device.id}/repository/files/$encodedPath/raw?ref=$encodedBranch"
            val body = get(url) ?: return@withContext emptyMap()
            parseBuildProp(body)
        } catch (e: Exception) {
            Log.e(TAG, "fetchBuildProp(${device.id}) failed: ${e.message}", e)
            emptyMap()
        }
    }

    /**
     * Convert a build.prop map + TadiphoneDevice metadata into a DeviceProfile.
     * Returns null if brand or model cannot be determined.
     */
    fun buildPropToProfile(props: Map<String, String>, device: TadiphoneDevice): DeviceProfile? {
        val brand = props["ro.product.system.brand"]
            ?: props["ro.product.brand"]
            ?: device.brand
        val model = props["ro.product.system.model"]
            ?: props["ro.product.model"]
            ?: device.model
        val manufacturer = props["ro.product.system.manufacturer"]
            ?: props["ro.product.manufacturer"]
            ?: brand
        val androidVersion = props["ro.system.build.version.release"]
            ?: props["ro.build.version.release"]
            ?: "14"
        val buildFingerprint = props["ro.system.build.fingerprint"]
            ?: props["ro.build.fingerprint"]
            ?: ""

        if (brand.isBlank() || model.isBlank()) return null

        // Reuse IMEI prefixes from the local DB when the brand matches
        val imeiPrefixes = DeviceDatabase.profiles
            .find { it.brand.equals(brand, ignoreCase = true) }
            ?.imeiPrefixes
            ?: listOf("86000000")

        val displaySize = props["ro.product.display_size"] ?: "6.5\""

        val densityStr = props["ro.sf.lcd_density"]
        val dpi = densityStr?.toIntOrNull() ?: 420
        // Derive a plausible resolution from density; real value not in build.prop
        val resolution = densityStr?.let { deriveResolution(dpi) } ?: "1080x2400"

        return DeviceProfile(
            brand = brand,
            manufacturer = manufacturer,
            model = model,
            androidVersion = androidVersion,
            buildFingerprint = buildFingerprint,
            imeiPrefixes = imeiPrefixes,
            displaySize = displaySize,
            resolution = resolution,
            dpi = dpi,
            ramGb = 8   // not available from build.prop; use sensible default
        )
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun get(urlString: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP $code for $urlString")
                return null
            }
            BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "GET $urlString => ${e.message}", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseProjectsArray(json: String, brand: String): List<TadiphoneDevice> {
        return try {
            val arr = JSONArray(json)
            val result = mutableListOf<TadiphoneDevice>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val namespace = obj.optJSONObject("namespace")
                val namespaceName = namespace?.optString("name", "") ?: ""
                // Filter: namespace.name must match the brand (case-insensitive)
                if (!namespaceName.equals(brand, ignoreCase = true)) continue

                val id = obj.optInt("id", -1)
                if (id == -1) continue
                val name = obj.optString("name", "")
                val pathWithNamespace = obj.optString("path_with_namespace", "")
                val defaultBranch = obj.optString("default_branch", "")

                result += TadiphoneDevice(
                    id = id,
                    brand = namespaceName,
                    model = name,
                    branch = defaultBranch,
                    namespace = pathWithNamespace
                )
            }
            result
        } catch (e: JSONException) {
            Log.e(TAG, "parseProjectsArray failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseBuildProp(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx <= 0) continue
            val key = trimmed.substring(0, eqIdx).trim()
            val value = trimmed.substring(eqIdx + 1).trim()
            if (key.isNotEmpty()) map[key] = value
        }
        return map
    }

    /** Very rough resolution guess from DPI bucket — build.prop doesn't carry it. */
    private fun deriveResolution(dpi: Int): String = when {
        dpi >= 560 -> "1440x3088"
        dpi >= 440 -> "1080x2400"
        dpi >= 400 -> "1080x2340"
        dpi >= 320 -> "1080x2160"
        else -> "720x1600"
    }
}
