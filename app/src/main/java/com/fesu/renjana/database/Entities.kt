package com.fesu.renjana.database

import androidx.room.*

@Entity(tableName = "instances")
data class InstanceEntity(
    @PrimaryKey val id: String,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val apkPath: String,
    val iconPath: String?,
    val accountId: String?,
    val dataPath: String,
    val createdAt: Long,
    val lastUsed: Long,
    val isActive: Boolean,
    // InstanceConfig fields (flattened for Room)
    val enableGms: Boolean = false,
    val enableFingerprint: Boolean = false,
    val fingerprintSeed: String? = null,
    val spoofSignature: Boolean = true,
    val isolateNetwork: Boolean = false,
    val enableAntiDetection: Boolean = true,
    // Device spoof values (null = auto-generate from seed)
    val spoofModel: String? = null,
    val spoofBrand: String? = null,
    val spoofManufacturer: String? = null,
    val spoofAndroidVersion: String? = null,
    val spoofAndroidId: String? = null,
    val spoofSerial: String? = null,
    // Extended fingerprint fields
    val canvasHash: String? = null,
    val canvasNoise: Float? = null,
    val screenDensityDpi: Int? = null,
    val screenWidthDp: Int? = null,
    val screenHeightDp: Int? = null,
    val screenRefreshRate: Float? = null,
    val sensorAccelerometer: Boolean? = null,
    val sensorGyroscope: Boolean? = null,
    val sensorMagnetometer: Boolean? = null,
    val sensorBarometer: Boolean? = null,
    val sensorProximity: Boolean? = null,
    val batteryCapacityMah: Int? = null,
    val wifiMacPrefix: String? = null,
)

@Entity(tableName = "google_accounts")
data class GoogleAccountEntity(
    @PrimaryKey val id: String,
    val email: String,
    val displayName: String,
    val photoUrl: String?,
    val idToken: String,
    val accessToken: String?,
    val refreshToken: String?,
    val tokenExpiryTime: Long,
    val createdAt: Long,
)
