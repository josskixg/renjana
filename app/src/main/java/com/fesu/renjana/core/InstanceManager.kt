package com.fesu.renjana.core

import android.content.Context
import com.fesu.renjana.database.InstanceDao
import com.fesu.renjana.database.InstanceEntity
import com.fesu.renjana.models.Instance
import com.fesu.renjana.models.InstanceConfig
import com.fesu.renjana.utils.RenjanaLog
import com.fesu.renjana.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class InstanceManager(
    private val context: Context,
    private val instanceDao: InstanceDao
) {
    companion object {
        private const val TAG = "InstanceManager"
    }

    fun getAllInstances(): Flow<List<Instance>> {
        return instanceDao.getAllInstances().map { entities ->
            entities.map { entityToInstance(it) }
        }
    }

    suspend fun getInstanceById(id: String): Instance? {
        return instanceDao.getInstanceById(id)?.let { entityToInstance(it) }
    }

    suspend fun createInstance(
        packageName: String,
        appName: String,
        versionName: String,
        versionCode: Int,
        apkPath: String,
        accountId: String?,
        config: InstanceConfig = InstanceConfig()
    ): Result<Instance> {
        return withContext(Dispatchers.IO) {
            try {
                val id = Utils.generateId()
                val dataPath = File(context.filesDir, "instances/$id").apply { mkdirs() }.absolutePath
                val now = System.currentTimeMillis()

                val instance = Instance(
                    id = id, packageName = packageName, appName = appName,
                    versionName = versionName, versionCode = versionCode,
                    apkPath = apkPath, iconPath = null, accountId = accountId,
                    dataPath = dataPath, createdAt = now, lastUsed = now,
                    isActive = false, config = config
                )
                instanceDao.insertInstance(instanceToEntity(instance))
                RenjanaLog.i(TAG, "Created instance: $id ($packageName)")
                Result.success(instance)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to create instance", e)
                Result.failure(e)
            }
        }
    }

    suspend fun deleteInstance(id: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val entity = instanceDao.getInstanceById(id) ?: return@withContext Result.failure(IllegalArgumentException("Instance not found"))
                instanceDao.deleteInstance(entity)
                File(entity.dataPath).deleteRecursively()
                RenjanaLog.i(TAG, "Deleted instance: $id")
                Result.success(Unit)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to delete instance", e)
                Result.failure(e)
            }
        }
    }

    suspend fun updateAppName(id: String, newName: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                instanceDao.updateAppName(id, newName)
                RenjanaLog.i(TAG, "Renamed instance $id to '$newName'")
                Result.success(Unit)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to rename instance", e)
                Result.failure(e)
            }
        }
    }

    suspend fun updateInstanceConfig(id: String, config: InstanceConfig): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                instanceDao.updateInstanceConfig(
                    id = id,
                    enableGms = config.enableGms,
                    enableFingerprint = config.enableFingerprint,
                    spoofSignature = config.spoofSignature,
                    enableAntiDetection = config.enableAntiDetection
                )
                RenjanaLog.i(TAG, "Updated config for instance $id")
                Result.success(Unit)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to update instance config", e)
                Result.failure(e)
            }
        }
    }

    suspend fun clearInstanceData(id: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val entity = instanceDao.getInstanceById(id) ?: return@withContext Result.failure(IllegalArgumentException("Instance not found"))
                val dataDir = File(entity.dataPath)
                if (dataDir.exists()) {
                    dataDir.deleteRecursively()
                    dataDir.mkdirs()
                }
                RenjanaLog.i(TAG, "Cleared data for instance $id")
                Result.success(Unit)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to clear instance data", e)
                Result.failure(e)
            }
        }
    }

    suspend fun updateInstanceUsage(id: String, timestamp: Long, isActive: Boolean) {
        withContext(Dispatchers.IO) {
            instanceDao.updateInstanceUsage(id, timestamp, isActive)
        }
    }

    suspend fun updateDeviceSpoof(
        id: String, model: String?, brand: String?, manufacturer: String?,
        androidVersion: String?, androidId: String?, serial: String?
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                instanceDao.updateDeviceSpoof(id, model, brand, manufacturer, androidVersion, androidId, serial)
                RenjanaLog.i(TAG, "Updated device spoof for instance $id")
                Result.success(Unit)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to update device spoof", e)
                Result.failure(e)
            }
        }
    }

    fun getInstanceCount(): Flow<Int> {
        return instanceDao.getInstanceCount()
    }

    suspend fun updateExtendedFingerprint(id: String, config: InstanceConfig): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                instanceDao.updateExtendedFingerprint(
                    id = id,
                    canvasHash = config.canvasHash,
                    canvasNoise = config.canvasNoise,
                    screenDensityDpi = config.screenDensityDpi,
                    screenWidthDp = config.screenWidthDp,
                    screenHeightDp = config.screenHeightDp,
                    screenRefreshRate = config.screenRefreshRate,
                    sensorAccelerometer = config.sensorAccelerometer,
                    sensorGyroscope = config.sensorGyroscope,
                    sensorMagnetometer = config.sensorMagnetometer,
                    sensorBarometer = config.sensorBarometer,
                    sensorProximity = config.sensorProximity,
                    batteryCapacityMah = config.batteryCapacityMah,
                    wifiMacPrefix = config.wifiMacPrefix,
                )
                RenjanaLog.i(TAG, "Updated extended fingerprint for instance $id")
                Result.success(Unit)
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to update extended fingerprint", e)
                Result.failure(e)
            }
        }
    }

    private fun entityToInstance(e: InstanceEntity): Instance {
        return Instance(
            id = e.id, packageName = e.packageName, appName = e.appName,
            versionName = e.versionName, versionCode = e.versionCode,
            apkPath = e.apkPath, iconPath = e.iconPath, accountId = e.accountId,
            dataPath = e.dataPath, createdAt = e.createdAt, lastUsed = e.lastUsed,
            isActive = e.isActive,
            config = InstanceConfig(
                enableGms = e.enableGms,
                enableFingerprint = e.enableFingerprint,
                fingerprintSeed = e.fingerprintSeed,
                spoofSignature = e.spoofSignature,
                isolateNetwork = e.isolateNetwork,
                enableAntiDetection = e.enableAntiDetection,
                spoofModel = e.spoofModel,
                spoofBrand = e.spoofBrand,
                spoofManufacturer = e.spoofManufacturer,
                spoofAndroidVersion = e.spoofAndroidVersion,
                spoofAndroidId = e.spoofAndroidId,
                spoofSerial = e.spoofSerial,
                canvasHash = e.canvasHash,
                canvasNoise = e.canvasNoise,
                screenDensityDpi = e.screenDensityDpi,
                screenWidthDp = e.screenWidthDp,
                screenHeightDp = e.screenHeightDp,
                screenRefreshRate = e.screenRefreshRate,
                sensorAccelerometer = e.sensorAccelerometer,
                sensorGyroscope = e.sensorGyroscope,
                sensorMagnetometer = e.sensorMagnetometer,
                sensorBarometer = e.sensorBarometer,
                sensorProximity = e.sensorProximity,
                batteryCapacityMah = e.batteryCapacityMah,
                wifiMacPrefix = e.wifiMacPrefix,
            )
        )
    }

    private fun instanceToEntity(i: Instance): InstanceEntity {
        return InstanceEntity(
            id = i.id, packageName = i.packageName, appName = i.appName,
            versionName = i.versionName, versionCode = i.versionCode,
            apkPath = i.apkPath, iconPath = i.iconPath, accountId = i.accountId,
            dataPath = i.dataPath, createdAt = i.createdAt, lastUsed = i.lastUsed,
            isActive = i.isActive,
            enableGms = i.config.enableGms,
            enableFingerprint = i.config.enableFingerprint,
            fingerprintSeed = i.config.fingerprintSeed,
            spoofSignature = i.config.spoofSignature,
            isolateNetwork = i.config.isolateNetwork,
            enableAntiDetection = i.config.enableAntiDetection,
            spoofModel = i.config.spoofModel,
            spoofBrand = i.config.spoofBrand,
            spoofManufacturer = i.config.spoofManufacturer,
            spoofAndroidVersion = i.config.spoofAndroidVersion,
            spoofAndroidId = i.config.spoofAndroidId,
            spoofSerial = i.config.spoofSerial,
            canvasHash = i.config.canvasHash,
            canvasNoise = i.config.canvasNoise,
            screenDensityDpi = i.config.screenDensityDpi,
            screenWidthDp = i.config.screenWidthDp,
            screenHeightDp = i.config.screenHeightDp,
            screenRefreshRate = i.config.screenRefreshRate,
            sensorAccelerometer = i.config.sensorAccelerometer,
            sensorGyroscope = i.config.sensorGyroscope,
            sensorMagnetometer = i.config.sensorMagnetometer,
            sensorBarometer = i.config.sensorBarometer,
            sensorProximity = i.config.sensorProximity,
            batteryCapacityMah = i.config.batteryCapacityMah,
            wifiMacPrefix = i.config.wifiMacPrefix,
        )
    }
}
