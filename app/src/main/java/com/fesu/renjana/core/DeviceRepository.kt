package com.fesu.renjana.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val TAG = "DeviceRepository"

/**
 * Single entry point for device profiles.
 *
 * Priority chain: disk cache → tadiphone network → local DeviceDatabase
 *
 * Always returns a valid, non-empty list / profile — never throws to callers.
 */
class DeviceRepository(context: Context) {

    private val cache = TadiphoneCache(context)

    companion object {
        val SUPPORTED_BRANDS = listOf(
            "samsung", "xiaomi", "realme", "oppo", "vivo",
            "oneplus", "motorola", "nokia", "google", "sony",
            "asus", "poco", "infinix"
        )
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    fun getSupportedBrands(): List<String> = SUPPORTED_BRANDS

    /**
     * Cache-first lookup. Falls back to network, then local DB.
     * Always returns at least one profile.
     */
    suspend fun getProfilesForBrand(brand: String): List<DeviceProfile> {
        // 1. Disk cache
        val cached = withContext(Dispatchers.IO) { cache.loadProfiles(brand) }
        if (!cached.isNullOrEmpty()) {
            Log.d(TAG, "Cache hit for '$brand' (${cached.size} profiles)")
            return cached
        }

        // 2. Network
        val networkProfiles = fetchFromNetwork(brand)
        if (networkProfiles.isNotEmpty()) {
            withContext(Dispatchers.IO) { cache.saveProfiles(brand, networkProfiles) }
            return networkProfiles
        }

        // 3. Local DeviceDatabase — exact brand match
        val localExact = DeviceDatabase.profiles.filter {
            it.brand.equals(brand, ignoreCase = true)
        }
        if (localExact.isNotEmpty()) {
            Log.d(TAG, "Local DB fallback for '$brand' (${localExact.size} profiles)")
            return localExact
        }

        // 4. Last resort — return all local profiles
        Log.w(TAG, "No profiles found for '$brand', returning full local DB")
        return DeviceDatabase.profiles.ifEmpty {
            listOf(emergencyProfile())
        }
    }

    /** Random profile across all supported brands. Never returns null. */
    suspend fun getRandomProfile(): DeviceProfile {
        return try {
            val brand = SUPPORTED_BRANDS.random()
            val profiles = getProfilesForBrand(brand)
            profiles.random()
        } catch (e: Exception) {
            Log.e(TAG, "getRandomProfile failed: ${e.message}", e)
            DeviceDatabase.profiles.firstOrNull() ?: emergencyProfile()
        }
    }

    /** Deterministic profile from a seed. Never returns null. */
    suspend fun getProfileBySeed(seed: Long): DeviceProfile {
        return try {
            val brandIndex = (abs(seed) % SUPPORTED_BRANDS.size).toInt()
            val brand = SUPPORTED_BRANDS[brandIndex]
            val profiles = getProfilesForBrand(brand)
            val profileIndex = (abs(seed) % profiles.size).toInt()
            profiles[profileIndex]
        } catch (e: Exception) {
            Log.e(TAG, "getProfileBySeed($seed) failed: ${e.message}", e)
            DeviceDatabase.profiles.firstOrNull() ?: emergencyProfile()
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private suspend fun fetchFromNetwork(brand: String): List<DeviceProfile> {
        return try {
            val devices = TadiphoneClient.searchDevices(brand).take(10)
            if (devices.isEmpty()) {
                Log.d(TAG, "No devices from network for '$brand'")
                return emptyList()
            }
            val profiles = mutableListOf<DeviceProfile>()
            for (device in devices) {
                try {
                    val props = TadiphoneClient.fetchBuildProp(device)
                    TadiphoneClient.buildPropToProfile(props, device)?.let { profiles += it }
                } catch (e: Exception) {
                    Log.w(TAG, "Skipped device ${device.id}: ${e.message}")
                }
            }
            Log.d(TAG, "Network returned ${profiles.size} profiles for '$brand'")
            profiles
        } catch (e: Exception) {
            Log.e(TAG, "fetchFromNetwork($brand) failed: ${e.message}", e)
            emptyList()
        }
    }

    /** Absolute last-resort profile so we never return null. */
    private fun emergencyProfile() = DeviceProfile(
        brand = "samsung",
        manufacturer = "Samsung",
        model = "SM-G991B",
        androidVersion = "14",
        buildFingerprint = "samsung/o1sxxx/o1s:14/UP1A.231005.007/G991BXXS9EXC3:user/release-keys",
        imeiPrefixes = listOf("35386511"),
        displaySize = "6.2\"",
        resolution = "2340x1080",
        dpi = 421,
        ramGb = 8
    )
}
